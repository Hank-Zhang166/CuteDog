"""Offline host validation fixtures; no Android or release acceptance is implied."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("ui_input", Path(__file__).resolve().parents[1] / "prepare-ui-validation.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PublishedApkInputTest(unittest.TestCase):
    def request(self):
        return {"schemaVersion":1, "apkUrl":"https://github.com/example/project/releases/download/v2/final.apk",
                "apkSha256":"a"*64, "sourceCommit":"b"*40}

    def test_explicit_final_identity_and_optional_upgrade_pair(self):
        value=self.request()
        self.assertEqual(value, m.validate_request(value))
        value.update(baselineUrl="https://github.com/example/project/releases/download/v1/old.apk", baselineSha256="c"*64)
        self.assertEqual(value, m.validate_request(value))

    def test_missing_final_identity_never_falls_back_to_old_apk(self):
        for key in ("apkUrl","apkSha256","sourceCommit"):
            value=self.request();del value[key]
            with self.assertRaises(ValueError):m.validate_request(value)
        for key,value in (("apkSha256","latest"),("sourceCommit","main"),("schemaVersion",True)):
            request=self.request();request[key]=value
            with self.assertRaises(ValueError):m.validate_request(request)

    def test_rejects_incomplete_baseline_and_unsafe_url_inputs(self):
        request=self.request();request["baselineUrl"]="https://github.com/example/old.apk"
        with self.assertRaises(ValueError):m.validate_request(request)
        for url in ("http://example.com/final.apk","https://token@example.com/final.apk","https://example.com/final.apk#fragment", "https://example.com/a b.apk"):
            request=self.request();request["apkUrl"]=url
            with self.assertRaises(ValueError):m.validate_request(request)


if __name__ == "__main__":unittest.main()
