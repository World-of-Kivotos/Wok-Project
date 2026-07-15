from dataclasses import dataclass
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


JAYPC_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 11.70, 4.0),
    "front_plate": CubeUV(26, 0, 6.90, 8.20, 0.42),
    "rear_plate": CubeUV(42, 0, 6.90, 8.50, 0.42),
    "left_side": CubeUV(58, 0, 0.34, 5.50, 3.90),
    "right_side": CubeUV(68, 0, 0.34, 5.50, 3.90),
    "front_strap_left": CubeUV(0, 20, 1.15, 4.80, 0.32),
    "front_strap_right": CubeUV(4, 20, 1.15, 4.80, 0.32),
    "rear_strap_left": CubeUV(8, 20, 1.15, 4.80, 0.32),
    "rear_strap_right": CubeUV(12, 20, 1.15, 4.80, 0.32),
    "webbing_bar": CubeUV(0, 28, 6.30, 0.22, 0.12),
    "front_pouch": CubeUV(18, 20, 2.80, 2.60, 0.34),
    "left_pouch": CubeUV(26, 20, 0.42, 3.46, 1.90),
    "right_pouch": CubeUV(31, 20, 0.42, 3.46, 1.90),
    "buckle_left": CubeUV(36, 20, 0.65, 0.80, 0.40),
    "buckle_right": CubeUV(40, 20, 0.65, 0.80, 0.40),
    "right_shoulder": CubeUV(0, 32, 2.95, 0.42, 3.20),
    "left_shoulder": CubeUV(14, 32, 2.95, 0.42, 3.20),
}

JAYPC_MATERIALS = {
    "carrier": "nylon",
    "front_plate": "plate",
    "rear_plate": "plate",
    "left_side": "webbing",
    "right_side": "webbing",
    "front_strap_left": "webbing",
    "front_strap_right": "webbing",
    "rear_strap_left": "webbing",
    "rear_strap_right": "webbing",
    "webbing_bar": "webbing",
    "front_pouch": "pouch",
    "left_pouch": "pouch",
    "right_pouch": "pouch",
    "buckle_left": "metal",
    "buckle_right": "metal",
    "right_shoulder": "webbing",
    "left_shoulder": "webbing",
}

PACA_CUBES = {
    "carrier": CubeUV(0, 48, 8.0, 11.50, 4.0),
    "front_panel": CubeUV(26, 48, 7.60, 9.60, 0.30),
    "rear_panel": CubeUV(44, 48, 7.60, 9.60, 0.30),
    "left_wrap": CubeUV(62, 48, 0.26, 7.30, 3.70),
    "right_wrap": CubeUV(72, 48, 0.26, 7.30, 3.70),
    "front_strap_left": CubeUV(0, 68, 1.30, 4.90, 0.28),
    "front_strap_right": CubeUV(4, 68, 1.30, 4.90, 0.28),
    "rear_strap_left": CubeUV(8, 68, 1.30, 4.90, 0.28),
    "rear_strap_right": CubeUV(12, 68, 1.30, 4.90, 0.28),
    "velcro_panel": CubeUV(16, 68, 4.20, 1.90, 0.24),
    "soft_band": CubeUV(26, 68, 7.10, 0.55, 0.18),
    "right_shoulder": CubeUV(0, 78, 3.0, 0.50, 3.30),
    "left_shoulder": CubeUV(14, 78, 3.0, 0.50, 3.30),
}

PACA_MATERIALS = {
    "carrier": "soft",
    "front_panel": "soft",
    "rear_panel": "soft",
    "left_wrap": "mesh",
    "right_wrap": "mesh",
    "front_strap_left": "soft",
    "front_strap_right": "soft",
    "rear_strap_left": "soft",
    "rear_strap_right": "soft",
    "velcro_panel": "velcro",
    "soft_band": "velcro",
    "right_shoulder": "mesh",
    "left_shoulder": "mesh",
}

PALETTES: dict[str, dict[str, RGBA]] = {
    "jaypc_olive": {
        "canvas": (48, 55, 37, 255),
        "nylon": (82, 91, 58, 255),
        "plate": (72, 84, 49, 255),
        "webbing": (62, 75, 43, 255),
        "pouch": (91, 92, 64, 255),
        "metal": (47, 50, 42, 255),
        "edge": (38, 45, 31, 255),
        "stitch": (125, 126, 86, 255),
        "slot": (31, 38, 27, 255),
    },
    "jaypc_black": {
        "canvas": (22, 24, 24, 255),
        "nylon": (39, 42, 42, 255),
        "plate": (32, 35, 36, 255),
        "webbing": (27, 30, 31, 255),
        "pouch": (43, 43, 42, 255),
        "metal": (57, 59, 56, 255),
        "edge": (15, 17, 18, 255),
        "stitch": (75, 77, 75, 255),
        "slot": (11, 13, 14, 255),
    },
    "paca": {
        "canvas": (25, 29, 30, 255),
        "soft": (48, 52, 53, 255),
        "mesh": (37, 52, 53, 255),
        "velcro": (31, 34, 35, 255),
        "edge": (18, 21, 22, 255),
        "stitch": (82, 86, 85, 255),
        "slot": (14, 17, 18, 255),
    },
}


def clamp_color(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(name: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(name)) % 1543


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def material_pixel(base: RGBA, kind: str, x: int, y: int, seed: int) -> RGBA:
    value = (x * 73 + y * 151 + seed * 197 + (x + 13) * (y + 5) * 19) & 0xFF
    noise = value % 7 - 3
    if kind in {"nylon", "soft", "pouch"}:
        weave = 3 if (x + 2 * y + seed) % 13 == 0 else 0
        shadow = -3 if (2 * x + y + seed) % 19 == 0 else 0
        delta = noise + weave + shadow
    elif kind == "plate":
        delta = noise // 2 + (2 if (x + y + seed) % 17 == 0 else 0)
    elif kind == "webbing":
        delta = noise + (-4 if (y + seed) % 4 == 0 else 1)
    elif kind == "mesh":
        delta = -7 if (x + y + seed) % 3 == 0 else noise
    elif kind == "velcro":
        delta = -5 if (x * 3 + y + seed) % 5 < 2 else noise
    else:
        delta = noise * 2
    return clamp_color(base, delta)


def paint_face(
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


def edge_face(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    palette: dict[str, RGBA],
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 3 or y1 - y0 < 3:
        return
    draw.line((x0, y0, x1 - 1, y0), fill=clamp_color(palette["stitch"], -8))
    draw.line((x0, y0, x0, y1 - 1), fill=palette["stitch"])
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=palette["edge"])
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=palette["edge"])


def stitched_face(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    palette: dict[str, RGBA],
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 4:
        return
    for x in range(x0 + 1, x1 - 1, 3):
        draw.point((x, y0 + 1), fill=palette["stitch"])
        draw.point((x, y1 - 2), fill=palette["edge"])


def molle_face(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    palette: dict[str, RGBA],
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 5 or y1 - y0 < 5:
        return
    row = 0
    for y in range(y0 + 2, y1 - 1, 2):
        offset = row % 2
        for x in range(x0 + 1 + offset, x1 - 2, 3):
            draw.line((x, y, min(x + 1, x1 - 2), y), fill=palette["slot"])
        row += 1


def pouch_face(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    palette: dict[str, RGBA],
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 4:
        return
    flap_y = y0 + max(1, (y1 - y0) // 3)
    draw.line((x0 + 1, flap_y, x1 - 2, flap_y), fill=palette["edge"])
    draw.point(((x0 + x1) // 2, min(y1 - 2, flap_y + 1)), fill=palette["metal"])


def velcro_face(
    draw: ImageDraw.ImageDraw,
    bounds: tuple[int, int, int, int],
    palette: dict[str, RGBA],
) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 3 or y1 - y0 < 2:
        return
    for y in range(y0 + 1, y1 - 1):
        for x in range(x0 + 1, x1 - 1):
            if (x + y) % 2 == 0:
                draw.point((x, y), fill=clamp_color(palette["slot"], 7))


def paint_layout(
    cubes: dict[str, CubeUV],
    materials: dict[str, str],
    palette: dict[str, RGBA],
) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel(palette["canvas"], "nylon", x, y, 17)

    for name, cube in cubes.items():
        kind = materials[name]
        base = palette[kind]
        for direction, face in cube_faces(cube).items():
            bounds = paint_face(image, face, base, kind, stable_seed(name + direction))
            edge_face(ImageDraw.Draw(image), bounds, palette)
    return image


def detail_jaypc(image: Image.Image, palette: dict[str, RGBA]) -> None:
    draw = ImageDraw.Draw(image)
    details = (
        ("carrier", "north", "stitched"),
        ("carrier", "south", "stitched"),
        ("front_plate", "north", "molle"),
        ("rear_plate", "south", "molle"),
        ("left_side", "west", "molle"),
        ("right_side", "east", "molle"),
        ("front_strap_left", "north", "stitched"),
        ("front_strap_right", "north", "stitched"),
        ("rear_strap_left", "south", "stitched"),
        ("rear_strap_right", "south", "stitched"),
        ("webbing_bar", "north", "molle"),
        ("front_pouch", "north", "pouch"),
        ("left_pouch", "west", "pouch"),
        ("right_pouch", "east", "pouch"),
        ("right_shoulder", "up", "stitched"),
        ("left_shoulder", "up", "stitched"),
    )
    for cube_name, direction, detail in details:
        bounds = pixel_bounds(cube_faces(JAYPC_CUBES[cube_name])[direction])
        if detail == "molle":
            molle_face(draw, bounds, palette)
        elif detail == "pouch":
            pouch_face(draw, bounds, palette)
        else:
            stitched_face(draw, bounds, palette)

    for buckle in ("buckle_left", "buckle_right"):
        bounds = pixel_bounds(cube_faces(JAYPC_CUBES[buckle])["north"])
        x0, y0, x1, y1 = bounds
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=palette["metal"], outline=palette["slot"])


def detail_paca(image: Image.Image, palette: dict[str, RGBA]) -> None:
    draw = ImageDraw.Draw(image)
    for cube_name, direction in (
        ("carrier", "north"),
        ("carrier", "south"),
        ("front_panel", "north"),
        ("rear_panel", "south"),
        ("front_strap_left", "north"),
        ("front_strap_right", "north"),
        ("rear_strap_left", "south"),
        ("rear_strap_right", "south"),
        ("right_shoulder", "up"),
        ("left_shoulder", "up"),
    ):
        stitched_face(draw, pixel_bounds(cube_faces(PACA_CUBES[cube_name])[direction]), palette)

    for cube_name, direction in (
        ("left_wrap", "west"),
        ("right_wrap", "east"),
    ):
        bounds = pixel_bounds(cube_faces(PACA_CUBES[cube_name])[direction])
        x0, y0, x1, y1 = bounds
        for y in range(y0 + 1, y1 - 1, 2):
            for x in range(x0 + 1, x1 - 1, 2):
                draw.point((x, y), fill=palette["slot"])

    velcro_face(draw, pixel_bounds(cube_faces(PACA_CUBES["velcro_panel"])["north"]), palette)
    velcro_face(draw, pixel_bounds(cube_faces(PACA_CUBES["soft_band"])["north"]), palette)


def validate_uvs(name: str, cubes: dict[str, CubeUV]) -> None:
    for cube_name, cube in cubes.items():
        for direction, face in cube_faces(cube).items():
            if not (
                0.0 <= face.x0 <= face.x1 <= SIZE
                and 0.0 <= face.y0 <= face.y1 <= SIZE
            ):
                raise RuntimeError(f"UV overflow in {name}: {cube_name}.{direction} = {face}")


def write_texture(
    file_name: str,
    cubes: dict[str, CubeUV],
    materials: dict[str, str],
    palette_name: str,
    detailer,
    model_cuboids: int,
) -> None:
    palette = PALETTES[palette_name]
    image = paint_layout(cubes, materials, palette)
    detailer(image, palette)

    duplicate = paint_layout(cubes, materials, palette)
    detailer(duplicate, palette)
    if image.tobytes() != duplicate.tobytes():
        raise RuntimeError(f"Texture generation is not deterministic: {file_name}")

    output = OUTPUT_DIR / file_name
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG", optimize=False)

    with Image.open(output) as written:
        if written.size != (SIZE, SIZE) or written.mode != "RGBA":
            raise RuntimeError(f"{file_name} must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError(f"{file_name} must remain fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 24:
            raise RuntimeError(f"{file_name} lost its fabric detail")

    print(f"{output} colors={len(colors)} cuboids={model_cuboids} uv=ok alpha=255")


def main() -> None:
    validate_uvs("jaypc", JAYPC_CUBES)
    validate_uvs("paca", PACA_CUBES)
    write_texture(
        "plate_armor_jaypc_olive_layer_1.png",
        JAYPC_CUBES,
        JAYPC_MATERIALS,
        "jaypc_olive",
        detail_jaypc,
        19,
    )
    write_texture(
        "plate_armor_jaypc_black_layer_1.png",
        JAYPC_CUBES,
        JAYPC_MATERIALS,
        "jaypc_black",
        detail_jaypc,
        19,
    )
    write_texture(
        "plate_armor_paca_layer_1.png",
        PACA_CUBES,
        PACA_MATERIALS,
        "paca",
        detail_paca,
        14,
    )


if __name__ == "__main__":
    main()
