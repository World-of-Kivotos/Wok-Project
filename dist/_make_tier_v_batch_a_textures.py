from collections import Counter
from dataclasses import dataclass
from hashlib import sha256
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw

from _validate_tier_v_geometry_integrity import load_tier_v_model


ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = (
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
    u: float
    v: float
    width: float
    height: float
    depth: float


@dataclass(frozen=True)
class Material:
    base: RGBA
    kind: str
    palette: tuple[RGBA, ...] = ()


@dataclass(frozen=True)
class TextureSpec:
    name: str
    output_name: str
    background: Material
    cubes: dict[str, CubeUV]
    materials: dict[str, Material]
    instance_counts: dict[str, int]
    expected_cuboids: int


def rgba(red: int, green: int, blue: int) -> RGBA:
    return red, green, blue, 255


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(value: str) -> int:
    return int.from_bytes(sha256(value.encode("utf-8")).digest()[:4], "big")


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
        "east": Face(u + depth + width, v + depth, u + depth * 2 + width, v + depth + height),
        "south": Face(
            u + depth * 2 + width,
            v + depth,
            u + depth * 2 + width * 2,
            v + depth + height,
        ),
    }


def cube_uv_bounds(cube: CubeUV) -> Face:
    return Face(
        cube.u,
        cube.v,
        cube.u + cube.depth * 2 + cube.width * 2,
        cube.v + cube.depth + cube.height,
    )


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def hash_noise(x: int, y: int, seed: int) -> int:
    value = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return (value ^ (value >> 16)) & 0xFF


def camouflage_color(material: Material, x: int, y: int, seed: int) -> RGBA:
    if not material.palette:
        return material.base
    coarse_x = x // 4
    coarse_y = y // 3
    field = hash_noise(coarse_x, coarse_y, seed)
    fine = hash_noise(x // 2, y // 2, seed + 101)
    if material.kind == "atacs":
        index = 0 if field < 48 else 1 if field < 101 else 2 if field < 158 else 3 if field < 216 else 4
        if fine < 18:
            index = min(len(material.palette) - 1, index + 1)
    elif material.kind == "black_camo":
        index = 0 if field < 70 else 1 if field < 145 else 2 if field < 218 else 3
    else:
        index = field * len(material.palette) // 256
    return material.palette[index % len(material.palette)]


def material_pixel(material: Material, x: int, y: int, seed: int) -> RGBA:
    base = camouflage_color(material, x, y, seed)
    noise = hash_noise(x, y, seed) % 9 - 4
    if material.kind in {"nylon", "atacs", "black_camo"}:
        weave = 4 if (x + y + seed) % 7 in (0, 1) else -2 if (x - y + seed) % 9 == 0 else 0
        delta = noise + weave
    elif material.kind == "webbing":
        delta = noise + (-8 if (y + seed) % 3 == 0 else 2)
    elif material.kind == "soft":
        delta = noise // 2 + (4 if (x - y + seed) % 8 in (0, 1) else -1)
    elif material.kind == "mesh":
        delta = -12 if (x + y + seed) % 3 == 0 else noise - 2
    elif material.kind == "plastic":
        delta = noise + (8 if (x + seed) % 7 == 0 else -2)
    elif material.kind == "metal":
        delta = noise * 2 + (12 if (x + 2 * y + seed) % 11 == 0 else -3)
    else:
        delta = noise
    return shade(base, delta)


def paint_face(image: Image.Image, face: Face, material: Material, seed: int) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    x0, y0, x1, y1 = bounds
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = material_pixel(material, x, y, seed)
    return bounds


def draw_edges(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 2 or y1 - y0 < 2:
        return
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 14))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 7))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -18))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -12))


def draw_stitches(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 3:
        return
    for x in range(x0 + 1, x1 - 1, 2):
        draw.point((x, y0 + 1), fill=shade(base, 20))
        draw.point((x, y1 - 2), fill=shade(base, -21))


def draw_panel(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_edges(draw, bounds, base)
    draw_stitches(draw, bounds, base)


def draw_webbing(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_edges(draw, bounds, base)
    x0, y0, x1, y1 = bounds
    if x1 <= x0 or y1 <= y0:
        return
    middle = y0 + max(0, (y1 - y0 - 1) // 2)
    for x in range(x0, x1, 2):
        draw.point((x, middle), fill=shade(base, -28))


def draw_pouch(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_panel(draw, bounds, base)
    x0, y0, x1, y1 = bounds
    if x1 - x0 >= 4 and y1 - y0 >= 4:
        center = x0 + (x1 - x0) // 2
        draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -15))
        flap = y0 + max(1, (y1 - y0) // 3)
        draw.line((x0 + 1, flap, x1 - 2, flap), fill=shade(base, -24))


def draw_magazine(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_edges(draw, bounds, base)
    x0, y0, x1, y1 = bounds
    for y in range(y0 + 1, y1 - 1, 2):
        draw.line((x0, y, x1 - 1, y), fill=shade(base, -18))
    if x1 - x0 >= 3:
        center = x0 + (x1 - x0) // 2
        draw.line((center, y0, center, y1 - 1), fill=shade(base, 9))


def draw_zipper(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    draw_edges(draw, bounds, base)
    for y in range(y0, y1):
        x = x0 if (y - y0) % 2 == 0 else max(x0, x1 - 1)
        draw.point((x, y), fill=rgba(182, 176, 151))


def draw_radio(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_edges(draw, bounds, base)
    x0, y0, x1, y1 = bounds
    if x1 - x0 >= 3:
        draw.line((x0 + 1, y0, x0 + 1, y1 - 1), fill=shade(base, 20))
    if y1 - y0 >= 4:
        draw.line((x0, y0 + 2, x1 - 1, y0 + 2), fill=shade(base, -20))


def primary_directions(cube_name: str) -> tuple[str, ...]:
    if cube_name == "rear_plate" or cube_name == "rear_yoke":
        return ("south",)
    if cube_name in {"side_panel"}:
        return ("west", "east")
    if cube_name in {"bridge", "top_pad"}:
        return ("up",)
    if cube_name in {"left_bag", "left_lid", "left_bevel", "left_panel", "radio_pouch", "radio_lid"}:
        return ("north", "west")
    if cube_name in {"right_pouch", "right_lid", "right_utility", "right_panel", "right_pull"}:
        return ("north", "east")
    return ("north",)


def detail_style(cube_name: str) -> str:
    if cube_name in {
        "carrier", "front_plate", "rear_plate", "side_panel", "front_yoke", "rear_yoke", "top_pad"
    }:
        return "panel"
    if cube_name in {"bridge", "molle", "mag_elastic", "bottom_seam", "right_pull"}:
        return "webbing"
    if cube_name in {"mag_pouch", "mag_insert", "black_mag"}:
        return "magazine"
    if cube_name in {"zipper"}:
        return "zipper"
    if cube_name in {"radio", "antenna", "tool"}:
        return "radio"
    if cube_name == "admin":
        return "patch"
    if cube_name == "buckle" or cube_name == "red_tab":
        return "buckle"
    return "pouch"


def decorate(image: Image.Image, spec: TextureSpec, cube_name: str) -> None:
    material = spec.materials[cube_name]
    style = detail_style(cube_name)
    for direction in primary_directions(cube_name):
        bounds = paint_face(
            image,
            cube_faces(spec.cubes[cube_name])[direction],
            material,
            stable_seed(spec.name + cube_name + direction + "detail"),
        )
        draw = ImageDraw.Draw(image)
        if style == "panel":
            draw_panel(draw, bounds, material.base)
        elif style == "webbing":
            draw_webbing(draw, bounds, material.base)
        elif style == "magazine":
            draw_magazine(draw, bounds, material.base)
        elif style == "zipper":
            draw_zipper(draw, bounds, material.base)
        elif style == "radio":
            draw_radio(draw, bounds, material.base)
        elif style == "patch":
            x0, y0, x1, y1 = bounds
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=shade(material.base, -25),
                           outline=shade(material.base, 18))
        elif style == "buckle":
            x0, y0, x1, y1 = bounds
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=shade(material.base, -12),
                           outline=shade(material.base, 28))
        else:
            draw_pouch(draw, bounds, material.base)


TACTEC_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.10, 8.75, 0.58),
    "rear_plate": CubeUV(42, 0, 7.00, 8.80, 0.56),
    "side_panel": CubeUV(58, 0, 0.40, 6.65, 4.10),
    "front_yoke": CubeUV(68, 0, 1.45, 4.05, 0.55),
    "rear_yoke": CubeUV(73, 0, 1.45, 4.05, 0.55),
    "bridge": CubeUV(78, 0, 1.29, 0.55, 4.50),
    "admin": CubeUV(91, 0, 5.80, 1.10, 0.22),
    "molle": CubeUV(105, 0, 6.30, 0.18, 0.20),
    "buckle": CubeUV(119, 0, 0.55, 0.65, 0.22),
    "mag_pouch": CubeUV(0, 20, 1.05, 3.50, 0.72),
    "mag_insert": CubeUV(6, 20, 0.86, 1.50, 0.42),
    "mag_elastic": CubeUV(11, 20, 1.11, 0.42, 0.20),
    "left_bag": CubeUV(16, 20, 2.85, 5.45, 1.75),
    "left_lid": CubeUV(26, 20, 3.10, 1.12, 1.90),
    "left_panel": CubeUV(37, 20, 2.40, 3.85, 0.30),
    "zipper": CubeUV(43, 20, 0.22, 4.45, 0.18),
    "right_pouch": CubeUV(45, 20, 1.02, 4.35, 1.45),
    "right_lid": CubeUV(51, 20, 1.14, 1.00, 1.60),
    "bottom_pouch": CubeUV(60, 20, 5.00, 4.35, 0.90),
    "bottom_lid": CubeUV(73, 20, 5.20, 1.05, 0.32),
    "bottom_seam": CubeUV(85, 20, 0.22, 3.15, 0.15),
    "left_bevel": CubeUV(0, 40, 2.50, 0.72, 1.55),
}

TACTEC_GREEN = tuple(map(lambda color: rgba(*color), (
    (46, 58, 42), (59, 72, 49), (70, 81, 55), (82, 91, 64), (99, 104, 73)
)))
TACTEC_MATERIALS = {
    name: Material(rgba(62, 73, 52), "nylon", TACTEC_GREEN)
    for name in TACTEC_CUBES
}
TACTEC_MATERIALS.update({
    "carrier": Material(rgba(45, 54, 43), "mesh"),
    "rear_plate": Material(rgba(46, 57, 43), "nylon", TACTEC_GREEN),
    "side_panel": Material(rgba(40, 48, 39), "webbing"),
    "admin": Material(rgba(34, 40, 33), "webbing"),
    "molle": Material(rgba(52, 62, 46), "webbing"),
    "buckle": Material(rgba(68, 72, 65), "plastic"),
    "mag_pouch": Material(rgba(60, 70, 50), "nylon", TACTEC_GREEN),
    "mag_insert": Material(rgba(28, 34, 31), "plastic"),
    "mag_elastic": Material(rgba(38, 47, 35), "webbing"),
    "left_bag": Material(rgba(65, 78, 55), "nylon", TACTEC_GREEN),
    "left_lid": Material(rgba(74, 84, 61), "nylon", TACTEC_GREEN),
    "left_panel": Material(rgba(57, 68, 49), "nylon", TACTEC_GREEN),
    "left_bevel": Material(rgba(58, 71, 50), "soft", TACTEC_GREEN),
    "zipper": Material(rgba(126, 120, 96), "metal"),
    "right_pouch": Material(rgba(48, 57, 44), "nylon", TACTEC_GREEN),
    "right_lid": Material(rgba(68, 77, 57), "nylon", TACTEC_GREEN),
    "bottom_pouch": Material(rgba(83, 80, 59), "soft"),
    "bottom_lid": Material(rgba(96, 91, 67), "soft"),
    "bottom_seam": Material(rgba(51, 52, 41), "webbing"),
})


CPC_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.00, 8.70, 0.55),
    "rear_plate": CubeUV(42, 0, 7.00, 8.65, 0.55),
    "side_panel": CubeUV(58, 0, 0.38, 6.35, 4.00),
    "front_yoke": CubeUV(68, 0, 1.75, 3.55, 0.72),
    "rear_yoke": CubeUV(74, 0, 1.75, 3.55, 0.72),
    "top_pad": CubeUV(80, 0, 1.55, 0.78, 4.60),
    "admin": CubeUV(94, 0, 5.40, 1.10, 0.24),
    "molle": CubeUV(106, 0, 6.10, 0.18, 0.30),
    "buckle": CubeUV(120, 0, 0.65, 0.70, 0.25),
    "left_bag": CubeUV(0, 40, 3.10, 4.75, 1.82),
    "left_lid": CubeUV(25, 40, 3.38, 1.25, 2.00),
    "left_bevel": CubeUV(50, 40, 2.60, 1.05, 1.60),
    "left_panel": CubeUV(75, 40, 2.70, 3.35, 0.30),
    "right_utility": CubeUV(33, 20, 1.55, 4.30, 1.30),
    "right_lid": CubeUV(40, 20, 1.75, 1.02, 1.45),
    "black_mag": CubeUV(48, 20, 1.08, 3.72, 0.56),
    "mag_lip": CubeUV(53, 20, 1.16, 0.60, 0.22),
    "lower_pouch": CubeUV(57, 20, 1.50, 2.10, 0.82),
    "lower_flap": CubeUV(63, 20, 1.60, 0.75, 0.24),
    "tool": CubeUV(68, 20, 0.45, 2.70, 0.55),
    "red_tab": CubeUV(71, 20, 0.55, 0.55, 0.25),
}

ATACS_FG = tuple(map(lambda color: rgba(*color), (
    (45, 58, 43), (67, 83, 56), (91, 90, 62), (116, 104, 74), (72, 58, 44)
)))
CPC_MATERIALS = {
    name: Material(rgba(80, 86, 61), "atacs", ATACS_FG)
    for name in CPC_CUBES
}
CPC_MATERIALS.update({
    "carrier": Material(rgba(42, 49, 40), "mesh"),
    "side_panel": Material(rgba(51, 61, 47), "webbing"),
    "front_yoke": Material(rgba(100, 103, 75), "atacs", ATACS_FG),
    "rear_yoke": Material(rgba(87, 91, 65), "atacs", ATACS_FG),
    "top_pad": Material(rgba(111, 109, 80), "soft"),
    "admin": Material(rgba(47, 53, 43), "webbing"),
    "molle": Material(rgba(64, 71, 52), "webbing"),
    "buckle": Material(rgba(68, 72, 65), "plastic"),
    "left_bag": Material(rgba(75, 83, 60), "atacs", ATACS_FG),
    "left_lid": Material(rgba(91, 96, 69), "atacs", ATACS_FG),
    "left_bevel": Material(rgba(62, 72, 52), "atacs", ATACS_FG),
    "left_panel": Material(rgba(69, 77, 56), "atacs", ATACS_FG),
    "right_utility": Material(rgba(66, 74, 54), "atacs", ATACS_FG),
    "right_lid": Material(rgba(83, 89, 64), "atacs", ATACS_FG),
    "black_mag": Material(rgba(24, 27, 27), "plastic"),
    "mag_lip": Material(rgba(38, 42, 37), "webbing"),
    "lower_pouch": Material(rgba(73, 81, 58), "atacs", ATACS_FG),
    "lower_flap": Material(rgba(92, 96, 68), "atacs", ATACS_FG),
    "tool": Material(rgba(40, 44, 41), "plastic"),
    "red_tab": Material(rgba(137, 41, 32), "nylon"),
})


FCPC_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 5.80, 7.55, 0.52),
    "rear_plate": CubeUV(41, 0, 5.90, 7.75, 0.52),
    "side_panel": CubeUV(57, 0, 0.30, 4.20, 4.00),
    "front_yoke": CubeUV(67, 0, 1.35, 3.70, 0.52),
    "rear_yoke": CubeUV(72, 0, 1.35, 3.70, 0.52),
    "bridge": CubeUV(77, 0, 1.19, 0.55, 4.44),
    "admin": CubeUV(89, 0, 5.30, 1.05, 0.22),
    "molle": CubeUV(101, 0, 5.90, 0.18, 0.20),
    "buckle": CubeUV(114, 0, 0.58, 0.65, 0.22),
    "radio_pouch": CubeUV(0, 20, 2.10, 4.55, 1.47),
    "radio": CubeUV(8, 20, 1.46, 4.65, 0.78),
    "antenna": CubeUV(13, 20, 0.22, 4.15, 0.22),
    "radio_lid": CubeUV(16, 20, 1.98, 1.02, 1.48),
    "mag_pouch": CubeUV(23, 20, 1.22, 3.68, 0.66),
    "mag_insert": CubeUV(29, 20, 1.06, 1.35, 0.42),
    "mag_elastic": CubeUV(34, 20, 1.28, 0.42, 0.20),
    "right_utility": CubeUV(45, 20, 1.72, 3.25, 1.16),
    "right_lid": CubeUV(51, 20, 1.92, 0.95, 1.30),
    "bottom_pouch": CubeUV(58, 20, 3.80, 2.65, 1.24),
    "bottom_lid": CubeUV(72, 20, 4.00, 0.95, 1.30),
    "bottom_seam": CubeUV(86, 20, 0.24, 1.55, 0.22),
    "right_panel": CubeUV(0, 40, 1.40, 2.00, 0.32),
    "right_pull": CubeUV(25, 40, 0.20, 1.55, 0.50),
}

FCPC_COYOTE = tuple(map(lambda color: rgba(*color), (
    (119, 91, 61), (139, 106, 72), (155, 122, 83), (171, 138, 96), (104, 80, 56)
)))
FCPC_MATERIALS = {
    name: Material(rgba(145, 112, 76), "nylon", FCPC_COYOTE)
    for name in FCPC_CUBES
}
FCPC_MATERIALS.update({
    "carrier": Material(rgba(98, 76, 55), "mesh"),
    "rear_plate": Material(rgba(128, 98, 68), "nylon", FCPC_COYOTE),
    "side_panel": Material(rgba(109, 84, 60), "webbing"),
    "front_yoke": Material(rgba(163, 128, 88), "nylon", FCPC_COYOTE),
    "rear_yoke": Material(rgba(139, 106, 73), "nylon", FCPC_COYOTE),
    "bridge": Material(rgba(151, 116, 80), "soft"),
    "admin": Material(rgba(91, 71, 54), "webbing"),
    "molle": Material(rgba(122, 93, 65), "webbing"),
    "buckle": Material(rgba(65, 62, 56), "plastic"),
    "radio_pouch": Material(rgba(73, 85, 60), "nylon"),
    "radio": Material(rgba(45, 59, 47), "plastic"),
    "antenna": Material(rgba(25, 28, 27), "plastic"),
    "radio_lid": Material(rgba(89, 99, 68), "webbing"),
    "mag_pouch": Material(rgba(128, 98, 68), "nylon", FCPC_COYOTE),
    "mag_insert": Material(rgba(29, 32, 30), "plastic"),
    "mag_elastic": Material(rgba(82, 67, 51), "webbing"),
    "right_utility": Material(rgba(139, 106, 72), "nylon", FCPC_COYOTE),
    "right_lid": Material(rgba(158, 123, 84), "nylon", FCPC_COYOTE),
    "right_panel": Material(rgba(130, 99, 68), "nylon", FCPC_COYOTE),
    "right_pull": Material(rgba(88, 69, 52), "webbing"),
    "bottom_pouch": Material(rgba(145, 111, 76), "soft"),
    "bottom_lid": Material(rgba(161, 125, 86), "nylon", FCPC_COYOTE),
    "bottom_seam": Material(rgba(101, 78, 57), "webbing"),
})


SPECS = (
    TextureSpec(
        "TacTec Ranger Green",
        "plate_armor_tactec_ranger_green_layer_1.png",
        Material(rgba(37, 44, 36), "nylon"),
        TACTEC_CUBES,
        TACTEC_MATERIALS,
        {
            "side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "bridge": 2,
            "molle": 4, "buckle": 2, "mag_pouch": 5, "mag_insert": 5,
            "mag_elastic": 5, "right_pouch": 2, "right_lid": 2, "bottom_seam": 2,
        },
        46,
    ),
    TextureSpec(
        "CPC MOD.1 A-TACS FG",
        "plate_armor_cpc_mod1_atacs_fg_layer_1.png",
        Material(rgba(54, 62, 47), "atacs", ATACS_FG),
        CPC_CUBES,
        CPC_MATERIALS,
        {
            "side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "top_pad": 2,
            "molle": 4, "buckle": 2, "black_mag": 3, "mag_lip": 3,
            "lower_pouch": 2, "lower_flap": 2,
        },
        36,
    ),
    TextureSpec(
        "FCPC V5",
        "plate_armor_fcpc_v5_layer_1.png",
        Material(rgba(98, 76, 55), "nylon"),
        FCPC_CUBES,
        FCPC_MATERIALS,
        {
            "side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "bridge": 2,
            "molle": 4, "buckle": 2, "mag_pouch": 4, "mag_insert": 4,
            "mag_elastic": 4, "bottom_seam": 2,
        },
        42,
    ),
)


MODEL_NAMES = {
    "TacTec Ranger Green": "TactecArmorModel",
    "CPC MOD.1 A-TACS FG": "CpcMod1ArmorModel",
    "FCPC V5": "FcpcV5ArmorModel",
}


def rectangles_overlap(first: Face, second: Face) -> bool:
    return first.x0 < second.x1 and second.x0 < first.x1 and first.y0 < second.y1 and second.y0 < first.y1


def validate_uvs(spec: TextureSpec) -> None:
    if set(spec.cubes) != set(spec.materials):
        raise RuntimeError(f"{spec.name}: cube/material definitions differ")
    regions = {name: cube_uv_bounds(cube) for name, cube in spec.cubes.items()}
    for cube_name, region in regions.items():
        if not (0.0 <= region.x0 <= region.x1 <= SIZE and 0.0 <= region.y0 <= region.y1 <= SIZE):
            raise RuntimeError(f"{spec.name}: UV overflow {cube_name}={region}")
    names = list(regions)
    for index, first_name in enumerate(names):
        for second_name in names[index + 1:]:
            if rectangles_overlap(regions[first_name], regions[second_name]):
                raise RuntimeError(f"{spec.name}: UV overlap {first_name}/{second_name}")


def runtime_cube_count(spec: TextureSpec) -> int:
    return sum(spec.instance_counts.get(cube_name, 1) for cube_name in spec.cubes)


def validate_model_uv_contract(spec: TextureSpec) -> None:
    model = load_tier_v_model(MODEL_NAMES[spec.name])
    boxes = model.world_boxes
    origin_to_name: dict[tuple[int, int], str] = {}
    for cube_name, cube in spec.cubes.items():
        origin = (int(cube.u), int(cube.v))
        if origin in origin_to_name:
            raise RuntimeError(f"{spec.name}: duplicate semantic UV origin {origin}")
        origin_to_name[origin] = cube_name

    box_origins = {(box.u, box.v) for box in boxes}
    expected_origins = set(origin_to_name)
    if box_origins != expected_origins:
        missing = sorted(expected_origins - box_origins)
        unexpected = sorted(box_origins - expected_origins)
        raise RuntimeError(
            f"{spec.name}: Java/generator UV origins differ; missing={missing} unexpected={unexpected}"
        )

    observed = Counter((box.u, box.v) for box in boxes)
    for origin, cube_name in origin_to_name.items():
        expected = spec.instance_counts.get(cube_name, 1)
        if observed[origin] != expected:
            raise RuntimeError(
                f"{spec.name}: {cube_name} expected {expected} Java cuboids, got {observed[origin]}"
            )

    for box in boxes:
        cube_name = origin_to_name[(box.u, box.v)]
        cube = spec.cubes[cube_name]
        if box.width > cube.width or box.height > cube.height or box.depth > cube.depth:
            raise RuntimeError(
                f"{spec.name}: Java {cube_name} dimensions "
                f"({box.width}, {box.height}, {box.depth}) exceed generator "
                f"({cube.width}, {cube.height}, {cube.depth})"
            )

    if len(boxes) != spec.expected_cuboids:
        raise RuntimeError(
            f"{spec.name}: expected {spec.expected_cuboids} Java cuboids, got {len(boxes)}"
        )


def build_texture(spec: TextureSpec) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    background_seed = stable_seed(spec.name + "background")
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel(spec.background, x, y, background_seed)
    for cube_name, cube in spec.cubes.items():
        material = spec.materials[cube_name]
        for direction, face in cube_faces(cube).items():
            paint_face(image, face, material, stable_seed(spec.name + cube_name + direction))
    for cube_name in spec.cubes:
        decorate(image, spec, cube_name)
    return image


def write_texture(spec: TextureSpec) -> None:
    validate_uvs(spec)
    validate_model_uv_contract(spec)
    cuboids = runtime_cube_count(spec)
    if cuboids != spec.expected_cuboids:
        raise RuntimeError(f"{spec.name}: expected {spec.expected_cuboids} cuboids, got {cuboids}")
    image = build_texture(spec)
    if image.tobytes() != build_texture(spec).tobytes():
        raise RuntimeError(f"{spec.name}: texture generation is not deterministic")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output = OUTPUT_DIR / spec.output_name
    image.save(output, format="PNG", optimize=False)
    with Image.open(output) as written:
        if written.mode != "RGBA" or written.size != (SIZE, SIZE):
            raise RuntimeError(f"{spec.name}: output must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError(f"{spec.name}: texture alpha must be fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 60:
            raise RuntimeError(f"{spec.name}: insufficient surface detail")
    digest = sha256(output.read_bytes()).hexdigest().upper()
    print(f"{output.name} cuboids={cuboids} colors={len(colors)} sha256={digest} uv=unique alpha=255")


def main() -> None:
    for spec in SPECS:
        write_texture(spec)


if __name__ == "__main__":
    main()
