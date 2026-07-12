from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
SIZE = 64
PX = 4


def rgba(r: int, g: int, b: int, a: int = 255) -> tuple[int, int, int, int]:
    return r, g, b, a


def rect(draw: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int,
         color: tuple[int, int, int, int]) -> None:
    draw.rectangle([x * PX, y * PX, (x + w) * PX - 1, (y + h) * PX - 1], fill=color)


def draw_mc_ingot(draw: ImageDraw.ImageDraw, ox: int, oy: int,
                  light: tuple[int, int, int, int],
                  mid: tuple[int, int, int, int],
                  dark: tuple[int, int, int, int],
                  accent: tuple[int, int, int, int] | None = None,
                  split: bool = False) -> None:
    # Vanilla-ingot-like 12x8 silhouette, drawn on a 16x16 pixel grid.
    rect(draw, ox + 3, oy + 1, 6, 1, rgba(11, 13, 17, 210))
    rect(draw, ox + 2, oy + 2, 9, 1, rgba(11, 13, 17, 230))
    rect(draw, ox + 1, oy + 3, 11, 3, rgba(11, 13, 17, 245))
    rect(draw, ox + 2, oy + 6, 9, 1, rgba(11, 13, 17, 230))
    rect(draw, ox + 3, oy + 7, 6, 1, rgba(11, 13, 17, 210))

    rect(draw, ox + 3, oy + 1, 6, 1, light)
    rect(draw, ox + 2, oy + 2, 9, 1, light)
    rect(draw, ox + 1, oy + 3, 11, 1, mid)
    rect(draw, ox + 1, oy + 4, 11, 2, mid)
    rect(draw, ox + 2, oy + 6, 9, 1, dark)
    rect(draw, ox + 3, oy + 7, 6, 1, dark)

    rect(draw, ox + 4, oy + 2, 4, 1, rgba(255, 255, 255, 88))
    rect(draw, ox + 2, oy + 3, 2, 1, rgba(255, 255, 255, 58))
    rect(draw, ox + 8, oy + 5, 3, 1, rgba(0, 0, 0, 55))
    rect(draw, ox + 3, oy + 6, 3, 1, rgba(0, 0, 0, 42))

    if accent is not None:
        if split:
            rect(draw, ox + 7, oy + 2, 4, 1, accent)
            rect(draw, ox + 6, oy + 3, 6, 2, accent)
            rect(draw, ox + 7, oy + 5, 3, 1, accent)
            rect(draw, ox + 8, oy + 2, 2, 1, rgba(255, 242, 159, 90))
        else:
            rect(draw, ox + 5, oy + 4, 4, 1, accent)
            rect(draw, ox + 6, oy + 5, 2, 1, accent)
            rect(draw, ox + 5, oy + 3, 1, 1, rgba(255, 246, 172, 100))


def icon_a() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 4, rgba(225, 231, 229), rgba(156, 166, 170), rgba(80, 90, 99),
                  rgba(224, 166, 57), False)
    return img


def icon_b() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 4, rgba(218, 226, 231), rgba(139, 153, 165), rgba(64, 76, 91),
                  rgba(224, 174, 61), True)
    return img


def icon_c() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 6, rgba(232, 213, 130), rgba(185, 128, 48), rgba(94, 66, 38), None, False)
    draw_mc_ingot(d, 2, 3, rgba(222, 230, 230), rgba(146, 157, 162), rgba(76, 85, 93),
                  rgba(219, 164, 59), False)
    return img


def icon_d() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 4, rgba(215, 222, 220), rgba(105, 129, 143), rgba(48, 64, 78),
                  rgba(77, 211, 190), False)
    return img


def icon_e() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 4, rgba(238, 227, 155), rgba(189, 147, 64), rgba(94, 75, 43),
                  rgba(202, 212, 208), True)
    return img


def icon_f() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mc_ingot(d, 2, 4, rgba(196, 207, 211), rgba(92, 103, 111), rgba(38, 45, 55),
                  rgba(229, 170, 58), False)
    rect(d, 5, 8, 6, 1, rgba(17, 21, 26, 160))
    rect(d, 6, 8, 3, 1, rgba(88, 218, 198))
    return img


def make_preview(icons: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 96
    label_h = 17
    cols = 3
    rows = 2
    out = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), rgba(20, 24, 32))
    d = ImageDraw.Draw(out)
    for i, (label, icon) in enumerate(icons):
        x = (i % cols) * cell
        y = (i // cols) * (cell + label_h)
        d.rectangle([x + 7, y + 6, x + cell - 7, y + cell - 7], fill=rgba(28, 33, 42))
        d.rectangle([x + 8, y + 7, x + cell - 8, y + cell - 8], outline=rgba(70, 77, 92))
        out.alpha_composite(icon.resize((80, 80), Image.Resampling.NEAREST), (x + 8, y + 6))
        d.text((x + 10, y + cell - 5), label, fill=rgba(220, 227, 238))
    return out.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    icons = [
        ("A 铁锭金芯", icon_a()),
        ("B 半铁半金", icon_b()),
        ("C 铁金叠锭", icon_c()),
        ("D 蓝钢合金", icon_d()),
        ("E 金银合金", icon_e()),
        ("F 暗钢合金", icon_f()),
    ]
    for idx, (_, icon) in enumerate(icons, 1):
        icon.save(OUT_DIR / f"gunsmith-material-alloy-mc-ingot-{idx:02d}.png")
    make_preview(icons).save(OUT_DIR / "gunsmith-material-alloy-mc-ingot-preview.png")


if __name__ == "__main__":
    main()
