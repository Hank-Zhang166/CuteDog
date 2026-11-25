"""Interpolate registered coverage contours before compositing their artwork."""

import cv2
import numpy as np


def _distance(alpha):
    mask = (alpha > 0.5).astype("u1")
    d = cv2.distanceTransform(mask, cv2.DIST_L2, 5) - cv2.distanceTransform(
        1 - mask, cv2.DIST_L2, 5
    )
    edge = np.abs(d) <= 1.02
    d[edge] = (alpha[edge] - 0.5) * 1.8
    return d


def registered_mix(a, b, weight):
    """Mix premultiplied artwork with one moving visible boundary at occlusions."""
    w = np.broadcast_to(np.asarray(weight, dtype="f").squeeze(), a.shape[:2])
    aa, bb = a[:, :, 3], b[:, :, 3]
    coverage = (1 - w) * aa + w * bb
    d = (1 - w) * _distance(aa) + w * _distance(bb)
    contour = np.clip(0.5 + d, 0, 1)
    agreement = np.clip((np.abs(aa - bb) - 0.04) / 0.72, 0, 1)
    arrival = np.clip(np.minimum(w, 1 - w) / 0.075, 0, 1)
    factor = (agreement * agreement * (3 - 2 * agreement)) * (
        arrival * arrival * (3 - 2 * arrival)
    )
    alpha = coverage + (contour - coverage) * factor
    rgb = (
        a[:, :, :3] * (1 - w[:, :, None]) + b[:, :, :3] * w[:, :, None]
    ) / np.maximum(coverage[:, :, None], 1e-7)
    return np.dstack((rgb * alpha[:, :, None], alpha)).astype("f")
