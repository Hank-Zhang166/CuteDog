"""Remove remote traces of the concealed illustration from registered poses."""

import cv2
import numpy as np
from PIL import Image

REPAIRED_MATTES = {
    "belly_up",
    "scratch",
    "side_lie",
    "side_to_lie",
    "wake",
    "lick_paw",
    "lie",
    "lie_to_stand",
    "stretch",
    "turn",
    "turn_to_left",
    "curl",
    "uncurl",
    "fall_asleep",
    "groom",
    "wave",
}


def cleaned(image):
    """Limit the low-opacity secondary edge to the true foreground fur band.

    Do nothing when a frame contains only the isolated antialiasing speckles
    of the original painting. Pixels within six canvas pixels of the opaque
    coat, as well as all foreground detail and separate opaque paws, are kept.
    """
    rgba = np.asarray(image)
    a = rgba[:, :, 3]
    distance = cv2.distanceTransform((a < 100).astype("u1"), cv2.DIST_L2, 5)
    # A pale old outline can run parallel to the coat, only a few pixels
    # from it, separated by a transparent gap. Follow the actual alpha fringe
    # out from the opaque coat; a detached loop is not a strand of guard hair.
    linked = (a >= 88).astype("u1")
    fringe = (a >= 5).astype("u1")
    kernel = np.ones((3, 3), "u1")
    for _ in range(9):
        linked = cv2.dilate(linked, kernel) & fringe
    gap = cv2.distanceTransform(1 - linked, cv2.DIST_L2, 5)
    distant = (distance > 12) | (gap > 4)
    if np.count_nonzero((a >= 6) & distant) < 7:
        return None
    u = np.maximum(np.clip((distance - 6) / 6, 0, 1), np.clip((gap - 1.6) / 3, 0, 1))
    alpha = np.rint(a * (1 - u * u * (3 - 2 * u))).astype("u1")
    if np.array_equal(a, alpha):
        return None
    new = rgba.copy()
    new[:, :, 3] = alpha
    new[alpha == 0, :3] = 0
    return Image.fromarray(new)
