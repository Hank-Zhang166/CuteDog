"""Measure final deformed, visible material in both hind limbs and the painted soles."""

import numpy as np, json
from locomotion import ROOT, Renderer, clips, LEG, solve_leg, FPS


def lengths(v):
    return np.abs(np.diff(v))


def positions(G, points):
    from scipy.ndimage import map_coordinates

    q = np.array([np.imag(points), np.real(points)]) / 4
    return map_coordinates(G.real, q, order=1) + 1j * map_coordinates(
        G.imag, q, order=1
    )


def flatten(z):
    return np.concatenate(z) if z else np.empty(0)


def bounds(v):
    return [round(float(np.min(v)), 5), round(float(np.max(v)), 5)]


def audit(names):
    rig = Renderer()
    states = clips()
    P = rig.P
    spacing = 4
    side = int(np.sqrt(len(P)))
    y, x = np.mgrid[:512:4, :512:4]
    result = {}
    for name, (frames, travel) in states.items():
        if names and name not in names:
            continue
        rec = {
            k: {
                "length": [],
                "width": [],
                "corejac": [],
                "shaftArea": [],
                "shaftS": [],
                "solejac": [],
                "soles": [],
                "maxDisplacement": [],
                "centerCoreLength": [],
                "patchArea": [],
                "rearBounds": [],
                "kneeReach": [],
                "scapula": [],
                "silWidth": [],
                "ribArc": [],
                "rigidPadMaterialChord": [],
                "skinJointChord": [],
                "ankleArea": [],
                "ankleSingular": [],
            }
            for k in LEG
        }
        support = []
        for i, state in enumerate(frames):
            xyz = rig.geometry(state)
            support.append(
                sum(LEG[k][-1].imag - state["legs"][k][0].imag < 0.21 for k in LEG)
            )
            for k, texture in [("hf", 0), ("ff", 1), ("hn", 2), ("fn", 2)]:
                r = rec[k]
                leg = LEG[k]
                J, _ = solve_leg(k, state["legs"][k], state["b"])
                r["length"].append(np.max(np.abs(lengths(J) - lengths(leg))))
                r["maxDisplacement"].append(J[-1].real - leg[-1].real)
                from locomotion import bodymap

                r["scapula"].append(abs(J[0] - bodymap(leg[0], state["b"])))
                r["kneeReach"].append(J[1].real - leg[1].real)
                z = xyz[texture].reshape(side, side)
                a = z[:-1, :-1]
                from locomotion import leg_profile

                mid0, rad = leg_profile(k)
                yy = np.arange(340, 405, 4, dtype=int)
                center = mid0[yy] + 1j * yy
                left = positions(z, center - rad[yy])
                right = positions(z, center + rad[yy])
                r["silWidth"].append(abs(right - left) / (2 * rad[yy]))
                cc = positions(z, center)
                r["ribArc"].append(float(lengths(cc).sum() / lengths(center).sum()))
                material = positions(z, np.asarray(LEG[k]))
                r["skinJointChord"].append(lengths(material) / lengths(LEG[k]))

                vx = (z[:-1, 1:] - a) / spacing
                vy = (z[1:, :-1] - a) / spacing
                det = (np.conj(vx) * vy).imag
                trace = np.abs(vx) ** 2 + np.abs(vy) ** 2
                big = np.sqrt(
                    (trace + np.sqrt(np.maximum(0, trace**2 - 4 * det**2))) * 0.5
                )
                small = np.abs(det) / np.maximum(big, 1e-8)
                alpha = rig.layers[texture][:, :, 3]
                vis = alpha[(y + 2).clip(0, 511), (x + 2).clip(0, 511)] > 180
                if k == "hn":
                    vis &= x > 273
                if k == "fn":
                    vis &= x < 258
                lo = 338 if k in ("hn", "fn") else 331
                lower = vis & (y >= lo) & (y <= leg[-1].imag - 7)
                r["width"].append(np.abs(vx[lower]))
                r["shaftArea"].append(det[lower])
                r["shaftS"].append(np.c_[small[lower], big[lower]])
                r["patchArea"].append(np.mean(det[lower]))
                # Two rigid cores straddling the hock, ignoring their curved crease; sole includes all opaque pad centers.
                sole = vis & (y >= leg[-1].imag - 20)
                ankle = (
                    vis
                    & (y >= max(368, leg[2].imag + 3 if k[0] == "h" else 378))
                    & (y < leg[-1].imag - 16)
                )
                r["ankleArea"].append(det[ankle])
                r["ankleSingular"].append(np.c_[small[ankle], big[ankle]])
                r["solejac"].append(det[sole])
                r["soles"].append(np.c_[small[sole], big[sole]])
                band = (
                    lower
                    & ((y + 4 < leg[2].imag - 15) | (y > leg[2].imag + 15))
                    & (y < leg[-1].imag - 42)
                )
                if np.any(band):
                    r["corejac"].append(det[band])
        legs = {}
        for k, r in rec.items():
            width = flatten(r["width"])
            shaft = flatten(r["shaftArea"])
            core = flatten(r["corejac"])
            sole = flatten(r["solejac"])
            sols = np.concatenate(r["soles"])
            ss = np.concatenate(r["shaftS"])
            legs[k] = dict(
                maxBoneLengthErrorPx=round(max(r["length"]), 10),
                rigidAnkleAreaRange=bounds(flatten(r["ankleArea"])),
                rigidAnkleSingularMinMax=bounds(np.concatenate(r["ankleSingular"])),
                distalIndividualUVEdgeScale=bounds(width),
                distalFullSilhouetteWidthRatio=bounds(flatten(r["silWidth"])),
                distalFurCenterlineArcLengthRatio=bounds(r["ribArc"]),
                materialJointChordMin=np.min(r["skinJointChord"], axis=0)
                .round(5)
                .tolist(),
                materialJointChordMax=np.max(r["skinJointChord"], axis=0)
                .round(5)
                .tolist(),
                materialPatchAreaFractionRange=bounds(r["patchArea"]),
                distalTriangleAreaMinMax=bounds(shaft),
                distalTriangleAreaP02P98=np.percentile(shaft, [2, 98])
                .round(5)
                .tolist(),
                shaftSingularMinMax=bounds(ss),
                shaftCoreTriangleAreaRange=bounds(core) if len(core) else None,
                pawAreaRange=bounds(sole),
                pawSingularMinMax=bounds(sols),
                foreAftToeRangePx=bounds(r["maxDisplacement"]),
                stifleOrElbowXRangePx=bounds(r["kneeReach"]),
                maxHipOrScapulaGlidePx=round(max(r["scapula"]), 3),
            )
        if name in ("walk", "trot", "chase") and min(support) < 2:
            raise AssertionError((name, "missing grounded pair"))
        for k, metrics in legs.items():
            for size in (
                "rigidAnkleAreaRange",
                "rigidAnkleSingularMinMax",
                "pawAreaRange",
                "pawSingularMinMax",
            ):
                if max(abs(v - 1.0) for v in metrics[size]) > 0.0001:
                    raise AssertionError((name, k, size, metrics[size]))
            if (
                metrics["maxBoneLengthErrorPx"] > 1e-7
                or min(metrics["distalTriangleAreaMinMax"]) <= 0
            ):
                raise AssertionError((name, k, "length/fold"))
            for dimension in (
                "distalFullSilhouetteWidthRatio",
                "distalFurCenterlineArcLengthRatio",
            ):
                if max(abs(v - 1.0) for v in metrics[dimension]) > 0.003:
                    raise AssertionError((name, k, dimension, metrics[dimension]))
        result[name] = dict(
            frames=len(frames),
            supportCountRange=[min(support), max(support)],
            flightFrames=sum(s == 0 for s in support),
            legs=legs,
        )
        print(
            name,
            "ground",
            min(support),
            "hf width/area/local",
            legs["hf"]["distalFullSilhouetteWidthRatio"],
            legs["hf"]["materialPatchAreaFractionRange"],
            legs["hf"]["distalTriangleAreaP02P98"],
            "bounds",
            legs["hf"]["foreAftToeRangePx"],
            flush=True,
        )
    report = {
        "fps": FPS,
        "units": "source 512 × 512 pixels",
        "procedure": "Length is measured on all three solved segments. Material statistics come from the renderer’s final deformed UV mesh after body relaxation, on original visible opaque texels (alpha > 180). The reference row’s silhouette endpoints verify full fur width. Individual UV edge scale measures redistribution within that row. The measured fur-centerline arc follows the same painted points after deformation. The signed determinant of the actual mesh measures local area; aggregated determinant across the whole marked patch measures apparent size independent of occlusion. Paw singular values measure shape and scale. The exact right-facing reflection has identical lengths, areas and singular values.",
        "clips": result,
    }
    (ROOT / "limb-metrics.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    import sys

    audit(sys.argv[1:])
