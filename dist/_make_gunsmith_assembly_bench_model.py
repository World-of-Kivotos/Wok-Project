import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "src/main/resources/assets/miningdim"
MODEL_ROOT = ASSET_ROOT / "models/block"
ITEM_MODEL_PATH = ASSET_ROOT / "models/item/gunsmith_assembly_bench.json"
BLOCKSTATE_PATH = ASSET_ROOT / "blockstates/gunsmith_assembly_bench.json"
ARM_TEXTURE_PATH = ASSET_ROOT / "textures/entity/gunsmith_assembly_arm.png"
PREVIEW_PATH = ROOT / "dist/gunsmith-assembly-bench-preview.png"
ANIMATION_PATH = ROOT / "dist/gunsmith-assembly-bench-animation-preview.gif"

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

COLORS = {
    "base": (45, 53, 62, 255),
    "metal": (91, 105, 116, 255),
    "dark": (25, 30, 36, 255),
    "brass": (205, 151, 54, 255),
    "panel": (32, 177, 163, 255),
    "warning": (246, 181, 55, 255),
    "hot": (239, 82, 37, 255),
}

PART_BOUNDS = {
    "main": (0.0, 16.0, 0.0, 16.0),
    "side": (16.0, 32.0, 0.0, 16.0),
    "back": (0.0, 16.0, 16.0, 32.0),
    "back_side": (16.0, 32.0, 16.0, 32.0),
}

STATIC_BOXES = []
ITEM_ARM_BOXES = []


def add_box(start, end, texture, *, top=None, front=None, down="dark", target=None):
    box = {
        "from": tuple(float(value) for value in start),
        "to": tuple(float(value) for value in end),
        "texture": texture,
        "top": top or texture,
        "front": front or texture,
        "down": down,
    }
    (STATIC_BOXES if target is None else target).append(box)


# Two-block work bed and recessed guide rails.
add_box((0, 0, 2), (32, 1.5, 30), "base", top="metal")
add_box((1, 1.5, 3), (31, 3, 29), "base", top="metal")
add_box((2, 2.75, 1), (30, 4, 3), "warning")
add_box((3, 3, 4), (29, 4, 27), "metal", top="panel")
add_box((4, 4, 5), (28, 4.75, 26), "dark", top="panel")
add_box((4, 4.75, 8), (28, 5.25, 9), "dark", top="metal")
add_box((4, 4.75, 22), (28, 5.25, 23), "dark", top="metal")
add_box((7, 5, 9), (8.5, 6, 22), "brass", top="metal")
add_box((23.5, 5, 9), (25, 6, 22), "brass", top="metal")

# Long rifle-shaped calibration workpiece.
add_box((4, 5.25, 14.5), (12.5, 5.85, 15.5), "dark", top="metal")
add_box((2.75, 5.1, 14.2), (4.5, 6, 15.8), "dark", top="metal")
add_box((12, 5, 13.5), (20.75, 6.1, 16.5), "dark", top="metal")
add_box((12.5, 6.1, 14), (20, 6.4, 16), "brass")
add_box((16, 4.25, 16), (18, 5.25, 18), "dark", top="metal")
add_box((20.5, 5, 12.5), (27.25, 6, 17.5), "dark", top="metal")
add_box((27, 4.9, 12.2), (28.5, 6.15, 17.8), "dark", top="metal")

# Open 3D-printer frame, kept sparse so the work bed remains visible.
for x0, z0 in ((1, 2), (29, 2), (1, 27), (29, 27)):
    add_box((x0, 3, z0), (x0 + 2, 15, z0 + 2), "metal", front="dark", top="metal")
add_box((1, 14, 2), (31, 16, 4), "metal", front="dark", top="metal")
add_box((1, 14, 27), (31, 16, 29), "metal", top="metal")
add_box((1, 14, 4), (3, 16, 27), "metal", top="metal")
add_box((29, 14, 4), (31, 16, 27), "metal", top="metal")
add_box((3, 11.5, 15), (29, 13, 17), "dark", top="metal")
add_box((9, 14.5, 0.75), (23, 15.35, 1.75), "panel", front="panel", top="metal")

# Front control pod.
add_box((1, 6, 0), (9, 11, 3), "dark", front="panel", top="metal")
add_box((2, 7.25, 0), (7.5, 9.5, 0.5), "panel", front="panel", top="panel")
add_box((2.25, 6.25, 0), (3.5, 7, 0.55), "hot", front="hot", top="hot")
add_box((4.25, 6.25, 0), (5.5, 7, 0.55), "brass", front="brass", top="brass")

# Rear mechanical-arm pedestal. Moving parts are rendered by the BER.
add_box((23, 4, 20), (31, 5.5, 29), "dark", top="metal")
add_box((24, 5.5, 21), (30, 6.5, 28), "brass", top="metal")
add_box((24, 6.5, 21), (29, 9, 27), "metal", top="dark")

# Parked arm silhouette used only by the inventory model.
add_box((23, 8, 22), (28, 11, 27), "dark", top="metal", target=ITEM_ARM_BOXES)
add_box((19, 10, 21), (24, 14, 26), "dark", top="metal", target=ITEM_ARM_BOXES)
add_box((20, 11, 20.5), (22.5, 13, 26.5), "brass", top="metal", target=ITEM_ARM_BOXES)
add_box((17, 5.5, 21), (21, 11.5, 25), "dark", top="metal", target=ITEM_ARM_BOXES)
add_box((17.8, 6, 20.5), (19.8, 11, 25.5), "brass", top="metal", target=ITEM_ARM_BOXES)
add_box((15.5, 4, 21.5), (19.5, 7, 25.5), "metal", top="dark", target=ITEM_ARM_BOXES)
add_box((16.7, 3.2, 22.2), (18.3, 5, 24.8), "hot", top="hot", target=ITEM_ARM_BOXES)
add_box((14.8, 3, 22), (16.2, 5, 23.5), "brass", top="hot", target=ITEM_ARM_BOXES)
add_box((18.8, 3, 22), (20.2, 5, 23.5), "brass", top="hot", target=ITEM_ARM_BOXES)


def element_from_box(box, start=None, end=None):
    start = start or box["from"]
    end = end or box["to"]
    faces = {
        "north": {"texture": f"#{box['front']}"},
        "south": {"texture": f"#{box['texture']}"},
        "east": {"texture": f"#{box['texture']}"},
        "west": {"texture": f"#{box['texture']}"},
        "up": {"texture": f"#{box['top']}"},
        "down": {"texture": f"#{box['down']}"},
    }
    return {
        "from": [round(value, 4) for value in start],
        "to": [round(value, 4) for value in end],
        "faces": faces,
    }


def clip_boxes_for_part(part):
    min_x, max_x, min_z, max_z = PART_BOUNDS[part]
    elements = []
    for box in STATIC_BOXES:
        x0 = max(box["from"][0], min_x)
        x1 = min(box["to"][0], max_x)
        z0 = max(box["from"][2], min_z)
        z1 = min(box["to"][2], max_z)
        if x1 <= x0 or z1 <= z0:
            continue
        start = (x0 - min_x, box["from"][1], z0 - min_z)
        end = (x1 - min_x, box["to"][1], z1 - min_z)
        elements.append(element_from_box(box, start, end))
    return elements


def block_model(part):
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": TEXTURES,
        "elements": clip_boxes_for_part(part),
    }


def item_model():
    scale = 0.4375
    offset = (1.0, 3.0, 1.0)
    elements = []
    for box in STATIC_BOXES + ITEM_ARM_BOXES:
        start = tuple(offset[i] + box["from"][i] * scale for i in range(3))
        end = tuple(offset[i] + box["to"][i] * scale for i in range(3))
        elements.append(element_from_box(box, start, end))
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": TEXTURES,
        "elements": elements,
        "display": {
            "thirdperson_righthand": {
                "rotation": [70, 45, 0], "translation": [0, 2, 0], "scale": [0.55, 0.55, 0.55]
            },
            "firstperson_righthand": {
                "rotation": [0, 45, 0], "translation": [0, 1, 0], "scale": [0.62, 0.62, 0.62]
            },
            "gui": {
                "rotation": [28, 225, 0], "translation": [0, 0, 0], "scale": [0.78, 0.78, 0.78]
            },
        },
    }


def blockstate():
    rotations = {"north": None, "east": 90, "south": 180, "west": 270}
    variants = {}
    for active in (False, True):
        for facing, rotation in rotations.items():
            for part in PART_BOUNDS:
                value = {"model": f"miningdim:block/gunsmith_assembly_bench_{part}"}
                if rotation is not None:
                    value["y"] = rotation
                variants[f"active={str(active).lower()},facing={facing},part={part}"] = value
    return {"variants": variants}


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def make_arm_texture():
    source_dir = ASSET_ROOT / "textures/block"
    sources = [
        source_dir / "gunsmith_press_dark.png",
        source_dir / "gunsmith_press_brass.png",
        source_dir / "gunsmith_press_metal.png",
        source_dir / "gunsmith_press_hot.png",
    ]
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for index, source in enumerate(sources):
        texture = Image.open(source).convert("RGBA").resize((32, 32), Image.Resampling.NEAREST)
        image.alpha_composite(texture, ((index % 2) * 32, (index // 2) * 32))
    ARM_TEXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    image.save(ARM_TEXTURE_PATH)


def project(point, yaw, viewport):
    x, y, z = point
    x -= 16.0
    z -= 16.0
    radians = math.radians(yaw)
    rotated_x = math.cos(radians) * x - math.sin(radians) * z
    depth = math.sin(radians) * x + math.cos(radians) * z
    left, top, right, bottom = viewport
    scale = min((right - left) / 45.0, (bottom - top) / 30.0)
    screen_x = (left + right) / 2.0 + rotated_x * scale
    screen_y = bottom - 90.0 - y * scale * 0.88 + depth * scale * 0.43
    return screen_x, screen_y, depth


def shade(color, factor):
    return tuple(max(0, min(255, int(channel * factor))) for channel in color[:3]) + (color[3],)


def draw_box(draw, box, yaw, viewport):
    x0, y0, z0 = box["from"]
    x1, y1, z1 = box["to"]
    corners = {
        "000": (x0, y0, z0), "001": (x0, y0, z1),
        "010": (x0, y1, z0), "011": (x0, y1, z1),
        "100": (x1, y0, z0), "101": (x1, y0, z1),
        "110": (x1, y1, z0), "111": (x1, y1, z1),
    }
    faces = [
        (("010", "110", "111", "011"), box["top"], 1.08),
        (("000", "100", "110", "010"), box["front"], 0.92),
        (("100", "101", "111", "110"), box["texture"], 0.82),
        (("101", "001", "011", "111"), box["texture"], 0.74),
        (("001", "000", "010", "011"), box["texture"], 0.68),
    ]
    polygons = []
    for keys, texture, light in faces:
        projected = [project(corners[key], yaw, viewport) for key in keys]
        polygons.append((sum(point[2] for point in projected) / 4.0, projected, texture, light))
    for _, points, texture, light in sorted(polygons, key=lambda value: value[0]):
        xy = [(point[0], point[1]) for point in points]
        draw.polygon(xy, fill=shade(COLORS[texture], light), outline=(9, 13, 17, 255))


def arm_points(frame_index, frame_count):
    cycle = frame_index / frame_count * math.tau
    sweep = math.sin(cycle * 0.55) * math.radians(17)
    reach = math.sin(cycle)
    yaw = math.radians(225) + sweep
    direction = (math.cos(yaw), math.sin(yaw))
    base = (24.5, 9.0, 24.0)
    elbow_distance = 4.3 + reach * 0.5
    elbow = (base[0] + direction[0] * elbow_distance, 14.1 + reach * 0.6,
             base[2] + direction[1] * elbow_distance)
    wrist_distance = 6.8 - reach * 0.7
    wrist = (elbow[0] + direction[0] * wrist_distance, 8.4 - reach * 0.8,
             elbow[2] + direction[1] * wrist_distance)
    tool = (wrist[0] + direction[0] * 1.4, 5.9 + math.sin(cycle * 2.3) * 0.25,
            wrist[2] + direction[1] * 1.4)
    return base, elbow, wrist, tool


def draw_arm(draw, yaw, viewport, frame_index, frame_count, sparks):
    base, elbow, wrist, tool = arm_points(frame_index, frame_count)
    projected = [project(point, yaw, viewport) for point in (base, elbow, wrist, tool)]
    xy = [(point[0], point[1]) for point in projected]
    draw.line(xy[:2], fill=COLORS["dark"], width=14)
    draw.line(xy[1:3], fill=COLORS["metal"], width=13)
    draw.line(xy[2:4], fill=COLORS["brass"], width=9)
    for index, point in enumerate(xy[:3]):
        radius = 9 if index == 1 else 7
        draw.ellipse((point[0] - radius, point[1] - radius, point[0] + radius, point[1] + radius),
                     fill=COLORS["brass"], outline=(10, 14, 18, 255), width=2)
    tx, ty = xy[3]
    draw.rectangle((tx - 6, ty - 7, tx + 6, ty + 8), fill=COLORS["hot"], outline=(10, 14, 18, 255))
    draw.line((tx - 10, ty + 8, tx - 3, ty + 2), fill=COLORS["brass"], width=4)
    draw.line((tx + 10, ty + 8, tx + 3, ty + 2), fill=COLORS["brass"], width=4)
    if sparks:
        for angle in range(0, 360, 60):
            radians = math.radians(angle + frame_index * 17)
            length = 5 + (frame_index + angle) % 6
            draw.line((tx, ty, tx + math.cos(radians) * length, ty + math.sin(radians) * length),
                      fill=(255, 200, 74, 255), width=2)


def draw_background(image):
    draw = ImageDraw.Draw(image)
    tile = 48
    for y in range(0, image.height, tile):
        for x in range(0, image.width, tile):
            color = (24, 29, 35, 255) if (x // tile + y // tile) % 2 == 0 else (28, 33, 40, 255)
            draw.rectangle((x, y, x + tile, y + tile), fill=color)


def render_view(image, viewport, yaw, frame_index, frame_count, sparks=False):
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = viewport
    draw.ellipse((left + 35, bottom - 70, right - 35, bottom - 25), fill=(4, 6, 8, 185))
    ordered = sorted(STATIC_BOXES, key=lambda box: project((
        (box["from"][0] + box["to"][0]) / 2,
        (box["from"][1] + box["to"][1]) / 2,
        (box["from"][2] + box["to"][2]) / 2,
    ), yaw, viewport)[2])
    for box in ordered:
        draw_box(draw, box, yaw, viewport)
    draw_arm(draw, yaw, viewport, frame_index, frame_count, sparks)


def render_previews():
    preview = Image.new("RGBA", (960, 720), (20, 24, 29, 255))
    draw_background(preview)
    render_view(preview, (25, 30, 475, 680), -35, 0, 32)
    render_view(preview, (485, 30, 935, 680), 145, 0, 32)
    ImageDraw.Draw(preview).line((480, 55, 480, 650), fill=(76, 88, 99, 255), width=2)
    preview.save(PREVIEW_PATH)

    frames = []
    frame_count = 32
    for frame_index in range(frame_count):
        frame = Image.new("RGBA", (900, 680), (20, 24, 29, 255))
        draw_background(frame)
        render_view(frame, (25, 20, 875, 650), -35, frame_index, frame_count,
                    sparks=frame_index % 5 != 4)
        frames.append(frame.convert("P", palette=Image.Palette.ADAPTIVE, colors=192))
    frames[0].save(ANIMATION_PATH, save_all=True, append_images=frames[1:],
                   duration=85, loop=0, disposal=2, optimize=False)


def main():
    for part in PART_BOUNDS:
        write_json(MODEL_ROOT / f"gunsmith_assembly_bench_{part}.json", block_model(part))
    write_json(ITEM_MODEL_PATH, item_model())
    write_json(BLOCKSTATE_PATH, blockstate())
    make_arm_texture()
    render_previews()


if __name__ == "__main__":
    main()
