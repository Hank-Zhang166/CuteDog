"""Render the registered native dog through Cubism Core; package 24 Hz interval samples."""

from pathlib import Path
import argparse
import csv
import json
import math
import os
import shutil
import subprocess
import tempfile

import numpy as np
from PIL import Image
from scipy.ndimage import find_objects, label

from author_motion import author, clean, ease, GAITS, PARAMS, FPS, sample, state, write
from render import bake


def support_x_distance(poses_dir):
    """Register toe clusters close to the common ground and measure planted rightward travel."""
    bank = sorted(poses_dir.glob("Walk[0-9][0-9].png"))
    points = []
    for filename in bank:
        a = np.asarray(Image.open(filename).convert("RGBA"))[:, :, 3] > 180
        bottom = np.where(a, np.arange(a.shape[0])[:, None], 0).max(axis=0)
        labels, count = label(bottom >= bottom.max() - 12)
        centers = []
        for sl in find_objects(labels):
            region = sl[0]
            if region.stop - region.start >= 5:
                centers.append(
                    ((region.start + region.stop - 1) / 2, int(bottom[region].max()))
                )
        points.append(centers)
    dx = []
    for i, left in enumerate(points):
        right = points[(i + 1) % len(points)]
        candidates = []
        for x, y in left:
            near = [
                (abs(x2 - x), x2 - x, y2 - y)
                for x2, y2 in right
                if abs(x2 - x) <= 54 and abs(y2 - y) <= 5
            ]
            if near:
                _, travel, drop = min(near)
                if travel > 1:
                    candidates.append(travel)
        dx.append(float(np.median(candidates)) if candidates else None)
    known = [i for i, v in enumerate(dx) if v is not None]
    if not known:
        raise ValueError("No planted paw trajectory recovered")
    interp = np.interp(
        np.arange(len(dx)), known, [dx[i] for i in known], period=len(dx)
    )
    # A short cyclic low-pass distributes distance over the contact exchange.
    filtered = (np.roll(interp, 1) + 2 * interp + np.roll(interp, -1)) / 4.0
    return bank, filtered


def travel_report(library, pose_map, poses_dir):
    names, dx = support_x_distance(poses_dir)
    indices = {pose_map[p.stem]: i for i, p in enumerate(names)}
    report = {}
    measurement = {}
    for name, m in library.items():
        n = round(m["Meta"]["Duration"] * FPS)
        report[name] = [0.0] * n
        if name not in GAITS:
            continue
        track = next(c for c in m["Curves"] if c["Id"] == "ParamPose")
        dest = report[name]
        for i in range(n):
            p = indices.get(round(sample(track, i)[0]))
            q = indices.get(round(sample(track, i + 1)[0]))
            if p is None or q is None:
                continue
            steps = (q - p) % len(dx)
            if steps <= len(dx) // 2:
                dest[i] = clean(sum(dx[(p + j) % len(dx)] for j in range(steps)) / 512)
        if not any(dest):
            raise ValueError(f"No movement in {name}")
        measurement[name] = clean(sum(dest))
    return report, dict(
        method="registered near-ground toe-cluster forward deltas, interpolated through support exchanges",
        units="px",
        intervals=[clean(x) for x in dx],
        traveledWidths=measurement,
    )


def prepare_prop(filename):
    art = Image.open(filename).convert("RGBA")

    def crop(box, width):
        im = art.crop(box)
        im = im.crop(im.getchannel("A").getbbox())
        return im.resize(
            (width, round(im.height * width / im.width)), Image.Resampling.LANCZOS
        )

    return {
        "ball": crop((0, 0, 887, 887), 31),
        "biscuit": crop((887, 0, 1774, 887), 26),
    }


def add_prop(dog, name, t, props):
    if t <= 0 or t >= 1:
        return dog
    scene = Image.new("RGBA", dog.size)
    if name == "play_ball":
        if t < 0.4:
            x = -32 + 165 * ease(t / 0.4)
        elif t < 0.52:
            x = 133
        else:
            x = 133 - 180 * ease((t - 0.52) / 0.48)
        y = (
            413 - abs(math.sin(math.pi * t / 0.4)) * 8
            if t <= 0.4
            else 413 - 7 * math.sin(math.pi * min(1, (t - 0.52) / 0.48)) ** 2
        )
        angle = -(x + 32) * 180 / (math.pi * 15.5)
        sprite = props["ball"].rotate(angle, Image.Resampling.BICUBIC, expand=True)
    elif name == "treat":
        u = ease(min(1, t / 0.65))
        x = -29 + 146 * u
        y = 237 - 46 * u - 13 * math.sin(math.pi * u)
        sprite = props["biscuit"].rotate(
            -18 * (1 - u), Image.Resampling.BICUBIC, expand=True
        )
        if t > 0.69:
            return dog
    else:
        return dog
    scene.alpha_composite(
        sprite, (round(x - sprite.width / 2), round(y - sprite.height / 2))
    )
    scene.alpha_composite(dog)
    return scene


def main():
    q = argparse.ArgumentParser()
    q.add_argument("model", type=Path)
    q.add_argument("pose_map", type=Path)
    q.add_argument("pose_png", type=Path)
    q.add_argument("prop_png", type=Path)
    q.add_argument("motions", type=Path)
    q.add_argument("output", type=Path)
    q.add_argument(
        "--core", type=Path, default=Path(r"C:\Program Files\Live2D Cubism 5.3\app")
    )
    q.add_argument("--jdk", type=Path, default=Path(r"C:\tools\jdk17\jdk-17.0.2\bin"))
    a = q.parse_args()
    here = Path(__file__).resolve().parent
    model = json.loads(a.model.read_text(encoding="utf-8-sig"))
    moc = a.model.parent / model["FileReferences"]["Moc"]
    options = [
        str(a.core / "jre/bin/java.exe"),
        "-Djava.library.path=" + str(a.core / "dll64"),
        "-cp",
        os.pathsep.join([str(here), str(a.core / "lib/Live2DCubismCore.jar")]),
        "CoreBake",
        str(moc),
    ]
    subprocess.run(
        [
            str(a.jdk / "javac.exe"),
            "-encoding",
            "UTF-8",
            "-cp",
            str(a.core / "lib/Live2DCubismCore.jar"),
            str(here / "CoreBake.java"),
        ],
        check=True,
    )
    info = subprocess.check_output(options, text=True)
    ids = [line.split()[0] for line in info.splitlines() if line.startswith("Param")]
    poses = json.loads(a.pose_map.read_text(encoding="utf-8-sig"))
    library = author(poses, ids, a.motions)
    a.output.mkdir(parents=True, exist_ok=True)
    (a.output / "core-model.txt").write_text(info, encoding="utf-8")
    travel, measurement = travel_report(library, poses, a.pose_png)
    write(a.output / "travel.json", travel)
    write(a.output / "travel-measurement.json", measurement)
    shutil.copyfile(a.motions / "continuity.json", a.output / "continuity.json")
    props = prepare_prop(a.prop_png)
    priority = [
        "stand",
        "walk",
        "run",
        "play_ball",
        "hop",
        "sit",
        "wave",
        "look_right",
        "blink",
        "trot",
        "treat",
        "sit_idle",
    ]
    names = list(dict.fromkeys(priority + list(library)))
    for name in names:
        m = library[name]
        n = round(m["Meta"]["Duration"] * FPS)
        tracks = m["Curves"]
        dest = a.output / name
        with tempfile.TemporaryDirectory(dir=a.output, prefix=".render-") as temp:
            tmp = Path(temp)
            tsv = tmp / "motion.tsv"
            raw = tmp / "frames"
            with tsv.open("w", encoding="utf-8", newline="") as out:
                writer = csv.writer(out, delimiter="\t", lineterminator="\n")
                writer.writerow([c["Id"] for c in tracks])
                for frame in range(n + 1):
                    vals = state(tracks, frame)["position"]
                    writer.writerow([clean(v) for v in vals])
            subprocess.run(
                options + [str(tsv), str(tmp)], check=True, stdout=subprocess.DEVNULL
            )
            bake(a.model, tmp, raw)
            if name in ("treat", "play_ball"):
                for f in range(1, n):
                    path = raw / f"{f:05}.webp"
                    with Image.open(path) as im:
                        original = im.convert("RGBA")
                    adjusted = add_prop(original, name, f / n, props)
                    if adjusted is not original:
                        adjusted.save(path, lossless=True, method=1, exact=True)
            dest.mkdir(exist_ok=True)
            for p in sorted(raw.glob("*.webp")):
                os.replace(p, dest / p.name)
        print(f"Baked {name}: {n} intervals", flush=True)


if __name__ == "__main__":
    main()
