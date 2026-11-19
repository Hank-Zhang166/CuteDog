"""Author the dog's editable Cubism motion curves and sample them for the native Core."""

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

import numpy as np
from scipy.interpolate import PchipInterpolator

FPS = 24
N = {
    "stand": 96,
    "breathe": 72,
    "tail_wag": 48,
    "happy": 32,
    "tilt": 26,
    "wave": 48,
    "blink": 12,
    "look_left": 30,
    "look_right": 34,
    "curious": 39,
    "expect": 36,
    "relax": 44,
    "soothe": 50,
    "focus": 32,
    "walk": 16,
    "walk_start": 18,
    "walk_stop": 17,
    "trot": 14,
    "walk_to_trot": 26,
    "trot_to_walk": 27,
    "run": 12,
    "trot_to_run": 23,
    "run_to_trot": 25,
    "sit": 24,
    "sit_idle": 45,
    "stand_up": 21,
    "play_ball": 48,
    "treat": 41,
    "hop": 26,
}
LOOPS = {"stand", "breathe", "tail_wag", "walk", "trot", "run", "sit_idle"}
GAITS = {
    "walk",
    "trot",
    "run",
    "walk_start",
    "walk_stop",
    "walk_to_trot",
    "trot_to_walk",
    "trot_to_run",
    "run_to_trot",
}
IDLES = ("stand", "breathe", "tail_wag")
PARAMS = (
    "ParamPose",
    "ParamBreath",
    "ParamHead",
    "ParamTail",
    "ParamLift",
    "ParamBlink",
)
TAU = 2 * math.pi


def clean(x):
    value = round(float(x), 9)
    return 0 if abs(value) < 1e-9 else value


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        + "\n",
        encoding="utf-8",
        newline="\n",
    )


def ease(t):
    t = max(0, min(1, t))
    return t * t * (3 - 2 * t)


def hump(t, a, b):
    if t <= a or t >= b:
        return 0.0
    return math.sin(math.pi * (t - a) / (b - a)) ** 2


def oscillate(t, frequency, phase=0):
    return math.sin(TAU * (t * frequency + phase))


def keyPose(bank, p):
    return bank[min(len(bank) - 1, max(0, int(math.floor(p * (len(bank) - 1) + 0.5))))]


def native_values(name, t, pose):
    """Authoring coordinates in normalized model parameter units."""
    stand = pose["Stand"]
    walk = pose["walk"]
    sit = pose["sit"]
    turn = pose["turn"]
    wave = pose.get("Wave", stand)
    jump = pose.get("Jump", stand)
    active = stand
    b = h = w = j = e = 0.0
    gate = hump(t, 0, 1)
    if name in GAITS:
        if name in ("walk_start", "walk_stop"):
            launch = [stand] + pose["begin"] + [walk[0]]
            if name == "walk_start" and t <= 0.34:
                active = keyPose(launch, t / 0.34)
            elif name == "walk_stop" and t >= 0.66:
                active = keyPose(launch, (1 - t) / 0.34)
            else:
                progress = (t - 0.34) / 0.66 if name == "walk_start" else t / 0.66
                active = walk[int(math.floor(progress * len(walk) + 0.5)) % len(walk)]
        else:
            cycles = 1 if name in ("walk", "trot", "run") else 2
            active = walk[int(math.floor(t * len(walk) * cycles + 0.5)) % len(walk)]
        strength = {"walk": 0.18, "trot": 0.38, "run": 0.62}.get(name, 0.36)
        cycles = 1 if name in ("walk", "trot", "run") else 2
        w = 0.27 * oscillate(t, cycles) * gate
        h = 0.21 * oscillate(t, cycles * 2, -0.13) * gate
        b = -strength * oscillate(t, cycles * 2) * gate
        j = strength * max(0, oscillate(t, cycles * 2, -0.09)) ** 1.2 * gate
        if t <= 0:
            active = stand if name == "walk_start" else walk[0]
        if t >= 1:
            active = stand if name == "walk_stop" else walk[0]
    elif name in ("sit", "stand_up", "sit_idle"):
        if name == "sit_idle":
            active = sit[-1]
            b = 0.4 * oscillate(t, 1.2) * gate
            h = 0.17 * oscillate(t, 1) * gate
            w = 0.35 * oscillate(t, 2.5) * gate
            e = 0.92 * hump(t, 0.51, 0.66)
        else:
            p = t if name == "sit" else 1 - t
            active = keyPose([stand] + sit, ease(p))
            h = (-0.43 if name == "sit" else 0.30) * hump(t, 0.02, 0.72)
            w = 0.4 * oscillate(t, 1.25) * gate
            b = 0.27 * hump(t, 0.19, 0.84)
    elif name == "stand":
        h = 0.21 * hump(t, 0.17, 0.39) - 0.13 * hump(t, 0.46, 0.67)
        w = gate * (0.20 * oscillate(t, 3.0) + 0.16 * oscillate(t, 4.35, 0.07))
        b = gate * (0.25 * oscillate(t, 2.0) + 0.07 * oscillate(t, 3.0, 0.1))
        e = 0.96 * hump(t, 0.701, 0.760)
    elif name == "breathe":
        b = gate * (0.65 * oscillate(t, 1.6, -0.02) + 0.13 * oscillate(t, 3.2, 0.12))
        h = 0.20 * oscillate(t, 1.6, -0.08) * gate
        w = 0.23 * oscillate(t, 3.1) * gate
    elif name == "tail_wag":
        w = gate * (0.79 * oscillate(t, 4.05) + 0.08 * oscillate(t, 8.1))
        h = 0.21 * oscillate(t, 1.1) * gate
        b = 0.35 * oscillate(t, 2.0) * gate
    elif name == "hop":
        # Take-off and landing stay low. The paw tuck follows the rise.
        j = 0.98 * hump(t, 0.16, 0.78)
        b = -0.82 * hump(t, 0.02, 0.23) - 0.69 * hump(t, 0.69, 0.93)
        flight = [stand] + pose["launch"] + [jump]
        blend = min(ease((t - 0.055) / 0.30), ease((0.93 - t) / 0.30))
        active = keyPose(flight, blend)
        h = 0.66 * hump(t, 0.22, 0.62) - 0.31 * hump(t, 0.64, 0.93)
        w = 0.63 * oscillate(t, 1.27) * gate
    elif name == "wave":
        approach = [stand] + sit + pose["greet"] + [wave]
        if t < 0.34:
            active = keyPose(approach, t / 0.34)
        elif t > 0.66:
            active = keyPose(approach, (1 - t) / 0.34)
        else:
            # Two short forepaw greetings, held in the same seated pose family.
            active = keyPose(
                approach[-3:], 1 - 0.72 * math.sin(math.pi * (t - 0.34) / 0.32 * 2) ** 2
            )
        h = 0.53 * hump(t, 0.04, 0.60)
        w = 0.77 * oscillate(t, 3.25) * gate
        b = 0.21 * oscillate(t, 1.15) * gate
        j = 0.13 * hump(t, 0.10, 0.46)
    elif name == "play_ball":
        # Accept the ball at the forefoot, then spring back to watch it leave.
        h = -0.84 * hump(t, 0.07, 0.52) + 0.62 * hump(t, 0.56, 0.88)
        b = -0.50 * hump(t, 0.22, 0.53)
        w = 0.79 * oscillate(t, 3.1) * gate
        j = 0.23 * hump(t, 0.53, 0.81)
    elif name == "treat":
        h = (
            0.74 * hump(t, 0.05, 0.54)
            - 0.32 * hump(t, 0.50, 0.72)
            + 0.24 * hump(t, 0.71, 0.88)
        )
        w = 0.71 * oscillate(t, 3.4) * gate
        b = 0.24 * hump(t, 0.36, 0.67)
        e = 0.74 * hump(t, 0.59, 0.74)
    elif name in ("look_left", "look_right", "curious", "focus"):
        polarity = -1 if name == "look_left" else 1
        # A brief turn passes through its illustrated intermediates.
        if turn and name in ("look_right", "curious"):
            angular = hump(t, 0.04, 0.90) * (1 if name == "look_right" else 0.56)
            active = keyPose([stand] + turn, angular)
        h = (
            polarity
            * (
                {"look_left": 0.59, "look_right": 0.68, "curious": 0.86, "focus": 0.48}[
                    name
                ]
            )
            * hump(t, 0.02, 0.91)
        )
        w = 0.30 * oscillate(t, 1.5) * gate
        b = 0.17 * oscillate(t, 1.15) * gate
        e = 0.77 * hump(t, 0.77, 0.94) if name in ("curious", "focus") else 0.0
    elif name == "blink":
        e = 0.99 * hump(t, 0.12, 0.72)
        h = -0.16 * hump(t, 0.18, 0.86)
        b = 0.08 * gate
    elif name == "tilt":
        h = 0.81 * hump(t, 0.03, 0.72) - 0.26 * hump(t, 0.60, 0.94)
        w = 0.37 * oscillate(t, 1.5) * gate
    elif name == "happy":
        j = 0.33 * hump(t, 0.16, 0.51) + 0.20 * hump(t, 0.55, 0.86)
        b = -0.46 * hump(t, 0.03, 0.26)
        h = 0.63 * hump(t, 0.16, 0.72)
        w = 0.82 * oscillate(t, 3) * gate
        e = 0.64 * hump(t, 0.38, 0.58)
    elif name == "expect":
        h = 0.80 * hump(t, 0.04, 0.86)
        w = 0.72 * oscillate(t, 2.5) * gate
        b = 0.27 * gate
        j = 0.19 * hump(t, 0.31, 0.79)
    elif name == "relax":
        h = -0.55 * hump(t, 0.08, 0.88)
        w = 0.31 * oscillate(t, 1.15) * gate
        b = 0.34 * oscillate(t, 1.0) * gate
        e = 0.64 * hump(t, 0.42, 0.84)
    elif name == "soothe":
        h = -0.36 * hump(t, 0.05, 0.64) + 0.19 * hump(t, 0.62, 0.91)
        b = 0.51 * oscillate(t, 1.0) * gate
        w = 0.43 * oscillate(t, 1.45) * gate
        e = 0.80 * hump(t, 0.29, 0.51)
    else:
        raise KeyError(name)
    return dict(zip(PARAMS, (active, b, h, w, j, e)))


def curve(name, frames, values, slope=None, step=False):
    # A monotonic cubic tangent avoids overshoot in bounded parameters.
    slopes = (
        np.array(slope)
        if slope is not None
        else PchipInterpolator(frames, values).derivative()(frames)
    )
    if slope is None:
        slopes[[0, -1]] = 0
    out = [0, clean(values[0])]
    for a, b, x, y, dx, dy in zip(
        frames[:-1], frames[1:], values[:-1], values[1:], slopes[:-1], slopes[1:]
    ):
        d = b - a
        if step:
            out.extend([2, clean(b / FPS), clean(y)])
        else:
            out.extend(
                [
                    1,
                    clean((a + d / 3) / FPS),
                    clean(x + dx * d / 3),
                    clean((b - d / 3) / FPS),
                    clean(y - dy * d / 3),
                    clean(b / FPS),
                    clean(y),
                ]
            )
    return dict(Target="Parameter", Id=name, FadeInTime=0, FadeOutTime=0, Segments=out)


def sample(track, frame):
    # All authored Bezier curves have evenly spaced time handles.
    s = track["Segments"]
    x0, y0 = s[:2]
    sec = frame / FPS
    k = 2
    while k < len(s):
        kind = s[k]
        sz = 7 if kind == 1 else 3
        x1, y1 = s[k + sz - 2 : k + sz]
        if (sec < x1 - 1e-8) or k + sz == len(s):
            if kind == 2:
                return (float(y1 if abs(sec - x1) < 1e-8 else y0), 0.0)
            u = max(0, min(1, (sec - x0) / (x1 - x0)))
            v = 1 - u
            yA, yB = s[k + 2], s[k + 4]
            y = (
                v * v * v * y0
                + 3 * v * v * u * yA
                + 3 * v * u * u * yB
                + u * u * u * y1
            )
            speed = (
                3
                * (v * v * (yA - y0) + 2 * v * u * (yB - yA) + u * u * (y1 - yB))
                / (x1 - x0)
            )
            return y, speed
        x0, y0 = x1, y1
        k += sz
    return float(y0), 0.0


def motion(tracks, n, loop):
    points = segments = 0
    for track in tracks:
        s = track["Segments"]
        k = 2
        points += 1
        while k < len(s):
            size = 7 if s[k] == 1 else 3
            points += (size - 1) // 2
            segments += 1
            k += size
        assert k == len(s)
    meta = dict(
        Duration=clean(n / FPS),
        Fps=FPS,
        Loop=loop,
        AreBeziersRestricted=True,
        FadeInTime=0,
        FadeOutTime=0,
        CurveCount=len(tracks),
        TotalSegmentCount=segments,
        TotalPointCount=points,
        UserDataCount=0,
        TotalUserDataSize=0,
    )
    return dict(Version=3, Meta=meta, Curves=tracks, UserData=[])


def state(tracks, f):
    xy = [sample(c, f) for c in tracks]
    return dict(
        position=[clean(v) for v, _ in xy], velocity=[round(d, 6) for _, d in xy]
    )


def author(poses, ids, output):
    def get(prefix):
        return [
            poses[k] for k in sorted(poses) if re.fullmatch(prefix + r"\d+", k, re.I)
        ]

    pose = {
        **poses,
        "walk": get("Walk"),
        "sit": get("Sit"),
        "turn": get("Turn"),
        "begin": get("Begin"),
        "launch": get("Launch"),
        "greet": get("Greet"),
    }
    if (
        any(not pose[g] for g in ("sit", "begin", "turn", "launch", "greet"))
        or len(pose["walk"]) < 4
    ):
        raise ValueError("Native pose bank is incomplete")
    ids = [p for p in PARAMS if p in ids]
    if "ParamPose" not in ids:
        raise ValueError("Dog rig is missing ParamPose")
    library = {}
    for name, n in N.items():
        samples = [native_values(name, f / n, pose) for f in range(n + 1)]
        tracks = []
        for p in ids:
            if p == "ParamPose":
                starts = (
                    [0]
                    + [f for f in range(1, n) if samples[f][p] != samples[f - 1][p]]
                    + [n]
                )
            else:
                starts = list(range(0, n, 2)) + [n]
            tracks.append(
                curve(
                    p, starts, [samples[f][p] for f in starts], step=(p == "ParamPose")
                )
            )
        library[name] = motion(tracks, n, name in LOOPS)
    sources = {}
    for source in IDLES:
        for a in range(6, N[source] - 8, 6):
            name = f"{source}_exit_{a:03}"
            base = library[source]["Curves"]
            entry = state(base, a)
            tracks = []
            for i, p in enumerate(ids):
                x, spd = entry["position"][i], entry["velocity"][i] / FPS
                neutral = pose["Stand"] if p == "ParamPose" else 0.0
                # Crest smoothly over one frame if the entry is still moving away from neutral.
                target = x + spd * 0.5
                target = (
                    max(0 if p in ("ParamLift", "ParamBlink") else -1, min(1, target))
                    if p != "ParamPose"
                    else x
                )
                tracks.append(
                    curve(
                        p,
                        [0, 1, 8],
                        [x, target, neutral],
                        slope=[spd, 0, 0],
                        step=(p == "ParamPose"),
                    )
                )
            library[name] = motion(tracks, 8, False)
            sources[name] = entry
    output.mkdir(exist_ok=True, parents=True)
    cont = dict(
        canonical=state(library["stand"]["Curves"], 0),
        sources=sources,
        entries={},
        terminals={},
    )
    for name, m in library.items():
        write(output / f"{name}.motion3.json", m)
        n = round(m["Meta"]["Duration"] * FPS)
        cont["entries"][name] = state(m["Curves"], 0)
        cont["terminals"][name] = state(m["Curves"], n)
    write(output / "continuity.json", cont)
    write(
        output / "library.json",
        dict(
            fps=FPS,
            parameters=ids,
            poses=poses,
            files={
                n: hashlib.sha256(
                    (output / f"{n}.motion3.json").read_bytes()
                ).hexdigest()
                for n in library
            },
        ),
    )
    print(f"{len(library)} editable motion3 files, {len(ids)} native parameters")
    return library


if __name__ == "__main__":
    q = argparse.ArgumentParser()
    q.add_argument("poses", type=Path)
    q.add_argument("ids", help="comma-separated native parameters")
    q.add_argument("output", type=Path)
    args = q.parse_args()
    author(
        json.loads(args.poses.read_text(encoding="utf-8-sig")),
        args.ids.split(","),
        args.output,
    )
