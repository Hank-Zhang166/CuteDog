from pathlib import Path
import numpy as np
from PIL import Image
import sys, json, shutil, os
from scipy.interpolate import RBFInterpolator
from functools import lru_cache

HERE = Path(__file__).resolve().parent
P = Path(os.environ.get("CUTEDOG_GAIT_OUTPUT", HERE)).resolve()
sys.path.insert(0, str(P.parent / "deps"))
import cv2
sys.path.insert(0,str(HERE.parent))
from foreground import registered_mix

POSE = next(
    v
    for v in (HERE.parents[1] / "poses", HERE.parents[1] / "live2d/poses")
    if (v / "Stand.png").is_file()
)
OUT = P / "frames"
FOLDER = OUT / "turn"
FOLDER.mkdir(parents=True, exist_ok=True)
Y, X = np.mgrid[:512, :512].astype("f")
BGS = np.array([0.42, 0.51, 0.56], dtype="f")


def smooth(u):
    u = np.clip(u, 0, 1)
    return u * u * (3 - 2 * u)


def sample(a, x, y):
    return cv2.remap(
        a,
        x.astype("f"),
        y.astype("f"),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
    )


def pm(a):
    a = a.astype("f") / 255
    a[:, :, :3] *= a[:, :, 3:]
    return a


def img(a):
    a = np.array(a)
    a[:, :, :3] /= np.maximum(a[:, :, 3:], 0.001)
    a[a[:, :, 3] < 0.5 / 255] = 0
    return np.clip(np.rint(a * 255), 0, 255).astype("u1")


def over(f, b):
    return f + (1 - f[:, :, 3:]) * b


def paint(a):
    return cv2.cvtColor(
        np.rint(np.clip(a[:, :, :3] + (1 - a[:, :, 3:]) * BGS, 0, 1) * 255).astype(
            "u1"
        ),
        cv2.COLOR_RGB2GRAY,
    )


def load(name):
    return np.array(Image.open(POSE / (name + ".png")).convert("RGBA"))


R = {k: load(k) for k in ("Stand", "Turn01", "Turn02", "Turn03", "Turn04", "Turn05")}
R["StandRight"] = R["Stand"][:, ::-1].copy()
R["Mirror2"] = R["Turn02"][:, ::-1].copy()
R["Mirror3"] = R["Turn03"][:, ::-1].copy()
# Landmarks left to right on screen: both eyes, nose, both corners of open mouth, upper lip, chin, ear tips, cheek extrema, sternum, belly, haunch, plume top, front soles, hind soles.
LM = {
    "Stand": [
        (106.6, 149.8),
        (134.9, 153.7),
        (90.6, 171.5),
        (99, 192),
        (141, 181),
        (116, 190),
        (108, 205),
        (138, 82),
        (179, 84),
        (104, 155),
        (229, 160),
        (150, 249),
        (269, 315),
        (371, 277),
        (342, 119),
        (150, 419),
        (196, 426),
        (332, 417),
        (389, 424),
    ],
    "Turn01": [
        (133.8, 153.7),
        (166.6, 157.1),
        (124.7, 176),
        (130, 194),
        (174, 185),
        (151, 195),
        (139, 209),
        (155, 82),
        (203, 83),
        (119, 157),
        (238, 160),
        (164, 249),
        (258, 316),
        (351, 274),
        (308, 119),
        (159, 419),
        (212, 426),
        (289, 414),
        (360, 420),
    ],
    "Turn02": [
        (161.8, 154.3),
        (200.5, 156.2),
        (164.7, 176.1),
        (162, 189),
        (207, 184),
        (182, 194),
        (176, 208),
        (167, 82),
        (223, 82),
        (133, 162),
        (250, 155),
        (174, 242),
        (253, 315),
        (327, 275),
        (301, 120),
        (172, 425),
        (235, 426),
        (268, 412),
        (337, 418),
    ],
    "Turn03": [
        (208.3, 151.3),
        (248.5, 151.4),
        (229.8, 171.7),
        (202, 180),
        (255, 179),
        (230, 187),
        (230, 204),
        (197, 81),
        (257, 79),
        (158, 157),
        (292, 154),
        (212, 241),
        (240, 315),
        (310, 277),
        (299, 120),
        (196, 426),
        (263, 426),
        (220, 409),
        (313, 410),
    ],
    "Turn04": [
        (329.3, 149.6),
        (359.7, 145.2),
        (372.7, 166.7),
        (323, 177),
        (368, 182),
        (350, 189),
        (356, 202),
        (286, 79),
        (338, 80),
        (273, 153),
        (371, 153),
        (315, 242),
        (253, 309),
        (169, 276),
        (173, 117),
        (274, 425),
        (330, 420),
        (153, 414),
        (218, 409),
    ],
    "Turn05": [
        (354.2, 153.1),
        (381.3, 147.9),
        (398.9, 169.9),
        (349, 180),
        (390, 190),
        (371, 191),
        (382, 204),
        (306, 84),
        (352, 83),
        (273, 151),
        (390, 156),
        (329, 249),
        (251, 314),
        (152, 277),
        (168, 121),
        (296, 426),
        (344, 422),
        (132, 415),
        (195, 410),
    ],
}


def mirror_points(a):
    m = np.array(a, dtype=float)
    m[:, 0] = 511.0 - m[:, 0]
    for i, j in ((0, 1), (3, 4), (7, 8), (9, 10), (15, 16), (17, 18)):
        m[[i, j]] = m[[j, i]]
    return m


for name in list(LM):
    LM[name] = np.asarray(LM[name], float)
LM["StandRight"] = mirror_points(LM["Stand"])
LM["Mirror2"] = mirror_points(LM["Turn02"])
LM["Mirror3"] = mirror_points(LM["Turn03"])
STATIC = np.array(
    [
        [-50, -45],
        [110, -45],
        [255, -45],
        [400, -45],
        [558, -45],
        [-42, 139],
        [553, 139],
        [-42, 298],
        [553, 298],
        [-42, 481],
        [553, 481],
        [-42, 558],
        [110, 558],
        [256, 558],
        [400, 558],
        [553, 558],
    ],
    float,
)
gy, gx = np.mgrid[0:516:4, 0:516:4]
grid = np.column_stack((gx.ravel(), gy.ravel()))


def augment(q):
    # Support eye and muzzle volume using small neighborhoods. No cross-face triangle spans both eyes.
    extras = []
    for i, vec in (
        (0, (0, -4)),
        (1, (0, -4)),
        (0, (0, 4)),
        (1, (0, 4)),
        (2, (-5, 0)),
        (2, (5, 0)),
        (2, (0, 4)),
        (7, (0, 11)),
        (8, (0, 11)),
    ):
        extras.append(q[i] + vec)
    for i in range(15, 19):
        extras.extend((q[i] + (-7, -7), q[i] + (7, -7), q[i] + (0, -18)))
    return np.vstack((q, extras, STATIC))


def field(q, a):
    A = augment(q)
    V = augment(a) - A
    rbf = RBFInterpolator(A, V, kernel="thin_plate_spline", smoothing=11.0)
    c = rbf(grid).reshape(gx.shape + (2,)).astype("f")
    f = cv2.remap(c, X / 4, Y / 4, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return f


def foot_progress(i, t):
    # Feet step in rotating pairs. The different takeoff intervals make the pivot readable in projection.
    a, b = ((0.0, 0.64), (0.23, 1), (0.04, 0.69), (0.26, 0.96))[i]
    return float(np.clip((t - a) / (b - a), 0, 1))


class Pair:
    def __init__(self, na, nb):
        self.na, self.nb = na, nb
        self.A, self.B = pm(R[na]), pm(R[nb])
        self.a, self.b = LM[na], LM[nb]
        mid = (self.a + self.b) / 2
        fa = field(mid, self.a)
        fb = field(mid, self.b)
        a = sample(self.A, X + fa[:, :, 0], Y + fa[:, :, 1])
        b = sample(self.B, X + fb[:, :, 0], Y + fb[:, :, 1])
        dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        dis.setFinestScale(0)
        self.flow = np.clip(
            (dis.calc(paint(a), paint(b), None) - dis.calc(paint(b), paint(a), None))
            * 0.5,
            -15,
            15,
        )

    def make(self, t):
        if t <= 0:
            return R[self.na]
        if t >= 1:
            return R[self.nb]
        q = (1 - t) * self.a + t * self.b
        for j in range(4):
            k = j + 15
            u = foot_progress(j, t)
            f = smooth(u)
            q[k] = (1 - f) * self.a[k] + f * self.b[k]
            q[k, 1] -= (
                min(12, 1.4 + abs(self.a[k, 0] - self.b[k, 0]) * 0.16)
                * np.sin(np.pi * u) ** 2
            )
        fa, fb = field(q, self.a), field(q, self.b)
        flow = sample(
            self.flow,
            X + (fa[:, :, 0] + fb[:, :, 0]) * 0.5,
            Y + (fa[:, :, 1] + fb[:, :, 1]) * 0.5,
        )
        a = sample(
            self.A,
            X + fa[:, :, 0] - t * flow[:, :, 0],
            Y + fa[:, :, 1] - t * flow[:, :, 1],
        )
        b = sample(
            self.B,
            X + fb[:, :, 0] + (1 - t) * flow[:, :, 0],
            Y + fb[:, :, 1] + (1 - t) * flow[:, :, 1],
        )
        # Interpolate the contour in signed distance only at genuine occlusions. Fine guard-hair coverage is retained along agreeing contours.
        aa, bb = a[:, :, 3], b[:, :, 3]
        ma = aa > 0.5
        mb = bb > 0.5
        d1 = cv2.distanceTransform(
            ma.astype("u1"), cv2.DIST_L2, 3
        ) - cv2.distanceTransform((~ma).astype("u1"), cv2.DIST_L2, 3)
        d2 = cv2.distanceTransform(
            mb.astype("u1"), cv2.DIST_L2, 3
        ) - cv2.distanceTransform((~mb).astype("u1"), cv2.DIST_L2, 3)
        d = (1 - t) * d1 + t * d2
        cov = np.clip(0.5 + d, 0, 1)
        strength = smooth(min(t, 1 - t) / 0.14) * smooth(
            (np.abs(aa - bb) - 0.10) / 0.66
        )
        seen = (1 - t) * aa + t * bb
        opacity = (seen * (1 - strength) + cov * strength)[:, :, None]
        rgb = (a[:, :, :3] * (1 - t) + b[:, :, :3] * t) / np.maximum(
            0.0001, seen[:, :, None]
        )
        return img(np.concatenate([rgb * opacity, opacity], axis=2))


def trim(a, lo, hi):
    m = smooth((X - lo + 1.3) / 2.6) * (1 - smooth((X - hi + 1.3) / 2.6))
    return a * m[:, :, None]


def row(points):
    return np.interp(Y, *np.array(points).T).astype("f")


def lower_plates(name):
    a = pm(R[name])
    if name == "Turn02":
        foreleft = trim(
            a, 123, row([(0, 205), (340, 207), (386, 206), (409, 202), (430, 201)])
        )
        foreright = trim(
            a,
            row(
                [
                    (0, 210),
                    (338, 208),
                    (365, 210),
                    (390, 215),
                    (403, 211),
                    (421, 204),
                    (430, 208),
                ]
            ),
            row(
                [
                    (0, 264),
                    (328, 274),
                    (350, 272),
                    (373, 267),
                    (395, 264),
                    (410, 264),
                    (426, 260),
                ]
            ),
        )
        hindright = trim(a, row([(0, 300), (345, 308), (387, 308), (426, 304)]), 390)
        last, hidden = 337, 267
        down = 8
    else:
        foreleft = trim(
            a,
            145,
            row(
                [
                    (0, 216),
                    (335, 223),
                    (363, 220),
                    (391, 218),
                    (403, 219),
                    (415, 223),
                    (427, 222),
                ]
            ),
        )
        foreright = trim(
            a,
            row([(0, 231), (332, 231), (362, 235), (385, 237), (407, 236), (428, 238)]),
            row(
                [
                    (0, 283),
                    (331, 287),
                    (366, 286),
                    (387, 285),
                    (406, 289),
                    (419, 291),
                    (428, 288),
                ]
            ),
        )
        hindright = trim(
            a,
            row([(0, 289), (341, 289), (362, 291), (390, 289), (402, 286), (417, 288)]),
            360,
        )
        last, hidden = 313, 221
        down = 7
    # Its other side was mostly concealed in both references. Reuse the same actual rear foot below the hock; perspective makes this far foot slightly shorter and narrower.
    hfore = sample(
        hindright, last + (X - hidden) / 0.83, Y + down * smooth((Y - 321) / 78)
    )
    hfore[:, :, :3] *= np.array([0.92, 0.90, 0.88], np.float32)
    return [foreleft, foreright, hfore, hindright]


@lru_cache(None)
def low(name):
    return lower_plates(name)


def replace_lower(base, t):
    if t <= 0:
        return base
    if t >= 1:
        return base
    k0, k1 = "Turn02", "Turn03"
    a, b = LM[k0], LM[k1]
    aa, bb = low(k0), low(k1)
    acc = np.zeros_like(pm(R[k0]))
    for j in (2, 3, 0, 1):
        z = j + 15
        u = foot_progress(j, t)
        timing = smooth(u)
        p = (1 - timing) * a[z] + timing * b[z]
        p[1] -= min(12, 1.4 + abs(a[z, 0] - b[z, 0]) * 0.16) * np.sin(np.pi * u) ** 2
        join = smooth((Y - 303) / 86)
        dx0 = (p[0] - a[z, 0]) * join + (b[z, 0] - a[z, 0]) * t * (1 - join)
        dx1 = (p[0] - b[z, 0]) * join + (a[z, 0] - b[z, 0]) * (1 - t) * (1 - join)
        dy0 = (p[1] - a[z, 1]) * join
        dy1 = (p[1] - b[z, 1]) * join
        left = sample(aa[j], X - dx0, Y - dy0)
        right = sample(bb[j], X - dx1, Y - dy1)
        # Signed silhouettes avoid the faint duplicate edge of a dissolving toe.
        la = left[:, :, 3]
        ra = right[:, :, 3]
        mo = (1 - t) * la + t * ra
        da = cv2.distanceTransform(
            (la > 0.5).astype("u1"), cv2.DIST_L2, 3
        ) - cv2.distanceTransform((la <= 0.5).astype("u1"), cv2.DIST_L2, 3)
        db = cv2.distanceTransform(
            (ra > 0.5).astype("u1"), cv2.DIST_L2, 3
        ) - cv2.distanceTransform((ra <= 0.5).astype("u1"), cv2.DIST_L2, 3)
        strength = smooth((np.abs(la - ra) - 0.1) / 0.65)
        op = (mo + (np.clip(0.5 + (1 - t) * da + t * db, 0, 1) - mo) * strength)[
            :, :, None
        ]
        rgb = (left[:, :, :3] * (1 - t) + right[:, :, :3] * t) / np.maximum(
            mo[:, :, None], 0.0001
        )
        acc = over(np.concatenate((rgb * op, op), axis=2), acc)
    mix = (smooth((Y - 326) / 31) * smooth(min(t, 1 - t) / 0.18))[:, :, None]
    return img(registered_mix(pm(base),acc,mix))


F3 = pm(R["Turn03"])
# The central foreground is the true, front-facing skull, chest and front paws.
# Its mirrored left outline supplies the occluded right guard-hair border underneath the original tail.
center = np.interp(
    Y,
    [0, 100, 150, 180, 227, 292, 354, 396, 440, 512],
    [228, 228, 228.5, 229.8, 228.4, 229.1, 229.1, 229.7, 230, 230],
).astype("f")
mirror = sample(F3, 2 * center - X, Y)
shape = np.where(X <= center, F3[:, :, 3], np.minimum(F3[:, :, 3], mirror[:, :, 3]))
f = F3.copy()
f[:, :, :3] *= (shape / np.maximum(0.001, F3[:, :, 3]))[:, :, None]
f[:, :, 3] = shape
# Slightly dilated frontal occlusion protects guard hairs on the inner forepaws.
rear = F3.copy()
rear *= smooth((X - center - 4) / 18)[:, :, None]
rear *= smooth((Y - 94) / 16)[:, :, None]


def centerpass(t):
    # t progresses from the genuine left-front view to its right-front reflection. The rear surface rotates behind the frontal coat.
    if t <= 0:
        return R["Turn03"]
    if t >= 1:
        return R["Mirror3"]
    right = t > 0.5
    v = (1 - t if right else t) * 2
    mid = 0.5 * (512 - 1)
    shift = (mid - center) * v
    fdx = shift.copy()
    fdy = np.zeros_like(X)
    for i, (cx, rad) in enumerate(((196, 35), (263, 34))):
        leg = smooth((Y - 306) / 78) * np.exp(-(((X - shift - cx) / rad) ** 4))
        u = foot_progress(i, v)
        progress = smooth(u)
        fdx += (mid - center) * (progress - v) * leg
        fdy -= 5 * np.sin(np.pi * u) ** 2 * leg
    front = sample(f, X - fdx, Y - fdy)
    # Central shading interpolates only across nearly symmetric frontal features; muzzle, eyes and ears are already projected in this central view.
    mirrorfront = sample(f, 2 * center - X + fdx, Y - fdy)
    blend = 0.5 * smooth((v - 0.20) / 0.80)
    # A frontal forepaw may overlap its reflected boundary by several pixels.
    # Move that boundary geometrically, keeping an opaque leg core on both sides.
    a, b = front[:, :, 3], mirrorfront[:, :, 3]
    def signed(alpha):
        m = (alpha > .5).astype("u1")
        return cv2.distanceTransform(m, cv2.DIST_L2, 5) - cv2.distanceTransform(1-m, cv2.DIST_L2, 5)
    weighted = front * (1 - blend) + mirrorfront * blend
    coverage = weighted[:, :, 3]
    contour = np.clip(.5 + (1-blend)*signed(a) + blend*signed(b),0,1)
    correction = smooth(np.abs(a-b)) * smooth(blend/.08)
    final_alpha = coverage + correction * (contour-coverage)
    front = np.dstack((weighted[:, :, :3]* (final_alpha/np.maximum(coverage,1e-6))[:, :, None],final_alpha))
    # Swing the back paw behind the frontal chest and planted forepaw. Even
    # the last visible strip remains opaque until the front geometry covers it.
    rear_dx = -54.5*v - 12.5*smooth((v-.33)/.67)*smooth((Y-290)/72)
    depth = np.clip((Y - 255) / 140, 0, 1)
    by = Y + depth * (6 * v) + 3 * np.sin(np.pi * v) ** 2 * depth
    back = sample(rear, X - shift - rear_dx, by)
    # The small exposed edge of the rear near paw moves under the *same* forepaw, then behind it.
    out = img(over(front, back))
    return out[:, ::-1].copy() if right else out


def build():
    keys = [
        ("Stand", 0),
        ("Turn01", 5),
        ("Turn02", 11),
        ("Turn03", 19),
        ("Mirror3", 33),
        ("Mirror2", 41),
        ("Turn04", 46),
        ("Turn05", 51),
        ("StandRight", 57),
    ]
    for (a, i), (b, j) in zip(keys, keys[1:]):
        fn = centerpass if (a, b) == ("Turn03", "Mirror3") else Pair(a, b).make
        for k in range(i, j + 1):
            t = (k - i) / (j - i)
            arr = fn(t)
            if (a, b) == ("Turn02", "Turn03"):
                arr = replace_lower(arr, t)
            if (a, b) == ("Mirror3", "Mirror2"):
                ref = np.array(Image.open(FOLDER / f"{19 - (k - i):05}.png"))
                arr = ref[:, ::-1].copy()
            Image.fromarray(arr).save(FOLDER / f"{k:05}.png", compress_level=2)
    report = {
        "clip": "turn",
        "fps": 24,
        "heading": "left_to_right",
        "entryPose": "stand_left",
        "exitPose": "stand_right",
        "frameIntervals": 57,
        "projectedTravel": [0.0] * 57,
        "totalTravelPx": 0,
        "travelUnits": "reference_canvas_pixels_positive_toward_heading",
        "loop": False,
        "sourceViews": [name for name, _ in keys],
        "keyFrames": dict(keys),
        "motion": "Full body pivot, both eyes tracked throughout; the tail and hind legs pass behind the genuine frontal skull and chest. End matches the reflected canonical gait neutral after the genuine independently drawn Turn05 right view.",
    }
    for name, rev in [
        ("turn", False),
        ("turn_to_right", False),
        ("turn_to_left", True),
    ]:
        d = OUT / name
        d.mkdir(exist_ok=True)
        if name != "turn":
            for i in range(58):
                shutil.copyfile(
                    FOLDER / f"{57 - i if rev else i:05}.png", d / f"{i:05}.png"
                )
        m = dict(report, clip=name)
        m["heading"] = "right_to_left" if rev else "left_to_right"
        if rev:
            m["entryPose"], m["exitPose"] = m["exitPose"], m["entryPose"]
        (d / "metadata.json").write_text(json.dumps(m, indent=1))
    out = Image.new("RGB", (8 * 174, 4 * 174), (117, 134, 143))
    for j, i in enumerate(range(0, 58, 2)):
        a = Image.open(FOLDER / f"{i:05}.png")
        a.thumbnail((174, 174))
        out.paste(a, ((j % 8) * 174, (j // 8) * 174), a)
    out.save(P / "turn-review.jpg", quality=95)


def verify():
    keys = [
        ("Stand", 0),
        ("Turn01", 5),
        ("Turn02", 11),
        ("Turn03", 19),
        ("Mirror3", 33),
        ("Mirror2", 41),
        ("Turn04", 46),
        ("Turn05", 51),
        ("StandRight", 57),
    ]
    nose = []
    detached = []
    exact = {}
    for na, t in keys:
        arr = np.asarray(Image.open(FOLDER / f"{t:05}.png"))
        value = bool(np.array_equal(arr, R[na]))
        assert value, (na, t)
        exact[na] = value
    for (a, i), (b, j) in zip(keys, keys[1:]):
        for frame in range(i, j + (j == 57)):
            u = (frame - i) / (j - i)
            nx, ny = (1 - u) * LM[a][2] + u * LM[b][2]
            im = np.asarray(Image.open(FOLDER / f"{frame:05}.png"))
            dark = (im[:, :, :3].mean(2) < 105) & (im[:, :, 3] > 127)
            n, lab, bb, centers = cv2.connectedComponentsWithStats(dark.astype("u1"))
            near = [
                (
                    float(np.hypot(cx - nx, cy - ny)),
                    int(stat[4]),
                    [float(cx), float(cy)],
                )
                for stat, (cx, cy) in zip(bb[1:], centers[1:])
                if stat[4] > 45 and abs(cx - nx) < 16 and abs(cy - ny) < 10
            ]
            assert len(near) == 1, (frame, near)
            nose.append(near[0][2])
            n, lab, bb, _ = cv2.connectedComponentsWithStats(
                (im[:, :, 3] > 127).astype("u1")
            )
            areas = bb[1:, 4]
            detached.append(int(areas.sum() - areas.max()))
    step = np.linalg.norm(np.diff(nose, axis=0), axis=1)
    report = {
        "frames": 58,
        "fps": 24,
        "exactSourceAnchorFrames": exact,
        "maxNoseCentroidDisplacementPx": round(float(step.max()), 3),
        "framesWithOneOpaqueDarkNoseAtRegisteredLocation": len(nose),
        "maxDetachedOpaquePixels": max(detached),
        "noseCenterByFramePx": np.round(nose, 2).tolist(),
    }
    (P / "turn-qa.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    build()
    verify()
