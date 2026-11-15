#!/usr/bin/env python3
"""Pin an explicitly requested published APK and verify its signature/source metadata.

No default APK, release, hash, or source commit is inferred. This does not publish a release.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("apk_package", REPO / "scripts/package-apk.py")
APK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(APK)


def validate_request(value):
    required = {"schemaVersion", "apkUrl", "apkSha256", "sourceCommit"}
    optional = {"requestId", "baselineUrl", "baselineSha256"}
    if not isinstance(value, dict) or not required <= value.keys() or value.keys() - required - optional:
        raise ValueError("Request must contain explicit APK URL, SHA-256 and source commit")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise ValueError("Unsupported UI validation request version")
    if not isinstance(value["sourceCommit"], str) or not re.fullmatch(r"[0-9a-f]{40}", value["sourceCommit"]):
        raise ValueError("sourceCommit must be a complete lowercase commit SHA")
    if "requestId" in value and not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", str(value["requestId"])):
        raise ValueError("Invalid request ID")
    if ("baselineUrl" in value) != ("baselineSha256" in value):
        raise ValueError("Upgrade baseline requires both its URL and SHA-256")
    for prefix in ("apk", "baseline"):
        if prefix + "Url" not in value:
            continue
        raw = value[prefix + "Url"]
        url = urllib.parse.urlsplit(raw) if isinstance(raw, str) else None
        if not url or url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment or any(c.isspace() for c in raw):
            raise ValueError("APK URLs must be explicit HTTPS URLs without embedded credentials")
        digest = value[prefix + "Sha256"]
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("APK SHA-256 must be exactly 64 lowercase hexadecimal characters")
    return value


def download(url, expected, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise ValueError("Refusing to overwrite existing downloaded APK evidence")
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "CuteDog-APK-validation"}), timeout=60) as response, path.open("xb") as output:
        if urllib.parse.urlsplit(response.url).scheme != "https":
            raise ValueError("APK download redirected away from HTTPS")
        while block := response.read(1024 * 1024):
            count += len(block)
            if count > 1024 * 1024 * 1024:
                raise ValueError("APK exceeds the 1 GiB validation download limit")
            output.write(block)
            digest.update(block)
    if count == 0 or digest.hexdigest() != expected:
        raise ValueError("Downloaded APK bytes do not match the requested SHA-256")


def prepare(args):
    if args.request:
        request = validate_request(json.loads(args.request.read_text()))
    else:
        request = {"schemaVersion": 1, "apkUrl": os.getenv("INPUT_APK_URL", ""),
                   "apkSha256": os.getenv("INPUT_APK_SHA256", ""), "sourceCommit": os.getenv("INPUT_SOURCE_COMMIT", "")}
        if os.getenv("INPUT_BASELINE_URL") or os.getenv("INPUT_BASELINE_SHA256"):
            request.update(baselineUrl=os.getenv("INPUT_BASELINE_URL", ""), baselineSha256=os.getenv("INPUT_BASELINE_SHA256", ""))
        validate_request(request)
    args.output.mkdir(parents=True, exist_ok=True)
    identity = args.output / "release-input.json"
    if identity.exists():
        raise ValueError("Use a new evidence directory")
    identity.write_text(json.dumps(request, indent=2) + "\n")
    final = args.apk_dir / "final.apk"
    download(request["apkUrl"], request["apkSha256"], final)
    metadata, badging, verification = APK.inspect_apk(final)
    with zipfile.ZipFile(final) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "assets/pet3d/manifest.json"]
        if len(entries) != 1:
            raise ValueError("Final artwork validation requires exactly one bundled character manifest")
        manifest = archive.read(entries[0])
    if subprocess.run(["git", "cat-file", "-e", request["sourceCommit"] + "^{commit}"], cwd=REPO, capture_output=True).returncode:
        subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", request["sourceCommit"]], cwd=REPO, check=True)
    APK.check_source_metadata(metadata, REPO, request["sourceCommit"])
    proof = {"apk": metadata, "declaredSourceCommit": request["sourceCommit"],
             "testToolCommit": os.getenv("GITHUB_SHA"), "provenanceNote": APK.PROVENANCE_NOTE,
             "authoredManifestSha256": hashlib.sha256(manifest).hexdigest()}
    (args.output / "apk-badging.txt").write_text(badging)
    (args.output / "apk-signature.txt").write_text(verification)
    if "baselineUrl" in request:
        baseline = args.apk_dir / "baseline.apk"
        download(request["baselineUrl"], request["baselineSha256"], baseline)
        before, _, signature = APK.inspect_apk(baseline)
        if before["applicationId"] != metadata["applicationId"] or before["versionCode"] >= metadata["versionCode"] or before["signerCertificateSha256"] != metadata["signerCertificateSha256"]:
            raise ValueError("Upgrade must preserve application ID and signer and increase the actual versionCode")
        proof["baseline"] = before
        (args.output / "baseline-signature.txt").write_text(signature)
    (args.output / "apk-identity.json").write_text(json.dumps(proof, indent=2) + "\n")


def run_device(args):
    request = validate_request(json.loads((args.output / "release-input.json").read_text()))
    if not (args.output / "apk-identity.json").is_file():
        raise ValueError("APK signature/source metadata must be verified before device execution")
    command = ["python3", "scripts/device-smoke.py", "--disposable", "--apk", str(args.apk_dir / "final.apk"),
               "--expected-sha256", request["apkSha256"], "--source-commit", request["sourceCommit"], "--output", str(args.output)]
    if "baselineUrl" in request:
        command += ["--baseline-apk", str(args.apk_dir / "baseline.apk"), "--baseline-sha256", request["baselineSha256"]]
    smoke = subprocess.run(command, cwd=REPO).returncode
    profile = subprocess.run(["python3", "scripts/profile-device.py", "--disposable", "--duration", "12", "--cycles", "3",
                              "--require-rig-metrics", "--apk", str(args.apk_dir / "final.apk"), "--expected-apk-sha256", request["apkSha256"],
                              "--source-commit", request["sourceCommit"], "--output", str(args.output / "performance")], cwd=REPO).returncode
    (args.output / "validation-outcomes.json").write_text(json.dumps({"smokeExitCode": smoke, "profileExitCode": profile,
        "status": "passed" if smoke == 0 and profile == 0 else "failed"}, indent=2) + "\n")
    return 0 if smoke == 0 and profile == 0 else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "run-device"])
    parser.add_argument("--request", type=Path)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(prepare(args) if args.mode == "prepare" else run_device(args))
