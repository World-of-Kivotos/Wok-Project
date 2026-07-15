from dataclasses import dataclass
from hashlib import sha256
from math import ceil, floor
from pathlib import Path
import re

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
        CubeUV("front_upper", 0, 0, 6.50, 3.20, 0.42, "mountain"),
        CubeUV("rear_upper", 15, 0, 6.50, 3.20, 0.42, "mountain"),
        CubeUV("front_middle", 30, 0, 7.50, 4.05, 0.47, "mountain"),
        CubeUV("rear_middle", 47, 0, 7.50, 4.05, 0.47, "mountain"),
        CubeUV("front_lower", 64, 0, 8.00, 4.10, 0.48, "mountain"),
        CubeUV("rear_lower", 82, 0, 8.00, 4.10, 0.48, "mountain"),
        CubeUV("left_side", 100, 0, 0.46, 8.10, 3.96, "dark_side"),
        CubeUV("right_side", 110, 0, 0.46, 8.10, 3.96, "dark_side"),
        CubeUV("front_collar_left", 0, 14, 3.75, 1.80, 0.44, "collar"),
        CubeUV("front_collar_right", 10, 14, 3.75, 1.80, 0.44, "collar"),
        CubeUV("rear_collar", 20, 14, 8.70, 1.68, 0.44, "collar"),
        CubeUV("left_collar", 40, 14, 0.44, 1.56, 8.12, "collar"),
        CubeUV("right_collar", 59, 14, 0.44, 1.56, 8.12, "collar"),
        CubeUV("front_yoke_left", 78, 14, 3.00, 0.90, 2.16, "webbing"),
        CubeUV("front_yoke_right", 90, 14, 3.00, 0.90, 2.16, "webbing"),
        CubeUV("rear_yoke_left", 102, 14, 3.00, 0.90, 2.16, "webbing"),
        CubeUV("rear_yoke_right", 114, 14, 3.00, 0.90, 2.16, "webbing"),
        CubeUV("front_fold", 0, 25, 7.80, 4.25, 0.34, "mountain"),
        CubeUV("front_apron", 18, 25, 6.20, 3.80, 0.36, "mountain"),
        CubeUV("waist_seam", 33, 25, 7.40, 0.38, 0.29, "webbing"),
        CubeUV("upper_face_layer", 50, 25, 6.10, 2.70, 0.25, "mountain"),
        CubeUV("middle_face_layer", 64, 25, 7.00, 3.50, 0.28, "mountain"),
        CubeUV("upper_seam", 80, 25, 5.80, 0.22, 0.18, "webbing"),
        CubeUV("middle_seam", 93, 25, 6.50, 0.22, 0.18, "webbing"),
        CubeUV("lower_seam", 108, 25, 6.90, 0.24, 0.20, "webbing"),
        CubeUV("left_vertical_seam", 124, 25, 0.24, 7.40, 0.20, "webbing"),
        CubeUV("right_vertical_seam", 126, 25, 0.24, 7.40, 0.20, "webbing"),
        CubeUV("left_side_guard", 0, 34, 0.50, 2.45, 4.36, "dark_side"),
        CubeUV("right_side_guard", 11, 34, 0.50, 2.45, 4.36, "dark_side"),
        CubeUV("apron_tip", 22, 34, 5.90, 0.26, 0.18, "mountain"),
        CubeUV("left_shoulder_tab", 36, 34, 1.10, 1.60, 0.28, "webbing"),
        CubeUV("right_shoulder_tab", 40, 34, 1.10, 1.60, 0.28, "webbing"),
    ),
    "b6b5_flora": (
        CubeUV("front_upper", 0, 0, 6.60, 3.00, 0.40, "flora"),
        CubeUV("rear_upper", 15, 0, 6.60, 3.00, 0.40, "flora"),
        CubeUV("front_middle", 30, 0, 7.50, 4.03, 0.44, "flora"),
        CubeUV("rear_middle", 47, 0, 7.50, 4.03, 0.44, "flora"),
        CubeUV("front_lower", 64, 0, 7.90, 4.23, 0.47, "flora"),
        CubeUV("rear_lower", 82, 0, 7.90, 4.23, 0.47, "flora"),
        CubeUV("left_side", 100, 0, 0.46, 8.14, 3.88, "flora_side"),
        CubeUV("right_side", 110, 0, 0.46, 8.14, 3.88, "flora_side"),
        CubeUV("front_collar_left", 0, 14, 3.80, 1.90, 0.44, "flora_collar"),
        CubeUV("front_collar_right", 10, 14, 3.80, 1.90, 0.44, "flora_collar"),
        CubeUV("rear_collar", 20, 14, 8.80, 1.78, 0.44, "flora_collar"),
        CubeUV("left_collar", 40, 14, 0.44, 1.63, 8.26, "flora_collar"),
        CubeUV("right_collar", 59, 14, 0.44, 1.63, 8.26, "flora_collar"),
        CubeUV("front_yoke_left", 78, 14, 2.80, 0.85, 2.21, "webbing"),
        CubeUV("front_yoke_right", 90, 14, 2.80, 0.85, 2.21, "webbing"),
        CubeUV("rear_yoke_left", 102, 14, 2.80, 0.85, 2.21, "webbing"),
        CubeUV("rear_yoke_right", 114, 14, 2.80, 0.85, 2.21, "webbing"),
        CubeUV("left_strap", 0, 25, 0.72, 4.65, 0.32, "webbing"),
        CubeUV("right_strap", 4, 25, 0.72, 4.65, 0.32, "webbing"),
        CubeUV("pouch_far_left", 8, 25, 1.65, 4.65, 0.92, "pouch"),
        CubeUV("pouch_left", 15, 25, 1.65, 4.65, 0.92, "pouch"),
        CubeUV("pouch_right", 22, 25, 1.65, 4.65, 0.92, "pouch"),
        CubeUV("pouch_far_right", 29, 25, 1.65, 4.65, 0.92, "pouch"),
        CubeUV("front_extension", 36, 25, 6.50, 5.10, 0.40, "flora"),
        CubeUV("front_tip", 51, 25, 4.00, 1.30, 0.34, "flora"),
        CubeUV("pouch_far_left_lid", 61, 25, 1.57, 0.85, 1.06, "pouch"),
        CubeUV("pouch_left_lid", 68, 25, 1.57, 0.85, 1.06, "pouch"),
        CubeUV("pouch_right_lid", 75, 25, 1.57, 0.85, 1.06, "pouch"),
        CubeUV("pouch_far_right_lid", 82, 25, 1.57, 0.85, 1.06, "pouch"),
        CubeUV("left_side_skirt", 89, 25, 0.50, 3.05, 4.40, "flora_side"),
        CubeUV("right_side_skirt", 100, 25, 0.50, 3.05, 4.40, "flora_side"),
        CubeUV("chest_seam", 111, 25, 5.88, 0.24, 0.26, "webbing"),
        CubeUV("waist_seam", 0, 34, 7.20, 0.28, 0.28, "webbing"),
        CubeUV("center_seam", 16, 34, 0.24, 3.00, 0.26, "webbing"),
        CubeUV("pouch_far_left_pull", 18, 34, 0.20, 2.20, 0.16, "webbing"),
        CubeUV("pouch_left_pull", 20, 34, 0.20, 2.20, 0.16, "webbing"),
        CubeUV("pouch_right_pull", 22, 34, 0.20, 2.20, 0.16, "webbing"),
        CubeUV("pouch_far_right_pull", 24, 34, 0.20, 2.20, 0.16, "webbing"),
        CubeUV("front_collar_outer_left", 26, 34, 3.80, 0.22, 0.52, "flora_collar"),
        CubeUV("front_collar_outer_right", 36, 34, 3.80, 0.22, 0.52, "flora_collar"),
        CubeUV("rear_collar_outer", 46, 34, 8.76, 0.22, 0.52, "flora_collar"),
        CubeUV("left_collar_outer", 66, 34, 0.52, 0.22, 8.32, "flora_collar"),
        CubeUV("right_collar_outer", 85, 34, 0.52, 0.22, 8.32, "flora_collar"),
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
        ("upper_face_layer", "north", "soft_panel"),
        ("middle_face_layer", "north", "soft_panel"),
        ("upper_seam", "north", "webbing"),
        ("middle_seam", "north", "webbing"),
        ("lower_seam", "north", "webbing"),
        ("left_vertical_seam", "north", "webbing"),
        ("right_vertical_seam", "north", "webbing"),
        ("left_side_guard", "west", "channels"),
        ("right_side_guard", "east", "channels"),
        ("apron_tip", "north", "fold"),
        ("left_shoulder_tab", "north", "strap"),
        ("right_shoulder_tab", "north", "strap"),
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
        ("pouch_far_left_lid", "north", "pouch"),
        ("pouch_left_lid", "north", "pouch"),
        ("pouch_right_lid", "north", "pouch"),
        ("pouch_far_right_lid", "north", "pouch"),
        ("left_side_skirt", "west", "channels"),
        ("right_side_skirt", "east", "channels"),
        ("chest_seam", "north", "webbing"),
        ("waist_seam", "north", "webbing"),
        ("center_seam", "north", "webbing"),
        ("pouch_far_left_pull", "north", "strap"),
        ("pouch_left_pull", "north", "strap"),
        ("pouch_right_pull", "north", "strap"),
        ("pouch_far_right_pull", "north", "strap"),
        ("front_collar_outer_left", "north", "collar"),
        ("front_collar_outer_right", "north", "collar"),
        ("rear_collar_outer", "south", "collar"),
        ("left_collar_outer", "west", "collar"),
        ("right_collar_outer", "east", "collar"),
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
        "background": ((31, 39, 31, 255),),
        "palette": {
            "mountain": (
                (112, 119, 84, 255),
                (132, 136, 98, 255),
                (119, 107, 74, 255),
                (100, 92, 64, 255),
                (82, 91, 60, 255),
                (60, 69, 48, 255),
            ),
            "dark_side": ((26, 32, 27, 255), (38, 47, 37, 255), (55, 61, 45, 255)),
            "collar": ((73, 85, 62, 255), (102, 105, 73, 255), (59, 68, 50, 255)),
            "webbing": ((51, 61, 44, 255), (73, 79, 54, 255), (91, 86, 59, 255)),
            "pouch": ((67, 75, 52, 255), (96, 92, 62, 255), (49, 58, 42, 255)),
        },
        "edge": (43, 44, 34, 255),
        "stitch": (164, 167, 124, 255),
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


EXPECTED_CUBES = {"b6b23_mountain": 32, "b6b5_flora": 43, "osprey_assault": 37}

JAVA_MODELS = {
    "b6b23_mountain": "B6B23MountainFloraArmorModel.java",
    "b6b5_flora": "B6B5FloraArmorModel.java",
    "osprey_assault": "OspreyMk4AAssaultArmorModel.java",
}


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
    # Two warped scales keep the camouflage organic while retaining visible fabric grain.
    warped_x = x + ((y * 7 + seed * 3) % 7) - 3
    warped_y = y + ((x * 5 + seed) % 5) - 2
    cell_x = warped_x // 4
    cell_y = warped_y // 3
    broad_x = (x + ((y + seed) % 9) - 4) // 9
    broad_y = (y + ((x + seed) % 7) - 3) // 7
    cluster = (
        cell_x * 37
        + cell_y * 53
        + (cell_x // 2) * (cell_y // 2) * 11
        + (cell_x ^ cell_y) * 17
        + broad_x * 23
        + broad_y * 31
        + seed
    )
    palette_index = (cluster + (broad_x ^ broad_y) * 3) % len(colors)
    color = colors[palette_index]
    weave = ((x * 71 + y * 149 + seed * 31 + (x + 3) * (y + 11) * 7) & 0xFF) % 7 - 3
    if (x + 2 * y + seed) % 13 == 0:
        weave += 3
    elif (2 * x + y + seed) % 17 == 0:
        weave -= 2
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
        middle = y0 + height // 2
        draw.line((x0 + 2, middle, x1 - 3, middle), fill=shade(base, -9))
        if height >= 8:
            draw.line((x0 + 2, min(y1 - 2, middle + 2), x1 - 3, min(y1 - 2, middle + 2)), fill=shade(base, 6))
    elif kind == "channels" and width >= 4 and height >= 5:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.line((x, y0 + 1, x, y1 - 2), fill=shade(base, -8))
    elif kind in {"strap", "webbing"} and width >= 2 and height >= 2:
        draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -8))
        if width >= 4:
            draw.line((x1 - 2, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 5))
    elif kind == "pouch" and width >= 3 and height >= 4:
        flap_y = min(y0 + max(2, height // 4), y1 - 2)
        draw.line((x0, flap_y, x1 - 1, flap_y), fill=edge)
        stitch_border(draw, bounds, stitch, edge)
        if width >= 5:
            draw.line((x0 + width // 2, flap_y + 1, x0 + width // 2, y1 - 2), fill=shade(base, -7))
            draw.line((x0 + 1, flap_y + 1, x1 - 2, y1 - 2), fill=shade(base, -5))
            draw.line((x1 - 2, flap_y + 1, x0 + 1, y1 - 2), fill=shade(base, 5))
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
        if height >= 5:
            for y in range(y0 + 2, y1 - 1, 2):
                draw.line((x0 + 2, y, x1 - 3, y), fill=shade(base, -5))
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


def validate_java_uvs() -> None:
    source_root = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
    pattern = re.compile(
        r"texOffs\((\d+),\s*(\d+)\)\s*\.addBox\("
        r"[^,]+,[^,]+,[^,]+,\s*([0-9.]+)F,\s*([0-9.]+)F,\s*([0-9.]+)F\)"
    )
    for model_name, filename in JAVA_MODELS.items():
        source = (source_root / filename).read_text(encoding="utf-8")
        actual = [
            (int(u), int(v), float(width), float(height), float(depth))
            for u, v, width, height, depth in pattern.findall(source)
        ]
        expected = [
            (int(cube.u), int(cube.v), cube.width, cube.height, cube.depth)
            for cube in MODELS[model_name]
        ]
        if sorted(actual) != sorted(expected):
            raise RuntimeError(f"Java UV/cuboid mismatch: {model_name}: {actual} != {expected}")


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
    validate_java_uvs()
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
