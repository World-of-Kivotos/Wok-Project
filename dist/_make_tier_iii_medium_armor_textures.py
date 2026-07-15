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
        "east": Face(
            u + depth + width,
            v + depth,
            u + depth * 2 + width,
            v + depth + height,
        ),
        "south": Face(
            u + depth * 2 + width,
            v + depth,
            u + depth * 2 + width * 2,
            v + depth + height,
        ),
    }


MODELS: dict[str, tuple[CubeUV, ...]] = {
    "b6b23": (
        CubeUV("carrier", 0, 0, 8.0, 12.0, 4.0, "fabric"),
        CubeUV("front_plate", 26, 0, 7.50, 9.0, 0.70, "plate"),
        CubeUV("rear_plate", 44, 0, 7.60, 9.50, 0.65, "plate"),
        CubeUV("left_side", 62, 0, 0.70, 8.50, 4.40, "side"),
        CubeUV("right_side", 74, 0, 0.70, 8.50, 4.40, "side"),
        CubeUV("front_collar", 0, 20, 6.80, 2.25, 0.65, "collar"),
        CubeUV("rear_collar", 16, 20, 6.80, 2.40, 0.65, "collar"),
        CubeUV("left_collar", 32, 20, 0.65, 2.30, 6.50, "collar"),
        CubeUV("right_collar", 48, 20, 0.65, 2.30, 6.50, "collar"),
        CubeUV("lower_front", 0, 34, 7.80, 2.50, 0.55, "lower"),
        CubeUV("groin", 18, 34, 4.30, 4.40, 0.50, "lower"),
        CubeUV("waist_lip", 30, 34, 8.20, 0.55, 0.25, "webbing"),
    ),
    "b6b5": (
        CubeUV("carrier", 0, 0, 8.0, 12.0, 4.0, "fabric"),
        CubeUV("front_shell", 26, 0, 7.60, 10.30, 0.55, "shell"),
        CubeUV("rear_shell", 44, 0, 7.60, 10.30, 0.55, "shell"),
        CubeUV("left_side", 62, 0, 0.55, 8.70, 4.30, "side"),
        CubeUV("right_side", 73, 0, 0.55, 8.70, 4.30, "side"),
        CubeUV("front_collar", 0, 18, 6.40, 2.0, 0.65, "collar"),
        CubeUV("rear_collar", 16, 18, 6.40, 2.0, 0.65, "collar"),
        CubeUV("left_collar", 32, 18, 0.65, 2.15, 5.90, "collar"),
        CubeUV("right_collar", 47, 18, 0.65, 2.15, 5.90, "collar"),
        CubeUV("left_strap", 62, 18, 0.75, 4.20, 0.28, "webbing"),
        CubeUV("right_strap", 66, 18, 0.75, 4.20, 0.28, "webbing"),
        CubeUV("pouch_far_left", 0, 30, 1.50, 4.10, 0.72, "pouch"),
        CubeUV("pouch_left", 6, 30, 1.55, 4.10, 0.72, "pouch"),
        CubeUV("pouch_right", 12, 30, 1.50, 4.10, 0.72, "pouch"),
        CubeUV("pouch_far_right", 18, 30, 1.55, 4.10, 0.72, "pouch"),
        CubeUV("lower_front", 24, 30, 5.80, 2.60, 0.50, "shell"),
        CubeUV("lower_tip", 38, 30, 3.20, 1.40, 0.50, "shell"),
    ),
    "kirasa": (
        CubeUV("front_upper", 0, 0, 6.50, 3.25, 0.32, "upper"),
        CubeUV("rear_upper", 16, 0, 6.50, 3.25, 0.32, "upper"),
        CubeUV("front_middle", 32, 0, 7.30, 4.0, 0.34, "middle"),
        CubeUV("rear_middle", 50, 0, 7.30, 4.0, 0.34, "middle"),
        CubeUV("front_lower", 68, 0, 7.80, 3.75, 0.36, "lower"),
        CubeUV("rear_lower", 87, 0, 7.80, 3.75, 0.36, "lower"),
        CubeUV("left_side", 0, 16, 0.51, 7.75, 3.88, "side"),
        CubeUV("right_side", 10, 16, 0.51, 7.75, 3.88, "side"),
        CubeUV("front_collar_left", 22, 16, 4.0, 1.30, 0.36, "collar"),
        CubeUV("front_collar_right", 32, 16, 4.0, 1.30, 0.36, "collar"),
        CubeUV("rear_collar", 42, 16, 8.96, 1.30, 0.36, "collar"),
        CubeUV("left_collar", 63, 16, 0.36, 1.28, 8.24, "collar"),
        CubeUV("right_collar", 81, 16, 0.36, 1.28, 8.24, "collar"),
        CubeUV("front_yoke_left", 0, 30, 2.77, 0.23, 2.22, "yoke"),
        CubeUV("front_yoke_right", 12, 30, 2.77, 0.23, 2.22, "yoke"),
        CubeUV("rear_yoke_left", 24, 30, 2.77, 0.23, 2.22, "yoke"),
        CubeUV("rear_yoke_right", 36, 30, 2.77, 0.23, 2.22, "yoke"),
        CubeUV("closure", 50, 30, 0.20, 9.70, 0.18, "seam"),
        CubeUV("chest_flap", 54, 30, 3.22, 2.10, 0.21, "flap"),
    ),
    "kora": (
        CubeUV("carrier", 0, 0, 8.0, 12.0, 4.0, "fabric"),
        CubeUV("front_shell", 26, 0, 7.80, 8.80, 0.55, "shell"),
        CubeUV("rear_shell", 44, 0, 7.80, 8.80, 0.55, "shell"),
        CubeUV("left_side", 62, 0, 0.65, 10.0, 4.40, "side"),
        CubeUV("right_side", 74, 0, 0.65, 10.0, 4.40, "side"),
        CubeUV("front_strap_left", 0, 18, 1.80, 4.50, 0.45, "strap"),
        CubeUV("front_strap_right", 6, 18, 1.80, 4.50, 0.45, "strap"),
        CubeUV("rear_strap_left", 12, 18, 1.80, 4.50, 0.45, "strap"),
        CubeUV("rear_strap_right", 18, 18, 1.80, 4.50, 0.45, "strap"),
        CubeUV("top_bridge_left", 24, 18, 1.90, 0.70, 5.0, "upper"),
        CubeUV("top_bridge_right", 39, 18, 1.90, 0.70, 5.0, "upper"),
        CubeUV("front_belt", 54, 18, 8.80, 3.20, 0.65, "belt"),
        CubeUV("rear_belt", 74, 18, 8.80, 3.20, 0.65, "belt"),
        CubeUV("left_belt", 94, 18, 0.75, 3.20, 4.90, "belt"),
        CubeUV("right_belt", 107, 18, 0.75, 3.20, 4.90, "belt"),
        CubeUV("buckle", 0, 30, 2.20, 2.60, 0.30, "buckle"),
        CubeUV("bottom_panel", 7, 30, 7.40, 2.10, 0.40, "shell"),
    ),
}


DETAILS: dict[str, tuple[tuple[str, str, str], ...]] = {
    "b6b23": (
        ("carrier", "north", "stitched"),
        ("carrier", "south", "stitched"),
        ("front_plate", "north", "armor"),
        ("rear_plate", "south", "armor"),
        ("left_side", "west", "channels"),
        ("right_side", "east", "channels"),
        ("front_collar", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("lower_front", "north", "stitched"),
        ("groin", "north", "stitched"),
        ("groin", "south", "stitched"),
        ("waist_lip", "north", "webbing"),
    ),
    "b6b5": (
        ("carrier", "north", "stitched"),
        ("carrier", "south", "stitched"),
        ("front_shell", "north", "smooth"),
        ("rear_shell", "south", "smooth"),
        ("left_side", "west", "stitched"),
        ("right_side", "east", "stitched"),
        ("front_collar", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("left_strap", "north", "webbing"),
        ("right_strap", "north", "webbing"),
        ("pouch_far_left", "north", "pouch"),
        ("pouch_left", "north", "pouch"),
        ("pouch_right", "north", "pouch"),
        ("pouch_far_right", "north", "pouch"),
        ("lower_front", "north", "stitched"),
        ("lower_tip", "north", "stitched"),
    ),
    "kirasa": (
        ("front_upper", "north", "smooth"),
        ("rear_upper", "south", "smooth"),
        ("front_middle", "north", "smooth"),
        ("rear_middle", "south", "smooth"),
        ("front_lower", "north", "smooth"),
        ("rear_lower", "south", "smooth"),
        ("left_side", "west", "seam"),
        ("right_side", "east", "seam"),
        ("front_collar_left", "north", "collar"),
        ("front_collar_right", "north", "collar"),
        ("rear_collar", "south", "collar"),
        ("left_collar", "west", "collar"),
        ("right_collar", "east", "collar"),
        ("front_yoke_left", "up", "smooth"),
        ("front_yoke_right", "up", "smooth"),
        ("rear_yoke_left", "up", "smooth"),
        ("rear_yoke_right", "up", "smooth"),
        ("closure", "north", "seam"),
        ("chest_flap", "north", "flap"),
    ),
    "kora": (
        ("carrier", "north", "stitched"),
        ("carrier", "south", "stitched"),
        ("front_shell", "north", "smooth"),
        ("rear_shell", "south", "smooth"),
        ("left_side", "west", "channels"),
        ("right_side", "east", "channels"),
        ("front_strap_left", "north", "strap"),
        ("front_strap_right", "north", "strap"),
        ("rear_strap_left", "south", "strap"),
        ("rear_strap_right", "south", "strap"),
        ("top_bridge_left", "up", "strap"),
        ("top_bridge_right", "up", "strap"),
        ("front_belt", "north", "belt"),
        ("rear_belt", "south", "belt"),
        ("left_belt", "west", "belt"),
        ("right_belt", "east", "belt"),
        ("buckle", "north", "buckle"),
        ("bottom_panel", "north", "stitched"),
    ),
}


THEMES = {
    "plate_armor_6b23_1_digital_flora_layer_1.png": {
        "model": "b6b23",
        "background": (73, 82, 56, 255),
        "palette": {
            "fabric": (92, 105, 71, 255),
            "plate": (82, 96, 63, 255),
            "side": (76, 88, 58, 255),
            "collar": (72, 85, 57, 255),
            "webbing": (66, 76, 51, 255),
            "lower": (84, 97, 65, 255),
        },
        "digital": {"fabric", "plate", "side", "collar", "lower"},
        "edge": (48, 57, 40, 255),
        "stitch": (119, 127, 91, 255),
    },
    "plate_armor_6b5_16_layer_1.png": {
        "model": "b6b5",
        "background": (108, 106, 78, 255),
        "palette": {
            "fabric": (133, 132, 96, 255),
            "shell": (139, 137, 101, 255),
            "side": (118, 119, 85, 255),
            "collar": (92, 99, 72, 255),
            "webbing": (105, 107, 77, 255),
            "pouch": (143, 140, 103, 255),
        },
        "digital": set(),
        "edge": (76, 79, 59, 255),
        "stitch": (172, 166, 125, 255),
    },
    "plate_armor_kirasa_n_green_layer_1.png": {
        "model": "kirasa",
        "background": (20, 23, 20, 255),
        "palette": {
            "upper": (41, 45, 35, 255),
            "middle": (31, 35, 29, 255),
            "lower": (29, 33, 27, 255),
            "side": (24, 27, 23, 255),
            "collar": (14, 18, 15, 255),
            "yoke": (36, 40, 32, 255),
            "seam": (18, 22, 18, 255),
            "flap": (41, 45, 35, 255),
        },
        "digital": set(),
        "edge": (12, 16, 13, 255),
        "stitch": (103, 106, 90, 255),
    },
    "plate_armor_kora_kulon_layer_1.png": {
        "model": "kora",
        "background": (47, 50, 50, 255),
        "palette": {
            "fabric": (59, 63, 63, 255),
            "shell": (64, 67, 68, 255),
            "side": (54, 58, 59, 255),
            "strap": (52, 55, 56, 255),
            "upper": (57, 60, 61, 255),
            "belt": (39, 42, 43, 255),
            "buckle": (46, 48, 49, 255),
        },
        "digital": set(),
        "edge": (25, 28, 29, 255),
        "stitch": (91, 95, 96, 255),
    },
    "plate_armor_kora_kulon_digital_layer_1.png": {
        "model": "kora",
        "background": (68, 72, 54, 255),
        "palette": {
            "fabric": (91, 97, 70, 255),
            "shell": (101, 105, 76, 255),
            "side": (82, 89, 64, 255),
            "strap": (50, 54, 52, 255),
            "upper": (91, 96, 69, 255),
            "belt": (39, 43, 43, 255),
            "buckle": (46, 49, 48, 255),
        },
        "digital": {"fabric", "shell", "side", "upper"},
        "edge": (29, 33, 32, 255),
        "stitch": (124, 128, 94, 255),
    },
}


EXPECTED_CUBES = {"b6b23": 12, "b6b5": 17, "kirasa": 19, "kora": 17}


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


def material_pixel(base: RGBA, x: int, y: int, seed: int, digital: bool) -> RGBA:
    fine = ((x * 73 + y * 151 + seed * 37 + (x + 5) * (y + 13) * 11) & 0xFF) % 5 - 2
    if digital:
        cell_x = x // 2
        cell_y = y // 2
        value = (cell_x * 31 + cell_y * 47 + seed * 19 + (cell_x ^ cell_y) * 13) % 17
        camo = -10 if value in (0, 1, 2) else 8 if value in (3, 4) else -4 if value == 5 else 0
        return shade(base, fine + camo)
    weave = 3 if (x + 2 * y + seed) % 23 == 0 else -2 if (2 * x + y + seed) % 29 == 0 else 0
    return shade(base, fine + weave)


def paint_face(
    image: Image.Image,
    face: Face,
    base: RGBA,
    seed: int,
    digital: bool,
) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    pixels = image.load()
    for y in range(bounds[1], bounds[3]):
        for x in range(bounds[0], bounds[2]):
            pixels[x, y] = material_pixel(base, x, y, seed, digital)
    return bounds


def low_contrast_edges(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 3 or y1 - y0 < 3:
        return
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 6))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -9))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 3))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -6))


def stitch_border(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    stitch: RGBA,
    edge: RGBA,
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 5 or y1 - y0 < 4:
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
    low_contrast_edges(draw, bounds, base)

    if kind in {"stitched", "armor", "smooth", "collar", "flap"}:
        stitch_border(draw, bounds, stitch, edge)
    if kind == "armor" and width >= 6 and height >= 6:
        for y in range(y0 + 3, y1 - 2, 3):
            draw.line((x0 + 2, y, x1 - 3, y), fill=shade(base, -7))
            if y + 1 < y1 - 1:
                draw.point((x0 + 2, y + 1), fill=shade(base, 5))
    elif kind == "channels" and width >= 4 and height >= 4:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.line((x, y0 + 1, x, y1 - 2), fill=shade(base, -7))
    elif kind == "webbing" and width >= 2 and height >= 4:
        for y in range(y0 + 2, y1 - 1, 2):
            draw.line((x0 + 1, y, x1 - 2, y), fill=shade(base, -8))
    elif kind == "pouch" and width >= 2 and height >= 4:
        flap_y = min(y0 + 2, y1 - 2)
        draw.line((x0, flap_y, x1 - 1, flap_y), fill=edge)
        if width >= 4:
            center = x0 + width // 2
            draw.line((center, flap_y + 1, center, y1 - 2), fill=shade(base, -7))
    elif kind == "collar" and width >= 5:
        center = x0 + width // 2
        draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -6))
    elif kind == "smooth" and width >= 6 and height >= 5:
        draw.line((x0 + 1, y1 - 2, x1 - 2, y1 - 2), fill=shade(base, -5))
    elif kind == "seam" and width >= 1:
        center = x0 + max(0, (width - 1) // 2)
        draw.line((center, y0, center, y1 - 1), fill=edge)
    elif kind == "flap" and width >= 4 and height >= 3:
        draw.line((x0 + 1, y1 - 2, x1 - 2, y1 - 2), fill=edge)
    elif kind == "strap" and width >= 3 and height >= 3:
        draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -7))
        draw.line((x1 - 2, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 5))
    elif kind == "belt" and width >= 4 and height >= 3:
        stitch_border(draw, bounds, stitch, edge)
        center = x0 + width // 2
        draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -6))
    elif kind == "buckle":
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=shade(base, -6), outline=edge)
        if width >= 2 and height >= 2:
            draw.point((x0 + 1, y0 + 1), fill=shade(stitch, 4))


def cube_net_bounds(cube: CubeUV) -> Face:
    faces = cube_faces(cube).values()
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
            first_bounds = cube_net_bounds(cube)
            for other in cubes[index + 1 :]:
                if overlaps(first_bounds, cube_net_bounds(other)):
                    raise RuntimeError(f"UV conflict: {model_name}.{cube.name} / {other.name}")
        for cube_name, direction, _ in DETAILS[model_name]:
            if cube_name not in names or direction not in cube_faces(next(c for c in cubes if c.name == cube_name)):
                raise RuntimeError(f"Invalid detail target: {model_name}.{cube_name}.{direction}")


def build_texture(theme_name: str) -> Image.Image:
    theme = THEMES[theme_name]
    model_name = theme["model"]
    cubes = MODELS[model_name]
    palette = theme["palette"]
    digital_materials = theme["digital"]
    background = theme["background"]

    image = Image.new("RGBA", (SIZE, SIZE), background)
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel(background, x, y, 17, False)

    cube_by_name = {cube.name: cube for cube in cubes}
    for cube in cubes:
        base = palette[cube.material]
        is_digital = cube.material in digital_materials
        for direction, face in cube_faces(cube).items():
            paint_face(
                image,
                face,
                base,
                stable_seed(f"{theme_name}:{cube.name}:{direction}"),
                is_digital,
            )

    for cube_name, direction, kind in DETAILS[model_name]:
        cube = cube_by_name[cube_name]
        base = palette[cube.material]
        bounds = paint_face(
            image,
            cube_faces(cube)[direction],
            base,
            stable_seed(f"{theme_name}:{cube_name}:{direction}:detail"),
            cube.material in digital_materials,
        )
        detail_face(image, bounds, kind, base, theme["edge"], theme["stitch"])
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
            if colors is None or len(colors) < 24:
                raise RuntimeError(f"Texture lost material detail: {filename}")
        digest = sha256(output.read_bytes()).hexdigest()
        print(f"{filename} model={THEMES[filename]['model']} colors={len(colors)} sha256={digest}")

    print("uv=ok alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
