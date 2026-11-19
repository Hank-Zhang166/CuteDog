"""Continuous side-view canine locomotion using the original stationary coat drawing."""

from pathlib import Path
from functools import lru_cache
import numpy as np
from PIL import Image
from scipy.interpolate import PchipInterpolator
import math, json, argparse, os
import moderngl

HERE = Path(__file__).resolve().parent
ROOT = Path(os.environ.get("CUTEDOG_GAIT_OUTPUT", HERE)).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
SOURCE = next(
    v
    for v in (
        HERE.parents[1] / "poses/Stand.png",
        HERE.parents[1] / "live2d/poses/Stand.png",
    )
    if v.is_file()
)
SIZE = 512
FPS = 24


def smooth(lo, hi, x):
    t = np.clip((x - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def unit(t):
    return smooth(0, 1, t)


def B(t):
    return math.sin(math.pi * t) ** 2


def path(y, points):
    return np.interp(y, *np.asarray(points).T)


def curve(y, points):
    a = np.asarray(points)
    return PchipInterpolator(a[:, 0], a[:, 1])(np.clip(y, a[0, 0], a[-1, 0]))


def rgba_layer(a, mask):
    out = a.copy()
    out[:, :, 3] = np.rint(np.clip(mask, 0, 1) * a[:, :, 3]).astype("u1")
    return out


def layers():
    a = np.array(Image.open(SOURCE).convert("RGBA"))
    y, x = np.mgrid[:512, :512]
    # Trace only occlusion boundaries inside the reference. All coat/face markings and visible paws come from the same drawing.
    fedge = curve(
        y,
        [
            (0, -10),
            (275, 99),
            (287, 123),
            (297, 140),
            (305, 151),
            (313, 169),
            (322, 180),
            (339, 185),
            (358, 189),
            (377, 190),
            (394, 187),
            (399, 184),
            (404, 177),
            (410, 173),
            (418, 170),
            (428, 172),
        ],
    )
    redge = curve(
        y,
        [
            (0, 260),
            (304, 286),
            (311, 305),
            (319, 329),
            (332, 344),
            (345, 355),
            (357, 366),
            (370, 377),
            (385, 380),
            (396, 378),
            (404, 372),
            (411, 368),
            (418, 367),
            (426, 371),
        ],
    )
    belly = curve(
        x,
        [
            (0, 325),
            (246, 325),
            (264, 320),
            (278, 317),
            (291, 315),
            (306, 314),
            (318, 316),
            (332, 321),
            (348, 335),
            (365, 353),
            (512, 370),
        ],
    )
    keep_fore = smooth(-1.65, 1.65, x - fedge)
    keep_hind = np.maximum.reduce(
        [
            smooth(-1.7, 1.7, x - redge),
            1 - smooth(-1.6, 1.6, y - belly),
            1 - smooth(261, 274, x),
        ]
    )
    coat = rgba_layer(a, keep_fore * keep_hind)
    # Far leg silhouettes extend several pixels beneath the opaque shoulder/haunch. Small feather distances retain guard hairs.
    fl = curve(
        y,
        [
            (0, 149),
            (242, 142),
            (261, 128),
            (282, 126),
            (299, 134),
            (319, 141),
            (337, 144),
            (377, 146),
            (392, 143),
            (400, 129),
            (409, 122),
            (429, 123),
        ],
    )
    fr = curve(
        y,
        [
            (0, 181),
            (243, 192),
            (269, 197),
            (295, 195),
            (320, 191),
            (352, 192),
            (374, 188),
            (391, 185),
            (398, 185),
            (405, 181),
            (412, 180),
            (417, 176),
            (421, 171),
            (426, 157),
        ],
    )
    # Reconstruct the right-hand edge of the far toes where a nearer foot concealed them.
    fbottom = curve(
        x,
        [
            (0, 405),
            (123, 409),
            (127, 415),
            (135, 420),
            (145, 422),
            (157, 423),
            (168, 421),
            (176, 417),
            (180, 413),
            (187, 404),
            (196, 392),
            (511, 391),
        ],
    )
    fbot = np.maximum(1 - smooth(390, 399, y), 1 - smooth(-0.65, 0.65, y - fbottom))
    far_f = rgba_layer(
        a,
        smooth(239, 259, y)
        * smooth(-1.3, 1.3, x - fl)
        * (1 - smooth(-1.35, 1.35, x - fr))
        * fbot,
    )
    hl = curve(
        y,
        [
            (0, 320),
            (238, 321),
            (265, 286),
            (284, 265),
            (296, 266),
            (305, 278),
            (314, 291),
            (324, 299),
            (332, 304),
            (348, 314),
            (374, 326),
            (390, 317),
            (403, 304),
            (429, 304),
        ],
    )
    # Keep the far thigh inside the anatomically visible shade, including at the back of its swing.
    # The broad bright tuft to the right of this edge belongs to the fixed near haunch.
    hr = curve(
        y,
        [
            (0, 361),
            (241, 361),
            (269, 358),
            (292, 349),
            (314, 343),
            (330, 346),
            (345, 355),
            (356, 364),
            (370, 368),
            (388, 364),
            (402, 359),
            (415, 350),
            (424, 336),
        ],
    )
    # Extend the hidden edge of the far thigh with the adjacent registered coat.
    # The source's composite belly contour would otherwise reappear inside the
    # uncovered thigh when the belly rises away from this overlap.
    hidden = a.copy()
    for row in range(287, 344):
        near = int(hl[row, 0])
        center = int(hr[row, 0])
        solid = np.flatnonzero(a[row, near + 1 : center, 3] >= 240)
        if solid.size == 0:
            continue
        first = near + 1 + int(solid[0])
        for col in range(near - 2, first + 1):
            weight = a[row, col, 3] / 255.0
            reflected = min(center, first + first - col)
            hidden[row, col, :3] = np.rint(
                weight * a[row, col, :3] + (1 - weight) * a[row, reflected, :3]
            )
            hidden[row, col, 3] = 255
    far_h = rgba_layer(
        hidden,
        smooth(235, 262, y)
        * smooth(-1.4, 1.4, x - hl)
        * (1 - smooth(-1.4, 1.4, x - hr))
        * (1 - smooth(418, 423, y)),
    )
    # Distant limbs have their natural source shading; no per-frame opacity changes.
    return [far_h, far_f, coat], a


def z(x, y):
    return complex(x, y)


LEG = {
    # shoulder/hip, elbow/stifle, carpus/hock, sole center (including far-plane sole depth)
    "fn": (z(191, 267), z(222, 330), z(207, 391), z(195, 426)),
    "ff": (z(170, 263), z(184, 333), z(168, 390), z(151, 422)),
    "hn": (z(365, 260), z(345, 318), z(399, 365), z(389, 424)),
    "hf": (z(342, 260), z(315, 319), z(352, 363), z(334, 418)),
}
SPEC = {
    "walk": dict(
        N=24,
        D=78.0,
        d=0.64,
        lift=(15.0, 10.5),
        ph={"hn": 0.0, "fn": 0.235, "hf": 0.5, "ff": 0.735},
        centers=[192.0, 157.0, 381.0, 328.0],
    ),
    "trot": dict(
        N=16,
        D=88.0,
        d=0.57,
        lift=(18.0, 12.5),
        ph={"hn": 0.0, "ff": 0.015, "hf": 0.5, "fn": 0.515},
        centers=[192.0, 157.0, 381.0, 328.0],
    ),

}
NAMES = list(LEG)


def hermite(a, b, va, vb, t):
    return (
        (2 * t**3 - 3 * t * t + 1) * a
        + (t**3 - 2 * t * t + t) * va
        + (-2 * t**3 + 3 * t * t) * b
        + (t**3 - t * t) * vb
    )


def spline(t, keys):
    for (ta, a, va), (tb, b, vb) in zip(keys, keys[1:]):
        if t <= tb + 1e-9:
            d = tb - ta
            return hermite(a, b, va * d, vb * d, (t - ta) / d)
    return keys[-1][1]


def target(name, g, p):
    spec = SPEC[g]
    q = (p - spec["ph"][name]) % 1
    d = spec["d"]
    D = spec["D"]
    lift = spec["lift"][0 if name[0] == "f" else 1]
    h = D * d / 2
    cg = spec["centers"][NAMES.index(name)]
    ground = LEG[name][-1].imag
    if q < d:
        return z(cg - h + D * q, ground), True, 0, q
    u = (q - d) / (1 - d)
    v = D * (1 - d)
    # A short recovery arc connects the two locked support footprints at their actual velocities.
    early, late = (2.0, 2.3) if name[0] == "h" else (3.0, 3.1)
    x = spline(u, [(0, h, v), (0.19, h + early, 0), (0.80, -h - late, 0), (1, -h, v)])
    b = B(u)
    recovery = lift * b * (1 + 0.08 * math.sin(2 * math.pi * u))
    rot = (0.12 if name[0] == "f" else 0.075) * b * math.sin(math.pi * u + 0.40)
    return z(cg + x, ground - recovery), False, rot, q


def body_state(g, p):
    phase = 2 * math.pi * p
    # Diagonal loading twice per cycle. The trunk retains its painted length.
    bob = {"walk": 1.25, "trot": 1.65}[g]
    roll = {"walk": 0.0035, "trot": 0.0048}[g]
    head = {"walk": 0.006, "trot": 0.009}[g]
    tail = {"walk": 0.022, "trot": 0.030}[g]
    return dict(
        cx=0.50 * math.sin(2 * phase),
        cy=0.9 + bob * math.cos(2 * phase + 0.4),
        angle=roll * math.sin(2 * phase),
        arch=0.52 * math.sin(2 * phase - 0.3),
        head=head * math.sin(2 * phase - 0.5),
        tail=tail * math.sin(phase - 0.46),
    )


ZERO = {key: 0.0 for key in ("cx", "cy", "angle", "arch", "head", "tail")}


def neutral():
    return dict(
        b=ZERO.copy(),
        legs={k: (r[-1], True, 0.0, 0) for k, r in LEG.items()},
        speed=0.0,
    )


def sample(g, p):
    return dict(
        b=body_state(g, p),
        legs={k: target(k, g, p) for k in LEG},
        speed=SPEC[g]["D"] / SPEC[g]["N"],
    )


def mix(a, b, w):
    def lerp(x, y):
        return (1 - w) * x + w * y

    return dict(
        b={k: lerp(x, b["b"][k]) for k, x in a["b"].items()},
        legs={
            k: (
                lerp(a["legs"][k][0], b["legs"][k][0]),
                a["legs"][k][1] and b["legs"][k][1],
                lerp(a["legs"][k][2], b["legs"][k][2]),
                lerp(a["legs"][k][3], b["legs"][k][3]),
            )
            for k in LEG
        },
        speed=lerp(a["speed"], b["speed"]),
    )


def bodymap(P, b):
    T = (P - z(258, 265)) * np.exp(1j * b["angle"]) + z(258, 265) + z(b["cx"], b["cy"])
    mid = np.exp(-(((P.real - 273) / 94) ** 2))
    T += 1j * b["arch"] * mid
    head = (1 - smooth(174, 271, P.real)) * (
        1 - smooth(195, 288, P.imag + 0.25 * (P.real - 145))
    )
    T += head * (P - z(171, 218)) * (np.exp(1j * b["head"]) - 1)
    tail = smooth(247, 278, P.real) * (
        1 - smooth(195, 227, P.imag - 0.16 * (P.real - 335))
    )
    T += tail * (P - z(357, 225)) * (np.exp(1j * b["tail"]) - 1)
    return T


def solve_leg(name, rec, b):
    """Exact three-link hind/fore chain. A small scapular/hip translation supplies reach; no bone scales."""
    pad, contact, paw_angle, _ = rec
    hip, knee, hock, sole = LEG[name]
    dx = pad.real - sole.real
    root = bodymap(hip, b) + 0.10 * dx
    # During compact steps the metapodium and padded foot share a rigid rotation; the hock/carpus flexes against the next proximal link.
    distal = paw_angle
    C = pad - (sole - hock) * np.exp(1j * distal)
    u, l = abs(knee - hip), abs(hock - knee)
    direction = (C - root) / max(abs(C - root), 1e-8)
    required = abs(C - root)
    limit = u + l - 0.95
    if required > limit:
        root += (required - limit) * direction
    elif required < abs(u - l) + 0.95:
        root -= (abs(u - l) + 0.95 - required) * direction
    reach = abs(C - root)
    along = (u * u - l * l + reach * reach) / (2 * reach)
    height = math.sqrt(max(0.0, u * u - along * along))
    side = 1 if ((knee - hip) / ((hock - hip) / abs(hock - hip))).imag > 0 else -1
    mid = root + direction * (along + 1j * side * height)
    return np.asarray((root, mid, C, pad)), paw_angle


@lru_cache(None)
def leg_profile(name):
    """Smoothed edges of the reference fur establish the transverse measurement of each material row."""
    from scipy.ndimage import gaussian_filter1d

    images, _ = layers()
    plate = images[0 if name == "hf" else 1 if name == "ff" else 2]
    a = plate[:, :, 3].copy()
    if name == "hn":
        a[:, :264] = 0
    elif name == "fn":
        a[:, 271:] = 0
    y = np.arange(513, dtype=float)
    left = []
    right = []
    valid = []
    for row in range(250, 431):
        x = np.flatnonzero(a[row] > 104)
        if len(x) > 4:
            valid.append(row)
            left.append(x[0] - 0.4)
            right.append(x[-1] + 1.4)
    left = gaussian_filter1d(np.interp(y, valid, left), 2)
    right = gaussian_filter1d(np.interp(y, valid, right), 2)
    return (left + right) * 0.5, np.maximum(2.0, (right - left) * 0.5)


def deform_leg(P, name, rec, b):
    """Bend a constant-width, area-preserving fur ribbon around the solved limb and rigid painted paw."""
    from scipy.ndimage import gaussian_filter1d

    bind = np.asarray(LEG[name])
    j, paw_angle = solve_leg(name, rec, b)
    rotation = np.unwrap(np.r_[np.angle(np.diff(j) / np.diff(bind)), paw_angle])
    y0 = np.arange(513, dtype=float)
    theta = np.full(513, rotation[0])
    windows = (
        (bind[1].imag, 26.0),
        (bind[2].imag - 1, 21.0),
        (bind[3].imag - 36.0, 13.0),
    )
    for i, (center, half) in enumerate(windows, 1):
        w = smooth(center - half, center + half, y0)
        theta += w * (rotation[i] - theta)
    # Readable flex over the full width of a shaggy limb, without inverting its inner crease.
    lock = int(bind[2].imag - 3) if name[0] == "h" else int(bind[2].imag - 19)
    max_twist = 0.0068 if name[0] == "h" else 0.0075
    theta[lock:] = paw_angle
    for k in range(lock - 1, 248, -1):
        theta[k] = np.clip(theta[k], theta[k + 1] - max_twist, theta[k + 1] + max_twist)
    theta = gaussian_filter1d(theta, 1.5)
    theta[lock + 3 :] = paw_angle
    slope = np.gradient(theta)
    xmid, hw = leg_profile(name)
    bind_mid = xmid + 1j * y0
    # Integrate the centerline from the rigid sole: every arc segment keeps exactly its neutral length.
    edge = np.diff(bind_mid) * np.exp(0.5j * (theta[1:] + theta[:-1]))
    curve = np.r_[0j, np.cumsum(edge)]
    pivot = int(bind[-1].imag)
    curve += (
        j[-1] + (bind_mid[pivot] - bind[-1]) * np.exp(1j * paw_angle) - curve[pivot]
    )
    yi = P.imag
    xc = np.interp(yi, y0, xmid)
    radius = np.interp(yi, y0, hw)
    k = np.interp(yi, y0, slope)
    ang = np.interp(yi, y0, theta)
    u = P.real - xc
    active = np.clip(u, -radius, radius)
    # Normalize the integrated bend at both reference edges. The fur center follows its unscaled arc and the complete outline keeps its registered width.
    cmax = 2 * radius / (1 + np.sqrt(np.maximum(0.05, 1 + 2 * k * radius)))
    cmin = -2 * radius / (1 + np.sqrt(np.maximum(0.05, 1 - 2 * k * radius)))
    scale = 2 * radius / np.maximum(0.01, cmax - cmin)
    offset = 2 * active / (1 + np.sqrt(np.maximum(0.05, 1 + 2 * k * active)))
    offset += (u - active) / np.maximum(0.2, 1 + k * offset)
    offset *= scale
    mid = np.interp(yi, y0, curve.real) + 1j * np.interp(yi, y0, curve.imag)
    return mid + offset * np.exp(1j * ang)


def transforms(P, state):
    base = bodymap(P, state["b"])
    parts = {k: deform_leg(P, k, state["legs"][k], state["b"]) for k in LEG}
    y = P.imag
    lo = smooth(292, 344, y)
    fore = smooth(236, 339, y) * (1 - smooth(214 + 29 * lo, 258 + 23 * lo, P.real))
    hind = smooth(226, 341, y) * smooth(308 - 28 * lo, 359 - 35 * lo, P.real)
    # Below the belly the two near limbs have separate silhouettes in the connected coat.
    # Each entire cross-section then follows its own leg, including the inner hock.
    full = smooth(306, 337, y)
    branch = smooth(251, 283, P.real)
    fore = (1 - full) * fore + full * (1 - branch)
    hind = (1 - full) * hind + full * branch
    main = base + fore * (parts["fn"] - base) + hind * (parts["hn"] - base)
    far = [base + smooth(244, 305, y) * (parts[k] - base) for k in ("hf", "ff")]
    return [*far, main]


class Regularize:
    """Local/global 2-D as-rigid-as-possible relaxation on the connected source silhouette."""

    def __init__(self, plate, points, side):
        from scipy import sparse
        from scipy.sparse.linalg import factorized
        from scipy.ndimage import maximum_filter

        occupied = maximum_filter(plate[:, :, 3], size=17) > 12
        x = np.minimum(points.real.astype(int), 511)
        y = np.minimum(points.imag.astype(int), 511)
        mask = occupied[y, x]
        self.ids = np.where(mask)[0]
        p = points[self.ids]
        self.p = p
        ids = np.full(len(points), -1)
        ids[self.ids] = np.arange(len(self.ids))
        ids = ids.reshape(side, side)
        edges = []
        for first, second, w in [
            (ids[:, :-1], ids[:, 1:], 1),
            (ids[:-1, :], ids[1:, :], 1),
            (ids[:-1, :-1], ids[1:, 1:], 0.5),
            (ids[:-1, 1:], ids[1:, :-1], 0.5),
        ]:
            a, b = first.ravel(), second.ravel()
            ok = (a >= 0) & (b >= 0)
            edges.append(np.column_stack((a[ok], b[ok], np.ones(np.sum(ok)) * w)))
        e = np.concatenate(edges)
        self.a = e[:, 0].astype(int)
        self.b = e[:, 1].astype(int)
        self.w = e[:, 2]
        a, b, w = self.a, self.b, self.w
        cnt = len(p)
        # Toes and face are prescribed anchors. The painted texture in the free joints relaxes continuously around their poses.
        self.anchor = (
            0.013
            + 2.8 * (1 - smooth(229, 278, p.imag))
            + 3.5 * smooth(386, 413, p.imag)
            + 0.13 * (1 - smooth(291, 321, p.imag))
        )
        self.anchor += (
            0.43
            * (1 - smooth(27, 54, np.abs(p.real - 271)))
            * (1 - smooth(313, 330, p.imag))
        )
        deg = np.bincount(a, w, minlength=cnt) + np.bincount(b, w, minlength=cnt)
        L = sparse.coo_matrix(
            (
                np.r_[deg + self.anchor, -w, -w],
                (np.r_[np.arange(cnt), a, b], np.r_[np.arange(cnt), b, a]),
            ),
            shape=(cnt, cnt),
        )
        self.solve = factorized(L.tocsc())
        self.edge = p[a] - p[b]
        self.cnt = cnt

    def calc(self, target):
        q = target[self.ids]
        v = q.copy()
        a, b, w = self.a, self.b, self.w
        src = self.edge
        for it in range(5):
            rot = (v[a] - v[b]) * np.conjugate(src) * w
            norm = (
                np.bincount(a, rot.real, minlength=self.cnt)
                + np.bincount(b, rot.real, minlength=self.cnt)
                + 1j
                * (
                    np.bincount(a, rot.imag, minlength=self.cnt)
                    + np.bincount(b, rot.imag, minlength=self.cnt)
                )
            )
            R = norm / np.maximum(np.abs(norm), 1e-8)
            edge = 0.5 * w * (R[a] + R[b]) * src
            bx = (
                np.bincount(a, edge.real, minlength=self.cnt)
                - np.bincount(b, edge.real, minlength=self.cnt)
                + self.anchor * q.real
            )
            by = (
                np.bincount(a, edge.imag, minlength=self.cnt)
                - np.bincount(b, edge.imag, minlength=self.cnt)
                + self.anchor * q.imag
            )
            xy = self.solve(np.column_stack((bx, by)))
            v = xy[:, 0] + 1j * xy[:, 1]
        out = target.copy()
        toe = smooth(397, 414, self.p.imag)
        out[self.ids] = (1 - toe) * v + toe * q
        return out


class Renderer:
    def __init__(self):
        self.layers, self.original = layers()
        self.gl = moderngl.create_standalone_context()
        gl = self.gl
        self.fbo = gl.simple_framebuffer((SIZE, SIZE), components=4, dtype="f2")
        self.program = gl.program(
            vertex_shader="""#version 330
  in vec2 xy;in vec2 uv;out vec2 st;
  void main(){st=uv;gl_Position=vec4(xy/256.0-1.0,0,1);}
  """,
            fragment_shader="""#version 330
  in vec2 st;uniform sampler2D paint;out vec4 pixel;void main(){pixel=texture(paint,st);}
  """,
        )
        coords = np.arange(0, 513, 4)
        yy, xx = np.meshgrid(coords, coords, indexing="ij")
        self.P = (xx + 1j * yy).ravel()
        self.reg = [Regularize(plate, self.P, len(coords)) for plate in self.layers]
        uv = np.column_stack([xx.ravel(), yy.ravel()]).astype("f4")
        self.gpos = gl.buffer(reserve=uv.nbytes)
        index = np.arange(len(self.P), dtype="i4").reshape(xx.shape)
        a = index[:-1, :-1].ravel()
        b = index[:-1, 1:].ravel()
        c = index[1:, :-1].ravel()
        d = index[1:, 1:].ravel()
        idx = np.column_stack((a, b, c, b, d, c)).astype("i4")
        self.vao = gl.vertex_array(
            self.program,
            [(self.gpos, "2f", "xy"), (gl.buffer((uv / 512).tobytes()), "2f", "uv")],
            gl.buffer(idx.tobytes()),
        )
        self.tex = []
        for plate in self.layers:
            color = np.asarray(plate, dtype="f4") / 255
            color[:, :, :3] *= color[:, :, 3:]
            tex = gl.texture((512, 512), 4, color.astype("f2").tobytes(), dtype="f2")
            tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            tex.repeat_x = False
            tex.repeat_y = False
            self.tex.append(tex)
        self.gl.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA

    def geometry(self, state):
        result = []
        for i, (reg, target) in enumerate(zip(self.reg, transforms(self.P, state))):
            relaxed = reg.calc(target)
            # The deformable ruff can relax. The visible shank, ankle and claws follow their authored frame exactly.
            fixed = (
                smooth(299, 329, self.P.imag)
                if i in (0, 1)
                else smooth(308, 338, self.P.imag)
            )
            result.append(relaxed + (target - relaxed) * fixed)
        return result

    def render(self, state, exact=True):
        if exact and state.get("neutral"):
            return self.original.copy()
        xyz = self.geometry(state)
        self.fbo.use()
        self.fbo.clear(0, 0, 0, 0)
        self.gl.enable(moderngl.BLEND)
        for tex, new in zip(self.tex, xyz):
            tex.use(0)
            self.gpos.write(
                np.column_stack([new.real, new.imag]).astype("f4").tobytes()
            )
            self.vao.render()
        a = (
            np.frombuffer(self.fbo.read(components=4, dtype="f2"), dtype="f2")
            .reshape(SIZE, SIZE, 4)
            .astype("f4")
        )
        a[:, :, :3] /= np.maximum(a[:, :, 3:], 1 / 65535.0)
        a[a[:, :, 3] < 0.5 / 255.0] = 0
        return np.uint8(np.rint(np.clip(a, 0, 1) * 255))


def loop(g):
    n = SPEC[g]["N"]
    return [sample(g, i / n) for i in range(n + 1)], [SPEC[g]["D"] / n] * n


def change(a, b, n):
    out = []
    for i in range(n + 1):
        t = i / n
        w = float(unit(t))
        if a and b:
            phase = hermite(0.0, 1.0, n / SPEC[a]["N"], n / SPEC[b]["N"], t)
            A = sample(a, phase)
            B = sample(b, phase)
        else:
            A = sample(a, i / SPEC[a]["N"]) if a else neutral()
            B = sample(b, (i - n) / SPEC[b]["N"]) if b else neutral()
        state = mix(A, B, w)
        # When raised by even one active partner, a foot is swinging. Overlap on the ground remains fixed in world space.
        legs = state["legs"]
        for k in LEG:
            pos, _, angle, q = legs[k]
            legs[k] = (pos, LEG[k][-1].imag - pos.imag < 0.2, angle, q)
        out.append(state)
    if not a:
        out[0]["neutral"] = True
    if not b:
        out[-1]["neutral"] = True
    delta = [(out[i]["speed"] + out[i + 1]["speed"]) / 2 for i in range(n)]
    if a and b:
        phases = [
            hermite(0.0, 1.0, n / SPEC[a]["N"], n / SPEC[b]["N"], i / n)
            for i in range(n + 1)
        ]
        delta = [
            (phases[i + 1] - phases[i])
            * (
                (1 - unit((i + 0.5) / n)) * SPEC[a]["D"]
                + unit((i + 0.5) / n) * SPEC[b]["D"]
            )
            for i in range(n)
        ]
    if a == "walk" and b is None:
        # The near hind sole remains in support for this whole braking transition.
        # Its start and final neutral positions prescribe net travel exactly.
        required = LEG["hn"][-1].real - out[0]["legs"]["hn"][0].real
        weights = np.sin(np.pi * (np.arange(n) + 0.5) / n) ** 2
        delta = (
            np.asarray(delta) + (required - sum(delta)) * weights / weights.sum()
        ).tolist()
    stabilize(out, delta)
    return out, delta


def stabilize(frames, delta):
    total = np.r_[0, np.cumsum(delta)]
    n = len(total) - 1
    for k in LEG:
        raw = np.array([s["legs"][k][0].real for s in frames]) - total
        on = np.array([s["legs"][k][1] for s in frames])
        err = np.full(n + 1, np.nan)
        groups = np.split(
            np.where(on)[0], np.where(np.diff(np.where(on)[0]) > 1)[0] + 1
        )
        for idx in groups:
            if len(idx):
                anchor = (
                    raw[idx[0]]
                    if idx[0] == 0
                    else raw[idx[-1]]
                    if idx[-1] == n
                    else raw[idx].mean()
                )
                err[idx] = anchor - raw[idx]
        err[0] = 0.0
        err[-1] = 0.0
        frees = np.where(np.isnan(err))[0]
        parts = np.split(frees, np.where(np.diff(frees) > 1)[0] + 1)
        for idx in parts:
            if not len(idx):
                continue
            a = idx[0] - 1
            b = idx[-1] + 1
            # Cubic continuation of contact correction through swing.
            da = (err[a] - err[a - 1]) if a > 0 and not np.isnan(err[a - 1]) else 0.0
            db = (err[b + 1] - err[b]) if b < n and not np.isnan(err[b + 1]) else 0.0
            err[idx] = hermite(
                err[a], err[b], da * (b - a), db * (b - a), (idx - a) / (b - a)
            )
        for i, (s, dx) in enumerate(zip(frames, err)):
            p, c, angle, q = s["legs"][k]
            s["legs"][k] = (p + dx, c, angle, q)


def staged(n, D, cycles, kind):
    # Matched neutral endpoints; contact coordinates are authored in world space so support is stationary even during acceleration/braking.
    root = [D * float(unit(i / n)) for i in range(n + 1)]
    out = []
    for i in range(n + 1):
        t = i / n
        strength = math.sin(math.pi * t) ** 0.85
        g = "trot" if kind == "chase" else "walk"
        st = sample(g, t * (1.6 if kind == "chase" else 1.0))
        st["b"] = {k: v * strength for k, v in st["b"].items()}
        st["speed"] = 0
        legs = {}
        for k in LEG:
            keys = cycles[k]
            rest = LEG[k][-1]
            world = rest.real
            lift = ang = 0.0
            c = True
            for ix, (take, land) in enumerate(keys):
                if ix == len(keys) - 1:
                    nextworld = rest.real - D
                else:
                    next_take = keys[ix + 1][0]
                    nextworld = (
                        rest.real - D * (float(unit(land)) + float(unit(next_take))) / 2
                    )
                if t >= land:
                    world = nextworld
                    continue
                if t > take:
                    u = (t - take) / (land - take)
                    world += (nextworld - world) * float(unit(u))
                    disk = math.sin(math.pi * u) ** 2
                    height = (
                        (18.0 if k[0] == "f" else 12.0) if kind == "chase" else 12.0
                    )
                    lift = height * disk
                    ang = 0.12 * disk * math.sin(math.pi * u + 0.7)
                    c = False
                break
            legs[k] = (z(world + root[i], rest.imag - lift), c, ang, 0)
        st["legs"] = legs
        out.append(st)
    out[0]["neutral"] = out[-1]["neutral"] = True
    return out, np.diff(root).tolist()


def hop():
    n = 30
    flight0, flight1 = 9, 21
    distance = 100.0
    frames = []
    root = []
    for i in range(n + 1):
        t = i / n
        s = neutral()
        b = s["b"]
        b["arch"] = 2.5 * math.sin(math.pi * t) ** 2
        if i <= flight0:
            u = i / flight0
            b["cy"] = 9.5 * B(u) - 2.8 * float(unit(u))
            b["angle"] = 0.018 * B(u)
            r = 0
            lift = flex = 0
        elif i <= flight1:
            u = (i - flight0) / (flight1 - flight0)
            r = distance * float(unit(u))
            lift = 51 * 4 * u * (1 - u)
            flex = B(u)
            b["cy"] = -2.8 - 0.94 * lift
            b["angle"] = -0.043 * math.sin(2 * math.pi * u)
            b["tail"] = 0.11 * B(u)
            b["head"] = -0.03 * math.sin(2 * math.pi * u)
        else:
            u = (i - flight1) / (n - flight1)
            r = distance
            lift = flex = 0
            b["cy"] = -2.8 * (1 - float(unit(u))) + 12 * math.sin(math.pi * u) ** 2 * (
                1 - 0.33 * u
            )
            b["angle"] = -0.035 * B(u)
            b["head"] = 0.018 * B(u)
        for k, a in LEG.items():
            p = a[-1] + z(
                (13 if k[0] == "h" else -19) * flex,
                -lift - (17 if k[0] == "f" else 11) * flex,
            )
            s["legs"][k] = (p, lift < 0.05, 0.39 * flex, 0)
        frames.append(s)
        root.append(r)
    frames[0]["neutral"] = frames[-1]["neutral"] = True
    return frames, np.diff(root).tolist()


def recovery():
    n = 28
    s_all = []
    r = []
    for i in range(n + 1):
        t = i / n
        s = neutral()
        b = s["b"]
        spread = float(unit(min(1, t / 0.47)))
        recover = float(unit(max(0, (t - 0.47) / 0.53)))
        skid = spread * (1 - recover)
        stop = 0.52
        move = 62 * (1 - (1 - min(1, t / stop)) ** 2.55)
        r.append(move)
        b["cy"] = 10 * skid
        b["cx"] = -8 * skid
        b["angle"] = -0.058 * skid
        b["head"] = 0.040 * skid
        b["tail"] = 0.10 * skid
        for k, a in LEG.items():
            begin, end = (0.53, 0.75) if k in ("fn", "hf") else (0.74, 0.97)
            u = float(np.clip((t - begin) / (end - begin), 0, 1))
            dis = (
                (27 if k[0] == "f" else -22)
                * (1 if k[1] == "n" else 0.78)
                * spread
                * (1 - float(unit(u)))
            )
            lift = (13 if k in ("fn", "hf") else 10) * B(u)
            s["legs"][k] = (
                a[-1] + z(dis, -lift),
                (t == 0 or (t >= stop and (u == 0 or u == 1))),
                0.11 * B(u),
                0,
            )
        s_all.append(s)
    s_all[0]["neutral"] = s_all[-1]["neutral"] = True
    return s_all, np.diff(r).tolist()


def pause_action():
    n = 26
    frames = []
    for i in range(n + 1):
        t = i / n
        s = neutral()
        a = math.sin(math.pi * t) ** 2
        hold = float(unit(min(1, t / 0.30))) * (
            1 - float(unit(max(0, (t - 0.70) / 0.30)))
        )
        s["b"].update(
            cx=-2.6 * hold,
            cy=2.3 * a,
            angle=-0.011 * hold,
            arch=-0.9 * a,
            head=0.034 * hold,
            tail=-0.041 * a,
        )
        u = float(np.clip((t - 0.15) / 0.50, 0, 1))
        lift = B(u)
        s["legs"]["fn"] = (
            LEG["fn"][-1] + z(-4.5 * lift, -12 * lift),
            lift < 1e-6,
            0.16 * lift,
            0,
        )
        frames.append(s)
    frames[0]["neutral"] = frames[-1]["neutral"] = True
    return frames, [0.0] * n


def rebound_action():
    n = 34
    frames = []
    travel = []
    # A strong first lift damps into a smaller second rebound; the pads hold the floor during both compressions.
    for i in range(n + 1):
        s = neutral()
        b = s["b"]
        lift = 0.0
        fold = 0.0
        amount = 0.0
        cx = 0.0
        if i <= 5:
            u = i / 5
            amount = 9 * math.sin(math.pi * u / 2) ** 2
            cx = 0.0
        elif i <= 13:
            u = (i - 5) / 8
            lift = 27 * 4 * u * (1 - u)
            amount = 9 * (1 - u) ** 3
            fold = B(u)
            cx = 18 * float(unit(u))
        elif i <= 19:
            u = (i - 13) / 6
            amount = 10 * B(u)
            cx = 18.0
        elif i <= 25:
            u = (i - 19) / 6
            lift = 11 * 4 * u * (1 - u)
            fold = 0.48 * B(u)
            cx = 18 + 7 * float(unit(u))
        else:
            u = (i - 25) / 9
            amount = 4 * B(u)
            cx = 25.0
        b.update(
            cy=-lift + amount,
            angle=0.003 * amount - 0.025 * fold,
            head=-0.022 * fold,
            tail=0.068 * fold,
            arch=0.11 * amount,
        )
        for k, rt in LEG.items():
            s["legs"][k] = (
                rt[-1]
                + z(
                    (-7 if k[0] == "f" else 8) * fold,
                    -lift - (9 if k[0] == "f" else 6) * fold,
                ),
                lift < 0.01,
                0.23 * fold,
                0,
            )
        frames.append(s)
        travel.append(cx)
    frames[0]["neutral"] = frames[-1]["neutral"] = True
    return frames, np.diff(travel).tolist()


def landing():
    frames, dist = hop()
    # Native frame 16 is just past the apex. Reusing its exact geometry permits a seamless branch into descent and the contact compression.
    return frames[16:], dist[16:]


def clips():
    chase = {
        "hf": [(0.035, 0.185), (0.345, 0.490), (0.655, 0.800)],
        "fn": [(0.047, 0.197), (0.355, 0.504), (0.665, 0.811)],
        "hn": [(0.198, 0.342), (0.505, 0.652), (0.815, 0.960)],
        "ff": [(0.207, 0.343), (0.512, 0.653), (0.825, 0.972)],
    }
    reverse = {
        "hn": [(0.03, 0.46)],
        "ff": [(0.14, 0.52)],
        "hf": [(0.49, 0.88)],
        "fn": [(0.55, 0.97)],
    }
    return {
        "walk": loop("walk"),
        "trot": loop("trot"),
        "chase": staged(30, 150, chase, "chase"),
        "walk_start": change(None, "walk", 17),
        "walk_stop": change("walk", None, 17),
        "walk_to_trot": change("walk", "trot", 18),
        "trot_to_walk": change("trot", "walk", 18),
        "backstep": staged(22, -56, reverse, "back"),
        "slip_recover": recovery(),
        "forward_hop": hop(),
        "pause": pause_action(),
        "land": landing(),
        "rebound": rebound_action(),
    }


def run():
    cli = argparse.ArgumentParser()
    cli.add_argument("names", nargs="*")
    args = cli.parse_args()
    rig = Renderer()
    states = clips()
    out = ROOT / "frames"
    out.mkdir(exist_ok=True)
    front = Image.fromarray(rig.layers[-1])
    front.save(ROOT / "connected-coat.png")
    for i, a in enumerate(rig.layers[:2]):
        Image.fromarray(a).save(ROOT / f"far-{i}.png")
    for name, (records, dist) in states.items():
        if args.names and name not in args.names:
            continue
        folder = out / name
        folder.mkdir(exist_ok=True)
        for old in folder.glob("[0-9][0-9][0-9][0-9][0-9].png"):
            if int(old.stem) >= len(records):
                old.unlink()
        info = []
        root = 0.0
        prev = {}
        max_slip = 0.0
        air = []
        images = []
        for i, s in enumerate(records):
            a = rig.render(s)
            im = Image.fromarray(a)
            im.save(folder / f"{i:05d}.png", compress_level=2)
            if i % (max(1, (len(records) - 1) // 6)) == 0 and i < len(records) - 1:
                images.append(im.resize((205, 205), Image.Resampling.LANCZOS))
            pets = {}
            supported = 0
            for k, (p, c, angle, q) in s["legs"].items():
                pets[k] = dict(
                    x=round(p.real, 5),
                    y=round(p.imag, 5),
                    planted=bool(c),
                    worldX=round(p.real - root, 5),
                )
                if c:
                    supported += 1
                    if k in prev and prev[k][1]:
                        max_slip = max(max_slip, abs((p.real - root) - prev[k][0]))
                prev[k] = (p.real - root, c)
            if all(LEG[k][-1].imag - s["legs"][k][0].imag > 0.05 for k in LEG):
                air.append(i)
            info.append(dict(frame=i, travelPx=root, paws=pets))
            root += dist[i] if i < len(dist) else 0
        meta = dict(
            clip=name,
            fps=FPS,
            loop=name in SPEC,
            heading="left",
            frameIntervals=len(dist),
            projectedTravel=dist,
            travelUnits="reference_canvas_pixels_positive_toward_heading",
            totalTravelPx=sum(dist),
            maxPlantedSlipPx=max_slip,
            airFrames=air,
            landmarks=info,
        )
        (folder / "metadata.json").write_text(json.dumps(meta, indent=1))
        sheet = Image.new("RGB", (len(images) * 205, 207), (152, 166, 171))
        for idx, im in enumerate(images):
            sheet.paste(im, (idx * 205, 0), im)
        sheet.save(ROOT / (name + ".jpg"), quality=93)
        print(name, len(dist), round(sum(dist), 2), round(max_slip, 3), air, flush=True)


if __name__ == "__main__":
    run()
