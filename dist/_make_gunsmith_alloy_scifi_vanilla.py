from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
REF_DIR = OUT_DIR / "vanilla_refs"
SIZE = 64


def load_iron() -> Image.Image:
    return Image.open(REF_DIR / "iron_ingot.png").convert("RGBA")


def recolor(icon: Image.Image, low: tuple[int, int, int],
            mid: tuple[int, int, int], high: tuple[int, int, int]) -> Image.Image:
    out = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    pix = out.load()
    src = icon.load()
    for y in range(icon.height):
        for x in range(icon.width):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            value = (r + g + b) / 765.0
            if value < 0.5:
                t = value / 0.5
                color = tuple(round(low[i] * (1.0 - t) + mid[i] * t) for i in range(3))
            else:
                t = (value - 0.5) / 0.5
                color = tuple(round(mid[i] * (1.0 - t) + high[i] * t) for i in range(3))
            pix[x, y] = color + (a,)
    return out


def overlay_pixels(icon: Image.Image, color: tuple[int, int, int, int],
                   pattern: list[tuple[int, int]]) -> Image.Image:
    out = icon.copy()
    pix = out.load()
    for x, y in pattern:
        if 0 <= x < 16 and 0 <= y < 16 and pix[x, y][3] > 0:
            pix[x, y] = color
    return out


def enlarge(icon: Image.Image) -> Image.Image:
    return icon.resize((SIZE, SIZE), Image.Resampling.NEAREST)


def variants() -> list[tuple[str, Image.Image]]:
    iron = load_iron()
    base = [
        ("A CYAN", recolor(iron, (20, 29, 40), (58, 92, 112), (174, 246, 237))),
        ("B VIOLET", recolor(iron, (30, 24, 47), (90, 65, 128), (220, 176, 255))),
        ("C BLUE", recolor(iron, (16, 28, 55), (48, 92, 160), (177, 224, 255))),
        ("D RED", recolor(iron, (45, 23, 28), (128, 54, 65), (255, 158, 139))),
        ("E GREEN", recolor(iron, (19, 35, 30), (55, 121, 86), (178, 255, 185))),
        ("F GOLD", recolor(iron, (50, 39, 23), (148, 104, 42), (255, 228, 139))),
    ]
    line_patterns = [
        [(5, 5), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5), (8, 6), (9, 6)],
        [(4, 5), (5, 5), (6, 5), (10, 6), (11, 6), (7, 7), (8, 7)],
        [(5, 4), (6, 4), (7, 5), (8, 5), (9, 6), (10, 6), (11, 7)],
        [(5, 6), (6, 6), (7, 6), (8, 5), (9, 5), (10, 4)],
        [(4, 6), (5, 6), (6, 6), (8, 5), (9, 5), (10, 5), (11, 5)],
        [(5, 5), (6, 5), (7, 5), (9, 6), (10, 6), (11, 6)],
    ]
    glow_colors = [
        (89, 255, 226, 255),
        (217, 118, 255, 255),
        (93, 173, 255, 255),
        (255, 77, 89, 255),
        (101, 255, 148, 255),
        (255, 199, 76, 255),
    ]
    return [(label, overlay_pixels(icon, glow_colors[i], line_patterns[i])) for i, (label, icon) in enumerate(base)]


def preview(entries: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 84
    label_h = 16
    cols = 3
    rows = 2
    out = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), (20, 24, 32, 255))
    d = ImageDraw.Draw(out)
    for i, (label, icon16) in enumerate(entries):
        x = (i % cols) * cell
        y = (i // cols) * (cell + label_h)
        d.rectangle([x + 5, y + 5, x + cell - 5, y + cell - 5], fill=(29, 34, 43, 255))
        d.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], outline=(70, 77, 92, 255))
        out.alpha_composite(enlarge(icon16), (x + 10, y + 8))
        d.text((x + 9, y + cell - 2), label, fill=(220, 227, 238, 255))
    return out.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    entries = variants()
    for label, icon in entries:
        icon.resize((SIZE, SIZE), Image.Resampling.NEAREST).save(
            OUT_DIR / f"gunsmith-material-alloy-scifi-{label.split()[0].lower()}.png")
    preview(entries).save(OUT_DIR / "gunsmith-material-alloy-scifi-preview.png")


if __name__ == "__main__":
    main()
