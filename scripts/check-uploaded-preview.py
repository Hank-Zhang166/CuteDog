"""Verify a locally signed public preview directly from its Release without storing APK parts in Git."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

spec = importlib.util.spec_from_file_location("apk_package", Path(__file__).with_name("package-apk.py"))
apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk)


def check(tag, output, build_tools):
    if not re.fullmatch(r"v[0-9][0-9.]*-preview\.[0-9]+", tag):
        return False
    ref = f"refs/tags/{tag}"
    with tempfile.TemporaryDirectory(prefix="preview-release-") as directory:
        local = f"refs/preview-validation/{Path(directory).name}"
        try:
            apk.run(["git", "fetch", "--depth=1", "--no-tags", "origin", f"{ref}:{local}"])
            if apk.run(["git", "cat-file", "-t", local]).strip() != "tag":
                return False
            annotation = apk.run(["git", "cat-file", "-p", local])
            markers = [line for line in annotation.splitlines() if line.startswith("Reviewed-APK-SHA256:")]
            if not markers:
                return False
            if len(markers) != 1 or "Release-Input-Commit:" in annotation:
                raise ValueError("Ambiguous release input")
            expected = markers[0].removeprefix("Reviewed-APK-SHA256:").strip()
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError("Invalid reviewed APK hash")
            source = apk.run(["git", "rev-parse", f"{local}^{{commit}}"]).strip()
            repository = os.environ["GITHUB_REPOSITORY"]
            # A draft already contains the verified uploads when its annotated tag is pushed.
            for attempt in range(12):
                result = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"],
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                if result.returncode == 0:
                    release = json.loads(result.stdout)
                    if not release["draft"]:
                        break
                if attempt == 11:
                    raise ValueError("Public prerelease unavailable")
                time.sleep(5)
            if not release["prerelease"] or release["tag_name"] != tag:
                raise ValueError("Expected a matching public prerelease")
            name = f"CuteDog-{tag[1:]}.apk"
            asset, = [a for a in release["assets"] if a["name"] == name]
            if asset["digest"] != "sha256:" + expected or not 0 < asset["size"] < 2**31:
                raise ValueError("Release asset differs from the reviewed APK")
            apk.run(["gh", "release", "download", tag, "--repo", repository, "--pattern", name, "--dir", directory])
            metadata, badging, signature = apk.inspect_apk(Path(directory) / name, build_tools)
            if metadata["sha256"] != expected or metadata["versionName"] != tag[1:] or metadata["bytes"] != asset["size"]:
                raise ValueError("Downloaded APK does not match the approved tag")
            apk.check_source_metadata(metadata, apk.REPO, source)
            remote = json.loads(apk.run(["gh", "api", f"repos/{repository}/git/ref/tags/{tag}"]))
            if remote["object"]["sha"] != apk.run(["git", "rev-parse", local]).strip():
                raise ValueError("Tag changed during verification")
            apk.write_evidence(output, metadata, badging, signature, source, False)
            return True
        finally:
            apk.run(["git", "update-ref", "-d", local])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    verified = check(args.tag, args.output, args.build_tools)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
        print(f"checked={str(verified).lower()}", file=stream)
