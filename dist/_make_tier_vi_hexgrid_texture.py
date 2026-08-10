from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
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
    / "plate_armor_hexgrid_layer_1.png"
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
    material: str


CUBES = {
    "front_plate": CubeUV(0, 0, 6.90, 9.10, 0.64, "plate"),
    "rear_plate": CubeUV(16, 0, 6.80, 9.00, 0.62, "plate"),
    "front_strap": CubeUV(32, 0, 1.48, 4.02, 0.44, "strap"),
    "rear_strap": CubeUV(37, 0, 1.48, 4.02, 0.44, "strap"),
    "top_bridge": CubeUV(42, 0, 1.36, 0.48, 4.60, "strap"),
    "side_sheet": CubeUV(56, 0, 0.54, 5.45, 4.24, "mesh"),
    "side_rail": CubeUV(66, 0, 0.60, 0.74, 3.68, "webbing"),
    "front_belt": CubeUV(75, 0, 6.84, 0.78, 0.26, "webbing"),
    "rear_belt": CubeUV(90, 0, 6.74, 0.78, 0.26, "webbing"),
    "vertical_rim": CubeUV(0, 16, 0.20, 8.70, 0.24, "rim"),
    "horizontal_rim": CubeUV(4, 16, 6.60, 0.20, 0.20, "rim"),
    "left_brace": CubeUV(20, 16, 0.20, 4.30, 0.30, "strap"),
    "right_brace": CubeUV(24, 16, 0.20, 4.30, 0.26, "strap"),
    "hex_top": CubeUV(0, 36, 0.72, 0.13, 0.22, "honeycomb"),
    "hex_bottom": CubeUV(4, 36, 0.72, 0.13, 0.21, "honeycomb"),
    "hex_upper_left": CubeUV(8, 36, 0.14, 0.56, 0.20, "honeycomb"),
    "hex_lower_left": CubeUV(12, 36, 0.14, 0.56, 0.19, "honeycomb"),
    "hex_upper_right": CubeUV(16, 36, 0.14, 0.56, 0.18, "honeycomb"),
    "hex_lower_right": CubeUV(20, 36, 0.14, 0.56, 0.17, "honeycomb"),
}

PALETTE: dict[str, RGBA] = {
    "plate": (30, 33, 35, 255),
    "strap": (39, 42, 45, 255),
    "mesh": (24, 27, 29, 255),
    "webbing": (34, 37, 39, 255),
    "rim": (48, 52, 55, 255),
    "honeycomb": (57, 61, 64, 255),
}

EDGE_LIGHT = (73, 77, 80, 255)
EDGE_DARK = (12, 14, 15, 255)
CELL_DARK = (15, 17, 18, 255)


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(name: str) -> int:
    return int.from_bytes(sha256(name.encode("utf-8")).digest()[:4], "big")


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


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def footprint(cube: CubeUV) -> Face:
    return Face(
        cube.u,
        cube.v,
        cube.u + 2.0 * (cube.width + cube.depth),
        cube.v + cube.height + cube.depth,
    )


def noise_at(x: int, y: int, seed: int) -> int:
    value = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return (value ^ (value >> 16)) & 0xFF


def material_pixel(material: str, x: int, y: int, seed: int) -> RGBA:
    base = PALETTE[material]
    noise = noise_at(x, y, seed) % 9 - 4
    if material in {"strap", "webbing"}:
        weave = 5 if (x + 2 * y + seed) % 11 in (0, 1) else -3 if (y + seed) % 5 == 0 else 0
        return shade(base, noise + weave)
    if material == "mesh":
        perforation = -9 if (x + y + seed) % 3 == 0 else 2
        return shade(base, noise + perforation)
    if material == "honeycomb":
        sheen = 7 if (x * 3 + y + seed) % 7 < 2 else -2
        return shade(base, noise + sheen)
    if material == "rim":
        return shade(base, noise + (5 if (x + seed) % 5 == 0 else -1))
    grain = 3 if (x + y * 2 + seed) % 17 < 3 else -2
    return shade(base, noise + grain)


def paint_face(image: Image.Image, face: Face, material: str, seed: int) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    x0, y0, x1, y1 = bounds
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = material_pixel(material, x, y, seed)
    return bounds


def draw_edges(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], material: str) -> None:
    x0, y0, x1, y1 = bounds
    if x1 <= x0 or y1 <= y0:
        return
    base = PALETTE[material]
    if y1 - y0 == 1:
        draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 9))
        return
    if x1 - x0 == 1:
        draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 5))
        return
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 10))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 5))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -13))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -9))


def decorate_face(
    draw: ImageDraw.ImageDraw,
    cube_name: str,
    direction: str,
    bounds: tuple[int, int, int, int],
    material: str,
) -> None:
    x0, y0, x1, y1 = bounds
    draw_edges(draw, bounds, material)
    width = x1 - x0
    height = y1 - y0

    if cube_name == "front_plate" and direction == "north":
        # Recessed dark pixels continue the honeycomb read beneath the raised six-bar cells.
        for row, y in enumerate(range(y0 + 1, y1 - 1)):
            offset = row & 1
            for x in range(x0 + 1 + offset, x1 - 1, 2):
                draw.point((x, y), fill=CELL_DARK)
                if x + 1 < x1 - 1 and (row + x) % 3 == 0:
                    draw.point((x + 1, y), fill=shade(CELL_DARK, 5))
    elif material in {"strap", "webbing"} and width >= 3 and height >= 3:
        for y in range(y0 + 1, y1 - 1, 2):
            draw.line((x0 + 1, y, x1 - 2, y), fill=shade(PALETTE[material], -10))
    elif material == "mesh" and width >= 2 and height >= 3:
        for y in range(y0 + 1, y1 - 1, 2):
            for x in range(x0 + ((y - y0) & 1), x1, 2):
                draw.point((x, y), fill=EDGE_DARK)
    elif material == "honeycomb":
        if width >= 2:
            draw.line((x0, y0, x1 - 1, y0), fill=EDGE_LIGHT)
        if height >= 2:
            draw.point((x0, y1 - 1), fill=EDGE_DARK)


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (20, 22, 24, 255))
    pixels = image.load()
    background_seed = stable_seed("hexgrid_background")
    for y in range(SIZE):
        for x in range(SIZE):
            value = noise_at(x, y, background_seed) % 7 - 3
            pixels[x, y] = shade((20, 22, 24, 255), value)

    draw = ImageDraw.Draw(image)
    for cube_name, cube in CUBES.items():
        seed = stable_seed(cube_name)
        for direction, face in cube_faces(cube).items():
            bounds = paint_face(image, face, cube.material, seed + stable_seed(direction))
            decorate_face(draw, cube_name, direction, bounds, cube.material)

    # Deliberately paint each atlas footprint boundary; this makes accidental UV drift visible.
    for cube in CUBES.values():
        draw_edges(draw, pixel_bounds(footprint(cube)), cube.material)
    return image


def png_bytes(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def validate(image: Image.Image, encoded: bytes) -> None:
    if image.size != (SIZE, SIZE):
        raise RuntimeError(f"unexpected texture dimensions: {image.size}")
    alpha = image.getchannel("A")
    if alpha.getextrema() != (255, 255):
        raise RuntimeError(f"texture must be fully opaque, got alpha range {alpha.getextrema()}")
    colors = image.getcolors(SIZE * SIZE) or []
    if len(colors) < 32:
        raise RuntimeError(f"texture detail collapsed to only {len(colors)} colors")
    if encoded != png_bytes(render()):
        raise RuntimeError("Hexgrid texture generation is not deterministic")


def main() -> None:
    image = render()
    encoded = png_bytes(image)
    validate(image, encoded)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(encoded)
    colors = len(image.getcolors(SIZE * SIZE) or [])
    print(
        f"{OUTPUT.name} size={SIZE}x{SIZE} alpha=255 colors={colors} "
        f"sha256={sha256(encoded).hexdigest().upper()} deterministic=yes"
    )


if __name__ == "__main__":
    main()
