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
    "frame": "miningdim:block/gunsmith_assembly_frame",
    "steel": "miningdim:block/gunsmith_assembly_steel",
    "dark": "miningdim:block/gunsmith_assembly_dark",
    "workmat": "miningdim:block/gunsmith_assembly_workmat",
    "brass": "miningdim:block/gunsmith_assembly_brass",
    "panel": "miningdim:block/gunsmith_assembly_panel",
    "warning": "miningdim:block/gunsmith_assembly_warning",
    "hot": "miningdim:block/gunsmith_assembly_hot",
    "particle": "miningdim:block/gunsmith_assembly_frame",
}

COLORS = {
    "frame": (43, 51, 58, 255),
    "steel": (105, 122, 132, 255),
    "dark": (22, 27, 32, 255),
    "workmat": (25, 125, 123, 255),
    "brass": (191, 137, 43, 255),
    "panel": (35, 207, 194, 255),
    "warning": (234, 170, 42, 255),
    "hot": (246, 91, 28, 255),
}

PART_BOUNDS = {
    "main": (0.0, 16.0, 0.0, 16.0),
    "side": (16.0, 32.0, 0.0, 16.0),
    "back": (0.0, 16.0, 16.0, 32.0),
    "back_side": (16.0, 32.0, 16.0, 32.0),
}

STATIC_BOXES = []
ACTIVE_BOXES = []
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


# Heavy plinth, lower cabinets and a full-width cold-steel countertop.
add_box((0.5, 0, 1), (31.5, 1, 30.5), "dark", top="frame")
add_box((1, 1, 1.5), (9.5, 7.25, 28), "frame", top="steel")
add_box((22.5, 1, 1.5), (31, 7.25, 28), "frame", top="steel")
add_box((9.5, 1, 18), (22.5, 7.25, 28), "frame", top="steel")
add_box((0, 7.25, 0.5), (32, 8.5, 30.5), "frame", top="steel")
add_box((1, 8.5, 1.5), (31, 8.85, 29.5), "steel", top="workmat")
add_box((4, 8.85, 4), (28, 9.1, 26), "dark", top="workmat")
add_box((9.5, 7.55, 0.15), (22.5, 8.2, 0.55), "warning", front="warning", top="steel")

# Front tool drawers, recessed labels and brass pulls.
for x0, x1 in ((1.4, 9.1), (22.9, 30.6)):
    for y0, y1 in ((1.55, 3.1), (3.35, 5.05), (5.3, 6.9)):
        add_box((x0, y0, 0.85), (x1, y1, 1.55), "dark", front="steel", top="frame")
        add_box(((x0 + x1) / 2 - 1.5, y1 - 0.45, 0.55),
                ((x0 + x1) / 2 + 1.5, y1 - 0.15, 0.95), "brass")
add_box((11, 4.8, 17.55), (21, 6.7, 18.15), "dark", front="steel", top="frame")
add_box((14.3, 6.25, 17.3), (17.7, 6.55, 17.7), "brass")

# Central gunsmith fixture: T-slot rails, receiver cradle and adjustable jaws.
add_box((3, 9.1, 12.6), (29, 9.55, 13.6), "steel", top="brass")
add_box((3, 9.1, 18.4), (29, 9.55, 19.4), "steel", top="brass")
add_box((5, 9.1, 14), (27, 9.4, 18), "dark", top="steel")
add_box((8, 9.4, 14.2), (10, 10.2, 17.8), "frame", top="brass")
add_box((22, 9.4, 14.2), (24, 10.2, 17.8), "frame", top="brass")
add_box((12, 9.4, 13.8), (20, 10.0, 18.2), "dark", top="steel")
add_box((13, 10.0, 14.6), (19, 10.35, 17.4), "brass", top="steel")
add_box((15, 10.35, 14.2), (17, 11.25, 17.8), "frame", top="dark")
add_box((6.5, 9.45, 15.4), (12.5, 10.05, 16.6), "dark", top="steel")
add_box((19.5, 9.45, 15.1), (26.5, 10.1, 16.9), "dark", top="steel")

# Rear open gantry with a tool rail and protected task light.
add_box((1, 8.5, 26), (3.25, 16, 29), "frame", front="dark", top="steel")
add_box((28.75, 8.5, 26), (31, 16, 29), "frame", front="dark", top="steel")
add_box((1, 14, 25.75), (31, 16, 29), "frame", front="dark", top="steel")
add_box((3.25, 11.5, 27), (28.75, 12.3, 28.2), "dark", front="steel", top="brass")
add_box((8, 13.35, 25.45), (24, 14.0, 26.15), "dark", front="panel", top="steel")
for x0 in (5, 11, 17):
    add_box((x0, 10.4, 27.2), (x0 + 2.8, 11.4, 28.0), "steel", front="dark", top="brass")

# Right-hand side console and guarded status controls.
add_box((28.5, 9.0, 4.5), (31.55, 13.2, 12.5), "dark", top="steel")
add_box((31.55, 9.7, 5.5), (32, 12.4, 11.4), "panel", top="panel")
add_box((31.6, 9.1, 6.2), (32, 9.55, 7.1), "brass", top="brass")
add_box((31.6, 9.1, 8.1), (32, 9.55, 9.0), "warning", top="warning")

# The renderer's shoulder remains anchored at global (26.5, 9, 24).
add_box((23.5, 8.5, 21), (29.5, 8.8, 27), "dark", top="steel")
add_box((24, 8.8, 21.5), (29, 9.0, 26.5), "brass", top="steel")

# Active-only work lights and heated clamp inserts.
add_box((8.5, 13.4, 25.25), (23.5, 14.05, 25.55), "hot", front="panel", top="hot", target=ACTIVE_BOXES)
add_box((8.35, 10.15, 14.4), (9.65, 10.45, 17.6), "hot", top="hot", target=ACTIVE_BOXES)
add_box((22.35, 10.15, 14.4), (23.65, 10.45, 17.6), "hot", top="hot", target=ACTIVE_BOXES)
add_box((31.6, 11.55, 6.2), (32, 12.1, 10.8), "hot", top="panel", target=ACTIVE_BOXES)

# Parked arm silhouette used only by the inventory model.
add_box((24, 8.6, 21.5), (29, 9.8, 26.5), "dark", top="steel", target=ITEM_ARM_BOXES)
add_box((22, 9.4, 22), (24.5, 14.4, 24.5), "dark", top="steel", target=ITEM_ARM_BOXES)
add_box((22.8, 9.9, 21.7), (23.7, 13.9, 24.8), "brass", top="steel", target=ITEM_ARM_BOXES)
add_box((17, 11.9, 22.2), (22.5, 14.1, 24.3), "dark", top="steel", target=ITEM_ARM_BOXES)
add_box((17.5, 12.4, 21.9), (22, 13.3, 24.6), "brass", top="steel", target=ITEM_ARM_BOXES)
add_box((15.8, 10.4, 21.8), (18.8, 12.9, 24.8), "steel", top="dark", target=ITEM_ARM_BOXES)
add_box((16.3, 9.6, 22.3), (18.3, 10.7, 24.3), "hot", top="hot", target=ITEM_ARM_BOXES)


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


def clip_boxes_for_part(part, active=False):
    min_x, max_x, min_z, max_z = PART_BOUNDS[part]
    elements = []
    boxes = STATIC_BOXES + (ACTIVE_BOXES if active else [])
    for box in boxes:
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


def block_model(part, active=False):
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": TEXTURES,
        "elements": clip_boxes_for_part(part, active),
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
                suffix = "_active" if active else ""
                value = {"model": f"miningdim:block/gunsmith_assembly_bench_{part}{suffix}"}
                if rotation is not None:
                    value["y"] = rotation
                variants[f"active={str(active).lower()},facing={facing},part={part}"] = value
    return {"variants": variants}


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def shifted(color, amount):
    return tuple(max(0, min(255, channel + amount)) for channel in color) + (255,)


def make_block_textures():
    texture_dir = ASSET_ROOT / "textures/block"
    texture_dir.mkdir(parents=True, exist_ok=True)

    def base_image(color, spread=5):
        image = Image.new("RGBA", (16, 16), color + (255,))
        pixels = image.load()
        for y in range(16):
            for x in range(16):
                amount = ((x * 11 + y * 7 + x * y * 3) % (spread * 2 + 1)) - spread
                pixels[x, y] = shifted(color, amount)
        return image

    frame = base_image((43, 51, 58), 4)
    draw = ImageDraw.Draw(frame)
    draw.rectangle((0, 0, 15, 15), outline=(18, 23, 28, 255))
    draw.line((1, 1, 14, 1), fill=(72, 82, 90, 255))
    draw.point([(2, 3), (13, 3), (2, 12), (13, 12)], fill=(132, 144, 150, 255))
    draw.point([(3, 4), (12, 4), (3, 13), (12, 13)], fill=(14, 18, 22, 255))
    frame.save(texture_dir / "gunsmith_assembly_frame.png")

    steel = base_image((101, 119, 130), 8)
    draw = ImageDraw.Draw(steel)
    for y in (2, 6, 11, 14):
        draw.line((1, y, 14, y), fill=(132, 150, 158, 255))
    draw.line((0, 15, 15, 15), fill=(43, 53, 61, 255))
    draw.point([(3, 5), (9, 9), (13, 3), (6, 13)], fill=(67, 81, 91, 255))
    steel.save(texture_dir / "gunsmith_assembly_steel.png")

    dark = base_image((22, 27, 32), 3)
    draw = ImageDraw.Draw(dark)
    for offset in range(-12, 17, 6):
        draw.line((offset, 15, offset + 15, 0), fill=(34, 41, 47, 255))
    draw.rectangle((0, 0, 15, 15), outline=(10, 13, 16, 255))
    dark.save(texture_dir / "gunsmith_assembly_dark.png")

    workmat = base_image((20, 112, 111), 4)
    draw = ImageDraw.Draw(workmat)
    draw.rectangle((0, 0, 15, 15), outline=(8, 45, 48, 255))
    for value in (4, 8, 12):
        draw.line((value, 1, value, 14), fill=(25, 132, 129, 255))
        draw.line((1, value, 14, value), fill=(25, 132, 129, 255))
    draw.point([(2, 2), (13, 2), (2, 13), (13, 13)], fill=(73, 197, 184, 255))
    workmat.save(texture_dir / "gunsmith_assembly_workmat.png")

    brass = base_image((181, 127, 37), 9)
    draw = ImageDraw.Draw(brass)
    draw.line((0, 2, 15, 2), fill=(244, 190, 72, 255))
    draw.line((0, 12, 15, 12), fill=(102, 69, 21, 255))
    for x in (3, 10):
        draw.line((x, 3, x + 3, 11), fill=(204, 148, 45, 255))
    brass.save(texture_dir / "gunsmith_assembly_brass.png")

    panel = Image.new("RGBA", (16, 16), (9, 24, 29, 255))
    draw = ImageDraw.Draw(panel)
    draw.rectangle((0, 0, 15, 15), outline=(34, 74, 78, 255))
    draw.rectangle((2, 2, 13, 10), fill=(12, 60, 63, 255), outline=(37, 208, 194, 255))
    draw.line((3, 4, 11, 4), fill=(88, 240, 218, 255))
    draw.line((3, 6, 8, 6), fill=(30, 164, 158, 255))
    draw.line((3, 8, 12, 8), fill=(24, 114, 113, 255))
    draw.rectangle((3, 12, 5, 13), fill=(233, 169, 43, 255))
    draw.rectangle((7, 12, 12, 13), fill=(31, 130, 127, 255))
    panel.save(texture_dir / "gunsmith_assembly_panel.png")

    warning = Image.new("RGBA", (16, 16), (228, 164, 38, 255))
    pixels = warning.load()
    for y in range(16):
        for x in range(16):
            if ((x + y) // 4) % 2 == 0:
                pixels[x, y] = (24, 28, 31, 255)
    draw = ImageDraw.Draw(warning)
    draw.rectangle((0, 0, 15, 15), outline=(12, 15, 17, 255))
    warning.save(texture_dir / "gunsmith_assembly_warning.png")

    hot = Image.new("RGBA", (16, 16), (45, 25, 20, 255))
    draw = ImageDraw.Draw(hot)
    draw.rectangle((0, 0, 15, 15), outline=(83, 34, 20, 255))
    draw.rectangle((2, 5, 13, 10), fill=(151, 46, 18, 255))
    draw.line((3, 6, 12, 6), fill=(255, 177, 54, 255), width=2)
    draw.line((4, 9, 11, 9), fill=(244, 75, 20, 255), width=2)
    draw.point([(2, 2), (13, 2), (2, 13), (13, 13)], fill=(255, 111, 26, 255))
    hot.save(texture_dir / "gunsmith_assembly_hot.png")


def make_arm_texture():
    source_dir = ASSET_ROOT / "textures/block"
    sources = [
        source_dir / "gunsmith_assembly_dark.png",
        source_dir / "gunsmith_assembly_brass.png",
        source_dir / "gunsmith_assembly_steel.png",
        source_dir / "gunsmith_assembly_hot.png",
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


def eased_step(phase, start, end):
    progress = max(0.0, min(1.0, (phase - start) / (end - start)))
    return progress * progress * (3.0 - 2.0 * progress)


def motion_window(phase, move_start, move_end, return_start, return_end):
    return eased_step(phase, move_start, move_end) * (1.0 - eased_step(phase, return_start, return_end))


def arm_points(frame_index, frame_count):
    phase = frame_index / frame_count
    turn = motion_window(phase, 0.04, 0.26, 0.72, 0.94)
    reach = motion_window(phase, 0.12, 0.32, 0.68, 0.88)
    weld = motion_window(phase, 0.40, 0.46, 0.58, 0.64)
    upper_angle = -0.78 + (-0.9195 + 0.78) * reach
    forearm_angle = 1.60 + (1.7825 - 1.60) * reach
    yaw = -math.atan2(8.0, 10.5) * turn
    base = (26.5, 9.0, 24.0)

    elbow_x = 8.0 * math.sin(upper_angle)
    elbow_model_y = -8.0 * math.cos(upper_angle)
    wrist_x = elbow_x - 9.0 * math.sin(upper_angle + forearm_angle)
    wrist_model_y = elbow_model_y + 9.0 * math.cos(upper_angle + forearm_angle)

    def world_point(local_x, model_y):
        return (base[0] + math.cos(yaw) * local_x,
                base[1] - model_y,
                base[2] - math.sin(yaw) * local_x)

    elbow = world_point(elbow_x, elbow_model_y)
    wrist = world_point(wrist_x, wrist_model_y)
    tool = (wrist[0], wrist[1] - 1.5, wrist[2])
    return base, elbow, wrist, tool, weld


def draw_arm(draw, yaw, viewport, frame_index, frame_count, sparks):
    base, elbow, wrist, tool, weld = arm_points(frame_index, frame_count)
    projected = [project(point, yaw, viewport) for point in (base, elbow, wrist, tool)]
    xy = [(point[0], point[1]) for point in projected]
    draw.line(xy[:2], fill=COLORS["dark"], width=13)
    draw.line(xy[1:3], fill=COLORS["steel"], width=11)
    draw.line(xy[2:4], fill=COLORS["brass"], width=7)
    for index, point in enumerate(xy[:3]):
        radius = 8 if index == 1 else 7
        draw.ellipse((point[0] - radius, point[1] - radius, point[0] + radius, point[1] + radius),
                     fill=COLORS["brass"], outline=(10, 14, 18, 255), width=2)
    tx, ty = xy[3]
    draw.rectangle((tx - 5, ty - 6, tx + 5, ty + 6), fill=COLORS["hot"], outline=(10, 14, 18, 255))
    draw.line((tx - 7, ty + 5, tx - 2, ty + 1), fill=COLORS["brass"], width=3)
    draw.line((tx + 7, ty + 5, tx + 2, ty + 1), fill=COLORS["brass"], width=3)
    if sparks and weld > 0.5:
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


def render_view(image, viewport, yaw, frame_index, frame_count, *, active=False, sparks=False):
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = viewport
    draw.ellipse((left + 35, bottom - 70, right - 35, bottom - 25), fill=(4, 6, 8, 185))
    boxes = STATIC_BOXES + (ACTIVE_BOXES if active else [])
    ordered = sorted(boxes, key=lambda box: project((
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
    render_view(preview, (485, 30, 935, 680), 145, 16, 32, active=True, sparks=True)
    ImageDraw.Draw(preview).line((480, 55, 480, 650), fill=(76, 88, 99, 255), width=2)
    preview.save(PREVIEW_PATH)

    frames = []
    frame_count = 32
    for frame_index in range(frame_count):
        frame = Image.new("RGBA", (900, 680), (20, 24, 29, 255))
        draw_background(frame)
        render_view(frame, (25, 20, 875, 650), -35, frame_index, frame_count,
                    active=True, sparks=frame_index % 5 != 4)
        frames.append(frame.convert("P", palette=Image.Palette.ADAPTIVE, colors=192))
    frames[0].save(ANIMATION_PATH, save_all=True, append_images=frames[1:],
                   duration=85, loop=0, disposal=2, optimize=False)


def main():
    for part in PART_BOUNDS:
        write_json(MODEL_ROOT / f"gunsmith_assembly_bench_{part}.json", block_model(part))
        write_json(MODEL_ROOT / f"gunsmith_assembly_bench_{part}_active.json", block_model(part, True))
    write_json(ITEM_MODEL_PATH, item_model())
    write_json(BLOCKSTATE_PATH, blockstate())
    make_block_textures()
    make_arm_texture()
    render_previews()


if __name__ == "__main__":
    main()
