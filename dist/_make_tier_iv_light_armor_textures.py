from dataclasses import dataclass
from hashlib import sha256
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw


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
class ModelSpec:
    name: str
    output_name: str
    background: Material
    cubes: dict[str, CubeUV]
    materials: dict[str, Material]
    detail_faces: tuple[tuple[str, str, str], ...]
    instance_counts: dict[str, int]


def rgba(red: int, green: int, blue: int) -> RGBA:
    return red, green, blue, 255


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


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(value)) % 4093


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def camouflage_color(material: Material, x: int, y: int, seed: int) -> RGBA:
    if not material.palette:
        return material.base
    coarse_x = x // 3
    coarse_y = y // 3
    field = (
        coarse_x * 37
        + coarse_y * 61
        + (coarse_x + coarse_y) * 17
        + ((x + y + seed) // 7) * 29
        + seed * 11
    ) % 101
    if material.kind == "black_camo":
        index = 0 if field < 36 else 1 if field < 64 else 2 if field < 86 else 3
    elif material.kind == "atacs":
        index = 0 if field < 25 else 1 if field < 48 else 2 if field < 70 else 3 if field < 88 else 4
    else:
        index = 0 if field < 28 else 1 if field < 52 else 2 if field < 74 else 3 if field < 90 else 4
    return material.palette[index % len(material.palette)]


def material_pixel(material: Material, x: int, y: int, seed: int) -> RGBA:
    base = camouflage_color(material, x, y, seed)
    value = (x * 71 + y * 149 + seed * 197 + (x + 13) * (y + 5) * 19) & 0xFF
    noise = value % 7 - 3
    if material.kind in ("multicam", "atacs", "black_camo"):
        delta = noise + (2 if (x + 2 * y + seed) % 11 == 0 else 0)
    elif material.kind == "nylon":
        delta = noise + (4 if (x + 2 * y + seed) % 9 in (0, 1) else 0)
    elif material.kind == "soft":
        delta = noise // 2 + (3 if (x - y + seed) % 7 in (0, 1) else -1)
    elif material.kind == "webbing":
        delta = noise + (-5 if (y + seed) % 3 == 0 else 1)
    elif material.kind == "mesh":
        delta = -8 if (x + y + seed) % 3 == 0 else noise - 2
    elif material.kind == "velcro":
        delta = noise * 2 - (4 if (x + y + seed) % 5 == 0 else 0)
    elif material.kind == "metal":
        delta = noise * 2 + (8 if (x + 2 * y + seed) % 11 == 0 else -2)
    elif material.kind == "plastic":
        delta = noise + (6 if (x + seed) % 9 == 0 else -1)
    else:
        delta = noise
    return shade(base, delta)


def paint_base_face(
    image: Image.Image,
    face: Face,
    material: Material,
    seed: int,
) -> tuple[int, int, int, int]:
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
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 10))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 5))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -15))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -10))


def draw_stitches(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 3:
        return
    for x in range(x0 + 1, x1 - 1, 2):
        draw.point((x, y0 + 1), fill=shade(base, 18))
        draw.point((x, y1 - 2), fill=shade(base, -18))


def draw_webbing(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    draw_edges(draw, bounds, base)
    if x1 <= x0 or y1 <= y0:
        return
    middle = min(y1 - 1, y0 + max(0, (y1 - y0) // 2))
    for x in range(x0, x1, 2):
        draw.point((x, middle), fill=shade(base, -24))


def draw_pouch(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    draw_edges(draw, bounds, base)
    draw_stitches(draw, bounds, base)
    x0, y0, x1, y1 = bounds
    if x1 - x0 >= 3 and y1 - y0 >= 4:
        center = x0 + (x1 - x0) // 2
        draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -13))


def paint_detail(image: Image.Image, spec: ModelSpec, cube_name: str, direction: str, style: str) -> None:
    material = spec.materials[cube_name]
    bounds = paint_base_face(
        image,
        cube_faces(spec.cubes[cube_name])[direction],
        material,
        stable_seed(spec.name + cube_name + direction),
    )
    draw = ImageDraw.Draw(image)
    base = material.base
    if style == "panel":
        draw_edges(draw, bounds, base)
        draw_stitches(draw, bounds, base)
    elif style == "webbing":
        draw_webbing(draw, bounds, base)
    elif style == "pouch":
        draw_pouch(draw, bounds, base)
    elif style == "patch":
        x0, y0, x1, y1 = bounds
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=rgba(24, 27, 25), outline=shade(base, 12))
        if x1 - x0 >= 5 and y1 - y0 >= 2:
            for x in range(x0 + 1, x1 - 1, 2):
                draw.line((x, y0, x, y1 - 1), fill=rgba(206, 207, 194))
    elif style == "tube":
        draw_edges(draw, bounds, base)
        x0, y0, x1, _ = bounds
        draw.line((x0, y0, x1 - 1, y0), fill=rgba(150, 125, 71))
    elif style == "magazine":
        draw_edges(draw, bounds, base)
        x0, y0, x1, _ = bounds
        draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 15))
    elif style == "mesh":
        x0, y0, x1, y1 = bounds
        for y in range(y0, y1):
            for x in range(x0 + ((y + stable_seed(spec.name)) & 1), x1, 2):
                draw.point((x, y), fill=shade(base, -18))
    elif style == "buckle":
        x0, y0, x1, y1 = bounds
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=shade(base, 25), fill=shade(base, -8))
    elif style == "radio":
        draw_edges(draw, bounds, base)
        x0, y0, x1, y1 = bounds
        if x1 - x0 >= 2:
            draw.line((x0 + 1, y0, x0 + 1, y1 - 1), fill=shade(base, 16))
    elif style == "tourniquet":
        draw_edges(draw, bounds, base)
        x0, y0, x1, y1 = bounds
        draw.line((x0, y0, x1 - 1, y1 - 1), fill=shade(base, -24))


MMAC_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.50, 9.60, 0.58),
    "rear_plate": CubeUV(50, 0, 7.40, 9.30, 0.58),
    "side_wrap": CubeUV(75, 0, 0.48, 6.20, 4.40),
    "front_yoke": CubeUV(100, 0, 1.35, 4.15, 0.48),
    "rear_yoke": CubeUV(0, 22, 1.35, 4.15, 0.48),
    "top_bridge": CubeUV(25, 22, 1.29, 0.55, 4.56),
    "admin": CubeUV(50, 22, 5.60, 1.40, 0.30),
    "molle": CubeUV(75, 22, 6.50, 0.20, 0.24),
    "mag_pouch": CubeUV(100, 22, 1.55, 4.40, 0.46),
    "mag_lid": CubeUV(0, 44, 1.61, 1.04, 0.22),
    "side_pouch": CubeUV(25, 44, 0.55, 3.80, 3.40),
    "radio": CubeUV(50, 44, 0.40, 3.0, 2.0),
    "belt": CubeUV(75, 44, 7.64, 0.90, 0.34),
    "dangling": CubeUV(100, 44, 0.32, 2.75, 0.25),
}

MMAC_MATERIALS = {
    "carrier": Material(rgba(50, 58, 51), "nylon"),
    "front_plate": Material(rgba(67, 74, 65), "nylon"),
    "rear_plate": Material(rgba(46, 54, 48), "nylon"),
    "side_wrap": Material(rgba(43, 50, 45), "webbing"),
    "front_yoke": Material(rgba(80, 84, 75), "nylon"),
    "rear_yoke": Material(rgba(69, 74, 66), "nylon"),
    "top_bridge": Material(rgba(91, 94, 84), "nylon"),
    "admin": Material(rgba(39, 45, 41), "velcro"),
    "molle": Material(rgba(53, 60, 54), "webbing"),
    "mag_pouch": Material(rgba(57, 63, 56), "nylon"),
    "mag_lid": Material(rgba(69, 74, 65), "webbing"),
    "side_pouch": Material(rgba(44, 51, 46), "nylon"),
    "radio": Material(rgba(30, 35, 33), "plastic"),
    "belt": Material(rgba(48, 55, 49), "webbing"),
    "dangling": Material(rgba(42, 48, 43), "webbing"),
}

RBAV_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.70, 8.95, 0.70),
    "rear_plate": CubeUV(50, 0, 7.70, 9.20, 0.66),
    "side_wrap": CubeUV(75, 0, 0.62, 7.70, 4.64),
    "front_yoke": CubeUV(100, 0, 1.55, 4.30, 0.62),
    "rear_yoke": CubeUV(0, 22, 1.55, 4.30, 0.62),
    "top_bridge": CubeUV(25, 22, 1.49, 0.64, 4.64),
    "tube": CubeUV(50, 22, 0.48, 0.66, 0.30),
    "mag_pouch": CubeUV(75, 22, 1.55, 4.20, 0.48),
    "mag_lid": CubeUV(100, 22, 1.61, 1.08, 0.24),
    "medical": CubeUV(0, 44, 0.68, 4.50, 3.40),
    "utility": CubeUV(25, 44, 0.68, 4.10, 3.16),
    "side_rail": CubeUV(50, 44, 0.20, 0.28, 4.20),
    "drop": CubeUV(75, 44, 5.20, 4.10, 0.52),
    "belt": CubeUV(100, 44, 7.72, 1.05, 0.42),
    "buckle": CubeUV(0, 66, 0.62, 0.72, 0.20),
}

RBAV_MATERIALS = {
    "carrier": Material(rgba(48, 53, 49), "nylon"),
    "front_plate": Material(rgba(61, 66, 61), "nylon"),
    "rear_plate": Material(rgba(43, 49, 45), "nylon"),
    "side_wrap": Material(rgba(37, 44, 40), "nylon"),
    "front_yoke": Material(rgba(74, 78, 72), "nylon"),
    "rear_yoke": Material(rgba(59, 65, 60), "nylon"),
    "top_bridge": Material(rgba(83, 87, 80), "soft"),
    "tube": Material(rgba(88, 96, 83), "metal"),
    "mag_pouch": Material(rgba(55, 61, 56), "nylon"),
    "mag_lid": Material(rgba(70, 75, 69), "webbing"),
    "medical": Material(rgba(57, 63, 58), "nylon"),
    "utility": Material(rgba(50, 56, 52), "nylon"),
    "side_rail": Material(rgba(40, 46, 42), "webbing"),
    "drop": Material(rgba(52, 58, 53), "nylon"),
    "belt": Material(rgba(45, 51, 47), "webbing"),
    "buckle": Material(rgba(76, 80, 77), "metal"),
}

STRANDHOGG_CUBES = {
    "front_upper": CubeUV(0, 0, 6.60, 4.0, 0.48),
    "front_lower": CubeUV(25, 0, 7.40, 5.75, 0.55),
    "rear_upper": CubeUV(50, 0, 6.60, 4.0, 0.48),
    "rear_lower": CubeUV(75, 0, 7.40, 5.75, 0.55),
    "side_mesh": CubeUV(100, 0, 0.45, 7.27, 4.10),
    "front_yoke": CubeUV(0, 22, 1.30, 3.90, 0.50),
    "rear_yoke": CubeUV(25, 22, 1.30, 3.90, 0.50),
    "top_bridge": CubeUV(50, 22, 1.24, 0.55, 4.16),
    "laser_rail": CubeUV(75, 22, 5.90, 0.16, 0.22),
    "mag_pouch": CubeUV(100, 22, 1.80, 4.80, 0.58),
    "mag_lid": CubeUV(0, 44, 1.86, 1.05, 0.24),
    "radio": CubeUV(25, 44, 0.60, 3.70, 2.70),
    "utility": CubeUV(50, 44, 0.60, 3.30, 2.90),
    "drop": CubeUV(75, 44, 4.60, 3.85, 0.48),
    "hinge": CubeUV(100, 44, 0.30, 1.10, 0.76),
    "belt": CubeUV(0, 66, 7.48, 0.90, 0.30),
}

STRANDHOGG_GREEN_MATERIALS = {
    "front_upper": Material(rgba(56, 63, 57), "nylon"),
    "front_lower": Material(rgba(42, 50, 45), "nylon"),
    "rear_upper": Material(rgba(38, 45, 40), "nylon"),
    "rear_lower": Material(rgba(47, 54, 49), "nylon"),
    "side_mesh": Material(rgba(38, 34, 28), "mesh"),
    "front_yoke": Material(rgba(91, 88, 75), "nylon"),
    "rear_yoke": Material(rgba(68, 70, 62), "nylon"),
    "top_bridge": Material(rgba(146, 136, 113), "soft"),
    "laser_rail": Material(rgba(34, 41, 37), "webbing"),
    "mag_pouch": Material(rgba(78, 37, 29), "plastic"),
    "mag_lid": Material(rgba(49, 55, 50), "webbing"),
    "radio": Material(rgba(31, 36, 34), "plastic"),
    "utility": Material(rgba(43, 50, 46), "nylon"),
    "drop": Material(rgba(54, 51, 43), "nylon"),
    "hinge": Material(rgba(83, 86, 79), "metal"),
    "belt": Material(rgba(39, 46, 42), "webbing"),
}

BLACK_CAMO = tuple(map(lambda color: rgba(*color), ((12, 14, 14), (24, 27, 27), (36, 39, 38), (52, 53, 51))))
STRANDHOGG_BLACK_MATERIALS = {
    name: Material(
        rgba(28, 31, 30) if name not in ("hinge", "radio") else rgba(48, 51, 49),
        "black_camo" if name not in ("hinge", "radio") else ("metal" if name == "hinge" else "plastic"),
        BLACK_CAMO if name not in ("hinge", "radio") else (),
    )
    for name in STRANDHOGG_CUBES
}

TROOPER_CUBES = {
    "front_upper": CubeUV(0, 0, 6.20, 4.20, 0.38),
    "front_lower": CubeUV(25, 0, 7.40, 6.35, 0.42),
    "rear_upper": CubeUV(50, 0, 6.20, 4.20, 0.38),
    "rear_lower": CubeUV(75, 0, 7.40, 6.35, 0.42),
    "side_mesh": CubeUV(100, 0, 0.38, 8.27, 4.10),
    "front_yoke": CubeUV(0, 22, 1.25, 3.90, 0.42),
    "rear_yoke": CubeUV(25, 22, 1.25, 3.90, 0.42),
    "top_bridge": CubeUV(50, 22, 1.19, 0.50, 4.10),
    "patch": CubeUV(75, 22, 5.40, 2.05, 0.30),
    "molle": CubeUV(100, 22, 6.40, 0.24, 0.22),
    "hem": CubeUV(0, 44, 7.44, 0.72, 0.26),
    "buckle": CubeUV(25, 44, 0.26, 0.75, 0.84),
    "seam": CubeUV(50, 44, 0.24, 5.55, 0.22),
}

MULTICAM = tuple(map(lambda color: rgba(*color), ((57, 61, 52), (83, 85, 71), (111, 107, 87), (145, 138, 113), (68, 76, 69))))
TROOPER_MATERIALS = {
    "front_upper": Material(rgba(94, 95, 79), "multicam", MULTICAM),
    "front_lower": Material(rgba(101, 101, 84), "multicam", MULTICAM),
    "rear_upper": Material(rgba(83, 86, 73), "multicam", MULTICAM),
    "rear_lower": Material(rgba(91, 93, 77), "multicam", MULTICAM),
    "side_mesh": Material(rgba(46, 53, 51), "mesh"),
    "front_yoke": Material(rgba(120, 115, 94), "multicam", MULTICAM),
    "rear_yoke": Material(rgba(103, 101, 84), "multicam", MULTICAM),
    "top_bridge": Material(rgba(132, 125, 102), "multicam", MULTICAM),
    "patch": Material(rgba(29, 32, 30), "velcro"),
    "molle": Material(rgba(104, 102, 83), "multicam", MULTICAM),
    "hem": Material(rgba(86, 87, 73), "multicam", MULTICAM),
    "buckle": Material(rgba(67, 71, 68), "plastic"),
    "seam": Material(rgba(70, 74, 64), "webbing"),
}

BANSHEE_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.44, 9.45, 0.64),
    "rear_plate": CubeUV(50, 0, 7.44, 9.45, 0.64),
    "side_panel": CubeUV(75, 0, 0.52, 6.40, 4.36),
    "front_yoke": CubeUV(100, 0, 1.65, 4.20, 0.56),
    "rear_yoke": CubeUV(0, 22, 1.65, 4.20, 0.56),
    "top_bridge": CubeUV(25, 22, 1.59, 0.62, 4.48),
    "admin": CubeUV(50, 22, 6.0, 1.55, 0.34),
    "tourniquet": CubeUV(75, 22, 4.20, 0.48, 0.42),
    "mag_pouch": CubeUV(100, 22, 1.80, 4.60, 0.52),
    "mag_lid": CubeUV(0, 44, 1.86, 1.08, 0.24),
    "utility": CubeUV(25, 44, 0.64, 4.35, 3.40),
    "utility_flap": CubeUV(50, 44, 0.32, 0.84, 3.48),
    "radio_slot": CubeUV(75, 44, 0.40, 3.60, 2.84),
    "radio": CubeUV(100, 44, 0.42, 3.15, 1.10),
    "side_pouch": CubeUV(0, 66, 0.64, 3.0, 3.28),
    "molle": CubeUV(25, 66, 6.40, 0.20, 0.24),
    "belt": CubeUV(50, 66, 7.60, 1.02, 0.42),
    "pull": CubeUV(75, 66, 0.28, 2.15, 0.26),
    "buckle": CubeUV(100, 66, 0.62, 0.72, 0.20),
}

ATACS = tuple(map(lambda color: rgba(*color), ((56, 56, 47), (78, 76, 64), (99, 94, 79), (126, 119, 100), (154, 146, 124))))
BANSHEE_MATERIALS = {
    "carrier": Material(rgba(78, 77, 66), "atacs", ATACS),
    "front_plate": Material(rgba(99, 95, 80), "atacs", ATACS),
    "rear_plate": Material(rgba(85, 82, 69), "atacs", ATACS),
    "side_panel": Material(rgba(45, 48, 42), "mesh"),
    "front_yoke": Material(rgba(127, 119, 98), "atacs", ATACS),
    "rear_yoke": Material(rgba(110, 104, 87), "atacs", ATACS),
    "top_bridge": Material(rgba(143, 135, 113), "atacs", ATACS),
    "admin": Material(rgba(80, 75, 63), "velcro"),
    "tourniquet": Material(rgba(37, 39, 36), "plastic"),
    "mag_pouch": Material(rgba(93, 91, 77), "atacs", ATACS),
    "mag_lid": Material(rgba(109, 104, 86), "atacs", ATACS),
    "utility": Material(rgba(86, 83, 70), "atacs", ATACS),
    "utility_flap": Material(rgba(102, 98, 82), "atacs", ATACS),
    "radio_slot": Material(rgba(68, 70, 61), "webbing"),
    "radio": Material(rgba(31, 34, 32), "plastic"),
    "side_pouch": Material(rgba(72, 71, 61), "atacs", ATACS),
    "molle": Material(rgba(88, 84, 70), "atacs", ATACS),
    "belt": Material(rgba(77, 75, 63), "atacs", ATACS),
    "pull": Material(rgba(46, 48, 43), "webbing"),
    "buckle": Material(rgba(58, 61, 57), "plastic"),
}


def standard_details(cubes: dict[str, CubeUV]) -> tuple[tuple[str, str, str], ...]:
    styles = {
        "carrier": "panel",
        "front_plate": "panel",
        "rear_plate": "panel",
        "front_upper": "panel",
        "front_lower": "panel",
        "rear_upper": "panel",
        "rear_lower": "panel",
        "side_wrap": "panel",
        "side_panel": "panel",
        "side_mesh": "mesh",
        "front_yoke": "panel",
        "rear_yoke": "panel",
        "top_bridge": "panel",
        "admin": "patch",
        "patch": "patch",
        "molle": "webbing",
        "laser_rail": "webbing",
        "side_rail": "webbing",
        "mag_pouch": "magazine",
        "mag_lid": "pouch",
        "side_pouch": "pouch",
        "medical": "pouch",
        "utility": "pouch",
        "utility_flap": "pouch",
        "drop": "pouch",
        "belt": "webbing",
        "buckle": "buckle",
        "radio": "radio",
        "radio_slot": "webbing",
        "tube": "tube",
        "tourniquet": "tourniquet",
        "hem": "webbing",
        "seam": "webbing",
        "hinge": "buckle",
    }
    details: list[tuple[str, str, str]] = []
    for cube_name in cubes:
        style = styles.get(cube_name)
        if style is None:
            continue
        directions = ("north", "south") if cube_name in ("carrier", "belt", "hem") else ("north",)
        if cube_name in ("side_wrap", "side_panel", "side_mesh", "side_pouch", "medical", "utility", "radio_slot"):
            directions = ("west", "east")
        if cube_name == "top_bridge":
            directions = ("up",)
        for direction in directions:
            details.append((cube_name, direction, style))
    return tuple(details)


SPECS = (
    ModelSpec(
        "MMAC",
        "plate_armor_mmac_ranger_green_layer_1.png",
        Material(rgba(41, 48, 43), "nylon"),
        MMAC_CUBES,
        MMAC_MATERIALS,
        standard_details(MMAC_CUBES),
        {"side_wrap": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2, "molle": 4,
         "mag_pouch": 4, "mag_lid": 4, "side_pouch": 2, "belt": 2, "dangling": 2},
    ),
    ModelSpec(
        "RBAV-AF",
        "plate_armor_rbav_af_ranger_green_layer_1.png",
        Material(rgba(40, 46, 42), "nylon"),
        RBAV_CUBES,
        RBAV_MATERIALS,
        standard_details(RBAV_CUBES),
        {"side_wrap": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2, "tube": 8,
         "mag_pouch": 4, "mag_lid": 4, "side_rail": 4, "belt": 2, "buckle": 2},
    ),
    ModelSpec(
        "Strandhogg Ranger Green",
        "plate_armor_strandhogg_ranger_green_layer_1.png",
        Material(rgba(35, 42, 38), "nylon"),
        STRANDHOGG_CUBES,
        STRANDHOGG_GREEN_MATERIALS,
        standard_details(STRANDHOGG_CUBES),
        {"side_mesh": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2, "laser_rail": 5,
         "mag_pouch": 3, "mag_lid": 3, "hinge": 2, "belt": 2},
    ),
    ModelSpec(
        "Strandhogg Black Multicam",
        "plate_armor_strandhogg_black_multicam_layer_1.png",
        Material(rgba(18, 21, 20), "black_camo", BLACK_CAMO),
        STRANDHOGG_CUBES,
        STRANDHOGG_BLACK_MATERIALS,
        standard_details(STRANDHOGG_CUBES),
        {"side_mesh": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2, "laser_rail": 5,
         "mag_pouch": 3, "mag_lid": 3, "hinge": 2, "belt": 2},
    ),
    ModelSpec(
        "Trooper TFO Multicam",
        "plate_armor_trooper_tfo_multicam_layer_1.png",
        Material(rgba(78, 80, 68), "multicam", MULTICAM),
        TROOPER_CUBES,
        TROOPER_MATERIALS,
        standard_details(TROOPER_CUBES),
        {"side_mesh": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2, "molle": 3,
         "hem": 2, "buckle": 2, "seam": 2},
    ),
    ModelSpec(
        "Banshee A-Tacs AU",
        "plate_armor_banshee_atacs_au_layer_1.png",
        Material(rgba(76, 73, 62), "atacs", ATACS),
        BANSHEE_CUBES,
        BANSHEE_MATERIALS,
        standard_details(BANSHEE_CUBES),
        {"side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "top_bridge": 2,
         "mag_pouch": 3, "mag_lid": 3, "molle": 3, "belt": 2, "buckle": 2},
    ),
)


def rectangles_overlap(first: Face, second: Face) -> bool:
    return first.x0 < second.x1 and second.x0 < first.x1 and first.y0 < second.y1 and second.y0 < first.y1


def validate_uvs(spec: ModelSpec) -> None:
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


def build_texture(spec: ModelSpec) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    background_seed = stable_seed(spec.name + "background")
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel(spec.background, x, y, background_seed)
    for cube_name, cube in spec.cubes.items():
        material = spec.materials[cube_name]
        for direction, face in cube_faces(cube).items():
            paint_base_face(image, face, material, stable_seed(spec.name + cube_name + direction))
    for cube_name, direction, style in spec.detail_faces:
        paint_detail(image, spec, cube_name, direction, style)
    return image


def runtime_cube_count(spec: ModelSpec) -> int:
    return sum(spec.instance_counts.get(cube_name, 1) for cube_name in spec.cubes)


def write_texture(spec: ModelSpec) -> None:
    validate_uvs(spec)
    image = build_texture(spec)
    if image.tobytes() != build_texture(spec).tobytes():
        raise RuntimeError(f"{spec.name}: texture generation is not deterministic")
    output = OUTPUT_DIR / spec.output_name
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG", optimize=False)
    with Image.open(output) as written:
        if written.mode != "RGBA" or written.size != (SIZE, SIZE):
            raise RuntimeError(f"{spec.name}: output must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError(f"{spec.name}: output alpha must remain fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 40:
            raise RuntimeError(f"{spec.name}: material texture lost surface variation")
        digest = sha256(output.read_bytes()).hexdigest().upper()
        print(f"{output.name} cubes={runtime_cube_count(spec)} colors={len(colors)} sha256={digest} uv=unique alpha=255")


def main() -> None:
    expected_counts = {
        "MMAC": 31,
        "RBAV-AF": 38,
        "Strandhogg Ranger Green": 30,
        "Strandhogg Black Multicam": 30,
        "Trooper TFO Multicam": 22,
        "Banshee A-Tacs AU": 32,
    }
    for spec in SPECS:
        actual = runtime_cube_count(spec)
        if actual != expected_counts[spec.name]:
            raise RuntimeError(f"{spec.name}: expected {expected_counts[spec.name]} cuboids, got {actual}")
        write_texture(spec)


if __name__ == "__main__":
    main()
