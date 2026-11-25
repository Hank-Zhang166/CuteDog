#!/usr/bin/env python3
"""Prepare, reassemble, and verify immutable signed-APK release inputs. Never signs."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("apk_package", Path(__file__).with_name("package-apk.py"))
APK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(APK)
MAX_CHUNK = 40 * 1024 * 1024
MAX_APK = 2 * 1024 * 1024 * 1024 - 1  # GitHub release assets must be smaller than 2 GiB.
MAX_MANIFEST = 128 * 1024
HASH_RE = re.compile(r"[0-9a-f]{64}")
TAG_RE = re.compile(r"v[A-Za-z0-9][A-Za-z0-9._+-]{0,99}")
ROOT_KEYS = {"schemaVersion", "tag", "sourceCommit", "release", "apk", "chunks"}


def exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} must contain exactly: {', '.join(sorted(keys))}")


def integer(value, minimum, maximum, label):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"Invalid {label}")


def pattern(value, expression, label):
    if not isinstance(value, str) or not expression.fullmatch(value):
        raise ValueError(f"Invalid {label}")


def validate_manifest(manifest):
    exact_keys(manifest, ROOT_KEYS, "manifest")
    integer(manifest["schemaVersion"], 1, 1, "schemaVersion")
    pattern(manifest["sourceCommit"], APK.SHA_RE, "sourceCommit")
    pattern(manifest["tag"], TAG_RE, "tag")
    if ".." in manifest["tag"] or manifest["tag"].endswith("."):
        raise ValueError("Tag is not a valid Git ref name")
    policy = manifest["release"]
    exact_keys(policy, {"mode", "allowDebuggable"}, "release")
    if policy["mode"] not in ("draft", "prerelease", "stable") or type(policy["allowDebuggable"]) is not bool:
        raise ValueError("Invalid release policy")
    metadata = manifest["apk"]
    exact_keys(metadata, APK.APK_FIELDS, "apk")
    pattern(metadata["versionName"], APK.VERSION_RE, "versionName")
    pattern(metadata["sha256"], HASH_RE, "APK SHA-256")
    pattern(metadata["applicationId"], re.compile(r"[A-Za-z]\w*(?:\.[A-Za-z]\w*)+"), "applicationId")
    integer(metadata["versionCode"], 1, 2100000000, "versionCode")
    integer(metadata["minimumSdk"], 1, 1000, "minimumSdk")
    integer(metadata["targetSdk"], metadata["minimumSdk"], 1000, "targetSdk")
    integer(metadata["bytes"], 1, MAX_APK, "APK bytes")
    if type(metadata["debuggable"]) is not bool:
        raise ValueError("debuggable must be boolean")
    expected_name = f"CuteDog-{metadata['versionName']}-{'debuggable' if metadata['debuggable'] else 'signed'}.apk"
    if metadata["file"] != expected_name or manifest["tag"] != "v" + metadata["versionName"]:
        raise ValueError("Tag and APK filename must match the actual versionName exactly")
    certificates = metadata["signerCertificateSha256"]
    if not isinstance(certificates, list) or not 1 <= len(certificates) <= 10:
        raise ValueError("Expected one or more signer certificates")
    for certificate in certificates:
        pattern(certificate, HASH_RE, "certificate SHA-256")
    if certificates != sorted(set(certificates)):
        raise ValueError("Certificates must be unique and sorted")
    if metadata["debuggable"] and (not policy["allowDebuggable"] or policy["mode"] == "stable"):
        raise ValueError("Debuggable APK requires explicit allowance and draft/prerelease mode")
    chunks = manifest["chunks"]
    if not isinstance(chunks, list) or not 1 <= len(chunks) <= 64:
        raise ValueError("Expected 1 to 64 ordered chunks")
    for index, chunk in enumerate(chunks):
        exact_keys(chunk, {"file", "bytes", "sha256"}, "chunk")
        if chunk["file"] != f"parts/{index:05d}.part":
            raise ValueError("Chunk paths must be ordered, contiguous canonical part names")
        integer(chunk["bytes"], 1, MAX_CHUNK, "chunk bytes")
        pattern(chunk["sha256"], HASH_RE, "chunk SHA-256")
    if sum(chunk["bytes"] for chunk in chunks) != metadata["bytes"]:
        raise ValueError("Sum of chunk bytes differs from APK size")
    return manifest


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_manifest(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_MANIFEST:
        raise ValueError("Missing, symbolic, or oversized release-manifest.json")
    return validate_manifest(json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys))


def write_json(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def split_apk(source, output, chunk_size=MAX_CHUNK):
    integer(chunk_size, 1, MAX_CHUNK, "chunk size")
    if not 1 <= source.stat().st_size <= MAX_APK:
        raise ValueError("APK must be nonempty and smaller than 2 GiB")
    (output / "parts").mkdir()
    chunks = []
    with source.open("rb") as stream:
        while block := stream.read(chunk_size):
            name = f"parts/{len(chunks):05d}.part"
            (output / name).write_bytes(block)
            chunks.append({"file": name, "bytes": len(block), "sha256": hashlib.sha256(block).hexdigest()})
    return chunks


def join_chunks(directory, manifest, destination):
    validate_manifest(manifest)
    directory = Path(directory)
    if directory.is_symlink() or (directory / "parts").is_symlink():
        raise ValueError("Input directories cannot be symbolic links")
    digest = hashlib.sha256()
    total = 0
    created = False
    try:
        with destination.open("xb") as output:
            created = True
            for chunk in manifest["chunks"]:
                part = directory / chunk["file"]
                if part.is_symlink() or not part.is_file() or part.stat().st_size != chunk["bytes"]:
                    raise ValueError(f"Missing, symbolic, or wrong-sized chunk: {chunk['file']}")
                chunk_digest = hashlib.sha256()
                count = 0
                with part.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        output.write(block)
                        digest.update(block)
                        chunk_digest.update(block)
                        count += len(block)
                if count != chunk["bytes"] or chunk_digest.hexdigest() != chunk["sha256"]:
                    raise ValueError(f"Chunk checksum failed: {chunk['file']}")
                total += count
        if total != manifest["apk"]["bytes"] or digest.hexdigest() != manifest["apk"]["sha256"]:
            raise ValueError("Reassembled APK size or checksum mismatch")
    except Exception:
        if created:
            destination.unlink(missing_ok=True)
        raise


def new_output(path):
    path = Path(path).absolute()
    if path.exists() or path.is_symlink():
        raise ValueError(f"Output must not already exist: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def prepare(args):
    commit, _ = APK.source_state(args.repo, args.source_commit, require_clean=True)
    # Within the source checkout, keep generated binary inputs under ignored artifacts/.
    output = args.output.absolute()
    repo = args.repo.resolve()
    if output.resolve().is_relative_to(repo) and not output.resolve().is_relative_to(repo / "artifacts"):
        raise ValueError("Within the source checkout, output must be under ignored artifacts/")
    output = new_output(output)
    with tempfile.TemporaryDirectory(prefix=".release-input-", dir=output.parent) as temporary:
        directory = Path(temporary)
        snapshot = directory / "input.apk"
        shutil.copyfile(args.apk, snapshot)
        metadata, _, _ = APK.inspect_apk(snapshot, args.build_tools)
        APK.check_source_metadata(metadata, args.repo, commit)
        manifest = {"schemaVersion": 1, "tag": args.tag, "sourceCommit": commit,
                    "release": {"mode": args.mode, "allowDebuggable": args.allow_debuggable},
                    "apk": metadata, "chunks": split_apk(snapshot, directory)}
        validate_manifest(manifest)
        snapshot.unlink()
        write_json(directory / "release-manifest.json", manifest)
        # Check the source checkout again in case it changed while the APK was read.
        APK.source_state(args.repo, commit, require_clean=True)
        directory.rename(output)
    print(output / "release-manifest.json")


def verify_input(directory, output, repo, expected_tag, expected_commit, tool_directory=None, input_commit=None, tag_object=None):
    manifest = read_manifest(directory / "release-manifest.json")
    if manifest["tag"] != expected_tag or manifest["sourceCommit"] != expected_commit:
        raise ValueError("Manifest tag/sourceCommit does not match the explicitly selected source")
    APK.check_source_metadata(manifest["apk"], repo, expected_commit)
    output = new_output(output)
    with tempfile.TemporaryDirectory(prefix=".verified-release-", dir=output.parent) as temporary:
        verified = Path(temporary)
        apk_file = verified / manifest["apk"]["file"]
        join_chunks(directory, manifest, apk_file)
        metadata, badging, signature = APK.inspect_apk(apk_file, tool_directory)
        if metadata != manifest["apk"]:
            raise ValueError("Actual APK metadata/signers do not match the release manifest")
        APK.write_evidence(verified, metadata, badging, signature, expected_commit, False)
        write_json(verified / "release-manifest.json", manifest)
        info = {"tag": expected_tag, "sourceCommit": expected_commit, "inputCommit": input_commit,
                "tagObject": tag_object, "mode": manifest["release"]["mode"], "apk": metadata["file"]}
        write_json(verified / "RELEASE-INFO.json", info)
        notes = (f"CuteDog {metadata['versionName']}\n\n"
                 f"Source commit: {expected_commit}\nInput commit: {input_commit or 'local verification'}\n"
                 f"APK SHA-256: {metadata['sha256']}\n"
                 f"Android SDK: {metadata['minimumSdk']}+; target {metadata['targetSdk']}\n"
                 f"Debuggable: {metadata['debuggable']}\n"
                 f"Signer certificate SHA-256: {', '.join(metadata['signerCertificateSha256'])}\n\n"
                 f"{APK.PROVENANCE_NOTE}\n")
        (verified / "BUILD-INFO.txt").write_text(notes)
        verified.rename(output)
    return info


def select_input(annotation, explicit):
    lines = [line for line in annotation.splitlines() if line.startswith("Release-Input-Commit:")]
    if len(lines) > 1:
        raise ValueError("Tag contains duplicate Release-Input-Commit bindings")
    bound = None
    if lines:
        match = re.fullmatch(r"Release-Input-Commit: ([0-9a-f]{40})", lines[0])
        if not match:
            raise ValueError("Malformed Release-Input-Commit annotation")
        bound = match[1]
    if explicit:
        pattern(explicit, APK.SHA_RE, "input commit")
        if bound and explicit != bound:
            raise ValueError("Explicit input commit disagrees with the annotated tag")
    selected = explicit or bound
    if not selected:
        raise ValueError("Tag needs a Release-Input-Commit annotation or an explicit dispatch input_commit")
    return selected


def extract_blob(repo, commit, name, destination, maximum, exact_size=None):
    spec = f"{commit}:{name}"
    # Reject trees, symlinks and submodules as well as large blob surprises.
    entry = APK.run(["git", "ls-tree", commit, "--", name], repo).strip()
    if not entry.startswith("100644 blob ") or entry.split("\t", 1)[-1] != name:
        raise ValueError(f"Input is not a regular committed data file: {name}")
    size = int(APK.run(["git", "cat-file", "-s", spec], repo).strip())
    if not 1 <= size <= maximum or (exact_size is not None and size != exact_size):
        raise ValueError(f"Unexpected committed file size: {name}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as stream:
        subprocess.run(["git", "cat-file", "blob", spec], cwd=repo, stdout=stream,
                       stderr=subprocess.PIPE, check=True)


def fetch_verify(args):
    pattern(args.tag, TAG_RE, "tag")
    if ".." in args.tag or args.tag.endswith("."):
        raise ValueError("Tag is not a valid Git ref name")
    # Fetch exact refs into an isolated temporary namespace; no input branch is checked out.
    with tempfile.TemporaryDirectory(prefix="release-fetch-") as temporary:
        marker = Path(temporary).name
        ref = f"refs/release-validation/{marker}"
        try:
            APK.run(["git", "fetch", "--no-tags", "--depth=1", "origin", f"refs/tags/{args.tag}:{ref}"], args.repo)
            commit = APK.run(["git", "rev-parse", f"{ref}^{{commit}}"], args.repo).strip()
            tag_object = APK.run(["git", "rev-parse", ref], args.repo).strip()
            kind = APK.run(["git", "cat-file", "-t", ref], args.repo).strip()
            annotation = APK.run(["git", "cat-file", "-p", ref], args.repo) if kind == "tag" else ""
            input_commit = select_input(annotation, args.input_commit)
            APK.run(["git", "fetch", "--no-tags", "--depth=1", "origin", input_commit], args.repo)
            resolved = APK.run(["git", "rev-parse", f"{input_commit}^{{commit}}"], args.repo).strip()
            if resolved != input_commit:
                raise ValueError("input_commit must identify a commit object")
            directory = Path(temporary) / "input"
            extract_blob(args.repo, input_commit, "release-manifest.json", directory / "release-manifest.json", MAX_MANIFEST)
            manifest = read_manifest(directory / "release-manifest.json")
            if manifest["sourceCommit"] != commit or manifest["tag"] != args.tag:
                raise ValueError("Tag source commit/version differs from the input manifest")
            for chunk in manifest["chunks"]:
                extract_blob(args.repo, input_commit, chunk["file"], directory / chunk["file"], MAX_CHUNK, chunk["bytes"])
            info = verify_input(directory, args.output, args.repo, args.tag, commit, args.build_tools, input_commit, tag_object)
            if args.github_output:
                with args.github_output.open("a") as stream:
                    for key in ("tag", "mode", "apk", "sourceCommit", "inputCommit", "tagObject"):
                        stream.write(f"{key}={info[key]}\n")
            print(json.dumps(info, indent=2))
        finally:
            APK.run(["git", "update-ref", "-d", ref], args.repo)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("prepare", help="Inspect a locally signed APK and create an immutable input directory")
    make.add_argument("--apk", required=True, type=Path)
    make.add_argument("--tag", required=True)
    make.add_argument("--source-commit", help="Full SHA, equal to clean checkout HEAD; defaults to HEAD")
    make.add_argument("--output", type=Path, default=APK.REPO / "artifacts/release-input")
    make.add_argument("--mode", choices=("draft", "prerelease", "stable"), default="draft")
    make.add_argument("--allow-debuggable", action="store_true")
    check = sub.add_parser("verify", help="Verify local input against an explicit source commit and tag")
    check.add_argument("--input", required=True, type=Path)
    check.add_argument("--tag", required=True)
    check.add_argument("--source-commit", required=True)
    check.add_argument("--output", type=Path, default=APK.REPO / "artifacts/release-verified")
    fetch = sub.add_parser("fetch-verify", help="Fetch an existing tag and explicitly bound input commit, then verify")
    fetch.add_argument("--tag", required=True)
    fetch.add_argument("--input-commit")
    fetch.add_argument("--output", type=Path, default=APK.REPO / "artifacts/release-verified")
    fetch.add_argument("--github-output", type=Path)
    for command in (make, check, fetch):
        command.add_argument("--build-tools", type=Path)
        command.add_argument("--repo", type=Path, default=APK.REPO)
    sub.add_parser("self-test", help="Offline boundary tests; no Gradle, credentials, or network")
    args = parser.parse_args(argv)
    if args.command == "prepare":
        prepare(args)
    elif args.command == "verify":
        print(json.dumps(verify_input(args.input, args.output, args.repo, args.tag,
                                      args.source_commit, args.build_tools), indent=2))
    elif args.command == "fetch-verify":
        fetch_verify(args)
    else:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReleaseBoundaryTests)
        if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
            raise SystemExit(1)


class ReleaseBoundaryTests(unittest.TestCase):
    """Byte-integrity and policy tests; these do not simulate a real APK signature."""
    def fixture(self, data=b"test APK bytes"):
        digest = hashlib.sha256(data).hexdigest()
        return {"schemaVersion": 1, "tag": "v1.2.3", "sourceCommit": "a" * 40,
                "release": {"mode": "draft", "allowDebuggable": False},
                "apk": {"file": "CuteDog-1.2.3-signed.apk", "bytes": len(data), "sha256": digest,
                        "applicationId": "com.cutedog.app", "versionName": "1.2.3", "versionCode": 123,
                        "minimumSdk": 26, "targetSdk": 35, "debuggable": False,
                        "signerCertificateSha256": ["c" * 64]},
                "chunks": [{"file": "parts/00000.part", "bytes": len(data), "sha256": digest}]}

    def test_round_trip_and_chunk_boundary(self):
        for size, expected in ((MAX_CHUNK, [MAX_CHUNK]), (MAX_CHUNK + 1, [MAX_CHUNK, 1])):
            with self.subTest(size=size), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                source = directory / "source.apk"
                with source.open("wb") as stream:
                    stream.truncate(size)
                parts = split_apk(source, directory)
                self.assertEqual([part["bytes"] for part in parts], expected)
                manifest = self.fixture()
                manifest["apk"].update(bytes=size, sha256=APK.sha256(source))
                manifest["chunks"] = parts
                join_chunks(directory, manifest, directory / "joined.apk")
                self.assertEqual(APK.sha256(source), APK.sha256(directory / "joined.apk"))

    def test_corrupt_missing_and_symlink_parts_remove_partial_output(self):
        for mode in ("corrupt", "missing", "symlink"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                (directory / "parts").mkdir()
                part = directory / "parts/00000.part"
                if mode == "corrupt":
                    part.write_bytes(b"fake APK bytes")
                elif mode == "symlink":
                    target = directory / "target"
                    target.write_bytes(b"test APK bytes")
                    part.symlink_to(target)
                output = directory / "joined.apk"
                with self.assertRaises(ValueError):
                    join_chunks(directory, self.fixture(), output)
                self.assertFalse(output.exists())

    def test_whole_apk_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "parts").mkdir()
            (directory / "parts/00000.part").write_bytes(b"test APK bytes")
            manifest = self.fixture()
            manifest["apk"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "Reassembled"):
                join_chunks(directory, manifest, directory / "joined.apk")

    def test_strict_manifest_rejects_bad_boundaries(self):
        cases = []
        for key, value in (("schemaVersion", True), ("sourceCommit", "main"), ("tag", "v9.9.9"),
                           ("chunks", []), ("unknown", "field")):
            item = self.fixture(); item[key] = value; cases.append(item)
        for key, value in (("file", "../secret"), ("bytes", MAX_CHUNK + 1), ("bytes", True), ("sha256", "x" * 64)):
            item = self.fixture(); item["chunks"][0][key] = value; cases.append(item)
        item = self.fixture(); item["chunks"] *= 2; cases.append(item)
        item = self.fixture(); item["apk"]["bytes"] += 1; cases.append(item)
        item = self.fixture(); item["apk"]["signerCertificateSha256"] *= 2; cases.append(item)
        item = self.fixture(); item["apk"]["bytes"] = MAX_APK + 1; cases.append(item)
        item = self.fixture(); del item["apk"]["targetSdk"]; cases.append(item)
        for item in cases:
            with self.subTest(manifest=item), self.assertRaises(ValueError):
                validate_manifest(item)

    def test_debug_policy(self):
        item = self.fixture()
        item["apk"].update(debuggable=True, file="CuteDog-1.2.3-debuggable.apk")
        with self.assertRaises(ValueError):
            validate_manifest(item)
        item["release"]["allowDebuggable"] = True
        validate_manifest(item)
        item["release"]["mode"] = "prerelease"
        validate_manifest(item)
        item["release"]["mode"] = "stable"
        with self.assertRaises(ValueError):
            validate_manifest(item)

    def test_duplicate_json_keys_and_oversize_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "release-manifest.json"
            path.write_text('{"schemaVersion":1,"schemaVersion":1}')
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                read_manifest(path)
            path.write_text(" " * (MAX_MANIFEST + 1))
            with self.assertRaisesRegex(ValueError, "oversized"):
                read_manifest(path)

    def test_annotation_binding(self):
        first, second = "a" * 40, "b" * 40
        annotation = f"Release-Input-Commit: {first}"
        self.assertEqual(select_input(annotation, None), first)
        self.assertEqual(select_input("", second), second)
        self.assertEqual(select_input(annotation, first), first)
        for text, explicit in (("", None), (annotation, second), (annotation + "\n" + annotation, None),
                               ("Release-Input-Commit: main", None), ("", "a" * 7)):
            with self.subTest(annotation=text, explicit=explicit), self.assertRaises(ValueError):
                select_input(text, explicit)

    def test_actual_metadata_parser_does_not_guess_certificate_or_debug(self):
        badging = ("package: name='com.cutedog.app' versionCode='4' versionName='1.2.3'\n"
                   "sdkVersion:'26'\ntargetSdkVersion:'35'\n")
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        result = APK.parse_metadata(badging, signature)
        self.assertFalse(result["debuggable"])
        self.assertEqual(result["signerCertificateSha256"], ["a" * 64])
        self.assertTrue(APK.parse_metadata(badging + "application-debuggable\n", signature)["debuggable"])
        for text, cert in ((badging, "unsigned"), ("", signature), (badging.replace("1.2.3", "../secret"), signature)):
            with self.assertRaises(ValueError):
                APK.parse_metadata(text, cert)

    def test_existing_destination_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "existing.apk"
            destination.write_bytes(b"preserve this file")
            with self.assertRaises(FileExistsError):
                join_chunks(Path(temporary), self.fixture(), destination)
            self.assertEqual(destination.read_bytes(), b"preserve this file")

    def test_source_metadata_and_dirty_checkout_rejected(self):
        metadata = self.fixture()["apk"]
        fields = ("applicationId", "versionName", "versionCode", "minimumSdk", "targetSdk")
        expected = {key: metadata[key] for key in fields}
        for key in fields:
            actual = dict(metadata)
            actual[key] = "wrong" if type(actual[key]) is str else actual[key] + 1
            with self.subTest(field=key), mock.patch.object(APK, "source_metadata", return_value=expected):
                with self.assertRaisesRegex(ValueError, key):
                    APK.check_source_metadata(actual, Path("."), "a" * 40)
        with mock.patch.object(APK, "run", side_effect=["a" * 40, " M app/source.kt"]):
            with self.assertRaisesRegex(ValueError, "clean source checkout"):
                APK.source_state(require_clean=True)
        with mock.patch.object(APK, "run", return_value="a" * 40):
            with self.assertRaisesRegex(ValueError, "checkout HEAD"):
                APK.source_state(source_commit="b" * 40)

    def test_motion_preview_from_declared_source(self):
        commit = APK.run(["git", "rev-parse", "HEAD"], APK.REPO).strip()
        base = APK.source_metadata(APK.REPO, commit)
        preview = {**base, "applicationId": base["applicationId"] + ".motionpreview", "debuggable": True}
        APK.check_source_metadata(base, APK.REPO, commit)
        APK.check_source_metadata(preview, APK.REPO, commit)
        for key, value in (("applicationId", base["applicationId"] + ".other"),
                           ("debuggable", False), ("versionName", base["versionName"] + "-other"),
                           ("versionCode", base["versionCode"] + 1), ("minimumSdk", base["minimumSdk"] + 1),
                           ("targetSdk", base["targetSdk"] + 1)):
            with self.subTest(field=key), self.assertRaises(ValueError):
                APK.check_source_metadata({**preview, key: value}, APK.REPO, commit)
        for blob in ("", "a" * 40):
            with self.subTest(previewScriptBlob=blob), mock.patch.object(APK, "source_metadata", return_value=base), \
                    mock.patch.object(APK, "run", return_value=blob), self.assertRaisesRegex(ValueError, "applicationId"):
                APK.check_source_metadata(preview, APK.REPO, commit)

    def test_reassembled_apk_metadata_and_signer_mismatch_rejected(self):
        for field, value in (("signerCertificateSha256", ["d" * 64]), ("debuggable", True),
                             ("versionName", "9.9.9"), ("targetSdk", 36)):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                (directory / "parts").mkdir()
                (directory / "parts/00000.part").write_bytes(b"test APK bytes")
                manifest = self.fixture()
                write_json(directory / "release-manifest.json", manifest)
                actual = copy.deepcopy(manifest["apk"])
                actual[field] = value
                with mock.patch.object(APK, "check_source_metadata"), mock.patch.object(
                        APK, "inspect_apk", return_value=(actual, "", "")):
                    with self.assertRaisesRegex(ValueError, "Actual APK metadata/signers"):
                        verify_input(directory, directory / "output", directory,
                                     manifest["tag"], manifest["sourceCommit"])
                self.assertFalse((directory / "output").exists())

    def test_signature_tool_failure_is_fatal(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ("aapt", "apksigner", "input.apk"):
                (directory / name).write_bytes(b"fixture")
            with mock.patch.object(APK, "run", side_effect=subprocess.CalledProcessError(1, ["apksigner"])):
                with self.assertRaises(subprocess.CalledProcessError):
                    APK.inspect_apk(directory / "input.apk", directory)

    def test_git_input_accepts_only_regular_data_blobs(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            APK.run(["git", "init", "--quiet", repo])
            (repo / "normal").write_bytes(b"data")
            (repo / "executable").write_bytes(b"data")
            (repo / "executable").chmod(0o755)
            (repo / "link").symlink_to("normal")
            APK.run(["git", "add", "--", "normal", "executable", "link"], repo)
            APK.run(["git", "-c", "user.name=Release verifier test", "-c", "user.email=test@example.invalid",
                     "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Local offline fixture"], repo)
            commit = APK.run(["git", "rev-parse", "HEAD"], repo).strip()
            extract_blob(repo, commit, "normal", repo / "extracted", 4, 4)
            self.assertEqual((repo / "extracted").read_bytes(), b"data")
            for name in ("executable", "link", "missing"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    extract_blob(repo, commit, name, repo / "rejected", 4, 4)
            with self.assertRaises(ValueError):
                extract_blob(repo, commit, "normal", repo / "wrong-size", 4, 3)

    def test_invalid_git_tag_name(self):
        for version in ("1..2", "1.2."):
            item = self.fixture()
            item["tag"] = "v" + version
            item["apk"]["versionName"] = version
            item["apk"]["file"] = f"CuteDog-{version}-signed.apk"
            with self.assertRaisesRegex(ValueError, "Git ref"):
                validate_manifest(item)

    def test_empty_apk_and_missing_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "empty.apk"
            source.touch()
            with self.assertRaises(ValueError):
                split_apk(source, Path(temporary))
            with self.assertRaises(ValueError):
                APK.inspect_apk(source, Path(temporary))
            source.write_bytes(b"APK")
            with self.assertRaisesRegex(ValueError, "Missing Android tool"):
                APK.inspect_apk(source, Path(temporary))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
