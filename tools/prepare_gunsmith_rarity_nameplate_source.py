"""Remove an image generator's baked light checkerboard from rarity plates.

This is a one-step source preparation utility.  It deliberately keeps only the
largest dark/coloured connected component in each configured row, fills enclosed
highlights, and writes a genuine RGBA source sheet for the deterministic runtime
texture generator.
"""

from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image


ROW_BANDS = (
    (180, 415),
    (430, 655),
    (680, 900),
    (920, 1140),
    (1160, 1385),
)


def largest_component(mask: np.ndarray) -> np.ndarray:
    height, width = mask.shape
    visited = np.zeros(mask.shape, dtype=bool)
    largest: list[tuple[int, int]] = []

    for y, x in zip(*np.nonzero(mask)):
        if visited[y, x]:
            continue
        component: list[tuple[int, int]] = []
        queue = deque([(int(y), int(x))])
        visited[y, x] = True
        while queue:
            cy, cx = queue.popleft()
            component.append((cy, cx))
            for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                if 0 <= ny < height and 0 <= nx < width and mask[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    queue.append((ny, nx))
        if len(component) > len(largest):
            largest = component

    result = np.zeros(mask.shape, dtype=bool)
    if largest:
        ys, xs = zip(*largest)
        result[np.asarray(ys), np.asarray(xs)] = True
    return result


def fill_holes(mask: np.ndarray) -> np.ndarray:
    height, width = mask.shape
    exterior = np.zeros(mask.shape, dtype=bool)
    queue: deque[tuple[int, int]] = deque()

    def add(y: int, x: int) -> None:
        if not mask[y, x] and not exterior[y, x]:
            exterior[y, x] = True
            queue.append((y, x))

    for x in range(width):
        add(0, x)
        add(height - 1, x)
    for y in range(height):
        add(y, 0)
        add(y, width - 1)

    while queue:
        cy, cx = queue.popleft()
        for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
            if 0 <= ny < height and 0 <= nx < width and not mask[ny, nx] and not exterior[ny, nx]:
                exterior[ny, nx] = True
                queue.append((ny, nx))
    return ~exterior


def isolate_plate(rgb: np.ndarray) -> np.ndarray:
    darkest = rgb.min(axis=2)
    chroma = rgb.max(axis=2) - darkest
    seed = (darkest < 238) | (chroma > 8)
    component = largest_component(seed)
    return fill_holes(component)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = Image.open(args.input).convert("RGB")
    rgb = np.asarray(source)
    alpha = np.zeros((source.height, source.width), dtype=np.uint8)

    for top, bottom in ROW_BANDS:
        plate = isolate_plate(rgb[top:bottom])
        alpha[top:bottom][plate] = 255

    rgba = np.dstack((rgb, alpha))
    rgba[alpha == 0, :3] = 0
    target = Image.fromarray(rgba, "RGBA")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    target.save(args.output, optimize=True)
    print(f"prepared {args.output}")


if __name__ == "__main__":
    main()
