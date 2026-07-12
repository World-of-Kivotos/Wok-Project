from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
REF_DIR = OUT_DIR / "vanilla_refs"
SIZE = 64


def load(name: str) -> Image.Image:
    return Image.open(REF_DIR / name).convert("RGBA")


def resize_icon(icon16: Image.Image) -> Image.Image:
    return icon16.resize((SIZE, SIZE), Image.Resampling.NEAREST)


def rgba_blend(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    return tuple(round(a[i] * (1.0 - t) + b[i] * t) for i in range(4))


def variant_average(iron: Image.Image, gold: Image.Image) -> Image.Image:
    out = Image.new("RGBA", iron.size, (0, 0, 0, 0))
    pix = out.load()
    ip = iron.load()
    gp = gold.load()
    for y in range(16):
        for x in range(16):
            ia = ip[x, y][3]
            ga = gp[x, y][3]
            if ia == 0 and ga == 0:
                continue
            pix[x, y] = rgba_blend(ip[x, y], gp[x, y], 0.38)
    return out


def variant_gold_core(iron: Image.Image, gold: Image.Image) -> Image.Image:
    out = iron.copy()
    pix = out.load()
    gp = gold.load()
    for y in range(16):
        for x in range(16):
            if gp[x, y][3] == 0:
                continue
            if 5 <= x <= 11 and 5 <= y <= 9:
                pix[x, y] = gp[x, y]
            elif 4 <= x <= 12 and 4 <= y <= 10:
                pix[x, y] = rgba_blend(pix[x, y], gp[x, y], 0.38)
    return out


def variant_half(iron: Image.Image, gold: Image.Image) -> Image.Image:
    out = iron.copy()
    pix = out.load()
    gp = gold.load()
    for y in range(16):
        for x in range(16):
            if gp[x, y][3] == 0:
                continue
            if x + y >= 16:
                pix[x, y] = gp[x, y]
            elif x + y >= 13:
                pix[x, y] = rgba_blend(pix[x, y], gp[x, y], 0.45)
    return out


def variant_gold_rim(iron: Image.Image, gold: Image.Image) -> Image.Image:
    out = iron.copy()
    pix = out.load()
    gp = gold.load()
    for y in range(16):
        for x in range(16):
            if gp[x, y][3] == 0:
                continue
            if y <= 5 or x >= 11:
                pix[x, y] = rgba_blend(pix[x, y], gp[x, y], 0.72)
            elif 6 <= y <= 8 and 4 <= x <= 11:
                pix[x, y] = rgba_blend(pix[x, y], gp[x, y], 0.25)
    return out


def variant_dark_alloy(iron: Image.Image, gold: Image.Image) -> Image.Image:
    out = Image.new("RGBA", iron.size, (0, 0, 0, 0))
    pix = out.load()
    ip = iron.load()
    gp = gold.load()
    for y in range(16):
        for x in range(16):
            if ip[x, y][3] == 0 and gp[x, y][3] == 0:
                continue
            base = rgba_blend(ip[x, y], gp[x, y], 0.26)
            dark = (max(0, base[0] - 28), max(0, base[1] - 25), max(0, base[2] - 18), base[3])
            pix[x, y] = dark
            if 5 <= x <= 10 and 4 <= y <= 7:
                pix[x, y] = rgba_blend(dark, gp[x, y], 0.34)
    return out


def variant_two_ingots(iron: Image.Image, gold: Image.Image) -> Image.Image:
    # Keeps the exact vanilla texture, only stacks iron and gold as two small ingots.
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    small_iron = iron.crop((1, 2, 15, 13)).resize((14, 11), Image.Resampling.NEAREST)
    small_gold = gold.crop((1, 2, 15, 13)).resize((14, 11), Image.Resampling.NEAREST)
    out.alpha_composite(small_gold, (2, 5))
    out.alpha_composite(small_iron, (0, 1))
    return out


def make_preview(entries: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 84
    label_h = 16
    cols = 4
    rows = 2
    preview = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), (20, 24, 32, 255))
    draw = ImageDraw.Draw(preview)
    for i, (label, icon16) in enumerate(entries):
        x = (i % cols) * cell
        y = (i // cols) * (cell + label_h)
        draw.rectangle([x + 5, y + 5, x + cell - 5, y + cell - 5], fill=(29, 34, 43, 255))
        draw.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], outline=(70, 77, 92, 255))
        preview.alpha_composite(icon16.resize((64, 64), Image.Resampling.NEAREST), (x + 10, y + 8))
        draw.text((x + 9, y + cell - 2), label, fill=(220, 227, 238, 255))
    return preview.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    iron = load("iron_ingot.png")
    gold = load("gold_ingot.png")
    variants = [
        ("IRON REF", iron),
        ("GOLD REF", gold),
        ("A MIX", variant_average(iron, gold)),
        ("B CORE", variant_gold_core(iron, gold)),
        ("C HALF", variant_half(iron, gold)),
        ("D RIM", variant_gold_rim(iron, gold)),
        ("E DARK", variant_dark_alloy(iron, gold)),
        ("F STACK", variant_two_ingots(iron, gold)),
    ]
    for label, icon in variants[2:]:
        icon.resize((SIZE, SIZE), Image.Resampling.NEAREST).save(
            OUT_DIR / f"gunsmith-material-alloy-vanilla-{label.split()[0].lower()}.png")
    make_preview(variants).save(OUT_DIR / "gunsmith-material-alloy-vanilla-preview.png")


if __name__ == "__main__":
    main()
