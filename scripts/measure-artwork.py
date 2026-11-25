#!/usr/bin/env python3
"""Compare a built APK's decoded artwork and transparency against the lossless source."""
import argparse
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageStat


def verify(apk, assets):
    worst = (0.0, "")
    exact = chase = count = 0
    with ZipFile(apk) as archive:
        root = "assets/pet3d/"
        manifest_bytes = archive.read(root + "manifest.json")
        if manifest_bytes != (assets / "pet3d/manifest.json").read_bytes():
            raise ValueError("The APK and source manifests are different")
        manifest = json.loads(manifest_bytes)
        index = json.loads(archive.read(root + "frame-index.json"))
        if index["manifestSha256"] != sha256(manifest_bytes).hexdigest():
            raise ValueError("Stored frame index belongs to a different manifest")
        seen = set()
        for clip in manifest["clips"]:
            frames = list(zip(clip["frames"], clip["frameSha256"], strict=True))
            if proof := clip.get("terminalProof"):
                frames.append((proof["path"], proof["sha256"]))
            for name, source_hash in frames:
                if name in seen:
                    continue
                seen.add(name)
                original = (assets / name).read_bytes()
                packed_hash, mirrored = index["frames"][source_hash]
                packed = archive.read(root + "frames/" + packed_hash + ".webp")
                if sha256(original).hexdigest() != source_hash or sha256(packed).hexdigest() != packed_hash:
                    raise ValueError(f"Frame digest mismatch: {name}")
                with Image.open(BytesIO(original)) as source, Image.open(BytesIO(packed)) as installed:
                    source = source.convert("RGBA")
                    installed = installed.convert("RGBA")
                if mirrored:
                    installed = installed.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
                if source.size != installed.size or source.getchannel("A").tobytes() != installed.getchannel("A").tobytes():
                    raise ValueError(f"Frame dimensions or alpha changed: {name}")
                same = source.tobytes() == installed.tobytes()
                count += 1
                exact += int(same)
                if clip["id"] in ("chase", "chase_right"):
                    if not same:
                        raise ValueError(f"Chase pixels changed: {name}")
                    chase += 1
                if not same:
                    alpha = source.getchannel("A").point(lambda a: 255 if a > 12 else 0)
                    black = Image.new("RGB", source.size)
                    # Opaque and translucent foreground both contribute in proportion to visibility.
                    left = Image.composite(source.convert("RGB"), black, source.getchannel("A"))
                    right = Image.composite(installed.convert("RGB"), black, installed.getchannel("A"))
                    diff = ImageStat.Stat(ImageChops.difference(left, right), mask=alpha)
                    if not diff.count[0]:
                        continue
                    mse = sum(diff.sum2) / (3 * diff.count[0])
                    worst = max(worst, (mse, name))
    return {"frames": count, "exactAlpha": count, "exactRgba": exact, "exactChaseFrames": chase,
            "lowestForegroundPsnrDb": round(10 * math.log10(255 ** 2 / worst[0]), 2) if worst[0] else None,
            "lowestPsnrFrame": worst[1] or None}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--assets", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/assets")
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.assets), indent=2))
