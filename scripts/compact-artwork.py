#!/usr/bin/env python3
"""Create the offline Android frame library from the approved lossless artwork."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import shutil
import tempfile

from PIL import Image

QUALITY = 96
LOSSLESS_CLIPS = {"chase", "chase_right"}


def digest(data):
    return sha256(data).hexdigest()


@dataclass(frozen=True)
class SourceFrame:
    path: Path
    pixels: str
    reflected_pixels: str
    lossless: bool


def pack(source, output, workers):
    source, output = source.resolve(), output.resolve()
    if source == output or output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError("Source and generated assets must be independent directories")
    art = source / "pet3d"
    manifest_data = (art / "manifest.json").read_bytes()
    manifest = json.loads(manifest_data)
    expected = {}
    lossless = set()
    for clip in manifest["clips"]:
        pairs = list(zip(clip["frames"], clip["frameSha256"], strict=True))
        if terminal := clip.get("terminalProof"):
            pairs.append((terminal["path"], terminal["sha256"]))
        for path, checksum in pairs:
            if expected.setdefault(path, checksum) != checksum:
                raise ValueError(f"Conflicting source digests: {path}")
            if clip["id"] in LOSSLESS_CLIPS:
                lossless.add(checksum)
    actual = {path.relative_to(source).as_posix() for path in art.rglob("*.webp")}
    if actual != set(expected):
        raise ValueError("Source frames and terminal proofs must match the manifest exactly")

    def inspect(item):
        name, wanted = item
        path = source / name
        data = path.read_bytes()
        if digest(data) != wanted:
            raise ValueError(f"Artwork checksum mismatch: {name}")
        return wanted, path

    def identify(item):
        checksum, path = item
        with Image.open(path) as image:
            if image.size != (manifest["width"], manifest["height"]):
                raise ValueError(f"Unexpected frame dimensions: {path}")
            rgba = image.convert("RGBA")
        return checksum, SourceFrame(path, digest(rgba.tobytes()),
                                    digest(rgba.transpose(Image.Transpose.FLIP_LEFT_RIGHT).tobytes()),
                                    checksum in lossless)

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="compact-art-", dir=output.parent) as temporary:
        stage = Path(temporary) / "assets"
        packed = stage / "pet3d" / "frames"
        packed.mkdir(parents=True)
        for item in source.iterdir():
            if item == art:
                continue
            if item.is_dir():
                shutil.copytree(item, stage / item.name)
            else:
                shutil.copy2(item, stage / item.name)
        for source_json in art.glob("*.json"):
            shutil.copy2(source_json, packed.parent / source_json.name)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            unique = dict(pool.map(inspect, sorted(expected.items())))
            groups = {}
            for checksum, frame in pool.map(identify, sorted(unique.items())):
                group = min(frame.pixels, frame.reflected_pixels)
                groups.setdefault(group, []).append((checksum, frame))

            def encode(members):
                # Keep all chase pixels exact, including poses shared with other actions.
                rep = next((frame for _, frame in members if frame.lossless), members[0][1])
                if any(frame.lossless for _, frame in members):
                    data = rep.path.read_bytes()
                else:
                    with Image.open(rep.path) as image:
                        rgba = image.convert("RGBA")
                    encoded = BytesIO()
                    rgba.save(encoded, format="WEBP", quality=QUALITY, alpha_quality=100, method=4)
                    data = encoded.getvalue()
                    with Image.open(BytesIO(data)) as result:
                        if result.convert("RGBA").getchannel("A").tobytes() != rgba.getchannel("A").tobytes():
                            raise ValueError(f"Alpha channel changed: {rep.path}")
                packed_hash = digest(data)
                # A different visual source can quantize to the same encoded payload.
                return data, packed_hash, {key: [packed_hash, f.pixels != rep.pixels] for key, f in members}

            index = {}
            for data, packed_hash, mappings in pool.map(encode, groups.values()):
                path = packed / (packed_hash + ".webp")
                if not path.exists():
                    path.write_bytes(data)
                index.update(mappings)
        record = {"version": 1, "manifestSha256": digest(manifest_data), "frames": index}
        (packed.parent / "frame-index.json").write_text(
            json.dumps(record, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8", newline="\n")
        old = Path(temporary) / "previous"
        if output.exists():
            output.rename(old)
        try:
            stage.rename(output)
        except BaseException:
            if old.exists():
                old.rename(output)
            raise
    files = list((output / "pet3d" / "frames").glob("*.webp"))
    print(f"{len(expected)} logical frames, {len(files)} independent payloads, "
          f"{sum(path.stat().st_size for path in files) / 2**20:.2f} MiB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        parser.error("--workers must be between 1 and 16")
    pack(args.source, args.output, args.workers)
