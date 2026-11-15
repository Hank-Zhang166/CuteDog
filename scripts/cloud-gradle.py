#!/usr/bin/env python3
"""Run the checked-in Gradle wrapper with the cloud's existing HTTP proxy.

No authentication values are logged or persisted. Normal developer machines may
run ./gradlew directly. This adapter is only needed for Java on proxy-only hosts.
"""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse

repo = Path(__file__).resolve().parent.parent
tooling = Path(os.environ.get("CUTEDOG_TOOLING", "/workspace/tooling"))
if (tooling / "jdk-17.0.17+10/bin/javac").is_file():
    os.environ["JAVA_HOME"] = str(tooling / "jdk-17.0.17+10")
if (tooling / "android-sdk").is_dir():
    os.environ.setdefault("ANDROID_HOME", str(tooling / "android-sdk"))
    os.environ.setdefault("ANDROID_USER_HOME", str(tooling / "android-user"))
    os.environ.setdefault("GRADLE_USER_HOME", str(tooling / "gradle-home"))
command = [str(repo / "gradlew")]
# Use the machine's maintained CA trust for the managed HTTPS proxy. Never
# bypass certificate checks; the downloaded JDK has only upstream root CAs.
system_trust = Path("/etc/ssl/certs/java/cacerts")
if system_trust.is_file():
    trust_option = "-Djavax.net.ssl.trustStore=" + str(system_trust)
    os.environ["JAVA_OPTS"] = (os.environ.get("JAVA_OPTS", "") + " " + trust_option).strip()
    command.append(trust_option)
proxy = urlparse(os.environ.get("HTTPS_PROXY", os.environ.get("https_proxy", "")))
if proxy.hostname:
    for protocol in ("http", "https"):
        command += [f"-D{protocol}.proxyHost={proxy.hostname}", f"-D{protocol}.proxyPort={proxy.port or 80}"]
# Native JVM nonProxyHosts uses a pipe-delimited list. Retain local traffic.
command += ["-Dhttp.nonProxyHosts=localhost|127.*|[::1]"]
command += sys.argv[1:]
raise SystemExit(subprocess.call(command, cwd=repo))
