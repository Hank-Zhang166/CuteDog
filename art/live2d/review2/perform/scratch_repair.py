"""One visible hind paw scratches the ipsilateral ear, using the approved seated drawing."""

from pathlib import Path
from functools import lru_cache
import argparse
import json
import os
import sys
import numpy as np
from PIL import Image
from scipy.interpolate import PchipInterpolator, RBFInterpolator

SRC = Path(__file__).resolve().parent
sys.path.insert(0, str(SRC.parent / "deps"))
import cv2
sys.path.insert(0, str(SRC.parent))
from foreground import registered_mix

Y, X = np.mgrid[:512, :512].astype("f")
GY, GX = np.mgrid[0:518:5, 0:518:5]
GRID = np.column_stack((GX.ravel(), GY.ravel()))
SEATED_REFERENCE = next(
    p
    for p in (
        SRC.parents[1] / "poses/Sit05.png",
        SRC.parents[1] / "live2d/poses/Sit05.png",
        SRC / "registered/Sit05.png",
    )
    if p.is_file()
)
NAMES = ("Sit", "Low", "Mid", "High")
ATLAS_CENTERS = (225, 632, 1042)
BODY_SHIFT = (0, 9, 10, 13)
THIGH = [
    [
        (280, 313),
        (315, 309),
        (345, 329),
        (351, 366),
        (337, 405),
        (296, 416),
        (258, 405),
        (239, 378),
        (242, 353),
        (254, 330),
        (284, 361),
    ],
    [
        (267, 315),
        (299, 294),
        (327, 310),
        (340, 350),
        (322, 395),
        (293, 415),
        (262, 403),
        (249, 377),
        (243, 348),
        (250, 332),
        (280, 352),
    ],
    [
        (245, 321),
        (289, 271),
        (319, 276),
        (338, 326),
        (324, 392),
        (290, 415),
        (257, 388),
        (246, 359),
        (243, 340),
        (248, 320),
        (283, 340),
    ],
    [
        (240, 244),
        (269, 228),
        (300, 237),
        (321, 282),
        (329, 369),
        (291, 412),
        (260, 390),
        (244, 337),
        (233, 286),
        (230, 255),
        (276, 324),
    ],
]
# All guides use the actual shape of that painted source, before ground registration.
# Local surface anchors outside the haunch lock the ribs and floor.
PIN = np.array(
    [
        (2, 2),
        (255, 2),
        (510, 10),
        (0, 270),
        (510, 250),
        (4, 510),
        (270, 508),
        (510, 510),
        (211, 429),
        (365, 434),
        (420, 399),
        (360, 274),
        (318, 255),
        (293, 231),
        (271, 205),
        (209, 208),
        (191, 283),
        (201, 355),
    ],
    float,
)
HIGH_TOE = np.array([242.0, 155.0])
HIGH_HOCK = np.array([246.0, 244.0])
OUTLINE = [
    (239, 152),
    (243, 151),
    (248, 152),
    (252, 155),
    (256, 160),
    (258, 167),
    (260, 178),
    (259, 193),
    (259, 208),
    (259, 220),
    (263, 225),
    (268, 227),
    (273, 233),
    (277, 244),
    (276, 252),
    (269, 258),
    (257, 261),
    (248, 260),
    (239, 256),
    (232, 251),
    (227, 244),
    (225, 237),
    (224, 226),
    (225, 216),
    (226, 206),
    (228, 193),
    (229, 186),
    (227, 181),
    (225, 179),
    (225, 172),
    (226, 165),
    (228, 160),
    (230, 157),
    (234, 154),
]
# Empirical contact geometry from the low, chest-height, and ear-height paintings.
STAGES = np.array([0.0, 1.0, 2.0, 3.0])
TOE = np.array([(235, 410), (240, 357), (250, 241), (255, 155)], float)
HOCK = np.array([(314, 401), (292, 401), (261, 321), (259, 244)], float)
ANGLE = np.unwrap(np.arctan2((TOE - HOCK)[:, 1], (TOE - HOCK)[:, 0]))
# Three short scratches rise to the same ear root, with recovery clear of the ear.
TIME = np.array([0, 3, 9, 16, 23, 30, 35, 38, 42, 45, 49, 52, 56, 60, 66, 73, 80, 83])
HEIGHT = np.array(
    [
        0,
        0,
        0.28,
        1.0,
        1.59,
        2.37,
        2.91,
        3.0,
        2.73,
        3.0,
        2.72,
        3.0,
        2.76,
        2.45,
        1.80,
        1.0,
        0.0,
        0.0,
    ]
)
HEIGHT_CURVE = PchipInterpolator(TIME, HEIGHT)
HEAD_CURVE = PchipInterpolator(
    [0, 7, 16, 25, 34, 38, 53, 60, 69, 77, 80, 83],
    [0, 0, 0.045, 0.25, 0.91, 1.0, 1.0, 0.87, 0.31, 0.08, 0.0, 0.0],
)


def ease(t):
    return np.clip(t, 0, 1) ** 2 * (3 - 2 * np.clip(t, 0, 1))


def tube(points, k=1.0):
    m = np.zeros((1024, 1024), "u1")
    cv2.fillPoly(
        m, [np.rint(2 * np.array(points)).astype("i4")], 255, lineType=cv2.LINE_AA
    )
    m = cv2.resize(m, (512, 512), interpolation=cv2.INTER_AREA).astype("f") / 255
    if k:
        m = cv2.GaussianBlur(m, (0, 0), k)
    return m


def pm(arr):
    v = arr.astype("f") / 255
    v[:, :, :3] *= v[:, :, 3:]
    return v


def unpm(a):
    c = a[:, :, 3:]
    rgb = a[:, :, :3] / np.maximum(c, 1e-7)
    return np.uint8(np.rint(np.clip(np.concatenate((rgb, c), axis=2), 0, 1) * 255))


def at(im, x, y, interp=cv2.INTER_LINEAR):
    return cv2.remap(
        im, x.astype("f"), y.astype("f"), interp, borderMode=cv2.BORDER_CONSTANT
    )


def rotate(v, angle):
    c, s = np.cos(angle), np.sin(angle)
    return np.stack(
        [v[..., 0] * c - v[..., 1] * s, v[..., 0] * s + v[..., 1] * c], axis=-1
    )


@lru_cache(None)
def refs():
    seated = np.asarray(Image.open(SEATED_REFERENCE).convert("RGBA")).copy()
    atlas = Image.open(SRC / "care-seated-atlas.png").convert("RGBA")
    source = [seated]
    for center in ATLAS_CENTERS:
        im = atlas.crop((center - 195, 0, center + 195, 398))
        im = im.resize((369, 377), Image.Resampling.LANCZOS)
        reg = Image.new("RGBA", (512, 512))
        reg.alpha_composite(im, (73, 66))
        source.append(np.array(reg))
    return source


@lru_cache(None)
def clear_guide(k):
    a = pm(refs()[k])
    if k == 0:
        return a
    # Remove the already-painted active distal leg from each source. A neighboring untouched
    # patch of the same seated ribcage supplies the revealed fur. The actual haunch and joint
    # remain original art, so the hock grows out of the correct thigh at every height.
    compare = pm(refs()[0])
    polygons = [
        [],
        [
            (214, 351),
            (230, 344),
            (249, 345),
            (273, 364),
            (296, 392),
            (297, 410),
            (284, 416),
            (260, 397),
            (239, 384),
            (215, 382),
        ],
        [
            (222, 231),
            (242, 228),
            (258, 238),
            (265, 253),
            (266, 293),
            (273, 305),
            (275, 322),
            (262, 331),
            (240, 336),
            (229, 327),
            (224, 318),
        ],
        [
            (219, 149),
            (239, 145),
            (253, 151),
            (262, 165),
            (264, 180),
            (264, 217),
            (271, 226),
            (269, 246),
            (259, 252),
            (241, 256),
            (217, 246),
        ],
    ]
    m = tube(polygons[k], 2.2)
    if k == 1:
        replacement = at(compare, X * 0 + 249 + (X - 225) * 0.4, Y - 16)
    else:
        replacement = at(compare, X + BODY_SHIFT[k] - 15, Y + 1)
    return a * (1 - m[:, :, None]) + replacement * m[:, :, None]


def source_field(q, p):
    f = RBFInterpolator(q, p - q, smoothing=10, kernel="thin_plate_spline")(GRID)
    f = (f + GRID).reshape(GX.shape + (2,)).astype("f")
    return cv2.remap(f, X / 5, Y / 5, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def body(stage, base):
    lo = min(int(stage), 2)
    hi = lo + 1
    t = stage - lo
    a, b = np.array(THIGH[lo], float), np.array(THIGH[hi], float)
    a0 = a + np.array([BODY_SHIFT[lo], 0])
    b0 = b + np.array([BODY_SHIFT[hi], 0])
    dest = np.vstack(((1 - t) * a0 + t * b0, PIN))
    # A common stationary ribcage and tail anchor every guide to the same seated dog.
    result = []
    for k, pts in [(lo, a), (hi, b)]:
        aa = clear_guide(k)
        field = source_field(dest, np.vstack((pts, PIN - np.array([BODY_SHIFT[k], 0]))))
        color = np.clip(at(aa, field[:, :, 0], field[:, :, 1], cv2.INTER_CUBIC), 0, 1)
        result.append(color)
    left, right = result
    soft = (1 - t) * left + t * right
    pick = left if t < 0.5 else right
    col = np.clip(soft[:, :, :3] / np.maximum(soft[:, :, 3:], 1.0e-6), 0, 1)
    pick_rgb = np.clip(pick[:, :, :3] / np.maximum(pick[:, :, 3:], 1.0e-6), 0, 1)
    # Compare color in straight light; premultiplied black from outside one guide must
    # never become a dark stripe in the other guide's feathered contour.
    detail = (pick_rgb - cv2.GaussianBlur(pick_rgb, (0, 0), 1.15)) * ease(
        (pick[:, :, 3:] - 0.65) / 0.25
    )
    rgb = np.clip(cv2.GaussianBlur(col, (0, 0), 0.85) + detail, 0, 1)

    def distance(alpha):
        m = (alpha > 0.5).astype("u1")
        return cv2.distanceTransform(m, cv2.DIST_L2, 3) - cv2.distanceTransform(
            1 - m, cv2.DIST_L2, 3
        )

    rail = (1 - t) * distance(left[:, :, 3]) + t * distance(right[:, :, 3])
    cov = np.clip(0.5 + rail * 0.50, 0, 1)
    edge = ease(min(1, t / 0.14, (1 - t) / 0.14)) * ease(
        np.abs(left[:, :, 3] - right[:, :, 3]) / 0.6
    )
    alpha = (soft[:, :, 3] * (1 - edge) + edge * cov)[:, :, None]
    color = np.concatenate((rgb * alpha, alpha), axis=2)
    border = np.interp(
        Y,
        [0, 151, 186, 235, 280, 320, 366, 398, 430, 512],
        [266, 259, 243, 227, 225, 224, 229, 226, 226, 226],
    )
    w = (
        ease((X - border) / 18)
        * ease((Y - 158) / 30)
        * (1 - ease((X - 327) / 48))
        * ease((418 - Y) / 15)
    )
    # Where the haunch emerges from behind the nape, move the one fur
    # boundary instead of dissolving a second pale strip into the background.
    return np.clip(registered_mix(base,color,w),0,1)


def headmap(tilt, pressure):
    a = np.deg2rad(13.2 * tilt)
    cx, cy = 196.0, 212.0
    # Inverse rigid rotation of skull. The collar absorbs the small rotation below the jaw.
    gx = X - 16.0 * tilt
    gy = Y - 5.0 * tilt
    xr = (gx - cx) * np.cos(a) + (gy - cy) * np.sin(a) + cx
    yr = -(gx - cx) * np.sin(a) + (gy - cy) * np.cos(a) + cy
    w = ease((294 - Y) / 60) * ease((320 - X) / 53)
    near_ear = np.exp(-(((xr - 222) / 24) ** 2 + ((yr - 141) / 33) ** 2))
    return X + w * (xr - X) - 1.2 * pressure * near_ear, Y + w * (
        yr - Y
    ) + 1.5 * pressure * near_ear


@lru_cache(None)
def paw_source():
    src = pm(refs()[3])
    m = tube(OUTLINE, 0.65)
    # Only the distal fur is cut sharply. Its proximal end tapers into the haunch over 13 pixels.
    m *= ease((253 - Y) / 16) * ease((270 - X) / 12)
    return src * m[:, :, None]


def pose(i):
    stage = float(HEIGHT_CURVE(i))
    tilt = float(HEAD_CURVE(i))
    hock = PchipInterpolator(STAGES, HOCK, axis=0)(stage)
    ang = float(PchipInterpolator(STAGES, ANGLE)(stage))
    tip = PchipInterpolator(STAGES, TOE, axis=0)(stage)
    pressure = float(ease((stage - 2.89) / 0.11))
    # The register changes only the projected metatarsal length. The toes always retain unit scale.
    return stage, tilt, pressure, tip, hock, ang


def pawmap(tip, hock, ang):
    natural = np.arctan2(*(HIGH_TOE - HIGH_HOCK)[::-1])
    e = np.array([np.cos(ang), np.sin(ang)])
    n = np.array([-e[1], e[0]])
    along = (X - tip[0]) * e[0] + (Y - tip[1]) * e[1]
    across = (X - tip[0]) * n[0] + (Y - tip[1]) * n[1]
    length = np.linalg.norm(tip - hock)
    ref_l = np.linalg.norm(HIGH_TOE - HIGH_HOCK)
    # Keep the entire toe cluster rigid and absorb projection difference towards the hock.
    scale = 1 + (ref_l / length - 1) * ease((-along - 18) / max(5, length - 31))
    ex = np.array([np.cos(natural), np.sin(natural)])
    ey = np.array([-ex[1], ex[0]])
    return HIGH_TOE[0] + along * scale * ex[0] + across * ey[0], HIGH_TOE[
        1
    ] + along * scale * ex[1] + across * ey[1]


def render_frame(i):
    stage, tilt, pressure, tip, hock, ang = pose(i)
    base = pm(refs()[0])
    if i in (0, 1, 2, 3, 80, 81, 82, 83):
        return refs()[0].copy()
    x, y = headmap(tilt, pressure)
    base = at(base, x, y, cv2.INTER_CUBIC)
    bg = body(stage, base)
    ux, uy = pawmap(tip, hock, ang)
    paw = at(paw_source(), ux, uy, cv2.INTER_CUBIC)
    # Throughout lift, contact and lowering, a single sampled toe surface moves over the intact body.
    # At the seated pose it slips behind the supporting paw; the whole plate is identical at both ends.
    paw *= ease(stage / 0.16)
    return unpm(np.clip(paw + bg * (1 - np.clip(paw[:, :, 3:], 0, 1)), 0, 1))


def render(dest):
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    for i in range(84):
        im = Image.fromarray(render_frame(i))
        im.save(dest / f"{i:05}.webp", lossless=True, exact=True, method=1)
    (dest / "motion.json").write_text(
        json.dumps(
            dict(
                fps=24,
                frameCount=83,
                terminalSample=True,
                poses=[
                    ["Sit05", 0],
                    ["Lift", 16],
                    ["Chest", 25],
                    ["Ear", 38],
                    ["Ear", 45],
                    ["Ear", 52],
                    ["Sit05", 83],
                ],
                entry="Sit05",
                exit="Sit05",
            )
        ),
        encoding="utf8",
    )


def audit(dest, output):
    """Check the lossless rendered pixels against the single moving face and toe material."""
    dest = Path(dest)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    canonical = refs()[0]
    actual_names = sorted(q.name for q in dest.glob("*.webp"))
    assert actual_names == [f"{i:05}.webp" for i in range(84)]
    rows = []
    face_error = []
    face_sharp = []
    paw_error = []
    paw_sharp = []
    components = []
    front_error = []

    def detail(image, region):
        gray = cv2.cvtColor(image[:, :, :3].astype("f"), cv2.COLOR_RGB2GRAY)
        return float(
            np.mean(
                np.hypot(
                    cv2.Sobel(gray, -1, 1, 0, ksize=3)[region],
                    cv2.Sobel(gray, -1, 0, 1, ksize=3)[region],
                )
            )
        )

    def features(mx, my):
        return (((mx - 157) / 11) ** 2 + ((my - 152) / 8) ** 2 < 1) | (
            ((mx - 115) / 11) ** 2 + ((my - 165) / 11) ** 2 < 1
        )

    def claw(mx, my):
        return ((mx - 241) / 8) ** 2 + ((my - 166) / 11) ** 2 < 1

    face_reference = detail(canonical, features(X, Y))
    toe_reference = detail(refs()[3], claw(X, Y))
    for frame in range(84):
        im = np.array(Image.open(dest / f"{frame:05}.webp").convert("RGBA"))
        if frame in (0, 83):
            np.testing.assert_array_equal(im, canonical)
        count, labels, stats, _ = cv2.connectedComponentsWithStats(
            np.uint8(im[:, :, 3] > 128)
        )
        components.append(int(np.sum(stats[1:, 4] > 8)))
        stage, tilt, pressure, tip, hock, angle = pose(frame)
        mx, my = headmap(tilt, pressure)
        ux, uy = pawmap(tip, hock, angle)
        ear = np.unravel_index(np.argmin((mx - 224) ** 2 + (my - 139) ** 2), mx.shape)[
            ::-1
        ]
        toe_image = at(paw_source(), ux, uy, cv2.INTER_CUBIC)
        yy, xx = np.where((toe_image[:, :, 3] > 0.5) & (uy < 164))
        distance = float(np.min(np.hypot(xx - ear[0], yy - ear[1])))
        material_tip = tip + rotate(
            np.array([-0.5, -1.0]), angle - np.arctan2(*(HIGH_TOE - HIGH_HOCK)[::-1])
        )
        rows.append(
            dict(
                frame=frame,
                toe=[round(float(n), 3) for n in material_tip],
                earRoot=[int(n) for n in ear],
                toeEdgeToEarPx=round(distance, 2),
                contact=distance <= 1.0,
            )
        )
        if frame < 38:
            continue
        face = features(mx, my)
        baseline = unpm(np.clip(at(pm(canonical), mx, my, cv2.INTER_CUBIC), 0, 1))
        face_error.append(
            int(np.max(np.abs(im.astype("i2") - baseline.astype("i2"))[face, :3]))
        )
        face_sharp.append(detail(im, face) / face_reference)
        feet = (
            (X > 125) & (X < 220) & (Y > 405) & (Y < 426) & (canonical[:, :, 3] > 200)
        )
        front_error.append(
            int(np.max(np.abs(im.astype("i2") - canonical.astype("i2"))[feet, :3]))
        )
        if stage > 0.2:
            ink = claw(ux, uy)
            comparison = unpm(toe_image)
            delta = np.abs(im.astype("i2") - comparison.astype("i2"))[ink, :3]
            paw_error.append(float(delta.mean()))
            paw_sharp.append(detail(im, ink) / toe_reference)
    assert all(v == 1 for v in components)
    assert max(face_error) <= 1, max(face_error)
    assert min(face_sharp) > 0.94 and min(paw_sharp) > 0.93
    assert max(paw_error) < 1.0
    assert all(rows[i]["contact"] for i in (38, 45, 52))
    assert all(rows[i]["toeEdgeToEarPx"] >= 15 for i in (42, 49))

    def limits(v):
        return [round(float(min(v)), 5), round(float(max(v)), 5)]

    report = dict(
        totalFrames=84,
        intervals=83,
        fps=24,
        endpointsEqualOriginalSit05=True,
        silhouetteComponentsOver8px=limits(components),
        touchFrames=[38, 45, 52],
        recoveryGapsPixels=[rows[i]["toeEdgeToEarPx"] for i in (42, 49)],
        lateFramesMeasured=len(face_error),
        clearlyLiftedLatePawFramesMeasured=len(paw_error),
        faceReference="Original Sit05 rigid head sampling, evaluated at each output angle. Sobel magnitude over nose and iris in original material space.",
        maxEyeNoseRGBDifferenceVsSingleSample=max(face_error),
        faceSobelMagnitudeRatioToOriginal=limits(face_sharp),
        pawReference="Same toe core of the generated high-contact painting registered at every angle; excludes the short transition behind the support foot.",
        maxMeanToeRGBDifferenceVsSingleSample=round(max(paw_error), 5),
        pawSobelMagnitudeRatioToOriginal=limits(paw_sharp),
        maxFrontFeetInteriorRGBShift=max(front_error),
    )
    (output / "scratch-repair-qa.json").write_text(
        json.dumps(report, indent=2), encoding="utf8"
    )
    (output / "scratch-tip-track.json").write_text(
        json.dumps(
            dict(
                canvas=[512, 512],
                fps=24,
                coordinates="screen XY in pixels from upper left, inclusive terminal",
                toeMaterial="subpixel center of leading upper toe surface, mapped from UV (241.5,154) in the registered high painting",
                earRootMaterial=[224, 139],
                frames=rows,
            ),
            indent=1,
        ),
        encoding="utf8",
    )
    return report


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument(
        "--out",
        type=Path,
        default=Path(os.environ.get("CUTEDOG_REVIEW_PERFORM_OUT", SRC)) / "scratch",
    )
    p.add_argument("--audit-only", action="store_true")
    p.add_argument("--report-dir", type=Path)
    args = p.parse_args()
    if not args.audit_only:
        render(args.out)
    print(json.dumps(audit(args.out, args.report_dir or args.out.parent), indent=1))
