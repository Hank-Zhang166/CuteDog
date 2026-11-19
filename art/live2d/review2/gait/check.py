"""Generate the opposite heading and verify visible, rasterized contact markers."""

import json, hashlib
import numpy as np
from scipy.ndimage import label
from PIL import Image
import moderngl
from locomotion import ROOT, LEG, clips, Renderer, SOURCE

frames = ROOT / "frames"
states = clips()
for name, (poses, travel) in states.items():
    src = frames / name
    dest = frames / (name + "_right")
    dest.mkdir(exist_ok=True)
    md = json.loads((src / "metadata.json").read_text())
    md["clip"] = dest.name
    md["heading"] = "right"
    md["directionSource"] = "horizontal reflection of verified left facing render"
    for row in md["landmarks"]:
        for pp in row["paws"].values():
            pp["x"] = round(512 - pp["x"], 5)
            pp["worldX"] = round(pp["x"] + row["travelPx"], 5)
    for old in dest.glob("[0-9][0-9][0-9][0-9][0-9].png"):
        if int(old.stem) >= len(poses):
            old.unlink()
    for i in range(len(poses)):
        pic = src / f"{i:05}.png"
        with Image.open(pic) as im:
            im.transpose(Image.Transpose.FLIP_LEFT_RIGHT).save(
                dest / pic.name, compress_level=2
            )
    (dest / "metadata.json").write_text(json.dumps(md, indent=1))
rig = Renderer()
marks = {"hf": (332, 411), "ff": (151, 415), "hn": (390, 416), "fn": (195, 417)}
colors = {"fn": (1, 0, 0), "ff": (0, 1, 0), "hn": (0, 0, 1), "hf": (1, 0, 1)}
yy, xx = np.mgrid[:512, :512]
for index, limbs in enumerate((("hf",), ("ff",), ("fn", "hn"))):
    a = rig.layers[index][:, :, 3].astype("f4") / 255.0
    c = np.zeros((512, 512, 4), "f4")
    c[:, :, 3] = a
    for k in limbs:
        px, py = marks[k]
        spot = (xx - px) ** 2 + (yy - py) ** 2 <= 23.5
        c[:, :, :3] += a[:, :, None] * spot[:, :, None] * colors[k]
    t = rig.gl.texture((512, 512), 4, c.astype("f2").tobytes(), dtype="f2")
    t.filter = (moderngl.LINEAR, moderngl.LINEAR)
    t.repeat_x = t.repeat_y = False
    rig.tex[index] = t
all_reports = {}
all_stats = []
for name, (poses, dist) in states.items():
    src = frames / name
    previous = {}
    edges = []
    measured = []
    samples = 0
    air = []
    previous_a = None
    pixel_deltas = []
    connected = []
    for i, (p, r) in enumerate(zip(poses, np.r_[0, np.cumsum(dist)])):
        file = src / f"{i:05}.png"
        a = np.asarray(Image.open(file))
        fg = a[:, :, 3] > 127
        iy, ix = np.where(fg)
        edges.append(min(ix.min(), iy.min(), 511 - ix.max(), 511 - iy.max()))
        lab, n = label(fg)
        counts = np.bincount(lab.ravel())[1:]
        connected.append(1 - counts.max() / counts.sum())
        if previous_a is not None:
            pixel_deltas.append(np.count_nonzero(previous_a != fg))
        previous_a = fg
        pix = rig.render(p, exact=False)
        for k in LEG:
            P, on, _, _ = p["legs"][k]
            marker = np.array(colors[k]) * 255
            detect = (pix[:, :, 3] > 244) & np.all(
                np.abs(pix[:, :, :3].astype("i2") - marker) < 20, axis=2
            )
            y, x = np.where(detect)
            moment = (
                (x.mean() - r, y.mean()) if len(x) >= 57 else None
            )  # >~85% of 69 hard interior center pixels
            if moment and on:
                prev = previous.get(k)
                if prev and prev[0] == i - 1:
                    measured.append(
                        float(np.hypot(moment[0] - prev[1][0], moment[1] - prev[1][1]))
                    )
                previous[k] = (i, moment)
                samples += 1
            else:
                previous.pop(k, None)
        if all(LEG[k][-1].imag - p["legs"][k][0].imag > 0.05 for k in LEG):
            air.append(min(LEG[k][-1].imag - p["legs"][k][0].imag for k in LEG))
    rep = {
        "visiblePlantedMarkerFrames": samples,
        "visibleAdjacentContactPairs": len(measured),
        "maxRasterMarkerSlipPx": round(max(measured, default=0), 3),
        "rmsRasterMarkerSlipPx": round(
            np.sqrt(np.mean(np.square(measured))) if measured else 0, 3
        ),
        "pct95RasterMarkerSlipPx": round(
            np.percentile(measured, 95) if measured else 0, 3
        ),
        "maxDetachedOpaqueAreaFraction": round(max(connected), 6),
        "minimumOpaqueMarginPx": int(min(edges)),
        "flightFrames": len(air),
        "largestSharedContactPlaneClearancePx": round(max(air, default=0.0), 2),
        "maxSilhouetteChangePx": int(max(pixel_deltas)),
        "medianSilhouetteChangePx": int(np.median(pixel_deltas)),
    }
    all_reports[name] = rep
    all_stats += measured
    print(
        name,
        rep["visibleAdjacentContactPairs"],
        rep["maxRasterMarkerSlipPx"],
        rep["maxDetachedOpaqueAreaFraction"],
        rep["minimumOpaqueMarginPx"],
        flush=True,
    )
report = {
    "source": "art/live2d/poses/Stand.png",
    "sourceSHA256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "canvas": [512, 512],
    "fps": 24,
    "contactTest": "For each final deformed coat, render four colored 9px sole markers behind exactly the same occluding paint; compare world-space centroids over adjacent in-stance frames with enough visible center pixels. Partial or total occlusion is excluded. The intentional slip has a sliding interval before replanting. Supplied trajectories contain continuous geometric sole centers independent of marker visibility.",
    "visibleStationaryContactSamples": len(all_stats),
    "maxVisibleContactErrorPx": round(max(all_stats), 3),
    "rmsVisibleContactErrorPx": round(float(np.sqrt(np.mean(np.square(all_stats)))), 3),
    "clips": all_reports,
}
(ROOT / "qa.json").write_text(json.dumps(report, indent=1))
