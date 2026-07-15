from dataclasses import dataclass, replace
from hashlib import sha256
from math import ceil, floor
from pathlib import Path
import re

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
TEXTURE_ROOT = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"
SIZE = 128
RGBA = tuple[int, int, int, int]


@dataclass(frozen=True)
class Face:
    x0: float
    y0: float
    x1: float
    y1: float


@dataclass(frozen=True)
class Cube:
    name: str
    width: float
    height: float
    depth: float
    material: str
    u: int = 0
    v: int = 0


def cube_faces(cube: Cube) -> dict[str, Face]:
    u, v = cube.u, cube.v
    w, h, d = cube.width, cube.height, cube.depth
    return {
        "down": Face(u + d, v, u + d + w, v + d),
        "up": Face(u + d + w, v, u + d + 2 * w, v + d),
        "west": Face(u, v + d, u + d, v + d + h),
        "north": Face(u + d, v + d, u + d + w, v + d + h),
        "east": Face(u + d + w, v + d, u + 2 * d + w, v + d + h),
        "south": Face(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


def pack(cubes: tuple[Cube, ...]) -> tuple[Cube, ...]:
    """Deterministic shelf packer with a one-pixel gutter between cube nets."""
    packed: list[Cube] = []
    x = y = row_height = 0
    for cube in cubes:
        net_width = ceil(2 * (cube.width + cube.depth))
        net_height = ceil(cube.height + cube.depth)
        if x and x + net_width > SIZE:
            x = 0
            y += row_height + 1
            row_height = 0
        if y + net_height > SIZE:
            raise RuntimeError(f"UV atlas overflow at {cube.name}")
        packed.append(replace(cube, u=x, v=y))
        x += net_width + 1
        row_height = max(row_height, net_height)
    return tuple(packed)


def c(name: str, w: float, h: float, d: float, material: str) -> Cube:
    return Cube(name, w, h, d, material)


RAW_MODELS: dict[str, tuple[Cube, ...]] = {
    "b6b13": (
        c("right_shoulder_top", 4.10, .65, 4.30, "shoulder"),
        c("right_shoulder_outer", .55, 1.30, 4.20, "shoulder"),
        c("left_shoulder_top", 4.10, .65, 4.30, "shoulder"),
        c("left_shoulder_outer", .55, 1.30, 4.20, "shoulder"),
        c("front_yoke", 6.80, 2.80, .36, "fabric"),
        c("front_plate", 7.50, 6.80, .50, "plate"),
        c("front_lower", 7.70, 2.00, .40, "lower"),
        c("rear_yoke", 6.80, 2.80, .40, "fabric"),
        c("rear_plate", 7.50, 8.60, .50, "plate"),
        c("left_side", .44, 8.40, 4.10, "side"),
        c("right_side", .44, 8.40, 4.10, "side"),
        c("front_collar_left", 3.80, 1.65, .42, "collar"),
        c("front_collar_right", 3.80, 1.65, .42, "collar"),
        c("rear_collar", 8.40, 1.65, .42, "collar"),
        c("left_collar", .42, 1.65, 8.10, "collar"),
        c("right_collar", .42, 1.65, 8.10, "collar"),
        c("long_apron", 5.00, 3.20, .40, "lower"),
        c("front_flap", 3.40, 1.30, .22, "webbing"),
        c("front_collar_root_left", 1.65, 1.80, .50, "fabric"),
        c("front_collar_root_right", 1.65, 1.80, .50, "fabric"),
        c("rear_collar_root_left", 1.65, 1.80, .50, "fabric"),
        c("rear_collar_root_right", 1.65, 1.80, .50, "fabric"),
    ),
    "b6b3": (
        c("front_upper", 6.8, 3.7, .40, "fabric"),
        c("front_lower", 7.5, 7.4, .45, "fabric"),
        c("rear_upper", 6.8, 3.7, .40, "fabric"),
        c("rear_lower", 7.5, 7.4, .45, "fabric"),
        c("left_side", .42, 8.8, 4.0, "side"),
        c("right_side", .42, 8.8, 4.0, "side"),
        c("front_strap_left", .85, 3.7, .32, "strap"),
        c("front_strap_right", .85, 3.7, .32, "strap"),
        c("rear_strap_left", .85, 3.7, .32, "strap"),
        c("rear_strap_right", .85, 3.7, .32, "strap"),
        c("center_flap", 3.1, 5.4, .35, "flap"),
        c("front_belt", 7.8, 1.3, .45, "belt"),
        c("rear_belt", 7.8, 1.3, .45, "belt"),
        c("pouch_far_left", 1.45, 3.9, .70, "pouch"),
        c("pouch_left", 1.45, 3.9, .70, "pouch"),
        c("pouch_right", 1.35, 3.9, .70, "pouch"),
        c("radio", 1.1, 4.4, .65, "dark"),
        c("buckle", 1.4, 1.5, .30, "metal"),
        c("side_belt", .28, 2.0, 3.9, "strap"),
    ),
    "ana": (
        c("front_plate", 6.96, 6.45, .48, "plate"),
        c("rear_plate", 6.96, 6.45, .48, "plate"),
        c("left_side", .56, 6.70, 4.16, "side"),
        c("right_side", .56, 6.70, 4.16, "side"),
        c("left_bridge", 1.37, 1.25, 5.08, "strap"),
        c("right_bridge", 1.37, 1.25, 5.08, "strap"),
        c("upper_webbing", 6.56, .32, .28, "webbing"),
        c("waist_front", 8.24, 3.40, .63, "belt"),
        c("waist_rear", 8.16, 3.30, .63, "belt"),
        c("left_side_pouch", 2.45, 4.70, 1.48, "pouch"),
        c("right_side_pouch", 1.55, 4.40, 1.20, "pouch"),
        c("mag_left", 1.35, 4.25, 1.04, "mag"),
        c("mag_right", 1.35, 4.10, 1.00, "mag"),
        c("small_pouch_upper", .90, 1.85, 1.10, "pouch"),
        c("radio", 1.08, 4.70, .76, "dark"),
        c("antenna", .18, 4.55, .20, "dark"),
        c("shoulder_hardware", .50, 3.20, .36, "strap"),
        c("left_pouch_lid", 2.68, 1.15, 1.68, "pouch"),
        c("left_pouch_stitch", 2.10, 2.90, .24, "webbing"),
        c("right_pouch_lid", 1.71, 1.10, 1.40, "pouch"),
        c("right_pouch_stitch", 1.30, 2.55, .22, "webbing"),
        c("mag_left_lip", 1.45, 1.05, 1.24, "mag"),
        c("mag_right_lip", 1.45, 1.02, 1.20, "mag"),
        c("chest_molle", 6.35, .30, .30, "webbing"),
        c("small_pouch_lower", .90, 2.00, 1.12, "pouch"),
        c("small_pouch_upper_lid", .96, .55, 1.25, "pouch"),
        c("small_pouch_lower_lid", .96, .55, 1.27, "pouch"),
    ),
    "a18": (
        c("front_plate", 7.10, 6.60, .52, "plate"),
        c("rear_plate", 7.10, 6.60, .50, "plate"),
        c("left_side", .48, 6.55, 4.16, "side"),
        c("right_side", .48, 6.55, 4.16, "side"),
        c("left_bridge", 1.54, 1.45, 5.12, "strap"),
        c("right_bridge", 1.54, 1.45, 5.12, "strap"),
        c("front_belt", 8.36, 3.30, .64, "belt"),
        c("rear_belt", 8.24, 3.20, .62, "belt"),
        c("left_side_bag", 2.82, 4.70, 1.60, "pouch"),
        c("right_side_bag", 2.87, 4.20, 1.48, "pouch"),
        c("mag_left", 1.77, 4.35, 1.20, "dark"),
        c("mag_right", 1.83, 4.10, 1.15, "dark"),
        c("utility", 1.24, 4.40, 1.04, "utility"),
        c("radio", 1.00, 5.00, .76, "dark"),
        c("chest_webbing", 6.64, .32, .26, "webbing"),
        c("center_buckle", .88, 1.30, 1.30, "metal"),
        c("left_bag_lid", 3.05, 1.14, 1.80, "pouch"),
        c("left_bag_stitch", 2.44, 2.85, .25, "webbing"),
        c("right_bag_lid", 3.09, 1.10, 1.68, "pouch"),
        c("right_bag_stitch", 2.48, 2.53, .24, "webbing"),
        c("mag_left_lip", 1.98, 1.20, 1.42, "dark"),
        c("mag_right_lip", 2.04, 1.07, 1.37, "dark"),
        c("tool_channel", .30, 3.80, .97, "utility"),
        c("tool_lid", .44, .80, 1.11, "utility"),
        c("left_shoulder_hardware", .63, 1.35, .41, "metal"),
        c("right_shoulder_hardware", .75, 1.37, .39, "metal"),
        c("molle_upper", 6.50, .30, .28, "webbing"),
        c("molle_lower", 6.30, .28, .30, "webbing"),
        c("waist_seam", 8.00, .28, .22, "webbing"),
    ),
    "avs": (
        c("front_plate", 6.90, 6.30, .45, "plate"),
        c("rear_plate", 6.90, 6.30, .45, "plate"),
        c("left_side", .44, 6.30, 4.04, "side"),
        c("right_side", .44, 6.30, 4.04, "side"),
        c("left_bridge", 1.30, 1.30, 4.90, "strap"),
        c("right_bridge", 1.30, 1.30, 4.90, "strap"),
        c("front_belt", 7.80, 2.65, .59, "belt"),
        c("rear_belt", 7.80, 2.65, .59, "belt"),
        c("left_zip_pack", 1.90, 5.00, 1.10, "pouch"),
        c("right_zip_pack", 1.90, 5.00, 1.10, "pouch"),
        c("mag_left", 1.40, 4.65, 1.08, "mag"),
        c("mag_mid", 1.40, 4.65, 1.08, "mag"),
        c("mag_right", 1.40, 4.65, 1.08, "mag"),
        c("radio", .88, 4.85, .72, "dark"),
        c("apron_upper", 5.40, 2.05, .36, "groin"),
        c("apron_molle_1", 4.60, .28, .18, "webbing"),
        c("apron_molle_2", 4.60, .28, .18, "webbing"),
        c("apron_molle_3", 4.60, .28, .18, "webbing"),
        c("apron_molle_4", 4.60, .28, .18, "webbing"),
        c("chest_webbing_1", 6.50, .28, .16, "webbing"),
        c("chest_webbing_2", 6.50, .28, .16, "webbing"),
        c("left_pack_lid", 1.86, .85, 1.22, "webbing"),
        c("right_pack_lid", 1.86, .85, 1.22, "webbing"),
        c("left_zipper", .18, 3.82, .18, "dark"),
        c("right_zipper", .18, 3.82, .18, "dark"),
        c("mag_left_lip", 1.36, .55, 1.22, "webbing"),
        c("mag_mid_lip", 1.36, .55, 1.22, "webbing"),
        c("mag_right_lip", 1.36, .55, 1.22, "webbing"),
        c("apron_middle", 4.40, 2.05, .35, "groin"),
        c("apron_tip", 3.40, 2.00, .34, "groin"),
        c("apron_middle_molle_1", 3.70, .28, .18, "webbing"),
        c("apron_middle_molle_2", 3.70, .28, .18, "webbing"),
        c("apron_tip_molle", 2.70, .28, .18, "webbing"),
    ),
    "thor": (
        c("front_upper", 6.6, 4.0, .34, "upper"),
        c("front_lower", 7.6, 6.8, .36, "lower"),
        c("rear_upper", 6.6, 4.0, .34, "upper"),
        c("rear_lower", 7.6, 6.8, .36, "lower"),
        c("left_side", .36, 8.8, 4.0, "side"),
        c("right_side", .36, 8.8, 4.0, "side"),
        c("left_bridge", 1.4, 1.2, 4.7, "shoulder"),
        c("right_bridge", 1.4, 1.2, 4.7, "shoulder"),
        c("waist_front", 7.8, 1.1, .35, "waist"),
        c("waist_rear", 7.8, 1.1, .35, "waist"),
        c("lower_pad_left", 3.4, 2.0, .20, "pad"),
        c("lower_pad_right", 3.4, 2.0, .20, "pad"),
        c("chest_patch", 2.2, .7, .16, "patch"),
    ),
    "stich": (
        c("front_plate", 6.80, 6.10, .45, "plate"),
        c("rear_plate", 6.80, 6.10, .45, "plate"),
        c("left_side", .44, 6.35, 4.04, "side"),
        c("right_side", .44, 6.35, 4.04, "side"),
        c("left_bridge", 1.30, 1.28, 4.88, "strap"),
        c("right_bridge", 1.30, 1.28, 4.88, "strap"),
        c("front_belt", 7.80, 2.70, .59, "belt"),
        c("rear_belt", 7.80, 2.70, .59, "belt"),
        c("mag_left", 1.75, 4.25, 1.05, "mag"),
        c("mag_right", 1.75, 4.25, 1.05, "mag"),
        c("left_medical_pouch", 1.85, 4.90, 1.02, "pouch"),
        c("right_radio_pouch", 1.23, 5.25, .92, "pouch"),
        c("antenna", .16, 4.50, .16, "dark"),
        c("drop_pouch", 5.10, 3.85, .88, "pouch"),
        c("upper_molle", 6.20, .30, .24, "webbing"),
        c("mag_left_lip", 1.69, .58, 1.20, "webbing"),
        c("mag_right_lip", 1.69, .58, 1.20, "webbing"),
        c("left_pouch_lid", 1.81, .85, 1.15, "webbing"),
        c("right_pouch_lid", 1.19, .75, 1.07, "webbing"),
        c("drop_pouch_lid", 4.96, .90, 1.24, "webbing"),
        c("molle_middle", 6.20, .30, .25, "webbing"),
        c("molle_lower", 6.20, .30, .27, "webbing"),
        c("left_pouch_face", 1.35, 2.60, .16, "pouch"),
        c("right_pouch_face", .95, 2.40, .16, "pouch"),
        c("drop_pouch_face", 3.20, 1.80, .16, "pouch"),
    ),
    "tv110": (
        c("front_plate", 6.80, 6.20, .45, "plate"),
        c("rear_plate", 6.80, 6.20, .45, "plate"),
        c("left_side", .44, 6.30, 4.04, "side"),
        c("right_side", .44, 6.30, 4.04, "side"),
        c("left_bridge", 1.35, 1.28, 4.88, "strap"),
        c("right_bridge", 1.35, 1.28, 4.88, "strap"),
        c("front_belt", 7.80, 2.55, .59, "belt"),
        c("rear_belt", 7.80, 2.55, .59, "belt"),
        c("left_square_pouch", 2.10, 4.95, 1.18, "pouch"),
        c("mag_left", 1.70, 4.70, 1.08, "mag"),
        c("mag_right", 1.70, 4.70, 1.08, "mag"),
        c("right_radio_pouch", 1.63, 5.55, .99, "dark"),
        c("upper_molle", 6.30, .30, .24, "webbing"),
        c("left_pouch_lid", 2.04, .95, 1.34, "webbing"),
        c("mag_left_lip", 1.64, .62, 1.23, "webbing"),
        c("mag_right_lip", 1.64, .62, 1.23, "webbing"),
        c("radio_lid", 1.57, .88, 1.14, "dark"),
        c("antenna", .16, 4.55, .16, "dark"),
        c("molle_row_2", 6.30, .30, .26, "webbing"),
        c("molle_row_3", 6.30, .30, .28, "webbing"),
        c("molle_row_4", 6.30, .30, .29, "webbing"),
        c("upper_face", 5.60, 1.40, .20, "plate"),
        c("left_zipper_outer", .18, 3.20, .16, "dark"),
        c("left_zipper_inner", .18, 3.20, .16, "dark"),
        c("radio_face", 1.04, 2.75, .16, "dark"),
        c("left_shoulder_tab", 1.02, .50, .25, "strap"),
        c("right_shoulder_tab", 1.02, .50, .25, "strap"),
        c("center_seam", .24, 4.00, .40, "webbing"),
        c("waist_seam", 6.90, .24, .18, "webbing"),
    ),
}

MODELS = {name: pack(cubes) for name, cubes in RAW_MODELS.items()}

JAVA_MODELS = {
    "b6b13": "B6B13ArmorModel.java",
    "b6b3": "B6B3Tm01MArmorModel.java",
    "ana": "AnaM1ArmorModel.java",
    "a18": "A18SkandaArmorModel.java",
    "avs": "AvsArmorModel.java",
    "thor": "ThorConcealableArmorModel.java",
    "stich": "StichProfiV2ArmorModel.java",
    "tv110": "Tv110ArmorModel.java",
}


THEMES = {
    "plate_armor_6b13_flora_layer_1.png": ("b6b13", {
        "fabric": (91, 111, 83, 255), "plate": (105, 124, 92, 255),
        "side": (61, 76, 58, 255), "collar": (78, 100, 76, 255),
        "lower": (96, 114, 84, 255), "webbing": (99, 105, 72, 255),
        "shoulder": (82, 105, 79, 255)}, {"fabric", "plate", "lower", "shoulder"}),
    "plate_armor_6b3tm_01m_khaki_layer_1.png": ("b6b3", {
        "fabric": (112, 108, 72, 255), "side": (73, 77, 53, 255),
        "strap": (84, 83, 55, 255), "flap": (129, 121, 78, 255),
        "belt": (85, 80, 52, 255), "pouch": (119, 112, 72, 255),
        "dark": (40, 40, 34, 255), "metal": (74, 68, 55, 255)}, set()),
    "plate_armor_ana_m1_olive_layer_1.png": ("ana", {
        "plate": (56, 65, 43, 255), "side": (41, 49, 34, 255),
        "strap": (65, 74, 50, 255), "webbing": (70, 77, 50, 255),
        "belt": (51, 59, 39, 255), "pouch": (58, 66, 43, 255),
        "mag": (73, 78, 52, 255), "dark": (27, 32, 27, 255),
        "accent": (125, 39, 31, 255)}, set()),
    "plate_armor_a18_skanda_multicam_layer_1.png": ("a18", {
        "plate": (126, 117, 82, 255), "side": (100, 92, 64, 255),
        "strap": (130, 121, 85, 255), "belt": (107, 97, 67, 255),
        "pouch": (142, 127, 85, 255), "dark": (31, 33, 31, 255),
        "utility": (82, 82, 63, 255), "webbing": (118, 107, 74, 255),
        "metal": (126, 124, 101, 255)}, {"plate", "side", "strap", "belt", "pouch", "webbing"}),
    "plate_armor_avs_ranger_green_layer_1.png": ("avs", {
        "plate": (68, 78, 54, 255), "side": (51, 61, 44, 255),
        "strap": (73, 83, 58, 255), "belt": (60, 70, 48, 255),
        "pouch": (64, 76, 52, 255), "mag": (52, 59, 52, 255),
        "dark": (31, 37, 34, 255), "groin": (65, 75, 51, 255),
        "webbing": (79, 87, 59, 255)}, set()),
    "plate_armor_avs_multicam_layer_1.png": ("avs", {
        "plate": (122, 114, 76, 255), "side": (92, 88, 62, 255),
        "strap": (132, 121, 83, 255), "belt": (108, 99, 67, 255),
        "pouch": (119, 110, 73, 255), "mag": (57, 62, 57, 255),
        "dark": (31, 35, 32, 255), "groin": (120, 110, 75, 255),
        "webbing": (115, 105, 71, 255)}, {"plate", "side", "strap", "belt", "pouch", "groin", "webbing"}),
    "plate_armor_thor_concealable_layer_1.png": ("thor", {
        "upper": (38, 41, 41, 255), "lower": (32, 36, 36, 255),
        "side": (24, 28, 28, 255), "shoulder": (42, 45, 45, 255),
        "waist": (25, 29, 29, 255), "pad": (35, 38, 38, 255),
        "patch": (24, 26, 26, 255)}, set()),
    "plate_armor_stich_profi_v2_black_layer_1.png": ("stich", {
        "plate": (43, 47, 47, 255), "side": (29, 34, 34, 255),
        "strap": (50, 54, 54, 255), "belt": (38, 42, 42, 255),
        "mag": (57, 50, 54, 255), "pouch": (46, 49, 48, 255),
        "dark": (22, 26, 26, 255), "webbing": (61, 65, 63, 255)}, set()),
    "plate_armor_tv110_coyote_layer_1.png": ("tv110", {
        "plate": (107, 104, 79, 255), "side": (77, 81, 64, 255),
        "strap": (116, 111, 83, 255), "belt": (85, 83, 64, 255),
        "pouch": (116, 109, 78, 255), "mag": (78, 74, 57, 255), "dark": (43, 46, 40, 255),
        "webbing": (96, 92, 70, 255)}, set()),
}


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, value + delta)) for value in color[:3]) + (255,)


def tint(color: RGBA, red: int, green: int, blue: int) -> RGBA:
    offsets = (red, green, blue)
    return tuple(max(0, min(255, value + offsets[index])) for index, value in enumerate(color[:3])) + (255,)


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(char) for index, char in enumerate(value)) % 4093


def bounds(face: Face) -> tuple[int, int, int, int]:
    return max(0, floor(face.x0)), max(0, floor(face.y0)), min(SIZE, ceil(face.x1)), min(SIZE, ceil(face.y1))


def pixel(base: RGBA, x: int, y: int, seed: int, camouflage: bool) -> RGBA:
    fine = ((x * 73 + y * 151 + seed * 37 + (x + 7) * (y + 11) * 9) & 255) % 7 - 3
    if camouflage:
        warped_x = x + ((y * 5 + seed) % 7) - 3
        warped_y = y + ((x * 3 + seed * 2) % 5) - 2
        cell_x, cell_y = warped_x // 4, warped_y // 3
        value = (cell_x * 29 + cell_y * 43 + seed * 17 + (cell_x ^ cell_y) * 11) % 29
        if value in (0, 1, 2, 3):
            color = tint(base, -30, -23, -13)
        elif value in (4, 5, 6):
            color = tint(base, 24, 18, 5)
        elif value in (7, 8, 9):
            color = tint(base, -16, 3, -12)
        elif value in (10, 11):
            color = tint(base, 10, -7, -15)
        else:
            color = base
        fiber = 3 if (x + 2 * y + seed) % 11 == 0 else -2 if (2 * x + y + seed) % 13 == 0 else 0
        return shade(color, fine + fiber)
    warp = 4 if (x + seed) % 9 == 0 else -3 if (y + seed) % 11 == 0 else 0
    diagonal = 2 if (x + y + seed) % 17 == 0 else -2 if (x - y + seed) % 19 == 0 else 0
    return shade(base, fine + warp + diagonal)


def paint(image: Image.Image, face: Face, base: RGBA, seed: int, camouflage: bool) -> tuple[int, int, int, int]:
    region = bounds(face)
    pixels = image.load()
    for y in range(region[1], region[3]):
        for x in range(region[0], region[2]):
            pixels[x, y] = pixel(base, x, y, seed, camouflage)
    return region


def detail(image: Image.Image, region: tuple[int, int, int, int], base: RGBA, kind: str) -> None:
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = region
    w, h = x1 - x0, y1 - y0
    if w >= 3 and h >= 3:
        draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 7))
        draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -10))
        draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 4))
        draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -7))
    if kind in {"plate", "side", "pouch", "belt", "groin", "flap", "mag", "utility", "collar", "shoulder"} and w >= 4 and h >= 4:
        for x in range(x0 + 1, x1 - 1, 3):
            draw.point((x, y0 + 1), fill=shade(base, 12))
            draw.point((x, y1 - 2), fill=shade(base, -13))
        for y in range(y0 + 2, y1 - 2, 3):
            draw.point((x0 + 1, y), fill=shade(base, 9))
            draw.point((x1 - 2, y), fill=shade(base, -10))
    if kind in {"plate", "side", "belt", "groin"} and w >= 6 and h >= 5:
        seam_y = y0 + max(2, h // 2)
        draw.line((x0 + 2, seam_y, x1 - 3, seam_y), fill=shade(base, -8))
        if h >= 8:
            draw.line((x0 + 2, min(y1 - 2, seam_y + 2), x1 - 3, min(y1 - 2, seam_y + 2)), fill=shade(base, 5))
    if kind in {"webbing", "strap"} and h >= 3:
        for y in range(y0 + 1, y1 - 1, 2):
            draw.line((x0 + 1, y, max(x0 + 1, x1 - 2), y), fill=shade(base, -8))
            for x in range(x0 + 3, x1 - 1, 4):
                draw.point((x, y), fill=shade(base, 7))
    if kind in {"pouch", "mag", "utility"} and h >= 4:
        if w >= 3:
            draw.line((x0, y0, x0, y1 - 1), fill=shade(base, -18))
            draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -20))
            draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -17))
        flap_y = min(y0 + max(2, h // 4), y1 - 2)
        draw.line((x0, flap_y, x1 - 1, flap_y), fill=shade(base, -14))
        if w >= 4:
            draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -6))
            draw.line((x1 - 2, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 5))
        if w >= 6:
            center = x0 + w // 2
            draw.line((center, flap_y + 1, center, y1 - 2), fill=shade(base, -9))
            draw.rectangle((center - 1, flap_y, min(x1 - 2, center + 1), min(y1 - 2, flap_y + 2)), outline=shade(base, 10))


def net_bounds(cube: Cube) -> Face:
    faces = tuple(cube_faces(cube).values())
    return Face(min(f.x0 for f in faces), min(f.y0 for f in faces), max(f.x1 for f in faces), max(f.y1 for f in faces))


def overlaps(a: Face, b: Face) -> bool:
    return min(a.x1, b.x1) > max(a.x0, b.x0) and min(a.y1, b.y1) > max(a.y0, b.y0)


def validate() -> None:
    for model, cubes in MODELS.items():
        if len({cube.name for cube in cubes}) != len(cubes):
            raise RuntimeError(f"duplicate cube name: {model}")
        for index, cube in enumerate(cubes):
            for direction, face in cube_faces(cube).items():
                if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                    raise RuntimeError(f"UV overflow: {model}.{cube.name}.{direction}")
            for other in cubes[index + 1:]:
                if overlaps(net_bounds(cube), net_bounds(other)):
                    raise RuntimeError(f"UV overlap: {model}.{cube.name}/{other.name}")


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
            (cube.u, cube.v, cube.width, cube.height, cube.depth)
            for cube in MODELS[model_name]
        ]
        if sorted(actual) != sorted(expected):
            raise RuntimeError(f"Java UV/cuboid mismatch: {model_name}: {actual} != {expected}")


def build(filename: str) -> Image.Image:
    model, palette, camouflage = THEMES[filename]
    background = tuple(max(8, value - 8) for value in next(iter(palette.values()))[:3]) + (255,)
    image = Image.new("RGBA", (SIZE, SIZE), background)
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = pixel(background, x, y, 31, False)
    for cube in MODELS[model]:
        base = palette[cube.material]
        for direction, face in cube_faces(cube).items():
            region = paint(image, face, base, stable_seed(f"{filename}:{cube.name}:{direction}"), cube.material in camouflage)
            if direction in {"north", "south", "west", "east", "up"}:
                detail(image, region, base, cube.material)
    return image


def main() -> None:
    validate()
    validate_java_uvs()
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for filename in THEMES:
        image = build(filename)
        if image.tobytes() != build(filename).tobytes():
            raise RuntimeError(f"nondeterministic texture: {filename}")
        output = TEXTURE_ROOT / filename
        image.save(output, format="PNG", optimize=False)
        with Image.open(output) as written:
            if written.size != (SIZE, SIZE) or written.mode != "RGBA" or written.getextrema()[3] != (255, 255):
                raise RuntimeError(f"invalid texture: {filename}")
            colors = written.getcolors(SIZE * SIZE)
            if colors is None or len(colors) < 24:
                raise RuntimeError(f"texture lost detail: {filename}")
        model_name = THEMES[filename][0]
        print(f"{filename} model={model_name} cuboids={len(MODELS[model_name])} colors={len(colors)} sha256={sha256(output.read_bytes()).hexdigest()}")
    print("uv=nonoverlapping alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
