"""Generate the WOK Chef GeckoLib workstation model, animations, tier atlases, and QA preview."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw


UV = {
    "tier": [0, 0],
    "tier_dark": [0, 40],
    "accent": [0, 80],
    "metal": [104, 0],
    "dark_metal": [176, 0],
    "pot": [104, 72],
    "wood": [176, 72],
    "red": [104, 144],
    "yellow": [148, 144],
    "green": [192, 144],
    "food": [104, 188],
    "cloth": [148, 188],
    "fire": [192, 188],
    "steam": [224, 188],
    "savory": [0, 120],
    "sweet": [24, 120],
    "oily": [48, 120],
    "sour": [72, 120],
    "spicy": [0, 148],
    "aromatic": [24, 148],
    "complex": [48, 148],
    "glass": [72, 148],
    "board": [0, 176],
    "board_dark": [0, 216],
    "blade": [48, 176],
}


def cube(origin: tuple[float, float, float], size: tuple[float, float, float], material: str,
         *, inflate: float | None = None, pivot: tuple[float, float, float] | None = None,
         rotation: tuple[float, float, float] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"origin": list(origin), "size": list(size), "uv": UV[material]}
    if inflate is not None:
        result["inflate"] = inflate
    if pivot is not None:
        result["pivot"] = list(pivot)
    if rotation is not None:
        result["rotation"] = list(rotation)
    result["_material"] = material
    return result


def bone(name: str, cubes: list[dict[str, Any]], *, parent: str | None = None,
         pivot: tuple[float, float, float] = (0, 0, 0),
         rotation: tuple[float, float, float] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"name": name, "pivot": list(pivot), "cubes": cubes}
    if parent is not None:
        result["parent"] = parent
    if rotation is not None:
        result["rotation"] = list(rotation)
    return result


def bottle_cubes(x: float, y: float, z: float, width: float, depth: float, height: float,
                 material: str, cap_material: str) -> list[dict[str, Any]]:
    shoulder_y = y + height - 0.72
    return [
        cube((x, y, z), (width, 0.18, depth), "glass"),
        cube((x + 0.08, y + 0.18, z + 0.08), (width - 0.16, height - 0.9, depth - 0.16), material),
        cube((x + 0.02, shoulder_y, z + 0.02), (width - 0.04, 0.28, depth - 0.04), "glass"),
        cube((x + 0.16, shoulder_y + 0.08, z + 0.14), (width - 0.32, 0.35, depth - 0.28), material),
        cube((x + width * 0.32, shoulder_y + 0.38, z + depth * 0.3),
             (width * 0.36, 0.52, depth * 0.4), "glass"),
        cube((x + width * 0.28, shoulder_y + 0.9, z + depth * 0.26),
             (width * 0.44, 0.3, depth * 0.48), cap_material),
        cube((x + 0.14, y + 0.55, z - 0.1), (width - 0.28, 0.68, 0.16), "cloth"),
        cube((x + 0.27, y + 0.79, z - 0.15), (width - 0.54, 0.18, 0.08), material),
        cube((x + 0.1, y + 0.3, z - 0.06), (0.12, height - 1.1, 0.1), "glass"),
        cube((x - 0.02, y + 0.28, z + 0.16), (0.13, height - 1.08, depth - 0.32), "glass",
             pivot=(x + 0.045, y + height / 2, z + depth / 2), rotation=(0, -18, 0)),
        cube((x + width - 0.11, y + 0.28, z + 0.16), (0.13, height - 1.08, depth - 0.32), "glass",
             pivot=(x + width - 0.045, y + height / 2, z + depth / 2), rotation=(0, 18, 0)),
        cube((x + width * 0.24, shoulder_y + 0.8, z + depth * 0.22),
             (width * 0.52, 0.1, depth * 0.56), "dark_metal"),
        cube((x + width * 0.25, shoulder_y + 1.2, z + depth * 0.23),
             (width * 0.5, 0.08, depth * 0.54), "glass"),
        cube((x + width * 0.44, y + 0.64, z - 0.19), (width * 0.12, 0.42, 0.06), material),
    ]


def workstation_bones() -> list[dict[str, Any]]:
    structure = [
        cube((-15.5, 0, -7.25), (14.5, 8, 14.5), "tier_dark"),
        cube((1, 0, -7.25), (14.5, 8, 14.5), "tier_dark"),
        cube((-16, 8, -8), (32, 3, 16), "tier"),
        cube((-16, 7.4, -8.2), (32, 0.8, 1.2), "accent"),
        cube((-16, 7.4, 7), (32, 0.8, 1.2), "tier_dark"),
        cube((-16, 11, 6.5), (32, 6.5, 1.5), "tier"),
        cube((-16, 17.5, 6.2), (32, 1, 1.8), "accent"),
        cube((-15.5, -0.5, -7.5), (2, 1, 2), "metal"),
        cube((-15.5, -0.5, 5.5), (2, 1, 2), "metal"),
        cube((13.5, -0.5, -7.5), (2, 1, 2), "metal"),
        cube((13.5, -0.5, 5.5), (2, 1, 2), "metal"),
        cube((-14.5, 1, -7.6), (12.5, 5.5, 0.5), "dark_metal"),
        cube((-13.5, 2, -7.9), (10.5, 3.5, 0.35), "metal"),
        cube((2, 1, -7.6), (12.5, 2.5, 0.5), "tier"),
        cube((2, 4, -7.6), (12.5, 2.5, 0.5), "tier"),
        cube((4, 2, -8.15), (8.5, 0.35, 0.35), "metal"),
        cube((4, 5, -8.15), (8.5, 0.35, 0.35), "metal"),
        cube((-1, 0.75, -6), (2, 6.5, 12), "metal"),
        cube((5.5, 14, 3.5), (9.5, 1, 3), "tier_dark"),
        cube((5.35, 14.9, 4.75), (9.8, 0.45, 1.65), "wood"),
        cube((5.2, 14.9, 3.2), (10.1, 0.25, 0.3), "metal"),
        cube((5.2, 15.15, 6.25), (10.1, 0.25, 0.3), "metal"),
        cube((5.25, 15, 4.65), (0.25, 1.05, 1.9), "dark_metal"),
        cube((15, 15, 4.65), (0.25, 1.05, 1.9), "dark_metal"),
        cube((5.5, 14, 3.1), (1, 3.5, 0.5), "metal"),
        cube((14, 14, 3.1), (1, 3.5, 0.5), "metal"),
        cube((-1, 12.2, 5.8), (2, 4.2, 0.35), "metal"),
        cube((2.2, 12.2, 5.8), (2, 4.2, 0.35), "metal"),
        cube((-0.5, 11.5, -8.25), (5.5, 0.25, 0.25), "metal"),
        cube((5, 8.5, -8.2), (6, 3.5, 0.3), "cloth"),
        # 台面使用规整包边和转角护条提升层次，主体仍保持大面积纯色。
        cube((-15.8, 10.62, -8.15), (31.6, 0.38, 0.42), "tier_dark"),
        cube((-16.16, 8.35, -8.05), (0.32, 2.3, 16.1), "accent"),
        cube((15.84, 8.35, -8.05), (0.32, 2.3, 16.1), "accent"),
        cube((-15.72, 7.08, -8.28), (31.44, 0.26, 0.28), "tier_dark"),
        # 左柜门压框、铰链、把手座和把手。
        cube((-14.85, 0.7, -8.02), (0.52, 5.9, 0.28), "tier"),
        cube((-2.67, 0.7, -8.02), (0.52, 5.9, 0.28), "tier"),
        cube((-14.85, 0.7, -8.02), (12.7, 0.48, 0.28), "tier"),
        cube((-14.85, 6.12, -8.02), (12.7, 0.48, 0.28), "tier"),
        cube((-13.92, 1.55, -8.2), (0.48, 0.82, 0.22), "dark_metal"),
        cube((-13.92, 4.72, -8.2), (0.48, 0.82, 0.22), "dark_metal"),
        cube((-4.72, 2.73, -8.24), (0.54, 0.42, 0.22), "dark_metal"),
        cube((-4.72, 4.15, -8.24), (0.54, 0.42, 0.22), "dark_metal"),
        cube((-4.62, 3.05, -8.42), (0.34, 1.2, 0.3), "metal"),
        # 右侧双抽屉增加内框、滑轨分界和带端帽的把手。
        cube((2.25, 0.72, -8.0), (0.42, 5.8, 0.26), "tier_dark"),
        cube((13.83, 0.72, -8.0), (0.42, 5.8, 0.26), "tier_dark"),
        cube((2.25, 3.42, -8.04), (12, 0.28, 0.28), "tier_dark"),
        cube((3.62, 1.78, -8.3), (0.48, 0.62, 0.24), "dark_metal"),
        cube((12.38, 1.78, -8.3), (0.48, 0.62, 0.24), "dark_metal"),
        cube((3.62, 4.78, -8.3), (0.48, 0.62, 0.24), "dark_metal"),
        cube((12.38, 4.78, -8.3), (0.48, 0.62, 0.24), "dark_metal"),
        cube((3.82, 2.02, -8.42), (8.84, 0.22, 0.22), "metal"),
        cube((3.82, 5.02, -8.42), (8.84, 0.22, 0.22), "metal"),
        # 中央承重柱铆钉、前踢脚板以及分层脚座。
        cube((-0.48, 5.8, -8.14), (0.46, 0.46, 0.24), "dark_metal"),
        cube((0.52, 5.8, -8.14), (0.46, 0.46, 0.24), "dark_metal"),
        cube((-14.7, 0.08, -7.82), (12.3, 0.55, 0.32), "tier"),
        cube((2.4, 0.08, -7.82), (12.3, 0.55, 0.32), "tier"),
        cube((-15.72, -0.72, -7.72), (2.44, 0.28, 2.44), "dark_metal"),
        cube((-15.72, -0.72, 5.28), (2.44, 0.28, 2.44), "dark_metal"),
        cube((13.28, -0.72, -7.72), (2.44, 0.28, 2.44), "dark_metal"),
        cube((13.28, -0.72, 5.28), (2.44, 0.28, 2.44), "dark_metal"),
    ]
    for index, material in enumerate(("savory", "sweet", "oily", "sour", "spicy", "aromatic", "complex")):
        structure.append(cube((5.75 + index * 1.35, 15.15, 3.05), (0.55, 0.28, 0.12), material))
    burner = [
        cube((-14, 11, -6), (13, 0.7, 10), "dark_metal"),
        cube((-12.5, 11.65, -4.5), (10, 0.25, 7), "metal"),
        cube((-12, 11.8, -1.35), (9, 0.35, 0.7), "pot"),
        cube((-7.85, 11.8, -5), (0.7, 0.35, 8), "pot"),
        cube((-10.5, 11.8, -3.8), (7, 0.35, 0.65), "pot",
             pivot=(-7, 12, -1), rotation=(0, 45, 0)),
        cube((-10.5, 11.8, -3.8), (7, 0.35, 0.65), "pot",
             pivot=(-7, 12, -1), rotation=(0, -45, 0)),
        cube((-13.2, 8.9, -8.35), (1.6, 1.6, 0.7), "red"),
        cube((-9.8, 8.9, -8.35), (1.6, 1.6, 0.7), "yellow"),
        cube((-6.4, 8.9, -8.35), (1.6, 1.6, 0.7), "green"),
        cube((-3, 8.9, -8.35), (1.6, 1.6, 0.7), "metal"),
    ]
    pot = [
        cube((-12, 12, -5), (10, 1, 8), "pot"),
        cube((-12.2, 13, -5.2), (0.9, 4.2, 8.4), "pot"),
        cube((-2.7, 13, -5.2), (0.9, 4.2, 8.4), "pot"),
        cube((-11.3, 13, -5.2), (8.6, 4.2, 0.9), "pot"),
        cube((-11.3, 13, 2.3), (8.6, 4.2, 0.9), "pot"),
        cube((-12.4, 16.8, -5.4), (10.8, 0.45, 0.45), "metal"),
        cube((-12.4, 16.8, 2.95), (10.8, 0.45, 0.45), "metal"),
        cube((-12.4, 16.8, -4.95), (0.45, 0.45, 7.9), "metal"),
        cube((-2.05, 16.8, -4.95), (0.45, 0.45, 7.9), "metal"),
        cube((-14, 14.2, -2.5), (2, 0.7, 3), "metal"),
        cube((-15, 14.2, -2.15), (1.2, 0.7, 2.3), "dark_metal"),
        cube((-2, 14.2, -2.5), (2, 0.7, 3), "metal"),
        cube((0, 14.2, -2.15), (1.2, 0.7, 2.3), "dark_metal"),
        cube((-11.2, 16.3, -4.2), (8.4, 0.25, 6.4), "food"),
    ]
    lid = [
        cube((-11.5, 17.15, -4.5), (9, 0.45, 7), "metal"),
        cube((-10, 17.6, -3), (6, 0.35, 4), "dark_metal"),
        cube((-7.8, 17.95, -1.8), (1.6, 1.2, 1.6), "accent"),
    ]
    ladle = [
        cube((-7.3, 15.8, -1.3), (0.6, 7.5, 0.6), "metal"),
        cube((-8.2, 14.5, -2.2), (2.4, 1, 2.4), "dark_metal"),
    ]
    prep = [
        # 多层厚案板：深色底托、包边、导汁槽、握孔与有方向的刀痕。
        cube((0, 11, -6), (10, 0.28, 8), "board_dark"),
        cube((0.18, 11.28, -5.82), (9.64, 0.5, 7.64), "board"),
        cube((0.42, 11.78, -5.58), (9.16, 0.13, 7.16), "board"),
        cube((0.18, 11.34, -5.92), (9.64, 0.24, 0.16), "board_dark"),
        cube((0.18, 11.34, 1.76), (9.64, 0.24, 0.16), "board_dark"),
        cube((0.08, 11.34, -5.82), (0.16, 0.24, 7.64), "board_dark"),
        cube((9.76, 11.34, -5.82), (0.16, 0.24, 7.64), "board_dark"),
        cube((0.82, 11.91, -5.18), (7.58, 0.045, 0.09), "board_dark"),
        cube((0.82, 11.91, 1.08), (7.58, 0.045, 0.09), "board_dark"),
        cube((0.82, 11.91, -5.18), (0.09, 0.045, 6.35), "board_dark"),
        cube((8.31, 11.91, -5.18), (0.09, 0.045, 6.35), "board_dark"),
        cube((8.63, 11.925, -1.18), (0.56, 0.05, 1.46), "board_dark"),
        cube((8.76, 11.955, -1.01), (0.3, 0.035, 1.12), "dark_metal"),
        cube((1.9, 11.93, -2.1), (1.85, 0.035, 0.055), "board_dark",
             pivot=(2.82, 11.95, -2.07), rotation=(0, 18, 0)),
        cube((2.35, 11.94, -1.68), (1.35, 0.035, 0.05), "board_dark",
             pivot=(3.02, 11.96, -1.66), rotation=(0, -23, 0)),
        cube((3.02, 11.945, -2.45), (1.05, 0.035, 0.05), "board_dark",
             pivot=(3.54, 11.96, -2.42), rotation=(0, 31, 0)),
        cube((0.42, 10.94, -5.35), (1.2, 0.16, 1.1), "board_dark"),
        cube((8.38, 10.94, 0.25), (1.2, 0.16, 1.1), "board_dark"),
        cube((10.7, 11, -5.5), (4.2, 0.45, 7), "metal"),
        cube((11.1, 11.45, -5.1), (3.4, 0.35, 6.2), "metal"),
        cube((11.5, 11.8, -4.7), (2.6, 0.3, 5.4), "metal"),
        cube((10.8, 11, 1.8), (4.2, 1, 3.8), "wood"),
        cube((11.4, 12, 2.4), (3, 1.2, 2.6), "pot"),
        cube((12.1, 13.2, 3), (1.6, 0.5, 1.4), "food"),
    ]
    knife = [
        # 主厨刀保留原动画骨骼，并拆出收尖刀身、刃线、刀脊、护手和铆接全柄。
        cube((5.15, 12.18, -3.72), (4.52, 0.18, 1.34), "blade"),
        cube((4.62, 12.2, -3.61), (1.18, 0.16, 1.02), "blade",
             pivot=(5.48, 12.28, -3.1), rotation=(0, 28, 0)),
        cube((5.42, 12.36, -3.72), (4.22, 0.09, 0.16), "dark_metal"),
        cube((4.9, 12.14, -2.48), (4.72, 0.08, 0.13), "metal",
             pivot=(7.26, 12.18, -2.42), rotation=(0, -2, 0)),
        cube((5.55, 12.24, -2.61), (3.95, 0.08, 0.16), "metal"),
        cube((9.48, 12.06, -3.83), (0.42, 0.58, 1.56), "metal"),
        cube((9.78, 12.16, -3.69), (0.62, 0.34, 1.28), "dark_metal"),
        cube((10.02, 12.08, -3.82), (2.92, 0.16, 1.48), "dark_metal"),
        cube((10.08, 12.24, -3.74), (2.72, 0.5, 1.32), "wood"),
        cube((10.28, 12.7, -3.58), (2.32, 0.1, 1), "board_dark"),
        cube((10.28, 12.13, -3.58), (2.32, 0.1, 1), "board_dark"),
        cube((10.43, 12.79, -3.42), (0.22, 0.08, 0.22), "metal"),
        cube((11.33, 12.79, -3.42), (0.22, 0.08, 0.22), "metal"),
        cube((12.2, 12.79, -3.42), (0.22, 0.08, 0.22), "metal"),
        cube((12.78, 12.15, -3.76), (0.36, 0.58, 1.36), "metal"),
        cube((13.08, 12.27, -3.61), (0.18, 0.34, 1.06), "dark_metal"),
    ]
    seasoning_bones = [
        bone("seasoning_savory", [
            cube((5.58, 15.35, 4.88), (1.58, 0.18, 1.34), "glass"),
            cube((5.68, 15.53, 4.98), (1.38, 1.18, 1.14), "savory"),
            cube((5.56, 16.71, 4.86), (1.62, 0.22, 1.38), "glass"),
            cube((5.72, 16.93, 5.02), (1.3, 0.22, 1.06), "savory"),
            cube((5.48, 17.15, 4.8), (1.78, 0.3, 1.5), "metal"),
            cube((5.92, 15.82, 4.63), (0.9, 0.62, 0.18), "cloth"),
            cube((6.15, 16.03, 4.59), (0.44, 0.16, 0.08), "savory"),
            cube((6.92, 16.95, 5.18), (0.16, 1.1, 0.16), "metal",
                 pivot=(7, 16.95, 5.26), rotation=(0, 0, -24)),
            cube((5.6, 15.7, 5.04), (0.12, 0.9, 0.98), "glass",
                 pivot=(5.66, 16.15, 5.53), rotation=(0, -18, 0)),
            cube((7.02, 15.7, 5.04), (0.12, 0.9, 0.98), "glass",
                 pivot=(7.08, 16.15, 5.53), rotation=(0, 18, 0)),
            cube((5.62, 17.05, 4.94), (1.5, 0.1, 1.22), "dark_metal"),
            cube((6.31, 15.92, 4.53), (0.12, 0.42, 0.06), "dark_metal"),
        ], parent="workstation", pivot=(6.37, 15.35, 5.55)),
        bone("seasoning_sweet",
             bottle_cubes(7.92, 15.35, 4.95, 1.42, 1.16, 2.15, "sweet", "wood") + [
                 cube((8.53, 16.02, 4.74), (0.2, 0.2, 0.06), "sweet",
                      pivot=(8.63, 16.12, 4.77), rotation=(0, 0, 45)),
             ],
             parent="workstation", pivot=(8.63, 15.35, 5.53)),
        bone("seasoning_oily",
             bottle_cubes(10.32, 15.35, 4.98, 1.24, 1.1, 2.5, "oily", "wood") + [
                 cube((10.76, 18.33, 5.32), (0.36, 0.28, 0.34), "metal"),
                 cube((10.76, 16.02, 4.77), (0.1, 0.36, 0.06), "oily"),
                 cube((10.99, 15.9, 4.77), (0.1, 0.48, 0.06), "oily"),
             ], parent="workstation", pivot=(10.94, 15.35, 5.53)),
        bone("seasoning_sour",
             bottle_cubes(12.68, 15.35, 4.95, 1.36, 1.16, 2.25, "sour", "metal") + [
                 cube((13.26, 15.96, 4.74), (0.12, 0.42, 0.06), "sour",
                      pivot=(13.32, 16.17, 4.77), rotation=(0, 0, 35)),
                 cube((13.26, 15.96, 4.73), (0.12, 0.42, 0.06), "sour",
                      pivot=(13.32, 16.17, 4.76), rotation=(0, 0, -35)),
             ],
             parent="workstation", pivot=(13.36, 15.35, 5.53)),
        bone("seasoning_spicy", [
            cube((6.68, 15, 3.53), (1.64, 0.18, 1.24), "glass"),
            cube((6.78, 15.18, 3.63), (1.44, 1.18, 1.04), "spicy"),
            cube((6.7, 16.36, 3.55), (1.6, 0.22, 1.2), "glass"),
            cube((6.85, 16.58, 3.67), (1.3, 0.25, 0.96), "spicy"),
            cube((6.62, 16.83, 3.47), (1.76, 0.34, 1.36), "metal"),
            cube((6.96, 15.52, 3.3), (1.08, 0.62, 0.18), "cloth"),
            cube((7.2, 15.74, 3.26), (0.6, 0.16, 0.08), "spicy"),
            cube((6.92, 17.17, 3.75), (0.12, 0.08, 0.12), "dark_metal"),
            cube((7.18, 17.17, 4.08), (0.12, 0.08, 0.12), "dark_metal"),
            cube((7.7, 17.17, 3.82), (0.12, 0.08, 0.12), "dark_metal"),
            cube((6.7, 15.35, 3.7), (0.12, 0.86, 0.9), "glass",
                 pivot=(6.76, 15.78, 4.15), rotation=(0, -18, 0)),
            cube((8.2, 15.35, 3.7), (0.12, 0.86, 0.9), "glass",
                 pivot=(8.26, 15.78, 4.15), rotation=(0, 18, 0)),
            cube((6.74, 16.74, 3.58), (1.52, 0.09, 1.14), "dark_metal"),
            cube((7.48, 15.62, 3.24), (0.1, 0.38, 0.06), "dark_metal"),
        ], parent="workstation", pivot=(7.5, 15, 4.15)),
        bone("seasoning_aromatic", [
            cube((9.03, 15, 3.53), (1.64, 0.18, 1.24), "glass"),
            cube((9.13, 15.18, 3.63), (1.44, 1.24, 1.04), "aromatic"),
            cube((9.05, 16.42, 3.55), (1.6, 0.2, 1.2), "glass"),
            cube((9.24, 16.62, 3.7), (1.22, 0.28, 0.9), "aromatic"),
            cube((8.97, 16.9, 3.47), (1.76, 0.3, 1.36), "wood"),
            cube((9.05, 16.78, 3.42), (1.6, 0.12, 0.12), "cloth"),
            cube((9.33, 15.54, 3.3), (1.04, 0.62, 0.18), "cloth"),
            cube((9.42, 15.42, 3.46), (0.26, 0.38, 0.18), "aromatic",
                 pivot=(9.55, 15.61, 3.55), rotation=(0, 0, 28)),
            cube((9.9, 15.8, 3.46), (0.24, 0.42, 0.18), "aromatic",
                 pivot=(10.02, 16.01, 3.55), rotation=(0, 0, -24)),
            cube((9.05, 15.35, 3.7), (0.12, 0.9, 0.9), "glass",
                 pivot=(9.11, 15.8, 4.15), rotation=(0, -18, 0)),
            cube((10.55, 15.35, 3.7), (0.12, 0.9, 0.9), "glass",
                 pivot=(10.61, 15.8, 4.15), rotation=(0, 18, 0)),
            cube((9.14, 16.64, 3.58), (1.42, 0.1, 1.08), "cloth"),
            cube((9.78, 15.68, 3.24), (0.18, 0.28, 0.06), "aromatic",
                 pivot=(9.87, 15.82, 3.27), rotation=(0, 0, 45)),
        ], parent="workstation", pivot=(9.85, 15, 4.15)),
        bone("seasoning_complex", [
            cube((11.48, 15, 3.6), (1.74, 0.2, 1.1), "metal"),
            cube((11.38, 15.2, 3.5), (1.94, 0.3, 1.3), "complex"),
            cube((11.3, 15.5, 3.4), (2.1, 0.72, 0.22), "complex"),
            cube((11.3, 15.5, 4.68), (2.1, 0.72, 0.22), "complex"),
            cube((11.3, 15.5, 3.62), (0.22, 0.72, 1.06), "complex"),
            cube((13.18, 15.5, 3.62), (0.22, 0.72, 1.06), "complex"),
            cube((11.2, 16.22, 3.3), (2.3, 0.22, 1.7), "metal"),
            cube((11.55, 16.3, 3.65), (1.6, 0.16, 1), "spicy"),
            cube((11.02, 15.7, 3.82), (0.28, 0.28, 0.66), "metal"),
            cube((13.4, 15.7, 3.82), (0.28, 0.28, 0.66), "metal"),
            cube((11.72, 16.46, 3.82), (0.28, 0.18, 0.24), "oily"),
            cube((12.18, 16.46, 4.16), (0.34, 0.2, 0.28), "aromatic"),
            cube((12.78, 16.38, 4.08), (0.16, 1.38, 0.16), "metal",
                 pivot=(12.86, 16.38, 4.16), rotation=(0, 0, -31)),
            cube((12.57, 17.48, 3.92), (0.46, 0.16, 0.42), "metal",
                 pivot=(12.8, 17.56, 4.13), rotation=(0, 0, -31)),
            cube((12.05, 16.47, 3.88), (0.5, 0.08, 0.1), "complex",
                 pivot=(12.3, 16.51, 3.93), rotation=(0, 20, 0)),
            cube((12.38, 16.47, 4.32), (0.46, 0.08, 0.1), "oily",
                 pivot=(12.61, 16.51, 4.37), rotation=(0, -24, 0)),
        ], parent="workstation", pivot=(12.35, 15, 4.15)),
    ]
    fire_core = [
        cube((-10.5, 11.7, -3.2), (2, 2.8, 1.2), "fire", pivot=(-7, 12, -1), rotation=(0, 25, 0)),
        cube((-7.8, 11.7, -2.1), (1.6, 3.6, 1.2), "fire", pivot=(-7, 12, -1), rotation=(0, -20, 0)),
        cube((-5, 11.7, -3), (1.8, 2.6, 1.2), "fire", pivot=(-7, 12, -1), rotation=(0, 35, 0)),
    ]
    fire_outer = [
        cube((-10, 11.6, 0.2), (1.4, 2.2, 1), "fire", pivot=(-7, 12, -1), rotation=(0, -35, 0)),
        cube((-5.7, 11.6, 0), (1.4, 2.4, 1), "fire", pivot=(-7, 12, -1), rotation=(0, 40, 0)),
    ]
    return [
        bone("workstation", structure),
        bone("burner", burner, parent="workstation", pivot=(-7, 12, -1)),
        bone("fire_core", fire_core, parent="burner", pivot=(-7, 12, -1)),
        bone("fire_outer", fire_outer, parent="burner", pivot=(-7, 12, -1)),
        bone("pot", pot, parent="workstation", pivot=(-7, 12, -1)),
        bone("pot_lid", lid, parent="pot", pivot=(-7, 17.2, -1)),
        bone("ladle", ladle, parent="pot", pivot=(-7, 16, -1), rotation=(0, 0, -18)),
        bone("steam_left", [cube((-9.3, 18.3, -2.2), (1.1, 4.5, 1.1), "steam")],
             parent="pot", pivot=(-8.75, 18.3, -1.65)),
        bone("steam_right", [cube((-5.8, 18.8, 0.2), (1, 4, 1), "steam")],
             parent="pot", pivot=(-5.3, 18.8, 0.7)),
        bone("prep", prep, parent="workstation"),
        bone("knife", knife, parent="prep", pivot=(9.8, 12.4, -3.1), rotation=(0, -8, 0)),
        *seasoning_bones,
    ]


def animation_data() -> dict[str, Any]:
    hidden = {"scale": {"vector": [0, 0, 0]}}
    seasoning_animation = {}
    for index, name in enumerate(("seasoning_savory", "seasoning_sweet", "seasoning_oily",
                                  "seasoning_sour", "seasoning_spicy", "seasoning_aromatic",
                                  "seasoning_complex")):
        start = index * 0.3
        seasoning_animation[name] = {"position": {
            f"{start:.1f}": {"vector": [0, 0, 0]},
            f"{start + 0.2:.1f}": {"vector": [0, 0.75, 0], "easing": "easeOutBack"},
            f"{start + 0.4:.1f}": {"vector": [0, 0, 0], "easing": "easeInQuad"},
        }}
    return {
        "format_version": "1.8.0",
        "animations": {
            "chef.idle": {
                "loop": True,
                "animation_length": 4,
                "bones": {
                    "fire_core": hidden,
                    "fire_outer": hidden,
                    "steam_left": hidden,
                    "steam_right": hidden,
                    "pot_lid": {"rotation": {
                        "0.0": {"vector": [0, 0, -0.8]},
                        "2.0": {"vector": [0, 0, 0.8], "easing": "easeInOutSine"},
                        "4.0": {"vector": [0, 0, -0.8], "easing": "easeInOutSine"},
                    }},
                    "ladle": {"rotation": {
                        "0.0": {"vector": [0, 0, -18]},
                        "2.0": {"vector": [0, 0, -16], "easing": "easeInOutSine"},
                        "4.0": {"vector": [0, 0, -18], "easing": "easeInOutSine"},
                    }},
                },
            },
            "chef.cooking": {
                "loop": True,
                "animation_length": 2.4,
                "bones": {
                    "fire_core": {
                        "rotation": {"0.0": {"vector": [0, 0, 0]}, "2.4": {"vector": [0, 360, 0]}},
                        "scale": {
                            "0.0": {"vector": [0.82, 0.9, 0.82]},
                            "0.6": {"vector": [1.08, 1.18, 1.08], "easing": "easeInOutSine"},
                            "1.2": {"vector": [0.9, 1.02, 0.9], "easing": "easeInOutSine"},
                            "1.8": {"vector": [1.12, 1.22, 1.12], "easing": "easeInOutSine"},
                            "2.4": {"vector": [0.82, 0.9, 0.82], "easing": "easeInOutSine"},
                        },
                    },
                    "fire_outer": {
                        "rotation": {"0.0": {"vector": [0, 0, 0]}, "2.4": {"vector": [0, -360, 0]}},
                        "scale": {
                            "0.0": {"vector": [1, 0.82, 1]},
                            "1.2": {"vector": [0.82, 1.15, 0.82], "easing": "easeInOutSine"},
                            "2.4": {"vector": [1, 0.82, 1], "easing": "easeInOutSine"},
                        },
                    },
                    "pot_lid": {
                        "rotation": {
                            "0.0": {"vector": [0, 0, -2]},
                            "0.6": {"vector": [1.5, 0, 2.5], "easing": "easeOutBack"},
                            "1.2": {"vector": [-1, 0, -1.5], "easing": "easeInOutSine"},
                            "1.8": {"vector": [1, 0, 2], "easing": "easeOutBack"},
                            "2.4": {"vector": [0, 0, -2], "easing": "easeInOutSine"},
                        },
                        "position": {
                            "0.0": {"vector": [0, 0, 0]},
                            "0.6": {"vector": [0, 0.45, 0], "easing": "easeOutSine"},
                            "1.2": {"vector": [0, 0, 0], "easing": "easeInSine"},
                            "1.8": {"vector": [0, 0.35, 0], "easing": "easeOutSine"},
                            "2.4": {"vector": [0, 0, 0], "easing": "easeInSine"},
                        },
                    },
                    "ladle": {
                        "rotation": {
                            "0.0": {"vector": [0, -32, -20]},
                            "0.6": {"vector": [0, 34, -14], "easing": "easeInOutSine"},
                            "1.2": {"vector": [0, -28, -21], "easing": "easeInOutSine"},
                            "1.8": {"vector": [0, 30, -15], "easing": "easeInOutSine"},
                            "2.4": {"vector": [0, -32, -20], "easing": "easeInOutSine"},
                        },
                    },
                    "steam_left": {
                        "position": {"0.0": {"vector": [0, -1, 0]}, "2.4": {"vector": [-0.5, 4, 0.4]}},
                        "scale": {"0.0": {"vector": [0.25, 0.2, 0.25]},
                                  "1.2": {"vector": [1, 1.2, 1]}, "2.4": {"vector": [0, 1.5, 0]}},
                    },
                    "steam_right": {
                        "position": {"0.0": {"vector": [0.5, 3.5, -0.3]}, "2.4": {"vector": [0, -1, 0]}},
                        "scale": {"0.0": {"vector": [0, 1.4, 0]},
                                  "1.2": {"vector": [0.9, 1.1, 0.9]}, "2.4": {"vector": [0.2, 0.2, 0.2]}},
                    },
                    "knife": {"rotation": {
                        "0.0": {"vector": [0, -8, 0]},
                        "0.3": {"vector": [0, -8, -12], "easing": "easeOutQuad"},
                        "0.5": {"vector": [0, -8, 0], "easing": "easeOutBack"},
                        "1.2": {"vector": [0, -8, 0]},
                        "1.5": {"vector": [0, -8, -10], "easing": "easeOutQuad"},
                        "1.7": {"vector": [0, -8, 0], "easing": "easeOutBack"},
                        "2.4": {"vector": [0, -8, 0]},
                    }},
                    **seasoning_animation,
                },
            },
        },
        "geckolib_format_version": 2,
    }


def paint_region(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
                 base: tuple[int, int, int], dark: tuple[int, int, int],
                 light: tuple[int, int, int], alpha: int = 255, *, speckle: bool = True) -> None:
    left, top, right, bottom = box
    draw.rectangle(box, fill=(*base, alpha))
    if speckle:
        for y in range(top, bottom + 1):
            for x in range(left, right + 1):
                marker = (x * 17 + y * 31) % 29
                if marker == 0:
                    draw.point((x, y), fill=(*light, alpha))
                elif marker == 1:
                    draw.point((x, y), fill=(*dark, alpha))
    draw.line((left, top, right, top), fill=(*light, alpha))
    draw.line((left, top, left, bottom), fill=(*light, alpha))
    draw.line((left, bottom, right, bottom), fill=(*dark, alpha))
    draw.line((right, top, right, bottom), fill=(*dark, alpha))


def paint_seasoning_region(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
                           base: tuple[int, int, int], dark: tuple[int, int, int],
                           light: tuple[int, int, int],
                           strokes: tuple[tuple[int, int, int, int, bool], ...]) -> None:
    paint_region(draw, box, base, dark, light, speckle=False)
    left, top, _, _ = box
    for x1, y1, x2, y2, bright in strokes:
        color = light if bright else dark
        draw.line((left + x1, top + y1, left + x2, top + y2), fill=(*color, 255))


def build_atlas(path: Path, palette: tuple[tuple[int, int, int], ...]) -> None:
    base, dark, accent = palette
    image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # 桌体三组材质使用整洁纯色，只靠边缘明暗表达体积；斑点仅用于金属、木材和食材细节。
    paint_region(draw, (0, 0, 95, 31), base, dark, accent, speckle=False)
    paint_region(draw, (0, 40, 95, 71), dark, (20, 18, 20), base, speckle=False)
    paint_region(draw, (0, 80, 63, 111), accent, dark, (255, 239, 188), speckle=False)
    paint_region(draw, (104, 0, 167, 63), (112, 122, 124), (45, 51, 54), (204, 214, 214))
    paint_region(draw, (176, 0, 239, 63), (42, 47, 49), (15, 18, 20), (91, 101, 104))
    paint_region(draw, (104, 72, 167, 135), (54, 59, 61), (18, 21, 23), (117, 126, 128))
    paint_region(draw, (176, 72, 239, 135), (158, 104, 54), (82, 50, 29), (222, 166, 94))
    paint_region(draw, (104, 144, 139, 179), (174, 47, 37), (82, 24, 25), (239, 105, 61))
    paint_region(draw, (148, 144, 183, 179), (194, 128, 30), (92, 61, 23), (252, 207, 83))
    paint_region(draw, (192, 144, 227, 179), (57, 116, 62), (27, 57, 32), (127, 183, 91))
    paint_region(draw, (104, 188, 139, 223), (167, 85, 43), (91, 40, 24), (238, 155, 71))
    paint_region(draw, (148, 188, 183, 223), (216, 205, 172), (119, 91, 74), (248, 239, 206))
    paint_region(draw, (192, 188, 219, 223), (241, 92, 19), (170, 24, 9), (255, 213, 59), 220)
    paint_region(draw, (224, 188, 251, 243), (223, 232, 229), (134, 151, 151), (255, 255, 255), 92)
    paint_seasoning_region(draw, (0, 120, 19, 139), (225, 220, 200), (139, 127, 105),
                            (255, 249, 226), ((4, 4, 6, 6, True), (12, 3, 13, 5, True),
                                              (6, 12, 8, 14, False), (14, 10, 16, 12, True)))
    paint_seasoning_region(draw, (24, 120, 43, 139), (190, 122, 36), (99, 58, 23),
                            (244, 185, 70), ((4, 3, 4, 15, True), (5, 3, 8, 3, True),
                                            (10, 5, 15, 10, False), (9, 13, 15, 13, True)))
    paint_seasoning_region(draw, (48, 120, 67, 139), (213, 173, 45), (123, 91, 23),
                            (255, 224, 103), ((3, 5, 16, 5, True), (5, 9, 14, 9, False),
                                             (3, 14, 16, 14, True)))
    paint_seasoning_region(draw, (72, 120, 91, 139), (130, 154, 78), (67, 88, 46),
                            (194, 210, 123), ((4, 15, 14, 4, True), (7, 15, 16, 7, False),
                                              (3, 6, 6, 3, True)))
    paint_seasoning_region(draw, (0, 148, 19, 167), (181, 48, 30), (91, 24, 22),
                            (242, 98, 52), ((3, 4, 5, 5, False), (10, 3, 12, 5, True),
                                           (6, 10, 8, 12, True), (13, 12, 16, 14, False)))
    paint_seasoning_region(draw, (24, 148, 43, 167), (67, 126, 61), (30, 67, 34),
                            (126, 181, 90), ((4, 14, 9, 5, True), (9, 5, 14, 9, False),
                                            (8, 10, 13, 14, True)))
    paint_seasoning_region(draw, (48, 148, 67, 167), (122, 70, 42), (64, 35, 26),
                            (188, 112, 61), ((4, 5, 15, 5, True), (15, 5, 15, 13, False),
                                            (7, 13, 15, 13, True), (7, 9, 11, 9, False)))
    paint_seasoning_region(draw, (72, 148, 91, 167), (168, 190, 194), (88, 115, 121),
                            (229, 241, 239), ((3, 2, 3, 16, True), (5, 2, 8, 2, True),
                                             (12, 5, 16, 9, False), (14, 13, 16, 15, True)))
    # 案板保持可读的顺纹和封边；刀身使用横向拉丝、亮刃与深色刀脊。
    paint_region(draw, (0, 176, 39, 207), (181, 126, 68), (102, 64, 36),
                 (229, 178, 101), speckle=False)
    for y, color in ((181, (211, 151, 82)), (188, (143, 91, 50)),
                     (196, (221, 163, 91)), (203, (128, 79, 44))):
        draw.line((2, y, 37, y), fill=(*color, 255))
    draw.line((7, 178, 15, 205), fill=(202, 141, 76, 255))
    draw.line((28, 178, 22, 205), fill=(151, 95, 52, 255))
    paint_region(draw, (0, 216, 39, 247), (112, 72, 41), (65, 40, 27),
                 (166, 110, 59), speckle=False)
    for y in (221, 231, 240):
        draw.line((2, y, 37, y), fill=(82, 50, 31, 255))
    paint_region(draw, (48, 176, 95, 207), (158, 170, 173), (61, 69, 72),
                 (229, 238, 238), speckle=False)
    for y in (181, 185, 190, 194, 199):
        draw.line((50, y, 93, y), fill=(187, 199, 201, 255))
    draw.line((49, 204, 94, 204), fill=(248, 252, 249, 255), width=2)
    draw.line((49, 177, 94, 177), fill=(78, 87, 90, 255), width=2)
    image.save(path, optimize=True)


def clean_geometry(bones: list[dict[str, Any]]) -> list[dict[str, Any]]:
    cleaned = json.loads(json.dumps(bones))
    for item in cleaned:
        for item_cube in item["cubes"]:
            item_cube.pop("_material", None)
    return cleaned


def build_preview(path: Path, bones: list[dict[str, Any]], palette: tuple[tuple[int, int, int], ...]) -> None:
    base, dark, accent = palette
    colors = {
        "tier": base, "tier_dark": dark, "accent": accent, "metal": (125, 137, 139),
        "dark_metal": (42, 47, 49), "pot": (58, 64, 66), "wood": (174, 119, 61),
        "red": (201, 58, 43), "yellow": (222, 157, 44), "green": (69, 136, 72),
        "food": (201, 101, 50), "cloth": (221, 210, 181), "fire": (247, 94, 18),
        "steam": (207, 222, 218), "savory": (225, 220, 200), "sweet": (190, 122, 36),
        "oily": (213, 173, 45), "sour": (130, 154, 78), "spicy": (181, 48, 30),
        "aromatic": (67, 126, 61), "complex": (122, 70, 42), "glass": (168, 190, 194),
        "board": (181, 126, 68), "board_dark": (112, 72, 41), "blade": (170, 183, 185),
    }
    image = Image.new("RGBA", (760, 470), (24, 21, 25, 255))
    draw = ImageDraw.Draw(image)

    def project(point: tuple[float, float, float]) -> tuple[int, int]:
        x, y, z = point
        return 380 + round((x - z) * 7), 350 + round((x + z) * 3.5 - y * 8)

    all_cubes = [item_cube for item in bones for item_cube in item["cubes"]]
    all_cubes.sort(key=lambda item: sum(item["origin"]) + item["size"][1] * 2)
    for item in all_cubes:
        x1, y1, z1 = item["origin"]
        sx, sy, sz = item["size"]
        x2, y2, z2 = x1 + sx, y1 + sy, z1 + sz
        color = colors[item["_material"]]
        top = [project(p) for p in ((x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2))]
        right = [project(p) for p in ((x2, y1, z1), (x2, y2, z1), (x2, y2, z2), (x2, y1, z2))]
        front = [project(p) for p in ((x1, y1, z2), (x1, y2, z2), (x2, y2, z2), (x2, y1, z2))]
        alpha = 125 if item["_material"] == "steam" else 230 if item["_material"] == "fire" else 255
        shade = lambda amount: tuple(max(0, min(255, channel + amount)) for channel in color) + (alpha,)
        draw.polygon(right, fill=shade(-10), outline=(16, 15, 17, alpha))
        draw.polygon(front, fill=shade(-28), outline=(16, 15, 17, alpha))
        draw.polygon(top, fill=shade(20), outline=(16, 15, 17, alpha))
    draw.text((22, 20), "WOK CHEF / GECKOLIB TWO-BLOCK WORKSTATION", fill=(242, 224, 180, 255))
    draw.text((22, 42), "tomato + cabbage + potato prep / grooved board / riveted knife / seasoning rack",
              fill=(178, 172, 167, 255))
    image.save(path, optimize=True)


def build_gecko_assets(root: Path, tier_palettes: dict[str, tuple[tuple[int, int, int], ...]]) -> None:
    resources = root / "src" / "main" / "resources" / "assets" / "miningdim"
    geo_dir = resources / "geo" / "block"
    animation_dir = resources / "animations" / "block"
    texture_dir = resources / "textures" / "block"
    preview_dir = root / "docs" / "assets" / "chef"
    for directory in (geo_dir, animation_dir, texture_dir, preview_dir):
        directory.mkdir(parents=True, exist_ok=True)

    bones = workstation_bones()
    geometry = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.miningdim.seasoning_table",
                "texture_width": 256,
                "texture_height": 256,
                "visible_bounds_width": 4,
                "visible_bounds_height": 2.5,
                "visible_bounds_offset": [0, 0.85, 0],
            },
            "bones": clean_geometry(bones),
        }],
    }
    (geo_dir / "seasoning_table.geo.json").write_text(
        json.dumps(geometry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (animation_dir / "seasoning_table.animation.json").write_text(
        json.dumps(animation_data(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for tier, palette in tier_palettes.items():
        build_atlas(texture_dir / f"seasoning_table_{tier}_geo.png", palette)
    build_preview(preview_dir / "chef_gecko_workstation_preview.png", bones, tier_palettes["radiant"])
