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
        cube((5.5, 14, 3.1), (1, 3.5, 0.5), "metal"),
        cube((14, 14, 3.1), (1, 3.5, 0.5), "metal"),
        cube((-1, 12.2, 5.8), (2, 4.2, 0.35), "metal"),
        cube((2.2, 12.2, 5.8), (2, 4.2, 0.35), "metal"),
        cube((-0.5, 11.5, -8.25), (5.5, 0.25, 0.25), "metal"),
        cube((5, 8.5, -8.2), (6, 3.5, 0.3), "cloth"),
    ]
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
        cube((0, 11, -6), (10, 0.65, 8), "wood"),
        cube((0.5, 11.65, -5.5), (2.1, 1.2, 2.1), "red"),
        cube((3.1, 11.65, -4.2), (1.7, 1.5, 1.7), "green"),
        cube((5.2, 11.65, -5.1), (2.6, 0.7, 2), "food"),
        cube((10.7, 11, -5.5), (4.2, 0.45, 7), "metal"),
        cube((11.1, 11.45, -5.1), (3.4, 0.35, 6.2), "metal"),
        cube((11.5, 11.8, -4.7), (2.6, 0.3, 5.4), "metal"),
        cube((10.8, 11, 1.8), (4.2, 1, 3.8), "wood"),
        cube((11.4, 12, 2.4), (3, 1.2, 2.6), "pot"),
        cube((12.1, 13.2, 3), (1.6, 0.5, 1.4), "food"),
    ]
    knife = [
        cube((5, 12.3, -3.7), (5.3, 0.35, 1.2), "metal"),
        cube((9.8, 12.15, -3.85), (3, 0.7, 1.5), "wood"),
    ]
    seasoning_bones = [
        bone("seasoning_savory", [
            cube((5.6, 15, 4.9), (1.55, 1.75, 1.3), "savory"),
            cube((5.5, 16.75, 4.8), (1.75, 0.35, 1.5), "metal"),
            cube((5.9, 15.5, 4.65), (0.95, 0.65, 0.25), "cloth"),
        ], parent="workstation", pivot=(6.375, 15, 5.55)),
        bone("seasoning_sweet", [
            cube((7.95, 15, 4.9), (1.4, 1.85, 1.25), "sweet"),
            cube((8.15, 16.85, 5.05), (1, 0.4, 0.95), "sweet"),
            cube((8.32, 17.25, 5.2), (0.65, 0.55, 0.65), "sweet"),
            cube((8.27, 17.8, 5.15), (0.75, 0.35, 0.75), "wood"),
            cube((8.2, 15.55, 4.65), (0.9, 0.65, 0.25), "cloth"),
        ], parent="workstation", pivot=(8.65, 15, 5.525)),
        bone("seasoning_oily", [
            cube((10.3, 15, 4.9), (1.3, 2.15, 1.2), "oily"),
            cube((10.48, 17.15, 5.05), (0.94, 0.35, 0.9), "oily"),
            cube((10.65, 17.5, 5.18), (0.6, 0.65, 0.64), "oily"),
            cube((10.6, 18.15, 5.13), (0.7, 0.3, 0.74), "wood"),
            cube((10.52, 15.65, 4.65), (0.86, 0.7, 0.25), "cloth"),
        ], parent="workstation", pivot=(10.95, 15, 5.5)),
        bone("seasoning_sour", [
            cube((12.65, 15, 4.9), (1.4, 1.95, 1.25), "sour"),
            cube((12.85, 16.95, 5.05), (1, 0.4, 0.95), "sour"),
            cube((13.03, 17.35, 5.2), (0.64, 0.6, 0.65), "sour"),
            cube((12.98, 17.95, 5.15), (0.74, 0.3, 0.75), "metal"),
            cube((12.9, 15.6, 4.65), (0.9, 0.65, 0.25), "cloth"),
        ], parent="workstation", pivot=(13.35, 15, 5.525)),
        bone("seasoning_spicy", [
            cube((6.7, 15, 3.55), (1.6, 1.65, 1.2), "spicy"),
            cube((6.62, 16.65, 3.47), (1.76, 0.42, 1.36), "metal"),
            cube((7.02, 15.48, 3.3), (0.96, 0.68, 0.25), "cloth"),
        ], parent="workstation", pivot=(7.5, 15, 4.15)),
        bone("seasoning_aromatic", [
            cube((9.05, 15, 3.55), (1.6, 1.75, 1.2), "aromatic"),
            cube((8.97, 16.75, 3.47), (1.76, 0.38, 1.36), "dark_metal"),
            cube((9.37, 15.52, 3.3), (0.96, 0.68, 0.25), "cloth"),
        ], parent="workstation", pivot=(9.85, 15, 4.15)),
        bone("seasoning_complex", [
            cube((11.4, 15, 3.5), (1.9, 1.25, 1.3), "complex"),
            cube((11.3, 16.25, 3.4), (2.1, 0.25, 1.5), "metal"),
            cube((11.55, 16.5, 3.65), (1.6, 0.18, 1), "complex"),
            cube((11.78, 16.68, 3.82), (0.35, 0.22, 0.3), "spicy"),
            cube((12.35, 16.68, 4.1), (0.4, 0.2, 0.32), "aromatic"),
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
    paint_region(draw, (0, 120, 19, 139), (225, 220, 200), (139, 127, 105), (255, 249, 226))
    paint_region(draw, (24, 120, 43, 139), (190, 122, 36), (99, 58, 23), (244, 185, 70))
    paint_region(draw, (48, 120, 67, 139), (213, 173, 45), (123, 91, 23), (255, 224, 103))
    paint_region(draw, (72, 120, 91, 139), (130, 154, 78), (67, 88, 46), (194, 210, 123))
    paint_region(draw, (0, 148, 19, 167), (181, 48, 30), (91, 24, 22), (242, 98, 52))
    paint_region(draw, (24, 148, 43, 167), (67, 126, 61), (30, 67, 34), (126, 181, 90))
    paint_region(draw, (48, 148, 67, 167), (122, 70, 42), (64, 35, 26), (188, 112, 61))
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
        "aromatic": (67, 126, 61), "complex": (122, 70, 42),
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
    draw.text((22, 42), "pot + lid + ladle + steam + fire + prep + knife + seven-category seasoning rack",
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
