from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
import hashlib
import math

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = (
    ROOT
    / "src/main/resources/assets/miningdim/textures/models/armor/plate_armor_slick_layer_1.png"
)
SIZE = 128


@dataclass(frozen=True)
class Cube:
    name: str
    u: int
    v: int
    width: float
    height: float
    depth: float
    material: str

    @property
    def footprint(self) -> tuple[int, int, int, int]:
        return (
            self.u,
            self.v,
            self.u + math.ceil(2.0 * (self.width + self.depth)),
            self.v + math.ceil(self.height + self.depth),
        )


# UV origins and dimensions mirror SlickArmorModel exactly. Symmetric and repeated geometry
# deliberately reuses the same footprint because its fabric treatment is identical.
CUBES = (
    Cube("carrier", 0, 0, 7.80, 10.40, 3.80, "nylon"),
    Cube("front_plate", 25, 0, 7.20, 8.80, 0.68, "plate"),
    Cube("rear_plate", 42, 0, 7.20, 8.90, 0.68, "plate"),
    Cube("cummerbund", 59, 0, 0.36, 5.20, 4.40, "cummerbund"),
    Cube("front_strap", 70, 0, 1.45, 4.00, 0.90, "strap"),
    Cube("rear_strap", 76, 0, 1.45, 4.00, 0.90, "strap"),
    Cube("shoulder_bridge", 82, 0, 1.45, 0.70, 4.00, "strap"),
    Cube("velcro", 94, 0, 5.70, 1.50, 0.36, "velcro"),
    Cube("center_pull", 108, 0, 0.62, 1.55, 0.38, "pull"),
    Cube("upper_seam", 111, 0, 6.20, 0.18, 0.26, "webbing"),
    Cube("front_molle", 0, 16, 6.20, 0.18, 0.28, "webbing"),
    Cube("lower_front", 14, 16, 6.80, 1.55, 0.36, "reinforcement"),
    Cube("lower_front_seam", 30, 16, 6.25, 0.18, 0.24, "stitch"),
    Cube("rear_velcro", 44, 16, 5.70, 1.25, 0.30, "velcro"),
    Cube("rear_molle", 57, 16, 6.20, 0.18, 0.24, "webbing"),
    Cube("side_molle", 71, 16, 0.20, 0.18, 3.50, "webbing"),
    Cube("side_tail", 80, 16, 0.52, 1.95, 0.30, "pull"),
    Cube("center_tail", 83, 16, 0.64, 1.65, 0.30, "pull"),
    Cube("tail_buckle", 86, 16, 0.52, 0.48, 0.34, "hardware"),
    Cube("lower_rear", 89, 16, 6.80, 1.35, 0.32, "reinforcement"),
    Cube("lower_rear_seam", 105, 16, 6.25, 0.18, 0.20, "stitch"),
    Cube("shoulder_keeper", 0, 22, 1.25, 0.20, 0.25, "webbing"),
    Cube("shoulder_buckle", 4, 22, 0.75, 0.55, 0.36, "hardware"),
)


PALETTE = {
    # Warm blacks reproduce the slightly brown charcoal nylon of the reference.
    "nylon": (35, 32, 29),
    "plate": (43, 39, 35),
    "cummerbund": (38, 34, 30),
    "strap": (49, 44, 39),
    "velcro": (28, 26, 24),
    "pull": (55, 49, 43),
    "webbing": (41, 37, 33),
    "reinforcement": (46, 41, 36),
    "stitch": (66, 59, 51),
    "hardware": (78, 73, 67),
}


def shade(color: tuple[int, int, int], delta: int) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, channel + delta)) for channel in color) + (255,)


def stable_seed(name: str) -> int:
    return sum((index + 3) * ord(character) for index, character in enumerate(name))


def material_delta(material: str, x: int, y: int, seed: int) -> int:
    noise = ((x * 73 + y * 151 + seed * 37 + (x + 9) * (y + 13) * 11) & 15) - 7
    if material in {"nylon", "plate", "reinforcement"}:
        weave = 4 if (x + 2 * y + seed) % 17 in (0, 1) else 0
        return noise // 2 + weave
    if material in {"strap", "webbing", "pull", "stitch"}:
        rib = -4 if (y + seed) % 3 == 0 else 2
        return noise // 2 + rib
    if material == "velcro":
        hook = 5 if (x * 3 + y * 5 + seed) % 11 < 3 else -2
        return noise // 3 + hook
    return noise + (5 if (x + y + seed) % 7 == 0 else 0)


def paint_cube(image: Image.Image, draw: ImageDraw.ImageDraw, cube: Cube) -> None:
    x0, y0, x1, y1 = cube.footprint
    if not (0 <= x0 < x1 <= SIZE and 0 <= y0 < y1 <= SIZE):
        raise RuntimeError(f"UV footprint outside atlas: {cube.name}={cube.footprint}")
    base = PALETTE[cube.material]
    seed = stable_seed(cube.name)
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = shade(base, material_delta(cube.material, x, y, seed))

    outline = shade(base, -11)
    highlight = shade(base, 9)
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=outline)
    if x1 - x0 >= 5:
        draw.line((x0 + 1, y0 + 1, x1 - 2, y0 + 1), fill=highlight)
    if y1 - y0 >= 4:
        draw.line((x0 + 1, y1 - 2, x1 - 2, y1 - 2), fill=shade(base, -7))

    if cube.material in {"nylon", "plate", "cummerbund", "reinforcement"}:
        for x in range(x0 + 2, x1 - 1, 3):
            if y0 + 1 < y1:
                draw.point((x, y0 + 1), fill=shade(base, 12))
            if y1 - 2 >= y0:
                draw.point((x, y1 - 2), fill=shade(base, -10))
    elif cube.material == "velcro":
        for y in range(y0 + 1, y1 - 1, 2):
            for x in range(x0 + 1, x1 - 1, 2):
                draw.point((x, y), fill=shade(base, 7 if (x + y) % 4 else -5))
    elif cube.material in {"strap", "pull"}:
        for y in range(y0 + 1, y1 - 1, 3):
            draw.line((x0 + 1, y, x1 - 2, y), fill=shade(base, -6))
    elif cube.material == "hardware" and x1 - x0 >= 2:
        draw.point((x0, y0), fill=shade(base, 15))
        draw.point((x1 - 1, y1 - 1), fill=shade(base, -18))


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (20, 18, 17, 255))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            grain = ((x * 29 + y * 53 + (x + 5) * (y + 17) * 7) & 7) - 3
            pixels[x, y] = shade((21, 19, 18), grain)

    draw = ImageDraw.Draw(image)
    for cube in CUBES:
        paint_cube(image, draw, cube)
    return image


def png_bytes(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def main() -> None:
    first = png_bytes(render())
    second = png_bytes(render())
    if first != second:
        raise RuntimeError("Slick armor texture generation is not deterministic")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(first)
    with Image.open(BytesIO(first)) as image:
        colors = len(image.getcolors(SIZE * SIZE) or ())
        alpha = image.getchannel("A").getextrema()
    print(
        f"{OUTPUT.name} cubes={len(CUBES)} colors={colors} alpha={alpha} "
        f"sha256={hashlib.sha256(first).hexdigest().upper()} deterministic=yes"
    )


if __name__ == "__main__":
    main()
