#!/usr/bin/env python3
"""Observe actual Android rendering/memory and exercise overlay lifecycle on a disposable device.

Requires an already installed CuteDog APK. Does not build, install, clear app data, manufacture
measurements, or equate emulator observations with phone battery life. Every result retains raw
package-scoped evidence. Permission revocation runs from the host, never inside instrumentation.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PACKAGE = "com.cutedog.app"
SERVICE = PACKAGE + "/.pet.OverlayService"
ACTIVITY = PACKAGE + "/.MainActivity"
MODES = {"allow", "ignore", "deny", "default", "foreground"}


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower, upper = math.floor(index), math.ceil(index)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def parse_gfx(raw):
    count = re.search(r"Total frames rendered:\s*(\d+)", raw)
    jank = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", raw)
    frames, header = {}, None
    for line in raw.splitlines():
        columns = [item.strip() for item in line.strip().split(",") if item.strip()]
        if "IntendedVsync" in columns and "FrameCompleted" in columns:
            header = columns
            continue
        if line.startswith("---"):
            header = None
        if header and len(columns) >= len(header) and columns[0].lstrip("-").isdigit():
            try:
                row = dict(zip(header, map(int, columns)))
                start, end = row["IntendedVsync"], row["FrameCompleted"]
                if row.get("Flags", 0) == 0 and 0 < start <= end < 2**63 - 1:
                    # Dedupe the same render timestamp across repeated dump sections.
                    frames[(start, end)] = (end - start) / 1_000_000
            except (ValueError, KeyError):
                continue
    return {"total_frames": int(count[1]) if count else None,
            "janky_frames_since_reset": int(jank[1]) if jank else None,
            "janky_percent_since_reset": float(jank[2]) if jank else None,
            "frames": frames}


def parse_memory(raw):
    pss = re.search(r"TOTAL PSS:\s*(\d+)", raw)
    if pss is None:
        pss = re.search(r"^\s*TOTAL\s+(\d+)\s+", raw, re.M)
    values = {"total_pss_kib": int(pss[1]) if pss else None}
    for label, field in [("Java Heap", "java_heap_pss_kib"), ("Native Heap", "native_heap_pss_kib"),
                         ("Graphics", "graphics_pss_kib"), ("TOTAL RSS", "total_rss_kib")]:
        match = re.search(re.escape(label) + r":\s*(\d+)", raw)
        values[field] = int(match[1]) if match else None
    return values


def parse_metrics(raw, scope):
    rows = []
    for line in raw.splitlines():
        for prefix in ("CUTEDOG_METRICS_JSON:", "CUTEDOG_VIEW_METRICS_JSON:"):
            if prefix not in line:
                continue
            try:
                value = json.loads(line.split(prefix, 1)[1].strip())
                items = value if isinstance(value, list) else value.get("views", [value])
                rows.extend(item for item in items if isinstance(item, dict) and item.get("scope", scope) == scope)
            except (ValueError, AttributeError):
                continue
    return rows


def parse_app_op(raw):
    match = re.search(r"SYSTEM_ALERT_WINDOW:\s*(allow|ignore|deny|default|foreground)\b", raw)
    if match:
        return match[1]
    if "No operations" in raw or "No op" in raw:
        return "default"
    return None


def visible_overlay_blocks(raw):
    result = []
    for block in re.split(r"(?=\s*Window #\d+ Window\{)", raw):
        if PACKAGE not in block or not re.search(r"(?:ty|type)=(?:2038|APPLICATION_OVERLAY)", block):
            continue
        # A retained hidden WindowState is not an observable overlay surface.
        if "mViewVisibility=0x8" in block or "mViewVisibility=8" in block or "mHasSurface=false" in block:
            continue
        if "mHasSurface=true" in block or "isOnScreen=true" in block or "isVisible=true" in block:
            result.append(block)
    return result


class Device:
    def __init__(self, serial, output, adb="adb"):
        self.serial, self.output, self.executable = serial, output, adb
        self.calls = []
        self.sequence = 0

    def run(self, *args, timeout=45, check=True):
        started = time.monotonic()
        result = subprocess.run([self.executable, "-s", self.serial, *map(str, args)],
                                capture_output=True, text=True, timeout=timeout)
        text = result.stdout + result.stderr
        self.calls.append({"args": list(map(str, args)), "exit_code": result.returncode,
                           "elapsed_seconds": round(time.monotonic() - started, 3),
                           "stderr": result.stderr[-2000:]})
        if check and result.returncode:
            raise RuntimeError(f"adb failed ({result.returncode}): {' '.join(map(str, args))}: {text[-1200:]}")
        return text

    def evidence(self, name, text):
        path = self.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return str(path.relative_to(self.output))

    def tree(self):
        self.run("shell", "uiautomator", "dump", "/sdcard/cutedog-profile-ui.xml")
        raw = self.run("shell", "cat", "/sdcard/cutedog-profile-ui.xml")
        start = raw.find("<?xml")
        if start < 0:
            raise RuntimeError("UIAutomator produced no accessibility XML")
        return ET.fromstring(raw[start:])

    @staticmethod
    def find(tree, label):
        for node in tree.iter("node"):
            if label not in (node.get("text", ""), node.get("content-desc", "")):
                continue
            bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1] and node.get("enabled", "true") == "true":
                return bounds
        return None

    def tap(self, label, scroll=False):
        for attempt in range(10 if scroll else 3):
            bounds = self.find(self.tree(), label)
            if bounds:
                left, top, right, bottom = bounds
                self.run("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
                time.sleep(.7)
                return
            if scroll:
                size = re.findall(r"(\d+)x(\d+)", self.run("shell", "wm", "size"))
                if not size:
                    raise RuntimeError("Cannot read display dimensions")
                width, height = map(int, size[-1])
                start, end = ((.78, .3) if attempt < 6 else (.3, .78))
                self.run("shell", "input", "swipe", width // 2, int(height * start), width // 2, int(height * end), 350)
            time.sleep(.6)
        raise AssertionError(f"Visible app control not found: {label}")

    def launch(self):
        text = self.run("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=90)
        if "Status: ok" not in text:
            raise AssertionError("Activity did not report Status: ok")
        for _ in range(6):
            tree = self.tree()
            if self.find(tree, "稍后了解"):
                self.tap("稍后了解")
            elif self.find(tree, "首页"):
                return
            time.sleep(1)
        raise AssertionError("App did not reach its navigation screen")

    def start_overlay(self, start_label):
        self.launch()
        self.tap("小狗")
        self.tap(start_label, scroll=True)
        self.wait(lambda: self.service_state()["foreground"], 12, "Overlay service did not become foreground")
        self.run("shell", "input", "keyevent", "KEYCODE_HOME")
        self.wait(lambda: bool(visible_overlay_blocks(self.run("shell", "dumpsys", "window", "windows"))),
                  12, "Overlay did not expose a visible TYPE_APPLICATION_OVERLAY surface")

    def stop_overlay(self, stop_label):
        self.launch()
        self.tap("小狗")
        self.tap(stop_label, scroll=True)
        self.wait(lambda: not self.service_state()["present"], 12, "Overlay service remains after its Stop control")
        self.wait(lambda: not visible_overlay_blocks(self.run("shell", "dumpsys", "window", "windows")),
                  8, "Overlay surface remains after Stop")

    def service_state(self):
        raw = self.run("shell", "dumpsys", "activity", "services", PACKAGE)
        present = bool(re.search(r"ServiceRecord\{[^\n]*\.pet\.OverlayService", raw))
        return {"present": present, "foreground": present and "isForeground=true" in raw}

    @staticmethod
    def wait(condition, timeout, message):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(.4)
        raise AssertionError(message)

    def screen_state(self):
        raw = self.run("shell", "dumpsys", "power")
        match = re.search(r"mWakefulness=(\w+)", raw)
        interactive = re.search(r"mInteractive=(true|false)", raw)
        return {"wakefulness": match[1] if match else None,
                "interactive": interactive[1] == "true" if interactive else (match[1] == "Awake" if match else None)}

    def cpu(self, pid):
        if not pid or not pid.isdigit():
            return None
        raw = self.run("shell", "run-as", PACKAGE, "cat", f"/proc/{pid}/stat", check=False)
        match = re.match(r"\d+\s+\(.*\)\s+(.*)", raw.strip())
        if not match:
            return None  # Release/non-debuggable or SELinux restriction: unknown, not zero CPU.
        parts = match[1].split()
        if len(parts) < 20:
            return None
        try:
            return {"pid": int(pid), "start_ticks": int(parts[19]), "cpu_ticks": int(parts[11]) + int(parts[12]), "captured_monotonic": time.monotonic()}
        except ValueError:
            return None

    def snapshot(self, name, scope):
        pid = self.run("shell", "pidof", PACKAGE, check=False).strip().split()
        pid = pid[0] if len(pid) == 1 and pid[0].isdigit() else None
        gfx_raw = self.run("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
        memory_raw = self.run("shell", "dumpsys", "meminfo", PACKAGE)
        command = ("service", SERVICE) if scope == "overlay" else ("activity", ACTIVITY)
        metrics_raw = self.run("shell", "dumpsys", "activity", *command, check=False)
        paths = {
            "gfxinfo": self.evidence(name + "/gfxinfo.txt", gfx_raw),
            "meminfo": self.evidence(name + "/meminfo.txt", memory_raw),
            "diagnostics": self.evidence(name + "/pet-diagnostics.txt", metrics_raw),
        }
        return {"host_monotonic": time.monotonic(), "pid": pid, "cpu": self.cpu(pid),
                "memory": parse_memory(memory_raw), "gfx": parse_gfx(gfx_raw),
                "pet": parse_metrics(metrics_raw, scope), "screen": self.screen_state(), "evidence": paths}

    def interval(self, name, scope, seconds, ticks_per_second):
        self.run("shell", "dumpsys", "gfxinfo", PACKAGE, "reset", check=False)
        before = self.snapshot(name + "-before", scope)
        print(f"Sampling {name} for {seconds:g}s", flush=True)
        time.sleep(seconds)
        after = self.snapshot(name + "-after", scope)
        elapsed = after["host_monotonic"] - before["host_monotonic"]
        report = interval_report(before, after, elapsed, ticks_per_second)
        report.update(name=name, requested_seconds=seconds, before_evidence=before["evidence"], after_evidence=after["evidence"])
        self.evidence(name + "/measurement.json", json.dumps(report, ensure_ascii=False, indent=2))
        return report


def interval_report(before, after, elapsed, ticks_per_second):
    same_process = before["pid"] is not None and before["pid"] == after["pid"]
    old_frames = before["gfx"]["frames"]
    marker = max((pair[0] for pair in old_frames), default=0)
    durations = [duration for pair, duration in after["gfx"]["frames"].items() if pair[0] > marker]
    first, last = before["gfx"]["total_frames"], after["gfx"]["total_frames"]
    draw_delta = last - first if same_process and first is not None and last is not None and last >= first else None
    cpu_before, cpu_after = before["cpu"], after["cpu"]
    cpu_ticks = None
    cpu_seconds = None
    if cpu_before and cpu_after and (cpu_before["pid"], cpu_before["start_ticks"]) == (cpu_after["pid"], cpu_after["start_ticks"]):
        cpu_ticks = cpu_after["cpu_ticks"] - cpu_before["cpu_ticks"]
        cpu_seconds = cpu_after["captured_monotonic"] - cpu_before["captured_monotonic"]
    result = {"observed_seconds": elapsed, "same_process": same_process,
              "before_memory": before["memory"], "after_memory": after["memory"],
              "before_screen": before["screen"], "after_screen": after["screen"],
              "process_cpu_ticks": cpu_ticks, "cpu_observed_seconds": cpu_seconds, "device_clock_ticks_per_second": ticks_per_second,
              "process_cpu_percent_of_one_core": 100 * cpu_ticks / ticks_per_second / cpu_seconds if cpu_ticks is not None and ticks_per_second and cpu_seconds and cpu_seconds > 0 else None,
              "renderer_draw_events": draw_delta,
              "renderer_draw_events_per_second": draw_delta / elapsed if draw_delta is not None else None,
              "retained_frame_sample_count": len(durations),
              "frame_duration_p50_ms": percentile(durations, .5), "frame_duration_p95_ms": percentile(durations, .95),
              "frame_duration_p99_ms": percentile(durations, .99),
              "frame_stats_scope": "Gfxinfo keeps a limited ring buffer. Percentiles cover retained completed frames, not necessarily the entire interval. Renderer redraws are not unique pet animation frames.",
              "pet_before": before["pet"], "pet_after": after["pet"], "pet_counter_deltas": None}
    if same_process and len(before["pet"]) == len(after["pet"]) == 1:
        a, b = before["pet"][0], after["pet"][0]
        same_view = a.get("viewId") == b.get("viewId") and a.get("scope") == b.get("scope")
        fields = ("tickCount", "drawCount", "actualFrameChanges", "decodedFrames", "reusedBuffers")
        if same_view:
            delta = {}
            for field in fields:
                old, new = a.get(field), b.get(field)
                delta[field] = new - old if isinstance(old, (int, float)) and isinstance(new, (int, float)) and new >= old else None
            result["pet_counter_deltas"] = delta
            pet_seconds = (b["uptimeMs"] - a["uptimeMs"]) / 1000 if isinstance(a.get("uptimeMs"), (int, float)) and isinstance(b.get("uptimeMs"), (int, float)) and b["uptimeMs"] > a["uptimeMs"] else elapsed
            result["pet_observed_uptime_seconds"] = pet_seconds
            result["actual_pet_frame_changes_per_second"] = delta["actualFrameChanges"] / pet_seconds if delta["actualFrameChanges"] is not None else None
            result["view_identity_verified"] = a.get("viewId") is not None
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.getenv("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=float, default=20, help="Sampling seconds per active state (5–60)")
    parser.add_argument("--cycles", type=int, default=3, help="Additional start/stop retention observations (1–10)")
    parser.add_argument("--disposable", action="store_true", help="Required: permit force-stop and temporary overlay permission changes")
    parser.add_argument("--allow-physical-device", action="store_true", help="Explicitly allow a non-emulator disposable test device")
    parser.add_argument("--require-rig-metrics", action="store_true", help="Return failure when real rig counters are unavailable or legacy art is installed")
    parser.add_argument("--expected-apk-sha256", help="Optional expected hash of the installed base.apk; rejects mismatched binaries")
    parser.add_argument("--start-label", default="召唤小狗")
    parser.add_argument("--stop-label", default="停止桌宠")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("--disposable is required; this workflow temporarily revokes overlay access and stops the app")
    if not args.serial.startswith("emulator-") and not args.allow_physical_device:
        parser.error("Physical devices require --allow-physical-device and must be disposable test devices")
    if not 5 <= args.duration <= 60 or not 1 <= args.cycles <= 10:
        parser.error("--duration must be 5–60 and --cycles 1–10")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "profile.json").exists():
        parser.error("Use a new output directory; existing measurement evidence must not be overwritten")
    device = Device(args.serial, args.output, args.adb)
    report = {"schema_version": 1, "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "source_commit": os.getenv("GITHUB_SHA"), "github_run_id": os.getenv("GITHUB_RUN_ID"),
              "serial": args.serial, "checks": [], "measurements": [], "restoration": [],
              "performance_acceptance": "No device-specific FPS/PSS budget has been imposed; measurements are evidence, not a commercial-quality performance claim.",
              "limitations": ["Emulator CPU, GPU, memory and latency are environment observations, not phone battery or thermal measurements.",
                              "Short PSS trends cannot prove absence of leaks. Use long physical-device sessions and heap/allocation profiling for a release claim.",
                              "Screen-off tests do not prove secure credential-lockscreen, OEM battery optimization, Doze alarms or reboot behavior."]}
    initial_mode = initial_timeout = initial_screen = None
    changed_op = changed_timeout = app_mutated = False
    exit_code = 1

    def check(name, action, strict=True):
        started = time.monotonic()
        try:
            detail = action()
            report["checks"].append({"name": name, "status": "passed", "details": detail, "seconds": time.monotonic() - started})
            print("PASS " + name, flush=True)
            return detail
        except Exception as error:
            report["checks"].append({"name": name, "status": "failed", "error": str(error), "seconds": time.monotonic() - started})
            print("FAIL " + name + ": " + str(error), file=sys.stderr, flush=True)
            if strict:
                raise
            return None

    def rig_assertion(name, measurement, paused=False):
        before, after = measurement["pet_before"], measurement["pet_after"]
        if not before or not after or any(row.get("renderMode") in (None, "legacy") for row in before + after):
            report["checks"].append({"name": name, "status": "not_run", "reason": "Real rig diagnostics unavailable or legacy artwork is installed"})
            return
        delta = measurement["pet_counter_deltas"]
        if not delta or not measurement.get("view_identity_verified"):
            report["checks"].append({"name": name, "status": "unverified", "reason": "View/counter continuity could not be established"})
            return
        required = ("tickCount", "drawCount", "actualFrameChanges", "decodedFrames") if paused else ("actualFrameChanges", "drawCount")
        if any(delta.get(key) is None for key in required):
            report["checks"].append({"name": name, "status": "unverified", "reason": "Required numeric counters are missing"})
            return
        if paused:
            check(name, lambda: assert_result(all(delta[key] == 0 for key in required) and all(row.get("running") is False and row.get("activeDecoder") is False and row.get("pendingFrames") == 0 for row in after),
                                             "Screen-off pet ticks, draws, frames or decoding continued after settling", delta), strict=False)
        else:
            check(name, lambda: assert_result(delta["actualFrameChanges"] > 0 and delta["drawCount"] > 0 and all(row.get("running") is True and row.get("visible") is True for row in after), "No visible running rig advanced/drew frames during the interval", delta), strict=False)

    try:
        device.run("wait-for-device")
        if device.run("shell", "getprop", "sys.boot_completed").strip() != "1":
            raise RuntimeError("Android has not completed boot")
        api = int(device.run("shell", "getprop", "ro.build.version.sdk").strip())
        if api < 26:
            raise RuntimeError("This application requires Android API26 or later")
        report["device"] = {"api": api, "model": device.run("shell", "getprop", "ro.product.model").strip(),
                            "abi": device.run("shell", "getprop", "ro.product.cpu.abi").strip(),
                            "emulator": device.run("shell", "getprop", "ro.kernel.qemu").strip() == "1"}
        package_paths = device.run("shell", "pm", "path", PACKAGE)
        base = next((line.removeprefix("package:").strip() for line in package_paths.splitlines() if line.endswith("/base.apk")), None)
        if not base or not re.fullmatch(r"/[A-Za-z0-9_./~+=@%-]+", base):
            raise RuntimeError("Cannot identify an installed base.apk safely")
        checksum = device.run("shell", "sha256sum", base).split()[0].lower()
        if not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise RuntimeError("Cannot establish installed APK SHA-256")
        report["installed_base_apk_sha256"] = checksum
        if args.expected_apk_sha256 and checksum != args.expected_apk_sha256.lower():
            raise RuntimeError("Installed APK differs from the expected hash")
        initial_raw = device.run("shell", "cmd", "appops", "get", PACKAGE, "SYSTEM_ALERT_WINDOW")
        device.evidence("original-overlay-appop.txt", initial_raw)
        initial_mode = parse_app_op(initial_raw)
        if initial_mode is None:
            raise RuntimeError("Original overlay app-op is unknown; refusing to overwrite unrestorable state")
        initial_screen = device.screen_state()
        initial_timeout = device.run("shell", "settings", "get", "system", "screen_off_timeout").strip()
        hz = device.run("shell", "getconf", "CLK_TCK", check=False).strip()
        ticks_per_second = int(hz) if hz.isdigit() and 1 <= int(hz) <= 10000 else None
        app_mutated = True
        device.run("shell", "am", "force-stop", PACKAGE)
        changed_op = True
        device.run("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        changed_timeout = True
        device.run("shell", "settings", "put", "system", "screen_off_timeout", "600000")
        device.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.run("shell", "wm", "dismiss-keyguard")
        device.launch(); device.tap("首页")
        time.sleep(5)
        foreground = device.interval("foreground", "activity", args.duration, ticks_per_second)
        report["measurements"].append(foreground)
        rig_assertion("foreground-real-rig-advances", foreground)
        check("foreground-memory-is-measurable", lambda: assert_result(foreground["after_memory"]["total_pss_kib"] is not None,
                                                                        "No app PSS measurement was returned", foreground["after_memory"]), strict=False)
        check("start-overlay-from-user-control", lambda: device.start_overlay(args.start_label) or device.service_state())
        time.sleep(5)
        overlay = device.interval("overlay", "overlay", args.duration, ticks_per_second)
        report["measurements"].append(overlay)
        rig_assertion("overlay-real-rig-advances", overlay)
        device.run("shell", "input", "keyevent", "KEYCODE_SLEEP")
        device.wait(lambda: device.screen_state()["interactive"] is False, 10, "Screen did not enter a non-interactive state")
        time.sleep(3)
        paused = device.interval("screen-off", "overlay", min(args.duration, 15), ticks_per_second)
        report["measurements"].append(paused)
        rig_assertion("screen-off-render-and-decode-stopped", paused, paused=True)
        check("screen-remained-off", lambda: assert_result(paused["before_screen"]["interactive"] is False and paused["after_screen"]["interactive"] is False,
                                                            "Screen woke during the paused measurement", paused["after_screen"]))
        device.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.run("shell", "wm", "dismiss-keyguard")
        device.wait(lambda: device.screen_state()["interactive"] is True, 10, "Screen did not resume")
        time.sleep(3)
        resumed = device.interval("resumed-overlay", "overlay", min(args.duration, 12), ticks_per_second)
        report["measurements"].append(resumed)
        rig_assertion("unlock-real-rig-resumed", resumed)
        check("service-survived-screen-off", lambda: assert_result(device.service_state()["foreground"], "Foreground overlay service did not survive screen-off", device.service_state()))

        cycles = []
        for index in range(args.cycles):
            check(f"stop-overlay-cycle-{index + 1}", lambda: device.stop_overlay(args.stop_label) or device.service_state())
            time.sleep(2)
            snap = device.snapshot(f"stopped-cycle-{index + 1}", "activity")
            cycles.append({"cycle": index + 1, "memory": snap["memory"], "pet": snap["pet"], "evidence": snap["evidence"]})
            check(f"restart-overlay-cycle-{index + 1}", lambda: device.start_overlay(args.start_label) or device.service_state())
            time.sleep(2)
        pss = [row["memory"]["total_pss_kib"] for row in cycles]
        report["retention_observations"] = {"cycles": cycles, "last_minus_first_pss_kib": pss[-1] - pss[0] if pss and all(value is not None for value in pss) else None,
                                            "leak_verdict": "not_proven", "reason": "PSS includes caches and GC timing; these repeated lifecycle samples are regression evidence, not a heap-leak proof."}
        device.run("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "ignore")
        check("permission-revocation-stops-service", lambda: device.wait(lambda: not device.service_state()["present"], 15,
                                                                          "OverlayService remains after actual overlay-permission revocation") or device.service_state())
        check("permission-revocation-removes-window", lambda: device.wait(lambda: not visible_overlay_blocks(device.run("shell", "dumpsys", "window", "windows")),
                                                                          10, "Overlay surface remains after permission revocation") or {"visible_overlay_windows": 0})
        device.evidence("after-revocation-services.txt", device.run("shell", "dumpsys", "activity", "services", PACKAGE))
        device.run("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        check("permission-restoration-allows-explicit-restart", lambda: device.start_overlay(args.start_label) or device.service_state())
        check("final-stop-control-cleans-service", lambda: device.stop_overlay(args.stop_label) or device.service_state())
        missing = any(item["status"] in ("not_run", "unverified") for item in report["checks"])
        failed = any(item["status"] == "failed" for item in report["checks"])
        report["status"] = "failed" if failed else ("partial" if missing else "passed")
        exit_code = 1 if failed else (2 if args.require_rig_metrics and missing else 0)
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error)
        report["traceback"] = traceback.format_exc()
        print("FAIL " + str(error), file=sys.stderr, flush=True)
    finally:
        # Host-side restoration can safely terminate the app without killing an instrumentation runner.
        restoration = [("stop-test-app", ("shell", "am", "force-stop", PACKAGE))] if app_mutated else []
        if changed_op and initial_mode in MODES:
            restoration.append(("restore-overlay-appop", ("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", initial_mode)))
        if changed_timeout:
            operation = ("delete", "system", "screen_off_timeout") if initial_timeout == "null" else ("put", "system", "screen_off_timeout", initial_timeout)
            restoration.append(("restore-screen-timeout", ("shell", "settings", *operation)))
        if initial_screen and initial_screen["interactive"] is False:
            restoration.append(("restore-screen-off", ("shell", "input", "keyevent", "KEYCODE_SLEEP")))
        for name, command in restoration:
            try:
                device.run(*command)
                if name == "restore-overlay-appop" and parse_app_op(device.run("shell", "cmd", "appops", "get", PACKAGE, "SYSTEM_ALERT_WINDOW")) != initial_mode:
                    raise AssertionError("Overlay app-op restoration was not observed")
                if name == "restore-screen-timeout" and device.run("shell", "settings", "get", "system", "screen_off_timeout").strip() != initial_timeout:
                    raise AssertionError("Screen timeout restoration was not observed")
                report["restoration"].append({"name": name, "status": "passed"})
            except Exception as error:
                report["restoration"].append({"name": name, "status": "failed", "error": str(error)})
                exit_code = 1; report["status"] = "failed"
        report["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        report["exit_code"] = exit_code
        device.evidence("adb-commands.json", json.dumps(device.calls, ensure_ascii=False, indent=2))
        device.evidence("profile.json", json.dumps(report, ensure_ascii=False, indent=2))
    return exit_code


def assert_result(condition, message, detail):
    if not condition:
        raise AssertionError(message)
    return detail


if __name__ == "__main__":
    raise SystemExit(main())
