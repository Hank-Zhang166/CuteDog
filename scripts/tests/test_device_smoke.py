"""Offline host boundaries only; these fixtures are not Android/UI/upgrade evidence."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("device_smoke", ROOT / "scripts/device-smoke.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class SmokeBoundaryTests(unittest.TestCase):
    def test_text_injection_waits_for_each_real_field_commit_before_hiding_ime(self):
        expected = "Preserve this real UI reminder across the signed APK upgrade"
        class Input(smoke.Device):
            value = ""
            pending = None
            polls = 0
            injected = []
            hidden = False
            def tree(self):
                if self.pending is not None:
                    self.polls += 1
                    if self.polls >= 2:
                        self.value, self.pending = self.pending, None
                root = ET.Element("hierarchy")
                ET.SubElement(root, "node", {"class":"android.widget.EditText", "text":self.value})
                return root
            def tap_node(self, node):
                pass
            def adb(self, *args, **kwargs):
                if args[:3] == ("shell", "input", "text"):
                    if self.pending is not None:
                        raise AssertionError("Next input arrived before the previous field commit")
                    chunk = args[3].replace("%s", " ")
                    self.injected.append(chunk)
                    self.pending, self.polls = self.value + chunk, 0
                elif args == ("shell", "dumpsys", "input_method"):
                    return "mInputShown=true"
                elif args == ("shell", "input", "keyevent", "KEYCODE_BACK"):
                    if self.value != expected or self.pending is not None:
                        raise AssertionError("IME hidden before complete expected text was committed")
                    self.hidden = True
                return ""
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(smoke.time, "sleep"):
            device = Input("emulator-5554", Path(temporary))
            device.edit(0, expected)
            self.assertEqual(expected, "".join(device.injected))
            self.assertTrue(device.hidden)
            self.assertEqual(expected, device.value)
            self.assertTrue(all(item["matched"] for item in device.edit_checks))
            self.assertEqual(["before-hide-ime", "after-hide-ime"], [item["phase"] for item in device.edit_checks[-2:]])

    def test_truncated_baseline_details_fail_before_save_and_retain_exact_observation(self):
        expected = "Preserve this real UI reminder across the signed APK upgrade"
        observed = "Preserve this real UI reminder across the signed APK upgra"
        class Truncated(smoke.Device):
            def tree(self):
                root = ET.Element("hierarchy")
                ET.SubElement(root, "node", {"class":"android.widget.EditText", "text":observed})
                return root
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            device = Truncated("emulator-5554", output)
            with mock.patch.object(smoke.time, "monotonic", side_effect=[0, 1, 13]), mock.patch.object(smoke.time, "sleep"):
                with self.assertRaisesRegex(AssertionError, "Input not committed before save"):
                    device.wait_edit_value(0, expected, "before-hide-ime")
            check = json.loads((output / "text-input/checks.json").read_text())[0]
            self.assertFalse(check["matched"])
            self.assertEqual(expected, check["expected"])
            self.assertEqual(observed, check["observed"])
            self.assertTrue((output / check["xml"]).is_file())

    def test_package_version_uses_real_archived_api26_and_api35_records(self):
        for api in (26, 35):
            raw = (ROOT / f"scripts/tests/fixtures/system-recovery-uid/api{api}-package.stdout.txt").read_text()
            self.assertEqual({"version_code": 1, "version_name": "0.1.0"}, smoke.package_version(raw))

    def test_package_version_rejects_missing_target_and_missing_fields(self):
        for raw in ("Package [other.package] (x):\n versionCode=2 versionName=1.0\n",
                    "Package [com.cutedog.app] (x):\n appId=10209\n"):
            with self.assertRaises(AssertionError):
                smoke.package_version(raw)

    def test_actual_installed_bytes_must_match_pin_and_failure_keeps_observation(self):
        class Installed(smoke.Device):
            def adb(self, *args, **kwargs):
                if args[:3] == ("shell", "pm", "path"):
                    return "package:/data/app/fixture/base.apk\n"
                if args[0] == "pull":
                    Path(args[2]).write_bytes(b"actual installed fixture")
                    return "copied"
                return "Package [com.cutedog.app] (x):\n versionCode=2 versionName=0.2.0-preview.1\n"
        with tempfile.TemporaryDirectory() as temporary:
            device = Installed("emulator-5554", Path(temporary))
            actual = hashlib.sha256(b"actual installed fixture").hexdigest()
            self.assertEqual(actual, device.installed_apk(actual, "matches")["sha256"])
            with self.assertRaisesRegex(AssertionError, "installed base APK"):
                device.installed_apk("0" * 64, "mismatch")
            self.assertEqual(actual, json.loads((Path(temporary) / "mismatch-installed-apk.json").read_text())["sha256"])

    def test_first_run_real_control_driver_accepts_leaf_continue_nodes(self):
        class Introduction(smoke.Device):
            page = 0
            def adb(self, *args, **kwargs):
                return "Status: ok\n" if args[:3] == ("shell", "am", "start") else "123\n"
            def tree(self):
                labels = ["首页"] if self.page == 5 else ["稍后了解", "开始陪伴" if self.page == 4 else "继续"]
                root = ET.Element("hierarchy")
                for label in labels:
                    ET.SubElement(root, "node", text=label)
                return root
            def tap_node(self, node):
                self.asserted = node.get("text")
                self.page += 1
            def screenshot(self, name):
                return "fixture/" + name
        with tempfile.TemporaryDirectory() as temporary:
            device = Introduction("emulator-5554", Path(temporary))
            device.launch()
            self.assertEqual(5, device.page)
            self.assertEqual(["继续"] * 4 + ["开始陪伴"], [item["action"] for item in device.onboarding])

    def test_database_observation_honors_wal_and_preserves_archived_original_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "fixture.db"
            connection = sqlite3.connect(database)
            try:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA wal_autocheckpoint=0")
                connection.execute("CREATE TABLE reminders(id INTEGER PRIMARY KEY, title TEXT, details TEXT)")
                connection.execute("INSERT INTO reminders VALUES(7, 'Upgrade-fixture', 'actual fixture details')")
                connection.commit()
                original = {suffix: Path(str(database) + suffix).read_bytes() for suffix in ("", "-wal", "-shm")}
                output = root / "output"
                output.mkdir()
                device = smoke.Device("emulator-5554", output)
                def read_bytes(command, **kwargs):
                    suffix = command[-1].removeprefix("databases/cutedog-reminders.db")
                    return subprocess.CompletedProcess(command, 0, original[suffix], b"")
                def observed_files(*args, **kwargs):
                    return "\n".join("cutedog-reminders.db" + suffix for suffix in original) if "ls" in args else ""
                with mock.patch.object(device, "adb", side_effect=observed_files), mock.patch.object(smoke.subprocess, "run", side_effect=read_bytes):
                    result = device.reminder_snapshot("snapshot", "Upgrade-fixture")
                self.assertEqual(7, result["fixture"]["id"])
                self.assertEqual("actual fixture details", result["fixture"]["details"])
                for suffix, data in original.items():
                    self.assertEqual(data, (output / "snapshot" / ("cutedog-reminders.db" + suffix)).read_bytes())
            finally:
                connection.close()

    def test_existing_installation_is_never_cleared_captured_or_mutated(self):
        calls = []
        class Existing(smoke.Device):
            def adb(self, *args, **kwargs):
                calls.append(args)
                if args == ("shell", "getprop", "ro.kernel.qemu"):
                    return "1\n"
                if args == ("shell", "pm", "path", smoke.PACKAGE):
                    return "package:/data/app/existing/base.apk\n"
                return ""
            def screenshot(self, name):
                raise AssertionError("Must not capture existing user contents")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "fixture.apk"
            apk.write_bytes(b"not a real APK; host-only safety fixture")
            output = root / "evidence"
            argv = ["device-smoke.py", "--apk", str(apk), "--expected-sha256", smoke.file_sha256(apk),
                    "--source-commit", "a" * 40, "--disposable", "--output", str(output)]
            with mock.patch.object(smoke, "Device", Existing), mock.patch.object(sys, "argv", argv):
                self.assertEqual(1, smoke.main())
            report = json.loads((output / "assertions.json").read_text())
            self.assertEqual(1, report["failed"])
            self.assertIn("capture_skipped", report["assertions"][0])
            self.assertEqual("not_needed; no installation owned by this run", report["metadata"]["cleanup"])
            self.assertEqual([("wait-for-device",), ("shell", "getprop", "ro.kernel.qemu"),
                              ("shell", "pm", "path", smoke.PACKAGE)], calls)


if __name__ == "__main__":
    unittest.main()
