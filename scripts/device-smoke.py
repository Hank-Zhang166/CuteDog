#!/usr/bin/env python3
"""Exercise a real released APK on a disposable Android emulator using adb UI input.

No fixtures bypass application UI and no model service/credentials are used. JSON assertions
and screenshots describe this exact APK hash, not later source revisions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.cutedog.app"


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def package_version(dump):
    block = re.search(r"(?m)^\s*Package \[" + re.escape(PACKAGE) + r"\][^\n]*\n(?P<body>.*?)(?=^\s*Package \[|\Z)", dump, re.S)
    if block is None:
        raise AssertionError("Target package record is missing")
    code = re.search(r"\bversionCode=(\d+)\b", block["body"])
    name = re.search(r"\bversionName=([^\s]+)", block["body"])
    if code is None or name is None:
        raise AssertionError("Target package version is unavailable")
    return {"version_code": int(code[1]), "version_name": name[1]}


class Device:
    def __init__(self, serial, output):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("This destructive test is restricted to a disposable emulator.")
        self.serial, self.output = serial, output
        self.width, self.height = 720, 1280
        self.calls = []
        self.external_dialogs = []
        self.app_pids = set()
        self.edit_checks = []
        self.onboarding = []

    def adb(self, *args, timeout=90, check=True):
        result = subprocess.run(["adb", "-s", self.serial, *map(str, args)],
                                capture_output=True, text=True, timeout=timeout)
        self.calls.append({"command": ["adb", *map(str, args)], "exit": result.returncode,
                           "output": (result.stdout + result.stderr)[-12000:]})
        if check and result.returncode:
            raise RuntimeError("adb " + " ".join(map(str, args)) + ": " + result.stdout + result.stderr)
        return result.stdout + result.stderr

    def tree(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/cutedog-ui.xml", timeout=45)
        raw = self.adb("shell", "cat", "/sdcard/cutedog-ui.xml")
        start = raw.find("<?xml")
        if start < 0:
            raise RuntimeError("uiautomator did not produce XML")
        return ET.fromstring(raw[start:])

    def find(self, text, contains=False, tree=None):
        for node in (tree if tree is not None else self.tree()).iter("node"):
            values = [node.get("text", ""), node.get("content-desc", "")]
            if any((text in value if contains else text == value) for value in values):
                return node
        return None

    def wait_text(self, text, contains=False, present=True, seconds=40):
        end = time.monotonic() + seconds
        last = None
        while time.monotonic() < end:
            try:
                tree = self.tree()
                if self.recover_launcher_dialog(tree):
                    continue
                node = self.find(text, contains, tree)
                if (node is not None) == present:
                    return node
            except Exception as error:
                last = str(error)
            time.sleep(1)
        raise AssertionError(f"Expected text {text!r} present={present}; last error={last}")

    def recover_launcher_dialog(self, tree):
        """Record/dismiss only a known unrelated first-boot Pixel Launcher ANR.

        Never dismiss CuteDog errors or assert success because a modal was closed.
        All requested application assertions must still execute afterwards.
        """
        title = next((n.get("text", "") for n in tree.iter("node")
                      if n.get("resource-id") == "android:id/alertTitle"), "")
        if "Pixel Launcher" not in title or "isn't responding" not in title:
            return False
        if len(self.external_dialogs) >= 3:
            raise RuntimeError("Pixel Launcher repeatedly ANRs; emulator is not stable")
        close = next((n for n in tree.iter("node") if n.get("resource-id") == "android:id/aerr_close"), None)
        if close is None:
            return False
        shot = self.screenshot("environment-launcher-anr-" + str(len(self.external_dialogs) + 1))
        self.external_dialogs.append({"title": title, "action": "Close the unrelated Pixel Launcher app",
                                      "screenshot": shot})
        self.tap_node(close)
        return True

    def tap_node(self, node):
        if node is None:
            raise AssertionError("Cannot tap a missing accessibility node")
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        if not (right > left and bottom > top):
            raise AssertionError("Node has no visible touch bounds")
        self.adb("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
        time.sleep(.8)

    def tap(self, text):
        self.tap_node(self.wait_text(text))

    def scroll_to(self, text):
        for _ in range(7):
            node = self.find(text)
            if node is not None:
                self.tap_node(node)
                return
            self.adb("shell", "input", "swipe", self.width // 2, int(self.height * .72),
                     self.width // 2, int(self.height * .32), 450)
            time.sleep(.7)
        raise AssertionError("Could not scroll to " + text)

    def wait_edit_value(self, index, expected, phase, seconds=12):
        """An input command returning zero does not prove Compose/IME committed its text."""
        deadline = time.monotonic() + seconds
        observed = None
        tree = None
        while time.monotonic() < deadline:
            tree = self.tree()
            fields = [node for node in tree.iter("node") if node.get("class") == "android.widget.EditText"]
            observed = fields[index].get("text", "") if index < len(fields) else None
            if observed == expected:
                break
            time.sleep(.15)
        directory = self.output / "text-input"
        directory.mkdir(exist_ok=True)
        path = directory / (str(len(self.edit_checks) + 1).zfill(3) + ".xml")
        if tree is not None:
            ET.ElementTree(tree).write(path, encoding="utf-8", xml_declaration=True)
        matched = observed == expected
        self.edit_checks.append({"field_index": index, "phase": phase, "expected": expected,
                                 "observed": observed, "matched": matched, "xml": str(path.relative_to(self.output))})
        (directory / "checks.json").write_text(json.dumps(self.edit_checks, ensure_ascii=False, indent=2) + "\n")
        if not matched:
            raise AssertionError(f"Input not committed before save ({phase}, field {index}): expected {expected!r}, observed {observed!r}")

    def edit(self, index, value):
        fields = [n for n in self.tree().iter("node") if n.get("class") == "android.widget.EditText"]
        field = fields[index]
        self.tap_node(field)
        old = field.get("text", "")
        if old:
            self.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            self.adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * len(old)))
            self.wait_edit_value(index, "", "clear-existing")
        # Pace injection with observed field commits, not an arbitrary delay after a long burst.
        # Every character remains required; a partial or altered chunk fails before Save.
        for start in range(0, len(value), 8):
            self.adb("shell", "input", "text", value[start:start + 8].replace(" ", "%s"))
            self.wait_edit_value(index, value[:start + 8], "typed-chunk")
        self.wait_edit_value(index, value, "before-hide-ime")
        ime = self.adb("shell", "dumpsys", "input_method")
        if re.search(r"m(?:Is)?Input(?:View)?Shown=true", ime):
            self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.wait_edit_value(index, value, "after-hide-ime")

    def screenshot(self, name):
        shots = self.output / "screenshots"
        shots.mkdir(exist_ok=True)
        path = shots / (name + ".png")
        result = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                                capture_output=True, timeout=60)
        if result.returncode or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("screencap did not return a PNG")
        path.write_bytes(result.stdout)
        # Capture the same visible accessibility tree for independently reviewable assertions.
        try:
            ET.ElementTree(self.tree()).write(self.output / (name + ".xml"), encoding="utf-8", xml_declaration=True)
        except Exception as error:
            (self.output / (name + "-xml-error.txt")).write_text(str(error))
        return str(path.relative_to(self.output))

    def long_drag(self, start_x, start_y, end_x, end_y):
        # A slow swipe moves immediately and correctly means "stroke" to CuteDog.
        # Its documented drag gesture holds still first, then moves without lifting.
        self.adb("shell", "input", "touchscreen", "motionevent", "DOWN", start_x, start_y)
        try:
            time.sleep(.75)
            for index in range(1, 7):
                x = round(start_x + (end_x - start_x) * index / 6)
                y = round(start_y + (end_y - start_y) * index / 6)
                self.adb("shell", "input", "touchscreen", "motionevent", "MOVE", x, y)
                time.sleep(.05)
        finally:
            self.adb("shell", "input", "touchscreen", "motionevent", "UP", end_x, end_y, check=False)

    def launch(self):
        result = self.adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=120)
        if "Status: ok" not in result:
            raise AssertionError("Activity launch did not report Status: ok: " + result)
        for pid in self.adb("shell", "pidof", PACKAGE, check=False).split():
            if pid.isdigit():
                self.app_pids.add(pid)
        # Older preview APKs go straight to the tabs; the current APK has a real five-step
        # first-run introduction. Complete only its visible controls, never write preferences.
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            tree = self.tree()
            if self.recover_launcher_dialog(tree):
                continue
            if self.find("首页", tree=tree) is not None:
                break
            if self.find("稍后了解", tree=tree) is not None:
                next_node = self.find("继续", tree=tree)
                if next_node is None:
                    next_node = self.find("开始陪伴", tree=tree)
                if next_node is None:
                    raise AssertionError("Introduction has no visible Continue/Start control")
                if len(self.onboarding) >= 10:
                    raise AssertionError("Introduction did not complete")
                shot = self.screenshot("onboarding-" + str(len(self.onboarding) + 1))
                self.onboarding.append({"action": next_node.get("text"), "screenshot": shot})
                self.tap_node(next_node)
                continue
            time.sleep(.5)
        self.wait_text("首页", seconds=10)
        return result

    def installed_apk(self, expected_hash, label):
        paths = self.adb("shell", "pm", "path", PACKAGE)
        candidates = [line.removeprefix("package:").strip() for line in paths.splitlines() if line.startswith("package:")]
        if len(candidates) != 1 or not candidates[0].endswith("/base.apk"):
            raise AssertionError("Expected one installed base APK, not a split/missing package")
        with tempfile.TemporaryDirectory(prefix="cutedog-installed-apk-") as directory:
            downloaded = Path(directory) / "installed.apk"
            self.adb("pull", candidates[0], downloaded, timeout=300)
            actual_hash, size = file_sha256(downloaded), downloaded.stat().st_size
        dump = self.adb("shell", "dumpsys", "package", PACKAGE)
        (self.output / (label + "-package.txt")).write_text(dump)
        result = {"device_path": candidates[0], "sha256": actual_hash, "bytes": size, **package_version(dump)}
        (self.output / (label + "-installed-apk.json")).write_text(json.dumps(result, indent=2) + "\n")
        if actual_hash != expected_hash.lower():
            raise AssertionError("Actual installed base APK does not match the pinned input SHA-256")
        return result

    def reminder_snapshot(self, label, title):
        """Read actual stopped-process SQLite bytes; never seed or change data outside UI."""
        self.adb("shell", "am", "force-stop", PACKAGE)
        directory = self.output / label
        directory.mkdir()
        name = "cutedog-reminders.db"
        files = []
        present = {line.strip() for line in self.adb("shell", "run-as", PACKAGE, "ls", "databases").splitlines()}
        if name not in present:
            raise AssertionError("Actual application reminder database does not exist")
        for suffix in ("", "-wal", "-shm"):
            if name + suffix not in present:
                continue
            command = ["adb", "-s", self.serial, "exec-out", "run-as", PACKAGE, "cat", "databases/" + name + suffix]
            result = subprocess.run(command, capture_output=True, timeout=45)
            self.calls.append({"command": command, "exit": result.returncode,
                               "binary_bytes": len(result.stdout), "stderr": result.stderr.decode(errors="replace")})
            if result.returncode:
                if suffix:
                    continue
                raise AssertionError("Cannot read the actual reminder database via run-as")
            path = directory / (name + suffix)
            path.write_bytes(result.stdout)
            files.append({"file": path.name, "bytes": path.stat().st_size, "sha256": file_sha256(path)})
        # SQLite may touch shared-memory sidecars when opening. Query a disposable copy so
        # the archived original bytes and their hashes remain unchanged.
        with tempfile.TemporaryDirectory(prefix="cutedog-upgrade-db-") as temporary:
            for item in files:
                shutil.copyfile(directory / item["file"], Path(temporary) / item["file"])
            connection = sqlite3.connect(f"file:{Path(temporary) / name}?mode=ro", uri=True)
            connection.row_factory = sqlite3.Row
            try:
                rows = [dict(row) for row in connection.execute("SELECT * FROM reminders WHERE title=?", (title,))]
                total = connection.execute("SELECT COUNT(*) FROM reminders").fetchone()[0]
                schema = connection.execute("PRAGMA user_version").fetchone()[0]
            finally:
                connection.close()
        if len(rows) != 1 or total != 1:
            raise AssertionError("Expected exactly the one UI-created upgrade fixture in this fresh emulator")
        result = {"fixture": rows[0], "schema_version": schema, "total_reminders": total, "files": files}
        (directory / "observed.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        return result

    def overlay_frame(self):
        dump = self.adb("shell", "dumpsys", "window", "windows")
        for block in re.split(r"(?=  Window #\d+ Window\{)", dump):
            if PACKAGE not in block or not re.search(r"(?:ty|type)=(?:2038|APPLICATION_OVERLAY)", block):
                continue
            frame = re.search(r"(?:mFrame|(?<!\w)frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
            if frame is None:
                frame = re.search(r"(?:mFrame|(?<!\w)frame)=\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]", block)
            if frame:
                bounds = tuple(map(int, frame.groups()))
                if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return bounds, block
        raise AssertionError("No visible CuteDog TYPE_APPLICATION_OVERLAY frame in WindowManager dump")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--source-commit", required=True, help="Full source SHA declared for the exact final APK")
    parser.add_argument("--disposable", action="store_true", help="Required: use a fresh isolated emulator with no existing CuteDog installation")
    parser.add_argument("--baseline-apk", type=Path, help="Optional old APK for actual in-place upgrade/data-retention validation")
    parser.add_argument("--baseline-sha256", help="Required together with --baseline-apk")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.disposable:
        parser.error("--disposable is required; existing app installations are never cleared")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        parser.error("--source-commit must be a full lowercase 40-character source SHA")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_sha256):
        parser.error("--expected-sha256 must be a 64-character SHA-256")
    if bool(args.baseline_apk) != bool(args.baseline_sha256):
        parser.error("--baseline-apk and --baseline-sha256 must be supplied together")
    if args.baseline_sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", args.baseline_sha256):
        parser.error("--baseline-sha256 must be a 64-character SHA-256")
    if args.baseline_sha256 and args.baseline_sha256.lower() == args.expected_sha256.lower():
        parser.error("Baseline and final APK must be different bytes; testing an APK against itself is not an upgrade")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "assertions.json").exists():
        parser.error("Use a fresh evidence output; previous assertions must not be overwritten")
    results = []
    device = Device(args.serial, args.output)
    metadata = {"apk": args.apk.name, "apk_sha256": file_sha256(args.apk),
                "expected_sha256": args.expected_sha256, "test_source_commit": os.getenv("GITHUB_SHA"),
                "source_commit": args.source_commit, "disposable_emulator": True,
                "source_binding_note": "Source identity is declared by the release input; installed-byte hashes do not independently prove reproducibility.",
                "github_run_id": os.getenv("GITHUB_RUN_ID"), "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "github_run_attempt": os.getenv("GITHUB_RUN_ATTEMPT"),
                "scope": "Actual installed APK UI/overlay checks; no remote AI or physical-device performance claim."}
    prepared = False
    baseline = {}
    upgrade_title = "Upgrade-" + uuid.uuid4().hex[:12]
    upgrade_details = "Preserve this real UI reminder across the signed APK upgrade"

    def step(name, action):
        started = time.monotonic()
        try:
            detail = action()
            results.append({"name": name, "status": "passed", "details": detail,
                            "elapsed_seconds": round(time.monotonic() - started, 2)})
            print("PASS " + name, flush=True)
            return True
        except Exception as error:
            result = {"name": name, "status": "failed", "error": str(error),
                      "traceback": traceback.format_exc(), "elapsed_seconds": round(time.monotonic() - started, 2)}
            if prepared:
                try:
                    result["screenshot"] = device.screenshot("failure-" + name)
                except Exception as capture_error:
                    result["capture_error"] = str(capture_error)
            else:
                result["capture_skipped"] = "No application was installed by this run; preserve any pre-existing device contents"
            results.append(result)
            print("FAIL " + name + ": " + str(error), flush=True)
            return False

    def prepare():
        nonlocal prepared
        if metadata["apk_sha256"] != args.expected_sha256.lower():
            raise AssertionError("Downloaded APK does not match the pinned SHA-256")
        device.adb("wait-for-device")
        if device.adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise AssertionError("Selected device is not an emulator")
        if "package:" in device.adb("shell", "pm", "path", PACKAGE, check=False):
            raise AssertionError("Refusing an existing CuteDog installation; use a fresh disposable emulator, never pm clear")
        metadata.update(api=device.adb("shell", "getprop", "ro.build.version.sdk").strip(),
                        model=device.adb("shell", "getprop", "ro.product.model").strip(),
                        abi=device.adb("shell", "getprop", "ro.product.cpu.abi").strip())
        install_apk = args.baseline_apk or args.apk
        expected = args.baseline_sha256 or args.expected_sha256
        if file_sha256(install_apk) != expected.lower():
            raise AssertionError("APK selected for initial installation does not match its pinned SHA-256")
        installed = device.adb("install", install_apk, timeout=300)
        if "Success" not in installed:
            raise AssertionError(installed)
        prepared = True
        actual = device.installed_apk(expected, "baseline" if args.baseline_apk else "final")
        if args.baseline_apk:
            baseline.update(actual)
            metadata["baseline"] = dict(actual, input_apk=args.baseline_apk.name, expected_sha256=args.baseline_sha256)
        else:
            metadata["installed_base_apk_sha256"] = actual["sha256"]
            metadata["installed_apk"] = actual
        # Keep the AVD's boot-time display configuration. Changing density during the
        # launcher's first start can cause an unrelated Pixel Launcher ANR on API 35.
        size = device.adb("shell", "wm", "size")
        sizes = re.findall(r"(\d+)x(\d+)", size)
        if not sizes:
            raise AssertionError("Cannot read actual display dimensions")
        device.width, device.height = map(int, sizes[-1])
        density = device.adb("shell", "wm", "density").strip()
        if int(metadata["api"]) >= 33:
            device.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        device.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.adb("shell", "wm", "dismiss-keyguard")
        return {"install": installed.strip(), "resolution": f"{device.width}x{device.height}", "density": density}

    def upgrade():
        device.launch()
        device.tap("事项")
        device.tap("＋ 新建")
        device.wait_text("记一件小事")
        device.edit(0, upgrade_title)
        device.edit(1, upgrade_details)
        device.tap("保存事项")
        device.wait_text(upgrade_title)
        seeded_shot = device.screenshot("upgrade-baseline-ui-seeded")
        before_uid = device.adb("shell", "run-as", PACKAGE, "id", "-u").strip()
        if not before_uid.isdigit():
            raise AssertionError("Baseline application UID could not be independently read")
        before = device.reminder_snapshot("upgrade-before-database", upgrade_title)
        if before["fixture"]["details"] != upgrade_details:
            raise AssertionError("Baseline UI did not persist the entered reminder details")
        installed = device.adb("install", "-r", args.apk, timeout=300)
        if "Success" not in installed:
            raise AssertionError("In-place installation failed: " + installed)
        actual = device.installed_apk(args.expected_sha256, "final")
        metadata["installed_base_apk_sha256"] = actual["sha256"]
        metadata["installed_apk"] = actual
        if actual["version_code"] <= baseline["version_code"]:
            raise AssertionError("Final versionCode is not greater than the actual baseline versionCode")
        after_uid = device.adb("shell", "run-as", PACKAGE, "id", "-u").strip()
        if before_uid != after_uid:
            raise AssertionError("Application UID changed during supposed in-place upgrade")
        device.launch()
        device.tap("事项")
        device.wait_text(upgrade_title)
        device.tap(upgrade_title)
        device.wait_text("编辑事项")
        fields = [n.get("text", "") for n in device.tree().iter("node") if n.get("class") == "android.widget.EditText"]
        if upgrade_title not in fields or upgrade_details not in fields:
            raise AssertionError("Final APK did not expose the original reminder title and details in its real UI")
        retained_shot = device.screenshot("upgrade-final-ui-retained")
        after = device.reminder_snapshot("upgrade-after-database", upgrade_title)
        if any(after["fixture"].get(key) != value for key, value in before["fixture"].items()):
            raise AssertionError("Reminder row fields or identity changed across actual APK update")
        result = {"baseline_apk_sha256": baseline["sha256"], "final_installed_apk_sha256": actual["sha256"],
                  "baseline_version_code": baseline["version_code"], "final_version_code": actual["version_code"],
                  "install_method": "adb install -r; no pm clear or uninstall", "same_uid": before_uid,
                  "ui_seeded": True, "ui_title_and_details_retained": True, "database_fields_retained": True,
                  "schema_before": before["schema_version"], "schema_after": after["schema_version"],
                  "screenshots": [seeded_shot, retained_shot]}
        metadata["upgrade"] = result
        # Remove only our known fixture through the product UI before the ordinary CRUD check.
        device.launch()
        device.tap("事项")
        device.tap(upgrade_title)
        device.scroll_to("删除这件事项")
        device.tap("删除")
        device.wait_text(upgrade_title, present=False)
        device.wait_text("这里还很清爽")
        return result

    def tabs():
        device.launch()
        paths = []
        for label, marker, filename in [("首页", "轻触 · 打个招呼", "home"), ("事项", "把小事记好", "reminders"),
                                         ("陪伴", "给心事一个柔软的落脚点", "chat"), ("小狗", "你的小狗，你的节奏", "settings")]:
            device.tap(label)
            device.wait_text(marker)
            paths.append(device.screenshot(filename))
        return {"screenshots": paths}

    def reminder_crud():
        device.tap("事项")
        device.tap("＋ 新建")
        device.wait_text("记一件小事")
        editor = device.screenshot("reminder-editor")
        device.edit(0, "Device check")
        device.tap("保存事项")
        device.wait_text("Device check")
        device.adb("shell", "am", "force-stop", PACKAGE)
        device.launch()
        device.tap("事项")
        device.wait_text("Device check")
        persisted = device.screenshot("reminder-persisted")
        device.tap("Device check")
        device.wait_text("编辑事项")
        device.edit(0, "Device check updated")
        device.tap("保存事项")
        device.wait_text("Device check updated")
        device.wait_text("Device check", present=False)
        # Filter chips are also exposed as checkable accessibility nodes. Use the product's
        # explicit reminder action label instead of counting unrelated checkboxes on the page.
        device.tap_node(device.wait_text("完成：Device check updated"))
        device.tap("完成")
        device.wait_text("Device check updated")
        complete = device.screenshot("reminder-completed")
        checked = device.wait_text("撤销完成：Device check updated")
        if checked.get("checked") != "true":
            raise AssertionError("Completed reminder checkbox is not checked")
        device.tap_node(checked)
        device.tap("待办")
        device.wait_text("Device check updated")
        device.tap("Device check updated")
        device.scroll_to("删除这件事项")
        device.tap("删除")
        device.wait_text("这里还很清爽")
        device.wait_text("Device check updated", present=False)
        return {"created_via_ui": True, "persisted_after_force_stop": True, "edited_via_ui": True,
                "completed_and_undone": True, "deleted_via_ui": True, "screenshots": [editor, persisted, complete]}

    def offline_chat():
        device.adb("shell", "am", "force-stop", PACKAGE)
        device.launch()
        device.tap("陪伴")
        device.wait_text("尚未连接时", contains=True)
        device.tap("连接模型服务")
        device.wait_text("连接你的模型")
        path = device.screenshot("model-settings-unconfigured")
        device.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        return {"unconfigured_state_visible": True, "model_request_sent": False, "screenshot": path}

    def overlay():
        device.adb("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        device.adb("shell", "am", "force-stop", PACKAGE)
        device.launch()
        device.tap("小狗")
        device.tap("召唤小狗")
        time.sleep(2)
        services = device.adb("shell", "dumpsys", "activity", "services", PACKAGE)
        if "OverlayService" not in services or "isForeground=true" not in services:
            raise AssertionError("Overlay service is not foreground")
        device.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(2)
        before, window = device.overlay_frame()
        left, top, right, bottom = before
        if not (0 < right - left < device.width / 2 and 0 < bottom - top < device.height / 2):
            raise AssertionError("Overlay is not a small bounded window: " + str(before))
        if "NOT_FOCUSABLE" not in window or "NOT_TOUCH_MODAL" not in window:
            raise AssertionError("Overlay flags do not allow interaction outside its bounds")
        metadata["overlay_observed"] = {"window_before": before, "foreground_service": True,
                                        "small_non_modal_window": True}
        (args.output / "overlay-window-before.txt").write_text(window)
        first = device.screenshot("overlay-on-launcher")
        # The dog can walk while the screenshot and accessibility tree are being collected.
        before, _ = device.overlay_frame()
        left, top, right, bottom = before
        cx, cy = (left + right) // 2, (top + bottom) // 2
        destination_x = int(device.width * (.2 if cx > device.width / 2 else .8))
        destination_y = max(120, min(device.height - 180, cy - 180))
        device.long_drag(cx, cy, destination_x, destination_y)
        time.sleep(2)
        after, after_window = device.overlay_frame()
        if abs(after[1] - before[1]) < 40:
            raise AssertionError("Long drag did not change overlay position: " + str(after))
        metadata["overlay_observed"].update(window_after=after, drag_moved=True)
        (args.output / "overlay-window-after.txt").write_text(after_window)
        second = device.screenshot("overlay-after-drag")
        # Use the launcher's actual icon bounds, away from Android's navigation gesture
        # region. Tapping Phone only opens its UI; it does not enter a number or call.
        launcher = device.tree()
        phone = next((node for node in launcher.iter("node")
                      if "launcher" in node.get("package", "").lower()
                      and "Phone" in [node.get("text", ""), node.get("content-desc", "")]), None)
        if phone is None:
            raise AssertionError("Launcher has no visible Phone icon for the outside-window touch")
        icon_bounds = tuple(map(int, re.findall(r"\d+", phone.get("bounds", ""))))
        if len(icon_bounds) != 4:
            raise AssertionError("Phone icon has invalid bounds")
        il, it, ir, ib = icon_bounds
        if not (ir <= after[0] or il >= after[2] or ib <= after[1] or it >= after[3]):
            raise AssertionError("Phone icon intersects the overlay; cannot prove an outside-window touch")
        metadata["overlay_observed"].update(outside_touch_icon_bounds=icon_bounds,
                                            outside_touch_point=[(il + ir) // 2, (it + ib) // 2])
        device.tap_node(phone)
        dialer_package = None
        resumed_lines = []
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            activity = device.adb("shell", "dumpsys", "activity", "activities")
            resumed_lines = [line.strip() for line in activity.splitlines()
                             if "mResumedActivity" in line or "topResumedActivity" in line]
            tree = device.tree()
            for package in ["com.google.android.dialer", "com.android.dialer"]:
                if (any(package + "/" in line for line in resumed_lines)
                        and any(node.get("package") == package for node in tree.iter("node"))):
                    dialer_package = package
                    break
            if dialer_package:
                break
            time.sleep(1)
        (args.output / "outside-touch-resumed-activity.txt").write_text("\n".join(resumed_lines) + "\n")
        if dialer_package is None:
            raise AssertionError("Outside-window Phone tap did not produce both a resumed Dialer Activity and its visible UI")
        external_touch = device.screenshot("overlay-launcher-touch")
        metadata["overlay_observed"].update(launcher_touch_observed=True, external_package=dialer_package)
        device.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        device.wait_text("Phone")
        returned_frame, _ = device.overlay_frame()
        metadata["overlay_observed"]["window_after_return_home"] = returned_frame
        device.launch()
        device.tap("小狗")
        device.scroll_to("停止桌宠")
        time.sleep(2)
        services_after = device.adb("shell", "dumpsys", "activity", "services", PACKAGE)
        if "isForeground=true" in services_after and "OverlayService" in services_after:
            raise AssertionError("Overlay service remains foreground after Stop")
        return {"window_before": before, "window_after": after, "foreground_service": True,
                "small_non_modal_window": True, "drag_moved": True, "launcher_touch_observed": True,
                "drag_gesture": "DOWN, hold still 750ms, six MOVE events, UP",
                "outside_touch_icon_bounds": icon_bounds,
                "outside_touch_point": [(il + ir) // 2, (it + ib) // 2],
                "external_package": dialer_package, "window_after_return_home": returned_frame,
                "service_stopped_from_ui": True, "screenshots": [first, second, external_touch]}

    try:
        if step("install-pinned-apk", prepare) and (not args.baseline_apk or step("actual-in-place-upgrade-preserves-ui-data", upgrade)):
            step("launch-and-four-tabs", tabs)
            step("reminder-ui-lifecycle", reminder_crud)
            step("chat-without-credentials", offline_chat)
            step("overlay-and-drag", overlay)
    finally:
        # This emulator is disposable; never collect any API keys or private chat contents.
        if prepared:
            try:
                for pid in device.adb("shell", "pidof", PACKAGE, check=False).split():
                    if pid.isdigit():
                        device.app_pids.add(pid)
                errors = []
                for pid in sorted(device.app_pids, key=int):
                    crash = device.adb("logcat", "-d", "--pid=" + pid, "AndroidRuntime:E", "*:S", check=False)
                    errors.append(f"Observed CuteDog process {pid}:\n{crash}")
                    if "FATAL EXCEPTION" in crash:
                        results.append({"name": "app-process-crash-" + pid, "status": "failed", "error": "App AndroidRuntime fatal exception captured"})
                (args.output / "app-runtime-errors.txt").write_text("\n".join(errors))
            except Exception as error:
                results.append({"name": "app-log-collection", "status": "failed", "error": str(error)})
            cleanup = [("stop", ("shell", "am", "force-stop", PACKAGE)),
                       ("overlay", ("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "default"))]
            if int(metadata["api"]) >= 33:
                cleanup.append(("notifications", ("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")))
            metadata["cleanup"] = []
            for label, command in cleanup:
                try:
                    device.adb(*command)
                    metadata["cleanup"].append({"action": label, "status": "completed"})
                except Exception as error:
                    metadata["cleanup"].append({"action": label, "status": "failed", "error": str(error)})
                    results.append({"name": "cleanup-" + label, "status": "failed", "error": str(error)})
        else:
            metadata["cleanup"] = "not_needed; no installation owned by this run"
        metadata["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        metadata["external_environment_dialogs"] = device.external_dialogs
        metadata["onboarding_ui_steps"] = device.onboarding
        report = {"metadata": metadata, "assertions": results,
                  "passed": sum(r["status"] == "passed" for r in results),
                  "failed": sum(r["status"] == "failed" for r in results)}
        (args.output / "assertions.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (args.output / "adb-commands.json").write_text(json.dumps(device.calls, ensure_ascii=False, indent=2) + "\n")
    return 0 if results and all(r["status"] == "passed" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
