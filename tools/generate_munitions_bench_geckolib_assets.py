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

ATLAS_SIZE = 256
PATCH_SIZE = 64
BODY_HEIGHT_SCALE = 1.42
MATERIALS = (
    "dark", "metal", "panel", "glass",
    "brass", "rubber", "accent", "light",
    "interior", "cartridge", "warning", "screen",
)
UV = {name: ((index % 4) * PATCH_SIZE, (index // 4) * PATCH_SIZE)
      for index, name in enumerate(MATERIALS)}

TIERS = (
    ("", "base", {
        "dark": (24, 28, 31), "metal": (77, 83, 86), "panel": (74, 79, 61),
        "glass": (67, 84, 82, 62), "brass": (181, 133, 49), "rubber": (16, 18, 19),
        "accent": (116, 128, 84), "light": (150, 167, 104), "interior": (32, 36, 37),
        "cartridge": (211, 159, 65), "warning": (180, 75, 48), "screen": (119, 154, 112),
    }),
    ("_medium", "medium", {
        "dark": (25, 30, 34), "metal": (92, 99, 103), "panel": (72, 78, 80),
        "glass": (43, 106, 112, 62), "brass": (190, 145, 52), "rubber": (16, 20, 22),
        "accent": (23, 175, 189), "light": (42, 230, 239), "interior": (30, 42, 45),
        "cartridge": (221, 169, 69), "warning": (195, 88, 51), "screen": (54, 205, 216),
    }),
    ("_high", "high", {
        "dark": (15, 29, 45), "metal": (51, 79, 111), "panel": (26, 61, 104),
        "glass": (29, 84, 132, 64), "brass": (194, 148, 52), "rubber": (11, 20, 29),
        "accent": (28, 128, 222), "light": (51, 189, 255), "interior": (21, 40, 60),
        "cartridge": (225, 173, 72), "warning": (208, 82, 46), "screen": (54, 168, 244),
    }),
    ("_superior", "superior", {
        "dark": (34, 24, 43), "metal": (80, 58, 99), "panel": (73, 47, 96),
        "glass": (92, 52, 128, 64), "brass": (199, 151, 56), "rubber": (21, 16, 27),
        "accent": (129, 61, 176), "light": (196, 92, 247), "interior": (43, 29, 52),
        "cartridge": (225, 172, 72), "warning": (205, 70, 56), "screen": (184, 81, 226),
    }),
    ("_transcendent", "transcendent", {
        "dark": (36, 23, 25), "metal": (88, 60, 62), "panel": (66, 44, 45),
        "glass": (113, 45, 49, 64), "brass": (205, 153, 55), "rubber": (25, 15, 16),
        "accent": (162, 39, 43), "light": (250, 72, 73), "interior": (48, 29, 30),
        "cartridge": (230, 171, 69), "warning": (240, 76, 42), "screen": (221, 62, 62),
    }),
    ("_radiant", "radiant", {
        "dark": (43, 43, 49), "metal": (195, 195, 191), "panel": (224, 222, 211),
        "glass": (111, 76, 148, 60), "brass": (224, 178, 62), "rubber": (34, 31, 40),
        "accent": (183, 126, 38), "light": (205, 105, 247), "interior": (65, 60, 70),
        "cartridge": (247, 195, 75), "warning": (210, 110, 51), "screen": (190, 93, 235),
    }),
)


def cube(origin, size, material, *, rotation=None, pivot=None):
    ox, oy, oz = origin
    sx, sy, sz = size
    if oy >= 2:
        oy = 2 + (oy - 2) * BODY_HEIGHT_SCALE
        sy *= BODY_HEIGHT_SCALE
    result = {"origin": [ox, oy, oz], "size": [sx, sy, sz], "uv": list(UV[material])}
    if rotation is not None:
        result["rotation"] = list(rotation)
        result["pivot"] = list(pivot)
    result["material"] = material
    return result


BODY = [
    cube((-8, 0, -8), (16, 2, 16), "dark"),
    cube((8, 0, -8), (16, 2, 16), "dark"),
    cube((-7, 2, -7), (30, 1, 14), "metal"),
    cube((-8, 1.2, -8.3), (32, 0.8, 1), "metal"),
    cube((-7, 3, -6.8), (7, 5, 4), "panel"),
    cube((-6.3, 4, -7.35), (4.2, 2.4, 0.7), "interior"),
    cube((-5.8, 4.5, -7.75), (3.2, 1.2, 0.4), "screen"),
    cube((-1.8, 4.4, -7.6), (0.8, 0.8, 0.5), "light"),
    cube((-6, 7.5, -1.5), (5, 3, 6), "panel"),
    cube((-7, 10.5, -2.5), (7, 4, 8), "panel"),
    cube((-7.5, 14.2, -3), (8, 0.8, 9), "metal"),
    cube((-6.5, 13.8, -2), (6, 0.5, 7), "interior"),
    cube((0, 3, -6.5), (18, 2, 13), "dark"),
    cube((0, 13, -6.5), (18, 2, 13), "panel"),
    cube((0, 4, -6.8), (2, 9, 2), "metal"),
    cube((16, 4, -6.8), (2, 9, 2), "metal"),
    cube((0, 4, 4.8), (2, 9, 2), "metal"),
    cube((16, 4, 4.8), (2, 9, 2), "metal"),
    cube((2, 5, 5.6), (14, 8, 0.6), "interior"),
    cube((2, 5, -7.05), (14, 7.5, 0.35), "glass"),
    cube((0.25, 5, -4.5), (0.35, 7.5, 9), "glass"),
    cube((17.4, 5, -4.5), (0.35, 7.5, 9), "glass"),
    cube((2.5, 13.2, -7.35), (13, 0.8, 0.5), "light"),
    cube((2.5, 4.1, -5.9), (13, 0.8, 4), "rubber"),
    cube((18, 3, -5.8), (5, 10, 11), "dark"),
    cube((18.3, 4, -6.35), (4.4, 8, 0.6), "metal"),
    cube((19, 5, -6.75), (3, 0.6, 0.5), "light"),
    cube((19, 7, -6.75), (3, 0.6, 0.5), "accent"),
    cube((19, 9, -6.75), (3, 0.6, 0.5), "accent"),
    cube((19, 11, -6.75), (3, 0.6, 0.5), "accent"),
    cube((18, 6, -4.5), (5, 1, 8), "metal"),
]

PRESS = [
    cube((5.5, 10, -1.5), (7, 3, 5), "metal"),
    cube((7.5, 7, -0.2), (3, 4, 2.4), "accent"),
    cube((6.5, 6, -1.3), (5, 2, 4.6), "brass"),
]

CAROUSEL = [
    cube((6, 4.9, -3), (6, 1.3, 6), "metal"),
    cube((7, 5.9, -2), (4, 0.8, 4), "brass"),
    cube((3.7, 5.3, -0.7), (10.6, 0.8, 1.4), "brass"),
    cube((8.3, 5.3, -5.3), (1.4, 0.8, 10.6), "brass"),
]
for angle in range(0, 360, 45):
    radians = math.radians(angle)
    CAROUSEL.append(cube((8.35 + math.cos(radians) * 4.2,
                          6.0,
                          -0.65 + math.sin(radians) * 4.2),
                         (1.3, 1.4, 1.3), "cartridge"))

BELT = [cube((2.8 + index * 2.4, 4.85, -6.1), (1.2, 0.5, 4.4), "accent")
        for index in range(6)]

DRAWER = [
    cube((15, 2, -7.8), (8, 4, 3), "panel"),
    cube((17.5, 3.2, -8.35), (3, 0.8, 0.8), "accent"),
]

UPGRADES = {
    0: [],
    1: [cube((0.7, 5.2, -7.45), (0.7, 6.5, 0.4), "accent"),
        cube((16.6, 5.2, -7.45), (0.7, 6.5, 0.4), "accent")],
    2: [cube((1.8, 3.5, 5.9), (6, 1, 0.7), "accent"),
        cube((10.2, 3.5, 5.9), (6, 1, 0.7), "accent")],
    3: [cube((2.8, 11.2, -7.55), (1, 1, 0.6), "light"),
        cube((8.5, 11.2, -7.55), (1, 1, 0.6), "light"),
        cube((14.2, 11.2, -7.55), (1, 1, 0.6), "light")],
    4: [cube((-0.5, 14.7, -6.8), (19, 0.7, 1.2), "accent"),
        cube((-0.5, 14.7, 5.6), (19, 0.7, 1.2), "accent")],
    5: [cube((-7.7, 2.2, -7.5), (31.4, 0.5, 0.8), "brass"),
        cube((-7.7, 14.8, -7.5), (31.4, 0.5, 0.8), "brass"),
        cube((22.8, 3, -5.5), (0.7, 10, 10), "brass")],
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
        {"name": "press", "parent": "root", "pivot": [9, 12, 0], "cubes": strip_material(PRESS)},
        {"name": "carousel", "parent": "root", "pivot": [9, 5.5, 0], "cubes": strip_material(CAROUSEL)},
        {"name": "belt", "parent": "root", "pivot": [9, 4.8, -4], "cubes": strip_material(BELT)},
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
            "machine.idle": {
                "loop": True,
                "animation_length": 4.0,
                "bones": {
                    "carousel": {"rotation": {"0.0": [0, 0, 0], "4.0": [0, 45, 0]}},
                    "press": {"position": {"0.0": [0, 0, 0], "2.0": [0, -0.15, 0], "4.0": [0, 0, 0]}},
                },
            },
            "machine.production": {
                "loop": True,
                "animation_length": 1.2,
                "bones": {
                    "press": {"position": {
                        "0.0": {"vector": [0, 0, 0]},
                        "0.24": {"vector": [0, -3.2, 0], "easing": "easeInQuad"},
                        "0.38": {"vector": [0, -3.2, 0]},
                        "0.62": {"vector": [0, 0, 0], "easing": "easeOutBack"},
                        "1.2": {"vector": [0, 0, 0]},
                    }},
                    "carousel": {"rotation": {
                        "0.0": [0, 0, 0], "0.38": [0, 0, 0],
                        "0.72": {"vector": [0, 45, 0], "easing": "easeInOutSine"},
                        "1.2": [0, 45, 0],
                    }},
                    "belt": {"position": {"0.0": [0, 0, 0], "1.2": [2.4, 0, 0]}},
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


def build_texture(palette):
    image = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image, "RGBA")
    for material, color in palette.items():
        x, y = UV[material]
        rgba = color if len(color) == 4 else (*color, 255)
        draw.rectangle((x, y, x + 63, y + 63), fill=rgba)
        draw.rectangle((x, y, x + 63, y + 7), fill=shifted(rgba, 24))
        draw.rectangle((x, y + 56, x + 63, y + 63), fill=shifted(rgba, -28))
        draw.rectangle((x, y, x + 7, y + 63), fill=shifted(rgba, 12))
        draw.line((x + 8, y + 16, x + 55, y + 16), fill=shifted(rgba, 14), width=2)
        draw.line((x + 8, y + 40, x + 55, y + 40), fill=shifted(rgba, -14), width=2)
    return image


def item_model(texture_name, tier_index):
    cubes = BODY + [item for level in range(tier_index + 1) for item in UPGRADES[level]]
    cubes += PRESS + CAROUSEL + BELT + DRAWER
    elements = []
    for source in cubes:
        origin = source["origin"]
        size = source["size"]
        x1, y1, z1 = origin[0] + 8, origin[1], origin[2] + 8
        x2, y2, z2 = x1 + size[0], y1 + size[1], z1 + size[2]
        patch_x, patch_y = UV[source["material"]]
        uv = [patch_x / 16, patch_y / 16, (patch_x + PATCH_SIZE) / 16, (patch_y + PATCH_SIZE) / 16]
        faces = {face: {"texture": "#atlas", "uv": uv}
                 for face in ("north", "south", "east", "west", "up", "down")}
        elements.append({"from": [x1, y1, z1], "to": [x2, y2, z2], "faces": faces})
    return {
        "parent": "minecraft:block/block",
        "texture_size": [ATLAS_SIZE, ATLAS_SIZE],
        "textures": {"atlas": f"miningdim:block/{texture_name}", "particle": f"miningdim:block/{texture_name}"},
        "display": {
            "gui": {"rotation": [28, 225, 0], "translation": [-1.5, -2.5, 0], "scale": [0.42, 0.42, 0.42]},
            "ground": {"translation": [-4, 2, 4], "scale": [0.3, 0.3, 0.3]},
            "fixed": {"translation": [-4, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_righthand": {"rotation": [0, 225, 0], "scale": [0.28, 0.28, 0.28]},
            "firstperson_lefthand": {"rotation": [0, 45, 0], "scale": [0.28, 0.28, 0.28]},
            "thirdperson_righthand": {"rotation": [65, 225, 0], "scale": [0.24, 0.24, 0.24]},
            "thirdperson_lefthand": {"rotation": [65, 45, 0], "scale": [0.24, 0.24, 0.24]},
        },
        "elements": elements,
    }


def project(point, ox, oy, scale):
    x, y, z = point
    return (ox + (x - z) * scale, oy + (x + z) * scale * 0.42 - y * scale)


def preview_cube(draw, source, palette, ox, oy, scale):
    x, y, z = source["origin"]
    dx, dy, dz = source["size"]
    color = palette[source["material"]]
    rgb = color[:3]
    top = [project(p, ox, oy, scale) for p in ((x, y + dy, z), (x + dx, y + dy, z),
                                                (x + dx, y + dy, z + dz), (x, y + dy, z + dz))]
    front = [project(p, ox, oy, scale) for p in ((x, y, z), (x + dx, y, z),
                                                  (x + dx, y + dy, z), (x, y + dy, z))]
    side = [project(p, ox, oy, scale) for p in ((x + dx, y, z), (x + dx, y, z + dz),
                                                 (x + dx, y + dy, z + dz), (x + dx, y + dy, z))]
    alpha = color[3] if len(color) == 4 else 255
    draw.polygon(front, fill=(*shifted(rgb, -18)[:3], alpha), outline=(12, 14, 17, 255))
    draw.polygon(side, fill=(*shifted(rgb, -32)[:3], alpha), outline=(12, 14, 17, 255))
    draw.polygon(top, fill=(*shifted(rgb, 18)[:3], alpha), outline=(12, 14, 17, 255))


def build_preview():
    canvas = Image.new("RGBA", (1536, 1024), (17, 21, 27, 255))
    for tier_index, (_, _, palette) in enumerate(TIERS):
        col, row = tier_index % 3, tier_index // 3
        ox, oy = 190 + col * 510, 300 + row * 470
        cubes = BODY + [item for level in range(tier_index + 1) for item in UPGRADES[level]]
        cubes += PRESS + CAROUSEL + BELT + DRAWER
        cubes = sorted(cubes, key=lambda item:
                       item["origin"][0] + item["size"][0] * 0.5
                       - item["origin"][2] - item["size"][2] * 0.5
                       + item["origin"][1] * 0.02)
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer, "RGBA")
        draw.ellipse((ox - 145, oy + 35, ox + 155, oy + 95), fill=(0, 0, 0, 110))
        for source in cubes:
            cube_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
            preview_cube(ImageDraw.Draw(cube_layer, "RGBA"), source, palette, ox, oy, 7.5)
            layer = Image.alpha_composite(layer, cube_layer)
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
