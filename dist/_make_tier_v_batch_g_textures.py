from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
import hashlib
import math

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/miningdim/textures/models/armor/plate_armor_korund_vm_black_layer_1.png"
SIZE = 128


@dataclass(frozen=True)
class Cube:
    name: str
    u: int
    v: int
    width: float
    height: float
    depth: float
    kind: str

    @property
    def footprint(self) -> tuple[int, int, int, int]:
        return (
            self.u,
            self.v,
            self.u + math.ceil(2.0 * (self.width + self.depth)),
            self.v + math.ceil(self.height + self.depth),
        )


CUBES = (
    Cube("front_upper", 0, 0, 6.70, 4.60, 0.52, "shell"),
    Cube("rear_upper", 16, 0, 6.70, 4.60, 0.52, "shell"),
    Cube("front_lower", 32, 0, 7.70, 6.05, 0.60, "shell"),
    Cube("rear_lower", 50, 0, 7.70, 6.05, 0.60, "shell"),
    Cube("left_wrap", 68, 0, 0.46, 9.10, 4.00, "side"),
    Cube("right_wrap", 78, 0, 0.46, 9.10, 4.00, "side"),
    Cube("collar_front_left", 88, 0, 2.35, 1.75, 0.52, "collar"),
    Cube("collar_front_right", 95, 0, 2.35, 1.75, 0.52, "collar"),
    Cube("collar_rear", 102, 0, 6.70, 1.85, 0.55, "collar"),
    Cube("collar_left", 0, 15, 0.44, 1.80, 4.80, "collar"),
    Cube("collar_right", 12, 15, 0.44, 1.80, 4.80, "collar"),
    Cube("strap_left", 24, 15, 1.20, 3.40, 0.34, "strap"),
    Cube("strap_right", 29, 15, 1.20, 3.40, 0.34, "strap"),
    Cube("buckle_left", 34, 15, 1.00, 0.70, 0.30, "metal"),
    Cube("buckle_right", 38, 15, 1.00, 0.70, 0.30, "metal"),
    Cube("tail_left", 42, 15, 0.40, 2.40, 0.20, "strap"),
    Cube("tail_right", 45, 15, 0.40, 2.40, 0.20, "strap"),
    Cube("upper_seam", 48, 15, 6.20, 0.18, 0.20, "seam"),
    Cube("lower_seam", 62, 15, 7.10, 0.20, 0.24, "seam"),
    Cube("center_seam", 78, 15, 0.20, 5.20, 0.28, "seam"),
    Cube("left_seam", 80, 15, 0.18, 5.20, 0.28, "seam"),
    Cube("right_seam", 82, 15, 0.18, 5.20, 0.28, "seam"),
    Cube("apron_left_upper", 84, 15, 3.70, 3.55, 0.58, "apron"),
    Cube("apron_right_upper", 94, 15, 3.70, 3.55, 0.58, "apron"),
    Cube("apron_left_lower", 104, 15, 3.30, 1.45, 0.54, "apron"),
    Cube("apron_right_lower", 113, 15, 3.30, 1.45, 0.54, "apron"),
    Cube("apron_top", 0, 23, 7.20, 0.55, 0.25, "strap"),
    Cube("hip_left", 17, 23, 0.45, 3.30, 0.50, "apron"),
    Cube("hip_right", 20, 23, 0.45, 3.30, 0.50, "apron"),
)

PALETTE = {
    "shell": (37, 40, 43),
    "side": (31, 34, 37),
    "collar": (24, 27, 30),
    "strap": (47, 49, 52),
    "metal": (151, 155, 154),
    "seam": (18, 20, 22),
    "apron": (34, 37, 40),
}


def shade(color: tuple[int, int, int], delta: int) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, channel + delta)) for channel in color) + (255,)


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (18, 20, 22, 255))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            noise = ((x * 73 + y * 151 + (x + 7) * (y + 19) * 11) & 15) - 8
            pixels[x, y] = shade((22, 24, 26), noise // 3)

    draw = ImageDraw.Draw(image)
    for index, cube in enumerate(CUBES):
        x0, y0, x1, y1 = cube.footprint
        base = PALETTE[cube.kind]
        for y in range(y0, y1):
            for x in range(x0, x1):
                grain = ((x * 31 + y * 47 + index * 59 + x * y * 3) & 15) - 7
                sheen = 4 if cube.kind in {"shell", "collar", "apron"} and (x + y * 2 + index) % 17 < 3 else 0
                cool = 2 if (x * 5 + y * 3 + index) % 11 < 3 else 0
                pixels[x, y] = (
                    max(0, min(255, base[0] + grain + sheen)),
                    max(0, min(255, base[1] + grain + sheen + cool)),
                    max(0, min(255, base[2] + grain + sheen + cool * 2)),
                    255,
                )
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=shade(base, -12))
        if x1 - x0 >= 7 and y1 - y0 >= 4:
            draw.line((x0 + 2, y0 + 1, x1 - 3, y0 + 1), fill=shade(base, 10))
            draw.line((x0 + 2, y1 - 2, x1 - 3, y1 - 2), fill=shade(base, -8))
        if cube.kind in {"shell", "apron"} and x1 - x0 >= 8:
            for x in range(x0 + 2, x1 - 1, 3):
                draw.point((x, y0 + 1), fill=shade(base, 13))
                draw.point((x, y1 - 2), fill=shade(base, -9))
        if cube.kind == "apron" and y1 - y0 >= 4:
            draw.line((x0 + 1, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 9))
            draw.line((x1 - 2, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -9))
        if cube.kind == "metal":
            draw.line((x0, y0, x1 - 1, y1 - 1), fill=(157, 160, 158, 255))
    return image


def png_bytes(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def main() -> None:
    first = png_bytes(render())
    second = png_bytes(render())
    if first != second:
        raise RuntimeError("Tier V batch G texture generation is not deterministic")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(first)
    print(f"{OUTPUT.name} cubes={len(CUBES)} colors={len(render().getcolors(SIZE * SIZE) or ())} "
          f"sha256={hashlib.sha256(first).hexdigest().upper()} deterministic=yes")


if __name__ == "__main__":
    main()
