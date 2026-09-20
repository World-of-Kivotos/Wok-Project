import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/miningdim"
GEO_DIR = ASSETS / "geo/block"
ANIMATION_DIR = ASSETS / "animations/block"
TEXTURE_DIR = ASSETS / "textures/block"
ITEM_DIR = ASSETS / "models/item"
PREVIEW_PATH = ROOT / "dist/munitions-bench-geckolib-asset-preview.png"

ATLAS_SIZE = 512
PATCH_SIZE = 128
UV_GUTTER = 16
BODY_HEIGHT_SCALE = 1.65
MATERIALS = (
    "dark", "metal", "panel", "glass",
    "brass", "rubber", "accent", "light",
    "interior", "cartridge", "warning", "screen",
    "edge", "vent", "bolt", "trim",
)
PATCH_ORIGINS = {name: ((index % 4) * PATCH_SIZE, (index // 4) * PATCH_SIZE)
                 for index, name in enumerate(MATERIALS)}
UV = {name: (origin[0] + UV_GUTTER, origin[1] + UV_GUTTER)
      for name, origin in PATCH_ORIGINS.items()}

TIERS = (
    ("", "base", {
        "dark": (42, 46, 47), "metal": (96, 102, 104), "panel": (78, 85, 64),
        "glass": (67, 84, 82, 255), "brass": (181, 133, 49), "rubber": (34, 37, 38),
        "accent": (125, 137, 93), "light": (160, 177, 114), "interior": (32, 36, 37),
        "cartridge": (211, 159, 65), "warning": (180, 75, 48), "screen": (119, 154, 112),
    }),
    ("_medium", "medium", {
        "dark": (41, 47, 51), "metal": (108, 115, 119), "panel": (72, 79, 82),
        "glass": (43, 106, 112, 255), "brass": (190, 145, 52), "rubber": (34, 40, 42),
        "accent": (31, 185, 199), "light": (51, 239, 248), "interior": (31, 40, 42),
        "cartridge": (221, 169, 69), "warning": (195, 88, 51), "screen": (54, 205, 216),
    }),
    ("_high", "high", {
        "dark": (23, 39, 56), "metal": (72, 102, 136), "panel": (32, 66, 108),
        "glass": (29, 84, 132, 255), "brass": (194, 148, 52), "rubber": (23, 34, 44),
        "accent": (38, 138, 232), "light": (61, 199, 255), "interior": (18, 30, 43),
        "cartridge": (225, 173, 72), "warning": (208, 82, 46), "screen": (54, 168, 244),
    }),
    ("_superior", "superior", {
        "dark": (42, 31, 52), "metal": (116, 91, 138), "panel": (72, 46, 94),
        "glass": (92, 52, 128, 255), "brass": (199, 151, 56), "rubber": (35, 29, 41),
        "accent": (140, 72, 187), "light": (207, 103, 255), "interior": (31, 23, 38),
        "cartridge": (225, 172, 72), "warning": (205, 70, 56), "screen": (184, 81, 226),
    }),
    ("_transcendent", "transcendent", {
        "dark": (44, 30, 32), "metal": (116, 80, 82), "panel": (69, 46, 48),
        "glass": (113, 45, 49, 255), "brass": (205, 153, 55), "rubber": (39, 28, 29),
        "accent": (174, 51, 55), "light": (255, 84, 85), "interior": (32, 22, 23),
        "cartridge": (230, 171, 69), "warning": (240, 76, 42), "screen": (221, 62, 62),
    }),
    ("_radiant", "radiant", {
        "dark": (47, 47, 54), "metal": (184, 185, 183), "panel": (211, 207, 197),
        "glass": (111, 76, 148, 255), "brass": (224, 178, 62), "rubber": (47, 44, 53),
        "accent": (193, 136, 48), "light": (215, 115, 255), "interior": (48, 44, 53),
        "cartridge": (247, 195, 75), "warning": (210, 110, 51), "screen": (190, 93, 235),
    }),
)


def stretched_height(y):
    """机身在 y=2 以上整体拉高。分段函数必须同时作用于底面和顶面, 否则跨越 y=2 的件只有底面被映射,
    顶面停在原位, 与紧邻其上的件之间裂出一条缝 (底座第二级与第三级之间原本就有 0.13 单位的缝)。"""
    return y if y <= 2 else 2 + (y - 2) * BODY_HEIGHT_SCALE


def cube(origin, size, material, *, rotation=None, pivot=None):
    ox, oy, oz = origin
    sx, sy, sz = size
    bottom = stretched_height(oy)
    oy, sy = bottom, stretched_height(oy + sy) - bottom
    result = {"origin": [ox, oy, oz], "size": [sx, sy, sz], "uv": list(UV[material])}
    if rotation is not None:
        result["rotation"] = list(rotation)
        result["pivot"] = list(pivot)
    result["material"] = material
    return result


def circular_plate(center_x, center_z, radius, y, height, material, strips=13):
    strip_depth = radius * 2 / strips
    result = []
    for index in range(strips):
        center_offset = -radius + (index + 0.5) * strip_depth
        half_width = math.sqrt(radius * radius - center_offset * center_offset)
        result.append(cube(
            (center_x - half_width, y, center_z + center_offset - strip_depth / 2),
            (half_width * 2, height, strip_depth),
            material,
        ))
    return result


BODY = [
    # Two-block stepped plinth, feet, and inset front service panels.
    cube((-8, 0, -8), (32, 1.1, 16), "rubber"),
    cube((-7.5, 1.1, -7.6), (31, 1.1, 15.2), "dark"),
    cube((-7, 2.2, -7.1), (30, 1.0, 14.2), "metal"),
    cube((-8, 0.15, -8.6), (32, 0.6, 0.6), "edge"),
    cube((-6.8, 2.55, -7.75), (6.0, 0.35, 0.55), "trim"),
    cube((1.0, 2.55, -7.75), (12.5, 0.35, 0.55), "trim"),
    cube((18.0, 2.55, -7.75), (4.5, 0.35, 0.55), "trim"),
    cube((-7.2, 0.78, -8.82), (6.0, 0.14, 0.18), "edge"),
    cube((1.2, 0.78, -8.82), (6.0, 0.14, 0.18), "edge"),
    cube((14.0, 0.78, -8.82), (6.0, 0.14, 0.18), "edge"),

    # Left controls: recessed fascia, display, status lamps, keypad, and lever.
    cube((-7, 3.2, -6.8), (6.8, 5.5, 4.6), "panel"),
    cube((-6.45, 3.8, -7.45), (5.7, 4.2, 0.65), "edge"),
    cube((-5.9, 6.15, -7.9), (3.1, 1.25, 0.42), "screen"),
    cube((-5.55, 6.45, -8.12), (2.4, 0.18, 0.22), "light"),
    cube((-5.9, 4.45, -7.9), (1.05, 0.75, 0.42), "interior"),
    cube((-4.45, 4.45, -7.9), (1.05, 0.75, 0.42), "interior"),
    cube((-5.65, 4.68, -8.15), (0.45, 0.34, 0.25), "light"),
    cube((-4.15, 4.68, -8.15), (0.45, 0.34, 0.25), "warning"),
    cube((-2.65, 4.05, -7.95), (0.72, 2.15, 0.5), "bolt"),
    cube((-2.82, 3.72, -8.18), (1.05, 0.33, 0.7), "dark"),
    cube((-2.8, 6.2, -8.13), (1.0, 0.55, 0.65), "metal"),
    cube((-6.6, 7.75, -7.8), (0.5, 0.5, 0.3), "bolt"),
    cube((-1.35, 7.75, -7.8), (0.5, 0.5, 0.3), "bolt"),

    # Raised control surround, screen bezel, keypad and protected lever recess.
    cube((-6.75, 3.65, -8.36), (0.22, 4.45, 0.16), "trim"),
    cube((-1.15, 3.65, -8.36), (0.22, 4.45, 0.16), "trim"),
    cube((-6.53, 7.88, -8.36), (5.38, 0.22, 0.16), "edge"),
    cube((-6.53, 3.65, -8.36), (5.38, 0.22, 0.16), "edge"),
    cube((-6.10, 7.40, -8.28), (3.50, 0.18, 0.16), "metal"),
    cube((-6.10, 5.97, -8.28), (3.50, 0.18, 0.16), "metal"),
    cube((-6.10, 6.15, -8.28), (0.20, 1.25, 0.16), "metal"),
    cube((-2.95, 6.15, -8.28), (0.15, 1.25, 0.16), "metal"),

    # Solid left cabinet below the hopper. This fills the full control/rear bay
    # instead of hiding it behind a thin side skin.
    cube((-7.0, 3.2, -2.2), (6.8, 5.5, 8.05), "panel"),
    cube((-7.35, 3.2, -2.2), (0.35, 5.5, 8.05), "panel"),
    cube((-7.7, 3.7, -1.5), (0.35, 4.6, 6.4), "edge"),
    cube((-7.95, 4.2, -0.9), (0.25, 3.6, 5.1), "interior"),
    cube((-8.2, 4.65, -0.45), (0.25, 0.45, 4.2), "vent"),
    cube((-8.2, 5.8, -0.45), (0.25, 0.45, 4.2), "vent"),
    cube((-8.2, 6.95, -0.45), (0.25, 0.45, 4.2), "vent"),

    # The hopper steps forward at z=2.3 while the rear shell starts at z=5.85.
    # Fill that exact upper interval so the left side stays solid at oblique angles.
    cube((-7.35, 8.7, 2.3), (7.15, 2.2, 3.55), "panel"),
    # The second hopper step ends farther back. Keep a sub-pixel inset from the
    # chamber side/top so scaled cubes cannot overlap at their floating endpoints.
    cube((-7.6, 10.9, 3.1), (7.399, 2.799, 2.75), "panel"),

    # Stepped ammunition hopper with a deep rim and individually modelled slats.
    cube((-7.0, 8.7, -4.8), (6.2, 2.2, 7.1), "panel"),
    cube((-7.6, 10.9, -5.4), (7.2, 2.8, 8.5), "dark"),
    cube((-8.0, 13.7, -5.8), (8.0, 1.1, 9.4), "metal"),
    cube((-7.25, 14.8, -5.0), (6.55, 0.8, 7.8), "edge"),
    cube((-6.6, 15.6, -4.35), (5.25, 0.3, 6.5), "interior"),
    cube((-6.35, 15.9, -4.0), (0.58, 0.28, 5.8), "vent"),
    cube((-5.05, 15.9, -4.0), (0.58, 0.28, 5.8), "vent"),
    cube((-3.75, 15.9, -4.0), (0.58, 0.28, 5.8), "vent"),
    cube((-2.45, 15.9, -4.0), (0.58, 0.28, 5.8), "vent"),
    cube((-7.55, 11.15, -5.9), (6.65, 0.45, 0.45), "trim"),
    cube((-7.45, 13.7, -6.02), (6.9, 0.15, 0.22), "edge"),
    cube((-7.10, 14.8, -5.25), (6.0, 0.14, 0.18), "trim"),

    # Main chamber floor, back wall and layered square arch.
    cube((0, 3.2, -6.65), (18, 1.0, 13.3), "dark"),
    cube((2.1, 4.2, -4.85), (13.8, 0.55, 9.8), "edge"),
    cube((1.9, 4.75, 4.95), (14.2, 8.2, 0.8), "interior"),
    cube((0, 4.2, -6.95), (2.1, 9.2, 2.1), "metal"),
    cube((15.9, 4.2, -6.95), (2.1, 9.2, 2.1), "metal"),
    cube((0.45, 4.8, -7.42), (0.65, 7.8, 0.45), "edge"),
    cube((16.9, 4.8, -7.42), (0.65, 7.8, 0.45), "edge"),
    cube((2.1, 12.35, -7.0), (13.8, 1.2, 1.9), "metal"),
    cube((2.5, 12.75, -7.48), (13.0, 0.55, 0.45), "light"),
    cube((0.0, 13.55, -6.65), (18, 1.2, 13.3), "panel"),
    cube((2.2, 14.75, -6.6), (13.6, 0.45, 12.7), "edge"),
    cube((0.7, 15.2, -5.9), (16.6, 0.55, 11.8), "metal"),
    cube((2.0, 15.75, -4.8), (14.0, 0.2, 9.6), "trim"),
    cube((3.0, 15.95, -4.0), (5.0, 0.2, 8.0), "edge"),
    cube((10.0, 15.95, -4.0), (5.0, 0.2, 8.0), "edge"),
    cube((8.25, 15.95, -4.0), (1.50, 0.2, 8.0), "panel"),
    cube((2.20, 15.95, -4.0), (0.60, 0.2, 8.0), "panel"),
    cube((15.20, 15.95, -4.0), (0.60, 0.2, 8.0), "panel"),
    cube((2.80, 15.95, -4.80), (12.40, 0.2, 0.60), "metal"),
    cube((2.80, 15.95, 4.20), (12.40, 0.2, 0.60), "metal"),
    cube((3.25, 16.15, -3.65), (0.28, 0.08, 0.28), "bolt"),
    cube((14.47, 16.15, -3.65), (0.28, 0.08, 0.28), "bolt"),
    cube((3.25, 16.15, 3.37), (0.28, 0.08, 0.28), "bolt"),
    cube((14.47, 16.15, 3.37), (0.28, 0.08, 0.28), "bolt"),

    # Segmented header light and a recessed solid inner chamber liner.
    cube((3.00, 12.88, -7.70), (3.20, 0.20, 0.22), "light"),
    cube((7.40, 12.88, -7.70), (3.20, 0.20, 0.22), "light"),
    cube((11.80, 12.88, -7.70), (3.20, 0.20, 0.22), "light"),
    cube((2.30, 5.10, 3.70), (0.70, 6.45, 1.10), "edge"),
    cube((14.30, 5.10, 3.70), (0.70, 6.45, 1.10), "edge"),
    cube((3.00, 11.55, 3.70), (12.00, 0.75, 1.10), "metal"),
    cube((2.52, 5.45, 3.42), (0.26, 5.70, 0.28), "trim"),
    cube((14.52, 5.45, 3.42), (0.26, 5.70, 0.28), "trim"),
    cube((3.25, 11.78, 3.42), (11.50, 0.28, 0.28), "trim"),
    cube((2.50, 13.55, -6.88), (13.0, 0.18, 0.23), "accent"),

    # Continuous chamber side shells. These close the full front-to-rear cavity
    # while leaving the framed front opening unobstructed.
    cube((-0.2, 4.2, -4.85), (0.2, 9.35, 9.8), "panel"),
    cube((17.8, 4.2, -4.85), (0.2, 9.35, 9.8), "panel"),

    # Open chamber with structural corner rails. Large translucent panes are omitted
    # because a single Geo render pass cannot depth-sort them against the machinery.
    cube((2.25, 5.15, -7.42), (0.52, 1.8, 0.35), "trim"),
    cube((15.25, 10.2, -7.42), (0.52, 1.8, 0.35), "trim"),
    cube((2.12, 5.05, -5.0), (0.45, 1.7, 2.0), "trim"),
    cube((15.43, 10.25, 2.2), (0.45, 1.7, 2.0), "trim"),
    cube((1.35, 4.60, -7.72), (1.65, 0.32, 0.27), "edge"),
    cube((15.00, 4.60, -7.72), (1.65, 0.32, 0.27), "edge"),
    cube((1.35, 12.72, -7.72), (1.05, 0.32, 0.27), "edge"),
    cube((15.60, 12.72, -7.72), (1.05, 0.32, 0.27), "edge"),
    cube((0.7, 4.5, -7.8), (0.55, 0.55, 0.35), "bolt"),
    cube((16.75, 4.5, -7.8), (0.55, 0.55, 0.35), "bolt"),
    cube((0.7, 12.65, -7.8), (0.55, 0.55, 0.35), "bolt"),
    cube((16.75, 12.65, -7.8), (0.55, 0.55, 0.35), "bolt"),

    # Right-side service tower with inset louver bank.
    cube((18, 3.2, -5.9), (4.7, 10.3, 11.7), "dark"),
    cube((18.4, 4.0, -6.65), (3.9, 8.7, 0.72), "metal"),
    cube((18.9, 4.55, -7.12), (2.9, 7.55, 0.44), "interior"),
    cube((18.1, 13.5, -6.0), (4.6, 1.25, 11.9), "panel"),
    cube((18.55, 14.75, -5.5), (3.7, 0.45, 10.9), "trim"),
    cube((18.85, 4.60, -7.90), (0.30, 7.80, 0.25), "edge"),
    cube((21.55, 4.60, -7.90), (0.30, 7.80, 0.25), "edge"),
    cube((19.15, 4.60, -7.90), (2.40, 0.30, 0.25), "metal"),
    cube((19.15, 12.10, -7.90), (2.40, 0.30, 0.25), "metal"),
]
for button_y in (4.05, 5.45):
    for button_x, material in ((-5.80, "light"), (-4.95, "bolt"), (-4.10, "warning")):
        BODY.append(cube((button_x, button_y, -7.70), (0.38, 0.35, 0.25), material))

# A four-piece guard surrounds the lever without crossing its swept visual area.
BODY.extend((
    cube((-2.90, 3.90, -8.42), (0.18, 2.06, 0.20), "edge"),
    cube((-1.60, 3.90, -8.42), (0.18, 2.06, 0.20), "edge"),
    cube((-2.72, 3.90, -8.42), (1.12, 0.18, 0.20), "edge"),
    cube((-2.72, 5.78, -8.42), (1.12, 0.18, 0.20), "edge"),
))
for vent_y in (5.05, 6.35, 7.65, 8.95, 10.25, 11.55):
    BODY.append(cube((19.15, vent_y, -7.65), (2.4, 0.42, 0.48), "vent"))
for bolt_x in (18.65, 22.1):
    for bolt_y in (4.15, 12.05):
        BODY.append(cube((bolt_x, bolt_y, -7.5), (0.38, 0.38, 0.32), "bolt"))
BODY.append(cube((22.7, 4.0, -4.95), (0.42, 8.6, 10.0), "panel"))
BODY.append(cube((23.12, 4.45, -4.45), (0.32, 7.7, 9.0), "interior"))
# Gecko mirrors model X, so these two stepped returns are the physical left-rear
# corner. They bridge each service-tower layer to the rear shell without overlap.
BODY.append(cube((22.7, 4.0, 5.05), (0.42, 8.6, 0.80), "panel"))
BODY.append(cube((23.12, 4.0, 4.55), (0.32, 8.6, 1.30), "panel"))
for side_z in (-3.8, -2.25, -0.7, 0.85, 2.4, 3.95):
    BODY.append(cube((23.44, 5.0, side_z), (0.3, 6.55, 0.52), "vent"))

# Full rear enclosure with raised service panels and external cooling ribs.
BODY.extend((
    cube((-7.0, 4.2, 5.85), (29.7, 9.3, 0.6), "panel"),
    cube((-6.4, 5.0, 6.5), (5.5, 7.4, 0.35), "edge"),
    cube((0.5, 5.0, 6.5), (16.7, 7.4, 0.35), "metal"),
    cube((18.1, 5.0, 6.5), (4.0, 7.4, 0.35), "dark"),
    cube((-5.8, 5.7, 6.9), (4.3, 5.8, 0.3), "interior"),
))
for rear_y in (5.8, 7.0, 8.2, 9.4, 10.6, 11.8):
    BODY.append(cube((2.0, rear_y, 6.9), (13.7, 0.35, 0.3), "vent"))
for rear_x in (-5.2, -3.65, -2.1):
    BODY.append(cube((rear_x, 6.25, 7.25), (0.5, 4.7, 0.25), "vent"))
for rear_x in (0.85, 16.5, 18.45, 21.4):
    for rear_y in (5.35, 11.7):
        BODY.append(cube((rear_x, rear_y, 6.9), (0.35, 0.35, 0.3), "bolt"))

PRESS_HOUSING = [
    cube((5.4, 11.0, -3.0), (7.2, 2.2, 5.8), "metal"),
    cube((6.0, 10.2, -2.5), (6.0, 0.8, 5.0), "panel"),
    cube((6.4, 11.9, -3.42), (5.2, 0.5, 0.4), "light"),
    cube((5.55, 11.35, -3.37), (0.45, 0.45, 0.35), "bolt"),
    cube((12.0, 11.35, -3.37), (0.45, 0.45, 0.35), "bolt"),
    # Side cheek rails and a recessed lower face break up the suspended press head.
    cube((5.10, 10.2, -2.8), (0.30, 2.2, 5.4), "edge"),
    cube((12.60, 10.2, -2.8), (0.30, 2.2, 5.4), "edge"),
    cube((6.15, 10.35, -3.28), (5.70, 0.45, 0.28), "panel"),
]

# Four fixed guide posts preserve a readable press silhouette around the moving ram.
for guide_x in (6.25, 11.40):
    for guide_z in (-1.90, 1.55):
        PRESS_HOUSING.append(cube((guide_x, 8.05, guide_z), (0.35, 2.15, 0.35), "metal"))
        PRESS_HOUSING.append(cube((guide_x - 0.20, 7.80, guide_z - 0.20),
                                  (0.75, 0.25, 0.75), "edge"))
PRESS_RAM = [
    cube((7.7, 8.85, -1.0), (2.6, 1.35, 2.0), "metal"),
    cube((8.35, 7.9, -0.55), (1.3, 0.95, 1.1), "brass"),
    cube((8.1, 7.55, -0.75), (1.8, 0.35, 1.5), "bolt"),
    cube((8.00, 9.15, -1.20), (2.00, 0.55, 0.20), "trim"),
]

# Floating die surround plus two split clamp rings; every piece stays on the ram bone.
PRESS_RAM.extend((
    cube((7.48, 7.42, -0.64), (0.62, 0.13, 1.28), "edge"),
    cube((9.90, 7.42, -0.64), (0.62, 0.13, 1.28), "edge"),
    cube((8.10, 7.42, -1.32), (1.80, 0.13, 0.40), "edge"),
    cube((8.10, 7.42, 0.92), (1.80, 0.13, 0.40), "edge"),
))
for clamp_y, material in ((8.14, "brass"), (8.55, "edge")):
    PRESS_RAM.extend((
        cube((8.15, clamp_y, -0.70), (0.20, 0.16, 1.40), material),
        cube((9.65, clamp_y, -0.70), (0.20, 0.16, 1.40), material),
        cube((8.35, clamp_y, -0.70), (1.30, 0.16, 0.15), material),
        cube((8.35, clamp_y, 0.55), (1.30, 0.16, 0.15), material),
    ))
PRESS = PRESS_HOUSING + PRESS_RAM

CAROUSEL = circular_plate(9.0, 0.0, 4.75, 4.8, 0.3, "rubber")
CAROUSEL += circular_plate(9.0, 0.0, 4.4, 5.1, 0.3, "metal")
CAROUSEL += circular_plate(9.0, 0.0, 3.65, 5.4, 0.25, "dark", strips=11)
CAROUSEL += circular_plate(9.0, 0.0, 1.8, 5.65, 0.28, "edge", strips=9)
CAROUSEL += circular_plate(9.0, 0.0, 0.9, 5.93, 0.42, "brass", strips=7)
for angle in range(0, 360, 30):
    radians = math.radians(angle)
    tooth_x = 9.0 + math.cos(radians) * 4.55
    tooth_z = math.sin(radians) * 4.55
    CAROUSEL.append(cube((tooth_x - 0.225, 5.40, tooth_z - 0.225),
                         (0.45, 0.18, 0.45), "edge"))
    cx = 9.0 + math.cos(radians) * 3.55
    cz = math.sin(radians) * 3.55
    CAROUSEL.append(cube((cx - 0.48, 5.65, cz - 0.48), (0.96, 0.25, 0.96), "metal"))
    CAROUSEL.append(cube((cx - 0.48, 5.65, cz - 0.62), (0.96, 0.25, 0.14), "edge"))
    CAROUSEL.append(cube((cx - 0.48, 5.65, cz + 0.48), (0.96, 0.25, 0.14), "edge"))
    CAROUSEL.append(cube((cx - 0.62, 5.65, cz - 0.34), (0.14, 0.25, 0.68), "metal"))
    CAROUSEL.append(cube((cx + 0.48, 5.65, cz - 0.34), (0.14, 0.25, 0.68), "metal"))
    CAROUSEL.append(cube((cx - 0.3, 5.9, cz - 0.3), (0.6, 1.3, 0.6), "cartridge"))
    CAROUSEL.append(cube((cx - 0.34, 7.2, cz - 0.34), (0.68, 0.24, 0.68), "brass"))

# Split center latches leave the press die a clear centerline during its downstroke.
CAROUSEL.extend((
    cube((7.68, 5.93, -0.36), (0.40, 0.21, 0.72), "edge"),
    cube((9.92, 5.93, -0.36), (0.40, 0.21, 0.72), "edge"),
    cube((8.64, 5.93, -1.32), (0.72, 0.21, 0.40), "edge"),
    cube((8.64, 5.93, 0.92), (0.72, 0.21, 0.40), "edge"),
))

DRAWER = [
    cube((16.3, 2.55, -11.4), (6.5, 0.65, 2.6), "dark"),
    cube((17.1, 3.0, -8.8), (0.45, 0.3, 1.7), "dark"),
    cube((21.4, 3.0, -8.8), (0.45, 0.3, 1.7), "dark"),
    cube((16.3, 3.2, -11.4), (6.5, 2.4, 0.6), "panel"),
    cube((16.3, 3.2, -9.4), (6.5, 1.8, 0.6), "edge"),
    cube((16.3, 3.2, -10.8), (0.6, 2.4, 1.4), "metal"),
    cube((22.2, 3.2, -10.8), (0.6, 2.4, 1.4), "metal"),
    cube((16.9, 3.2, -10.8), (5.3, 0.15, 1.4), "interior"),
    cube((18.0, 3.75, -11.78), (3.2, 0.6, 0.35), "accent"),
    cube((18.45, 3.9, -12.0), (2.3, 0.25, 0.22), "light"),
    cube((16.00, 3.20, -11.05), (0.30, 0.24, 2.05), "metal"),
    cube((22.80, 3.20, -11.05), (0.30, 0.24, 2.05), "metal"),
    cube((18.60, 3.10, -12.22), (2.70, 0.18, 0.22), "accent"),
    cube((19.00, 4.50, -11.68), (1.10, 0.35, 0.28), "metal"),
    cube((19.18, 4.60, -11.96), (0.16, 0.16, 0.16), "bolt"),
    cube((19.94, 4.60, -11.96), (0.16, 0.16, 0.16), "bolt"),
]
for roller_x in (15.84, 23.10):
    for roller_z in (-10.62, -9.95, -9.28):
        DRAWER.append(cube((roller_x, 3.43, roller_z), (0.16, 0.28, 0.28), "bolt"))
for index, (dx, dz, height) in enumerate((
        (0.0, 0.0, 0.8), (1.05, 0.0, 1.1), (2.1, 0.0, 0.7),
        (3.15, 0.0, 1.0), (4.2, 0.0, 0.85), (0.5, 1.0, 0.75),
        (1.55, 1.0, 0.9), (2.6, 1.0, 0.75), (3.65, 1.0, 0.95))):
    DRAWER.append(cube((17.2 + dx, 3.35, -10.7 + dz * 0.5),
                       (0.65, height, 0.5), "cartridge"))
    DRAWER.append(cube((17.285 + dx, 3.35 + height, -10.64 + dz * 0.5),
                       (0.48, 0.16, 0.38), "brass"))

UPGRADES = {
    0: [],
    1: [cube((1.0, 5.25, -7.88), (0.42, 6.35, 0.35), "accent"),
        cube((16.58, 5.25, -7.88), (0.42, 6.35, 0.35), "accent")],
    2: [cube((2.0, 3.55, 6.7), (6.0, 0.5, 0.48), "accent"),
        cube((10.0, 3.55, 6.7), (6.0, 0.5, 0.48), "accent")],
    3: [cube((3.0, 11.5, -7.65), (1.0, 0.45, 0.38), "light"),
        cube((8.5, 11.5, -7.65), (1.0, 0.45, 0.38), "light"),
        cube((14.0, 11.5, -7.65), (1.0, 0.45, 0.38), "light")],
    4: [cube((-0.15, 14.65, -7.25), (18.3, 0.42, 0.55), "accent"),
        cube((-0.15, 14.65, 6.7), (18.3, 0.42, 0.55), "accent")],
    5: [cube((-7.25, 2.7, -8.25), (30.0, 0.3, 0.45), "brass"),
        cube((-0.1, 15.3, -7.25), (18.2, 0.32, 0.48), "brass"),
        cube((23.74, 3.6, -5.5), (0.26, 9.1, 10.8), "brass")],
}


def strip_material(cubes):
    result = []
    for source in cubes:
        target = {key: value for key, value in source.items() if key != "material"}
        result.append(target)
    return result


def geometry_for(tier_index, tier_name):
    body = BODY + [item for level in range(tier_index + 1) for item in UPGRADES[level]]
    bones = [
        {"name": "root", "pivot": [0, 0, 0]},
        {"name": "body", "parent": "root", "pivot": [8, 0, 0], "cubes": strip_material(body)},
        {"name": "press", "parent": "root", "pivot": [9, 12, 0], "cubes": strip_material(PRESS_HOUSING)},
        {"name": "ram", "parent": "root", "pivot": [9, 10, 0], "cubes": strip_material(PRESS_RAM)},
        {"name": "carousel", "parent": "root", "pivot": [9, 5.5, 0], "cubes": strip_material(CAROUSEL)},
        {"name": "drawer", "parent": "root", "pivot": [19, 3, -7], "cubes": strip_material(DRAWER)},
    ]
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": f"geometry.miningdim.munitions_bench_{tier_name}",
                "texture_width": ATLAS_SIZE,
                "texture_height": ATLAS_SIZE,
                "visible_bounds_width": 3.5,
                "visible_bounds_height": 2.75,
                "visible_bounds_offset": [0.5, 0.85, 0],
            },
            "bones": bones,
        }],
    }


def legacy_empty_geometry():
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.miningdim.munitions_bench_legacy_empty",
                "texture_width": ATLAS_SIZE,
                "texture_height": ATLAS_SIZE,
                "visible_bounds_width": 1.0,
                "visible_bounds_height": 1.0,
                "visible_bounds_offset": [0, 0.5, 0],
            },
            "bones": [{"name": "root", "pivot": [0, 0, 0]}],
        }],
    }


def animation_definition():
    return {
        "format_version": "1.8.0",
        "animations": {
            # 待机呼吸幅度必须够肉眼分辨: 原值 -0.08 模型单位 = 1/200 格, 连一个像素都不到, 等于没有。
            # 取生产行程 (-1.95) 的四分之一, 既能看出机器在待机又不会像在工作。
            "machine.idle": {
                "loop": True,
                "animation_length": 4.0,
                "bones": {
                    "ram": {"position": {"0.0": [0, 0, 0], "2.0": [0, -0.5, 0], "4.0": [0, 0, 0]}},
                },
            },
            # 弹盘的连续旋转不在这里定义: 见 MunitionsBenchBlockEntity.advanceCarouselAngle,
            # GeckoLib 的 speed 开关会让弹盘在开停机时瞬间归零, 角度改由渲染器直接驱动骨骼。
            "machine.production": {
                "loop": True,
                "animation_length": 1.2,
                "bones": {
                    "ram": {"position": {
                        "0.0": {"vector": [0, 0, 0]},
                        "0.24": {"vector": [0, -1.95, 0], "easing": "easeInQuad"},
                        "0.38": {"vector": [0, -1.95, 0]},
                        "0.62": {"vector": [0, 0, 0], "easing": "easeOutBack"},
                        "1.2": {"vector": [0, 0, 0]},
                    }},
                    "drawer": {"position": {
                        "0.0": [0, 0, 0], "0.72": [0, 0, 0],
                        "0.9": {"vector": [0, 0, -1.4], "easing": "easeOutSine"},
                        "1.2": {"vector": [0, 0, 0], "easing": "easeInSine"},
                    }},
                },
            },
        },
    }


def shifted(color, amount, alpha=None):
    rgba = color if len(color) == 4 else (*color, 255)
    result = tuple(max(0, min(255, channel + amount)) for channel in rgba[:3])
    return (*result, rgba[3] if alpha is None else alpha)


def mixed(first, second, ratio=0.5, alpha=255):
    return tuple(round(first[index] * (1 - ratio) + second[index] * ratio)
                 for index in range(3)) + (alpha,)


def resolved_palette(source):
    palette = dict(source)
    palette["edge"] = mixed(source["dark"], source["metal"], 0.4)
    palette["vent"] = mixed(source["rubber"], source["accent"], 0.18)
    palette["bolt"] = shifted(source["metal"], 48)
    palette["trim"] = mixed(source["metal"], source["accent"], 0.55)
    return palette


def draw_patch_frame(draw, box, highlight, shadow, width=1):
    x0, y0, x1, y1 = box
    draw.line((x0, y0, x1, y0), fill=highlight, width=width)
    draw.line((x0, y0, x0, y1), fill=highlight, width=width)
    draw.line((x0, y1, x1, y1), fill=shadow, width=width)
    draw.line((x1, y0, x1, y1), fill=shadow, width=width)


def draw_material_patch(draw, material, color, origin):
    x0, y0 = origin
    x1, y1 = x0 + PATCH_SIZE - 1, y0 + PATCH_SIZE - 1
    base = (*color[:3], 255)
    draw.rectangle((x0, y0, x1, y1), fill=base)

    # Broad deterministic value blocks stop large faces reading as unlit primitives.
    material_index = MATERIALS.index(material)
    for row in range(0, PATCH_SIZE, 32):
        for column in range(0, PATCH_SIZE, 32):
            delta = ((row // 32) * 3 + column // 32 + material_index) % 5 - 2
            draw.rectangle((x0 + column, y0 + row,
                            x0 + min(column + 31, PATCH_SIZE - 1),
                            y0 + min(row + 31, PATCH_SIZE - 1)),
                           fill=shifted(color, delta, 255))

    content = (x0 + UV_GUTTER, y0 + UV_GUTTER,
               x1 - UV_GUTTER, y1 - UV_GUTTER)
    cx0, cy0, cx1, cy1 = content
    draw_patch_frame(draw, content, shifted(color, 12, 255), shifted(color, -18, 255))

    if material == "dark":
        for offset in (28, 60, 92):
            draw.line((x0 + 22, y0 + offset, x1 - 18, y0 + offset),
                      fill=shifted(color, -10, 255), width=2)
            draw.line((x0 + 24, y0 + offset + 2, x1 - 28, y0 + offset + 2),
                      fill=shifted(color, 4, 255), width=1)
    elif material == "interior":
        for offset in (30, 62, 94):
            draw.line((x0 + 22, y0 + offset, x1 - 18, y0 + offset),
                      fill=shifted(color, -12, 255), width=2)
        for streak_x, streak_y, streak_length in ((34, 39, 19), (77, 24, 31), (99, 73, 18)):
            draw.line((x0 + streak_x, y0 + streak_y,
                       x0 + streak_x, y0 + streak_y + streak_length),
                      fill=shifted(color, -7, 255), width=2)
            draw.line((x0 + streak_x + 2, y0 + streak_y + 4,
                       x0 + streak_x + 2, y0 + streak_y + streak_length - 3),
                      fill=shifted(color, 3, 255), width=1)
    elif material == "rubber":
        for offset in (28, 52, 76, 100):
            draw.line((x0 + 20, y0 + offset, x0 + 42, y0 + offset + 5,
                       x0 + 64, y0 + offset), fill=shifted(color, -6, 255), width=2)
            draw.line((x0 + 66, y0 + offset, x0 + 88, y0 + offset + 5,
                       x0 + 108, y0 + offset), fill=shifted(color, 3, 255), width=1)
    elif material == "metal":
        for index, offset in enumerate(range(22, 108, 11)):
            start = x0 + 20 + (index % 3) * 9
            draw.line((start, y0 + offset, min(start + 42, x1 - 18), y0 + offset),
                      fill=shifted(color, 5 if index % 2 else -4, 255), width=1)
        draw.line((x0 + 64, y0 + 20, x0 + 64, y1 - 20),
                  fill=shifted(color, -10, 255), width=2)
        draw.line((x0 + 66, y0 + 24, x0 + 66, y1 - 24),
                  fill=shifted(color, 8, 255), width=1)
    elif material == "panel":
        draw_patch_frame(draw, (x0 + 22, y0 + 22, x1 - 22, y1 - 22),
                         shifted(color, 10, 255), shifted(color, -14, 255), width=2)
        draw.line((x0 + 64, y0 + 24, x0 + 64, y1 - 24),
                  fill=shifted(color, -8, 255), width=1)
        draw.line((x0 + 24, y0 + 64, x1 - 24, y0 + 64),
                  fill=shifted(color, -8, 255), width=1)
        for chip_x, chip_y in ((30, 25), (84, 37), (46, 89), (96, 98)):
            draw.rectangle((x0 + chip_x, y0 + chip_y, x0 + chip_x + 2, y0 + chip_y + 1),
                           fill=shifted(color, 18, 255))
    elif material == "glass":
        for offset in (8, 36, 64):
            draw.line((cx0, cy0 + offset, cx0 + 30, cy0 + offset - 30),
                      fill=shifted(color, 10, 255), width=2)
    elif material in {"brass", "cartridge"}:
        for offset in (26, 50, 74, 98):
            draw.line((x0 + offset, y0 + 20, x0 + offset, y1 - 20),
                      fill=shifted(color, 18, 255), width=2)
            draw.line((x0 + offset + 3, y0 + 22, x0 + offset + 3, y1 - 22),
                      fill=shifted(color, -16, 255), width=1)
        for mark_x, mark_y in ((35, 42), (77, 31), (91, 82), (52, 101)):
            draw.rectangle((x0 + mark_x, y0 + mark_y, x0 + mark_x + 2, y0 + mark_y + 2),
                           fill=shifted(color, -24, 255))
    elif material in {"accent", "light", "screen"}:
        draw.rectangle((cx0 + 3, cy0 + 3, cx1 - 3, cy1 - 3),
                       outline=shifted(color, -24, 255), width=2)
        for offset in (28, 52, 76, 100):
            draw.line((x0 + 22, y0 + offset, x1 - 22, y0 + offset),
                      fill=shifted(color, 20 if material == "light" else 10, 255), width=2)
            draw.line((x0 + 24, y0 + offset + 3, x1 - 28, y0 + offset + 3),
                      fill=shifted(color, -12, 255), width=1)
    elif material == "warning":
        for stripe_x, stripe_y in ((22, 58), (64, 58), (22, 110), (64, 110)):
            draw.line((x0 + stripe_x, y0 + stripe_y,
                       x0 + stripe_x + 36, y0 + stripe_y - 36),
                      fill=shifted(color, -34, 255), width=7)
    elif material in {"edge", "trim"}:
        draw.rectangle((cx0 + 2, cy0 + 2, cx1 - 2, cy0 + 20),
                       fill=shifted(color, 14, 255))
        draw.rectangle((cx0 + 2, cy1 - 20, cx1 - 2, cy1 - 2),
                       fill=shifted(color, -16, 255))
        draw.line((cx0 + 4, cy0 + 24, cx1 - 4, cy0 + 24),
                  fill=shifted(color, 7, 255), width=2)
    elif material == "vent":
        for offset in (24, 40, 56, 72, 88, 104):
            draw.rectangle((x0 + 20, y0 + offset, x1 - 20, y0 + offset + 4),
                           fill=shifted(color, -24, 255))
            draw.line((x0 + 22, y0 + offset + 5, x1 - 24, y0 + offset + 5),
                      fill=shifted(color, 8, 255), width=1)
    elif material == "bolt":
        for center_x in (32, 64, 96):
            for center_y in (32, 64, 96):
                draw.rectangle((x0 + center_x - 3, y0 + center_y - 3,
                                x0 + center_x + 3, y0 + center_y + 3),
                               fill=shifted(color, -12, 255))
                draw.line((x0 + center_x - 2, y0 + center_y - 2,
                           x0 + center_x + 1, y0 + center_y - 2),
                          fill=shifted(color, 28, 255), width=1)


def build_texture(palette):
    palette = resolved_palette(palette)
    image = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image, "RGBA")
    for material in MATERIALS:
        draw_material_patch(draw, material, palette[material], PATCH_ORIGINS[material])
    return image


def mirrored_origin_x(origin_x, size_x, plane):
    """GeckoLib 的 BakedModelFactory 把每个 cube 的 X 取负 (origin.x -> -(origin.x + size.x)), 所以世界里
    的机器沿 X 轴是镜像的。凡是要还原"玩家实际看到的样子"的地方都必须过一次这个换算, 否则会左右颠倒。
    plane 是镜像轴在目标坐标系里的位置乘 2: 物品模型坐标取 8 (原版 0-16 一格 + GeckoLib 的 0.5 格平移),
    评审预览沿用 16 以保持原有构图。"""
    return plane - origin_x - size_x


def mirrored_box(source):
    """把 bedrock 立方体换算成原版物品模型坐标 (含 GeckoLib 的 X 镜像与 z 的 +8 平移)。"""
    origin, size = source["origin"], source["size"]
    x1 = mirrored_origin_x(origin[0], size[0], 8.0)
    y1 = origin[1]
    z1 = origin[2] + 8
    return [x1, y1, z1], [x1 + size[0], y1 + size[1], z1 + size[2]]


def rendered_cube(source):
    """评审预览与正交三视图共用的镜像换算, 保持原有构图中心不变。"""
    cube = dict(source)
    cube["origin"] = [mirrored_origin_x(source["origin"][0], source["size"][0], 16.0),
                      source["origin"][1], source["origin"][2]]
    return cube


FACE_AXIS = {
    "west": (0, False), "east": (0, True),
    "down": (1, False), "up": (1, True),
    "north": (2, False), "south": (2, True),
}


def rectangles_cover(target, rectangles):
    """判断若干矩形的并集是否完整覆盖 target。坐标压缩后逐格检查, 单个面的候选矩形只有几十个, 够快。"""
    (a1, b1), (a2, b2) = target
    xs = sorted({a1, a2} | {value for rect in rectangles for value in (rect[0][0], rect[1][0])
                            if a1 < value < a2})
    ys = sorted({b1, b2} | {value for rect in rectangles for value in (rect[0][1], rect[1][1])
                            if b1 < value < b2})
    for xi in range(len(xs) - 1):
        for yi in range(len(ys) - 1):
            cx = (xs[xi] + xs[xi + 1]) / 2
            cy = (ys[yi] + ys[yi + 1]) / 2
            if not any(rect[0][0] <= cx <= rect[1][0] and rect[0][1] <= cy <= rect[1][1]
                       for rect in rectangles):
                return False
    return True


def face_is_hidden(box, others, face):
    """某个面是否被别的立方体挡住。挡住它的可以是若干个立方体的并集 —— 机身内部的件大多是被四五块外壳
    合起来盖住的, 只做单块包含判定几乎剪不掉东西。全部材质都是不透明色块, 所以删掉被挡的面观感无损。"""
    axis, positive = FACE_AXIS[face]
    lo, hi = box
    plane = hi[axis] if positive else lo[axis]
    first, second = [index for index in range(3) if index != axis]
    blockers = []
    for other_lo, other_hi in others:
        if positive:
            covers_plane = other_lo[axis] <= plane + 1e-6 < other_hi[axis] - 1e-6
        else:
            covers_plane = other_lo[axis] + 1e-6 < plane <= other_hi[axis] + 1e-6
        if not covers_plane:
            continue
        if other_hi[first] <= lo[first] or other_lo[first] >= hi[first]:
            continue
        if other_hi[second] <= lo[second] or other_lo[second] >= hi[second]:
            continue
        blockers.append(((other_lo[first], other_lo[second]), (other_hi[first], other_hi[second])))
    if not blockers:
        return False
    return rectangles_cover(((lo[first], lo[second]), (hi[first], hi[second])), blockers)


def rotate_xyz(point, degrees):
    """复现原版 ItemTransform.apply 的 Quaternionf.rotationXYZ, 即 Rx * Ry * Rz。"""
    x, y, z = point
    for axis, angle in reversed(list(enumerate(degrees))):
        radians = math.radians(angle)
        cos, sin = math.cos(radians), math.sin(radians)
        if axis == 0:
            y, z = y * cos - z * sin, y * sin + z * cos
        elif axis == 1:
            x, z = x * cos + z * sin, -x * sin + z * cos
        else:
            x, y = x * cos - y * sin, x * sin + y * cos
    return x, y, z


def display_transform(bounds, rotation, scale=None, slot_pixels=15.0):
    """按包围盒解出 display 变换, 而不是手调常数。

    原版 GUI 渲染链是 translate(x+8,y+8) -> scale(16,-16,16) -> ItemTransform(translate/rotate/scale)
    -> translate(-0.5,-0.5,-0.5) -> 模型坐标 /16。所以屏幕像素 = 16 * (translation + R * scale * (p/16 - 0.5))。
    这里把 8 个角点代进去求投影包围盒, 解出让图标正好落在 slot_pixels 内的 scale 与居中用的 translation。
    机身横跨两格, 几何中心远离模型空间中心 (8,8,8), 手调常数必然溢出槽位。
    """
    lo, hi = bounds
    corners = [rotate_xyz(
        [(value / 16.0) - 0.5 for value in (x, y, z)], rotation)
        for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    spans = [(min(corner[axis] for corner in corners), max(corner[axis] for corner in corners))
             for axis in range(3)]
    if scale is None:
        widest = max(spans[0][1] - spans[0][0], spans[1][1] - spans[1][0])
        scale = round(slot_pixels / (16.0 * widest), 3)
    translation = [round(-scale * (spans[axis][0] + spans[axis][1]) / 2 * 16, 3) for axis in range(2)]
    transform = {"translation": translation + [0.0], "scale": [scale, scale, scale]}
    if any(rotation):
        transform["rotation"] = list(rotation)
    return transform


def item_model(texture_name, tier_index):
    cubes = BODY + [item for level in range(tier_index + 1) for item in UPGRADES[level]]
    cubes += PRESS + CAROUSEL + DRAWER
    boxes = [mirrored_box(source) for source in cubes]
    safe_span = PATCH_SIZE - UV_GUTTER * 2
    uv_scale = ATLAS_SIZE / 16
    elements = []
    for source, box in zip(cubes, boxes):
        others = [candidate for candidate in boxes if candidate is not box]
        patch_x, patch_y = UV[source["material"]]
        uv = [patch_x / uv_scale, patch_y / uv_scale,
              (patch_x + safe_span) / uv_scale, (patch_y + safe_span) / uv_scale]
        faces = {face: {"texture": "#atlas", "uv": uv}
                 for face in FACE_AXIS if not face_is_hidden(box, others, face)}
        if not faces:
            continue
        elements.append({"from": box[0], "to": box[1], "faces": faces})

    for element in elements:
        for key in ("from", "to"):
            for value in element[key]:
                if not -16.0 <= value <= 32.0:
                    raise ValueError(
                        f"{texture_name} 的 element {key}={element[key]} 越出原版 BlockElement 的 "
                        f"[-16, 32] 边界; 原版会抛 JsonParseException 并把整个物品模型降级成缺失模型")

    lo = [min(element["from"][axis] for element in elements) for axis in range(3)]
    hi = [max(element["to"][axis] for element in elements) for axis in range(3)]
    bounds = (lo, hi)
    return {
        "parent": "minecraft:block/block",
        "texture_size": [ATLAS_SIZE, ATLAS_SIZE],
        "textures": {"atlas": f"miningdim:block/{texture_name}", "particle": f"miningdim:block/{texture_name}"},
        "display": {
            "gui": display_transform(bounds, (28, 225, 0)),
            "ground": display_transform(bounds, (0, 0, 0), 0.3),
            "fixed": display_transform(bounds, (0, 0, 0), 0.4),
            "firstperson_righthand": display_transform(bounds, (0, 225, 0), 0.28),
            "firstperson_lefthand": display_transform(bounds, (0, 45, 0), 0.28),
            "thirdperson_righthand": display_transform(bounds, (65, 225, 0), 0.24),
            "thirdperson_lefthand": display_transform(bounds, (65, 45, 0), 0.24),
        },
        "elements": elements,
    }


def project(point, ox, oy, scale):
    x, y, z = point
    return (ox + (x + z * 0.55) * scale,
            oy + (z * 0.55 - x * 0.12) * scale - y * scale)


def preview_faces(source, palette, ox, oy, scale):
    # 过一次 rendered_cube 的 X 镜像, 否则评审预览图与游戏里的实际观感左右颠倒。
    x, y, z = rendered_cube(source)["origin"]
    dx, dy, dz = source["size"]
    color = palette[source["material"]]
    rgb = color[:3]
    alpha = color[3] if len(color) == 4 else 255
    faces = (
        ((x + dx * 0.5) * 0.4 + (y + dy * 0.5) * 0.25 - z, "front",
         ((x, y, z), (x + dx, y, z), (x + dx, y + dy, z), (x, y + dy, z)), -18),
        ((x + dx) * 0.4 + (y + dy * 0.5) * 0.25 - (z + dz * 0.5), "side",
         ((x + dx, y, z), (x + dx, y, z + dz),
          (x + dx, y + dy, z + dz), (x + dx, y + dy, z)), -36),
        ((x + dx * 0.5) * 0.4 + (y + dy) * 0.25 - (z + dz * 0.5), "top",
         ((x, y + dy, z), (x + dx, y + dy, z),
          (x + dx, y + dy, z + dz), (x, y + dy, z + dz)), 24),
    )
    result = []
    for depth, face_name, points, shade in faces:
        result.append({
            "depth": depth,
            "name": face_name,
            "points": [project(point, ox, oy, scale) for point in points],
            "fill": (*shifted(rgb, shade)[:3], alpha),
            "material": source["material"],
            "size": (dx, dy, dz),
            "highlight": (*shifted(rgb, 72)[:3], min(alpha, 230)),
        })
    return result


def build_preview():
    canvas = Image.new("RGBA", (1536, 1024), (15, 19, 25, 255))
    background = ImageDraw.Draw(canvas, "RGBA")
    for y in range(1024):
        lift = round(12 * (1 - abs(y - 360) / 720))
        lift = max(0, lift)
        background.line((0, y, 1536, y), fill=(15 + lift, 19 + lift, 25 + lift, 255))
    for tier_index, (_, _, palette) in enumerate(TIERS):
        palette = resolved_palette(palette)
        col, row = tier_index % 3, tier_index // 3
        ox, oy = 210 + col * 510, 465 + row * 440
        cubes = BODY + [item for level in range(tier_index + 1) for item in UPGRADES[level]]
        cubes += PRESS + CAROUSEL + DRAWER
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer, "RGBA")
        draw.ellipse((ox - 125, oy + 20, ox + 280, oy + 92), fill=(0, 0, 0, 115))
        faces = []
        for source in cubes:
            faces.extend(preview_faces(source, palette, ox, oy, 9.5))
        for face in sorted(faces, key=lambda item: item["depth"]):
            face_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
            face_draw = ImageDraw.Draw(face_layer, "RGBA")
            face_draw.polygon(face["points"], fill=face["fill"], outline=(7, 9, 12, 235), width=2)
            face_draw.line((face["points"][3], face["points"][2]), fill=face["highlight"], width=1)
            face_draw.line((face["points"][0], face["points"][1]), fill=(3, 5, 7, 210), width=2)
            if face["material"] in {"accent", "light", "screen"}:
                glow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
                glow_draw = ImageDraw.Draw(glow_layer, "RGBA")
                glow_draw.line((face["points"][-1], face["points"][-2]),
                               fill=(*face["highlight"][:3], 70), width=7)
                layer = Image.alpha_composite(layer, glow_layer)
                face_draw.line((face["points"][-1], face["points"][-2]), fill=face["highlight"], width=3)
            layer = Image.alpha_composite(layer, face_layer)
        canvas = Image.alpha_composite(canvas, layer)
    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(PREVIEW_PATH)


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    GEO_DIR.mkdir(parents=True, exist_ok=True)
    ANIMATION_DIR.mkdir(parents=True, exist_ok=True)
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    ITEM_DIR.mkdir(parents=True, exist_ok=True)
    write_json(ANIMATION_DIR / "munitions_bench.animation.json", animation_definition())
    write_json(GEO_DIR / "munitions_bench_legacy_empty.geo.json", legacy_empty_geometry())
    for tier_index, (suffix, tier_name, palette) in enumerate(TIERS):
        texture_name = f"munitions_bench_geo{suffix}"
        write_json(GEO_DIR / f"munitions_bench{suffix}.geo.json", geometry_for(tier_index, tier_name))
        build_texture(palette).save(TEXTURE_DIR / f"{texture_name}.png")
        write_json(ITEM_DIR / f"munitions_bench{suffix}.json", item_model(texture_name, tier_index))
    build_preview()


if __name__ == "__main__":
    main()
