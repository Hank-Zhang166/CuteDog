"""Export editable jaw keyforms and sample the single yawn in the installed Cubism Core."""

import hashlib, json, os, shutil, subprocess, sys
from pathlib import Path
import numpy as np
from PIL import Image
from performance import HERE, SRC, REPO, smooth, straight
from yawn_repair import area, jawkeys, keys_at, plates, points, sit, tri, xx, yy

sys.path.insert(0, str(REPO / "tools/cubism"))
from render import bake


def floats(x):
    return list(np.asarray(x, dtype="<f4").tobytes())


def model(native):
    names = ["Seated", "JawOnset", "JawHalf", "JawOpen"]
    art = Image.new("RGBA", (2048, 2048))
    meshes = []
    parts = []
    phases = [0.0, 0.16, 0.44, 0.56, 0.84, 0.99, 1.0]
    keys = sorted(
        {
            round(float(lo + (hi - lo) * t), 7)
            for lo, hi in zip(jawkeys[:-1], jawkeys[1:])
            for t in phases
        }
    )

    def coordinates(v):
        k = min(2, max(0, int(np.searchsorted(jawkeys, v, "right") - 1)))
        u = np.clip((v - jawkeys[k]) / (jawkeys[k + 1] - jawkeys[k]), 0, 1)
        return points[k] * (1 - u) + points[k + 1] * u

    def opacity(j, v):
        if j > 0 and v < jawkeys[j]:
            u = (v - jawkeys[j - 1]) / (jawkeys[j] - jawkeys[j - 1])
            return float(smooth((u - 0.16) / 0.68))
        if j < 3:
            u = (v - jawkeys[j]) / (jawkeys[j + 1] - jawkeys[j])
            return 1.0 - float(smooth((u - 0.84) / 0.15))
        return 1.0

    vertices = np.array([coordinates(v) for v in keys])
    normal = area(points.mean(axis=0), tri)
    assert np.all(normal > 0) and all(np.all(area(v, tri) > 0) for v in points)
    for j, name in enumerate(names):
        x0 = 6 + 526 * (j % 2)
        y0 = 6 + 526 * (j // 2)
        art.paste(Image.fromarray(straight(plates()[j])), (x0, y0))
        coords = points[j]
        # Cubism Editor registers each art image in its default form, even for
        # invisible artwork. Give each hidden chart its own true UV rest shape.
        mesh_vertices = vertices.copy()
        mesh_vertices[0] = coords
        parts.append(
            dict(
                id="Part" + name,
                parent_part=None,
                visible=True,
                enabled=True,
                binding=dict(axes=[]),
                draw_orders=[500],
            )
        )
        meshes.append(
            dict(
                id=name,
                parent_part=j,
                parent_deformer=None,
                visible=True,
                enabled=True,
                texture=0,
                flags=4,
                vertex_count=len(coords),
                uvs=floats((coords + [x0, y0]) / 2048),
                indices=list(tri.astype("<u2").tobytes()),
                masks=[],
                binding=dict(axes=[dict(parameter=0, keys=keys)]),
                draw_orders=[100 + j] * len(keys),
                opacities=[opacity(j, v) for v in keys],
                positions=floats((mesh_vertices - 256) / 512),
            )
        )
    art.save(native / "Yawn.png")
    spec = dict(
        canvas=dict(pixels_per_unit=512, origin_x=256, origin_y=256, width=512, height=512),
        parameters=[
            dict(
                id="ParamJawOpen",
                min=0,
                max=1,
                default=0,
                repeat=False,
                decimals=3,
                blend_shape=False,
            )
        ],
        parts=parts,
        art_meshes=meshes,
        deformers=[],
        glues=[],
        draw_groups=[],
    )
    for n, obj in [
        ("rig", spec),
        ("textures", ["Yawn.png"]),
        (
            "Yawn.model3",
            dict(Version=3, FileReferences=dict(Moc="CuteDog.moc3", Textures=["Yawn.png"])),
        ),
    ]:
        (native / (n + ".json")).write_text(json.dumps(obj, separators=(",", ":")), encoding="utf8")


def render_native(native, dest):
    native.mkdir(parents=True, exist_ok=True)
    dest.mkdir(parents=True, exist_ok=True)
    model(native)
    exporter = os.environ.get("CUBISM_REVIEW_EXPORTER")
    if exporter:
        subprocess.run([exporter, str(native)], check=True)
    else:
        assert (
            hashlib.sha256((native / "rig.json").read_bytes()).hexdigest()
            == (SRC / "yawn-model/rig.sha256").read_text().strip()
        )
        shutil.copyfile(SRC / "yawn-model/CuteDog.moc3", native / "CuteDog.moc3")
    core = Path(os.environ.get("CUBISM_APP", "C:/Program Files/Live2D Cubism 5.3/app"))
    local = native / "java"
    local.mkdir(exist_ok=True)
    jdk = Path(os.environ.get("JAVA_HOME", "C:/tools/jdk17/jdk-17.0.2"))
    jar = core / "lib/Live2DCubismCore.jar"
    subprocess.run(
        [
            str(jdk / "bin/javac.exe"),
            "--release",
            "17",
            "-cp",
            str(jar),
            "-d",
            str(local),
            str(REPO / "tools/cubism/CoreBake.java"),
        ],
        check=True,
    )
    keys = [round(keys_at(i), 7) for i in range(65)]
    vals = native / "values.tsv"
    vals.write_text("ParamJawOpen\n" + "".join(f"{k}\n" for k in keys), encoding="utf8")
    expr = [0, keys[0]]
    for i, k in enumerate(keys[1:], 1):
        expr.extend([0, round(i / 24, 8), k])
    (native / "yawn.motion3.json").write_text(
        json.dumps(
            dict(
                Version=3,
                Meta=dict(
                    Duration=64 / 24,
                    Fps=24,
                    Loop=False,
                    AreBeziersRestricted=True,
                    CurveCount=1,
                    TotalSegmentCount=64,
                    TotalPointCount=65,
                    UserDataCount=0,
                    TotalUserDataSize=0,
                    FadeInTime=0,
                    FadeOutTime=0,
                ),
                Curves=[dict(Target="Parameter", Id="ParamJawOpen", Segments=expr)],
            ),
            separators=(",", ":"),
        ),
        encoding="utf8",
    )
    with (native / "core.log").open("w") as f:
        subprocess.run(
            [
                str(core / "jre/bin/java.exe"),
                "-Djava.library.path=" + str(core / "dll64"),
                "-cp",
                os.pathsep.join([str(local), str(jar)]),
                "CoreBake",
                str(native / "CuteDog.moc3"),
                str(vals),
                str(native),
            ],
            check=True,
            stdout=f,
        )
    bake(
        native / "Yawn.model3.json",
        native,
        native / "frames",
        sharp_meshes={"Seated", "JawOnset", "JawHalf", "JawOpen"},
    )
    for i, k in enumerate(keys):
        file = native / "frames" / f"{i:05}.webp"
        v = np.array(Image.open(file).convert("RGBA"))
        if i in (0, 64) or k < 0.000001:
            v = sit.copy()
        else:
            stable = (yy >= 278) | (xx >= 278)
            v[stable] = sit[stable]
        Image.fromarray(v).save(dest / f"{i:05}.webp", lossless=True, exact=True, method=1)
    (dest / "motion.json").write_text(
        json.dumps(
            dict(
                fps=24,
                frameCount=64,
                terminalSample=True,
                entry="Sit05",
                exit="Sit05",
                poses=[
                    ["Sit05", 0],
                    ["YawnOnset", 15],
                    ["YawnHalf", 22],
                    ["YawnFull", 36],
                    ["Sit05", 60],
                ],
                jawOpen=keys,
            )
        ),
        encoding="utf8",
    )


if __name__ == "__main__":
    render_native(HERE / "yawn-native", HERE / "yawn")
