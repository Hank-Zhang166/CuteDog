"""Registered inbetweens for the puppy's continuous lying, curl and wake paths."""

from pathlib import Path
import sys, os, json, shutil
from functools import lru_cache
import numpy as np
from PIL import Image
from scipy.interpolate import RBFInterpolator

P = Path(__file__).resolve().parent
sys.path.insert(
    0,
    str(
        Path(os.environ.get("CUTEDOG_REVIEW_PERFORM_OUT", P)).resolve().parent / "deps"
    ),
)
import cv2

Y, X = np.mgrid[:512, :512].astype("f")


def smooth(t):
    t = np.clip(t, 0, 1)
    return t * t * (3 - 2 * t)


def rem(a, x, y):
    return cv2.remap(
        a,
        x.astype("f"),
        y.astype("f"),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
    )


def premul(a):
    a = a.astype("f") / 255
    a[:, :, :3] *= a[:, :, 3:]
    return a


def straight(p):
    v = p.copy()
    v[:, :, :3] /= np.maximum(0.001, v[:, :, 3:])
    v[v[:, :, 3] < 0.5 / 255] = 0
    return np.rint(np.clip(v, 0, 1) * 255).astype("u1")


outdir = Path(os.environ.get("CUTEDOG_REVIEW_PERFORM_OUT", P)).resolve()
ref = outdir / "rest-repair-registered"
ref.mkdir(exist_ok=True, parents=True)


def prepare():
    im = np.asarray(Image.open(P / "rest-inbetweens.png").convert("RGBA"))
    a = im[:, :, 3]
    n, l, stats, cent = cv2.connectedComponentsWithStats((a > 70).astype("u1"))
    boxes = [(i, tuple(b[:4])) for i, b in enumerate(stats) if i and b[4] > 5000]
    boxes.sort(key=lambda v: v[1][0])
    rr = [
        ("CurlA", (67, 252, 440, 428), 0, -4),
        ("CurlB", (73, 246, 432, 428), 0, -6),
        ("CurlC", (77, 239, 427, 428), -21, 0),
    ]
    for (n, (x0, y0, x1, y1), hdx, hdy), (i, (x, y, w, h)) in zip(rr, boxes):
        patch = im[y - 4 : y + h + 4, x - 4 : x + w + 4]
        src = premul(patch)
        sx = (x1 - x0) / w
        sy = (y1 - y0) / h
        head = (1 - smooth((X - 198) / 107)) * smooth((Y - 259) / 70)
        xmap = (X - x0 - hdx * head) / sx + 4
        ymap = (Y - y0 - hdy * head) / sy + 4
        v = straight(rem(src, xmap, ymap))
        Image.fromarray(v).save(ref / (n + ".png"))
    im2 = np.asarray(Image.open(P / "lowering-roll-inbetweens.png").convert("RGBA"))
    _, _, stats2, _ = cv2.connectedComponentsWithStats((im2[:, :, 3] > 68).astype("u1"))
    top = sorted(
        [z for z in stats2[1:] if z[4] > 5000 and z[1] < im2.shape[0] // 2],
        key=lambda z: z[0],
    )
    for (name, (l, t, r, b)), box in zip(
        [("LowerA", (64, 207, 440, 428)), ("LowerB", (63, 252, 446, 428))], top
    ):
        x, y, w, h = box[:4]
        a = premul(im2[y - 4 : y + h + 4, x - 4 : x + w + 4])
        mx = 4 + (X - l) * w / (r - l)
        my = 4 + (Y - t) * h / (b - t)
        new = straight(rem(a, mx, my))
        Image.fromarray(new).save(ref / (name + ".png"))


@lru_cache(None)
def registered(n):
    f = (
        ref / (n + ".png")
        if n.startswith(("Curl", "Lower"))
        else outdir / "registered" / (n + ".png")
    )
    return np.array(Image.open(f).convert("RGBA"))


# Eyes and ear tips are indexed by the dog's unchanged anatomical side, including when its head rolls through profile.
# F arrays: left/right eyes, nose, left/right lip, chin, left/right ears, center crown, left/right cheeks, neck, chest/front limb, rear toes, croup, two tail landmarks, back.
LM = {
    "Lie": [
        (108, 236),
        (145.7, 242.2),
        (100.4, 259.5),
        (110, 278),
        (150, 270),
        (118, 292),
        (151, 173),
        (187, 174),
        (169, 195),
        (105, 232),
        (215, 252),
        (209, 302),
        (174, 363),
        (82, 413),
        (122, 420),
        (306, 414),
        (332, 419),
        (348, 343),
        (354, 231),
        (431, 321),
        (256, 301),
    ],
    "Rest": [
        (86.6, 358.9),
        (113.3, 351.3),
        (80.7, 388.3),
        (90, 397),
        (123, 383),
        (98, 400),
        (81, 289),
        (117, 266),
        (106, 300),
        (75, 349),
        (202, 343),
        (207, 323),
        (193, 389),
        (84, 414),
        (130, 417),
        (281, 404),
        (301, 416),
        (335, 322),
        (413, 337),
        (435, 395),
        (262, 268),
    ],
    "CurlA": [
        (114.7, 362.1),
        (162.6, 349.4),
        (142.7, 392.7),
        (116, 397),
        (183, 381),
        (146, 403),
        (83, 282),
        (148, 265),
        (125, 292),
        (74, 351),
        (221, 343),
        (225, 314),
        (206, 393),
        (97, 411),
        (142, 418),
        (263, 403),
        (290, 417),
        (334, 319),
        (411, 335),
        (428, 391),
        (264, 254),
    ],
    "CurlB": [
        (153, 357),
        (204.1, 352.4),
        (184.4, 389.7),
        (158, 397),
        (208, 385),
        (183, 402),
        (111, 289),
        (184, 278),
        (150, 293),
        (81, 354),
        (251, 349),
        (246, 310),
        (222, 390),
        (127, 407),
        (166, 416),
        (233, 401),
        (253, 414),
        (332, 309),
        (389, 355),
        (413, 393),
        (260, 248),
    ],
    "CurlC": [
        (163.5, 371),
        (200.4, 348.4),
        (215.5, 389.1),
        (189, 399),
        (224, 390),
        (201, 403),
        (88, 310),
        (168, 266),
        (133, 289),
        (77, 358),
        (239, 347),
        (244, 311),
        (218, 390),
        (139, 409),
        (176, 419),
        (227, 401),
        (242, 413),
        (330, 306),
        (367, 352),
        (415, 394),
        (261, 241),
    ],
    "Sleep": [
        (170.3, 370.6),
        (203.9, 347),
        (213, 390.4),
        (192, 399),
        (223, 391),
        (205, 405),
        (91, 314),
        (167, 257),
        (134, 289),
        (89, 360),
        (245, 344),
        (251, 312),
        (221, 389),
        (146, 410),
        (179, 419),
        (230, 398),
        (246, 414),
        (333, 311),
        (357, 359),
        (402, 392),
        (258, 237),
    ],
}
LM["LowerA"] = [
    (100.2, 295.6),
    (126.8, 294.9),
    (89.6, 325.1),
    (98, 340),
    (129.5, 331),
    (104, 344),
    (115, 216),
    (148, 210),
    (135, 233),
    (95, 295),
    (204, 303),
    (207, 324),
    (179, 372),
    (83, 410),
    (124, 418),
    (305, 411),
    (326, 417),
    (349, 342),
    (345, 237),
    (430, 325),
    (258, 302),
]
LM["LowerB"] = [
    (94.9, 346.2),
    (121.5, 343.7),
    (86.2, 378),
    (93, 391),
    (122.6, 383.5),
    (101, 394),
    (99, 265),
    (139, 254),
    (122, 278),
    (86, 337),
    (210, 340),
    (211, 319),
    (196, 388),
    (83, 413),
    (126, 417),
    (283, 403),
    (304, 417),
    (337, 311),
    (419, 335),
    (436, 394),
    (262, 267),
]
LM = {k: np.array(v, float) for k, v in LM.items()}
NOSE_SIZE = {
    "Lie": (18, 16),
    "Rest": (17, 15),
    "CurlA": (24, 19),
    "CurlB": (24, 16),
    "CurlC": (23, 17),
    "Sleep": (20, 20),
}
EYESIZE = {
    "Lie": [(4, 7), (18, 14)],
    "Rest": [(3, 7), (18, 14)],
    "CurlA": [(18, 8), (19, 13)],
    "CurlB": [(19, 4), (18, 6)],
    "CurlC": [(20, 3), (12, 5)],
    "Sleep": [(16, 3), (15, 4)],
}
NOSE_SIZE.update(LowerA=(18, 19), LowerB=(18, 18))
EYESIZE.update(LowerA=[(4, 8), (19, 14)], LowerB=[(4, 8), (18, 14)])
STATIC = np.array(
    [
        (-45, -45),
        (150, -45),
        (315, -45),
        (556, -45),
        (-45, 129),
        (557, 129),
        (-45, 299),
        (557, 299),
        (-45, 505),
        (552, 505),
        (155, 554),
        (335, 554),
    ],
    float,
)
Gy, Gx = np.mgrid[:519:6, :519:6]
GRID = np.stack((Gx.ravel(), Gy.ravel()), axis=1)
from scipy.interpolate import PchipInterpolator


def rol(M):
    vec = M[1] - M[0]
    return np.arctan2(vec[1], vec[0])


def basis(theta):
    return np.array([[np.cos(theta), np.sin(theta)], [-np.sin(theta), np.cos(theta)]])


def add_marks(name, M):
    q = list(M)
    ang = rol(M)
    rot = basis(ang)
    for i, (w, h) in enumerate(EYESIZE[name]):
        for u, v in [(-0.48, 0), (0.48, 0), (0, -0.45), (0, 0.45)]:
            q.append(M[i] + np.array((u * w, v * h)) @ rot)
    w, h = NOSE_SIZE[name]
    for u, v in [
        (-0.46, 0),
        (0.46, 0),
        (0, -0.46),
        (0, 0.46),
        (-0.28, -0.30),
        (0.28, -0.30),
    ]:
        q.append(M[2] + np.array((u * w, v * h)) @ rot)
    for i in (13, 14, 15, 16):
        q.append(M[i] + [-7, -3])
        q.append(M[i] + [7, -3])
        q.append(M[i] + [0, -10])
    return np.array(q)


AUG = {k: add_marks(k, m) for k, m in LM.items()}


def field(q, target):
    A = np.vstack((q, STATIC))
    v = np.vstack((target - q, np.zeros_like(STATIC)))
    rb = RBFInterpolator(A, v, kernel="thin_plate_spline", smoothing=2.2)
    f = rb(GRID).reshape(Gx.shape + (2,)).astype("f")
    return cv2.remap(f, X / 6, Y / 6, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def gray(z):
    return cv2.cvtColor(
        np.uint8(
            np.rint(
                255
                * np.clip(z[:, :, :3] + (1 - z[:, :, 3:]) * [0.45, 0.51, 0.54], 0, 1)
            )
        ),
        cv2.COLOR_RGB2GRAY,
    )


def distance(a):
    m = (a > 0.5).astype("u1")
    return cv2.distanceTransform(m, cv2.DIST_L2, 3) - cv2.distanceTransform(
        1 - m, cv2.DIST_L2, 3
    )


@lru_cache(None)
def cleansed(name):
    raw = registered(name)
    M = LM[name]
    angle = rol(M)
    ca, sa = np.cos(angle), np.sin(angle)
    mask = np.zeros((512, 512), "u1")
    for k in (0, 1, 2):
        x, y = M[k]
        w, h = EYESIZE[name][k] if k < 2 else NOSE_SIZE[name]
        u = (X - x) * ca + (Y - y) * sa
        v = -(X - x) * sa + (Y - y) * ca
        mask[(u / (w * 0.62 + 3)) ** 2 + (v / (h * 0.65 + 2.4)) ** 2 < 1] = 255
    rgb = cv2.inpaint(raw[:, :, :3], mask, 5, cv2.INPAINT_TELEA)
    return premul(np.dstack((rgb, raw[:, :, 3])))


def split_tail(a):
    # The rear plume recedes behind the rump. Preserve its original guard hairs and keep the actual dorsal contour in front.
    ridge = np.interp(
        X,
        [230, 250, 268, 285, 318, 349, 367, 388, 405, 430, 451],
        [292, 297, 305, 305, 299, 299, 302, 313, 334, 351, 366],
    )
    cover = smooth((X - 250) / 24) * (1 - smooth((Y - ridge + 2) / 4))
    lower = a * (1 - cover[:, :, None])
    plume = a * (smooth((X - 248) / 22) * (1 - smooth((Y - ridge - 8) / 8)))[:, :, None]
    return lower, plume


def orbit_plume(t):
    _, p = split_tail(premul(registered("LowerA")))
    u = float(smooth(t))
    angle = -1.34 * u
    scale = 1 - 0.46 * u
    px = 410 - 16 * u
    py = 348 - 8 * u
    dx = (X - px) / scale
    dy = (Y - py) / scale
    c, ss = np.cos(angle), np.sin(angle)
    return rem(p, 410 + dx * c + dy * ss, 348 - dx * ss + dy * c)


def features(base, na, nb, t, q):
    # Sample each dark facial feature from one bounded affine patch. Occluded far eyes and the nose cannot be repeated by an inverse body warp near a crossing forepaw.
    rot = rol(q)
    co, si = np.cos(rot), np.sin(rot)
    sources = [premul(registered(na)), premul(registered(nb))]
    for k in (0, 1, 2):
        cx, cy = q[k]
        U = (X - cx) * co + (Y - cy) * si
        V = -(X - cx) * si + (Y - cy) * co
        widths = np.array(
            [EYESIZE[n][k] if k < 2 else NOSE_SIZE[n] for n in (na, nb)], "f"
        )
        sw, sh = (1 - t) * widths[0] + t * widths[1]
        # A soft rim carries the original matching fur around the dark shape.
        m = (
            1
            - smooth(
                (
                    np.sqrt((U / (sw * 0.65 + 2)) ** 2 + (V / (sh * 0.75 + 2)) ** 2)
                    - 0.72
                )
                / 0.47
            )
        )[:, :, None]
        tex = 0
        for n, weight, orig, (w, h) in zip((na, nb), (1 - t, t), sources, widths):
            phi = rol(LM[n])
            c, s = np.cos(phi), np.sin(phi)
            dx = U * w / sw
            dy = V * h / sh
            x, y = LM[n][k]
            tex = tex + weight * rem(orig, x + dx * c - dy * s, y + dx * s + dy * c)
        base = base * (1 - m) + tex * m
    return base


class Pair:
    def __init__(self, na, nb):
        self.na, self.nb = na, nb
        self.a, self.b = premul(registered(na)), premul(registered(nb))
        self.pa, self.pb = AUG[na], AUG[nb]
        mid = 0.5 * (self.pa + self.pb)
        fa = field(mid, self.pa)
        fb = field(mid, self.pb)
        l = rem(self.a, X + fa[:, :, 0], Y + fa[:, :, 1])
        r = rem(self.b, X + fb[:, :, 0], Y + fb[:, :, 1])
        dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        dis.setFinestScale(0)
        self.flow = np.clip(
            (dis.calc(gray(l), gray(r), None) - dis.calc(gray(r), gray(l), None)) * 0.5,
            -15,
            15,
        )

    def make(self, t, q=None):
        if t < 0.00001:
            return registered(self.na)
        if t > 0.99999:
            return registered(self.nb)
        q = (1 - t) * self.pa + t * self.pb if q is None else q
        fa, fb = field(q, self.pa), field(q, self.pb)
        flow = rem(
            self.flow,
            X + (fa[:, :, 0] + fb[:, :, 0]) * 0.5,
            Y + (fa[:, :, 1] + fb[:, :, 1]) * 0.5,
        )
        # High-contrast eye and nose centers are explicitly registered; optical flow only corrects residual coat and contour detail.
        for k in (0, 1, 2):
            xx, yy = q[k]
            loc = np.exp(-(((X - xx) / 15) ** 4) - ((Y - yy) / 12) ** 4)
            flow *= 1 - 0.91 * loc[:, :, None]
        first = cleansed(self.na)
        is_tail = (self.na, self.nb) == ("LowerA", "LowerB")
        if is_tail:
            first, _ = split_tail(first)
        a = rem(
            first,
            X + fa[:, :, 0] - t * flow[:, :, 0],
            Y + fa[:, :, 1] - t * flow[:, :, 1],
        )
        b = rem(
            cleansed(self.nb),
            X + fb[:, :, 0] + (1 - t) * flow[:, :, 0],
            Y + fb[:, :, 1] + (1 - t) * flow[:, :, 1],
        )
        aa, bb = a[:, :, 3], b[:, :, 3]
        mix = (1 - t) * aa + t * bb
        d = (1 - t) * distance(aa) + t * distance(bb)
        s = smooth(min(t, 1 - t) / 0.08) * smooth((np.abs(aa - bb) - 0.10) / 0.67)
        cov = (mix + (np.clip(0.5 + d, 0, 1) - mix) * s)[:, :, None]
        rgb = ((1 - t) * a[:, :, :3] + t * b[:, :, :3]) / np.maximum(
            0.0001, mix[:, :, None]
        )
        result = features(
            np.concatenate([rgb * cov, cov], axis=2), self.na, self.nb, t, q
        )
        if is_tail:
            result = result + (1 - result[:, :, 3:]) * orbit_plume(t)
        return straight(result)


pair = lru_cache(None)(Pair)
NAMES = ["Rest", "CurlA", "CurlB", "CurlC", "Sleep"]
KNOT = np.array([0, 0.36, 0.65, 0.865, 1])
SHAPE = PchipInterpolator(KNOT, np.array([AUG[n] for n in NAMES]), axis=0)


def curl(t):
    if t <= 0:
        return registered("Rest")
    if t >= 1:
        return registered("Sleep")
    i = min(3, int(np.searchsorted(KNOT, t, side="right") - 1))
    u = (t - KNOT[i]) / (KNOT[i + 1] - KNOT[i])
    return pair(NAMES[i], NAMES[i + 1]).make(u, SHAPE(t))


LOW = ["Lie", "LowerA", "LowerB", "Rest"]
LOWK = np.array([0, 0.44, 0.83, 1])
LOWP = PchipInterpolator(LOWK, np.array([AUG[n] for n in LOW]), axis=0)


def lower(t):
    if t <= 0:
        return registered("Lie")
    if t >= 1:
        return registered("Rest")
    i = min(2, int(np.searchsorted(LOWK, t, side="right") - 1))
    u = (t - LOWK[i]) / (LOWK[i + 1] - LOWK[i])
    return pair(LOW[i], LOW[i + 1]).make(u, LOWP(t))


def phase(which, t):
    return curl(t) if which == "curl" else lower(t)


REFS = {
    "curl": [(0, 31, "curl", False)],
    "uncurl": [(0, 36, "curl", True)],
    "fall_asleep": [(0, 37, "curl", False)],
    "wake": [(14, 36, "curl", True), (36, 57, "lower", True)],
    "side_lie": [(0, 28, "lower", False)],
    "side_to_lie": [(0, 28, "lower", True)],
}


def generate(names):
    for n in names:
        folder = outdir / n
        folder.mkdir(exist_ok=True)
        done = 0
        for start, end, kind, reverse in REFS[n]:
            for i in range(start, end + 1):
                u = (i - start) / (end - start)
                v = float(smooth(1 - u if reverse else u))
                a = phase(kind, v)
                path = folder / f"{i:05}.webp"
                canonical_names = {"curl": ("Rest", "Sleep"), "lower": ("Lie", "Rest")}
                canon = (
                    canonical_names[kind][0 if v == 0 else 1]
                    if v == 0 or v == 1
                    else None
                )
                if canon in ("Rest", "Sleep", "Lie"):
                    idle = {
                        "Rest": "side_lie_idle",
                        "Sleep": "sleep",
                        "Lie": "lie_idle",
                    }[canon]
                    exact = outdir / idle / "00000.webp"
                    if exact.is_file():
                        shutil.copyfile(exact, path)
                    else:
                        Image.fromarray(straight(premul(a))).save(
                            path, lossless=True, exact=True, method=1
                        )
                else:
                    Image.fromarray(a).save(path, lossless=True, exact=True, method=1)
                done += 1
        print(n, done, flush=True)


def check():
    # Require exactly one nose-sized dark region through the full span from the initial side muzzle to the final closed-eye frontal muzzle.
    checked = 0
    report = {}
    for n, paths in REFS.items():
        track = []
        extra = []
        max_off = 0.0
        bad = []
        folder = outdir / n
        for f in sorted(folder.glob("*.webp")):
            i = int(f.stem)
            applicable = [z for z in paths if z[0] <= i <= z[1]]
            if applicable:
                start, end, kind, reverse = applicable[0]
                t = (i - start) / (end - start)
                v = smooth(1 - t if reverse else t)
                expect = (SHAPE(v) if kind == "curl" else LOWP(v))[2]
                is_curl = kind == "curl"
            else:
                expect = LM["Sleep"][2]
                is_curl = True
            a = np.array(Image.open(f).convert("RGBA"))
            m = (a[:, :, :3].mean(2) < 109) & (a[:, :, 3] > 127)
            _, _, stats, c = cv2.connectedComponentsWithStats(m.astype("u1"))
            nose = [
                cc
                for bb, cc in zip(stats[1:], c[1:])
                if bb[4] >= 60
                and 51 < cc[0] < 241
                and abs(cc[1] - expect[1]) < (12 if is_curl else 9)
            ]
            if len(nose) != 1 or np.linalg.norm(nose[0] - expect) > 7:
                bad.append(i)
            elif nose:
                max_off = max(max_off, float(np.linalg.norm(nose[0] - expect)))
                track.append(nose[0])
            _, _, stats, _ = cv2.connectedComponentsWithStats(
                (a[:, :, 3] > 128).astype("u1")
            )
            areas = stats[1:, 4]
            extra.append(int(areas.sum() - areas.max()))
            checked += 1
        ds = np.linalg.norm(np.diff(track, axis=0), axis=1)
        report[n] = {
            "frameSamples": len(list(folder.glob("*.webp"))),
            "noseExceptions": bad,
            "maxNoseCentroidStepPx": round(float(ds.max()), 3),
            "maxNoseDistanceToTargetPx": round(max_off, 3),
            "maxDetachedOpaquePixels": max(extra),
        }
    for n, segments in REFS.items():
        for lo, hi, k, reverse in segments:
            endpoints = ("Rest", "Sleep") if k == "curl" else ("Lie", "Rest")
            if reverse:
                endpoints = endpoints[::-1]
            for i, tag in zip((lo, hi), endpoints):
                target = outdir / n / f"{i:05}.webp"
                idle = {"Rest": "side_lie_idle", "Sleep": "sleep", "Lie": "lie_idle"}[
                    tag
                ]
                anchor = outdir / idle / "00000.webp"
                if anchor.is_file():
                    assert np.array_equal(
                        np.array(Image.open(target)), np.array(Image.open(anchor))
                    ), (n, i, tag)
    (outdir / "rest-repair-qa.json").write_text(
        json.dumps({"frameSamples": checked, "clips": report}, indent=1)
    )
    return report


def set_boundaries():
    for name, paths in REFS.items():
        for lo, hi, k, r in paths:
            ends = ("Rest", "Sleep") if k == "curl" else ("Lie", "Rest")
            for i, tag in zip((lo, hi), ends[::-1] if r else ends):
                well = {"Rest": "side_lie_idle", "Sleep": "sleep", "Lie": "lie_idle"}[
                    tag
                ]
                src = outdir / well / "00000.webp"
                if src.is_file():
                    shutil.copyfile(src, outdir / name / f"{i:05}.webp")


def sheet():
    out = Image.new("RGB", (7 * 198, 3 * 150), (102, 118, 126))
    for row, (name, last) in enumerate(
        [("curl", 31), ("side_lie", 28), ("side_to_lie", 28)]
    ):
        for j, t in enumerate(np.linspace(0, 1, 7)):
            a = Image.open(outdir / name / f"{round(t * last):05}.webp")
            a = a.crop((43, 152, 463, 439))
            a.thumbnail((198, 149))
            out.paste(a, (j * 198, row * 150 + 147 - a.height), a)
    out.save(outdir / "repaired-rest-review.jpg", quality=95)


if __name__ == "__main__":
    prepare()
    generate(sys.argv[1:] or REFS.keys())
    set_boundaries()
    r = check()
    sheet()
    if any(v["noseExceptions"] for v in r.values()):
        raise ValueError(r)
