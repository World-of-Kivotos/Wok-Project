from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "dist"
REF_DIR = OUT_DIR / "vanilla_refs"
SIZE = 64


def load_mask() -> Image.Image:
    iron = Image.open(REF_DIR / "iron_ingot.png").convert("RGBA")
    return iron.getchannel("A").point(lambda a: 255 if a > 0 else 0)


def edge_mask(mask: Image.Image) -> set[tuple[int, int]]:
    edge: set[tuple[int, int]] = set()
    pix = mask.load()
    for y in range(16):
        for x in range(16):
            if pix[x, y] == 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx = x + dx
                ny = y + dy
                if nx < 0 or ny < 0 or nx >= 16 or ny >= 16 or pix[nx, ny] == 0:
                    edge.add((x, y))
                    break
    return edge


def make_icon(body: tuple[int, int, int], edge: tuple[int, int, int],
              glow: tuple[int, int, int] | None = None) -> Image.Image:
    mask = load_mask()
    edges = edge_mask(mask)
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    pix = out.load()
    mp = mask.load()
    for y in range(16):
        for x in range(16):
            if mp[x, y] == 0:
                continue
            pix[x, y] = (*body, 255)
    for x, y in edges:
        pix[x, y] = (*edge, 255)

    # A tiny controlled shine line keeps the solid style readable in a slot.
    shine = glow or edge
    for x, y in ((5, 5), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5)):
        if mp[x, y] > 0:
            pix[x, y] = (*shine, 255)
    for x, y in ((8, 8), (9, 8), (10, 8)):
        if mp[x, y] > 0:
            pix[x, y] = tuple(max(0, c - 42) for c in body) + (255,)
    return out.resize((SIZE, SIZE), Image.Resampling.NEAREST)


def make_preview(entries: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 88
    label_h = 16
    cols = 3
    rows = 2
    preview = Image.new("RGBA", (cols * cell, rows * (cell + label_h)), (20, 24, 32, 255))
    draw = ImageDraw.Draw(preview)
    for i, (label, icon) in enumerate(entries):
        x = (i % cols) * cell
        y = (i // cols) * (cell + label_h)
        draw.rectangle([x + 6, y + 6, x + cell - 6, y + cell - 6], fill=(28, 33, 42, 255))
        draw.rectangle([x + 7, y + 7, x + cell - 7, y + cell - 7], outline=(70, 77, 92, 255))
        preview.alpha_composite(icon, (x + 12, y + 10))
        draw.text((x + 9, y + cell - 2), label, fill=(220, 227, 238, 255))
    return preview.convert("RGB")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    specs = [
        ("A 黑青边", (14, 20, 28), (64, 255, 223), (190, 255, 246)),
        ("B 紫粉边", (44, 28, 72), (224, 92, 255), (250, 194, 255)),
        ("C 蓝白边", (27, 74, 132), (212, 242, 255), (116, 202, 255)),
        ("D 红金边", (105, 25, 34), (255, 198, 70), (255, 105, 82)),
        ("E 墨绿边", (24, 72, 54), (105, 255, 142), (197, 255, 205)),
        ("F 白紫边", (198, 204, 214), (156, 92, 255), (246, 246, 255)),
    ]
    entries: list[tuple[str, Image.Image]] = []
    for i, (label, body, edge, glow) in enumerate(specs, 1):
        icon = make_icon(body, edge, glow)
        icon.save(OUT_DIR / f"gunsmith-material-alloy-solid-edge-{i:02d}.png")
        entries.append((label, icon))
    make_preview(entries).save(OUT_DIR / "gunsmith-material-alloy-solid-edge-preview.png")


if __name__ == "__main__":
    main()
