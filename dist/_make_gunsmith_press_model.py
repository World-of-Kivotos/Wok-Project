import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "src/main/resources/assets/miningdim"
BLOCKSTATE_PATH = ASSET_ROOT / "blockstates/gunsmith_press.json"
MODEL_ROOT = ASSET_ROOT / "models/block"
ITEM_MODEL_PATH = ASSET_ROOT / "models/item/gunsmith_press.json"
TEXTURE_ROOT = ASSET_ROOT / "textures/block"
PREVIEW_PATH = ROOT / "dist/gunsmith-press-redesign-preview.png"

TEXTURES = {
    "base": "miningdim:block/gunsmith_press_base",
    "metal": "miningdim:block/gunsmith_press_metal",
    "dark": "miningdim:block/gunsmith_press_dark",
    "brass": "miningdim:block/gunsmith_press_brass",
    "panel": "miningdim:block/gunsmith_press_panel",
    "warning": "miningdim:block/gunsmith_press_warning",
    "hot": "miningdim:block/gunsmith_press_hot",
    "particle": "miningdim:block/gunsmith_press_base",
}

PREVIEW_COLORS = {
    "base": (45, 52, 60, 255),
    "metal": (106, 123, 136, 255),
    "dark": (20, 25, 30, 255),
    "brass": (190, 132, 38, 255),
    "panel": (33, 220, 207, 255),
    "warning": (238, 174, 43, 255),
    "hot": (255, 91, 24, 255),
}

FACE_NAMES = ("north", "south", "east", "west", "up", "down")


def make_box(start, end, texture, **faces):
    if any(name not in FACE_NAMES for name in faces):
        raise ValueError(f"Unknown face override: {faces}")
    return {
        "from": tuple(float(value) for value in start),
        "to": tuple(float(value) for value in end),
        "faces": {name: faces.get(name, texture) for name in FACE_NAMES},
    }


COMMON_BOXES = [
    # Split feet and a deep foundation keep the machine heavy without filling the block.
    make_box((0.5, 0, 2.5), (6.5, 1, 15.5), "dark", up="metal"),
    make_box((9.5, 0, 2.5), (15.5, 1, 15.5), "dark", up="metal"),
    make_box((1, 1, 3), (15, 2.5, 15.5), "base", north="base", up="metal"),
    make_box((2, 2.5, 5), (14, 4.25, 15), "base", north="dark", up="metal"),
    make_box((2.5, 2.25, 2), (13.5, 3.25, 5.25), "warning", east="dark", west="dark", up="metal"),
    make_box((3.25, 3.5, 4.25), (12.75, 5, 12.5), "base", north="metal", up="metal"),
    make_box((5, 4.75, 5.5), (11, 5.65, 11.5), "brass", up="metal", down="dark"),
    # The rear spine, forward crown and open throat form the C-frame silhouette.
    make_box((1.5, 3.5, 11.5), (14.5, 14.75, 15.5), "base", north="dark", east="metal", west="metal"),
    make_box((1, 3.25, 10.75), (3.25, 15, 15.75), "base", north="metal", west="dark", up="metal"),
    make_box((12.75, 3.25, 10.75), (15, 15, 15.75), "base", north="metal", east="dark", up="metal"),
    make_box((3.25, 5, 10.9), (12.75, 12.5, 12.2), "dark", north="dark"),
    make_box((1.5, 12, 5), (14.5, 15.75, 15.5), "base", north="metal", east="dark", west="dark", up="metal"),
    make_box((3.25, 11.25, 3.25), (12.75, 14.25, 9.5), "dark", north="metal", east="base", west="base", up="metal"),
    make_box((1, 15, 6), (15, 16, 15.5), "dark", north="base", up="metal"),
    # A bright front status bar anchors the crown and reads clearly at inventory scale.
    make_box((3.5, 13.55, 2.75), (12.5, 14.8, 4.4), "dark", north="dark", up="metal"),
    make_box((4.1, 13.9, 2.35), (11.9, 14.45, 2.9), "panel", up="panel", down="dark"),
    make_box((2.15, 13.1, 4.2), (3.15, 14.1, 5.2), "brass", north="brass", up="metal"),
    make_box((12.85, 13.1, 4.2), (13.85, 14.1, 5.2), "brass", north="brass", up="metal"),
    # Hydraulic barrel and locking collars hang inside the open throat.
    make_box((5.25, 9.4, 5.25), (10.75, 12.75, 11.25), "dark", north="metal", east="metal", west="metal", up="dark", down="brass"),
    make_box((4.75, 11.55, 4.75), (11.25, 12.8, 11.75), "metal", north="metal", up="dark", down="brass"),
    make_box((5.15, 9.1, 5.15), (10.85, 10, 11.35), "brass", north="brass", up="metal", down="dark"),
    # Brass hydraulic lines trace the frame instead of floating as decoration.
    make_box((2.45, 5.6, 9.9), (3.55, 12, 11), "brass", north="brass", up="metal", down="dark"),
    make_box((2.45, 11.25, 7.5), (5.55, 12, 8.35), "brass", north="brass", up="metal"),
    make_box((2.15, 5.35, 9.6), (3.85, 6.45, 11.3), "brass", north="metal", up="brass"),
    make_box((10.45, 10.45, 9.45), (13.5, 11.2, 10.3), "brass", north="brass", up="metal"),
    # Side guards frame the die while leaving the pressing gap visible.
    make_box((3, 4.8, 4), (5, 6.15, 6.3), "base", north="metal", east="dark", up="metal"),
    make_box((11, 4.8, 4), (13, 6.15, 6.3), "warning", north="warning", west="dark", up="metal"),
    make_box((3.4, 5.9, 4.1), (4.6, 6.65, 5.3), "dark", north="metal", up="metal"),
    make_box((11.4, 5.9, 4.1), (12.6, 6.65, 5.3), "dark", north="warning", up="metal"),
    # The control pod projects toward the operator on the front-right corner.
    make_box((11.5, 7, 1.5), (15.5, 11.5, 5), "dark", north="base", east="metal", up="metal"),
    make_box((13, 9, 4.5), (14.25, 10.25, 11.8), "metal", north="dark", up="metal"),
    make_box((11.9, 8.65, 0.9), (15.1, 10.9, 1.8), "dark", north="dark", up="metal"),
    make_box((12.25, 9.05, 0.55), (14.75, 10.5, 1), "panel", north="panel", up="panel"),
]


IDLE_BOXES = [
    make_box((6, 5.65, 6.4), (10, 6.2, 10.6), "brass", up="hot", down="dark"),
    make_box((6.75, 8.9, 6.5), (9.25, 9.55, 10.1), "metal", north="metal", down="brass"),
    make_box((4.75, 7.95, 4.75), (11.25, 9.1, 11.75), "dark", north="warning", east="metal", west="metal", up="metal", down="brass"),
    make_box((6.25, 7.35, 6.25), (9.75, 7.95, 10.75), "brass", north="brass", up="metal", down="hot"),
    make_box((12.15, 7.55, 0.55), (13.25, 8.25, 1.05), "brass", north="brass", up="metal"),
    make_box((13.75, 7.55, 0.55), (14.75, 8.25, 1.05), "dark", north="dark", up="metal"),
]


ACTIVE_BOXES = [
    make_box((6, 5.65, 6.4), (10, 6.25, 10.6), "hot", north="hot", up="hot", down="dark"),
    make_box((6.75, 7.7, 6.5), (9.25, 9.55, 10.1), "metal", north="metal", east="brass", west="brass", down="hot"),
    make_box((4.75, 6.45, 4.75), (11.25, 7.8, 11.75), "dark", north="warning", east="metal", west="metal", up="metal", down="hot"),
    make_box((6.25, 5.85, 6.25), (9.75, 6.45, 10.75), "hot", north="hot", up="brass", down="hot"),
    make_box((5.35, 5.3, 5.8), (10.65, 5.65, 11.1), "hot", north="hot", up="hot", down="dark"),
    make_box((12.15, 7.55, 0.55), (13.25, 8.25, 1.05), "hot", north="hot", up="hot"),
    make_box((13.75, 7.55, 0.55), (14.75, 8.25, 1.05), "panel", north="panel", up="panel"),
    make_box((6.6, 13.05, 2.15), (9.4, 13.55, 2.8), "hot", north="hot", up="hot"),
]


def element_from_box(box):
    return {
        "from": [clean_coordinate(value) for value in box["from"]],
        "to": [clean_coordinate(value) for value in box["to"]],
        "faces": {
            face: {"texture": f"#{texture}"}
            for face, texture in box["faces"].items()
        },
    }


def clean_coordinate(value):
    return int(value) if value.is_integer() else round(value, 4)


def block_model(active):
    boxes = COMMON_BOXES + (ACTIVE_BOXES if active else IDLE_BOXES)
    payload = {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": TEXTURES,
        "elements": [element_from_box(box) for box in boxes],
    }
    if not active:
        payload["display"] = {
            "thirdperson_righthand": {
                "rotation": [72, 45, 0],
                "translation": [0, 2.5, 0],
                "scale": [0.36, 0.36, 0.36],
            },
            "firstperson_righthand": {
                "rotation": [0, 45, 0],
                "translation": [0, 0, 0],
                "scale": [0.42, 0.42, 0.42],
            },
            "gui": {
                "rotation": [30, 225, 0],
                "translation": [0, -0.5, 0],
                "scale": [0.64, 0.64, 0.64],
            },
        }
    return payload


def blockstate():
    rotations = {"north": None, "east": 90, "south": 180, "west": 270}
    variants = {}
    for active in (False, True):
        model = "miningdim:block/gunsmith_press_active" if active else "miningdim:block/gunsmith_press"
        for facing, rotation in rotations.items():
            value = {"model": model}
            if rotation is not None:
                value["y"] = rotation
            variants[f"active={str(active).lower()},facing={facing}"] = value
    return {"variants": variants}


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def make_base_texture():
    image = Image.new("RGBA", (16, 16), (43, 50, 58, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(19, 24, 29, 255))
    draw.line((1, 4, 14, 4), fill=(55, 64, 73, 255))
    draw.line((1, 11, 14, 11), fill=(31, 37, 44, 255))
    draw.line((7, 1, 7, 14), fill=(35, 41, 48, 255))
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        draw.point((x, y), fill=(103, 117, 127, 255))
        draw.point((x + (1 if x < 8 else -1), y), fill=(24, 29, 35, 255))
    return image


def make_metal_texture():
    image = Image.new("RGBA", (16, 16), (94, 110, 123, 255))
    draw = ImageDraw.Draw(image)
    for y, color in ((1, (135, 151, 163, 255)), (4, (73, 88, 100, 255)),
                     (7, (118, 134, 146, 255)), (11, (67, 81, 93, 255)),
                     (14, (128, 142, 153, 255))):
        draw.line((1, y, 14, y), fill=color)
    draw.rectangle((0, 0, 15, 15), outline=(45, 55, 64, 255))
    draw.line((3, 2, 11, 2), fill=(161, 174, 184, 255))
    draw.point((12, 8), fill=(177, 187, 194, 255))
    draw.point((4, 12), fill=(49, 61, 70, 255))
    return image


def make_dark_texture():
    image = Image.new("RGBA", (16, 16), (18, 23, 28, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(6, 9, 12, 255))
    draw.rectangle((2, 2, 13, 13), outline=(39, 47, 55, 255))
    draw.line((3, 5, 12, 5), fill=(25, 32, 39, 255))
    draw.line((3, 10, 12, 10), fill=(11, 15, 19, 255))
    draw.point((3, 3), fill=(76, 86, 94, 255))
    draw.point((12, 12), fill=(76, 86, 94, 255))
    return image


def make_brass_texture():
    image = Image.new("RGBA", (16, 16), (151, 102, 29, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(77, 48, 14, 255))
    draw.line((1, 2, 14, 2), fill=(224, 177, 66, 255))
    draw.line((1, 5, 14, 5), fill=(119, 75, 20, 255))
    draw.line((1, 9, 14, 9), fill=(196, 139, 37, 255))
    draw.line((1, 13, 14, 13), fill=(101, 61, 17, 255))
    for x, y in ((3, 4), (11, 7), (5, 12), (13, 3)):
        draw.point((x, y), fill=(243, 196, 81, 255))
    return image


def make_panel_texture():
    image = Image.new("RGBA", (16, 16), (8, 25, 29, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(4, 10, 13, 255))
    draw.rectangle((2, 2, 13, 13), fill=(13, 75, 77, 255), outline=(47, 215, 204, 255))
    draw.line((3, 4, 12, 4), fill=(77, 255, 237, 255))
    draw.line((3, 7, 9, 7), fill=(29, 158, 153, 255))
    draw.line((3, 10, 11, 10), fill=(24, 124, 122, 255))
    draw.rectangle((10, 8, 12, 11), fill=(54, 236, 216, 255))
    return image


def make_warning_texture():
    image = Image.new("RGBA", (16, 16), (231, 169, 38, 255))
    pixels = image.load()
    for y in range(16):
        for x in range(16):
            if ((x + y) // 4) % 2:
                pixels[x, y] = (18, 21, 23, 255)
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(73, 51, 12, 255))
    return image


def make_hot_texture():
    image = Image.new("RGBA", (16, 16), (91, 25, 10, 255))
    pixels = image.load()
    for y in range(16):
        for x in range(16):
            distance = math.hypot(x - 7.5, y - 7.5) / 10.6
            heat = max(0.0, 1.0 - distance)
            pixels[x, y] = (
                int(132 + 123 * heat),
                int(31 + 126 * heat * heat),
                int(9 + 27 * heat * heat),
                255,
            )
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline=(58, 13, 7, 255))
    draw.line((2, 11, 5, 8, 8, 9, 12, 4), fill=(255, 200, 61, 255), width=1)
    draw.line((4, 4, 7, 6, 10, 5), fill=(255, 117, 24, 255), width=1)
    draw.point((8, 8), fill=(255, 239, 121, 255))
    return image


def write_textures():
    makers = {
        "base": make_base_texture,
        "metal": make_metal_texture,
        "dark": make_dark_texture,
        "brass": make_brass_texture,
        "panel": make_panel_texture,
        "warning": make_warning_texture,
        "hot": make_hot_texture,
    }
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for name, maker in makers.items():
        maker().save(TEXTURE_ROOT / f"gunsmith_press_{name}.png")


def shade(color, factor):
    return tuple(max(0, min(255, round(channel * factor))) for channel in color[:3]) + (color[3],)


def project(point, yaw, viewport):
    x, y, z = point
    x -= 8
    z -= 8
    radians = math.radians(yaw)
    rotated_x = math.cos(radians) * x - math.sin(radians) * z
    depth = math.sin(radians) * x + math.cos(radians) * z
    left, top, right, bottom = viewport
    scale = min((right - left) / 22, (bottom - top) / 22)
    screen_x = (left + right) / 2 + rotated_x * scale
    screen_y = bottom - 72 - y * scale * 0.86 + depth * scale * 0.38
    return screen_x, screen_y, depth


def face_polygons(box, yaw, viewport):
    x0, y0, z0 = box["from"]
    x1, y1, z1 = box["to"]
    corners = {
        "000": (x0, y0, z0), "001": (x0, y0, z1),
        "010": (x0, y1, z0), "011": (x0, y1, z1),
        "100": (x1, y0, z0), "101": (x1, y0, z1),
        "110": (x1, y1, z0), "111": (x1, y1, z1),
    }
    definitions = {
        "up": (("010", "110", "111", "011"), 1.08),
        "north": (("000", "100", "110", "010"), 0.98),
        "south": (("101", "001", "011", "111"), 0.69),
        "east": (("100", "101", "111", "110"), 0.82),
        "west": (("001", "000", "010", "011"), 0.75),
    }
    result = []
    for name, (keys, light) in definitions.items():
        points = [project(corners[key], yaw, viewport) for key in keys]
        result.append((sum(point[2] for point in points) / 4, points, box["faces"][name], light))
    return result


def draw_textured_polygon(image, points, texture, light):
    xy = [(round(point[0]), round(point[1])) for point in points]
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).polygon(xy, fill=255)
    layer = Image.new("RGBA", image.size, shade(PREVIEW_COLORS[texture], light))
    layer_draw = ImageDraw.Draw(layer)
    if texture == "warning":
        for offset in range(-image.height, image.width + image.height, 18):
            layer_draw.line((offset, image.height, offset + image.height, 0),
                            fill=shade((17, 20, 22, 255), light), width=7)
    elif texture == "panel":
        bounds = mask.getbbox()
        if bounds:
            for y in range(bounds[1] + 2, bounds[3], 5):
                layer_draw.line((bounds[0], y, bounds[2], y), fill=(17, 118, 119, 255), width=1)
    elif texture == "hot":
        bounds = mask.getbbox()
        if bounds:
            cx = (bounds[0] + bounds[2]) // 2
            cy = (bounds[1] + bounds[3]) // 2
            radius = max(2, min(bounds[2] - bounds[0], bounds[3] - bounds[1]) // 4)
            layer_draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius),
                               fill=(255, 182, 51, 255))
    image.alpha_composite(Image.composite(layer, Image.new("RGBA", image.size), mask))
    ImageDraw.Draw(image).line(xy + [xy[0]], fill=(7, 10, 13, 255), width=2, joint="curve")


def draw_machine(image, viewport, active):
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = viewport
    draw.ellipse((left + 35, bottom - 72, right - 35, bottom - 25), fill=(4, 7, 9, 180))
    boxes = COMMON_BOXES + (ACTIVE_BOXES if active else IDLE_BOXES)
    polygons = []
    for box in boxes:
        polygons.extend(face_polygons(box, 144, viewport))
    for _, points, texture, light in sorted(polygons, key=lambda value: value[0]):
        draw_textured_polygon(image, points, texture, light)


def render_preview():
    image = Image.new("RGBA", (1100, 700), (16, 20, 25, 255))
    draw = ImageDraw.Draw(image)
    tile = 44
    for y in range(0, image.height, tile):
        for x in range(0, image.width, tile):
            color = (21, 26, 32, 255) if (x // tile + y // tile) % 2 == 0 else (25, 31, 38, 255)
            draw.rectangle((x, y, x + tile, y + tile), fill=color)
    draw.rectangle((20, 20, 1080, 680), outline=(67, 82, 94, 255), width=2)
    draw.rectangle((42, 40, 518, 86), fill=(25, 31, 38, 255), outline=(76, 91, 103, 255), width=2)
    draw.rectangle((582, 40, 1058, 86), fill=(31, 29, 27, 255), outline=(159, 91, 38, 255), width=2)
    draw.text((62, 56), "IDLE / RAM RAISED", fill=(150, 171, 184, 255))
    draw.text((602, 56), "ACTIVE / PRESSING", fill=(255, 159, 68, 255))
    draw_machine(image, (45, 85, 520, 665), False)
    draw_machine(image, (580, 85, 1055, 665), True)
    draw.line((550, 100, 550, 650), fill=(62, 75, 86, 255), width=2)
    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    image.save(PREVIEW_PATH)


def validate_boxes():
    for state, boxes in (("idle", COMMON_BOXES + IDLE_BOXES), ("active", COMMON_BOXES + ACTIVE_BOXES)):
        for index, box in enumerate(boxes):
            for axis, (start, end) in enumerate(zip(box["from"], box["to"])):
                if not 0 <= start < end <= 16:
                    raise ValueError(
                        f"{state} box {index} axis {axis} is outside 0..16: {start}..{end}"
                    )


def validate_outputs():
    model_paths = {
        "idle": MODEL_ROOT / "gunsmith_press.json",
        "active": MODEL_ROOT / "gunsmith_press_active.json",
    }
    models = {
        name: json.loads(path.read_text(encoding="utf-8"))
        for name, path in model_paths.items()
    }
    state = json.loads(BLOCKSTATE_PATH.read_text(encoding="utf-8"))
    item = json.loads(ITEM_MODEL_PATH.read_text(encoding="utf-8"))
    if len(state.get("variants", {})) != 8:
        raise ValueError("Gunsmith press blockstate must contain eight facing/state variants")
    if item != {"parent": "miningdim:block/gunsmith_press"}:
        raise ValueError("Gunsmith press item model no longer points to the idle block model")

    for name, model in models.items():
        texture_keys = set(model["textures"])
        for element_index, element in enumerate(model["elements"]):
            for axis, (start, end) in enumerate(zip(element["from"], element["to"])):
                if not 0 <= start < end <= 16:
                    raise ValueError(
                        f"Generated {name} element {element_index} axis {axis} is invalid: {start}..{end}"
                    )
            for face in element["faces"].values():
                texture = face["texture"]
                if not texture.startswith("#") or texture[1:] not in texture_keys:
                    raise ValueError(f"Generated {name} model has an unknown texture reference: {texture}")

    rotations = {"north": None, "east": 90, "south": 180, "west": 270}
    for active in (False, True):
        target = "miningdim:block/gunsmith_press_active" if active else "miningdim:block/gunsmith_press"
        for facing, rotation in rotations.items():
            variant = state["variants"][f"active={str(active).lower()},facing={facing}"]
            if variant["model"] != target or variant.get("y") != rotation:
                raise ValueError(f"Unexpected blockstate mapping for active={active}, facing={facing}")

    for texture_name in ("base", "metal", "dark", "brass", "panel", "warning", "hot"):
        texture_path = TEXTURE_ROOT / f"gunsmith_press_{texture_name}.png"
        with Image.open(texture_path) as texture:
            texture.load()
            if texture.size != (16, 16):
                raise ValueError(f"{texture_path.name} must be 16x16, got {texture.size}")
            if texture.convert("RGBA").getchannel("A").getextrema() != (255, 255):
                raise ValueError(f"{texture_path.name} must be fully opaque")

    with Image.open(PREVIEW_PATH) as preview:
        preview.load()
        if preview.size != (1100, 700):
            raise ValueError(f"Preview must be 1100x700, got {preview.size}")

    idle_head_y = IDLE_BOXES[2]["from"][1]
    active_head_y = ACTIVE_BOXES[2]["from"][1]
    if active_head_y >= idle_head_y:
        raise ValueError("Active press head must sit lower than the idle press head")
    print(
        "PASS: 4 JSON files parsed, 8 blockstate variants, "
        f"{len(models['idle']['elements'])}/{len(models['active']['elements'])} model elements in range, "
        "7 opaque 16x16 textures, 1100x700 preview, active ram lowered"
    )


def main():
    validate_boxes()
    write_json(MODEL_ROOT / "gunsmith_press.json", block_model(False))
    write_json(MODEL_ROOT / "gunsmith_press_active.json", block_model(True))
    write_json(ITEM_MODEL_PATH, {"parent": "miningdim:block/gunsmith_press"})
    write_json(BLOCKSTATE_PATH, blockstate())
    write_textures()
    render_preview()
    validate_outputs()


if __name__ == "__main__":
    main()
