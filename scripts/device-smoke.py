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
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PACKAGE = "com.cutedog.app"


class Device:
    def __init__(self, serial, output):
        if not serial.startswith("emulator-"):
            raise ValueError("This destructive test is restricted to a disposable emulator.")
        self.serial, self.output = serial, output
        self.width, self.height = 720, 1280
        self.calls = []
        self.external_dialogs = []
        self.app_pids = set()

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

    def edit(self, index, value):
        fields = [n for n in self.tree().iter("node") if n.get("class") == "android.widget.EditText"]
        field = fields[index]
        self.tap_node(field)
        old = field.get("text", "")
        if old:
            self.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            self.adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * len(old)))
        self.adb("shell", "input", "text", value.replace(" ", "%s"))
        ime = self.adb("shell", "dumpsys", "input_method")
        if re.search(r"m(?:Is)?Input(?:View)?Shown=true", ime):
            self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.8)

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
        self.wait_text("首页", seconds=60)
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
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    device = Device(args.serial, args.output)
    metadata = {"apk": args.apk.name, "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                "expected_sha256": args.expected_sha256, "test_source_commit": os.getenv("GITHUB_SHA"),
                "github_run_id": os.getenv("GITHUB_RUN_ID"), "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "scope": "Actual installed APK UI/overlay checks; no remote AI or physical-device performance claim."}

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
            try:
                result["screenshot"] = device.screenshot("failure-" + name)
            except Exception as capture_error:
                result["capture_error"] = str(capture_error)
            results.append(result)
            print("FAIL " + name + ": " + str(error), flush=True)
            return False

    def prepare():
        if metadata["apk_sha256"] != args.expected_sha256.lower():
            raise AssertionError("Downloaded APK does not match the pinned SHA-256")
        device.adb("wait-for-device")
        metadata.update(api=device.adb("shell", "getprop", "ro.build.version.sdk").strip(),
                        model=device.adb("shell", "getprop", "ro.product.model").strip(),
                        abi=device.adb("shell", "getprop", "ro.product.cpu.abi").strip())
        installed = device.adb("install", "-r", args.apk, timeout=180)
        if "Success" not in installed:
            raise AssertionError(installed)
        device.adb("shell", "pm", "clear", PACKAGE)
        # Keep the AVD's boot-time display configuration. Changing density during the
        # launcher's first start can cause an unrelated Pixel Launcher ANR on API 35.
        size = device.adb("shell", "wm", "size")
        sizes = re.findall(r"(\d+)x(\d+)", size)
        if not sizes:
            raise AssertionError("Cannot read actual display dimensions")
        device.width, device.height = map(int, sizes[-1])
        density = device.adb("shell", "wm", "density").strip()
        device.adb("shell", "settings", "put", "global", "window_animation_scale", "0")
        device.adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
        device.adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
        device.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        device.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        device.adb("shell", "wm", "dismiss-keyguard")
        return {"install": installed.strip(), "resolution": f"{device.width}x{device.height}", "density": density}

    def tabs():
        device.launch()
        paths = []
        for label, marker, filename in [("首页", "一日一暖", "home"), ("事项", "把小事记好", "reminders"),
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
        checkboxes = [n for n in device.tree().iter("node") if n.get("checkable") == "true" and n.get("class") == "android.widget.CheckBox"]
        if len(checkboxes) != 1:
            raise AssertionError("Expected exactly one test-reminder checkbox")
        device.tap_node(checkboxes[0])
        device.tap("完成")
        device.wait_text("Device check updated")
        complete = device.screenshot("reminder-completed")
        checked = [n for n in device.tree().iter("node") if n.get("class") == "android.widget.CheckBox" and n.get("checked") == "true"]
        if len(checked) != 1:
            raise AssertionError("Completed reminder checkbox is not checked")
        device.tap_node(checked[0])
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
        (args.output / "overlay-window-before.txt").write_text(window)
        first = device.screenshot("overlay-on-launcher")
        cx, cy = (left + right) // 2, (top + bottom) // 2
        destination_x = int(device.width * (.2 if cx > device.width / 2 else .8))
        destination_y = max(120, min(device.height - 180, cy - 180))
        device.long_drag(cx, cy, destination_x, destination_y)
        time.sleep(2)
        after, after_window = device.overlay_frame()
        if abs(after[0] - before[0]) + abs(after[1] - before[1]) < 40:
            raise AssertionError("Long drag did not change overlay position: " + str(after))
        (args.output / "overlay-window-after.txt").write_text(after_window)
        second = device.screenshot("overlay-after-drag")
        # Touch the launcher away from the pet: opening the app drawer is a real external interaction.
        def launcher_labels(tree):
            labels = set()
            for node in tree.iter("node"):
                if "launcher" in node.get("package", "").lower():
                    labels.update(value for value in [node.get("text", ""), node.get("content-desc", "")] if value)
            return labels
        labels_before = launcher_labels(device.tree())
        swipe_x = 80 if after[0] > device.width / 2 else device.width - 80
        device.adb("shell", "input", "swipe", swipe_x, device.height - 80, swipe_x, device.height // 3, 500)
        time.sleep(2)
        launcher = device.tree()
        new_labels = launcher_labels(launcher) - labels_before
        if len(new_labels) < 3:
            raise AssertionError("Outside-window swipe did not expose at least three new launcher app-drawer labels")
        drawer = device.screenshot("overlay-launcher-touch")
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
                "new_launcher_labels_after_touch": sorted(new_labels),
                "service_stopped_from_ui": True, "screenshots": [first, second, drawer]}

    try:
        if step("install-pinned-apk", prepare):
            step("launch-and-four-tabs", tabs)
            step("reminder-ui-lifecycle", reminder_crud)
            step("chat-without-credentials", offline_chat)
            step("overlay-and-drag", overlay)
    finally:
        # This emulator is disposable; never collect any API keys or private chat contents.
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
            device.adb("shell", "am", "force-stop", PACKAGE, check=False)
            device.adb("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "default", check=False)
        except Exception as error:
            metadata["cleanup_or_log_collection_error"] = str(error)
        metadata["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        metadata["external_environment_dialogs"] = device.external_dialogs
        report = {"metadata": metadata, "assertions": results,
                  "passed": sum(r["status"] == "passed" for r in results),
                  "failed": sum(r["status"] == "failed" for r in results)}
        (args.output / "assertions.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (args.output / "adb-commands.json").write_text(json.dumps(device.calls, ensure_ascii=False, indent=2) + "\n")
    return 0 if results and all(r["status"] == "passed" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
