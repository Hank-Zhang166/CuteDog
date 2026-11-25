"""Whole-dog wave and vertical hop. Both routes preserve their old 24-Hz joins."""

import argparse, os, shutil, sys
from functools import lru_cache
from pathlib import Path
import cv2
import numpy as np
from PIL import Image
from scipy.interpolate import RBFInterpolator

HERE = Path(__file__).resolve().parent
POSES = HERE.parents[1] / "poses"
Y, X = np.mgrid[:512, :512].astype("f")
GY, GX = np.mgrid[:519:5, :519:5]
GRID = np.column_stack((GX.ravel(), GY.ravel()))
STATIC = np.array(
    [
        (-30, -30),
        (256, -35),
        (545, -30),
        (-25, 245),
        (550, 245),
        (-30, 545),
        (256, 545),
        (545, 545),
    ],
    float,
)


def ease(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def pm(a):
    a = np.asarray(a, dtype="f") / 255
    a[:, :, :3] *= a[:, :, 3:]
    return a


def straight(a):
    return np.uint8(
        np.round(
            255
            * np.clip(
                np.dstack((a[:, :, :3] / np.maximum(a[:, :, 3:], 1e-7), a[:, :, 3])),
                0,
                1,
            )
        )
    )


def rem(im, x, y):
    return cv2.remap(
        im,
        x.astype("f"),
        y.astype("f"),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
    )


def over(a, b):
    return a + (1 - a[:, :, 3:]) * b


# Both eyes, nose, lip corner/chin, both ears, front/back of shoulder,
# lifted paw, wrist and elbow. Its grounded counterpart has separate pins.
MARKS = {
    "sit": [
        (131, 144),
        (157, 151),
        (111, 165),
        (163, 178),
        (128, 199),
        (163, 80),
        (205, 83),
        (141, 248),
        (242, 226),
        (144, 412),
        (154, 391),
        (156, 315),
    ],
    "A": [
        (177, 128),
        (211, 136),
        (156, 154),
        (218, 169),
        (177, 193),
        (220, 49),
        (265, 53),
        (159, 261),
        (310, 231),
        (124, 374),
        (148, 352),
        (178, 310),
    ],
    "B": [
        (176, 129),
        (215, 141),
        (162, 157),
        (216, 174),
        (179, 200),
        (219, 48),
        (267, 53),
        (162, 266),
        (310, 231),
        (122, 267),
        (135, 290),
        (167, 325),
    ],
    "C": [
        (193, 131),
        (238, 147),
        (191, 161),
        (239, 179),
        (203, 209),
        (222, 47),
        (274, 54),
        (168, 267),
        (306, 231),
        (119, 201),
        (128, 229),
        (155, 278),
    ],
}
FIXED = np.array(
    [
        (197, 417),
        (205, 392),
        (203, 350),
        (263, 417),
        (305, 404),
        (310, 358),
        (367, 277),
        (394, 359),
    ],
    float,
)


def feature_points(p):
    extra = []
    for index, offsets in [
        (0, [(-3, 0), (3, 0)]),
        (1, [(-6, -2), (6, 1)]),
        (2, [(-7, 0), (6, 0), (0, 5)]),
        (3, [(-5, 1), (3, 1)]),
        (9, [(-8, -2), (7, -4), (2, 9)]),
    ]:
        extra.extend(p[index] + ofs for ofs in offsets)
    return np.vstack((p, extra, FIXED, STATIC))


def field(now, rest):
    model = RBFInterpolator(now, rest - now, kernel="thin_plate_spline", smoothing=2.2)
    d = model(GRID).reshape(GX.shape + (2,)).astype("f")
    return rem(d, X / 5, Y / 5)


def warp(im, now, rest):
    df = field(feature_points(now), feature_points(rest))
    return rem(im, X + df[:, :, 0], Y + df[:, :, 1])


def distance(a):
    m = (a > 0.5).astype("u1")
    return cv2.distanceTransform(m, cv2.DIST_L2, 5) - cv2.distanceTransform(
        1 - m, cv2.DIST_L2, 5
    )


def registered(atlas, neutral):
    base = pm(neutral)
    # The hidden limb must vacate its old ground contact. The planted right
    # foreleg retains its own anatomical boundary, foot and original painting.
    edge = np.interp(
        Y,
        [285, 302, 323, 346, 369, 385, 397, 408, 420, 429],
        [170, 179, 184, 188, 186, 181, 174, 170, 172, 183],
    )
    ground = base * ease((X - edge + 0.7) / 1.4)[:, :, None]
    regs = {"sit": base}
    marks = {"sit": np.array(MARKS["sit"], float)}
    cell = atlas.width // 3
    ratio = 0.82
    dx, dy = 8, 41
    for k, n in enumerate(["A", "B", "C"]):
        im = pm(atlas.crop((k * cell, 0, (k + 1) * cell, cell)))
        layer = rem(
            im, (X - dx) / ratio * (cell / 512), (Y - dy) / ratio * (cell / 512)
        )
        q = np.array(MARKS[n], float) * ratio + [dx, dy]
        # Keep the one painted raised limb down to its actual sole, while the
        # photographed extra ground footprint behind it is removed.
        if n == "A":
            curl = [
                (306, 70),
                (326, 122),
                (335, 153),
                (338, 169),
                (344, 180),
                (349, 184),
            ]
        elif n == "B":
            curl = [
                (280, 81),
                (299, 101),
                (318, 121),
                (326, 136),
                (331, 148),
                (335, 159),
                (340, 171),
                (345, 180),
                (352, 185),
            ]
        else:
            curl = [
                (260, 79),
                (281, 97),
                (295, 112),
                (307, 133),
                (313, 147),
                (319, 161),
                (325, 172),
                (333, 180),
                (345, 185),
            ]
        pts = curl + [
            (368, 186),
            (384, 182),
            (396, 175),
            (408, 170),
            (425, 173),
            (433, 180),
        ]
        border = np.interp(Y, *np.asarray(pts).T)
        keep = ease((X - border + 1.2) / 2.4)
        if n == "A":
            split = np.interp(
                Y,
                [305, 320, 330, 335, 340, 348, 358, 368, 378],
                [131, 143, 153, 161, 151, 141, 137, 131, 108],
            )
            moving = ease((split - X + 1.2) / 2.4) * (1 - ease((Y - 363) / 7))
            keep = np.maximum(keep, moving)
        layer *= keep[:, :, None]
        w = ease((Y - 346) / 36) * ease((X - border - 1) / 8)
        den = (1 - w) * layer[:, :, 3] + w * ground[:, :, 3]
        a = np.where(w < 0.5, layer[:, :, 3], ground[:, :, 3])
        common = (layer[:, :, 3] > 0.88) & (ground[:, :, 3] > 0.88)
        a = np.where(common, den, a)
        rgb = (
            layer[:, :, :3] * (1 - w[:, :, None]) + ground[:, :, :3] * w[:, :, None]
        ) / np.maximum(den[:, :, None], 1e-7)
        regs[n] = np.dstack((rgb * a[:, :, None], a)).astype("f")
        marks[n] = q
    return regs, marks


def between(a, b, pa, pb, t):
    if t <= 0:
        return a
    if t >= 1:
        return b
    q = (1 - t) * pa + t * pb
    qa, qb = warp(a, q, pa), warp(b, q, pb)
    aa, bb = qa[:, :, 3], qb[:, :, 3]
    av = (1 - t) * aa + t * bb
    contour = np.clip(0.5 + (1 - t) * distance(aa) + t * distance(bb), 0, 1)
    w = ease(np.abs(aa - bb) / 0.67)
    aout = av + (contour - av) * w
    rgb = (qa[:, :, :3] * (1 - t) + qb[:, :, :3] * t) / np.maximum(av[:, :, None], 1e-7)
    # A single sharp registration owns the eyes/nose and paw pads at every
    # frame. Their texture never dissolves into an offset duplicate.
    owner = qa if t < 0.5 else qb
    opaque = (qa[:, :, 3] > 0.94) & (qb[:, :, 3] > 0.94)
    masks = np.zeros_like(X)
    for i, rx, ry in [(0, 6, 5), (1, 10, 7), (2, 12, 9), (3, 8, 6), (9, 15, 13)]:
        cx, cy = q[i]
        masks = np.maximum(
            masks, ease((1.45 - ((X - cx) / rx) ** 2 - ((Y - cy) / ry) ** 2) / 0.65)
        )
    masks *= opaque
    rgb = (
        rgb * (1 - masks[:, :, None])
        + owner[:, :, :3] / np.maximum(owner[:, :, 3:], 1e-7) * masks[:, :, None]
    )
    return np.dstack((rgb * aout[:, :, None], aout)).astype("f")


def seat_bridge(directory, indices):
    """Sample the six approved sitting drawings through one moving surface."""
    sources = [
        pm(Image.open(directory / f"{i:05}.webp").convert("RGBA")) for i in indices
    ]
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    dis.setFinestScale(0)

    def grey(a):
        q = a[:, :, :3] + (1 - a[:, :, 3:]) * np.array([0.22, 0.29, 0.36])
        return cv2.cvtColor(
            np.uint8(np.rint(np.clip(q, 0, 1) * 255)), cv2.COLOR_RGB2GRAY
        )

    pairs = {}

    def draw(t):
        v = 5 * ease(t)
        j = min(4, int(v))
        u = float(v - j)
        a, b = sources[j : j + 2]
        if u < 1e-5:
            return a
        if u > 1 - 1e-5:
            return b
        right = u > 0.5
        if (j, right) not in pairs:
            pairs[j, right] = dis.calc(
                grey(b if right else a), grey(a if right else b), None
            )
        flow = pairs[j, right]
        frac = (1 - u) if right else u
        delta = flow
        for _ in range(3):
            delta = rem(flow, X - frac * delta[:, :, 0], Y - frac * delta[:, :, 1])
        return rem(
            b if right else a, X - frac * delta[:, :, 0], Y - frac * delta[:, :, 1]
        )

    return draw


def wave(base, dest):
    dest.mkdir(exist_ok=True, parents=True)
    atlas = Image.open(HERE / "wave-reference.png").convert("RGBA")
    reg, m = registered(atlas, Image.open(base / "00008.webp").convert("RGBA"))
    reg["end"] = pm(Image.open(base / "00040.webp").convert("RGBA"))
    m["end"] = m["sit"]
    qp = m["C"].copy()
    qp[9] += [9, -8]
    qp[10] += [5, -3]
    qp[3] += [1, -1]
    reg["high"] = warp(reg["C"], qp, m["C"])
    m["high"] = qp
    keys = [
        (8, "sit"),
        (12, "A"),
        (15, "B"),
        (18, "C"),
        (21, "high"),
        (24, "C"),
        (27, "high"),
        (29, "C"),
        (33, "B"),
        (37, "A"),
        (40, "end"),
    ]
    settle = seat_bridge(base, [0, 1, 3, 4, 6, 8])
    rise = seat_bridge(base, [48, 46, 45, 43, 42, 40])
    for src in sorted(base.glob("*.webp")):
        i = int(src.stem)
        out = dest / src.name
        if i in (0, 8, 40, 48):
            shutil.copyfile(src, out)
            continue
        if i < 8 or i > 40:
            v = settle(i / 8) if i < 8 else rise((48 - i) / 8)
            Image.fromarray(straight(v)).save(out, lossless=True, method=1, exact=True)
            continue
        (f0, s), (f1, e) = next(
            (a, b) for a, b in zip(keys, keys[1:]) if a[0] <= i <= b[0]
        )
        t = (i - f0) / (f1 - f0)
        v = between(reg[s], reg[e], m[s], m[e], t)
        Image.fromarray(straight(v)).save(out, lossless=True, method=1, exact=True)


def hop(dest):
    sys.path.insert(0, str(HERE.parent / "gait"))
    import locomotion as gait
    import math

    dest.mkdir(exist_ok=True, parents=True)
    rig = gait.Renderer()
    for i in range(27):
        st = gait.neutral()
        b = st["b"]
        front = 0.0
        lift = 0.0
        if i < 7:
            u = i / 7
            b["cy"] = 9.3 * gait.B(u) - 1.6 * gait.unit(u)
            b["angle"] = 0.019 * gait.B(u)
            b["head"] = -0.016 * gait.B(u)
        elif i <= 19:
            u = (i - 7) / 12
            front = gait.B(u)
            lift = 63 * 4 * u * (1 - u)
            b["cy"] = -1.6 - 0.97 * lift
            b["angle"] = -0.063 * np.sin(2 * np.pi * u)
            b["tail"] = 0.09 * front
            b["head"] = -0.052 * front
        else:
            u = (i - 19) / 7
            b["cy"] = -1.6 * (1 - gait.unit(u)) + 11.2 * gait.B(u)
            b["angle"] = -0.021 * gait.B(u)
        for k, points in gait.LEG.items():
            fore = k[0] == "f"
            toe = points[-1] + gait.z(
                (-10 if fore else 6) * front, -lift - (20 if fore else 12) * front
            )
            st["legs"][k] = (toe, lift < 0.05, 0.35 * front, 0)
        st["neutral"] = i in (0, 1, 25, 26)
        Image.fromarray(rig.render(st)).save(
            dest / f"{i:05}.webp", lossless=True, method=1, exact=True
        )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("base", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("names", nargs="*", default=["wave", "hop"])
    a = p.parse_args()
    if "wave" in a.names:
        wave(a.base / "wave", a.output / "wave")
    if "hop" in a.names:
        hop(a.output / "hop")
