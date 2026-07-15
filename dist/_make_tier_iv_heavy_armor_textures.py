from dataclasses import dataclass
from hashlib import sha256
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
TEXTURE_ROOT = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "miningdim"
    / "textures"
    / "models"
    / "armor"
)
SIZE = 128
RGBA = tuple[int, int, int, int]


@dataclass(frozen=True)
class Face:
    x0: float
    y0: float
    x1: float
    y1: float


@dataclass(frozen=True)
class CubeUV:
    name: str
    u: float
    v: float
    width: float
    height: float
    depth: float
    material: str


def cube_faces(cube: CubeUV) -> dict[str, Face]:
    u = cube.u
    v = cube.v
    width = cube.width
    height = cube.height
    depth = cube.depth
    return {
        "down": Face(u + depth, v, u + depth + width, v + depth),
        "up": Face(u + depth + width, v, u + depth + width * 2, v + depth),
        "west": Face(u, v + depth, u + depth, v + depth + height),
        "north": Face(u + depth, v + depth, u + depth + width, v + depth + height),
        "east": Face(u + depth + width, v + depth, u + depth + width + depth, v + depth + height),
        "south": Face(
            u + depth * 2 + width,
            v + depth,
            u + depth * 2 + width * 2,
            v + depth + height,
        ),
    }


MODELS: dict[str, tuple[CubeUV, ...]] = {
    "b6b23_mountain": (
        CubeUV("front_upper", 0, 0, 6.50, 3.20, 0.40, "mountain"),
        CubeUV("rear_upper", 15, 0, 6.50, 3.20, 0.40, "mountain"),
        CubeUV("front_middle", 30, 0, 7.50, 4.03, 0.42, "mountain"),
        CubeUV("rear_middle", 47, 0, 7.50, 4.03, 0.42, "mountain"),
        CubeUV("front_lower", 64, 0, 8.00, 4.03, 0.45, "mountain"),
        CubeUV("rear_lower", 82, 0, 8.00, 4.03, 0.45, "mountain"),
        CubeUV("left_side", 100, 0, 0.63, 7.92, 3.96, "dark_side"),
        CubeUV("right_side", 110, 0, 0.63, 7.92, 3.96, "dark_side"),
        CubeUV("front_collar_left", 0, 13, 3.75, 1.55, 0.42, "collar"),
        CubeUV("front_collar_right", 10, 13, 3.75, 1.55, 0.42, "collar"),
        CubeUV("rear_collar", 20, 13, 8.70, 1.55, 0.42, "collar"),
        CubeUV("left_collar", 40, 13, 0.42, 1.47, 8.16, "collar"),
        CubeUV("right_collar", 58, 13, 0.42, 1.47, 8.16, "collar"),
        CubeUV("front_yoke_left", 76, 13, 2.82, 0.25, 2.15, "webbing"),
        CubeUV("front_yoke_right", 87, 13, 2.82, 0.25, 2.15, "webbing"),
        CubeUV("rear_yoke_left", 98, 13, 2.82, 0.25, 2.15, "webbing"),
        CubeUV("rear_yoke_right", 109, 13, 2.82, 0.25, 2.15, "webbing"),
        CubeUV("front_fold", 0, 24, 7.80, 4.00, 0.26, "mountain"),
        CubeUV("front_apron", 18, 24, 6.20, 3.60, 0.35, "mountain"),
        CubeUV("waist_seam", 33, 24, 7.60, 0.40, 0.24, "webbing"),
    ),
    "b6b5_flora": (
        CubeUV("front_upper", 0, 0, 6.60, 3.00, 0.36, "flora"),
        CubeUV("rear_upper", 15, 0, 6.60, 3.00, 0.36, "flora"),
        CubeUV("front_middle", 30, 0, 7.50, 4.03, 0.40, "flora"),
        CubeUV("rear_middle", 47, 0, 7.50, 4.03, 0.40, "flora"),
        CubeUV("front_lower", 64, 0, 7.90, 4.23, 0.42, "flora"),
        CubeUV("rear_lower", 82, 0, 7.90, 4.23, 0.42, "flora"),
        CubeUV("left_side", 100, 0, 0.63, 8.14, 3.88, "flora_side"),
        CubeUV("right_side", 110, 0, 0.63, 8.14, 3.88, "flora_side"),
        CubeUV("front_collar_left", 0, 13, 3.80, 1.60, 0.42, "flora_collar"),
        CubeUV("front_collar_right", 10, 13, 3.80, 1.60, 0.42, "flora_collar"),
        CubeUV("rear_collar", 20, 13, 8.80, 1.60, 0.42, "flora_collar"),
        CubeUV("left_collar", 40, 13, 0.42, 1.52, 8.26, "flora_collar"),
        CubeUV("right_collar", 58, 13, 0.42, 1.52, 8.26, "flora_collar"),
        CubeUV("front_yoke_left", 76, 13, 2.70, 0.28, 2.21, "webbing"),
        CubeUV("front_yoke_right", 87, 13, 2.70, 0.28, 2.21, "webbing"),
        CubeUV("rear_yoke_left", 98, 13, 2.70, 0.28, 2.21, "webbing"),
        CubeUV("rear_yoke_right", 109, 13, 2.70, 0.28, 2.21, "webbing"),
        CubeUV("left_strap", 0, 24, 0.70, 4.60, 0.28, "webbing"),
        CubeUV("right_strap", 3, 24, 0.70, 4.60, 0.28, "webbing"),
        CubeUV("pouch_far_left", 6, 24, 1.55, 4.40, 0.78, "pouch"),
        CubeUV("pouch_left", 12, 24, 1.55, 4.40, 0.78, "pouch"),
        CubeUV("pouch_right", 18, 24, 1.55, 4.40, 0.78, "pouch"),
        CubeUV("pouch_far_right", 24, 24, 1.55, 4.40, 0.78, "pouch"),
        CubeUV("front_extension", 30, 24, 6.20, 3.00, 0.30, "flora"),
        CubeUV("front_tip", 44, 24, 3.50, 1.20, 0.25, "flora"),
    ),
    "osprey_assault": (
        CubeUV("front_upper", 0, 0, 6.80, 3.00, 0.50, "multicam"),
        CubeUV("rear_upper", 16, 0, 6.80, 3.00, 0.50, "multicam"),
        CubeUV("front_middle", 32, 0, 8.10, 4.03, 0.54, "multicam"),
        CubeUV("rear_middle", 51, 0, 8.10, 4.03, 0.54, "multicam"),
        CubeUV("front_lower", 70, 0, 8.30, 3.73, 0.58, "multicam"),
        CubeUV("rear_lower", 89, 0, 8.30, 3.73, 0.58, "multicam"),
        CubeUV("left_side", 108, 0, 0.73, 8.10, 4.00, "side"),
        CubeUV("right_side", 118, 0, 0.73, 8.10, 4.00, "side"),
        CubeUV("front_collar_left", 0, 13, 3.85, 1.85, 0.55, "collar"),
        CubeUV("front_collar_right", 10, 13, 3.85, 1.85, 0.55, "collar"),
        CubeUV("rear_collar", 20, 13, 9.00, 1.85, 0.55, "collar"),
        CubeUV("left_collar", 41, 13, 0.55, 1.77, 8.32, "collar"),
        CubeUV("right_collar", 60, 13, 0.55, 1.77, 8.32, "collar"),
        CubeUV("front_yoke_left", 79, 13, 2.87, 0.36, 2.23, "webbing"),
        CubeUV("front_yoke_right", 90, 13, 2.87, 0.36, 2.23, "webbing"),
        CubeUV("rear_yoke_left", 101, 13, 2.87, 0.36, 2.23, "webbing"),
        CubeUV("rear_yoke_right", 112, 13, 2.87, 0.36, 2.23, "webbing"),
        CubeUV("upper_mag_1", 0, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("upper_mag_2", 4, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("upper_mag_3", 8, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("upper_mag_4", 12, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("upper_mag_5", 16, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("upper_mag_6", 20, 24, 0.90, 3.40, 0.60, "magazine"),
        CubeUV("lower_pouch_left", 25, 24, 2.25, 3.80, 0.72, "pouch"),
        CubeUV("lower_pouch_middle", 32, 24, 2.24, 3.80, 0.72, "pouch"),
        CubeUV("lower_pouch_right", 39, 24, 2.25, 3.80, 0.72, "pouch"),
        CubeUV("utility_pouch", 46, 24, 1.00, 4.00, 1.40, "utility"),
        CubeUV("front_belt", 52, 24, 8.00, 2.30, 0.55, "webbing"),
        CubeUV("pull_tab", 70, 24, 1.80, 0.85, 0.45, "webbing"),
        CubeUV("right_shoulder_top", 0, 31, 4.75, 0.55, 5.10, "shoulder"),
        CubeUV("left_shoulder_top", 21, 31, 4.75, 0.55, 5.10, "shoulder"),
        CubeUV("right_shoulder_outer", 42, 31, 0.48, 5.02, 4.70, "shoulder"),
        CubeUV("left_shoulder_outer", 54, 31, 0.48, 5.02, 4.70, "shoulder"),
        CubeUV("right_shoulder_front", 66, 31, 4.68, 5.05, 0.42, "shoulder"),
        CubeUV("right_shoulder_rear", 78, 31, 4.68, 4.99, 0.42, "shoulder"),
        CubeUV("left_shoulder_front", 90, 31, 4.68, 5.05, 0.42, "shoulder"),
        CubeUV("left_shoulder_rear", 102, 31, 4.68, 4.99, 0.42, "shoulder"),
    ),
}


DETAILS: dict[str, tuple[tuple[str, str, str], ...]] = {
    "b6b23_mountain": (
        ("front_upper", "north", "stitched"),
        ("rear_upper", "south", "stitched"),
        ("front_middle", "north", "soft_panel"),
        ("rear_middle", "south", "soft_panel"),
        ("front_lower", "north", "stitched"),
        ("rear_lower", "south", "stitched"),
        ("left_side", "west", "channels"),
        ("right_side", "east", "channels"),
        ("front_collar_left", "north", "collar"),
        ("front_collar_right", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("front_yoke_left", "up", "strap"),
        ("front_yoke_right", "up", "strap"),
        ("rear_yoke_left", "up", "strap"),
        ("rear_yoke_right", "up", "strap"),
        ("front_fold", "north", "fold"),
        ("front_apron", "north", "stitched"),
        ("waist_seam", "north", "webbing"),
    ),
    "b6b5_flora": (
        ("front_upper", "north", "stitched"),
        ("rear_upper", "south", "stitched"),
        ("front_middle", "north", "soft_panel"),
        ("rear_middle", "south", "soft_panel"),
        ("front_lower", "north", "stitched"),
        ("rear_lower", "south", "stitched"),
        ("left_side", "west", "channels"),
        ("right_side", "east", "channels"),
        ("front_collar_left", "north", "collar"),
        ("front_collar_right", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("front_yoke_left", "up", "strap"),
        ("front_yoke_right", "up", "strap"),
        ("rear_yoke_left", "up", "strap"),
        ("rear_yoke_right", "up", "strap"),
        ("left_strap", "north", "webbing"),
        ("right_strap", "north", "webbing"),
        ("pouch_far_left", "north", "pouch"),
        ("pouch_left", "north", "pouch"),
        ("pouch_right", "north", "pouch"),
        ("pouch_far_right", "north", "pouch"),
        ("front_extension", "north", "stitched"),
        ("front_tip", "north", "fold"),
    ),
    "osprey_assault": (
        ("front_upper", "north", "molle"),
        ("rear_upper", "south", "stitched"),
        ("front_middle", "north", "molle"),
        ("rear_middle", "south", "molle"),
        ("front_lower", "north", "molle"),
        ("rear_lower", "south", "stitched"),
        ("left_side", "west", "channels"),
        ("right_side", "east", "channels"),
        ("front_collar_left", "north", "collar"),
        ("front_collar_right", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("front_yoke_left", "up", "strap"),
        ("front_yoke_right", "up", "strap"),
        ("rear_yoke_left", "up", "strap"),
        ("rear_yoke_right", "up", "strap"),
        ("upper_mag_1", "north", "magazine"),
        ("upper_mag_2", "north", "magazine"),
        ("upper_mag_3", "north", "magazine"),
        ("upper_mag_4", "north", "magazine"),
        ("upper_mag_5", "north", "magazine"),
        ("upper_mag_6", "north", "magazine"),
        ("lower_pouch_left", "north", "pouch"),
        ("lower_pouch_middle", "north", "pouch"),
        ("lower_pouch_right", "north", "pouch"),
        ("utility_pouch", "north", "pouch"),
        ("front_belt", "north", "molle"),
        ("pull_tab", "north", "strap"),
        ("right_shoulder_top", "up", "shoulder"),
        ("left_shoulder_top", "up", "shoulder"),
        ("right_shoulder_outer", "west", "shoulder"),
        ("left_shoulder_outer", "east", "shoulder"),
        ("right_shoulder_front", "north", "shoulder"),
        ("right_shoulder_rear", "south", "shoulder"),
        ("left_shoulder_front", "north", "shoulder"),
        ("left_shoulder_rear", "south", "shoulder"),
    ),
}


THEMES = {
    "plate_armor_6b23_2_mountain_flora_layer_1.png": {
        "model": "b6b23_mountain",
        "background": ((43, 50, 41, 255),),
        "palette": {
            "mountain": (
                (194, 191, 130, 255),
                (179, 169, 114, 255),
                (160, 130, 84, 255),
                (127, 112, 75, 255),
                (113, 97, 64, 255),
            ),
            "dark_side": ((30, 34, 27, 255), (43, 50, 41, 255), (59, 60, 46, 255)),
            "collar": ((134, 131, 89, 255), (171, 147, 98, 255), (113, 97, 64, 255)),
            "webbing": ((81, 84, 63, 255), (113, 97, 64, 255)),
        },
        "edge": (62, 58, 41, 255),
        "stitch": (213, 211, 147, 255),
    },
    "plate_armor_6b5_15_flora_layer_1.png": {
        "model": "b6b5_flora",
        "background": ((22, 28, 22, 255),),
        "palette": {
            "flora": (
                (70, 83, 62, 255),
                (91, 106, 82, 255),
                (103, 112, 86, 255),
                (59, 64, 48, 255),
                (37, 44, 33, 255),
            ),
            "flora_side": ((37, 44, 33, 255), (48, 59, 43, 255), (64, 73, 54, 255)),
            "flora_collar": ((48, 59, 43, 255), (70, 83, 62, 255), (82, 87, 67, 255)),
            "webbing": ((64, 73, 54, 255), (82, 87, 67, 255)),
            "pouch": ((86, 96, 74, 255), (103, 112, 86, 255), (59, 64, 48, 255)),
        },
        "edge": (22, 28, 22, 255),
        "stitch": (137, 149, 119, 255),
    },
    "plate_armor_osprey_mk4a_assault_layer_1.png": {
        "model": "osprey_assault",
        "background": ((27, 28, 19, 255),),
        "palette": {
            "multicam": (
                (94, 89, 71, 255),
                (112, 107, 85, 255),
                (131, 128, 96, 255),
                (70, 74, 58, 255),
                (52, 51, 38, 255),
                (40, 46, 33, 255),
            ),
            "side": ((40, 46, 33, 255), (56, 60, 46, 255), (70, 74, 58, 255)),
            "collar": ((56, 60, 46, 255), (83, 82, 64, 255), (98, 99, 81, 255)),
            "webbing": ((52, 51, 38, 255), (70, 74, 58, 255), (83, 82, 64, 255)),
            "magazine": ((65, 63, 48, 255), (83, 82, 64, 255), (40, 46, 33, 255)),
            "pouch": ((70, 74, 58, 255), (94, 89, 71, 255), (52, 51, 38, 255)),
            "utility": ((40, 46, 33, 255), (65, 63, 48, 255)),
            "shoulder": ((70, 74, 58, 255), (94, 89, 71, 255), (112, 107, 85, 255), (40, 46, 33, 255)),
        },
        "edge": (27, 28, 19, 255),
        "stitch": (186, 182, 134, 255),
    },
}


EXPECTED_CUBES = {"b6b23_mountain": 20, "b6b5_flora": 25, "osprey_assault": 37}


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(value)) % 8191


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def pattern_pixel(colors: tuple[RGBA, ...], x: int, y: int, seed: int) -> RGBA:
    # Warped 3x2 clusters produce broad, irregular camouflage rather than a digital grid.
    warped_x = x + ((y * 7 + seed * 3) % 5) - 2
    warped_y = y + ((x * 5 + seed) % 3) - 1
    cell_x = warped_x // 3
    cell_y = warped_y // 2
    cluster = (
        cell_x * 37
        + cell_y * 53
        + (cell_x // 2) * (cell_y // 2) * 11
        + (cell_x ^ cell_y) * 17
        + seed
    )
    color = colors[cluster % len(colors)]
    weave = ((x * 71 + y * 149 + seed * 31 + (x + 3) * (y + 11) * 7) & 0xFF) % 5 - 2
    if (x + 2 * y + seed) % 29 == 0:
        weave += 3
    return shade(color, weave)


def paint_face(
    image: Image.Image,
    face: Face,
    colors: tuple[RGBA, ...],
    seed: int,
) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    pixels = image.load()
    for y in range(bounds[1], bounds[3]):
        for x in range(bounds[0], bounds[2]):
            pixels[x, y] = pattern_pixel(colors, x, y, seed)
    return bounds


def stitch_border(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    stitch: RGBA,
    edge: RGBA,
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 3:
        return
    for x in range(x0 + 1, x1 - 1, 3):
        draw.point((x, y0 + 1), fill=stitch)
        draw.point((x, y1 - 2), fill=edge)
    for y in range(y0 + 2, y1 - 2, 3):
        draw.point((x0 + 1, y), fill=stitch)
        draw.point((x1 - 2, y), fill=edge)


def detail_face(
    image: Image.Image,
    bounds: tuple[int, int, int, int],
    kind: str,
    base: RGBA,
    edge: RGBA,
    stitch: RGBA,
) -> None:
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width >= 3 and height >= 3:
        draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 7))
        draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -9))
        draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 4))
        draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -6))

    if kind in {"stitched", "soft_panel", "collar", "fold", "shoulder"}:
        stitch_border(draw, bounds, stitch, edge)
    if kind == "soft_panel" and width >= 6 and height >= 5:
        draw.line((x0 + 2, y0 + height // 2, x1 - 3, y0 + height // 2), fill=shade(base, -7))
    elif kind == "channels" and width >= 4 and height >= 5:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.line((x, y0 + 1, x, y1 - 2), fill=shade(base, -8))
    elif kind in {"strap", "webbing"} and width >= 2 and height >= 2:
        draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -8))
        if width >= 4:
            draw.line((x1 - 2, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 5))
    elif kind == "pouch" and width >= 3 and height >= 4:
        flap_y = min(y0 + 2, y1 - 2)
        draw.line((x0, flap_y, x1 - 1, flap_y), fill=edge)
        stitch_border(draw, bounds, stitch, edge)
        if width >= 5:
            draw.line((x0 + width // 2, flap_y + 1, x0 + width // 2, y1 - 2), fill=shade(base, -7))
    elif kind == "magazine" and width >= 2 and height >= 4:
        draw.line((x0, min(y0 + 2, y1 - 1), x1 - 1, min(y0 + 2, y1 - 1)), fill=edge)
        if width >= 3:
            draw.line((x0 + width // 2, y0 + 1, x0 + width // 2, y1 - 2), fill=shade(base, -8))
    elif kind == "molle" and width >= 5 and height >= 4:
        stitch_border(draw, bounds, stitch, edge)
        for y in range(y0 + 2, y1 - 1, 2):
            draw.line((x0 + 1, y, x1 - 2, y), fill=shade(base, -8))
            for x in range(x0 + 3, x1 - 1, 4):
                draw.point((x, y), fill=shade(base, 5))
    elif kind == "collar" and width >= 5:
        draw.line((x0 + width // 2, y0 + 1, x0 + width // 2, y1 - 2), fill=shade(base, -7))
    elif kind == "fold" and width >= 4 and height >= 3:
        draw.line((x0 + 1, y0 + height // 2, x1 - 2, y0 + height // 2), fill=shade(base, -7))
    elif kind == "shoulder" and width >= 5 and height >= 4:
        draw.line((x0 + 2, y1 - 2, x1 - 3, y1 - 2), fill=edge)


def cube_net_bounds(cube: CubeUV) -> Face:
    faces = tuple(cube_faces(cube).values())
    return Face(
        min(face.x0 for face in faces),
        min(face.y0 for face in faces),
        max(face.x1 for face in faces),
        max(face.y1 for face in faces),
    )


def overlaps(first: Face, second: Face) -> bool:
    return (
        min(first.x1, second.x1) > max(first.x0, second.x0)
        and min(first.y1, second.y1) > max(first.y0, second.y0)
    )


def validate_models() -> None:
    for model_name, cubes in MODELS.items():
        if len(cubes) != EXPECTED_CUBES[model_name]:
            raise RuntimeError(f"Unexpected cuboid count for {model_name}: {len(cubes)}")
        names = {cube.name for cube in cubes}
        if len(names) != len(cubes):
            raise RuntimeError(f"Duplicate cuboid name in {model_name}")
        for cube in cubes:
            for direction, face in cube_faces(cube).items():
                if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                    raise RuntimeError(f"UV overflow: {model_name}.{cube.name}.{direction}={face}")
        for index, cube in enumerate(cubes):
            first = cube_net_bounds(cube)
            for other in cubes[index + 1 :]:
                if overlaps(first, cube_net_bounds(other)):
                    raise RuntimeError(f"UV conflict: {model_name}.{cube.name}/{other.name}")
        for cube_name, direction, _ in DETAILS[model_name]:
            if cube_name not in names:
                raise RuntimeError(f"Invalid detail cube: {model_name}.{cube_name}")
            cube = next(candidate for candidate in cubes if candidate.name == cube_name)
            if direction not in cube_faces(cube):
                raise RuntimeError(f"Invalid detail face: {model_name}.{cube_name}.{direction}")


def build_texture(filename: str) -> Image.Image:
    theme = THEMES[filename]
    model_name = theme["model"]
    cubes = MODELS[model_name]
    palette = theme["palette"]
    background = theme["background"]

    image = Image.new("RGBA", (SIZE, SIZE), background[0])
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = pattern_pixel(background, x, y, 17)

    cube_by_name = {cube.name: cube for cube in cubes}
    for cube in cubes:
        colors = palette[cube.material]
        for direction, face in cube_faces(cube).items():
            paint_face(image, face, colors, stable_seed(f"{filename}:{cube.name}:{direction}"))

    for cube_name, direction, kind in DETAILS[model_name]:
        cube = cube_by_name[cube_name]
        colors = palette[cube.material]
        bounds = paint_face(
            image,
            cube_faces(cube)[direction],
            colors,
            stable_seed(f"{filename}:{cube_name}:{direction}:detail"),
        )
        detail_face(image, bounds, kind, colors[0], theme["edge"], theme["stitch"])
    return image


def main() -> None:
    validate_models()
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for filename in THEMES:
        image = build_texture(filename)
        if image.tobytes() != build_texture(filename).tobytes():
            raise RuntimeError(f"Texture generation is not deterministic: {filename}")
        output = TEXTURE_ROOT / filename
        image.save(output, format="PNG", optimize=False)
        with Image.open(output) as written:
            if written.size != (SIZE, SIZE) or written.mode != "RGBA":
                raise RuntimeError(f"Invalid texture format: {filename}")
            if written.getextrema()[3] != (255, 255):
                raise RuntimeError(f"Texture must be fully opaque: {filename}")
            colors = written.getcolors(maxcolors=SIZE * SIZE)
            if colors is None or len(colors) < 64:
                raise RuntimeError(f"Texture lost material detail: {filename}")
        digest = sha256(output.read_bytes()).hexdigest().upper()
        print(f"{filename} model={THEMES[filename]['model']} colors={len(colors)} sha256={digest}")
    print("uv=ok alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
