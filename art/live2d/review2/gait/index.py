from pathlib import Path
from hashlib import sha256
from PIL import Image
import json, os

HERE = Path(__file__).resolve().parent
P = Path(os.environ.get("CUTEDOG_GAIT_OUTPUT", HERE)).resolve()
FR = P / "frames"
H = lambda file: sha256(Image.open(file).convert("RGBA").tobytes()).hexdigest()
MOTIONS = ("walk", "trot", "chase", "walk_start", "walk_stop", "walk_to_trot", "trot_to_walk",
           "backstep", "slip_recover", "forward_hop", "pause", "land", "rebound")
TURN_CLIPS = ("turn", "turn_to_right", "turn_to_left")


def direction(clip):
    return "right" if clip.endswith("_right") else "left"


def base(clip):
    return clip.removesuffix("_right")


def ends(clip):
    side = direction(clip)
    g = base(clip)
    st = "stand_" + side
    name = lambda a: a + "_" + side
    if clip in ("turn", "turn_to_right"):
        return "stand_left", "stand_right"
    if clip == "turn_to_left":
        return "stand_right", "stand_left"
    if g in ("walk", "trot"):
        return name(g), name(g)
    if g == "walk_start":
        return st, name("walk")
    if g == "walk_stop":
        return name("walk"), st
    if g == "land":
        return "forward_hop_" + side + "@16", st
    if "_to_" in g:
        a, b = g.split("_to_")
        return name(a), name(b)
    return st, st


def build():
    model = next(
        v
        for v in (
            HERE.parents[1] / "poses/Stand.png",
            HERE.parents[1] / "live2d/poses/Stand.png",
        )
        if v.is_file()
    )
    ref = {
        "stand_left": {"file": "../../poses/Stand.png", "rgbaSha256": H(model)},
        "stand_right": {
            "file": "frames/walk_stop_right/00017.png",
            "rgbaSha256": H(FR / "walk_stop_right/00017.png"),
        },
    }
    result = {}
    directories = sorted((*MOTIONS, *(name + "_right" for name in MOTIONS), *TURN_CLIPS))
    for clip_id in directories:
        d = FR / clip_id
        m = json.loads((d / "metadata.json").read_text())
        n = m["frameIntervals"]
        frames = {p.name for p in d.glob("*.png")}
        expected = {f"{i:05}.png" for i in range(n + 1)}
        if frames != expected:
            raise ValueError(
                f"{d.name} has missing or unexpected frames: {frames ^ expected}"
            )
        a, b = ends(d.name)
        src0 = d / "00000.png"
        src1 = d / f"{n:05}.png"
        entry = H(src0)
        exit_ = H(src1)
        for pose, fp, h in [(a, src0, entry), (b, src1, exit_)]:
            existing = ref.get(pose)
            if existing and existing["rgbaSha256"] != h:
                raise Exception("junction mismatch: " + d.name + " " + pose)
            if not existing:
                ref[pose] = {
                    "file": str(fp.relative_to(P)).replace("\\", "/"),
                    "rgbaSha256": h,
                }
        result[d.name] = {
            "directory": "frames/" + d.name,
            "pattern": "%05d.png",
            "intervals": n,
            "fps": 24,
            "inclusiveTerminal": True,
            "loop": m["loop"],
            "direction": m["heading"],
            "entryPose": a,
            "exitPose": b,
            "entryRgbaSha256": entry,
            "exitRgbaSha256": exit_,
            "projectedTravel": [round(x / 512, 12) for x in m["projectedTravel"]],
            "totalTravelPixels": round(sum(m["projectedTravel"]), 6),
        }
    (P / "index.json").write_text(
        json.dumps(
            {
                "fps": 24,
                "canvas": [512, 512],
                "travelUnits": "canvas_width_fraction_positive_toward_heading; backstep negative",
                "references": ref,
                "clips": result,
            },
            ensure_ascii=False,
            indent=1,
        )
    )
    for side, folder in [("left", "forward_hop"), ("right", "forward_hop_right")]:
        assert (
            H(FR / folder / "00016.png")
            == ref["forward_hop_" + side + "@16"]["rgbaSha256"]
        )
    compact = {
        name: json.loads((FR / name / "metadata.json").read_text())
        for name in sorted(MOTIONS)
    }
    (P / "foot-contacts.json").write_text(json.dumps(compact, separators=(",", ":")))
    print(len(result), "clips indexed")


if __name__ == "__main__":
    build()
