#!/usr/bin/env python3
"""Real Android reminder recovery on one explicitly disposable emulator.

No Gradle invocation, fake system broadcasts, force-stop substitution, database clearing,
or production debug entry points. A separate test-APK Instrumentation seeds fixtures;
all event/notification observations occur in adb/system_server before read-only inspection.

Examples:
  python3 scripts/system-recovery-validation.py self-test
  python3 scripts/system-recovery-validation.py run --disposable --serial emulator-5554 \
    --apk app/build/outputs/apk/debug/app-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --source-commit FULL_SOURCE_SHA --output artifacts/system-recovery

The real run requires already-built APKs and exclusive use of the selected emulator;
never overlap it with run-device-tests.sh, UI tests, or another adb operator. A per-serial
host lock excludes other copies of this script, and setup refuses active instrumentation.
Evidence is named with source and both APK hashes.
Doze runs first; allow-while-idle quotas can require up to roughly nine minutes. Forced
Doze verifies real OS idle state, not natural overnight idle entry. SIGINT/SIGTERM trigger
best-effort cleanup; SIGKILL, host power loss, and unreachable devices cannot run finally.
The recorded recovery-state.json describes pending restoration if the host is interrupted.
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import uuid

REPO = Path(__file__).resolve().parent.parent
PACKAGE = "com.cutedog.app"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/com.cutedog.app.reminder.SystemRecoveryProbeRunner"
SCENARIOS = ("doze", "kill", "reboot", "timezone")
SHA = re.compile(r"[0-9a-f]{40}")
BOOT_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


class Blocked(RuntimeError):
    """The device or host cannot establish a required test precondition."""


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def source_state(expected):
    if not SHA.fullmatch(expected):
        raise ValueError("--source-commit must be a full lowercase source SHA")
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=REPO, text=True).strip()
    if git("rev-parse", "HEAD") != expected:
        raise ValueError("Declared source commit must equal this checkout HEAD")
    names = git("ls-files", "--cached", "--others", "--exclude-standard", "--", "app/src", "app/build.gradle.kts",
                "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle", "gradlew",
                "scripts/system-recovery-validation.py").splitlines()
    value = hashlib.sha256()
    for name in sorted(set(names)):
        path = REPO / name
        if path.is_file():
            value.update(name.encode()); value.update(b"\0"); value.update(bytes.fromhex(digest(path)))
    return {"sourceCommit": expected, "sourceDirty": bool(git("status", "--porcelain")),
            "sourceFilesSha256": value.hexdigest(),
            "sourceBindingNote": "APK-to-source mapping is supplied by the builder; hashes prove tested bytes, not reproducibility."}


def active_notification_records(text):
    """Exclude history, enqueued and snoozed records: they cannot prove a visible notification."""
    start = re.search(r"^\s*(?:Current )?Notification List:\s*$", text, re.MULTILINE)
    if not start:
        # AOSP omits the entire Notification List section when there are no active cards.
        # Require independent service-dump markers so a command error is not mistaken for emptiness.
        if "mArchive=" in text and "Ranking Config:" in text:
            return []
        raise Blocked("Unsupported or incomplete dumpsys notification output")
    body = text[start.end():]
    boundary = re.search(r"^\s*(?:Enqueued Notification List|Snoozed Notification List|Snoozed notifications|"
                         r"Notification listeners|Notification assistants|Ranking Config|Zen Mode|"
                         r"Notification history|Historical notifications|Archive|Toast Queue):", body, re.MULTILINE)
    if boundary:
        body = body[:boundary.start()]
    return re.findall(r"^\s*NotificationRecord\([^\n]*(?:\n(?!\s*NotificationRecord\()[^\n]*)*", body, re.MULTILINE)


def find_fixture_notification(text, seed):
    matches = []
    for record in active_notification_records(text):
        header = record.lstrip().splitlines()[0]
        if (re.search(r"\bpkg=" + re.escape(PACKAGE) + r"\b", header)
                and re.search(r"\bid=1\b", header)
                and re.search(r"\btag=" + str(seed["reminderId"]) + r"(?:\s|\b)", header)
                and seed["title"] in record):
            matches.append(record)
    if len(matches) > 1:
        raise AssertionError("More than one active notification matches the fixture identity")
    return matches[0] if matches else None


def temporary_idle_uids(text):
    # API26/35 print UID= entries in `dumpsys deviceidle tempwhitelist`.
    return {int(value) for value in re.findall(r"\bUID=(\d+)\b", text)}


def exact_appop(text):
    mode = re.search(r"SCHEDULE_EXACT_ALARM:\s*(allow|ignore|deny|default|foreground)", text)
    if mode: return mode[1]
    if "No operations" in text: return "default"
    raise Blocked("Cannot parse actual SCHEDULE_EXACT_ALARM app-op state")


def application_uid_from_outputs(package_dump, run_as_uid):
    """Cross-check a real user-0 run-as UID against only the requested package's block."""
    numeric = run_as_uid.strip()
    if not re.fullmatch(r"[0-9]+", numeric):
        raise Blocked("run-as id -u did not return one strictly numeric application UID")
    uid = int(numeric)
    if not 10000 <= uid < 100000:
        raise Blocked("run-as UID is not a user-0 application UID")
    header = list(re.finditer(r"^([ \t]*)Package \[" + re.escape(PACKAGE)
                             + r"\] \([^\n]+\):[ \t]*$", package_dump, re.MULTILINE))
    if len(header) != 1:
        raise Blocked("Cannot identify exactly one target Package block for UID verification")
    match = header[0]
    indent = match[1]
    lines = []
    for line in package_dump[match.end():].splitlines():
        if line.strip() and len(line) - len(line.lstrip(" \t")) <= len(indent):
            break
        lines.append(line)
    block = "\n".join(lines)
    if not re.search(r"^\s*User 0:[^\n]*\binstalled=true\b", block, re.MULTILINE):
        raise Blocked("Target package is not confirmed installed for user 0")
    fields = re.findall(r"^" + re.escape(indent + "  ") + r"(userId|appId)=(\d+)[ \t]*$",
                        block, re.MULTILINE)
    if not fields or {int(value) for _, value in fields} != {uid}:
        raise Blocked("Target package userId/appId is missing, conflicting, or differs from run-as UID")
    return {"uid": uid, "user": 0, "package": PACKAGE, "runAsCommand": "run-as com.cutedog.app id -u",
            "packageFields": [{"field": name, "value": int(value)} for name, value in fields],
            "crossChecked": True}


def owner_user_unlocked(text):
    if re.search(r"(?:\{|,|\s)0=RUNNING_UNLOCKED(?:,|\}|\s)", text):
        return True
    owner = re.search(r"UserInfo\{0:[\s\S]*?(?=UserInfo\{|\Z)", text)
    return bool(owner and re.search(r"State:\s*RUNNING_UNLOCKED\b", owner[0]))


def probe_result(text):
    matches = re.findall(r"^INSTRUMENTATION_RESULT: recovery=(.+)$", text, re.MULTILINE)
    if len(matches) != 1 or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", text, re.MULTILINE):
        raise RuntimeError("Test-only recovery runner did not finish successfully; inspect raw instrumentation output")
    result = json.loads(matches[0])
    if result.get("status") != "ok":
        raise RuntimeError("Recovery probe reported an error")
    return result


def check_inspection(result, seed):
    if result.get("readOnly") is not True or result.get("fixture") != seed:
        raise AssertionError("Read-only probe fixture differs from the host-observed seed")
    item = result.get("item")
    if not item or any((item.get("id") != seed["reminderId"], item.get("dueAt") != seed["dueAt"],
                        item.get("timeZoneId") != seed["timeZoneId"], item.get("title") != seed["title"],
                        item.get("completed") is not False)):
        raise AssertionError("Persisted reminder changed or disappeared during recovery")
    records = result.get("deliveries", [])
    if len(records) != 1:
        raise AssertionError("Expected exactly one durable delivery occurrence")
    record = records[0]
    expected = {"reminderId": seed["reminderId"], "token": "due:" + str(seed["dueAt"]),
                "status": "sent", "attempts": 1, "dueAt": seed["dueAt"], "isSnooze": False}
    if any(record.get(key) != value for key, value in expected.items()):
        raise AssertionError("Durable delivery is not one successfully sent original occurrence")


class Host:
    def __init__(self, args, root, metadata):
        self.args, self.root, self.metadata = args, root, metadata
        self.raw = root / "setup"
        self.counter = 0
        self.restore = {}
        self.probe_processes = []
        self.current_fixture = None
        self.command_log = root / "commands.jsonl"
        self.summary_path = root / "summary.json"

    def save(self):
        write_json(self.summary_path, self.metadata)
        write_json(self.root / "recovery-state.json", {"device": self.args.serial, "restore": self.restore,
                    "fixture": self.current_fixture, "recordedAt": now(),
                    "note": "If host finally could not run, use the recorded original state to restore this disposable emulator."})

    def execute(self, argv, label, timeout=30, check=True):
        self.raw.mkdir(parents=True, exist_ok=True)
        self.counter += 1
        base = self.raw / f"{self.counter:05d}-{label}"
        start = now()
        try:
            result = subprocess.run([str(part) for part in argv], capture_output=True, text=True, timeout=timeout)
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            stdout = error.stdout or ""; stderr = error.stderr or ""
            if isinstance(stdout, bytes): stdout = stdout.decode(errors="replace")
            if isinstance(stderr, bytes): stderr = stderr.decode(errors="replace")
            stderr += "\nHOST_COMMAND_TIMEOUT\n"; code = 124
        base.with_suffix(".stdout.txt").write_text(stdout)
        base.with_suffix(".stderr.txt").write_text(stderr)
        with self.command_log.open("a") as stream:
            stream.write(json.dumps({"at": start, "argv": [str(part) for part in argv], "exitCode": code,
                                     "stdout": str(base.with_suffix('.stdout.txt').relative_to(self.root)),
                                     "stderr": str(base.with_suffix('.stderr.txt').relative_to(self.root))}) + "\n")
        if check and code:
            raise RuntimeError(f"{label} failed with exit {code}; see {base.relative_to(self.root)}.*.txt")
        return subprocess.CompletedProcess(argv, code, stdout, stderr)

    def adb(self, *args, label="adb", timeout=30, check=True):
        return self.execute([self.args.adb, "-s", self.args.serial, *args], label, timeout, check)

    def shell(self, *args, label="shell", timeout=30, check=True):
        return self.adb("shell", shlex.join([str(part) for part in args]), label=label, timeout=timeout, check=check)

    def snapshot(self, name):
        for label, command in (("notifications", ("dumpsys", "notification", "--noredact")),
                               ("alarms", ("dumpsys", "alarm")), ("processes", ("dumpsys", "activity", "processes")),
                               ("pending-intents", ("dumpsys", "activity", "intents")),
                               ("deviceidle", ("dumpsys", "deviceidle")), ("package", ("dumpsys", "package", PACKAGE)),
                               ("boot-id", ("cat", "/proc/sys/kernel/random/boot_id")),
                               ("uptime", ("cat", "/proc/uptime"))):
            self.shell(*command, label=name + "-" + label, timeout=15, check=False)
        self.adb("logcat", "-d", "-v", "threadtime", "-s", "CuteDogReminders:V", "AndroidRuntime:E",
                 "ActivityManager:I", "AlarmManager:I", "BroadcastQueue:I", "*:S", label=name + "-logcat", check=False)

    def probe_arguments(self, operation, scenario, run_id):
        values = {"recoveryOperation": operation, "recoveryScenario": scenario, "recoveryRunId": run_id,
                  "recoverySourceCommit": self.metadata["sourceCommit"],
                  "recoveryApplicationSha256": self.metadata["apks"]["application"]["sha256"],
                  "recoveryInstrumentationSha256": self.metadata["apks"]["instrumentation"]["sha256"],
                  "recoveryDelayMillis": str(self.args.seed_delay * 1000)}
        command = ["am", "instrument", "-w", "-r"]
        for key, value in values.items(): command.extend(["-e", key, value])
        return command + [RUNNER]

    def probe(self, operation, scenario, run_id):
        result = self.shell(*self.probe_arguments(operation, scenario, run_id), label="probe-" + operation, timeout=60)
        return probe_result(result.stdout)

    def seed(self, scenario, run_id):
        self.current_fixture = {"scenario": scenario, "runId": run_id}
        self.save()
        if scenario != "kill":
            self.probe("seed", scenario, run_id)
        else:
            # A ready file is the synchronization point; this process is expected to be SIGKILLed.
            output = (self.raw / "seed-held-instrumentation.stdout.txt").open("w")
            errors = (self.raw / "seed-held-instrumentation.stderr.txt").open("w")
            command = [self.args.adb, "-s", self.args.serial, "shell", shlex.join(self.probe_arguments("seed", scenario, run_id))]
            process = subprocess.Popen(command, stdout=output, stderr=errors, text=True)
            self.probe_processes.append((process, output, errors))
            with self.command_log.open("a") as stream:
                stream.write(json.dumps({"at": now(), "argv": command, "expectedTermination": "host SIGKILL of target app PID",
                                         "stdout": str((self.raw / 'seed-held-instrumentation.stdout.txt').relative_to(self.root))}) + "\n")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            result = self.shell("run-as", PACKAGE, "cat", f"files/system-recovery/{run_id}.json", label="seed-ready", check=False)
            if result.returncode == 0:
                seed = json.loads(result.stdout)
                if (seed.get("runId") != run_id or seed.get("scenario") != scenario
                        or seed.get("sourceCommit") != self.metadata["sourceCommit"]
                        or seed.get("applicationSha256") != self.metadata["apks"]["application"]["sha256"]
                        or seed.get("instrumentationSha256") != self.metadata["apks"]["instrumentation"]["sha256"]):
                    raise AssertionError("Ready fixture provenance differs from this run")
                write_json(self.raw.parent / "seed.json", seed)
                self.current_fixture["seed"] = seed; self.save()
                return seed
            time.sleep(0.5)
        raise RuntimeError("Test probe did not publish a ready fixture")

    def assert_live_window(self, seed):
        if seed["scheduled"]:
            device_seconds = self.shell("date", "+%s", label="event-device-time").stdout.strip()
            if int(device_seconds) * 1000 + 5000 >= seed["dueAt"]:
                raise Blocked("Fixture deadline arrived before the real system event; increase --seed-delay")

    def assert_no_fixture_notification(self, seed, label):
        text = self.shell("dumpsys", "notification", "--noredact", label=label).stdout
        if find_fixture_notification(text, seed):
            raise Blocked("Fixture notification already exists before the observation window; event attribution is invalid")

    def change_timezone(self, zone, label):
        command = self.restore.get("timezoneSetter")
        if not command:
            raise Blocked("Device exposes no supported real timezone setter")
        args = ["cmd", "alarm", "set-timezone", zone] if command == "alarm" else [
            "cmd", "time_zone_detector", "suggest_manual_time_zone", "--zone_id", zone]
        result = self.shell(*args, label=label, check=False)
        if result.returncode:
            raise Blocked("System timezone setter was denied")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            actual = self.shell("getprop", "persist.sys.timezone", label=label + "-observed").stdout.strip()
            if actual == zone: return
            time.sleep(0.5)
        raise Blocked("System timezone did not actually change")

    def action(self, scenario, seed):
        self.assert_live_window(seed)
        self.assert_no_fixture_notification(seed, "before-event-no-fixture-notification")
        if scenario == "kill":
            pid = seed["pid"]
            pids = self.shell("pidof", PACKAGE, label="kill-before-pids").stdout.split()
            if str(pid) not in pids:
                raise Blocked("Ready probe PID is no longer the actual application process")
            before = self.shell("run-as", PACKAGE, "cat", f"/proc/{pid}/stat", label="kill-before-proc-stat").stdout
            result = self.shell("run-as", PACKAGE, "kill", "-9", str(pid), label="actual-sigkill", check=False)
            if result.returncode:
                raise Blocked("Same-UID SIGKILL is unavailable; force-stop is deliberately not a substitute")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                pids = self.shell("pidof", PACKAGE, label="kill-after-pids", check=False).stdout.split()
                if str(pid) not in pids: break
                time.sleep(0.2)
            else: raise AssertionError("Old application PID survived SIGKILL")
            package = self.shell("dumpsys", "package", PACKAGE, label="kill-stopped-state").stdout
            if not re.search(r"User 0:[^\n]*stopped=false", package):
                raise Blocked("Package is stopped; this would not be a process-death recovery test")
            self.assert_no_fixture_notification(seed, "after-kill-before-deadline-no-notification")
            self.assert_live_window(seed)
            return {"method": "same-UID SIGKILL", "killedPid": pid, "priorProcStat": before.strip(),
                    "packageStopped": False, "eventCompletedBeforeDeadline": True}
        if scenario == "reboot":
            previous = self.shell("cat", "/proc/sys/kernel/random/boot_id", label="before-real-reboot").stdout.strip()
            if not BOOT_ID.fullmatch(previous): raise Blocked("Cannot record a valid kernel boot ID before reboot")
            self.adb("reboot", label="actual-adb-reboot")
            deadline = time.monotonic() + self.args.reboot_timeout
            observed_reboot = False
            while time.monotonic() < deadline:
                state = self.adb("get-state", label="reboot-adb-state", timeout=10, check=False)
                if state.returncode == 0 and state.stdout.strip() == "device":
                    boot = self.shell("getprop", "sys.boot_completed", label="reboot-boot-completed", check=False).stdout.strip()
                    current = self.shell("cat", "/proc/sys/kernel/random/boot_id", label="reboot-new-boot-id", check=False).stdout.strip()
                    if boot == "1" and BOOT_ID.fullmatch(current) and current != previous:
                        observed_reboot = True
                        user = self.shell("dumpsys", "user", label="reboot-user-state").stdout
                        # Android 15 can report boot_completed during RUNNING_UNLOCKING.
                        if owner_user_unlocked(user):
                            return {"method": "adb reboot", "beforeBootId": previous, "afterBootId": current,
                                    "bootCompleted": True, "userUnlocked": True}
                time.sleep(2)
            if observed_reboot:
                raise Blocked("Real reboot completed but owner storage did not unlock before the deadline")
            raise Blocked("Real reboot did not complete with a changed kernel boot ID within the deadline")
        if scenario == "doze":
            idle = self.shell("dumpsys", "deviceidle", label="doze-before-state").stdout
            whitelist = self.shell("dumpsys", "deviceidle", "whitelist", label="doze-whitelist").stdout
            if re.search(r"(?:^|,)" + re.escape(PACKAGE) + r"(?:,|$)", whitelist, re.MULTILINE):
                raise Blocked("Application is exempted from Doze by a permanent whitelist")
            uid = self.metadata["applicationUid"] % 100000
            temporary = self.shell("dumpsys", "deviceidle", "tempwhitelist", label="doze-temporary-whitelist").stdout
            if "Unknown command" in temporary or "Error:" in temporary:
                raise Blocked("Cannot inspect actual temporary Doze exemptions")
            if uid in temporary_idle_uids(temporary):
                raise Blocked("Application still has a temporary idle exemption; retry after it expires")
            self.restore["dozeChanged"] = True; self.save()
            self.shell("dumpsys", "battery", "unplug", label="actual-battery-unplug")
            self.shell("input", "keyevent", "KEYCODE_SLEEP", label="actual-screen-sleep")
            self.shell("dumpsys", "deviceidle", "force-idle", "deep", label="actual-force-deep-idle", check=False)
            deep = self.shell("dumpsys", "deviceidle", "get", "deep", label="doze-deep-state").stdout.strip()
            if deep != "IDLE": raise Blocked("System did not actually enter deep IDLE")
            self.assert_no_fixture_notification(seed, "entered-idle-before-deadline-no-notification")
            self.assert_live_window(seed)
            return {"method": "framework forced deep Doze", "deepState": deep, "permanentWhitelist": False,
                    "eventCompletedBeforeDeadline": True}
        # No alarm was scheduled for this overdue fixture. The real timezone receiver must restore it.
        if seed["scheduled"]: raise AssertionError("Timezone fixture must not have an existing alarm")
        intents = self.shell("dumpsys", "activity", "intents", label="timezone-before-intents").stdout
        if f"cutedog://reminder/{seed['reminderId']}/FIRE" in intents:
            raise AssertionError("Timezone fixture unexpectedly has a pending alarm intent before timezone change")
        actual_before = self.shell("getprop", "persist.sys.timezone", label="timezone-immediately-before-setter").stdout.strip()
        if not actual_before: raise Blocked("Cannot establish the timezone immediately before the real setter")
        selected = "Etc/UTC" if actual_before != "Etc/UTC" else "Asia/Tokyo"
        self.assert_no_fixture_notification(seed, "immediately-before-timezone-setter-no-notification")
        self.change_timezone(selected, "actual-system-timezone-change")
        return {"method": "system timezone service setter", "beforeTimeZone": actual_before, "afterTimeZone": selected,
                "fixtureHadAlarm": False, "fixtureWasOverdue": True}

    def observe(self, scenario, seed):
        deadline = time.monotonic() + self.args.delivery_timeout
        next_progress = time.monotonic()
        while time.monotonic() < deadline:
            result = self.shell("dumpsys", "notification", "--noredact", label="observe-active-notifications")
            found = find_fixture_notification(result.stdout, seed)
            if found:
                # The success evidence is frozen BEFORE any inspection or restoration can start the app.
                observed = {"at": now(), "fixtureNotification": found, "observedBeforeProbeRestart": True}
                if scenario == "doze":
                    state = self.shell("dumpsys", "deviceidle", "get", "deep", label="delivery-deep-state").stdout.strip()
                    if state != "IDLE": raise AssertionError("Notification appeared only after leaving deep Doze")
                    observed["deepStateAtDelivery"] = state
                write_json(self.raw.parent / "system-observation.json", observed)
                return observed
            if time.monotonic() >= next_progress:
                print(f"{scenario}: waiting for the fixture's actual system notification", flush=True)
                next_progress = time.monotonic() + 30
            time.sleep(2)
        raise AssertionError("No matching active system notification appeared within the declared observation window")

    def verify_installed(self, package, expected):
        locations = self.shell("pm", "path", package, label="installed-" + package).stdout.splitlines()
        paths = [line.removeprefix("package:").strip() for line in locations if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].endswith("/base.apk"):
            raise Blocked("Expected exactly one installed base APK, without splits: " + package)
        result = self.shell("sha256sum", paths[0], label="installed-sha256-" + package).stdout.split()
        if not result or result[0] != expected:
            raise AssertionError("Installed APK bytes differ from the supplied file: " + package)
        return {"devicePath": paths[0], "sha256": result[0]}

    def setup(self):
        if not self.args.disposable or not re.fullmatch(r"emulator-\d+", self.args.serial):
            raise Blocked("Explicit --disposable and an emulator-N serial are required; physical devices are refused")
        if self.shell("getprop", "ro.kernel.qemu", label="emulator-identity").stdout.strip() != "1":
            raise Blocked("Selected device is not an Android emulator")
        if self.shell("am", "get-current-user", label="current-user").stdout.strip() != "0":
            raise Blocked("This fixture supports only the unlocked owner user 0")
        self.metadata["api"] = int(self.shell("getprop", "ro.build.version.sdk", label="api").stdout.strip())
        activities = self.shell("dumpsys", "activity", "activities", label="initial-activities").stdout
        if re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*com\.cutedog\.app", activities):
            raise Blocked("Application UI is currently resumed; use an idle disposable emulator")
        instrumentations = self.shell("dumpsys", "activity", "instrumentation", label="initial-instrumentation").stdout
        if "Active instrumentation:" in instrumentations and re.search(r"InstrumentationRecord\{", instrumentations):
            raise Blocked("Another instrumentation run is active on this emulator")
        services = self.shell("dumpsys", "activity", "services", PACKAGE, label="initial-services").stdout
        if "OverlayService" in services:
            raise Blocked("Stop the overlay through its normal UI before isolating this system recovery run")
        for label, path in (("application", self.args.apk), ("instrumentation", self.args.test_apk)):
            self.adb("install", "-r", "-t", path, label="install-" + label, timeout=60)
            package = PACKAGE if label == "application" else TEST_PACKAGE
            self.metadata["apks"][label]["installed"] = self.verify_installed(package, self.metadata["apks"][label]["sha256"])
        registered = self.shell("pm", "list", "instrumentation", label="installed-runners").stdout
        if RUNNER not in registered:
            raise Blocked("Installed test APK does not register the standalone SystemRecoveryProbeRunner")
        run_as = self.shell("run-as", PACKAGE, "id", "-u", label="probe-run-as-uid", check=False)
        if run_as.returncode:
            raise Blocked("The application must be debuggable so same-UID probe files and SIGKILL are available")
        package = self.shell("dumpsys", "package", PACKAGE, label="initial-package").stdout
        uid = application_uid_from_outputs(package, run_as.stdout)
        self.metadata["applicationUid"] = uid["uid"]
        self.metadata["applicationUidVerification"] = uid
        write_json(self.raw / "application-uid-verification.json", uid)
        self.restore["notificationGranted"] = bool(re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=true", package))
        exact = self.shell("cmd", "appops", "get", PACKAGE, "SCHEDULE_EXACT_ALARM", label="initial-exact-appop", check=False).stdout
        self.restore["exactAlarmAppOp"] = exact_appop(exact) if self.metadata["api"] >= 31 else "not_applicable"
        self.restore["timezone"] = self.shell("getprop", "persist.sys.timezone", label="initial-timezone").stdout.strip()
        if not self.restore["timezone"]:
            raise Blocked("Cannot record the emulator's original timezone for reliable restoration")
        self.restore["autoTimeZone"] = self.shell("settings", "get", "global", "auto_time_zone", label="initial-auto-timezone").stdout.strip()
        self.restore["initialBattery"] = self.shell("dumpsys", "battery", label="initial-battery").stdout
        idle = self.shell("dumpsys", "deviceidle", label="initial-idle").stdout
        if "UPDATES STOPPED" in self.restore["initialBattery"] or "mForceIdle=true" in idle:
            raise Blocked("Emulator already has simulated battery/forced-idle state; refusing to overwrite it")
        power = self.shell("dumpsys", "power", label="initial-power").stdout
        if not re.search(r"mWakefulness=(?:Awake|Asleep|Dozing|Dreaming)\b", power):
            raise Blocked("Cannot record the emulator's current screen wakefulness")
        self.restore["screenAwake"] = "mWakefulness=Awake" in power
        alarm_help = self.shell("cmd", "alarm", "help", label="alarm-service-help", check=False).stdout
        detector_help = self.shell("cmd", "time_zone_detector", "help", label="timezone-service-help", check=False).stdout
        self.restore["timezoneSetter"] = ("alarm" if "set-timezone" in alarm_help else
                                            "detector" if "suggest_manual_time_zone" in detector_help else None)
        self.restore["permissionsChanged"] = True; self.save()
        if self.metadata["api"] >= 33:
            self.shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", label="grant-notification")
        if self.metadata["api"] >= 31:
            self.shell("cmd", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow", label="allow-exact-alarms")
        self.save()

    def restore_device(self):
        """Runs after measurement is frozen; no cleanup-triggered delivery can change its result."""
        errors = []
        def attempt(name, operation):
            try: operation()
            except Exception as error: errors.append({"operation": name, "error": str(error)})
        if self.restore.get("dozeChanged"):
            attempt("unforce-doze", lambda: self.shell("dumpsys", "deviceidle", "unforce", label="restore-unforce-doze"))
            attempt("reset-battery", lambda: self.shell("dumpsys", "battery", "reset", label="restore-battery"))
            attempt("restore-screen", lambda: self.shell("input", "keyevent", "KEYCODE_WAKEUP" if self.restore.get("screenAwake") else "KEYCODE_SLEEP", label="restore-screen"))
            def verify_idle_battery():
                idle = self.shell("dumpsys", "deviceidle", label="verify-restored-idle").stdout
                battery = self.shell("dumpsys", "battery", label="verify-restored-battery").stdout
                if ("mForceIdle=false" not in idle or "UPDATES STOPPED" in battery
                        or not re.search(r"^\s*level:\s*\d+", battery, re.MULTILINE)):
                    raise RuntimeError("Forced Doze or simulated battery state remains active after restoration")
            attempt("verify-idle-battery-restoration", verify_idle_battery)
            self.restore["dozeChanged"] = bool(errors)
        # Remove this fixture before restoring timezone; that restoration emits another real system event.
        if self.current_fixture:
            fixture = self.current_fixture
            def clean_fixture():
                result = self.probe("cleanup", fixture["scenario"], fixture["runId"])
                if result.get("fixtureFileRemoved") is not True:
                    raise RuntimeError("Probe did not confirm removal of its own fixture metadata")
                self.current_fixture = None
            attempt("delete-only-fixture", clean_fixture)
        if self.restore.get("timezoneChanged"):
            before_errors = len(errors)
            attempt("restore-timezone", lambda: self.change_timezone(self.restore["timezone"], "restore-original-timezone"))
            if self.restore["autoTimeZone"] == "null":
                attempt("restore-auto-timezone", lambda: self.shell("settings", "delete", "global", "auto_time_zone", label="restore-auto-timezone"))
            else:
                attempt("restore-auto-timezone", lambda: self.shell("settings", "put", "global", "auto_time_zone", self.restore["autoTimeZone"], label="restore-auto-timezone"))
            def verify_auto_zone():
                actual = self.shell("settings", "get", "global", "auto_time_zone", label="verify-restored-auto-timezone").stdout.strip()
                if actual != self.restore["autoTimeZone"]:
                    raise RuntimeError("Automatic timezone setting differs from its original state")
            attempt("verify-auto-timezone-restoration", verify_auto_zone)
            self.restore["timezoneChanged"] = len(errors) != before_errors
        self.save()
        return errors

    def restore_permissions(self):
        errors = []
        if self.restore.get("permissionsChanged"):
            commands = []
            if self.metadata.get("api", 0) >= 33:
                commands.append(("pm", "grant" if self.restore["notificationGranted"] else "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS"))
            if self.metadata.get("api", 0) >= 31:
                commands.append(("cmd", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", self.restore["exactAlarmAppOp"]))
            for command in commands:
                try: self.shell(*command, label="restore-permission")
                except Exception as error: errors.append(str(error))
            try:
                if self.metadata.get("api", 0) >= 33:
                    package = self.shell("dumpsys", "package", PACKAGE, label="verify-restored-notification-permission").stdout
                    granted = bool(re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=true", package))
                    if granted != self.restore["notificationGranted"]:
                        raise RuntimeError("Notification permission was not restored to its original state")
                if self.metadata.get("api", 0) >= 31:
                    actual = self.shell("cmd", "appops", "get", PACKAGE, "SCHEDULE_EXACT_ALARM", label="verify-restored-exact-appop").stdout
                    if exact_appop(actual) != self.restore["exactAlarmAppOp"]:
                        raise RuntimeError("Exact alarm app-op was not restored to its original state")
            except Exception as error:
                errors.append(str(error))
            self.restore["permissionsChanged"] = bool(errors)
        for process, output, errors_stream in self.probe_processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
            output.close(); errors_stream.close()
        self.save()
        return errors

    def run_scenario(self, scenario):
        result = self.metadata["scenarios"][scenario]
        result.update(status="running", startedAt=now(), runId=uuid.uuid4().hex)
        self.raw = self.root / scenario / "raw"
        self.raw.mkdir(parents=True, exist_ok=True)
        self.save()
        try:
            if scenario == "timezone":
                if not self.restore.get("timezoneSetter"):
                    raise Blocked("This Android image exposes no supported real timezone setter")
                # Configure automatic detection before the fixture exists; its configuration
                # events must not be mistaken for the explicit timezone setter under test.
                self.restore["timezoneChanged"] = True; self.save()
                self.shell("settings", "put", "global", "auto_time_zone", "0", label="timezone-disable-auto-before-seed")
                if self.shell("settings", "get", "global", "auto_time_zone", label="timezone-auto-disabled").stdout.strip() != "0":
                    raise Blocked("Cannot disable automatic timezone detection before seeding")
            seed = self.seed(scenario, result["runId"])
            self.snapshot("before-event")
            result["action"] = self.action(scenario, seed)
            result["observation"] = self.observe(scenario, seed)
            self.snapshot("observed-before-probe-restart")
            # Required evidence is already saved. This re-entry may disturb system notifications/alarms.
            inspection = self.probe("inspect", scenario, result["runId"])
            write_json(self.raw.parent / "database-inspection.json", inspection)
            check_inspection(inspection, seed)
            result.update(status="passed", passedAt=now())
        except InterruptedError as error:
            result.update(status="failed", reason=str(error))
            self.snapshot("interrupted-before-cleanup")
            raise
        except Blocked as error:
            result.update(status="blocked", reason=str(error))
            self.snapshot("blocked-before-cleanup")
        except Exception as error:
            result.update(status="failed", reason=f"{error.__class__.__name__}: {error}")
            self.snapshot("failed-before-cleanup")
        finally:
            result["measurementEndedAt"] = now()
            self.save()
            result["cleanupErrors"] = self.restore_device()
            result["finishedAt"] = now()
            self.save()
        print(f"{scenario}: {result['status']}", flush=True)
        if result["cleanupErrors"]:
            raise RuntimeError("Device/fixture cleanup failed; remaining scenarios will not run")


def run(args):
    if not args.disposable or not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Only an explicitly --disposable emulator-N is supported")
    metadata = source_state(args.source_commit)
    apks = {}
    for name, path in (("application", args.apk), ("instrumentation", args.test_apk)):
        if not path.is_file() or path.stat().st_size == 0: raise ValueError("Missing APK: " + str(path))
        apks[name] = {"path": str(path.resolve()), "sha256": digest(path), "bytes": path.stat().st_size}
    identity = f"apk-{apks['application']['sha256']}-test-{apks['instrumentation']['sha256']}"
    root = args.output / ("source-" + args.source_commit) / identity / ("run-" + uuid.uuid4().hex)
    root.mkdir(parents=True, exist_ok=False)
    metadata.update(schemaVersion=1, startedAt=now(), device=args.serial, disposableEmulator=True, apks=apks,
                    overallStatus="not_run", scenarios={name: {"status": "not_run"} for name in args.scenarios},
                    scope="Real process SIGKILL, kernel reboot, forced framework Doze and real timezone change; force-stop and natural idle entry are not covered.")
    host = Host(args, root, metadata); host.save()
    lock_path = Path(tempfile.gettempdir()) / ("cutedog-system-recovery-" + args.serial + ".lock")
    previous_signals = {}
    def interrupted(signum, frame):
        raise InterruptedError(f"Host received signal {signum}")
    try:
        with lock_path.open("a+") as lock:
            try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error: raise Blocked("Another recovery runner owns this emulator") from error
            for sig in (signal.SIGINT, signal.SIGTERM):
                previous_signals[sig] = signal.signal(sig, interrupted)
            try:
                metadata["overallStatus"] = "running"; host.save()
                host.setup()
                for scenario in args.scenarios: host.run_scenario(scenario)
            finally:
                # Avoid a second signal interrupting restoration. SIGKILL cannot be handled.
                for sig in previous_signals: signal.signal(sig, signal.SIG_IGN)
                host.raw = root / "final-cleanup"
                metadata["finalCleanupErrors"] = host.restore_device() + host.restore_permissions()
    except Exception as error:
        metadata["hostError"] = f"{error.__class__.__name__}: {error}"
    finally:
        for sig, handler in previous_signals.items(): signal.signal(sig, handler)
        states = [value["status"] for value in metadata["scenarios"].values()]
        metadata["overallStatus"] = "passed" if (states and all(state == "passed" for state in states)
            and not metadata.get("hostError") and not metadata.get("finalCleanupErrors")) else "not_passed"
        metadata["finishedAt"] = now(); host.save()
        hashes = {str(path.relative_to(root)): digest(path) for path in root.rglob("*")
                  if path.is_file() and path.name != "evidence-sha256.json"}
        write_json(root / "evidence-sha256.json", hashes)
        print(root, flush=True)
    return 0 if metadata["overallStatus"] == "passed" else 1


class HostBoundaryTests(unittest.TestCase):
    def uid_fixture(self, api):
        root = REPO / "scripts/tests/fixtures/system-recovery-uid"
        provenance = json.loads((root / "provenance.json").read_text())
        values = []
        for name in (f"api{api}-package.stdout.txt", f"api{api}-run-as-id.stdout.txt"):
            file = root / name
            self.assertEqual(file.stat().st_size, provenance["files"][name]["bytes"])
            self.assertEqual(digest(file), provenance["files"][name]["sha256"])
            values.append(file.read_text())
        # The original CI ran verbose `id`. This derives unit-test numeric input from
        # that real stdout; it does not pretend `id -u` was already run on a device.
        verbose_uid = re.match(r"uid=(\d+)\(", values[1])
        self.assertIsNotNone(verbose_uid)
        return values[0], verbose_uid[1]

    def test_uid_matches_real_api26_and_api35_package_stdout(self):
        for api, expected, field in ((26, 10077, "userId"), (35, 10209, "appId")):
            with self.subTest(api=api):
                package, numeric = self.uid_fixture(api)
                observed = application_uid_from_outputs(package, numeric + "\n")
                self.assertEqual(observed["uid"], expected)
                self.assertEqual(observed["packageFields"], [{"field": field, "value": expected}])

    def test_uid_ignores_other_package_and_global_uid_fields(self):
        package, numeric = self.uid_fixture(35)
        unrelated = "userId=10001\n  Package [com.unrelated.app] (123):\n    userId=10002\n    User 0: installed=true\n"
        self.assertEqual(application_uid_from_outputs(unrelated + package, numeric)["uid"], 10209)

    def test_uid_rejects_conflicting_missing_or_duplicate_target_identity(self):
        package, numeric = self.uid_fixture(35)
        variants = [package.replace("    appId=10209", "    appId=10209\n    userId=10210"),
                    package.replace("    appId=10209", "    appId=10210"),
                    package.replace("    appId=10209", ""),
                    package.replace("installed=true", "installed=false"), package + package]
        for value in variants:
            with self.assertRaises(Blocked): application_uid_from_outputs(value, numeric)

    def test_uid_requires_numeric_owner_application_uid(self):
        package, numeric = self.uid_fixture(35)
        for value in ("", "0", "1010209", "10209\n10209", "uid=10209(u0_a209)", "10209 warning"):
            with self.subTest(value=value), self.assertRaises(Blocked):
                application_uid_from_outputs(package, value)

    def seed(self):
        return {"reminderId": 42, "title": "Recovery doze unique-token", "dueAt": 1234, "timeZoneId": "Etc/UTC"}
    def notification(self, title=None, tag="42"):
        return ("NotificationRecord(0x123: pkg=com.cutedog.app user=UserHandle{0} id=1 tag=" + tag + " importance=4)\n"
                "  android.title=String (" + (title or self.seed()["title"]) + ")\n")
    def test_only_actual_active_notification_matches(self):
        text = "Notification List:\n  " + self.notification() + "Enqueued Notification List:\n"
        self.assertIsNotNone(find_fixture_notification(text, self.seed()))
        for suffix in ("Enqueued Notification List:", "Snoozed Notification List:", "Archive:"):
            with self.subTest(section=suffix):
                self.assertIsNone(find_fixture_notification("Notification List:\n" + suffix + "\n" + self.notification(), self.seed()))
        self.assertIsNone(find_fixture_notification("Notification List:\n" + self.notification(tag="420"), self.seed()))
        self.assertIsNone(find_fixture_notification("Notification List:\n" + self.notification(title="old fixture"), self.seed()))
        with self.assertRaises(Blocked): find_fixture_notification(self.notification(), self.seed())
    def test_aosp_empty_notification_dump_is_a_valid_empty_state(self):
        for suffix in ("", "mSystemReady=true\n"):
            text = "Current Notification Manager state:\n  mArchive=Archive (0 notifications)\n  Ranking Config:\n" + suffix
            self.assertEqual(active_notification_records(text), [])
            self.assertIsNone(find_fixture_notification(text, self.seed()))
        with self.assertRaises(Blocked): active_notification_records("Permission Denial: cannot dump notification")
        with self.assertRaises(Blocked): active_notification_records("mSystemReady=true\n")

    def test_duplicate_notification_is_not_success(self):
        with self.assertRaises(AssertionError):
            find_fixture_notification("Current Notification List:\n" + self.notification() * 2, self.seed())
    def test_real_temporary_whitelist_format(self):
        self.assertEqual(temporary_idle_uids("Temp whitelist schedule:\n    UID=10123: duration=1000 reason=test\n    UID=1000: duration=1000\n"), {10123, 1000})
        self.assertEqual(temporary_idle_uids(""), set())
        self.assertEqual(exact_appop("No operations."), "default")
        self.assertEqual(exact_appop("SCHEDULE_EXACT_ALARM: allow; time=123"), "allow")
        with self.assertRaises(Blocked): exact_appop("Unknown operation")

    def test_boot_unlock_requires_owner_user(self):
        self.assertTrue(owner_user_unlocked("UserInfo{0:Owner:c13}\n  State: RUNNING_UNLOCKED\n"))
        self.assertTrue(owner_user_unlocked("Started users state: {0=RUNNING_UNLOCKED, 10=RUNNING_LOCKED}"))
        self.assertFalse(owner_user_unlocked("UserInfo{0:Owner:c13}\n State: RUNNING_LOCKED\nUserInfo{10:Guest:1}\n State: RUNNING_UNLOCKED\n"))
        self.assertFalse(owner_user_unlocked("Started users state: {0=RUNNING_LOCKED, 10=RUNNING_UNLOCKED}"))

    def test_probe_requires_actual_success_code(self):
        self.assertEqual(probe_result('INSTRUMENTATION_RESULT: recovery={"status":"ok"}\nINSTRUMENTATION_CODE: -1\n')["status"], "ok")
        for value in ('INSTRUMENTATION_CODE: -1\n', 'INSTRUMENTATION_RESULT: recovery={"status":"ok"}\nINSTRUMENTATION_CODE: 0\n'):
            with self.assertRaises(RuntimeError): probe_result(value)
    def test_inspection_rejects_replay_or_changed_schedule(self):
        seed = self.seed()
        result = {"readOnly": True, "fixture": seed, "item": {"id": 42, "dueAt": 1234, "timeZoneId": "Etc/UTC",
                   "title": seed["title"], "completed": False}, "deliveries": [{"reminderId": 42, "token": "due:1234",
                   "status": "sent", "attempts": 1, "dueAt": 1234, "isSnooze": False}]}
        check_inspection(result, seed)
        result["deliveries"][0]["attempts"] = 2
        with self.assertRaises(AssertionError): check_inspection(result, seed)
        result["deliveries"][0]["attempts"] = 1
        result["item"]["dueAt"] = 5678
        with self.assertRaises(AssertionError): check_inspection(result, seed)


    def test_cleanup_attempts_all_independent_restorations(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = argparse.Namespace(serial="emulator-5554")
            host = Host(args, Path(temporary), {})
            host.restore = {"dozeChanged": True, "screenAwake": True, "timezoneChanged": True,
                            "timezone": "Etc/UTC", "autoTimeZone": "1"}
            host.current_fixture = {"scenario": "timezone", "runId": "a" * 32}
            calls = []
            def shell(*words, **kwargs):
                calls.append(words)
                if "unforce" in words: raise RuntimeError("emulated unforce failure")
                output = ("mForceIdle=false" if words == ("dumpsys", "deviceidle") else "level: 100" if words == ("dumpsys", "battery")
                          else "1" if words[:2] == ("settings", "get") else "")
                return subprocess.CompletedProcess(words, 0, output, "")
            with mock.patch.object(host, "shell", side_effect=shell), mock.patch.object(
                    host, "probe", side_effect=RuntimeError("emulated probe failure")), mock.patch.object(
                    host, "change_timezone", side_effect=RuntimeError("emulated timezone failure")):
                errors = host.restore_device()
            self.assertEqual(len(errors), 3)
            self.assertIn(("dumpsys", "battery", "reset"), calls)
            self.assertIn(("input", "keyevent", "KEYCODE_WAKEUP"), calls)
            self.assertIn(("settings", "put", "global", "auto_time_zone", "1"), calls)
            self.assertIsNotNone(host.current_fixture)
            self.assertTrue(host.restore["timezoneChanged"])
            self.assertTrue(host.restore["dozeChanged"])

    def test_failed_system_observation_never_reenters_inspection(self):
        with tempfile.TemporaryDirectory() as temporary:
            metadata = {"scenarios": {"kill": {"status": "not_run"}}}
            host = Host(argparse.Namespace(serial="emulator-5554"), Path(temporary), metadata)
            with mock.patch.object(host, "seed", return_value=self.seed()), mock.patch.object(host, "snapshot"), \
                    mock.patch.object(host, "action", return_value={"method": "fixture"}), \
                    mock.patch.object(host, "observe", side_effect=AssertionError("no system notification")), \
                    mock.patch.object(host, "probe") as probe, \
                    mock.patch.object(host, "restore_device", return_value=[]) as cleanup:
                with mock.patch("builtins.print"):
                    host.run_scenario("kill")
            probe.assert_not_called()
            cleanup.assert_called_once()
            self.assertEqual(metadata["scenarios"]["kill"]["status"], "failed")
            self.assertIn("measurementEndedAt", metadata["scenarios"]["kill"])

    def test_event_window_rejects_an_already_due_fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(argparse.Namespace(serial="emulator-5554"), Path(temporary), {})
            seed = {**self.seed(), "scheduled": True, "dueAt": 15_000}
            with mock.patch.object(host, "shell", return_value=subprocess.CompletedProcess([], 0, "11\n", "")):
                with self.assertRaises(Blocked): host.assert_live_window(seed)
            text = "Notification List:\n" + self.notification()
            with mock.patch.object(host, "shell", return_value=subprocess.CompletedProcess([], 0, text, "")):
                with self.assertRaises(Blocked): host.assert_no_fixture_notification(self.seed(), "fixture")

    def test_permission_restore_continues_after_one_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(argparse.Namespace(serial="emulator-5554"), Path(temporary), {"api": 35})
            host.restore = {"permissionsChanged": True, "notificationGranted": False, "exactAlarmAppOp": "default"}
            def restore_shell(*words, **kwargs):
                if words[:2] == ("pm", "revoke"): raise RuntimeError("emulated revoke failure")
                return subprocess.CompletedProcess(words, 0, "No operations." if words[:3] == ("cmd", "appops", "get") else "", "")
            with mock.patch.object(host, "shell", side_effect=restore_shell) as shell:
                errors = host.restore_permissions()
            self.assertEqual(shell.call_count, 4)
            self.assertEqual(len(errors), 1)
            self.assertTrue(host.restore["permissionsChanged"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-test", help="Host parser/policy tests only; never claims Android execution")
    execute = commands.add_parser("run")
    execute.add_argument("--serial", required=True)
    execute.add_argument("--disposable", action="store_true")
    execute.add_argument("--apk", type=Path, required=True)
    execute.add_argument("--test-apk", type=Path, required=True)
    execute.add_argument("--source-commit", required=True)
    execute.add_argument("--output", type=Path, default=REPO / "artifacts/system-recovery")
    execute.add_argument("--adb", default=shutil.which("adb") or "/workspace/tooling/android-sdk/platform-tools/adb")
    execute.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=list(SCENARIOS))
    execute.add_argument("--seed-delay", type=int, choices=range(20, 181), metavar="20..180", default=60)
    execute.add_argument("--delivery-timeout", type=int, default=660)
    execute.add_argument("--reboot-timeout", type=int, default=240)
    args = parser.parse_args()
    if args.command == "self-test":
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(HostBoundaryTests)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    if len(set(args.scenarios)) != len(args.scenarios): parser.error("Do not repeat a scenario")
    if args.delivery_timeout < 20 or args.reboot_timeout < 30: parser.error("Observation/reboot timeouts are too short")
    return run(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from error
