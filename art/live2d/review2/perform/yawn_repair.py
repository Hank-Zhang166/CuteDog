"""Locally registered jaw/eyelid keyforms on the original seated Samoyed."""

import math
from functools import lru_cache
import cv2
import numpy as np
from PIL import Image
from scipy.spatial import Delaunay
from scipy.interpolate import PchipInterpolator
from performance import SRC, premul, smooth, load

SIZE = 512
sit = load("Sit05")
base = premul(sit)
yy, xx = np.mgrid[:SIZE, :SIZE].astype("f")
SHEET = "care-seated-atlas.png"
# Nose, near eye, far eye, upper lip L to R, visible tongue and lower jaw,
# chin and cheek. The heavy fur is anchored outside these facial contours.
features = np.array(
    [
        [
            (112, 163),
            (104, 159),
            (120, 162),
            (111, 171),
            (149, 152),
            (157, 146),
            (165, 153),
            (157, 157),
            (130, 145),
            (106, 177),
            (115, 181),
            (129, 184),
            (146, 182),
            (160, 177),
            (117, 194),
            (117, 199),
            (131, 195),
            (147, 188),
            (158, 181),
            (118, 205),
            (138, 202),
            (159, 193),
            (173, 181),
            (148, 165),
        ],
        [
            (73, 98),
            (65, 94),
            (82, 98),
            (72, 107),
            (116, 87),
            (124, 82),
            (135, 89),
            (125, 92),
            (95, 82),
            (68, 114),
            (79, 120),
            (91, 124),
            (106, 124),
            (117, 124),
            (86, 138),
            (85, 143),
            (99, 139),
            (110, 133),
            (118, 127),
            (89, 149),
            (107, 147),
            (124, 137),
            (132, 124),
            (113, 103),
        ],
        [
            (64, 92),
            (54, 88),
            (73, 92),
            (62, 101),
            (105, 84),
            (115, 77),
            (127, 85),
            (116, 88),
            (84, 79),
            (60, 111),
            (71, 115),
            (85, 119),
            (98, 121),
            (111, 127),
            (77, 153),
            (80, 158),
            (95, 152),
            (105, 142),
            (113, 133),
            (82, 164),
            (102, 157),
            (118, 144),
            (127, 127),
            (104, 100),
        ],
        [
            (55, 77),
            (45, 74),
            (64, 77),
            (53, 86),
            (95, 75),
            (101, 72),
            (115, 79),
            (103, 75),
            (73, 68),
            (51, 98),
            (62, 103),
            (78, 108),
            (92, 111),
            (103, 117),
            (67, 153),
            (74, 165),
            (88, 159),
            (99, 146),
            (107, 129),
            (78, 172),
            (99, 163),
            (114, 145),
            (123, 120),
            (96, 92),
        ],
    ],
    dtype="f",
)
jawkeys = np.array([0, 0.29, 0.66, 1.0], dtype="f")
noseTargets = np.array([[112, 163], [111.5, 162], [112.5, 157], [115, 151]], dtype="f")
scales = [1, 0.875, 0.865, 0.86]


def mapped(j, q):
    return (q - features[j, 0]) * scales[j] + noseTargets[j]


def head_motion(q, level):
    x, y = q[:, 0], q[:, 1]
    w = smooth((272 - y) / 63) * smooth((273 - x) / 74)
    px, py = 182, 221
    a = level * 0.14
    dx = (np.cos(a) - 1) * (x - px) - np.sin(a) * (y - py)
    dy = np.sin(a) * (x - px) + (np.cos(a) - 1) * (y - py)
    # The four texture outline vertices enclose empty space and stay fixed.
    w = w * ((x > 4) & (x < 507) & (y > 4) & (y < 507))
    return q + np.column_stack([(dx - 3 * level) * w, dy * w])


outer = np.array(
    [
        (3, 3),
        (508, 3),
        (508, 508),
        (3, 508),
        (70, 77),
        (116, 60),
        (170, 54),
        (204, 64),
        (235, 80),
        (252, 105),
        (263, 145),
        (273, 185),
        (277, 225),
        (263, 253),
        (245, 267),
        (207, 277),
        (177, 278),
        (142, 271),
        (118, 252),
        (90, 233),
        (70, 213),
        (70, 178),
        (70, 137),
        (95, 117),
        (117, 103),
        (141, 98),
        (164, 102),
        (188, 115),
        (202, 136),
        (208, 158),
        (209, 190),
        (198, 216),
        (179, 230),
        (154, 240),
        (128, 232),
        (102, 219),
        (90, 196),
        (88, 151),
        (102, 138),
        (123, 125),
        (147, 126),
        (174, 129),
        (181, 148),
        (188, 168),
        (186, 196),
        (169, 214),
    ],
    dtype="f",
)
points = np.array(
    [np.vstack((mapped(j, features[j]), head_motion(outer, jawkeys[j]))) for j in range(4)]
)
# Place the surrounding neck fur beneath the opening lower jaw, with a gradual
# return to zero at the fixed chest. This keeps the skin response continuous.
for j, u in enumerate(jawkeys):
    for k, (x, y) in enumerate(outer):
        if y >= 206 and y <= 253 and 93 < x < 183:
            points[j, len(features[0]) + k] += (
                np.array([3.5, 30.0])
                * u
                * math.exp(-(((x - 125) / 49) ** 2) - ((y - 216) / 24) ** 2)
            )
tri = Delaunay(points.mean(axis=0)).simplices


def area(vertices, tr):
    p = vertices[tr]
    v = p[:, 1] - p[:, 0]
    w = p[:, 2] - p[:, 0]
    return v[:, 0] * w[:, 1] - v[:, 1] * w[:, 0]


def field(dst, src):
    mx = xx.copy()
    my = yy.copy()
    for t in tri:
        p = dst[t]
        q = src[t]
        if np.max(abs(p - q)) < 0.00005:
            continue
        x0, y0 = np.maximum(np.floor(p.min(0)).astype(int), 0)
        x1, y1 = np.minimum(np.ceil(p.max(0)).astype(int) + 1, 512)
        a, b, c = p
        dv = b - a
        dw = c - a
        det = dv[0] * dw[1] - dv[1] * dw[0]
        if abs(det) < 0.00001 or x1 <= x0 or y1 <= y0:
            continue
        x = xx[y0:y1, x0:x1] - a[0]
        y = yy[y0:y1, x0:x1] - a[1]
        r = (x * dw[1] - y * dw[0]) / det
        s = (dv[0] * y - dv[1] * x) / det
        inside = (r >= -0.0001) & (s >= -0.0001) & (r + s <= 1.0001)
        for k, m in enumerate([mx, my]):
            cut = m[y0:y1, x0:x1]
            v = q[0, k] + r * (q[1, k] - q[0, k]) + s * (q[2, k] - q[0, k])
            cut[inside] = v[inside]
    return mx, my


def warp(im, dp, sp):
    return cv2.remap(im, *field(dp, sp), cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT)


@lru_cache(None)
def plates():
    sheet = Image.open(SRC / SHEET).convert("RGBA")
    c = sheet.width // 3
    out = [base]
    for j in [1, 2, 3]:
        src = premul(np.asarray(sheet.crop(((j - 1) * c, 2 * c, j * c, 3 * c))))
        q = points[j]
        ox, oy = noseTargets[j]
        z = scales[j]
        sx, sy = features[j, 0]
        donor = cv2.remap(
            src,
            (xx - ox) / z + sx,
            (yy - oy) / z + sy,
            cv2.INTER_CUBIC,
            borderMode=cv2.BORDER_CONSTANT,
        )
        neutral = warp(base, q, points[0])
        # Keep the complete expression (both lids, one nose and both lips)
        # on one painted patch. The feather runs through flat cheek/ruff fur,
        # outside all dark edges, including the silhouette under the muzzle.
        eye = q[5]
        width = q[22, 0] + 30
        bottom = q[19, 1] + 20
        weight = (
            smooth((yy - eye[1] + 34) / 13)
            * smooth((bottom + 7 - yy) / 14)
            * smooth((width - xx) / 19)
            * smooth((xx - 70) / 16)
        )[:, :, None]
        out.append((neutral * (1 - weight) + donor * weight).astype("f"))
    return out


def keys_at(frame):
    ts = [0, 5, 11, 18, 30, 36, 45, 53, 60, 64]
    values = [0, 0.035, 0.15, 0.46, 0.98, 1, 0.67, 0.22, 0, 0]
    return float(PchipInterpolator(ts, values)(frame))
