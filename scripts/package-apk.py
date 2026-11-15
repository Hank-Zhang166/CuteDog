#!/usr/bin/env python3
"""Inspect an existing signed APK; default invocation packages the debug CI build.

This script never builds, signs, uploads, or infers a signing identity from a filename.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parent.parent
SHA_RE = re.compile(r"[0-9a-f]{40}")
VERSION_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,99}")
APK_FIELDS = {"file", "bytes", "sha256", "applicationId", "versionName", "versionCode",
              "minimumSdk", "targetSdk", "debuggable", "signerCertificateSha256"}
PROVENANCE_NOTE = ("sourceCommit identifies the declared source checkout. APK metadata and "
                   "signature verification do not prove reproducible correspondence to that source.")


def run(args, cwd=None):
    return subprocess.run([str(arg) for arg in args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_tools(argument=None):
    if argument:
        return Path(argument)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    return Path(sdk or "/workspace/tooling/android-sdk") / "build-tools/35.0.0"


def parse_metadata(badging, verification):
    package = re.search(r"^package: (.+)$", badging, re.MULTILINE)
    if not package:
        raise ValueError("aapt did not report APK package metadata")
    attributes = dict(re.findall(r"(\w+)='([^']*)'", package[1]))
    try:
        version = attributes["versionName"]
        package_id = attributes["name"]
        code = int(attributes["versionCode"])
        minimum = int(re.search(r"^sdkVersion:'(\d+)'$", badging, re.MULTILINE)[1])
        target = int(re.search(r"^targetSdkVersion:'(\d+)'$", badging, re.MULTILINE)[1])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("aapt metadata is incomplete or nonnumeric") from error
    if not VERSION_RE.fullmatch(version):
        raise ValueError("APK versionName is not safe for a release asset/tag")
    if not re.fullmatch(r"[A-Za-z]\w*(?:\.[A-Za-z]\w*)+", package_id):
        raise ValueError("Invalid APK applicationId")
    if code <= 0 or minimum <= 0 or target < minimum:
        raise ValueError("Invalid APK versionCode/SDK values")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                              verification, re.MULTILINE)
    if not certificates:
        raise ValueError("apksigner did not report signer SHA-256 certificates")
    certificates = sorted(item.lower() for item in certificates)
    if len(certificates) != len(set(certificates)):
        raise ValueError("Duplicate signer certificates")
    debuggable = bool(re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE))
    return {"file": f"CuteDog-{version}-{'debuggable' if debuggable else 'signed'}.apk",
            "applicationId": package_id, "versionName": version, "versionCode": code,
            "minimumSdk": minimum, "targetSdk": target, "debuggable": debuggable,
            "signerCertificateSha256": certificates}


def inspect_apk(source, tool_directory=None):
    source = Path(source)
    if not source.is_file() or source.stat().st_size == 0:
        raise ValueError(f"APK is missing or empty: {source}")
    directory = build_tools(tool_directory)
    for name in ("aapt", "apksigner"):
        if not (directory / name).is_file():
            raise ValueError(f"Missing Android tool: {directory / name}")
    verification = run([directory / "apksigner", "verify", "--verbose", "--print-certs", source])
    badging = run([directory / "aapt", "dump", "badging", source])
    metadata = parse_metadata(badging, verification)
    metadata.update(bytes=source.stat().st_size, sha256=sha256(source))
    return metadata, badging, verification


def source_state(repo=REPO, source_commit=None, require_clean=False):
    commit = run(["git", "rev-parse", "HEAD"], repo).strip()
    if not SHA_RE.fullmatch(commit):
        raise ValueError("Expected a full SHA-1 source commit")
    if source_commit is not None and source_commit != commit:
        raise ValueError("--source-commit must equal the current source checkout HEAD")
    dirty = bool(run(["git", "status", "--porcelain", "--untracked-files=normal"], repo).strip())
    if require_clean and dirty:
        raise ValueError("Release input requires a clean source checkout, including untracked files")
    return commit, dirty


def source_metadata(repo, commit):
    if not SHA_RE.fullmatch(commit):
        raise ValueError("sourceCommit must be a full lowercase 40-character commit SHA")
    resolved = run(["git", "rev-parse", f"{commit}^{{commit}}"], repo).strip()
    if resolved != commit:
        raise ValueError("sourceCommit must identify a commit object")
    gradle = run(["git", "show", f"{commit}:app/build.gradle.kts"], repo)
    result = {}
    # Deliberately accept only the project's literal assignments. If these become dynamic,
    # fail closed and update the verifier rather than evaluate arbitrary Gradle code.
    for key, setting, quoted in (("applicationId", "applicationId", True),
                                 ("versionName", "versionName", True),
                                 ("versionCode", "versionCode", False),
                                 ("minimumSdk", "minSdk", False),
                                 ("targetSdk", "targetSdk", False)):
        expression = r'"([^"\n]+)"' if quoted else r'(\d+)'
        matches = re.findall(r"^\s*" + setting + r"\s*=\s*" + expression + r"\s*(?://[^\n]*)?$",
                             gradle, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError(f"Source must contain exactly one literal {setting} assignment")
        result[key] = matches[0] if quoted else int(matches[0])
    return result


def check_source_metadata(metadata, repo, commit):
    for key, value in source_metadata(repo, commit).items():
        if metadata[key] != value:
            raise ValueError(f"APK {key} does not match source commit {commit}")


def write_evidence(output, metadata, badging, verification, commit, dirty):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "SHA256SUMS.txt").write_text(f"{metadata['sha256']}  {metadata['file']}\n")
    (output / "signature-verification.txt").write_text(verification)
    (output / "apk-metadata.txt").write_text(badging)
    info = {**metadata, "sourceCommit": commit, "sourceDirty": dirty,
            "sourceProvenance": PROVENANCE_NOTE}
    (output / "BUILD-INFO.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    return info


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=REPO / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--output", type=Path, default=REPO / "artifacts")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--source-commit", help="Full SHA; must equal checkout HEAD")
    args = parser.parse_args(argv)
    commit, dirty = source_state(source_commit=args.source_commit)
    args.output.mkdir(parents=True, exist_ok=True)
    # Inspect the copy that will actually be delivered, avoiding a source-file change
    # between signature verification and packaging. Publish the canonical name last.
    with tempfile.TemporaryDirectory(prefix=".package-", dir=args.output) as temporary:
        snapshot = Path(temporary) / "input.apk"
        shutil.copyfile(args.apk, snapshot)
        metadata, badging, verification = inspect_apk(snapshot, args.build_tools)
        target = args.output / metadata["file"]
        snapshot.replace(target)
    write_evidence(args.output, metadata, badging, verification, commit, dirty)
    print(target)
    print("SHA-256:", metadata["sha256"])
    print(f"Verified signature and metadata; sourceDirty={dirty}. Device tests are separate.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
