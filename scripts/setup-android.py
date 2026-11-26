#!/usr/bin/env python3
"""Install pinned Linux Android SDK tools, verifying the archive before use.

Usage: python3 scripts/setup-android.py [tooling-directory]
Requires Python 3.12+ and curl. Installs a verified Temurin JDK 17.
The SDK package license prompt is accepted for this requested development setup.
"""
from pathlib import Path
import hashlib
import os
import subprocess
import sys
import tarfile
from urllib.parse import urlparse
import zipfile

root = Path(sys.argv[1] if len(sys.argv) > 1 else "/workspace/tooling").resolve()
sdk = root / "android-sdk"
archive = root / "downloads/commandlinetools-linux-13114758_latest.zip"
manager = sdk / "cmdline-tools/19.0/bin/sdkmanager"
# Verified against Google's repository2-1.xml SHA-1, then pinned as SHA-256.
checksum = "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"
root.mkdir(parents=True, exist_ok=True)
archive.parent.mkdir(parents=True, exist_ok=True)
jdk = root / "jdk-17.0.17+10"
if not (jdk / "bin/javac").exists():
    jdk_archive = root / "downloads/jdk17.tar.gz"
    if not jdk_archive.exists():
        subprocess.run(["curl", "-fSL", "--retry", "2", "--max-time", "300",
                        "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz",
                        "-o", str(jdk_archive)], check=True)
    # Publisher SHA-256 (the release's .sha256.txt asset).
    if hashlib.sha256(jdk_archive.read_bytes()).hexdigest() != "992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75":
        raise SystemExit("Temurin JDK SHA-256 mismatch; installation stopped.")
    with tarfile.open(jdk_archive) as bundle:
        bundle.extractall(root, filter="data")
os.environ["JAVA_HOME"] = str(jdk)
if Path("/etc/ssl/certs/java/cacerts").is_file():
    os.environ["JAVA_OPTS"] = (os.environ.get("JAVA_OPTS", "") +
        " -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts").strip()
if not manager.exists():
    if not archive.exists():
        subprocess.run(["curl", "-fSL", "--retry", "2", "--max-time", "300",
                        "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip",
                        "-o", str(archive)], check=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise SystemExit("Android SDK archive SHA-256 mismatch; installation stopped.")
    staging = root / "sdk-unpack"
    with zipfile.ZipFile(archive) as file:
        file.extractall(staging)
    manager.parent.parent.parent.mkdir(parents=True, exist_ok=True)
    (staging / "cmdline-tools").rename(sdk / "cmdline-tools/19.0")
    staging.rmdir()
    for entry in manager.parent.iterdir():
        entry.chmod(0o755)
proxy = urlparse(os.environ.get("HTTPS_PROXY", os.environ.get("https_proxy", "")))
command = [str(manager), "--sdk_root=" + str(sdk)]
if proxy.hostname:
    command += ["--proxy=http", "--proxy_host=" + proxy.hostname,
                "--proxy_port=" + str(proxy.port or 80)]
child_environment = os.environ.copy()
child_environment.setdefault("ANDROID_USER_HOME", str(root / "android-user"))
subprocess.run(command + ["--licenses"], input="y\n" * 100, text=True,
               env=child_environment, check=True)
subprocess.run(command + ["platforms;android-35", "build-tools;35.0.0", "platform-tools", "ndk;28.2.13676358", "cmake;3.22.1"],
               env=child_environment, check=True)
print("SDK installed:", sdk)
print("Build: ANDROID_HOME=" + str(sdk) + " GRADLE_USER_HOME=" + str(root / "gradle-home")
      + " python3 scripts/cloud-gradle.py --no-daemon testDebugUnitTest lintDebug assembleDebug")
