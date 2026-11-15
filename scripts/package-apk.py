#!/usr/bin/env python3
"""Verify and collect an already-built debug APK, checksum, and metadata."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

repo = Path(__file__).resolve().parent.parent
sdk = Path(os.environ.get("ANDROID_HOME", "/workspace/tooling/android-sdk"))
source = repo / "app/build/outputs/apk/debug/app-debug.apk"
if not source.is_file():
    raise SystemExit("Build the APK first: ./gradlew assembleDebug")
verification = subprocess.run([str(sdk / "build-tools/35.0.0/apksigner"), "verify",
                               "--verbose", "--print-certs", str(source)],
                              check=True, capture_output=True, text=True).stdout
badging = subprocess.run([str(sdk / "build-tools/35.0.0/aapt"), "dump", "badging", str(source)],
                         check=True, capture_output=True, text=True).stdout
output = repo / "artifacts"
output.mkdir(exist_ok=True)
target = output / "CuteDog-0.1.0-debug.apk"
shutil.copy2(source, target)
sha256 = hashlib.sha256(target.read_bytes()).hexdigest()
(output / "SHA256SUMS.txt").write_text(f"{sha256}  {target.name}\n")
(output / "signature-verification.txt").write_text(verification)
(output / "apk-metadata.txt").write_text(badging)
(output / "BUILD-INFO.json").write_text(json.dumps({
    "file": target.name, "version": "0.1.0", "build": "debug",
    "signing": "Android debug certificate; not production signing",
    "minimumAndroid": "8.0 (API 26)", "targetSdk": 35,
    "sha256": sha256, "bytes": target.stat().st_size,
}, ensure_ascii=False, indent=2) + "\n")
print(target)
print("SHA-256:", sha256)
print("APK signature and metadata verified; installation/device tests are separate.")
