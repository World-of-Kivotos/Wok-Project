from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
SIZE = 64
SCALE = 4


def rgba(r: int, g: int, b: int, a: int = 255) -> tuple[int, int, int, int]:
    return r, g, b, a


def pts(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def box(x1: float, y1: float, x2: float, y2: float) -> list[int]:
    return [round(x1 * SCALE), round(y1 * SCALE), round(x2 * SCALE), round(y2 * SCALE)]


def poly(draw: ImageDraw.ImageDraw, points: list[tuple[float, float]],
         fill: tuple[int, int, int, int]) -> None:
    draw.polygon(pts(points), fill=fill)


def line(draw: ImageDraw.ImageDraw, points: list[tuple[float, float]],
         fill: tuple[int, int, int, int], width: float = 1.0) -> None:
    draw.line(pts(points), fill=fill, width=max(1, round(width * SCALE)))


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def finish(image: Image.Image) -> Image.Image:
    return image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def draw_ingot(draw: ImageDraw.ImageDraw, x: float, y: float, w: float, h: float,
               top: tuple[int, int, int, int], front: tuple[int, int, int, int],
               side: tuple[int, int, int, int], edge: tuple[int, int, int, int],
               accent: tuple[int, int, int, int] | None = None,
               stamp: bool = False) -> None:
    shadow = [(x + 5, y + h + 3), (x + w - 6, y + h + 3), (x + w + 2, y + h - 6),
              (x + w - 2, y + h - 11), (x + 6, y + h - 8), (x - 2, y + h - 2)]
    poly(draw, shadow, rgba(0, 0, 0, 105))

    outline = [(x + 8, y), (x + w - 9, y), (x + w, y + 11), (x + w - 8, y + h),
               (x + 7, y + h), (x, y + 12)]
    poly(draw, outline, rgba(11, 14, 18))

    top_face = [(x + 9, y + 3), (x + w - 10, y + 3), (x + w - 16, y + 15), (x + 15, y + 15)]
    left_face = [(x + 2, y + 13), (x + 15, y + 15), (x + 10, y + h - 3), (x + 7, y + h - 3)]
    front_face = [(x + 15, y + 15), (x + w - 16, y + 15), (x + w - 9, y + h - 3), (x + 10, y + h - 3)]
    right_face = [(x + w - 16, y + 15), (x + w - 2, y + 12), (x + w - 8, y + h - 3),
                  (x + w - 9, y + h - 3)]
    poly(draw, top_face, top)
    poly(draw, left_face, side)
    poly(draw, front_face, front)
    poly(draw, right_face, side)

    line(draw, [(x + 10, y + 5), (x + w - 12, y + 5)], edge, 1.0)
    line(draw, [(x + 16, y + 15), (x + w - 16, y + 15)], rgba(21, 25, 31), 1.0)
    line(draw, [(x + 10, y + h - 4), (x + w - 10, y + h - 4)], rgba(18, 22, 28), 1.0)

    if accent is not None:
        poly(draw, [(x + w * 0.52, y + 5), (x + w - 15, y + 5),
                    (x + w - 19, y + 13), (x + w * 0.48, y + 13)], accent)
        line(draw, [(x + w * 0.52, y + 5), (x + w - 15, y + 5)], rgba(244, 238, 185), 0.75)

    if stamp:
        cx = x + w * 0.52
        cy = y + h * 0.62
        draw.ellipse(box(cx - 6, cy - 4, cx + 6, cy + 4), fill=rgba(19, 23, 29, 135))
        draw.ellipse(box(cx - 4.2, cy - 2.7, cx + 4.2, cy + 2.7), fill=accent or edge)


def variant_a() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 10, 22, 44, 23, rgba(201, 208, 207), rgba(88, 98, 104),
               rgba(54, 62, 70), rgba(244, 249, 242), rgba(220, 164, 62), True)
    return finish(image)


def variant_b() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 13, 31, 39, 18, rgba(122, 139, 153), rgba(52, 65, 82),
               rgba(36, 45, 58), rgba(187, 211, 233), rgba(72, 190, 212), False)
    draw_ingot(d, 10, 20, 42, 19, rgba(220, 194, 127), rgba(120, 83, 47),
               rgba(76, 56, 39), rgba(255, 231, 161), rgba(216, 168, 68), False)
    return finish(image)


def variant_c() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 9, 23, 45, 22, rgba(182, 193, 198), rgba(70, 79, 86),
               rgba(39, 47, 55), rgba(238, 246, 242), None, False)
    poly(d, [(31, 27), (48, 27), (45, 36), (29, 36)], rgba(222, 166, 58))
    poly(d, [(30, 37), (45, 37), (48, 43), (31, 43)], rgba(141, 88, 40))
    line(d, [(31, 27), (48, 27), (45, 36)], rgba(255, 222, 125), 0.9)
    return finish(image)


def variant_d() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 11, 22, 43, 23, rgba(88, 119, 154), rgba(37, 54, 77),
               rgba(23, 33, 51), rgba(163, 205, 243), rgba(84, 225, 207), True)
    d.rectangle(box(17, 38, 44, 41), fill=rgba(83, 229, 209))
    d.rectangle(box(18, 39, 43, 40), fill=rgba(187, 255, 242))
    return finish(image)


def variant_e() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 8, 27, 47, 19, rgba(214, 205, 175), rgba(91, 81, 65),
               rgba(55, 47, 39), rgba(255, 240, 180), rgba(176, 189, 193), False)
    draw_ingot(d, 15, 18, 37, 18, rgba(161, 171, 172), rgba(68, 75, 80),
               rgba(42, 47, 54), rgba(230, 235, 230), rgba(220, 161, 60), False)
    return finish(image)


def variant_f() -> Image.Image:
    image, d = canvas()
    draw_ingot(d, 10, 21, 44, 25, rgba(190, 201, 190), rgba(76, 88, 80),
               rgba(43, 55, 50), rgba(238, 245, 224), rgba(92, 229, 143), True)
    for x, y in ((20, 35), (31, 39), (43, 34)):
        d.rectangle(box(x - 1.5, y - 1.5, x + 1.5, y + 1.5), fill=rgba(18, 24, 22))
        d.rectangle(box(x - 0.8, y - 0.8, x + 0.8, y + 0.8), fill=rgba(109, 244, 151))
    return finish(image)


def preview(icons: list[tuple[str, Image.Image]]) -> Image.Image:
    cols = 3
    cell = 104
    label_h = 17
    rows = 2
    image = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), rgba(20, 24, 32))
    draw = ImageDraw.Draw(image)
    for i, (label, icon) in enumerate(icons):
        col = i % cols
        row = i // cols
        x = col * cell
        y = row * (cell + label_h)
        draw.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], fill=rgba(28, 33, 42))
        draw.rectangle([x + 7, y + 7, x + cell - 7, y + cell - 7], outline=rgba(70, 77, 92))
        image.alpha_composite(icon.resize((80, 80), Image.Resampling.NEAREST), (x + 12, y + 12))
        draw.text((x + 10, y + cell - 4), label, fill=rgba(220, 227, 238))
    return image.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    icons = [
        ("A 银金锭", variant_a()),
        ("B 双锭", variant_b()),
        ("C 半金半钢", variant_c()),
        ("D 蓝钢锭", variant_d()),
        ("E 叠锭", variant_e()),
        ("F 绿芯锭", variant_f()),
    ]
    for idx, (_, icon) in enumerate(icons, 1):
        icon.save(OUT_DIR / f"gunsmith-material-alloy-ingot-{idx:02d}.png")
    preview(icons).save(OUT_DIR / "gunsmith-material-alloy-ingot-variants-preview.png")


if __name__ == "__main__":
    main()
