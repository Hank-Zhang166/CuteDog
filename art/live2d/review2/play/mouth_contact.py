"""Paint the mouth at the registered Cubism nose, eye and original mouth corner.

The mouth reference contains open/retracted tongue, closed lips, and a real
bite. The red pixels of the last drawing only define lip occlusion: the ball
shown in the animation always comes from its own original textured sprite.
"""

from functools import lru_cache
from pathlib import Path
import cv2
import numpy as np
from PIL import Image
from scipy.interpolate import PchipInterpolator

CONTACT_RANGES = {"play_ball": (62, 98), "treat": (47, 104)}
XS = [26, 40, 51, 60, 70, 80, 95, 108, 120, 132, 143, 156, 175]
TOP = np.array(
    [
        [194, 201, 207, 210, 212, 213, 212, 209, 206, 202, 198, 198, 197],
        [194, 201, 207, 210, 212, 213, 214, 212, 209, 205, 201, 199, 197],
        [194, 201, 204, 205, 206, 208, 210, 212, 213, 211, 207, 201, 198],
    ],
    float,
)
LOW = np.array(
    [
        [197, 203, 208, 235, 238, 237, 232, 226, 219, 210, 201, 200, 199],
        [197, 203, 208, 212, 214, 215, 216, 214, 211, 207, 203, 201, 199],
        [197, 203, 229, 246, 252, 254, 251, 242, 229, 216, 209, 203, 200],
    ],
    float,
)


@lru_cache(None)
def spline(points):
    x, y = zip(*points)
    return PchipInterpolator(x, y)


def curve(f, points):
    return float(spline(tuple(points))(np.clip(f, points[0][0], points[-1][0])))


def ease(t):
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


def pm(im):
    a = np.asarray(im, dtype="f") / 255
    a[:, :, :3] *= a[:, :, 3:]
    return a


def straight(a):
    rgb = a[:, :, :3] / np.maximum(a[:, :, 3:], 1e-7)
    return Image.fromarray(
        np.rint(np.clip(np.dstack((rgb, a[:, :, 3])), 0, 1) * 255).astype("u1")
    )


@lru_cache(None)
def references():
    img = Image.open(Path(__file__).with_name("muzzle-reference.png")).convert("RGBA")
    w, h = img.size
    cell = w // 3
    result = []
    for k in range(3):
        result.append(pm(img.crop((k * cell, 0, (k + 1) * cell, h))))
    painted = result[2]
    rgb = painted[:, :, :3] / np.maximum(1e-6, painted[:, :, 3:])
    y, x = np.mgrid[:h, :cell] / (cell / 350)
    red = (
        (rgb[:, :, 0] - rgb[:, :, 1] > 0.19)
        & (rgb[:, :, 0] - rgb[:, :, 2] > 0.23)
        & (x > 49)
        & (x < 112)
        & (y > 198)
        & (y < 255)
    )
    _, lab, stats, _ = cv2.connectedComponentsWithStats(red.astype("u1"))
    mask = (lab == (1 + np.argmax(stats[1:, 4]))).astype("u1")
    mask = cv2.GaussianBlur(
        cv2.dilate(mask.astype("f"), np.ones((3, 3), "u1")), (3, 3), 0.75
    )
    return result, mask, cell / 350


def active(name, f):
    return (
        name in CONTACT_RANGES
        and CONTACT_RANGES[name][0] <= f <= CONTACT_RANGES[name][1]
    )


def control(name, f):
    if name == "play_ball":
        activation = curve(f, [(61, 0), (64, 1), (95, 1), (99, 0)])
        bite = curve(f, [(62, 0), (65, 0.24), (68, 1), (90, 1), (93, 0.65), (96, 0)])
        aperture = curve(
            f, [(61, 1), (65, 1.42), (68, 1), (90, 1), (94, 1.46), (98, 1)]
        )
        return activation, bite, 0.0, aperture
    activation = curve(f, [(46, 0), (51, 1), (100, 1), (105, 0)])
    closed = curve(
        f,
        [
            (47, 0),
            (54, 0),
            (57, 0.07),
            (62, 0.57),
            (66, 0.76),
            (70, 0.95),
            (73, 0.58),
            (77, 1),
            (81, 0.56),
            (85, 1),
            (89, 0.69),
            (94, 1),
            (100, 1),
            (105, 0),
        ],
    )
    gape = curve(f, [(46, 1), (54, 1.45), (58, 1.22), (65, 1), (104, 1)])
    return activation, 0.0, closed, gape


def geometry(name, row, pts):
    f = row["frame"]
    launch = (
        curve(f, [(45, 0), (46, 0.17), (51, 1), (56, 1), (65, 0)])
        if name == "treat"
        else 0.0
    )
    nose = pts["nose"] + [6.5, 2.3]
    eye = pts["eye"]
    corner = pts["cheek"] + [11.8 - 5.6 * launch, -4.4 + 3.6 * launch]
    # This affine registration uses three actually evaluated head vertices;
    # neither the rigid body origin nor the camera controls the grip.
    src = np.float32([[52, 180], [133, 147], [141, 201]])
    dst = np.float32([nose, eye, corner])
    return cv2.getAffineTransform(src, dst)


def stage_contact(name, row, dog, sprite, other_props):
    f = row["frame"]
    root = np.array(row["dog"]["rootOffset"])
    pts = {k: np.array(v) - root for k, v in row["dog"]["markers"].items()}
    mat = geometry(name, row, pts)
    inv = cv2.invertAffineTransform(mat)
    x0 = max(0, int(pts["lip"][0]) - 70)
    y0 = max(0, int(pts["lip"][1]) - 43)
    x1 = min(512, x0 + 158)
    y1 = min(512, y0 + 113)
    yy, xx = np.mgrid[y0:y1, x0:x1].astype("f")
    u = inv[0, 0] * xx + inv[0, 1] * yy + inv[0, 2]
    v = inv[1, 0] * xx + inv[1, 1] * yy + inv[1, 2]
    act, bite, closed, gape = control(name, f)
    weights = np.array([1 - bite - closed, closed, bite])
    tops = np.stack([np.interp(u, XS, ar) for ar in TOP])
    lows = np.stack([np.interp(u, XS, ar) for ar in LOW])
    top = np.einsum("i,ijk->jk", weights, tops)
    lower = top + np.einsum("i,ijk->jk", weights, lows - tops) * gape
    xm = mat[0, 0] * 79 + mat[0, 1] * 225 + mat[0, 2]
    ym = mat[1, 0] * 79 + mat[1, 1] * 225 + mat[1, 2]
    sources, red, scale = references()
    surfaces = []
    ymaps = []
    for i, src in enumerate(sources):
        ymap = np.where(
            v < top,
            v + (tops[i] - top) * ease((v - 183) / 20),
            np.where(
                v < lower,
                tops[i]
                + (v - top) * (lows[i] - tops[i]) / np.maximum(lower - top, 0.9),
                v + (lows[i] - lower) * (1 - ease((v - lower) / 50)),
            ),
        )
        ymaps.append(ymap)
        surfaces.append(rem(src, u * scale, ymap * scale))
    redmask = rem(red, u * scale, ymaps[2] * scale)
    # Remove every painted red pixel before compositing the independent toy.
    # The registered open photograph supplies the far cavity underneath it.
    surfaces[2] = (
        surfaces[2] * (1 - redmask[:, :, None]) + surfaces[0] * redmask[:, :, None]
    )
    tex = sum(w * a for w, a in zip(weights, surfaces))
    # The lower chin silhouette, the retracting tongue and the background
    # directly under it are replaced together, including transparent pixels.
    region = (
        ease((v - 182) / 19)
        * (1 - ease((v - 261) / 30))
        * (1 - ease((u - 135) / 36))
        * ease((u + 10) / 34)
    )
    # A short moving feather withdraws the original tongue from tip to root.
    adopt = region * ease((act * 145 - (u - 30)) / 16)
    if act == 0:
        adopt = np.zeros_like(region)
    base = pm(dog.crop((x0, y0, x1, y1)))
    # In a three-quarter jump the far cheek projects behind the near muzzle.
    # A profile donor has transparency there; keep the actual far cheek. Only
    # the original protruding lip/tongue can be withdrawn into that opening.
    source_rgb = base[:, :, :3] / np.maximum(1e-6, base[:, :, 3:])
    r, g, b = source_rgb.transpose(2, 0, 1)
    former_mouth = ((r - g > 0.078) & (r - b > 0.099) & (g < 0.73)) | (
        (r < 0.36) & (g < 0.34)
    )
    former_mouth &= (
        (v > 195) & (v < 260) & (u > 35) & (u < 151) & (base[:, :, 3] > 0.035)
    )
    fg_distance = cv2.distanceTransform(
        (base[:, :, 3] > 0.58).astype("u1"), cv2.DIST_L2, 3
    )
    retained = base.copy()
    if np.any(former_mouth):
        cleaned = (
            cv2.inpaint(
                np.uint8(np.rint(source_rgb * 255)),
                former_mouth.astype("u1"),
                4,
                cv2.INPAINT_TELEA,
            ).astype("f")
            / 255
        )
        keep = ease((fg_distance - 2.0) / 5) * former_mouth + (~former_mouth)
        retained[:, :, 3] *= keep
        retained[:, :, :3] = cleaned * retained[:, :, 3:]
    tex = tex + (1 - tex[:, :, 3:]) * retained
    surface = base * (1 - adopt[:, :, None]) + tex * adopt[:, :, None]
    newdog = dog.copy()
    newdog.paste(straight(surface), (x0, y0))
    canvas = other_props.copy()
    offset = tuple(map(round, root))
    canvas.alpha_composite(newdog, offset)
    propid = "ball" if name == "play_ball" else "biscuit"
    ob = next((p for p in row["props"] if p["id"] == propid), None)
    stats = dict(
        frame=f,
        aperturePx=round(
            float(
                np.interp(80, XS, (weights @ (LOW - TOP)))
                * gape
                * np.linalg.norm(mat[:, 1])
            ),
            2,
        ),
        contact=round(bite, 3),
        close=round(closed, 3),
        objectPixels=0,
        visibleObjectPixels=0,
        nearUpperCover=0,
        nearLowerCover=0,
    )
    if ob is None or (name == "treat" and f >= 72):
        return canvas, stats
    center = np.array(ob["center"]) - root
    ang = ob["clockwiseDegrees"]
    if name == "play_ball":
        lock = curve(f, [(61, 0), (65, 0.35), (68, 1), (90, 1), (93, 0.82), (98, 0)])
        target = np.array([xm, ym])
        center = center * (1 - lock) + target * lock
        # Sample the original toy at the projected grip diameter. The visible
        # sphere is compressed slightly by the muzzle in this viewing angle.
        ratio = (1 - lock) + lock * np.clip(
            59 * np.linalg.norm(mat[:, 0]) / 37, 0.83, 1.04
        )
    else:
        lock = curve(f, [(46, 0), (52, 0), (56, 0.88), (59, 1), (100, 1)])
        swallow = curve(
            f, [(46, 0), (56, 0), (61, 0.18), (66, 0.70), (72, 1), (100, 1)]
        )
        cx = 67 + 56 * swallow
        cy = float(
            np.interp(cx, XS, weights @ TOP)
            + (np.interp(cx, XS, weights @ (LOW - TOP))) * gape * 0.53
        )
        target = mat[:, :2] @ np.array([cx, cy]) + mat[:, 2]
        center = center * (1 - lock) + target * lock
        ratio = 1
        ang = (1 - lock) * ang + lock * (183 + 7 * swallow)
    layer = Image.new("RGBA", other_props.size)
    if ratio != 1:
        sprite = sprite.resize(
            (round(sprite.width * ratio), round(sprite.height * ratio)),
            Image.Resampling.LANCZOS,
        )
    p = sprite.rotate(-ang, Image.Resampling.BICUBIC, expand=True)
    layer.alpha_composite(
        p,
        (
            round(center[0] + root[0] - p.width / 2),
            round(center[1] + root[1] - p.height / 2),
        ),
    )
    box = (x0 + offset[0], y0 + offset[1], x1 + offset[0], y1 + offset[1])
    obpm = pm(layer.crop(box))
    om = obpm[:, :, 3] > 0.5
    upper = ease((top - v + 0.85) / 2)
    bottom = ease((v - lower + 0.8) / 2)
    deep = ease((u - 117) / 8)
    if name == "treat" and f >= 56:
        inward = curve(f, [(56, 0), (61, 0.10), (67, 0.69), (72, 1)])
        deep = np.maximum(deep, ease((u - (123 - 82 * inward)) / 5))
    cover = surface[:, :, 3] * np.maximum(deep, np.maximum(upper, bottom))
    if name == "treat":
        # The tossed biscuit passes in front of the far cheek. The near side
        # only intercepts its bitten end after the leading edge passes the lips.
        approach = curve(f, [(46, 0), (54, 0), (57, 1), (104, 1)])
        cover *= approach * ease((u - 51) / 10)
    if name == "play_ball":
        # The measured red opening in the bite photograph is the lip border.
        # Its top and bottom preserve the original fur/teeth in front of the ball.
        solid = rem(sources[2][:, :, 3], u * scale, ymaps[2] * scale) * (1 - redmask)
        cover = bite * solid + (1 - bite) * cover
        if f < 65 or f > 94:
            cover = act * cover + (1 - act) * surface[:, :, 3]
    visibility = 1 - cover
    obpm *= visibility[:, :, None]
    layer.paste(straight(obpm), box[:2])
    canvas.alpha_composite(layer)
    stats.update(
        projectedCenter=np.round(center + root, 2).tolist(),
        objectPixels=int(om.sum()),
        visibleObjectPixels=int((obpm[:, :, 3] > 0.5).sum()),
        nearUpperCover=int((om & (cover > 0.65) & (v < 0.5 * (top + lower))).sum()),
        nearLowerCover=int((om & (cover > 0.65) & (v >= 0.5 * (top + lower))).sum()),
    )
    return canvas, stats
