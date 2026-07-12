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


def box(x1: float, y1: float, x2: float, y2: float) -> list[int]:
    return [round(x1 * SCALE), round(y1 * SCALE), round(x2 * SCALE), round(y2 * SCALE)]


def pts(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def line(draw: ImageDraw.ImageDraw, points: list[tuple[float, float]],
         fill: tuple[int, int, int, int], width: float = 1.0) -> None:
    draw.line(pts(points), fill=fill, width=max(1, round(width * SCALE)))


def poly(draw: ImageDraw.ImageDraw, points: list[tuple[float, float]],
         fill: tuple[int, int, int, int]) -> None:
    draw.polygon(pts(points), fill=fill)


def make_canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def finish(image: Image.Image) -> Image.Image:
    return image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def draw_ingot(draw: ImageDraw.ImageDraw, x: float, y: float, w: float, h: float,
               top: tuple[int, int, int, int], side: tuple[int, int, int, int],
               edge: tuple[int, int, int, int], accent: tuple[int, int, int, int]) -> None:
    poly(draw, [(x + 6, y), (x + w - 5, y), (x + w, y + h * 0.42), (x + w - 6, y + h),
                (x + 5, y + h), (x, y + h * 0.42)], rgba(7, 9, 13, 145))
    poly(draw, [(x + 6, y + 1), (x + w - 7, y + 1), (x + w - 11, y + h * 0.45),
                (x + 11, y + h * 0.45)], top)
    poly(draw, [(x + 1, y + h * 0.43), (x + 11, y + h * 0.45), (x + 7, y + h - 2),
                (x + 5, y + h - 2)], side)
    poly(draw, [(x + 11, y + h * 0.45), (x + w - 11, y + h * 0.45),
                (x + w - 7, y + h - 2), (x + 7, y + h - 2)], side)
    line(draw, [(x + 7, y + 3), (x + w - 10, y + 3)], edge, 1.0)
    line(draw, [(x + 12, y + h * 0.45), (x + w - 12, y + h * 0.45)], rgba(18, 22, 28), 1.0)
    poly(draw, [(x + w * 0.55, y + 3), (x + w - 12, y + 3), (x + w - 16, y + h * 0.38),
                (x + w * 0.50, y + h * 0.38)], accent)


def alloy_a() -> Image.Image:
    image, d = make_canvas()
    draw_ingot(d, 12, 33, 38, 16, rgba(171, 184, 190), rgba(75, 85, 94), rgba(227, 236, 235), rgba(219, 164, 63))
    draw_ingot(d, 16, 22, 36, 15, rgba(105, 126, 150), rgba(46, 58, 76), rgba(176, 203, 230), rgba(78, 192, 213))
    draw_ingot(d, 10, 14, 39, 14, rgba(199, 194, 178), rgba(92, 83, 67), rgba(247, 233, 177), rgba(213, 142, 51))
    return finish(image)


def alloy_b() -> Image.Image:
    image, d = make_canvas()
    d.ellipse(box(13, 20, 52, 51), fill=rgba(0, 0, 0, 135))
    d.ellipse(box(12, 14, 52, 44), fill=rgba(33, 37, 43))
    d.ellipse(box(15, 16, 49, 39), fill=rgba(99, 109, 115))
    d.ellipse(box(21, 20, 43, 34), fill=rgba(219, 169, 78))
    d.ellipse(box(25, 23, 39, 31), fill=rgba(251, 218, 126))
    d.arc(box(16, 16, 49, 39), 198, 328, fill=rgba(236, 244, 240), width=round(1.3 * SCALE))
    d.arc(box(13, 14, 52, 44), 25, 135, fill=rgba(70, 216, 197), width=round(1.1 * SCALE))
    for x, y in ((18, 37), (45, 35), (32, 41)):
        d.rectangle(box(x - 1, y - 1, x + 1, y + 1), fill=rgba(255, 180, 75))
    return finish(image)


def alloy_c() -> Image.Image:
    image, d = make_canvas()
    poly(d, [(15, 40), (34, 29), (52, 37), (35, 51)], rgba(0, 0, 0, 130))
    draw_ingot(d, 14, 33, 34, 13, rgba(178, 188, 194), rgba(70, 80, 87), rgba(235, 240, 237), rgba(77, 204, 183))
    poly(d, [(15, 22), (29, 13), (47, 24), (32, 34)], rgba(12, 15, 19))
    poly(d, [(17, 22), (30, 15), (45, 24), (32, 31)], rgba(58, 70, 83))
    poly(d, [(22, 23), (30, 18), (39, 24), (31, 28)], rgba(112, 160, 207))
    line(d, [(18, 22), (30, 15), (45, 24)], rgba(205, 220, 235), 1.1)
    poly(d, [(31, 34), (48, 24), (53, 31), (36, 45)], rgba(202, 151, 56))
    poly(d, [(35, 34), (47, 27), (50, 31), (38, 40)], rgba(245, 196, 87))
    return finish(image)


def alloy_d() -> Image.Image:
    image, d = make_canvas()
    center = (32, 31)
    radius = 22
    outer = []
    inner = []
    for i in range(6):
        a = -math.pi / 6 + i * math.tau / 6
        outer.append((center[0] + math.cos(a) * radius, center[1] + math.sin(a) * radius))
        inner.append((center[0] + math.cos(a) * (radius - 8), center[1] + math.sin(a) * (radius - 8)))
    poly(d, [(x + 2, y + 3) for x, y in outer], rgba(0, 0, 0, 130))
    poly(d, outer, rgba(14, 17, 23))
    poly(d, [(15, 25), (32, 12), (49, 25), (49, 39), (32, 52), (15, 39)], rgba(43, 53, 66))
    poly(d, inner, rgba(78, 97, 120))
    d.ellipse(box(25, 24, 39, 38), fill=rgba(18, 22, 29))
    d.ellipse(box(28, 27, 36, 35), fill=rgba(74, 216, 201))
    line(d, [(18, 25), (32, 15), (46, 25)], rgba(184, 208, 230), 1.1)
    line(d, [(18, 39), (32, 49), (46, 39)], rgba(23, 28, 35), 1.1)
    return finish(image)


def sheet_stack(draw: ImageDraw.ImageDraw, layers: list[tuple[float, float, tuple[int, int, int, int]]],
                accent: tuple[int, int, int, int], holes: bool = True) -> None:
    for x, y, color in layers:
        poly(draw, [(x + 4, y), (x + 44, y + 3), (x + 50, y + 15), (x + 11, y + 22), (x, y + 11)],
             rgba(0, 0, 0, 110))
        poly(draw, [(x + 4, y), (x + 42, y + 2), (x + 48, y + 13), (x + 10, y + 19), (x, y + 10)],
             color)
        line(draw, [(x + 6, y + 2), (x + 41, y + 4)], rgba(198, 208, 216), 1.0)
        line(draw, [(x + 10, y + 19), (x + 48, y + 13)], rgba(17, 21, 27), 1.0)
    if holes:
        for x, y in ((23, 31), (38, 33), (30, 42)):
            draw.ellipse(box(x - 2, y - 2, x + 2, y + 2), fill=rgba(12, 15, 20))
            draw.ellipse(box(x - 1, y - 1, x + 1, y + 1), fill=accent)


def plate_a() -> Image.Image:
    image, d = make_canvas()
    sheet_stack(d, [(9, 33, rgba(50, 58, 69)), (12, 25, rgba(78, 91, 107)), (15, 17, rgba(113, 129, 145))],
                rgba(74, 211, 190), True)
    return finish(image)


def plate_b() -> Image.Image:
    image, d = make_canvas()
    sheet_stack(d, [(12, 34, rgba(45, 51, 61)), (10, 25, rgba(70, 76, 88))],
                rgba(225, 166, 62), False)
    poly(d, [(18, 20), (45, 22), (52, 38), (25, 45), (12, 32)], rgba(20, 24, 31))
    poly(d, [(20, 22), (43, 24), (49, 37), (25, 42), (15, 32)], rgba(80, 86, 94))
    line(d, [(22, 25), (42, 27), (47, 36)], rgba(220, 225, 224), 1.2)
    d.rectangle(box(20, 30, 46, 34), fill=rgba(211, 145, 54))
    return finish(image)


def plate_c() -> Image.Image:
    image, d = make_canvas()
    for x, y, color in ((12, 36, rgba(45, 55, 72)), (15, 29, rgba(57, 85, 118)), (18, 22, rgba(92, 135, 180))):
        d.rounded_rectangle(box(x, y, x + 38, y + 15), radius=round(3 * SCALE), fill=rgba(0, 0, 0, 120))
        d.rounded_rectangle(box(x, y - 2, x + 38, y + 12), radius=round(3 * SCALE), fill=color)
        line(d, [(x + 5, y), (x + 31, y)], rgba(210, 226, 242), 1.0)
        line(d, [(x + 5, y + 10), (x + 34, y + 10)], rgba(18, 23, 31), 1.0)
    for x, y in ((24, 29), (42, 31), (27, 44), (45, 46)):
        d.ellipse(box(x - 1.8, y - 1.8, x + 1.8, y + 1.8), fill=rgba(14, 18, 24))
        d.ellipse(box(x - 0.9, y - 0.9, x + 0.9, y + 0.9), fill=rgba(80, 225, 202))
    return finish(image)


def plate_d() -> Image.Image:
    image, d = make_canvas()
    poly(d, [(13, 43), (25, 25), (44, 18), (53, 29), (40, 50), (22, 54)], rgba(0, 0, 0, 135))
    poly(d, [(12, 38), (24, 20), (43, 13), (52, 25), (39, 46), (21, 50)], rgba(38, 45, 52))
    poly(d, [(17, 37), (26, 24), (41, 19), (47, 27), (36, 41), (23, 44)], rgba(75, 91, 82))
    poly(d, [(25, 25), (41, 19), (47, 27), (30, 31)], rgba(97, 225, 143))
    line(d, [(16, 37), (25, 22), (42, 16), (50, 26)], rgba(199, 229, 204), 1.1)
    line(d, [(22, 45), (36, 41), (48, 28)], rgba(16, 20, 25), 1.0)
    return finish(image)


def preview_grid(items: list[tuple[str, Image.Image]]) -> Image.Image:
    cols = 4
    cell = 96
    label_h = 18
    title_h = 22
    rows = 2
    image = Image.new("RGBA", (cols * cell, title_h * 2 + rows * (cell + label_h)), rgba(20, 24, 32))
    draw = ImageDraw.Draw(image)
    draw.text((10, 5), "ALLOY", fill=rgba(224, 231, 240))
    draw.text((10, title_h + cell + label_h + 5), "PLATE", fill=rgba(224, 231, 240))
    for i, (label, icon) in enumerate(items):
        section = 0 if i < 4 else 1
        index = i if i < 4 else i - 4
        x = index * cell
        y = title_h + section * (cell + label_h + title_h)
        draw.rectangle([x + 7, y + 5, x + cell - 7, y + cell - 7], fill=rgba(28, 33, 42))
        draw.rectangle([x + 8, y + 6, x + cell - 8, y + cell - 8], outline=rgba(70, 77, 92))
        image.alpha_composite(icon.resize((76, 76), Image.Resampling.NEAREST), (x + 10, y + 7))
        draw.text((x + 12, y + cell - 6), label, fill=rgba(220, 227, 238))
    return image.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    alloy = [("A", alloy_a()), ("B", alloy_b()), ("C", alloy_c()), ("D", alloy_d())]
    plate = [("A", plate_a()), ("B", plate_b()), ("C", plate_c()), ("D", plate_d())]
    for label, icon in alloy:
        icon.save(OUT_DIR / f"gunsmith-material-alloy-{label.lower()}.png")
    for label, icon in plate:
        icon.save(OUT_DIR / f"gunsmith-material-plate-{label.lower()}.png")
    preview_grid([(f"Alloy {label}", icon) for label, icon in alloy]
                 + [(f"Plate {label}", icon) for label, icon in plate]).save(
        OUT_DIR / "gunsmith-material-inputs-variants-preview.png")


if __name__ == "__main__":
    main()
