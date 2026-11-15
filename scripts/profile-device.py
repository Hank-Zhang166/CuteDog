#!/usr/bin/env python3
"""Observe actual Android rendering/memory and exercise overlay lifecycle on a disposable device.

Requires an already installed CuteDog APK. Does not build, install, clear app data, manufacture
measurements, or equate emulator observations with phone battery life. Every result retains raw
package-scoped evidence. Permission revocation runs from the host, never inside instrumentation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.cutedog.app"
SERVICE = PACKAGE + "/.pet.OverlayService"
ACTIVITY = PACKAGE + "/.MainActivity"
MODES = {"allow", "ignore", "deny", "default", "foreground"}
NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
RESTORABLE_PERMISSION_FLAGS = {
    "USER_SET": "user-set", "USER_FIXED": "user-fixed", "REVIEW_REQUIRED": "review-required",
    "REVOKED_COMPAT": "revoked-compat", "USER_SENSITIVE_WHEN_GRANTED": "user-sensitive-when-granted",
    "USER_SENSITIVE_WHEN_DENIED": "user-sensitive-when-denied",
}
PAGE_MARKERS = {"home": ("轻触 · 打个招呼",), "reminders": ("把小事记好",),
                "companion": ("记忆", "清空聊天"), "settings": ("你的小狗，你的节奏",)}


def verified_artwork_binding(apk, expected_sha256, expected_bytes=None):
    """Inspect the exact tested binary, never infer installed artwork from source directories."""
    if not isinstance(expected_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("An exact lowercase APK SHA-256 is required")
    apk = Path(apk)
    actual_bytes = apk.stat().st_size
    with apk.open("rb") as stream:
        actual_sha = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual_sha != expected_sha256 or (expected_bytes is not None and actual_bytes != expected_bytes):
        raise ValueError("Actual APK bytes differ from tested-apks identity")
    with zipfile.ZipFile(apk) as archive:
        entries = [item for item in archive.infolist() if item.filename == "assets/pet3d/manifest.json"]
        if len(entries) > 1:
            raise ValueError("APK contains duplicate character manifests")
        manifest_sha = hashlib.sha256(archive.read(entries[0])).hexdigest() if entries else None
    return {"path": str(apk), "bytes": actual_bytes, "sha256": actual_sha,
            "contains_authored_manifest": bool(entries), "manifest_sha256": manifest_sha}


def tested_apk_binding(record):
    identity = json.loads(Path(record).read_text())["application"]
    if type(identity.get("bytes")) is not int or identity["bytes"] <= 0:
        raise ValueError("tested-apks application byte count is missing or invalid")
    return verified_artwork_binding(identity["path"], identity["sha256"], identity["bytes"])


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


def parse_notification_permission(raw):
    """Read user 0's runtime state; absence or another user's grant is not a denial."""
    user = re.search(r"^\s*User 0:.*?(?=^\s*User \d+:|\Z)", raw, re.M | re.S)
    if user is None:
        raise ValueError("User 0 permission state is unavailable")
    states = re.findall(r"^\s*" + re.escape(NOTIFICATION_PERMISSION)
                        + r":\s*granted=(true|false),\s*flags=\[([^\]]*)\]", user.group(), re.M)
    if len(states) != 1:
        raise ValueError("Expected one explicit user 0 POST_NOTIFICATIONS grant and flags record")
    granted, flags = states[0]
    return {"granted": granted == "true", "flags": sorted(flag.strip() for flag in flags.split("|") if flag.strip())}


class ProfileNotificationPermission:
    """Temporary host permission state, separate from the prior instrumentation phases."""
    def __init__(self, device):
        self.device = device
        self.original = None
        self.restore_required = False

    def read(self, name):
        raw = self.device.run("shell", "dumpsys", "package", PACKAGE)
        self.device.evidence("notification-permission/" + name + ".txt", raw)
        state = parse_notification_permission(raw)
        self.device.evidence("notification-permission/" + name + ".json", json.dumps(state, indent=2))
        return state

    def prepare(self):
        if not self.device.serial.startswith("emulator-") or self.device.run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Temporary notification authorization requires a disposable emulator")
        self.original = self.read("original")
        # Set before a mutation: even an adb error after applying the grant must restore.
        self.restore_required = True
        if not self.original["granted"]:
            self.device.run("shell", "pm", "grant", "--user", "0", PACKAGE, NOTIFICATION_PERMISSION)
        observed = self.read("authorized")
        if not observed["granted"]:
            raise AssertionError("POST_NOTIFICATIONS grant was not observed; profiling cannot start")
        return {"setup": "explicit_host_grant_before_ui", "original": self.original, "observed": observed,
                "note": "This profile uses granted notifications; denied-permission behavior is tested separately by instrumentation."}

    def restore(self):
        if not self.restore_required or self.original is None:
            raise RuntimeError("No restorable notification permission snapshot")
        current = self.read("before-restore")
        if current["granted"] != self.original["granted"]:
            operation = "grant" if self.original["granted"] else "revoke"
            self.device.run("shell", "pm", operation, "--user", "0", PACKAGE, NOTIFICATION_PERMISSION)
        current = self.read("after-grant-restore")
        before_flags, current_flags = set(self.original["flags"]), set(current["flags"])
        unsupported = (before_flags ^ current_flags) - RESTORABLE_PERMISSION_FLAGS.keys()
        if unsupported:
            raise AssertionError("Cannot safely restore changed permission flags: " + ", ".join(sorted(unsupported)))
        for operation, flags in [("clear-permission-flags", current_flags - before_flags),
                                 ("set-permission-flags", before_flags - current_flags)]:
            if flags:
                self.device.run("shell", "pm", operation, "--user", "0", PACKAGE, NOTIFICATION_PERMISSION,
                                *(RESTORABLE_PERMISSION_FLAGS[flag] for flag in sorted(flags)))
        observed = self.read("restored")
        if observed != self.original:
            raise AssertionError("Notification grant and complete permission flags did not return to the original state")
        return {"original": self.original, "observed": observed, "grant_and_flags_restored": True}


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


def parse_keyguard(raw):
    """Use explicit keyguard fields; a successful dismiss command is not an unlocked state."""
    match = re.search(r"\b(?:mKeyguardShowing|keyguardShowing|isStatusBarKeyguard)=(true|false)\b", raw)
    delegate = raw.split("KeyguardServiceDelegate", 1)[-1] if "KeyguardServiceDelegate" in raw else ""
    showing = re.search(r"\bshowing=(true|false)\b", delegate)
    secure = re.search(r"\bsecure=(true|false)\b", delegate)
    return {"showing": (showing or match)[1] == "true" if showing or match else None,
            "secure": secure[1] == "true" if secure else None}


def ui_observation(tree):
    if tree is None:
        return {"available": False, "scope": "active_window_only_not_all_windows"}
    nodes = list(tree.iter("node"))
    labels = {node.get(field, "") for node in nodes for field in ("text", "content-desc")}
    app_labels = {node.get(field, "") for node in nodes if node.get("package") == PACKAGE for field in ("text", "content-desc")}
    parents = {child: parent for parent in tree.iter() for child in parent}
    selected = []
    for node in nodes:
        label = node.get("text", "")
        if node.get("package") != PACKAGE or label not in ("首页", "事项", "陪伴", "小狗"):
            continue
        ancestor = node
        while ancestor is not None:
            if ancestor.get("selected") == "true":
                selected.append(label)
                break
            ancestor = parents.get(ancestor)
    error_labels = ("isn't responding", "is not responding", "无响应", "没有响应", "keeps stopping", "屡次停止")
    return {"available": True, "scope": "active_window_only_not_all_windows", "node_count": len(nodes),
            "packages": sorted({node.get("package", "") for node in nodes if node.get("package")}),
            "has_navigation": "首页" in app_labels and "小狗" in app_labels,
            "page_markers": {name: all(marker in app_labels for marker in markers) for name, markers in PAGE_MARKERS.items()},
            "selected_navigation": sorted(set(selected)),
            "system_error_dialog_text_observed": any(marker in label.lower() for label in labels for marker in error_labels)}


def scene_observation(raw, tree):
    activities, windows = raw.get("activities", ""), raw.get("windows", "")
    resumed = [line.strip() for line in activities.splitlines() if re.search(r"(?:mResumedActivity|topResumedActivity|ResumedActivity):?\s*[:=]?", line)]
    focus = [line.strip() for line in windows.splitlines() if "mCurrentFocus=" in line or "mFocusedApp=" in line]
    keyguard = parse_keyguard(raw.get("policy", ""))
    ui = ui_observation(tree)
    power = re.search(r"mWakefulness=(\w+)", raw.get("power", ""))
    app_resumed = any(PACKAGE + "/" in line for line in resumed) if resumed else None
    observations = []
    if keyguard["showing"] is True:
        observations.append("keyguard_showing")
    if power and power[1] != "Awake":
        observations.append("screen_not_awake")
    if ui.get("system_error_dialog_text_observed"):
        observations.append("system_error_dialog_text_observed")
    overlays = len(visible_overlay_blocks(windows))
    if app_resumed and not ui.get("has_navigation") and overlays:
        observations.append("app_resumed_but_navigation_absent_from_single_active_window_tree")
    if ui.get("has_navigation"):
        observations.append("main_navigation_observed")
    return {"resumed_activity_lines": resumed, "focus_lines": focus, "app_resumed": app_resumed,
            "keyguard": keyguard, "wakefulness": power[1] if power else None, "ui": ui,
            "visible_app_overlay_windows": overlays, "observations": observations,
            "root_cause": "unknown; raw contemporaneous evidence requires review",
            "anr_note": "lastanr and events are historical evidence; neither alone proves an ANR at this failure"}


class Device:
    def __init__(self, serial, output, adb="adb"):
        self.serial, self.output, self.executable = serial, output, adb
        self.calls = []
        self.sequence = 0
        self.stage = "preflight"
        self.run_id = uuid.uuid4().hex[:12]
        self.last_ui = None
        self.scenes = []
        self.failure_captures = []

    def run(self, *args, timeout=45, check=True, binary=False):
        started = time.monotonic()
        try:
            result = subprocess.run([self.executable, "-s", self.serial, *map(str, args)],
                                    capture_output=True, text=not binary, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            partial = error.stderr or b""
            if isinstance(partial, bytes):
                partial = partial.decode("utf-8", errors="replace")
            stdout = error.stdout or b""
            if isinstance(stdout, bytes):
                stdout = stdout.decode("utf-8", errors="replace")
            self.calls.append({"args": list(map(str, args)), "stage": self.stage, "exit_code": None,
                               "timed_out": True, "timeout_seconds": timeout,
                               "elapsed_seconds": round(time.monotonic() - started, 3), "stderr": partial[-2000:],
                               "partial_stdout": stdout[-2000:] if not binary else "binary response omitted"})
            raise
        stderr = result.stderr.decode("utf-8", errors="replace") if binary else result.stderr
        text = result.stdout if binary else result.stdout + result.stderr
        self.calls.append({"args": list(map(str, args)), "exit_code": result.returncode,
                           "stage": self.stage,
                           "elapsed_seconds": round(time.monotonic() - started, 3),
                           "stderr": stderr[-2000:]})
        if check and result.returncode:
            detail = stderr if binary else text
            raise RuntimeError(f"adb failed ({result.returncode}): {' '.join(map(str, args))}: {detail[-1200:]}")
        return text

    def evidence(self, name, text):
        path = self.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(text, bytes):
            path.write_bytes(text)
        else:
            path.write_text(text, encoding="utf-8")
        return str(path.relative_to(self.output))

    def tree(self, timeout=20):
        self.sequence += 1
        name = f"navigation/{self.sequence:04d}"
        remote = f"/sdcard/cutedog-profile-{self.run_id}-{self.sequence}.xml"
        self.last_ui = {"stage": self.stage, "sequence": self.sequence, "captured_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        try:
            stdout = self.run("shell", "uiautomator", "dump", remote, timeout=timeout, check=False)
            self.last_ui["dump_output"] = self.evidence(name + "-dump.txt", stdout)
            if self.calls and self.calls[-1].get("exit_code") not in (0, None):
                raise RuntimeError("UIAutomator dump returned an error; see the retained raw output")
            # A fresh path prevents a CLI failure with exit code zero from returning an old tree.
            raw = self.run("shell", "cat", remote, timeout=5)
            self.last_ui["xml"] = self.evidence(name + ".xml", raw)
            start = raw.find("<?xml")
            if start < 0:
                raise RuntimeError("UIAutomator produced no fresh accessibility XML")
            tree = ET.fromstring(raw[start:])
            self.last_ui["observation"] = ui_observation(tree)
            return tree
        except Exception as error:
            self.last_ui["error"] = str(error)
            raise
        finally:
            self.evidence(name + "-metadata.json", json.dumps(self.last_ui, ensure_ascii=False, indent=2))
            try:
                self.run("shell", "rm", "-f", remote, timeout=5, check=False)
            except Exception:
                pass

    def capture_scene(self, name, failure=False):
        """Read-only observations before cleanup or recovery input; errors never replace a failure."""
        folder = "scenes/" + re.sub(r"[^A-Za-z0-9_.-]", "_", name)
        record = {"name": name, "stage": self.stage, "failure": failure,
                  "captured_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                  "previous_ui_attempt": dict(self.last_ui) if self.last_ui else None, "evidence": {}, "errors": {}}
        raw = {}
        try:
            png = self.run("exec-out", "screencap", "-p", timeout=10, binary=True)
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("screencap did not return PNG bytes")
            record["evidence"]["screenshot"] = self.evidence(folder + "/screen.png", png)
        except Exception as error:
            record["errors"]["screenshot"] = str(error)
        commands = {"activities": ("activity", "activities"), "windows": ("window", "windows"),
                    "policy": ("window", "policy"), "power": ("power",),
                    "services": ("activity", "services", PACKAGE)}
        if failure:
            commands.update(processes=("activity", "processes"), lastanr=("activity", "lastanr"))
        for key, command in commands.items():
            try:
                raw[key] = self.run("shell", "dumpsys", *command, timeout=10)
                record["evidence"][key] = self.evidence(folder + "/" + key + ".txt", raw[key])
            except Exception as error:
                record["errors"][key] = str(error)
        if failure:
            try:
                events = self.run("shell", "logcat", "-b", "events", "-d", "-v", "threadtime", "-t", "200", "am_anr:I", "am_crash:I", "*:S", timeout=10)
                record["evidence"]["error_events"] = self.evidence(folder + "/error-events.txt", events)
            except Exception as error:
                record["errors"]["error_events"] = str(error)
        tree = None
        try:
            tree = self.tree(timeout=12)
            record["fresh_ui_attempt"] = dict(self.last_ui)
            record["evidence"]["xml"] = self.last_ui["xml"]
        except Exception as error:
            record["errors"]["fresh_ui"] = str(error)
            record["fresh_ui_attempt"] = dict(self.last_ui) if self.last_ui else None
        record["observation"] = scene_observation(raw, tree)
        record["capture_finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        self.evidence(folder + "/scene.json", json.dumps(record, ensure_ascii=False, indent=2))
        self.scenes.append(record)
        if failure:
            self.failure_captures.append(record)
        return record

    @staticmethod
    def find(tree, label):
        for node in tree.iter("node"):
            if node.get("package") != PACKAGE:
                continue
            if label not in (node.get("text", ""), node.get("content-desc", "")):
                continue
            bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1] and node.get("enabled", "true") == "true":
                return bounds
        return None

    def tap(self, label, scroll=False):
        for attempt in range(10 if scroll else 3):
            tree = self.tree()
            bounds = self.find(tree, label)
            if bounds:
                left, top, right, bottom = bounds
                self.run("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
                time.sleep(.7)
                return
            if scroll:
                if not ui_observation(tree)["has_navigation"]:
                    raise AssertionError("Cannot safely scroll: app navigation is absent from the single active-window tree")
                size = re.findall(r"(\d+)x(\d+)", self.run("shell", "wm", "size"))
                if not size:
                    raise RuntimeError("Cannot read display dimensions")
                width, height = map(int, size[-1])
                start, end = ((.78, .3) if attempt < 6 else (.3, .78))
                self.run("shell", "input", "swipe", width // 2, int(height * start), width // 2, int(height * end), 350)
            time.sleep(.6)
        raise AssertionError(f"Visible app control not found: {label}")

    def launch(self):
        self.await_unlocked()
        text = self.run("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=90)
        self.sequence += 1
        name = f"navigation/{self.sequence:04d}-launch"
        self.evidence(name + "-am-start.txt", text)
        if "Status: ok" not in text:
            raise AssertionError("Activity did not report Status: ok")
        for attempt in range(6):
            activities = self.run("shell", "dumpsys", "activity", "activities", timeout=10)
            self.evidence(name + f"-activities-{attempt + 1}.txt", activities)
            resumed = scene_observation({"activities": activities}, None)["app_resumed"]
            tree = self.tree()
            if resumed is True and self.find(tree, "稍后了解"):
                self.tap("稍后了解")
            elif resumed is True and self.find(tree, "首页") and self.find(tree, "小狗"):
                return
            time.sleep(1)
        raise AssertionError("MainActivity resumed state and navigation could not both be verified; the CLI UI tree covers only the active window")

    def await_unlocked(self):
        def unlocked():
            policy = self.run("shell", "dumpsys", "window", "policy", timeout=10)
            return self.screen_state()["interactive"] is True and parse_keyguard(policy)["showing"] is False
        self.wait(unlocked, 15, "Interactive and keyguard-dismissed states were not both observed")

    def show_page(self, label, page, capture=True):
        self.tap(label)
        self.wait(lambda: ui_observation(self.tree())["page_markers"][page], 12,
                  f"The {label} tab did not expose its actual {page} content")
        if not capture:
            return {"page": page, "content_verified": True}
        scene = self.capture_scene("page-" + page)
        if not scene["evidence"].get("screenshot") or not scene["evidence"].get("xml"):
            raise AssertionError(f"The {page} page was reached but PNG/XML evidence is incomplete: {scene['errors']}")
        if not scene["observation"]["ui"].get("page_markers", {}).get(page):
            raise AssertionError(f"The retained {page} XML did not contain the page's own content markers")
        return {"page": page, "content_verified": True, "scene": scene["name"], "evidence": scene["evidence"]}

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
    parser.add_argument("--disposable", action="store_true", help="Required: permit force-stop and temporary permissions on an isolated emulator")
    parser.add_argument("--require-rig-metrics", action="store_true", help="Return failure when real rig counters are unavailable or legacy art is installed")
    parser.add_argument("--expected-apk-sha256", help="Optional expected hash of the installed base.apk; rejects mismatched binaries")
    parser.add_argument("--apk", type=Path, help="Actual APK file; requires --expected-apk-sha256 and enables strict rig checks when bundled")
    parser.add_argument("--tested-apks", type=Path, help="Instrumentation tested-apks.json; validates its actual APK before selecting strict rig checks")
    parser.add_argument("--source-commit", help="Declared APK source commit, independently recorded from the test tooling commit")
    parser.add_argument("--start-label", default="召唤小狗")
    parser.add_argument("--stop-label", default="停止桌宠")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("--disposable is required; this workflow temporarily revokes overlay access and stops the app")
    if not args.serial.startswith("emulator-"):
        parser.error("This permission-changing profile is restricted to disposable emulators")
    if not 5 <= args.duration <= 60 or not 1 <= args.cycles <= 10:
        parser.error("--duration must be 5–60 and --cycles 1–10")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "profile.json").exists():
        parser.error("Use a new output directory; existing measurement evidence must not be overwritten")
    device = Device(args.serial, args.output, args.adb)
    report = {"schema_version": 2, "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "source_commit": args.source_commit or os.getenv("GITHUB_SHA"), "test_tool_commit": os.getenv("GITHUB_SHA"), "github_run_id": os.getenv("GITHUB_RUN_ID"),
              "serial": args.serial, "checks": [], "measurements": [], "restoration": [], "scenes": device.scenes,
              "performance_acceptance": "No device-specific FPS/PSS budget has been imposed; measurements are evidence, not a commercial-quality performance claim.",
              "limitations": ["Emulator CPU, GPU, memory and latency are environment observations, not phone battery or thermal measurements.",
                              "Short PSS trends cannot prove absence of leaks. Use long physical-device sessions and heap/allocation profiling for a release claim.",
                              "Screen-off tests do not prove secure credential-lockscreen, OEM battery optimization, Doze alarms or reboot behavior."]}
    initial_mode = initial_timeout = initial_screen = None
    changed_op = changed_timeout = app_mutated = False
    notification_permission = ProfileNotificationPermission(device)
    exit_code = 1

    def capture_failure(name):
        if device.failure_captures and device.failure_captures[-1]["stage"] == name:
            return device.failure_captures[-1]["name"]
        try:
            return device.capture_scene("failure-" + name, failure=True)["name"]
        except Exception as error:
            # An unavailable adb or full evidence disk must not conceal the original failure.
            report.setdefault("capture_errors", []).append({"stage": name, "error": str(error)})
            return None

    def check(name, action, strict=True):
        device.stage = name
        started = time.monotonic()
        try:
            detail = action()
            report["checks"].append({"name": name, "status": "passed", "details": detail, "seconds": time.monotonic() - started})
            print("PASS " + name, flush=True)
            return detail
        except Exception as error:
            elapsed = time.monotonic() - started
            scene = capture_failure(name)
            report["checks"].append({"name": name, "status": "failed", "error": str(error), "seconds": elapsed, "failure_scene": scene})
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
        if args.tested_apks and args.apk:
            raise ValueError("Use either --tested-apks or --apk, not both")
        binding = tested_apk_binding(args.tested_apks) if args.tested_apks else (
            verified_artwork_binding(args.apk, args.expected_apk_sha256) if args.apk else None)
        if binding:
            if args.expected_apk_sha256 and args.expected_apk_sha256 != binding["sha256"]:
                raise ValueError("Expected APK hash conflicts with tested-apks identity")
            args.expected_apk_sha256 = binding["sha256"]
            args.require_rig_metrics = args.require_rig_metrics or binding["contains_authored_manifest"]
            report["tested_apk_binding"] = binding
        report["require_rig_metrics"] = args.require_rig_metrics
        device.run("wait-for-device")
        if device.run("shell", "getprop", "sys.boot_completed").strip() != "1":
            raise RuntimeError("Android has not completed boot")
        api = int(device.run("shell", "getprop", "ro.build.version.sdk").strip())
        if api < 26:
            raise RuntimeError("This application requires Android API26 or later")
        report["device"] = {"api": api, "model": device.run("shell", "getprop", "ro.product.model").strip(),
                            "abi": device.run("shell", "getprop", "ro.product.cpu.abi").strip(),
                            "emulator": device.run("shell", "getprop", "ro.kernel.qemu").strip() == "1"}
        if not report["device"]["emulator"] or not args.serial.startswith("emulator-"):
            raise RuntimeError("This profile changes notification permissions and is restricted to a disposable emulator")
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
        if api >= 33:
            report["notification_permission_setup"] = check("prepare-notification-permission", notification_permission.prepare)
        changed_op = True
        device.run("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        changed_timeout = True
        device.run("shell", "settings", "put", "system", "screen_off_timeout", "600000")
        device.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.run("shell", "wm", "dismiss-keyguard")
        check("initial-launch-unlocked", device.launch)
        for label, page in (("首页", "home"), ("事项", "reminders"), ("陪伴", "companion"), ("小狗", "settings")):
            check("visual-page-" + page, lambda label=label, page=page: device.show_page(label, page))
        check("return-to-home-before-measurement", lambda: device.show_page("首页", "home", capture=False))
        device.stage = "foreground-measurement"
        time.sleep(5)
        foreground = device.interval("foreground", "activity", args.duration, ticks_per_second)
        report["measurements"].append(foreground)
        rig_assertion("foreground-real-rig-advances", foreground)
        check("foreground-memory-is-measurable", lambda: assert_result(foreground["after_memory"]["total_pss_kib"] is not None,
                                                                        "No app PSS measurement was returned", foreground["after_memory"]), strict=False)
        check("start-overlay-from-user-control", lambda: device.start_overlay(args.start_label) or device.service_state())
        device.capture_scene("overlay-active")
        device.stage = "overlay-measurement"
        time.sleep(5)
        overlay = device.interval("overlay", "overlay", args.duration, ticks_per_second)
        report["measurements"].append(overlay)
        rig_assertion("overlay-real-rig-advances", overlay)
        device.stage = "screen-off"
        device.run("shell", "input", "keyevent", "KEYCODE_SLEEP")
        device.wait(lambda: device.screen_state()["interactive"] is False, 10, "Screen did not enter a non-interactive state")
        device.capture_scene("screen-off")
        time.sleep(3)
        paused = device.interval("screen-off", "overlay", min(args.duration, 15), ticks_per_second)
        report["measurements"].append(paused)
        rig_assertion("screen-off-render-and-decode-stopped", paused, paused=True)
        check("screen-remained-off", lambda: assert_result(paused["before_screen"]["interactive"] is False and paused["after_screen"]["interactive"] is False,
                                                            "Screen woke during the paused measurement", paused["after_screen"]))
        device.stage = "wake-and-dismiss-keyguard"
        device.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.run("shell", "wm", "dismiss-keyguard")
        check("screen-resumed-and-keyguard-dismissed", device.await_unlocked)
        device.capture_scene("overlay-resumed")
        device.stage = "resumed-overlay-measurement"
        time.sleep(3)
        resumed = device.interval("resumed-overlay", "overlay", min(args.duration, 12), ticks_per_second)
        report["measurements"].append(resumed)
        rig_assertion("unlock-real-rig-resumed", resumed)
        check("service-survived-screen-off", lambda: assert_result(device.service_state()["foreground"], "Foreground overlay service did not survive screen-off", device.service_state()))

        cycles = []
        for index in range(args.cycles):
            check(f"stop-overlay-cycle-{index + 1}", lambda: device.stop_overlay(args.stop_label) or device.service_state())
            device.stage = f"stopped-cycle-{index + 1}-measurement"
            time.sleep(2)
            snap = device.snapshot(f"stopped-cycle-{index + 1}", "activity")
            cycles.append({"cycle": index + 1, "memory": snap["memory"], "pet": snap["pet"], "evidence": snap["evidence"]})
            check(f"restart-overlay-cycle-{index + 1}", lambda: device.start_overlay(args.start_label) or device.service_state())
            time.sleep(2)
        pss = [row["memory"]["total_pss_kib"] for row in cycles]
        report["retention_observations"] = {"cycles": cycles, "last_minus_first_pss_kib": pss[-1] - pss[0] if pss and all(value is not None for value in pss) else None,
                                            "leak_verdict": "not_proven", "reason": "PSS includes caches and GC timing; these repeated lifecycle samples are regression evidence, not a heap-leak proof."}
        device.stage = "revoke-overlay-permission"
        device.run("shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "ignore")
        check("permission-revocation-stops-service", lambda: device.wait(lambda: not device.service_state()["present"], 15,
                                                                          "OverlayService remains after actual overlay-permission revocation") or device.service_state())
        check("permission-revocation-removes-window", lambda: device.wait(lambda: not visible_overlay_blocks(device.run("shell", "dumpsys", "window", "windows")),
                                                                          10, "Overlay surface remains after permission revocation") or {"visible_overlay_windows": 0})
        device.evidence("after-revocation-services.txt", device.run("shell", "dumpsys", "activity", "services", PACKAGE))
        device.capture_scene("overlay-permission-revoked")
        device.stage = "restore-overlay-permission"
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
        report["failed_stage"] = device.stage
        report["failure_scene"] = capture_failure(device.stage)
        print("FAIL " + str(error), file=sys.stderr, flush=True)
    finally:
        device.stage = "restoration"
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
            device.stage = name
            try:
                device.run(*command)
                if name == "restore-overlay-appop" and parse_app_op(device.run("shell", "cmd", "appops", "get", PACKAGE, "SYSTEM_ALERT_WINDOW")) != initial_mode:
                    raise AssertionError("Overlay app-op restoration was not observed")
                if name == "restore-screen-timeout" and device.run("shell", "settings", "get", "system", "screen_off_timeout").strip() != initial_timeout:
                    raise AssertionError("Screen timeout restoration was not observed")
                report["restoration"].append({"name": name, "status": "passed"})
            except Exception as error:
                report["restoration"].append({"name": name, "status": "failed", "error": str(error),
                                              "failure_scene": capture_failure(name)})
                exit_code = 1; report["status"] = "failed"
        if notification_permission.restore_required:
            name = "restore-notification-permission"
            device.stage = name
            try:
                detail = notification_permission.restore()
                report["restoration"].append({"name": name, "status": "passed", "details": detail})
            except Exception as error:
                report["restoration"].append({"name": name, "status": "failed", "error": str(error),
                                              "failure_scene": capture_failure(name)})
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
