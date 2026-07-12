from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
REF_DIR = OUT_DIR / "vanilla_refs"
SIZE = 64


Color = tuple[int, int, int]


def load_iron() -> Image.Image:
    return Image.open(REF_DIR / "iron_ingot.png").convert("RGBA")


def blend(a: Color, b: Color, t: float) -> Color:
    return tuple(round(a[i] * (1.0 - t) + b[i] * t) for i in range(3))


def luminance(pixel: tuple[int, int, int, int]) -> float:
    r, g, b, _ = pixel
    return (r * 0.299 + g * 0.587 + b * 0.114) / 255.0


def border_pixels(mask: Image.Image) -> set[tuple[int, int]]:
    pix = mask.load()
    edges: set[tuple[int, int]] = set()
    for y in range(16):
        for x in range(16):
            if pix[x, y] == 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx = x + dx
                ny = y + dy
                if nx < 0 or ny < 0 or nx >= 16 or ny >= 16 or pix[nx, ny] == 0:
                    edges.add((x, y))
                    break
    return edges


def map_body(value: float, dark: Color, mid: Color, light: Color) -> Color:
    if value < 0.52:
        return blend(dark, mid, value / 0.52)
    return blend(mid, light, (value - 0.52) / 0.48)


def make_icon(label: str, dark: Color, mid: Color, light: Color, edge: Color) -> Image.Image:
    iron = load_iron()
    mask = iron.getchannel("A").point(lambda a: 255 if a > 0 else 0)
    edges = border_pixels(mask)
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    src = iron.load()
    mp = mask.load()
    pix = out.load()

    for y in range(16):
        for x in range(16):
            if mp[x, y] == 0:
                continue
            value = luminance(src[x, y])
            color = map_body(value, dark, mid, light)
            if (x, y) in edges:
                color = blend(color, edge, 0.55)
            pix[x, y] = (*color, 255)

    # Small sci-fi edge highlights, but the ingot remains a normal ingot silhouette.
    for x, y in ((4, 5), (5, 5), (6, 5), (10, 6), (11, 6)):
        if mp[x, y] > 0:
            pix[x, y] = (*blend(light, edge, 0.65), 255)
    for x, y in ((4, 9), (5, 9), (9, 10), (10, 10)):
        if mp[x, y] > 0:
            pix[x, y] = (*blend(dark, edge, 0.35), 255)

    out.resize((SIZE, SIZE), Image.Resampling.NEAREST).save(
        OUT_DIR / f"gunsmith-material-alloy-shaded-{label.lower()}.png")
    return out


def preview(entries: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 88
    label_h = 16
    cols = 3
    rows = 2
    out = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), (20, 24, 32, 255))
    draw = ImageDraw.Draw(out)
    for i, (label, icon16) in enumerate(entries):
        x = (i % cols) * cell
        y = (i // cols) * (cell + label_h)
        draw.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], fill=(28, 33, 42, 255))
        draw.rectangle([x + 7, y + 7, x + cell - 7, y + cell - 7], outline=(70, 77, 92, 255))
        out.alpha_composite(icon16.resize((64, 64), Image.Resampling.NEAREST), (x + 12, y + 10))
        draw.text((x + 10, y + cell - 2), label, fill=(220, 227, 238, 255))
    return out.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    specs = [
        ("A 深蓝青边", (10, 24, 42), (38, 88, 130), (136, 205, 235), (75, 255, 224)),
        ("B 暗紫粉边", (29, 19, 49), (88, 52, 130), (194, 146, 238), (244, 89, 255)),
        ("C 枪灰蓝边", (28, 34, 43), (77, 91, 106), (177, 190, 201), (82, 170, 255)),
        ("D 黑红金边", (42, 16, 21), (121, 39, 49), (228, 133, 111), (255, 199, 77)),
        ("E 墨绿荧边", (13, 34, 28), (42, 105, 75), (147, 218, 166), (95, 255, 137)),
        ("F 银白紫边", (78, 81, 91), (160, 166, 181), (236, 238, 244), (172, 96, 255)),
    ]
    icons = [(name, make_icon(chr(ord("a") + i), dark, mid, light, edge))
             for i, (name, dark, mid, light, edge) in enumerate(specs)]
    preview(icons).save(OUT_DIR / "gunsmith-material-alloy-shaded-preview.png")


if __name__ == "__main__":
    main()
