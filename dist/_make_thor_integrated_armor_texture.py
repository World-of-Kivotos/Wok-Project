from dataclasses import dataclass
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "miningdim"
    / "textures"
    / "models"
    / "armor"
    / "plate_armor_thor_integrated_layer_1.png"
)

SIZE = 128
RGBA = tuple[int, int, int, int]

NYLON = (94, 89, 66, 255)
PLATE = (87, 83, 62, 255)
WEBBING = (78, 75, 56, 255)
COLLAR = (101, 96, 71, 255)
POUCH = (88, 84, 63, 255)
METAL = (61, 60, 51, 255)
EDGE = (53, 51, 39, 255)
SEAM = (65, 62, 46, 255)
STITCH = (112, 106, 80, 255)
SLOT = (43, 43, 35, 255)


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


CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(26, 0, 7.30, 8.75, 0.58),
    "rear_plate": CubeUV(44, 0, 7.50, 9.45, 0.58),
    "left_side": CubeUV(63, 0, 0.50, 7.80, 4.68),
    "right_side": CubeUV(75, 0, 0.50, 7.80, 4.68),
    "front_collar_left": CubeUV(0, 72, 1.55, 2.01, 0.62),
    "front_collar_center": CubeUV(8, 72, 5.50, 1.37, 0.62),
    "front_collar_right": CubeUV(24, 72, 1.55, 2.01, 0.62),
    "rear_collar": CubeUV(20, 18, 8.60, 2.23, 0.62),
    "left_collar": CubeUV(40, 18, 0.62, 2.10, 8.32),
    "right_collar": CubeUV(58, 18, 0.62, 2.10, 8.32),
    "front_strap_left": CubeUV(76, 18, 1.20, 4.25, 0.48),
    "front_strap_right": CubeUV(81, 18, 1.20, 4.25, 0.48),
    "rear_strap_left": CubeUV(86, 18, 1.20, 4.25, 0.48),
    "rear_strap_right": CubeUV(91, 18, 1.20, 4.25, 0.48),
    "lower_front": CubeUV(0, 30, 8.0, 1.55, 0.55),
    "lower_rear": CubeUV(19, 30, 8.0, 1.55, 0.55),
    "groin": CubeUV(38, 30, 4.50, 4.85, 0.46),
    "left_pouch": CubeUV(50, 30, 0.72, 4.20, 3.10),
    "right_pouch": CubeUV(59, 30, 0.72, 4.20, 3.10),
    "admin_webbing": CubeUV(8, 80, 6.20, 0.26, 0.12),
    "front_buckle": CubeUV(24, 80, 0.70, 0.75, 0.18),
    "molle_loop": CubeUV(0, 80, 1.20, 0.24, 0.12),
    "right_shoulder_core": CubeUV(0, 48, 4.0, 4.75, 4.0),
    "left_shoulder_core": CubeUV(18, 48, 4.0, 4.75, 4.0),
    "right_shoulder_top": CubeUV(36, 48, 4.96, 0.52, 4.96),
    "left_shoulder_top": CubeUV(57, 48, 4.96, 0.52, 4.96),
    "right_shoulder_outer": CubeUV(78, 48, 0.46, 5.15, 4.86),
    "left_shoulder_outer": CubeUV(90, 48, 0.46, 5.15, 4.86),
    "right_shoulder_front": CubeUV(0, 60, 4.84, 4.70, 0.43),
    "left_shoulder_front": CubeUV(12, 60, 4.84, 4.70, 0.43),
    "right_shoulder_rear": CubeUV(24, 60, 4.84, 4.70, 0.43),
    "left_shoulder_rear": CubeUV(36, 60, 4.84, 4.70, 0.43),
}

INSTANCE_COUNTS = {
    "admin_webbing": 2,
    "front_buckle": 2,
    "molle_loop": 20,
}

MATERIALS: dict[str, tuple[RGBA, str]] = {
    "carrier": (NYLON, "nylon"),
    "front_plate": (PLATE, "plate"),
    "rear_plate": (PLATE, "plate"),
    "left_side": (WEBBING, "webbing"),
    "right_side": (WEBBING, "webbing"),
    "front_collar_left": (COLLAR, "nylon"),
    "front_collar_center": (COLLAR, "nylon"),
    "front_collar_right": (COLLAR, "nylon"),
    "rear_collar": (COLLAR, "nylon"),
    "left_collar": (COLLAR, "nylon"),
    "right_collar": (COLLAR, "nylon"),
    "front_strap_left": (WEBBING, "webbing"),
    "front_strap_right": (WEBBING, "webbing"),
    "rear_strap_left": (WEBBING, "webbing"),
    "rear_strap_right": (WEBBING, "webbing"),
    "lower_front": (WEBBING, "webbing"),
    "lower_rear": (WEBBING, "webbing"),
    "groin": (POUCH, "nylon"),
    "left_pouch": (POUCH, "nylon"),
    "right_pouch": (POUCH, "nylon"),
    "admin_webbing": (WEBBING, "webbing"),
    "front_buckle": (METAL, "metal"),
    "molle_loop": (WEBBING, "webbing"),
    "right_shoulder_core": (NYLON, "nylon"),
    "left_shoulder_core": (NYLON, "nylon"),
    "right_shoulder_top": (PLATE, "plate"),
    "left_shoulder_top": (PLATE, "plate"),
    "right_shoulder_outer": (PLATE, "plate"),
    "left_shoulder_outer": (PLATE, "plate"),
    "right_shoulder_front": (NYLON, "nylon"),
    "left_shoulder_front": (NYLON, "nylon"),
    "right_shoulder_rear": (NYLON, "nylon"),
    "left_shoulder_rear": (NYLON, "nylon"),
}


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(name: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(name)) % 997


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def material_pixel(base: RGBA, kind: str, x: int, y: int, seed: int) -> RGBA:
    value = (x * 73 + y * 151 + seed * 199 + (x + 11) * (y + 7) * 17) & 0xFF
    noise = value % 5 - 2
    if kind == "nylon":
        weave = 4 if (x + 2 * y + seed) % 17 in (0, 1) and (x + seed) % 4 < 2 else 0
        shadow = -3 if (2 * x + y + seed) % 23 == 0 else 0
        delta = noise + weave + shadow
    elif kind == "plate":
        delta = noise // 2 + (2 if (x + y + seed) % 19 == 0 else 0)
    elif kind == "webbing":
        delta = noise + (-3 if (y + seed) % 4 == 0 else 1)
    else:
        delta = noise * 2
    return shade(base, delta)


def paint_base_face(
    image: Image.Image,
    face: Face,
    base: RGBA,
    kind: str,
    seed: int,
) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = pixel_bounds(face)
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = material_pixel(base, kind, x, y, seed)
    return x0, y0, x1, y1


def panel_edges(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width < 3 or height < 3:
        return
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 6))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 3))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -12))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -8))


def stitches(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width < 5 or height < 4:
        return
    for x in range(x0 + 1, x1 - 1, 3):
        draw.point((x, y0 + 1), fill=STITCH)
        draw.point((x, y1 - 2), fill=SEAM)
    if height >= 6:
        for y in range(y0 + 2, y1 - 2, 3):
            draw.point((x0 + 1, y), fill=STITCH)
            draw.point((x1 - 2, y), fill=SEAM)


def molle_slots(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width < 5 or height < 5:
        return
    row_index = 0
    for y in range(y0 + 3, y1 - 1, 2):
        stagger = row_index % 2
        for x in range(x0 + 1 + stagger, x1 - 2, 3):
            draw.line((x, y, min(x + 1, x1 - 2), y), fill=SLOT)
            if y - 1 > y0:
                draw.point((x, y - 1), fill=shade(base, 5))
        row_index += 1


def collar_quilting(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width < 5 or height < 2:
        return
    center = x0 + width // 2
    draw.line((center, y0 + 1, center, y1 - 1), fill=SEAM)
    if center + 1 < x1 - 1:
        draw.line((center + 1, y0 + 1, center + 1, y1 - 1), fill=shade(base, 4))


def pouch_detail(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    width = x1 - x0
    height = y1 - y0
    if width < 4 or height < 4:
        return
    center = x0 + width // 2
    draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -8))
    draw.point((center, y0 + 2), fill=METAL)


def buckle_detail(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = bounds
    if x1 <= x0 or y1 <= y0:
        return
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=METAL, outline=SLOT)
    draw.point((x0, y0), fill=shade(METAL, 18))


def paint_detail_face(
    image: Image.Image,
    cube_name: str,
    direction: str,
    detail: str,
) -> None:
    cube = CUBES[cube_name]
    base, kind = MATERIALS[cube_name]
    face = cube_faces(cube)[direction]
    bounds = paint_base_face(image, face, base, kind, stable_seed(cube_name + direction))
    draw = ImageDraw.Draw(image)
    panel_edges(draw, bounds, base)
    if detail == "stitched":
        stitches(draw, bounds)
    elif detail == "plate":
        stitches(draw, bounds)
        molle_slots(draw, bounds, base)
    elif detail == "collar":
        collar_quilting(draw, bounds, base)
        stitches(draw, bounds)
    elif detail == "pouch":
        stitches(draw, bounds)
        pouch_detail(draw, bounds, base)
    elif detail == "buckle":
        buckle_detail(draw, bounds)


DETAIL_FACES = (
    ("carrier", "north", "stitched"),
    ("carrier", "south", "stitched"),
    ("front_plate", "north", "plate"),
    ("rear_plate", "south", "plate"),
    ("left_side", "west", "plate"),
    ("right_side", "east", "plate"),
    ("front_collar_left", "north", "collar"),
    ("front_collar_center", "north", "collar"),
    ("front_collar_right", "north", "collar"),
    ("rear_collar", "south", "collar"),
    ("left_collar", "west", "collar"),
    ("right_collar", "east", "collar"),
    ("front_strap_left", "north", "stitched"),
    ("front_strap_right", "north", "stitched"),
    ("rear_strap_left", "south", "stitched"),
    ("rear_strap_right", "south", "stitched"),
    ("lower_front", "north", "stitched"),
    ("lower_rear", "south", "stitched"),
    ("groin", "north", "pouch"),
    ("groin", "south", "pouch"),
    ("left_pouch", "west", "pouch"),
    ("right_pouch", "east", "pouch"),
    ("admin_webbing", "north", "stitched"),
    ("front_buckle", "north", "buckle"),
    ("molle_loop", "north", "stitched"),
    ("right_shoulder_core", "north", "stitched"),
    ("left_shoulder_core", "north", "stitched"),
    ("right_shoulder_top", "up", "plate"),
    ("left_shoulder_top", "up", "plate"),
    ("right_shoulder_outer", "west", "plate"),
    ("left_shoulder_outer", "east", "plate"),
    ("right_shoulder_front", "north", "stitched"),
    ("left_shoulder_front", "north", "stitched"),
    ("right_shoulder_rear", "south", "stitched"),
    ("left_shoulder_rear", "south", "stitched"),
)


def validate_uvs() -> None:
    for name, cube in CUBES.items():
        for direction, face in cube_faces(cube).items():
            if not (
                0.0 <= face.x0 <= face.x1 <= SIZE
                and 0.0 <= face.y0 <= face.y1 <= SIZE
            ):
                raise RuntimeError(f"UV overflow: {name}.{direction} = {face}")


def build_texture() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel((73, 70, 53, 255), "nylon", x, y, 7)

    for name, cube in CUBES.items():
        base, kind = MATERIALS[name]
        for direction, face in cube_faces(cube).items():
            paint_base_face(image, face, base, kind, stable_seed(name + direction))

    for cube_name, direction, detail in DETAIL_FACES:
        paint_detail_face(image, cube_name, direction, detail)
    return image


def main() -> None:
    validate_uvs()
    image = build_texture()
    if image.tobytes() != build_texture().tobytes():
        raise RuntimeError("THOR armor texture generation must be deterministic")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, format="PNG", optimize=False)

    with Image.open(OUTPUT) as written:
        if written.size != (SIZE, SIZE) or written.mode != "RGBA":
            raise RuntimeError("THOR armor texture must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError("THOR armor texture must remain fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 24:
            raise RuntimeError("THOR armor texture lost its material detail")

    print(OUTPUT)
    runtime_cube_count = sum(INSTANCE_COUNTS.get(name, 1) for name in CUBES)
    print(f"colors={len(colors)} cubes={runtime_cube_count} uv=ok alpha=255")


if __name__ == "__main__":
    main()
