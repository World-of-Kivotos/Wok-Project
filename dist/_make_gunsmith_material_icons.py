from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
ITEM_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item"
OUT_DIR = ROOT / "dist"
SIZE = 64
SCALE = 4


def rgba(r: int, g: int, b: int, a: int = 255) -> tuple[int, int, int, int]:
    return r, g, b, a


def scaled(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def gear_points(cx: float, cy: float, teeth: int, outer: float, root: float) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    for tooth in range(teeth):
        center = -math.pi / 2.0 + tooth * math.tau / teeth
        for offset, radius in (
            (-0.38, root),
            (-0.20, outer),
            (0.20, outer),
            (0.38, root),
        ):
            angle = center + offset * math.tau / teeth
            points.append((cx + math.cos(angle) * radius, cy + math.sin(angle) * radius))
    return points


def draw_gear(draw: ImageDraw.ImageDraw, cx: float, cy: float, teeth: int,
              outer: float, root: float, hole: float,
              body_color: tuple[int, int, int, int],
              inner_color: tuple[int, int, int, int],
              highlight_color: tuple[int, int, int, int],
              accent_color: tuple[int, int, int, int] | None = None) -> None:
    shadow = [(x + 2.2, y + 2.5) for x, y in gear_points(cx, cy, teeth, outer, root)]
    draw.polygon(scaled(shadow), fill=rgba(0, 0, 0, 130))

    outline = gear_points(cx, cy, teeth, outer + 1.8, root + 1.8)
    draw.polygon(scaled(outline), fill=rgba(15, 19, 24))

    body = gear_points(cx, cy, teeth, outer, root)
    draw.polygon(scaled(body), fill=body_color)
    draw.ellipse(
        [round((cx - root - 0.5) * SCALE), round((cy - root - 0.5) * SCALE),
         round((cx + root + 0.5) * SCALE), round((cy + root + 0.5) * SCALE)],
        fill=body_color,
    )

    inner = gear_points(cx, cy, teeth, outer - 4.5, root - 4.5)
    draw.polygon(scaled(inner), fill=inner_color)
    draw.ellipse(
        [round((cx - root + 4) * SCALE), round((cy - root + 4) * SCALE),
         round((cx + root - 4) * SCALE), round((cy + root - 4) * SCALE)],
        fill=inner_color,
    )

    hi = gear_points(cx - 1.5, cy - 1.8, teeth, outer - 8.5, root - 8.5)
    draw.polygon(scaled(hi), fill=highlight_color)

    draw.ellipse(
        [round((cx - hole - 2) * SCALE), round((cy - hole - 2) * SCALE),
         round((cx + hole + 2) * SCALE), round((cy + hole + 2) * SCALE)],
        fill=rgba(18, 23, 28),
    )
    draw.ellipse(
        [round((cx - hole) * SCALE), round((cy - hole) * SCALE),
         round((cx + hole) * SCALE), round((cy + hole) * SCALE)],
        fill=rgba(18, 22, 27),
    )
    draw.arc(
        [round((cx - hole - 1) * SCALE), round((cy - hole - 1) * SCALE),
         round((cx + hole + 1) * SCALE), round((cy + hole + 1) * SCALE)],
        205,
        330,
        fill=highlight_color,
        width=max(1, round(1.2 * SCALE)),
    )
    if accent_color is not None:
        for angle in (math.radians(35), math.radians(160), math.radians(275)):
            bx = cx + math.cos(angle) * (root - 5.0)
            by = cy + math.sin(angle) * (root - 5.0)
            draw.ellipse(
                [round((bx - 1.5) * SCALE), round((by - 1.5) * SCALE),
                 round((bx + 1.5) * SCALE), round((by + 1.5) * SCALE)],
                fill=rgba(15, 18, 22),
            )
            draw.ellipse(
                [round((bx - 0.9) * SCALE), round((by - 0.9) * SCALE),
                 round((bx + 0.9) * SCALE), round((by + 0.9) * SCALE)],
                fill=accent_color,
            )


def draw_plate(draw: ImageDraw.ImageDraw) -> None:
    draw.polygon(scaled([(15, 39), (40, 43), (50, 51), (24, 55), (12, 48)]), fill=rgba(10, 12, 16, 165))
    draw.polygon(scaled([(15, 36), (41, 39), (50, 47), (23, 52), (11, 44)]), fill=rgba(32, 39, 50))
    draw.polygon(scaled([(18, 38), (39, 41), (45, 46), (24, 49), (16, 43)]), fill=rgba(79, 90, 101))
    draw.polygon(scaled([(23, 40), (36, 42), (40, 45), (25, 47), (20, 43)]), fill=rgba(55, 202, 181))
    draw.line(scaled([(17, 38), (39, 41), (45, 46)]), fill=rgba(182, 190, 191), width=round(1.1 * SCALE))
    draw.line(scaled([(18, 45), (24, 49), (45, 46)]), fill=rgba(16, 20, 25), width=round(1.0 * SCALE))

    for x, y, color in ((21, 42, rgba(50, 216, 187)), (38, 44, rgba(223, 169, 71))):
        draw.ellipse(
            [round((x - 2.2) * SCALE), round((y - 2.2) * SCALE),
             round((x + 2.2) * SCALE), round((y + 2.2) * SCALE)],
            fill=rgba(13, 17, 21),
        )
        draw.ellipse(
            [round((x - 1.3) * SCALE), round((y - 1.3) * SCALE),
             round((x + 1.3) * SCALE), round((y + 1.3) * SCALE)],
            fill=color,
        )


def make_icon() -> Image.Image:
    img = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_plate(draw)
    draw_gear(draw, 32, 28, 10, 20, 17.5, 7,
              rgba(70, 82, 94), rgba(36, 43, 53), rgba(174, 188, 196), rgba(221, 165, 67))
    draw_gear(draw, 46, 43, 8, 9.5, 8.1, 3.6,
              rgba(73, 211, 187), rgba(25, 76, 79), rgba(181, 247, 232), rgba(230, 178, 78))
    img = img.resize((SIZE, SIZE), Image.Resampling.LANCZOS)

    alpha = img.getchannel("A")
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    shadow.putalpha(alpha.filter(ImageFilter.GaussianBlur(0.55)))
    shadow = Image.eval(shadow, lambda v: v)
    out = Image.alpha_composite(shadow, img)
    return out


def make_preview(icon: Image.Image) -> Image.Image:
    cell = 16
    scale = 4
    preview = Image.new("RGBA", (SIZE * scale, SIZE * scale), rgba(20, 24, 32))
    draw = ImageDraw.Draw(preview)
    for y in range(0, preview.height, cell):
        for x in range(0, preview.width, cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=rgba(30, 35, 44))
    preview.alpha_composite(icon.resize(preview.size, Image.Resampling.NEAREST))
    return preview.convert("RGB")


def main() -> None:
    ITEM_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    icon = make_icon()
    icon.save(ITEM_DIR / "gunsmith_material_parts.png")
    icon.save(OUT_DIR / "gunsmith-material-parts-icon.png")
    make_preview(icon).save(OUT_DIR / "gunsmith-material-parts-preview.png")


if __name__ == "__main__":
    main()
