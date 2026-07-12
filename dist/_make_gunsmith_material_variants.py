from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
SIZE = 64
SCALE = 4


def rgba(r: int, g: int, b: int, a: int = 255) -> tuple[int, int, int, int]:
    return r, g, b, a


def scaled(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def gear_points(cx: float, cy: float, teeth: int, outer: float, root: float) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for tooth in range(teeth):
        center = -math.pi / 2.0 + tooth * math.tau / teeth
        for offset, radius in ((-0.38, root), (-0.2, outer), (0.2, outer), (0.38, root)):
            angle = center + offset * math.tau / teeth
            pts.append((cx + math.cos(angle) * radius, cy + math.sin(angle) * radius))
    return pts


def box(x1: float, y1: float, x2: float, y2: float) -> list[int]:
    return [round(x1 * SCALE), round(y1 * SCALE), round(x2 * SCALE), round(y2 * SCALE)]


def draw_gear(draw: ImageDraw.ImageDraw, cx: float, cy: float, teeth: int, outer: float, root: float,
              hole: float, body: tuple[int, int, int, int], inner: tuple[int, int, int, int],
              hi: tuple[int, int, int, int], accent: tuple[int, int, int, int] | None) -> None:
    draw.polygon(scaled([(x + 2.0, y + 2.3) for x, y in gear_points(cx, cy, teeth, outer, root)]),
                 fill=rgba(0, 0, 0, 120))
    draw.polygon(scaled(gear_points(cx, cy, teeth, outer + 1.7, root + 1.7)), fill=rgba(12, 15, 20))
    draw.polygon(scaled(gear_points(cx, cy, teeth, outer, root)), fill=body)
    draw.ellipse(box(cx - root - 0.4, cy - root - 0.4, cx + root + 0.4, cy + root + 0.4), fill=body)
    draw.polygon(scaled(gear_points(cx, cy, teeth, outer - 4.7, root - 4.5)), fill=inner)
    draw.ellipse(box(cx - root + 4.0, cy - root + 4.0, cx + root - 4.0, cy + root - 4.0), fill=inner)
    draw.polygon(scaled(gear_points(cx - 1.4, cy - 1.6, teeth, outer - 8.0, root - 8.0)), fill=hi)
    draw.ellipse(box(cx - hole - 2.0, cy - hole - 2.0, cx + hole + 2.0, cy + hole + 2.0), fill=rgba(10, 13, 18))
    draw.ellipse(box(cx - hole, cy - hole, cx + hole, cy + hole), fill=rgba(18, 22, 28))
    draw.arc(box(cx - hole - 1, cy - hole - 1, cx + hole + 1, cy + hole + 1), 205, 330,
             fill=hi, width=max(1, round(1.2 * SCALE)))
    if accent is not None:
        for angle in (math.radians(35), math.radians(160), math.radians(275)):
            bx = cx + math.cos(angle) * (root - 5.0)
            by = cy + math.sin(angle) * (root - 5.0)
            draw.ellipse(box(bx - 1.45, by - 1.45, bx + 1.45, by + 1.45), fill=rgba(12, 15, 20))
            draw.ellipse(box(bx - 0.85, by - 0.85, bx + 0.85, by + 0.85), fill=accent)


def draw_plate(draw: ImageDraw.ImageDraw, accent: tuple[int, int, int, int], mode: int) -> None:
    draw.polygon(scaled([(15, 39), (40, 43), (50, 51), (24, 55), (12, 48)]), fill=rgba(0, 0, 0, 135))
    if mode == 0:
        draw.polygon(scaled([(15, 36), (41, 39), (50, 47), (23, 52), (11, 44)]), fill=rgba(33, 39, 49))
        draw.polygon(scaled([(18, 38), (39, 41), (45, 46), (24, 49), (16, 43)]), fill=rgba(76, 87, 98))
        draw.polygon(scaled([(23, 40), (36, 42), (40, 45), (25, 47), (20, 43)]), fill=accent)
    elif mode == 1:
        draw.rounded_rectangle(box(12, 39, 50, 53), radius=round(4 * SCALE), fill=rgba(36, 42, 50))
        draw.rounded_rectangle(box(16, 42, 46, 49), radius=round(2 * SCALE), fill=accent)
    else:
        draw.polygon(scaled([(12, 41), (28, 35), (51, 42), (45, 52), (20, 53)]), fill=rgba(37, 43, 52))
        draw.line(scaled([(17, 43), (31, 39), (45, 43)]), fill=accent, width=round(2 * SCALE))
    draw.line(scaled([(17, 38), (39, 41), (45, 46)]), fill=rgba(176, 185, 190), width=round(1.0 * SCALE))
    for x, y in ((21, 43), (39, 45)):
        draw.ellipse(box(x - 1.8, y - 1.8, x + 1.8, y + 1.8), fill=rgba(13, 17, 21))
        draw.ellipse(box(x - 1.0, y - 1.0, x + 1.0, y + 1.0), fill=accent)


def variant(idx: int, name: str, body: tuple[int, int, int, int], inner: tuple[int, int, int, int],
            hi: tuple[int, int, int, int], accent: tuple[int, int, int, int], mode: int,
            layout: int) -> Image.Image:
    img = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_plate(draw, accent, mode)
    if layout == 0:
        draw_gear(draw, 32, 28, 10, 20, 17.5, 7, body, inner, hi, accent)
        draw_gear(draw, 46, 43, 8, 9.5, 8.1, 3.6, accent, inner, hi, None)
    elif layout == 1:
        draw_gear(draw, 29, 30, 10, 18.5, 16.0, 6.5, body, inner, hi, accent)
        draw_gear(draw, 47, 28, 8, 9.0, 7.5, 3.2, accent, inner, hi, None)
        draw_gear(draw, 44, 45, 8, 8.0, 6.7, 2.7, rgba(112, 119, 124), inner, hi, None)
    else:
        draw_gear(draw, 32, 29, 12, 19.0, 16.6, 6.8, body, inner, hi, accent)
        draw.rectangle(box(23, 25, 41, 33), fill=rgba(15, 18, 24))
        draw.rectangle(box(25, 27, 39, 31), fill=accent)
    out = img.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    out.save(OUT_DIR / f"gunsmith-material-parts-{idx:02d}-{name}.png")
    return out


def make_preview(icons: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 104
    label_h = 17
    cols = 3
    rows = math.ceil(len(icons) / cols)
    preview = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), rgba(20, 24, 32))
    draw = ImageDraw.Draw(preview)
    for i, (label, icon) in enumerate(icons):
        col = i % cols
        row = i // cols
        x = col * cell
        y = row * (cell + label_h)
        draw.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], fill=rgba(27, 31, 40))
        draw.rectangle([x + 7, y + 7, x + cell - 7, y + cell - 7], outline=rgba(69, 76, 91))
        preview.alpha_composite(icon.resize((80, 80), Image.Resampling.NEAREST), (x + 12, y + 12))
        draw.text((x + 10, y + cell - 4), label, fill=rgba(220, 227, 238))
    return preview.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    specs = [
        ("A 青色副齿", rgba(70, 82, 94), rgba(36, 43, 53), rgba(174, 188, 196), rgba(73, 211, 187), 0, 0),
        ("B 黄铜轮芯", rgba(81, 87, 88), rgba(40, 43, 46), rgba(214, 218, 210), rgba(221, 165, 67), 1, 0),
        ("C 蓝钢三齿", rgba(63, 77, 98), rgba(24, 32, 45), rgba(161, 187, 224), rgba(82, 156, 255), 2, 1),
        ("D 红色标记", rgba(75, 83, 91), rgba(33, 38, 45), rgba(188, 196, 203), rgba(238, 76, 85), 0, 1),
        ("E 绿能量条", rgba(64, 75, 76), rgba(27, 39, 41), rgba(170, 198, 190), rgba(93, 230, 142), 1, 2),
        ("F 暗金压铸", rgba(80, 73, 61), rgba(42, 36, 30), rgba(217, 188, 130), rgba(207, 130, 54), 2, 2),
    ]
    icons = [(label, variant(i + 1, f"v{i + 1}", body, inner, hi, accent, mode, layout))
             for i, (label, body, inner, hi, accent, mode, layout) in enumerate(specs)]
    make_preview(icons).save(OUT_DIR / "gunsmith-material-parts-variants-preview.png")


if __name__ == "__main__":
    main()
