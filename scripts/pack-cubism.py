"""Build the Android v2 frame library from a native Live2D Cubism model and motion bake.

Each clip directory holds N interval starts and its exact terminal sample,
numbered 00000.webp through N. travel.json contains N canvas-width distances
per moving clip. continuity.json contains independently evaluated control states
(position and velocity) for each entry, omitted terminal and phase anchor.
The graph supplies the existing user-visible actions and routing semantics.
The destination is created only after every input and binding passes validation.
"""
import argparse
import hashlib
import json
import math
import shutil
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
from clip_integrity import content_sha, state_sha, validate_package

FPS = 24
SIZE = 512
IDLE_SOURCES = ("stand", "breathe", "tail_wag")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n",
                    encoding="utf-8", newline="\n")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def samples(bake, name):
    paths = sorted((bake / name).glob("*.webp"))
    require(3 <= len(paths) <= 2401, f"{name}: expected N intervals plus a terminal")
    for i, path in enumerate(paths):
        require(path.name == f"{i:05d}.webp", f"{name}: missing sample {i}")
        with Image.open(path) as frame:
            require(frame.format == "WEBP" and frame.size == (SIZE, SIZE) and frame.mode == "RGBA",
                    f"{path}: expected 512px RGBA WebP")
    return paths


def matches_state(reference, candidate):
    for field, tolerance in (("position", 1e-5), ("velocity", 1e-3)):
        a, b = reference[field], candidate[field]
        require(len(a) > 0 and len(a) == len(b), f"Invalid {field} channels")
        require(all(math.isfinite(value) for value in [*a, *b]), f"Nonfinite {field}")
        if max(abs(x - y) for x, y in zip(a, b)) > tolerance:
            return False
    return True


def put_clip_files(target, name, paths):
    (target / name).mkdir()
    frames, hashes = [], []
    for path in paths[:-1]:
        dst = target / name / path.name
        shutil.copyfile(path, dst)
        frames.append("pet3d/" + dst.relative_to(target).as_posix())
        hashes.append(digest(dst))
    require(len(set(hashes)) > 1, f"{name}: all playback frames are identical")
    return frames, hashes


def bind_native_sources(args, clips, target):
    descriptor = read_json(args.model)
    require(descriptor.get("Version") == 3, "Expected a Cubism model3.json descriptor")
    refs = descriptor["FileReferences"]
    model_root = args.model.resolve().parent
    def model_file(name):
        path = (model_root / name).resolve()
        require(path.is_relative_to(model_root), "Invalid model resource path")
        return path
    moc = model_file(refs["Moc"])
    with moc.open("rb") as stream:
        require(stream.read(4) == b"MOC3", "Model descriptor does not resolve to a native MOC3")
    textures = {name.replace("\\", "/"): digest(model_file(name)) for name in refs["Textures"]}
    require(textures, "The model has no texture atlas")
    motions = {}
    library = read_json(args.library)
    require(library["fps"] == FPS and set(library["files"]) == set(clips), "Native motion library is out of date")
    for path in sorted(args.motions.rglob("*.motion3.json")):
        name = path.name.removesuffix(".motion3.json")
        if name not in clips:
            continue
        require(name not in motions, f"Duplicate motion ID {name}")
        data = read_json(path)
        require(data.get("Version") == 3 and data.get("Curves"), f"{path.name}: invalid Cubism motion")
        duration = len(clips[name]["frames"]) / FPS
        require(abs(data["Meta"]["Duration"] - duration) < 1e-3 and data["Meta"]["Fps"] == FPS,
                f"{name}: native and baked timing differ")
        sha = digest(path)
        require(sha == library["files"][name], f"{name}: native motion file differs from its library digest")
        motions[name] = dict(file=path.relative_to(args.motions).as_posix(), sha256=sha)
    require(motions.keys() == clips.keys(), "Baked clips without native motions: " + ", ".join(sorted(clips.keys() - motions.keys())))
    def records(paths):
        return [dict(file=p.name, sha256=digest(p)) for p in paths]
    core_report = args.bake / "core-model.txt"
    core_lines = core_report.read_text(encoding="utf-8").splitlines()
    require(len(core_lines) > 1 and core_lines[0].startswith("Cubism Core "), "Missing native Core model inspection")
    provenance = {
        "schema": "cutedog-cubism-bake-v1", "engine": "Live2D Cubism",
        "nativeCore": {"version": core_lines[0], "canvas": core_lines[1], "reportSha256": digest(core_report)},
        "modelDescriptor": records([args.model])[0], "moc": dict(file=refs["Moc"], sha256=digest(moc)),
        "textures": textures, "motions": motions, "motionLibrary": records([args.library])[0],
        "renderSources": records(args.renderer), "authoringSources": records(args.authoring),
        "travelSha256": digest(args.bake / "travel.json"),
        "travelMeasurementSha256": digest(args.bake / "travel-measurement.json"),
        "continuityReportSha256": digest(args.bake / "continuity.json"),
    }
    path = target / "native-provenance.json"
    write_json(path, provenance)
    return {
        "motionSourceSha256": state_sha([provenance["motionLibrary"], motions]), "rigSha256": digest(moc),
        "atlasSha256": state_sha(textures), "rendererSha256": state_sha(provenance["renderSources"]),
        "continuityReportSha256": provenance["continuityReportSha256"], "nativeProvenanceSha256": digest(path),
    }


def assemble(args, assets):
    bake = args.bake
    target = assets / "pet3d"
    target.mkdir()
    (target / "proofs").mkdir()
    graph = read_json(args.graph)
    base = {c["id"]: c for c in graph["clips"] if "phaseEntry" not in c}
    require(graph["initialClip"] == "stand" and len(base) == 29, "Incomplete ordinary motion graph")
    states = read_json(bake / "continuity.json")
    travel = read_json(bake / "travel.json")
    canonical = states["canonical"]
    canonical_hash = state_sha(canonical)
    clips, exits = {}, {}
    for name, template in base.items():
        paths = samples(bake, name)
        frames, hashes = put_clip_files(target, name, paths)
        distances = travel[name] if template["canTravel"] else travel.get(name, [0.0] * len(frames))
        require(len(distances) == len(frames) and all(math.isfinite(x) and 0 <= x <= 1 for x in distances),
                f"{name}: invalid projected travel")
        require(any(x > 0 for x in distances) == template["canTravel"], f"{name}: incorrect travel mode")
        clip = {key: template[key] for key in ("id", "entryPose", "exitPose", "loop", "interrupt",
                                                "transitions", "heading", "exitHeading", "canTravel")}
        clip.update(fps=FPS, sampling="interval_starts", frames=frames, frameSha256=hashes,
                    travelUnits="canvas_width_fraction", projectedTravel=distances)
        if matches_state(canonical, states["entries"][name]):
            clip["entryStateSha256"] = canonical_hash
        if matches_state(canonical, states["terminals"][name]):
            clip["exitStateSha256"] = canonical_hash
        exits[name] = digest(paths[-1])
        clips[name] = clip

    for name, clip in clips.items():
        for successor in clip["transitions"]:
            require(exits[name] == clips[successor]["frameSha256"][0],
                    f"Rendered boundary mismatch: {name} -> {successor}")
    canonical_frame = clips["stand"]["frameSha256"][0]
    direct = [name for name, clip in clips.items() if clip["entryPose"] == "stand_left"
              and clip.get("entryStateSha256") == canonical_hash and clip["frameSha256"][0] == canonical_frame]
    require("stand" in direct, "Missing canonical standing state")
    source_names = set(base)
    bindings = []
    for source_name in IDLE_SOURCES:
        source = clips[source_name]
        for anchor in range(6, len(source["frames"]) - 8, 6):
            name = f"{source_name}_exit_{anchor:03d}"
            source_names.add(name)
            paths = samples(bake, name)
            require(len(paths) == 9, f"{name}: expected eight recovery intervals")
            frames, hashes = put_clip_files(target, name, paths)
            require(hashes[0] == source["frameSha256"][anchor] and digest(paths[-1]) == canonical_frame,
                    f"{name}: rendered recovery endpoints differ")
            require(matches_state(states["sources"][name], states["entries"][name]),
                    f"{name}: entry position or velocity differs from the source")
            require(matches_state(canonical, states["terminals"][name]),
                    f"{name}: terminal position or velocity differs from standing")
            entry_hash = state_sha(states["sources"][name])
            terminal_path = f"pet3d/proofs/{name}.webp"
            shutil.copyfile(paths[-1], assets / terminal_path)
            terminal = dict(path=terminal_path, sha256=canonical_frame, stateSha256=canonical_hash)
            source_sha = content_sha(SIZE, SIZE, source)
            entry = dict(sourceClip=source_name, atFrame=anchor, sourceClipSha256=source_sha,
                         sourceFrameSha256=hashes[0], entryStateSha256=entry_hash)
            clip = dict(id=name, entryPose=f"phase_{source_name}_{anchor}", exitPose="stand_left",
                        fps=FPS, sampling="interval_starts", loop=False, interrupt="never",
                        transitions=direct, heading="left", exitHeading="left", canTravel=False,
                        frames=frames, frameSha256=hashes, travelUnits="canvas_width_fraction",
                        projectedTravel=[0.0] * len(frames), entryStateSha256=entry_hash,
                        exitStateSha256=canonical_hash, terminalProof=terminal, phaseEntry=entry)
            clips[name] = clip
            bindings.append(dict(bridge=name, sourceClip=source_name, atFrame=anchor,
                                 sourceClipSha256=source_sha, bridgeClipSha256=content_sha(SIZE, SIZE, clip),
                                 sourceFrameSha256=hashes[0], entryStateSha256=entry_hash,
                                 terminalFrameSha256=canonical_frame, exitStateSha256=canonical_hash))

    require(source_names == {p.name for p in bake.iterdir() if p.is_dir() and (p / "00000.webp").is_file()},
            "Unexpected or missing baked clips")
    inputs = bind_native_sources(args, clips, target)
    proof = dict(version=1, continuityOrder=1, inputs=inputs, bindings=bindings)
    proof_path = target / "phase-proof.json"
    write_json(proof_path, proof)
    proof_ref = dict(path="pet3d/phase-proof.json", sha256=digest(proof_path))
    for clip in clips.values():
        if "phaseEntry" in clip:
            clip["phaseEntry"]["proof"] = proof_ref
    manifest = dict(version=2, width=SIZE, height=SIZE, initialClip="stand", clips=list(clips.values()))
    validate_package(manifest, assets, proof)
    write_json(target / "manifest.json", manifest)
    require((target / "manifest.json").stat().st_size <= 2_000_000, "Manifest exceeds Android read limit")
    require(proof_path.stat().st_size <= 512 * 1024, "Phase proof exceeds Android read limit")
    return len(clips), len(bindings), sum(len(c["frames"]) for c in clips.values())


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("bake", type=Path, help="directory with Cubism WebP sequences, travel.json and continuity.json")
    cli.add_argument("--model", type=Path, required=True, help="native .model3.json with its MOC3 and texture files")
    cli.add_argument("--motions", type=Path, required=True, help="directory of action-ID.motion3.json files")
    cli.add_argument("--library", type=Path, required=True, help="motion library authoring metadata")
    cli.add_argument("--renderer", type=Path, action="append", required=True, help="native rendering program/source (repeatable)")
    cli.add_argument("--authoring", type=Path, action="append", default=[], help="editable project or authoring source (repeatable)")
    cli.add_argument("--graph", type=Path, default=ROOT / "art/live2d/motions/base-graph.json")
    cli.add_argument("--output", required=True, type=Path, help="new pet3d directory")
    args = cli.parse_args()
    require(not args.output.exists(), "Output must be a new directory")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".cutedog-", dir=args.output.parent) as tmp:
        stage = Path(tmp)
        totals = assemble(args, stage)
        (stage / "pet3d").rename(args.output)
    print(f"Validated {totals[0]} clips including {totals[1]} phase bridges and {totals[2]} playback frames")


if __name__ == "__main__":
    main()
