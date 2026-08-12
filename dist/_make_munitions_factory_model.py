import hashlib
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "src/main/resources/assets/miningdim"
BLOCKSTATE_ROOT = ASSET_ROOT / "blockstates"
BLOCK_MODEL_ROOT = ASSET_ROOT / "models/block"
ITEM_MODEL_ROOT = ASSET_ROOT / "models/item"
TEXTURE_ROOT = ASSET_ROOT / "textures/block"
PREVIEW_PATH = ROOT / "dist/munitions-factory-redesign-preview.png"

FACE_NAMES = ("north", "south", "east", "west", "up", "down")
TEXTURE_KEYS = ("base", "black", "metal", "panel", "glass", "brass", "hot", "vent")
PART_BOUNDS = {
    "main": (0.0, 16.0),
    "extension": (16.0, 32.0),
}

TIERS = (
    {
        "suffix": "",
        "label": "军火台",
        "subtitle": "兼容生产单元",
        "base": (48, 56, 63),
        "black": (17, 22, 27),
        "metal": (105, 119, 129),
        "accent": (224, 154, 42),
        "glass": (116, 94, 55),
        "brass": (180, 124, 36),
        "hot": (247, 86, 25),
    },
    {
        "suffix": "_medium",
        "label": "中级",
        "subtitle": "双路供料",
        "base": (38, 58, 63),
        "black": (14, 24, 27),
        "metal": (82, 125, 128),
        "accent": (38, 211, 191),
        "glass": (42, 116, 118),
        "brass": (184, 137, 47),
        "hot": (255, 103, 30),
    },
    {
        "suffix": "_high",
        "label": "高级",
        "subtitle": "监测增压",
        "base": (35, 50, 74),
        "black": (13, 20, 31),
        "metal": (75, 108, 142),
        "accent": (44, 190, 239),
        "glass": (40, 91, 139),
        "brass": (190, 145, 50),
        "hot": (255, 94, 33),
    },
    {
        "suffix": "_superior",
        "label": "极品",
        "subtitle": "精密冷却",
        "base": (57, 41, 74),
        "black": (23, 16, 31),
        "metal": (116, 86, 139),
        "accent": (184, 86, 235),
        "glass": (94, 52, 132),
        "brass": (202, 152, 60),
        "hot": (255, 80, 46),
    },
    {
        "suffix": "_transcendent",
        "label": "超凡",
        "subtitle": "高热能生产",
        "base": (73, 37, 42),
        "black": (29, 14, 17),
        "metal": (139, 74, 79),
        "accent": (239, 67, 75),
        "glass": (126, 47, 55),
        "brass": (216, 157, 61),
        "hot": (255, 119, 34),
    },
    {
        "suffix": "_radiant",
        "label": "闪耀",
        "subtitle": "终端能量阵列",
        "base": (88, 91, 101),
        "black": (27, 24, 35),
        "metal": (183, 186, 196),
        "accent": (218, 94, 234),
        "glass": (130, 86, 157),
        "brass": (235, 190, 74),
        "hot": (255, 143, 48),
    },
)


def clamp(value):
    return max(0, min(255, int(round(value))))


def shift(color, amount):
    return tuple(clamp(channel + amount) for channel in color)


def mix(first, second, ratio):
    return tuple(clamp(a * (1.0 - ratio) + b * ratio) for a, b in zip(first, second))


def rgba(color, alpha=255):
    return tuple(color) + (alpha,)


def box(start, end, texture, **overrides):
    unknown = set(overrides) - set(FACE_NAMES)
    if unknown:
        raise ValueError(f"Unknown face overrides: {sorted(unknown)}")
    return {
        "from": tuple(float(value) for value in start),
        "to": tuple(float(value) for value in end),
        "faces": {face: overrides.get(face, texture) for face in FACE_NAMES},
    }


STATIC_BOXES = [
    # Long split plinth: both blocks read as one production cell.
    box((0.5, 0, 0.75), (5.5, 0.75, 31.25), "black", up="metal"),
    box((10.5, 0, 0.75), (15.5, 0.75, 31.25), "black", up="metal"),
    box((1, 0.75, 1), (15, 2.1, 30.75), "base", north="metal", up="metal"),
    box((2, 1.75, 0.25), (14, 3.1, 3.25), "black", north="base", up="metal"),
    box((3, 2.15, 0.05), (13, 2.75, 0.35), "panel", north="panel", up="panel"),

    # Front operator cabinet and recessed production controls.
    box((1.25, 2.1, 3), (14.75, 7.15, 8.0), "base", north="metal", up="metal"),
    box((2.1, 6.45, 0.75), (13.9, 10.0, 5.2), "black", north="base", up="metal"),
    box((3.3, 7.25, 0.25), (12.7, 9.25, 0.85), "black", north="panel", up="metal"),
    box((2.2, 3.1, 2.55), (6.7, 5.6, 3.15), "black", north="metal", up="base"),
    box((7.2, 3.1, 2.55), (13.8, 5.6, 3.15), "black", north="metal", up="base"),
    box((3.5, 4.75, 2.25), (5.4, 5.15, 2.65), "brass", north="brass"),
    box((9.5, 4.75, 2.25), (11.5, 5.15, 2.65), "brass", north="brass"),

    # Continuous indexed conveyor and its guide rails.
    box((4.0, 6.9, 4.5), (12.0, 7.85, 26.0), "black", north="metal", up="metal"),
    box((3.45, 7.3, 4.1), (4.4, 8.45, 26.35), "base", north="metal", up="brass"),
    box((11.6, 7.3, 4.1), (12.55, 8.45, 26.35), "base", north="metal", up="brass"),
    box((4.35, 7.75, 4.55), (11.65, 8.15, 5.0), "vent", up="metal"),
    box((4.35, 7.75, 7.5), (11.65, 8.15, 8.0), "vent", up="metal"),
    box((4.35, 7.75, 15.25), (11.65, 8.15, 15.75), "vent", up="metal"),
    box((4.35, 7.75, 18.25), (11.65, 8.15, 18.75), "vent", up="metal"),
    box((4.35, 7.75, 21.25), (11.65, 8.15, 21.75), "vent", up="metal"),
    box((4.35, 7.75, 24.25), (11.65, 8.15, 24.75), "vent", up="metal"),

    # Enclosed press/primer station spanning the seam between the two blocks.
    box((1.55, 6.4, 7.1), (3.55, 13.3, 16.8), "base", north="metal", east="black", up="metal"),
    box((12.45, 6.4, 7.1), (14.45, 13.3, 16.8), "base", north="metal", west="black", up="metal"),
    box((1.55, 13.3, 6.8), (14.45, 15.65, 16.8), "base", north="metal", up="metal"),
    box((3.55, 12.4, 8.0), (12.45, 13.7, 16.2), "black", north="metal", up="metal"),
    box((2.1, 9.0, 6.65), (3.05, 13.0, 7.3), "brass", north="brass", up="metal"),
    box((12.95, 9.0, 6.65), (13.9, 13.0, 7.3), "brass", north="brass", up="metal"),
    box((4.2, 14.0, 6.25), (11.8, 15.2, 7.05), "black", north="panel", up="metal"),
    box((2.7, 7.95, 15.75), (13.3, 10.1, 17.4), "black", north="metal", south="metal", up="metal"),

    # Rear feed hopper: stepped armor gives a hopper silhouette without rotations.
    box((1.3, 2.1, 19.2), (7.2, 8.9, 29.0), "base", north="metal", east="black", up="metal"),
    box((1.75, 8.9, 20.1), (6.75, 11.7, 28.45), "base", north="metal", east="black", up="metal"),
    box((2.25, 11.7, 21.0), (6.25, 13.8, 27.75), "metal", north="base", east="black", up="metal"),
    box((1.75, 13.45, 20.7), (6.75, 14.35, 28.1), "black", north="metal", up="brass"),
    box((2.25, 6.0, 18.65), (6.25, 8.1, 19.35), "black", north="glass", up="metal"),
    box((5.85, 7.1, 18.2), (8.0, 8.0, 20.1), "brass", north="brass", up="metal"),

    # Propellant mixer and sealed metering tower.
    box((8.55, 2.1, 18.8), (14.75, 6.2, 29.4), "base", north="metal", west="black", up="metal"),
    box((9.1, 6.2, 19.45), (14.2, 13.25, 28.75), "metal", north="base", west="black", up="metal"),
    box((10.1, 13.25, 21.0), (13.2, 14.55, 27.2), "black", north="metal", up="brass"),
    box((7.25, 5.4, 24.5), (9.2, 7.15, 27.0), "brass", north="metal", up="brass"),

    # Rear service radiator and pressure manifold.
    box((1.1, 2.0, 29.2), (14.9, 13.6, 31.75), "base", north="vent", south="metal", up="metal"),
    box((2.0, 3.2, 31.35), (14.0, 4.25, 31.95), "black", south="vent", up="metal"),
    box((2.0, 5.0, 31.35), (14.0, 6.05, 31.95), "black", south="vent", up="metal"),
    box((2.0, 6.8, 31.35), (14.0, 7.85, 31.95), "black", south="vent", up="metal"),
    box((2.0, 8.6, 31.35), (14.0, 9.65, 31.95), "black", south="vent", up="metal"),
    box((2.0, 10.4, 31.35), (14.0, 11.45, 31.95), "black", south="vent", up="metal"),
    box((2.0, 12.2, 31.35), (14.0, 13.25, 31.95), "black", south="vent", up="metal"),
    box((6.6, 12.7, 28.5), (9.4, 14.1, 30.2), "brass", north="metal", up="brass"),
]


IDLE_BOXES = [
    box((4.55, 8.15, 9.0), (11.45, 8.55, 15.6), "black", north="brass", up="metal"),
    box((5.5, 8.55, 10.0), (10.5, 9.05, 14.8), "brass", north="brass", up="metal"),
    box((4.85, 9.25, 8.8), (11.15, 10.15, 15.8), "black", north="metal", up="metal", down="brass"),
    box((5.4, 10.15, 9.3), (10.6, 13.25, 15.35), "metal", north="metal", up="black", down="brass"),
    box((3.8, 7.65, 0.05), (9.1, 8.85, 0.35), "panel", north="panel", up="panel"),
    box((9.8, 7.55, 0.05), (10.8, 8.55, 0.35), "brass", north="brass", up="metal"),
    box((11.25, 7.55, 0.05), (12.25, 8.55, 0.35), "black", north="black", up="metal"),
    box((5.1, 8.15, 5.4), (6.3, 8.65, 6.9), "brass", up="brass"),
    box((7.4, 8.15, 5.4), (8.6, 8.65, 6.9), "brass", up="brass"),
    box((9.7, 8.15, 5.4), (10.9, 8.65, 6.9), "brass", up="brass"),
    box((9.5, 7.2, 18.95), (13.8, 11.8, 19.55), "black", north="glass", up="metal"),
]


ACTIVE_BOXES = [
    box((4.55, 8.15, 9.0), (11.45, 8.4, 15.6), "black", north="hot", up="hot"),
    box((5.65, 8.4, 10.0), (10.35, 8.75, 14.9), "hot", north="hot", up="hot", down="black"),
    box((4.85, 8.75, 8.8), (11.15, 9.5, 15.8), "black", north="hot", up="metal", down="hot"),
    box((5.4, 9.5, 9.3), (10.6, 12.7, 15.35), "metal", north="metal", up="black", down="hot"),
    box((3.8, 7.65, 0.05), (9.1, 8.85, 0.35), "panel", north="panel", up="panel"),
    box((9.8, 7.55, 0.05), (10.8, 8.55, 0.35), "hot", north="hot", up="hot"),
    box((11.25, 7.55, 0.05), (12.25, 8.55, 0.35), "panel", north="panel", up="panel"),
    box((5.1, 8.15, 17.5), (6.3, 8.65, 18.6), "brass", up="hot"),
    box((7.4, 8.15, 17.5), (8.6, 8.65, 18.6), "brass", up="hot"),
    box((9.7, 8.15, 17.5), (10.9, 8.65, 18.6), "brass", up="hot"),
    box((5.1, 14.1, 6.0), (10.9, 14.8, 6.25), "hot", north="hot", up="hot"),
    box((9.5, 7.2, 18.95), (13.8, 11.8, 19.55), "black", north="hot", up="panel"),
]


def tier_upgrade_boxes(level):
    result = []
    if level >= 1:
        result.extend([
            box((0.45, 5.0, 19.5), (1.45, 12.1, 28.6), "black", west="metal", up="brass"),
            box((14.55, 4.2, 18.3), (15.55, 13.2, 29.1), "brass", east="metal", up="metal"),
            box((0.15, 7.0, 21.0), (0.55, 10.8, 27.2), "panel", west="panel"),
        ])
    if level >= 2:
        result.extend([
            box((0.35, 9.8, 17.4), (1.55, 15.8, 29.5), "base", west="metal", up="metal"),
            box((14.45, 9.8, 17.4), (15.65, 15.8, 29.5), "base", east="metal", up="metal"),
            box((1.55, 14.8, 17.4), (14.45, 15.8, 29.5), "black", north="metal", up="brass"),
            box((6.0, 14.45, 16.8), (10.0, 15.45, 17.35), "black", north="panel", up="metal"),
        ])
    if level >= 3:
        result.extend([
            box((0.45, 3.0, 8.0), (1.75, 9.8, 15.4), "base", west="metal", up="metal"),
            box((14.25, 3.0, 8.0), (15.55, 9.8, 15.4), "base", east="metal", up="metal"),
            box((0.0, 6.0, 7.5), (0.45, 8.7, 14.5), "glass", west="glass"),
            box((15.55, 6.0, 7.5), (16.0, 8.7, 14.5), "glass", east="glass"),
            box((3.0, 15.15, 8.4), (13.0, 15.9, 16.6), "brass", north="metal", up="brass"),
        ])
    if level >= 4:
        result.extend([
            box((2.0, 15.8, 5.6), (3.0, 16.0, 29.8), "hot", north="panel", up="hot"),
            box((13.0, 15.8, 5.6), (14.0, 16.0, 29.8), "hot", north="panel", up="hot"),
            box((1.05, 10.2, 3.6), (2.0, 14.2, 6.8), "brass", north="metal", up="brass"),
            box((14.0, 10.2, 3.6), (14.95, 14.2, 6.8), "brass", north="metal", up="brass"),
        ])
    if level >= 5:
        result.extend([
            box((4.0, 15.8, 4.8), (12.0, 16.0, 30.5), "metal", north="brass", up="panel"),
            box((5.4, 14.95, 3.9), (10.6, 15.65, 5.4), "black", north="panel", up="brass"),
            box((0.15, 1.0, 14.0), (0.75, 14.8, 16.0), "brass", west="metal", up="panel"),
            box((15.25, 1.0, 14.0), (15.85, 14.8, 16.0), "brass", east="metal", up="panel"),
            box((6.7, 14.6, 30.5), (9.3, 15.8, 31.5), "black", south="panel", up="brass"),
        ])
    return result


def machine_boxes(level, active):
    return STATIC_BOXES + tier_upgrade_boxes(level) + (ACTIVE_BOXES if active else IDLE_BOXES)


def clean_number(value):
    rounded = round(float(value), 4)
    return int(rounded) if rounded.is_integer() else rounded


def element_from_box(source, start=None, end=None, omit_faces=()):
    start = start or source["from"]
    end = end or source["to"]
    return {
        "from": [clean_number(value) for value in start],
        "to": [clean_number(value) for value in end],
        "faces": {
            face: {"texture": f"#{texture}"}
            for face, texture in source["faces"].items()
            if face not in omit_faces
        },
    }


def texture_prefix(tier):
    return f"munitions_bench{tier['suffix']}"


def model_prefix(tier):
    return f"munitions_bench{tier['suffix']}"


def texture_map(tier):
    prefix = texture_prefix(tier)
    return {
        key: f"miningdim:block/{prefix}_{key}"
        for key in TEXTURE_KEYS
    } | {"particle": f"miningdim:block/{prefix}_base"}


def clip_boxes(boxes, part):
    min_z, max_z = PART_BOUNDS[part]
    elements = []
    for source in boxes:
        z0 = max(source["from"][2], min_z)
        z1 = min(source["to"][2], max_z)
        if z1 <= z0:
            continue
        start = (source["from"][0], source["from"][1], z0 - min_z)
        end = (source["to"][0], source["to"][1], z1 - min_z)
        omit_faces = set()
        if source["from"][2] < min_z < source["to"][2]:
            omit_faces.add("north")
        if source["from"][2] < max_z < source["to"][2]:
            omit_faces.add("south")
        elements.append(element_from_box(source, start, end, omit_faces))
    return elements


def block_model(tier, level, part, active):
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": texture_map(tier),
        "elements": clip_boxes(machine_boxes(level, active), part),
    }


def item_model(tier, level):
    scale = (0.72, 0.72, 0.36)
    offset = (2.24, 1.8, 2.24)
    elements = []
    for source in machine_boxes(level, False):
        start = tuple(offset[i] + source["from"][i] * scale[i] for i in range(3))
        end = tuple(offset[i] + source["to"][i] * scale[i] for i in range(3))
        elements.append(element_from_box(source, start, end))
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": texture_map(tier),
        "elements": elements,
        "display": {
            "thirdperson_righthand": {
                "rotation": [68, 45, 0],
                "translation": [0, 2, 0],
                "scale": [0.48, 0.48, 0.48],
            },
            "firstperson_righthand": {
                "rotation": [0, 45, 0],
                "translation": [0, 1, 0],
                "scale": [0.52, 0.52, 0.52],
            },
            "gui": {
                "rotation": [28, 225, 0],
                "translation": [0, 0, 0],
                "scale": [0.76, 0.76, 0.76],
            },
        },
    }


def blockstate(tier):
    rotations = {"north": None, "east": 90, "south": 180, "west": 270}
    prefix = model_prefix(tier)
    variants = {}
    for active in (False, True):
        for facing, rotation in rotations.items():
            for part in PART_BOUNDS:
                suffix = "_active" if active else ""
                value = {"model": f"miningdim:block/{prefix}_{part}{suffix}"}
                if rotation is not None:
                    value["y"] = rotation
                variants[
                    f"active={str(active).lower()},facing={facing},part={part}"
                ] = value
    return {"variants": variants}


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def framed_texture(fill, border, inner=None):
    image = Image.new("RGBA", (16, 16), rgba(fill))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=rgba(border))
    if inner is not None:
        draw.rectangle((2, 2, 13, 13), outline=rgba(inner))
    return image, draw


def make_texture(tier, key):
    if key == "base":
        image, draw = framed_texture(tier["base"], shift(tier["base"], -26), shift(tier["base"], 18))
        draw.line((1, 5, 14, 5), fill=rgba(shift(tier["base"], 12)))
        draw.line((1, 11, 14, 11), fill=rgba(shift(tier["base"], -15)))
        draw.line((7, 1, 7, 14), fill=rgba(shift(tier["base"], -9)))
        for point in ((2, 2), (13, 2), (2, 13), (13, 13)):
            draw.point(point, fill=rgba(shift(tier["metal"], 38)))
        return image
    if key == "black":
        image, draw = framed_texture(tier["black"], shift(tier["black"], -10), shift(tier["black"], 20))
        draw.line((3, 5, 12, 5), fill=rgba(shift(tier["black"], 12)))
        draw.line((3, 10, 12, 10), fill=rgba(shift(tier["black"], -6)))
        return image
    if key == "metal":
        image, draw = framed_texture(tier["metal"], shift(tier["metal"], -32))
        for y, amount in ((2, 35), (4, -18), (7, 20), (10, -25), (13, 28)):
            draw.line((1, y, 14, y), fill=rgba(shift(tier["metal"], amount)))
        draw.point((3, 3), fill=rgba((225, 230, 234)))
        draw.point((12, 12), fill=rgba(shift(tier["metal"], -45)))
        return image
    if key == "panel":
        background = mix(tier["black"], tier["accent"], 0.18)
        image, draw = framed_texture(background, shift(tier["accent"], -45), tier["accent"])
        draw.line((3, 4, 12, 4), fill=rgba(shift(tier["accent"], 35)))
        draw.line((3, 7, 9, 7), fill=rgba(tier["accent"]))
        draw.line((3, 10, 12, 10), fill=rgba(shift(tier["accent"], -25)))
        draw.rectangle((10, 6, 12, 8), fill=rgba(shift(tier["accent"], 50)))
        return image
    if key == "glass":
        image, draw = framed_texture(tier["glass"], shift(tier["glass"], -30), shift(tier["glass"], 35))
        draw.line((3, 12, 12, 3), fill=rgba(shift(tier["glass"], 55)))
        draw.line((3, 13, 13, 3), fill=rgba(shift(tier["glass"], 18)))
        return image
    if key == "brass":
        image, draw = framed_texture(tier["brass"], shift(tier["brass"], -45))
        draw.line((1, 3, 14, 3), fill=rgba(shift(tier["brass"], 45)))
        draw.line((1, 8, 14, 8), fill=rgba(shift(tier["brass"], -16)))
        draw.line((1, 12, 14, 12), fill=rgba(shift(tier["brass"], 25)))
        return image
    if key == "hot":
        image, draw = framed_texture(tier["hot"], shift(tier["hot"], -60))
        draw.rectangle((2, 2, 13, 13), fill=rgba(shift(tier["hot"], -16)))
        draw.rectangle((4, 4, 11, 11), fill=rgba(shift(tier["hot"], 30)))
        draw.rectangle((6, 6, 9, 9), fill=rgba((255, 225, 104)))
        return image
    if key == "vent":
        image, draw = framed_texture(mix(tier["black"], tier["metal"], 0.32), shift(tier["black"], -8))
        for y in range(2, 15, 3):
            draw.line((2, y, 13, y), fill=rgba(shift(tier["metal"], 18)))
            draw.line((2, y + 1, 13, y + 1), fill=rgba(shift(tier["black"], -4)))
        return image
    raise ValueError(f"Unknown texture key: {key}")


def write_textures():
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for tier in TIERS:
        prefix = texture_prefix(tier)
        for key in TEXTURE_KEYS:
            make_texture(tier, key).save(TEXTURE_ROOT / f"{prefix}_{key}.png")


def shade(color, factor):
    return rgba(tuple(clamp(channel * factor) for channel in color))


def project(point, viewport):
    x, y, z = point
    x -= 8.0
    z -= 16.0
    yaw = math.radians(143.0)
    rotated_x = math.cos(yaw) * x - math.sin(yaw) * z
    depth = math.sin(yaw) * x + math.cos(yaw) * z
    left, top, right, bottom = viewport
    scale = min((right - left) / 27.0, (bottom - top) / 35.0)
    screen_x = (left + right) / 2.0 + rotated_x * scale
    screen_y = (top + bottom) / 2.0 - (y - 7.1) * scale * 0.78 + depth * scale * 0.29
    return screen_x, screen_y, depth


def face_polygons(source, viewport):
    x0, y0, z0 = source["from"]
    x1, y1, z1 = source["to"]
    corners = {
        "000": (x0, y0, z0), "001": (x0, y0, z1),
        "010": (x0, y1, z0), "011": (x0, y1, z1),
        "100": (x1, y0, z0), "101": (x1, y0, z1),
        "110": (x1, y1, z0), "111": (x1, y1, z1),
    }
    definitions = {
        "up": (("010", "110", "111", "011"), 1.08),
        "north": (("000", "100", "110", "010"), 1.00),
        "south": (("101", "001", "011", "111"), 0.67),
        "east": (("100", "101", "111", "110"), 0.82),
        "west": (("001", "000", "010", "011"), 0.74),
    }
    result = []
    for face, (keys, light) in definitions.items():
        points = [project(corners[key], viewport) for key in keys]
        result.append((sum(point[2] for point in points) / 4.0, points, source["faces"][face], light))
    return result


def draw_polygon(image, points, texture, light, tier):
    palette = {
        "base": tier["base"],
        "black": tier["black"],
        "metal": tier["metal"],
        "panel": tier["accent"],
        "glass": tier["glass"],
        "brass": tier["brass"],
        "hot": tier["hot"],
        "vent": mix(tier["black"], tier["metal"], 0.38),
    }
    xy = [(round(point[0]), round(point[1])) for point in points]
    draw = ImageDraw.Draw(image)
    draw.polygon(xy, fill=shade(palette[texture], light))
    draw.line(xy + [xy[0]], fill=(6, 9, 12, 230), width=1, joint="curve")


def draw_machine(image, viewport, tier, level, active):
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = viewport
    draw.ellipse((left + 24, bottom - 40, right - 24, bottom - 4), fill=(2, 4, 7, 165))
    polygons = []
    for source in machine_boxes(level, active):
        polygons.extend(face_polygons(source, viewport))
    for _, points, texture, light in sorted(polygons, key=lambda value: value[0]):
        draw_polygon(image, points, texture, light, tier)


def load_font(size):
    candidates = (
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
        Path("C:/Windows/Fonts/arial.ttf"),
    )
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def render_preview():
    width, height = 1800, 1080
    image = Image.new("RGBA", (width, height), (14, 18, 23, 255))
    draw = ImageDraw.Draw(image)
    for y in range(0, height, 40):
        for x in range(0, width, 40):
            color = (19, 24, 30, 255) if (x // 40 + y // 40) % 2 == 0 else (22, 28, 35, 255)
            draw.rectangle((x, y, x + 40, y + 40), fill=color)
    title_font = load_font(34)
    label_font = load_font(25)
    small_font = load_font(17)
    draw.rectangle((24, 20, width - 24, 86), fill=(23, 30, 37, 245), outline=(83, 99, 112, 255), width=2)
    draw.text((52, 35), "弹药工厂 · 六档设备重制预览", font=title_font, fill=(229, 235, 239, 255))
    draw.text((1245, 45), "左：待机   /   右：生产中", font=small_font, fill=(151, 169, 181, 255))

    margin_x, gap_x = 28, 18
    card_w = (width - margin_x * 2 - gap_x * 2) // 3
    card_h = 474
    top_y = 102
    for level, tier in enumerate(TIERS):
        column = level % 3
        row = level // 3
        left = margin_x + column * (card_w + gap_x)
        top = top_y + row * (card_h + 18)
        right = left + card_w
        bottom = top + card_h
        border = tier["accent"]
        draw.rounded_rectangle((left, top, right, bottom), radius=12,
                               fill=(20, 26, 32, 245), outline=rgba(shift(border, -25)), width=2)
        draw.rectangle((left + 2, top + 2, right - 2, top + 56), fill=rgba(mix(tier["base"], (24, 30, 36), 0.55)))
        draw.rectangle((left + 2, top + 55, right - 2, top + 59), fill=rgba(border))
        draw.text((left + 20, top + 13), tier["label"], font=label_font, fill=(240, 243, 245, 255))
        draw.text((left + 112, top + 20), tier["subtitle"], font=small_font, fill=rgba(shift(border, 25)))
        split = (left + right) // 2
        draw.line((split, top + 74, split, bottom - 18), fill=(58, 70, 80, 255), width=1)
        draw.text((left + 22, top + 72), "待机", font=small_font, fill=(142, 159, 171, 255))
        draw.text((split + 18, top + 72), "生产中", font=small_font, fill=rgba(shift(tier["hot"], 15)))
        draw_machine(image, (left + 8, top + 94, split - 2, bottom - 12), tier, level, False)
        draw_machine(image, (split + 2, top + 94, right - 8, bottom - 12), tier, level, True)
    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(PREVIEW_PATH)


FACE_PLANES = {
    "west": (0, False, 2, 1),
    "east": (0, True, 2, 1),
    "down": (1, False, 0, 2),
    "up": (1, True, 0, 2),
    "north": (2, False, 0, 1),
    "south": (2, True, 0, 1),
}


def find_coplanar_face_overlaps(boxes):
    overlaps = []
    for first_index in range(len(boxes)):
        first = boxes[first_index]
        for second_index in range(first_index + 1, len(boxes)):
            second = boxes[second_index]
            for face, (axis, use_end, u_axis, v_axis) in FACE_PLANES.items():
                if face not in first.get("faces", {}) or face not in second.get("faces", {}):
                    continue
                first_plane = first["to"][axis] if use_end else first["from"][axis]
                second_plane = second["to"][axis] if use_end else second["from"][axis]
                if first_plane != second_plane:
                    continue
                u_overlap = max(
                    0.0,
                    min(first["to"][u_axis], second["to"][u_axis])
                    - max(first["from"][u_axis], second["from"][u_axis]),
                )
                v_overlap = max(
                    0.0,
                    min(first["to"][v_axis], second["to"][v_axis])
                    - max(first["from"][v_axis], second["from"][v_axis]),
                )
                area = u_overlap * v_overlap
                if area > 0.000001:
                    overlaps.append(
                        (first_index, second_index, face, clean_number(first_plane), round(area, 6))
                    )
    return overlaps


def validate_source_boxes():
    for level in range(len(TIERS)):
        for active in (False, True):
            boxes = machine_boxes(level, active)
            for index, source in enumerate(boxes):
                for axis, (start, end) in enumerate(zip(source["from"], source["to"])):
                    limit = 32 if axis == 2 else 16
                    if not 0 <= start < end <= limit:
                        raise ValueError(
                            f"tier {level} active={active} box {index} axis {axis} invalid: {start}..{end}"
                        )
                for texture in source["faces"].values():
                    if texture not in TEXTURE_KEYS:
                        raise ValueError(f"Unknown source texture key: {texture}")
            overlaps = find_coplanar_face_overlaps(boxes)
            if overlaps:
                raise ValueError(
                    f"tier {level} active={active} has coplanar duplicate faces: {overlaps[:8]}"
                )


def validate_model(path):
    model = json.loads(path.read_text(encoding="utf-8"))
    texture_keys = set(model["textures"])
    if not model.get("elements"):
        raise ValueError(f"{path.name} has no elements")
    for element_index, element in enumerate(model["elements"]):
        for axis, (start, end) in enumerate(zip(element["from"], element["to"])):
            if not 0 <= start < end <= 16:
                raise ValueError(f"{path.name} element {element_index} axis {axis} invalid: {start}..{end}")
        for face in element["faces"].values():
            reference = face["texture"]
            if not reference.startswith("#") or reference[1:] not in texture_keys:
                raise ValueError(f"{path.name} has unknown texture reference {reference}")
    overlaps = find_coplanar_face_overlaps(model["elements"])
    if overlaps:
        raise ValueError(f"{path.name} has coplanar duplicate faces: {overlaps[:8]}")
    return model


def output_hashes(paths):
    return {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in paths
    }


def validate_outputs():
    generated = []
    element_counts = []
    for level, tier in enumerate(TIERS):
        prefix = model_prefix(tier)
        state_path = BLOCKSTATE_ROOT / f"{prefix}.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if len(state.get("variants", {})) != 16:
            raise ValueError(f"{state_path.name} must contain 16 variants")
        generated.append(state_path)
        for active in (False, True):
            for part in PART_BOUNDS:
                suffix = "_active" if active else ""
                path = BLOCK_MODEL_ROOT / f"{prefix}_{part}{suffix}.json"
                model = validate_model(path)
                element_counts.append(len(model["elements"]))
                generated.append(path)
        item_path = ITEM_MODEL_ROOT / f"{prefix}.json"
        validate_model(item_path)
        generated.append(item_path)
        for key in TEXTURE_KEYS:
            texture_path = TEXTURE_ROOT / f"{texture_prefix(tier)}_{key}.png"
            with Image.open(texture_path) as texture:
                texture.load()
                if texture.size != (16, 16):
                    raise ValueError(f"{texture_path.name} must be 16x16")
                if texture.convert("RGBA").getchannel("A").getextrema() != (255, 255):
                    raise ValueError(f"{texture_path.name} must be fully opaque")
            generated.append(texture_path)
        for active in (False, True):
            for facing, rotation in {"north": None, "east": 90, "south": 180, "west": 270}.items():
                for part in PART_BOUNDS:
                    suffix = "_active" if active else ""
                    key = f"active={str(active).lower()},facing={facing},part={part}"
                    expected = f"miningdim:block/{prefix}_{part}{suffix}"
                    value = state["variants"].get(key)
                    if value is None or value.get("model") != expected or value.get("y") != rotation:
                        raise ValueError(f"Unexpected mapping in {state_path.name}: {key}")
    with Image.open(PREVIEW_PATH) as preview:
        preview.load()
        if preview.size != (1800, 1080):
            raise ValueError(f"Preview must be 1800x1080, got {preview.size}")
    generated.append(PREVIEW_PATH)
    if len(generated) != 85:
        raise ValueError(f"Expected 85 generated files, got {len(generated)}")
    if IDLE_BOXES[1]["from"][1] <= ACTIVE_BOXES[1]["from"][1]:
        raise ValueError("Active press head must be lower than idle press head")
    print(
        "PASS: 6 tiers, 24 block models, 6 item models, 96 blockstate variants, "
        f"48 opaque textures, model elements {min(element_counts)}..{max(element_counts)}, "
        "active ram lowered, 1800x1080 preview"
    )
    return output_hashes(generated)


def generate():
    validate_source_boxes()
    for level, tier in enumerate(TIERS):
        prefix = model_prefix(tier)
        write_json(BLOCKSTATE_ROOT / f"{prefix}.json", blockstate(tier))
        for active in (False, True):
            for part in PART_BOUNDS:
                suffix = "_active" if active else ""
                write_json(
                    BLOCK_MODEL_ROOT / f"{prefix}_{part}{suffix}.json",
                    block_model(tier, level, part, active),
                )
        write_json(ITEM_MODEL_ROOT / f"{prefix}.json", item_model(tier, level))
    write_textures()
    render_preview()
    return validate_outputs()


def main():
    first = generate()
    second = generate()
    if first != second:
        changed = sorted(path for path in first if first[path] != second.get(path))
        raise ValueError(f"Generator is not deterministic: {changed}")
    print(f"PASS: deterministic regeneration for {len(first)} outputs")


if __name__ == "__main__":
    main()
