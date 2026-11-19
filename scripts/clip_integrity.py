"""Cubism pack producer side of the Android ClipIntegrity.contentSha256 contract.

The Android verifier and the animation packer use the same v2 canonical records
and number normalization for clip and frame digests.
"""
import hashlib
import json
from decimal import Decimal


def canonical(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, Decimal)):
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError("Nonfinite value in clip digest")
        if number == 0:
            return "0"
        text = format(number, "f")
        return text.rstrip("0").rstrip(".") if "." in text else text
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(canonical(k) + ":" + canonical(value[k]) for k in sorted(value)) + "}"
    raise TypeError(type(value))


def state_sha(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def content_sha(width, height, clip):
    record = {k: clip[k] for k in ("id", "fps", "entryPose", "exitPose", "loop", "interrupt",
                                   "transitions", "frames", "frameSha256")}
    defaults = {
        "sampling": "inclusive_endpoints", "variantOf": None, "heading": None,
        "exitHeading": clip.get("heading"), "travelUnits": None, "projectedTravel": [],
        "canTravel": False, "entryStateSha256": None, "exitStateSha256": None, "terminalProof": None,
    }
    record.update({key: clip.get(key, fallback) for key, fallback in defaults.items()})
    if clip.get("backwardTravel"):
        record["backwardTravel"] = True
    record.update(schema="cutedog-clip-content-v2", width=width, height=height,
                  safeExitFrames=sorted(set(clip.get("safeExitFrames", []))))
    return state_sha(record)


def validate_package(manifest, assets, proof):
    clips = {c["id"]: c for c in manifest["clips"]}
    rows = {b["bridge"]: b for b in proof["bindings"]}
    internal = {name for name, c in clips.items() if "phaseEntry" in c}
    if rows.keys() != internal:
        raise ValueError("Phase coverage differs between proof and graph")
    seen = set()
    for name, clip in clips.items():
        for path, expected in zip(clip["frames"], clip["frameSha256"], strict=True):
            if path in seen or hashlib.sha256((assets / path).read_bytes()).hexdigest() != expected:
                raise ValueError("Duplicate path or damaged playback frame: " + path)
            seen.add(path)
        for successor in clip["transitions"]:
            if successor in internal or clip["exitPose"] != clips[successor]["entryPose"]:
                raise ValueError(f"Invalid ordinary transition {name} -> {successor}")
        if name in internal:
            entry, terminal, row = clip["phaseEntry"], clip["terminalProof"], rows[name]
            source = clips[entry["sourceClip"]]
            source_sha = content_sha(manifest["width"], manifest["height"], source)
            bridge_sha = content_sha(manifest["width"], manifest["height"], clip)
            if source_sha != row["sourceClipSha256"] or bridge_sha != row["bridgeClipSha256"]:
                raise ValueError("Source or bridge content mismatch: " + name)
            if source["frameSha256"][entry["atFrame"]] != clip["frameSha256"][0]:
                raise ValueError("Source anchor mismatch: " + name)
            for ref in (entry["proof"], terminal):
                if hashlib.sha256((assets / ref["path"]).read_bytes()).hexdigest() != ref["sha256"]:
                    raise ValueError("Damaged proof: " + ref["path"])
            for successor in clip["transitions"]:
                target = clips[successor]
                if target["frameSha256"][0] != terminal["sha256"] or target["entryStateSha256"] != terminal["stateSha256"]:
                    raise ValueError(f"Invalid bridge terminal {name} -> {successor}")
