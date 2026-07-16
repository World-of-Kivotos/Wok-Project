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
    / "src/main/resources/assets/miningdim/textures/models/armor"
    / "plate_armor_stich_defense_mod2_layer_1.png"
)
SIZE = 128
RGBA = tuple[int, int, int, int]


@dataclass(frozen=True)
class Cube:
    name: str
    u: float
    v: float
    width: float
    height: float
    depth: float
    material: str
    detail: str = "panel"


@dataclass(frozen=True)
class Face:
    x0: float
    y0: float
    x1: float
    y1: float


def rgba(red: int, green: int, blue: int) -> RGBA:
    return red, green, blue, 255


PALETTES: dict[str, tuple[RGBA, ...]] = {
    "carrier": (
        rgba(119, 108, 72), rgba(102, 101, 71), rgba(132, 117, 76),
        rgba(86, 91, 65), rgba(143, 126, 84),
    ),
    "side": (rgba(76, 82, 60), rgba(87, 91, 61), rgba(66, 73, 55)),
    "strap": (rgba(135, 120, 78), rgba(113, 107, 73), rgba(151, 133, 88)),
    "webbing": (rgba(111, 104, 68), rgba(91, 92, 64), rgba(132, 116, 74)),
    "pouch": (
        rgba(112, 108, 72), rgba(89, 96, 65), rgba(126, 113, 72),
        rgba(75, 83, 59), rgba(139, 122, 80),
    ),
    "magazine": (rgba(43, 50, 40), rgba(52, 60, 46), rgba(35, 41, 34)),
    "cylinder": (rgba(172, 172, 155), rgba(203, 198, 171), rgba(119, 99, 116)),
    "cord": (rgba(31, 34, 29), rgba(44, 46, 38)),
    "metal": (rgba(105, 109, 96), rgba(143, 142, 119)),
    "blue": (rgba(28, 80, 112), rgba(36, 104, 137), rgba(18, 58, 87)),
    "badge_back": (rgba(53, 52, 39), rgba(67, 63, 43), rgba(43, 42, 34)),
    "badge": (rgba(205, 166, 34), rgba(236, 195, 47), rgba(43, 40, 28)),
}


CUBES = (
    Cube("front", 0, 0, 7.10, 9.65, 0.56, "carrier", "molle"),
    Cube("rear", 18, 0, 7.10, 9.65, 0.56, "carrier", "panel"),
    Cube("left_side", 36, 0, 0.46, 6.95, 4.16, "side", "mesh"),
    Cube("right_side", 47, 0, 0.46, 6.95, 4.16, "side", "mesh"),
    Cube("front_strap", 58, 0, 1.55, 4.25, 0.62, "strap", "stitched"),
    Cube("rear_strap", 66, 0, 1.55, 4.25, 0.62, "strap", "stitched"),
    Cube("top_strap", 74, 0, 1.45, 0.62, 4.68, "strap", "stitched"),
    Cube("upper_webbing", 88, 0, 6.40, 0.34, 0.28, "webbing", "webbing"),
    Cube("hook_loop", 102, 0, 5.90, 0.48, 0.26, "webbing", "hook"),
    Cube("badge_back", 116, 0, 1.50, 1.20, 0.30, "badge_back", "badge_back"),
    Cube("badge_top", 116, 8, 0.24, 0.22, 0.22, "badge", "badge"),
    Cube("badge_middle", 120, 8, 0.68, 0.20, 0.21, "badge", "badge"),
    Cube("badge_bottom", 124, 8, 1.16, 0.20, 0.20, "badge", "badge"),
    Cube("lower_front", 0, 18, 7.80, 1.30, 0.46, "webbing", "stitched"),
    Cube("lower_rear", 18, 18, 7.80, 1.30, 0.46, "webbing", "stitched"),
    Cube("left_outer", 36, 18, 2.40, 5.15, 1.28, "pouch", "pouch"),
    Cube("left_outer_lid", 44, 18, 2.52, 1.16, 1.46, "pouch", "lid"),
    Cube("left_outer_rib", 52, 18, 0.24, 3.62, 0.22, "webbing", "webbing"),
    Cube("left_outer_pull", 55, 18, 0.30, 3.95, 0.22, "webbing", "webbing"),
    Cube("left_mag", 59, 18, 1.34, 5.05, 1.10, "pouch", "magazine"),
    Cube("left_mag_lid", 65, 18, 1.42, 1.02, 1.28, "pouch", "lid"),
    Cube("medical", 71, 18, 1.94, 4.88, 1.18, "pouch", "medical"),
    Cube("medical_lid", 78, 18, 2.02, 1.05, 1.36, "pouch", "lid"),
    Cube("center_pouch", 85, 18, 1.42, 4.15, 1.08, "pouch", "pouch"),
    Cube("center_lid", 91, 18, 1.50, 0.98, 1.26, "pouch", "lid"),
    Cube("right_mag_a", 97, 18, 1.28, 5.25, 1.20, "magazine", "magazine"),
    Cube("right_mag_b", 103, 18, 1.22, 5.05, 1.22, "magazine", "magazine"),
    Cube("right_mag_lid_a", 109, 18, 1.38, 0.90, 1.35, "pouch", "lid"),
    Cube("right_mag_lid_b", 115, 18, 1.32, 0.90, 1.38, "pouch", "lid"),
    Cube("right_outer", 0, 36, 2.18, 4.90, 1.24, "pouch", "pouch"),
    Cube("right_outer_lid", 8, 36, 2.30, 1.12, 1.46, "pouch", "lid"),
    Cube("right_outer_pull", 16, 36, 0.26, 3.72, 0.22, "webbing", "webbing"),
    Cube("cylinder_a", 20, 36, 0.48, 4.32, 0.48, "cylinder", "cylinder"),
    Cube("cylinder_b", 24, 36, 0.48, 4.32, 0.48, "cylinder", "cylinder"),
    Cube("cylinder_c", 28, 36, 0.48, 4.32, 0.48, "cylinder", "cylinder"),
    Cube("cylinder_band", 32, 36, 1.78, 0.38, 0.24, "webbing", "webbing"),
    Cube("lanyard", 38, 36, 0.22, 3.45, 0.22, "cord", "cord"),
    Cube("buckle", 42, 36, 0.72, 0.72, 0.30, "metal", "buckle"),
    Cube("release_tab", 46, 36, 0.36, 2.15, 0.28, "blue", "tab"),
    Cube("retention_rib", 52, 36, 4.50, 0.22, 0.26, "webbing", "webbing"),
)


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def seed_for(name: str) -> int:
    return int.from_bytes(sha256(name.encode("utf-8")).digest()[:4], "big")


def cube_faces(cube: Cube) -> dict[str, Face]:
    u, v, width, height, depth = cube.u, cube.v, cube.width, cube.height, cube.depth
    return {
        "down": Face(u + depth, v, u + depth + width, v + depth),
        "up": Face(u + depth + width, v, u + depth + width * 2, v + depth),
        "west": Face(u, v + depth, u + depth, v + depth + height),
        "north": Face(u + depth, v + depth, u + depth + width, v + depth + height),
        "east": Face(u + depth + width, v + depth, u + depth * 2 + width, v + depth + height),
        "south": Face(u + depth * 2 + width, v + depth, u + depth * 2 + width * 2, v + depth + height),
    }


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return max(0, floor(face.x0)), max(0, floor(face.y0)), min(SIZE, ceil(face.x1)), min(SIZE, ceil(face.y1))


def material_pixel(material: str, x: int, y: int, seed: int) -> RGBA:
    palette = PALETTES[material]
    coarse = ((x // 3) * 97 + (y // 3) * 193 + seed * 17) & 0xFF
    base = palette[coarse * len(palette) // 256]
    noise = ((x * 73 + y * 151 + seed * 31 + x * y * 7) & 15) - 7
    if material in {"carrier", "pouch", "strap"}:
        weave = 4 if (x + 2 * y + seed) % 11 in (0, 1) else -2 if (2 * x - y + seed) % 17 == 0 else 0
        noise += weave
    elif material == "side":
        noise += -12 if (x + y + seed) % 3 == 0 else 1
    elif material == "magazine":
        noise = noise // 2 + (5 if (x + seed) % 4 == 0 else -2)
    return shade(base, noise)


def paint_face(image: Image.Image, face: Face, material: str, seed: int) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    pixels = image.load()
    for y in range(bounds[1], bounds[3]):
        for x in range(bounds[0], bounds[2]):
            pixels[x, y] = material_pixel(material, x, y, seed)
    return bounds


def decorate(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], cube: Cube) -> None:
    x0, y0, x1, y1 = bounds
    if x1 <= x0 or y1 <= y0:
        return
    base = PALETTES[cube.material][0]
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=shade(base, -18))
    if x1 - x0 >= 3 and y1 - y0 >= 3:
        draw.line((x0 + 1, y0 + 1, x1 - 2, y0 + 1), fill=shade(base, 17))
        draw.line((x0 + 1, y1 - 2, x1 - 2, y1 - 2), fill=shade(base, -22))
    if cube.detail in {"stitched", "panel", "pouch", "medical", "lid"}:
        for x in range(x0 + 1, x1 - 1, 2):
            draw.point((x, y0 + min(1, y1 - y0 - 1)), fill=shade(base, 24))
    if cube.detail in {"webbing", "molle"} and y1 - y0 >= 2:
        for y in range(y0 + 1, y1 - 1, 2):
            draw.line((x0 + 1, y, max(x0 + 1, x1 - 2), y), fill=shade(base, -24))
    if cube.detail in {"pouch", "medical", "magazine"} and x1 - x0 >= 2:
        center = x0 + (x1 - x0) // 2
        draw.line((center, y0 + 1, center, max(y0 + 1, y1 - 2)), fill=shade(base, -20))
    if cube.detail == "medical" and x1 - x0 >= 2 and y1 - y0 >= 3:
        cy = y0 + (y1 - y0) // 2
        draw.line((x0, cy, x1 - 1, cy), fill=rgba(45, 48, 39))
    if cube.detail == "cylinder":
        for y in range(y0 + 1, y1, 2):
            draw.line((x0, y, x1 - 1, y), fill=rgba(105, 54, 91))
    if cube.detail == "badge_back":
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=rgba(49, 47, 35), outline=rgba(25, 25, 21))
    if cube.detail == "badge":
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=rgba(221, 179, 35), outline=rgba(37, 35, 26))
    if cube.detail == "buckle":
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=rgba(126, 128, 111), outline=rgba(48, 51, 45))
    if cube.detail == "tab":
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=rgba(28, 91, 130), outline=rgba(13, 42, 64))


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), rgba(75, 77, 57))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel("carrier", x, y, 11)

    for cube in CUBES:
        for direction, face in cube_faces(cube).items():
            paint_face(image, face, cube.material, seed_for(cube.name + direction))
        decorate(ImageDraw.Draw(image), pixel_bounds(cube_faces(cube)["north"]), cube)
    return image


def png_bytes(image: Image.Image) -> bytes:
    stream = BytesIO()
    image.save(stream, format="PNG", optimize=False, compress_level=9)
    return stream.getvalue()


def validate_uvs() -> None:
    for cube in CUBES:
        for direction, face in cube_faces(cube).items():
            if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                raise RuntimeError(f"UV overflow: {cube.name}.{direction}={face}")


def main() -> None:
    validate_uvs()
    first = png_bytes(render())
    second = png_bytes(render())
    if first != second:
        raise RuntimeError("Stich Defense texture generation must be deterministic")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(first)
    with Image.open(OUTPUT) as written:
        if written.size != (SIZE, SIZE) or written.mode != "RGBA":
            raise RuntimeError("Stich Defense texture must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError("Stich Defense texture must remain fully opaque")
        colors = written.getcolors(SIZE * SIZE) or []
        if len(colors) < 48:
            raise RuntimeError("Stich Defense texture lost material detail")
    print(f"{OUTPUT.name} colors={len(colors)} sha256={sha256(first).hexdigest().upper()} deterministic=yes")


if __name__ == "__main__":
    main()
