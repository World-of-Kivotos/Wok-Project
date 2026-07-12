from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ITEM_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item"
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/item"
OUT_DIR = ROOT / "dist"
BOLT_SOURCE = OUT_DIR / "gunsmith-bolt-source.png"
AR_GAS_BLOCK_SOURCE = OUT_DIR / "gunsmith-ar-gas-block-source.png"
AK_GAS_SOURCE = OUT_DIR / "gunsmith-ak-gas-source.png"
AK_BARREL_SOURCE = OUT_DIR / "gunsmith-ak-barrel-source.png"
GRIP_SOURCE = OUT_DIR / "gunsmith-grip-source.png"
AK_GRIP_SOURCE = OUT_DIR / "gunsmith-ak-grip-source.png"
STOCK_APPROVED = OUT_DIR / "gunsmith-stock-approved.png"
AK_STOCK_SOURCE = OUT_DIR / "gunsmith-ak-stock-source.png"
AK_HANDGUARD_SOURCE = OUT_DIR / "gunsmith-ak-handguard-source.png"

PLATFORMS = ("ar", "ak")
PARTS = ("core", "barrel", "bolt", "handguard", "grip", "stock")
QUALITIES = {
    "common": (233, 238, 247, 255),
    "improved": (71, 227, 124, 255),
    "milspec": (86, 168, 255, 255),
    "precision": (197, 108, 255, 255),
    "legendary": (255, 55, 72, 255),
}

SCALE = 3
SIZE = 64


def rgba(color: tuple[int, int, int]) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], 255


C = {
    "void": rgba((7, 9, 11)),
    "black": rgba((11, 14, 17)),
    "shadow": rgba((19, 24, 27)),
    "deep": rgba((27, 34, 37)),
    "body": rgba((44, 49, 52)),
    "body2": rgba((54, 60, 64)),
    "mid": rgba((75, 81, 84)),
    "hi": rgba((124, 130, 132)),
    "edge": rgba((163, 168, 166)),
    "cool": rgba((23, 38, 39)),
}


def sc(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def box(x1: float, y1: float, x2: float, y2: float) -> list[int]:
    return [round(x1 * SCALE), round(y1 * SCALE), round(x2 * SCALE), round(y2 * SCALE)]


def rect(draw: ImageDraw.ImageDraw, xy: tuple[float, float, float, float], fill: tuple[int, int, int, int]) -> None:
    draw.rectangle(box(*xy), fill=fill)


def poly(draw: ImageDraw.ImageDraw, points: list[tuple[float, float]], fill: tuple[int, int, int, int]) -> None:
    draw.polygon(sc(points), fill=fill)


def line(
    draw: ImageDraw.ImageDraw,
    points: list[tuple[float, float]],
    fill: tuple[int, int, int, int],
    width: float = 1.0,
) -> None:
    draw.line(sc(points), fill=fill, width=max(1, round(width * SCALE)))


def ellipse(draw: ImageDraw.ImageDraw, xy: tuple[float, float, float, float], fill: tuple[int, int, int, int]) -> None:
    draw.ellipse(box(*xy), fill=fill)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def finish(image: Image.Image) -> Image.Image:
    return image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def source_lowres_icon(source: Path, max_w: int = 58, max_h: int = 54,
                       light_background: bool = False) -> Image.Image:
    src = Image.open(source).convert("RGBA")
    pixels = src.load()
    width, height = src.size

    def is_background(x: int, y: int) -> bool:
        r, g, b, a = pixels[x, y]
        if a == 0:
            return True
        # Keep holes inside the item by removing only background pixels connected to the image edge.
        if max(r, g, b) <= 10:
            return True
        if light_background and min(r, g, b) >= 170 and max(r, g, b) - min(r, g, b) <= 35:
            return True
        return False

    visited = [[False] * width for _ in range(height)]
    stack: list[tuple[int, int]] = []
    for x in range(width):
        stack.append((x, 0))
        stack.append((x, height - 1))
    for y in range(height):
        stack.append((0, y))
        stack.append((width - 1, y))

    while stack:
        x, y = stack.pop()
        if x < 0 or x >= width or y < 0 or y >= height or visited[y][x]:
            continue
        visited[y][x] = True
        if not is_background(x, y):
            continue
        stack.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))

    alpha = Image.new("L", src.size, 255)
    alpha_pixels = alpha.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if visited[y][x] and is_background(x, y):
                alpha_pixels[x, y] = 0
            elif light_background and a and min(r, g, b) >= 185 and max(r, g, b) - min(r, g, b) <= 45:
                alpha_pixels[x, y] = 0
    src.putalpha(alpha)

    bbox = alpha.getbbox()
    if bbox is None:
        return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    cropped = src.crop(bbox)
    scale = min(max_w / cropped.width, max_h / cropped.height)
    new_size = (max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale)))
    lowres = cropped.resize(new_size, Image.Resampling.LANCZOS)

    result = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    result.alpha_composite(lowres, ((SIZE - new_size[0]) // 2, (SIZE - new_size[1]) // 2))
    return result


def approved_icon_base(source: Path) -> Image.Image:
    src = Image.open(source).convert("RGBA")
    # The approved stock reference already includes the legendary red outline.
    # Remove that outline and its immediate black separator, then regenerate all quality outlines.
    red_mask = Image.new("L", src.size, 0)
    red_pixels = red_mask.load()
    pixels = src.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = pixels[x, y]
            if a and r > 120 and g < 95 and b < 95:
                red_pixels[x, y] = 255

    remove_mask = dilate(red_mask, 1)
    remove_pixels = remove_mask.load()
    for y in range(src.height):
        for x in range(src.width):
            if remove_pixels[x, y]:
                pixels[x, y] = (0, 0, 0, 0)
    return src


def draw_rail(draw: ImageDraw.ImageDraw, x: int, y: int, length: int, teeth: int, slant: int = 0) -> None:
    rect(draw, (x, y + 3, x + length, y + 6), C["shadow"])
    rect(draw, (x + 1, y + 5, x + length - 1, y + 8), C["body"])
    step = max(3, length // teeth)
    for i in range(teeth):
        tx = x + i * step
        poly(draw, [(tx, y + 1), (tx + step - 1, y + 1 + slant), (tx + step, y + 5), (tx - 1, y + 5)], C["mid"])
        line(draw, [(tx + 0.6, y + 1.7), (tx + step - 2, y + 1.9 + slant)], C["edge"], 0.55)


def draw_cutouts(draw: ImageDraw.ImageDraw, start_x: int, y: int, count: int, step: int, tall: bool = False) -> None:
    for i in range(count):
        x = start_x + i * step
        if tall:
            ellipse(draw, (x, y, x + 4, y + 6), C["void"])
        else:
            rect(draw, (x, y, x + 3, y + 2), C["void"])


def base_core(platform: str) -> Image.Image:
    if platform == "ar" and AR_GAS_BLOCK_SOURCE.exists():
        return source_lowres_icon(AR_GAS_BLOCK_SOURCE, 54, 54)
    if platform == "ak" and AK_GAS_SOURCE.exists():
        return source_lowres_icon(AK_GAS_SOURCE, 54, 54)

    image, d = canvas()
    if platform == "ar":
        # Low-profile AR gas block with a slim gas tube.
        rect(d, (7, 28, 53, 31), C["body2"])
        rect(d, (8, 29, 52, 30), C["edge"])
        rect(d, (10, 34, 55, 38), C["shadow"])
        rect(d, (14, 32, 29, 44), C["body"])
        poly(d, [(15, 32), (29, 32), (27, 43), (17, 43)], C["body2"])
        rect(d, (18, 36, 25, 40), C["deep"])
        rect(d, (31, 33, 42, 39), C["body"])
        rect(d, (34, 35, 39, 37), C["deep"])
        ellipse(d, (3, 27, 10, 33), C["shadow"])
        ellipse(d, (5, 28, 9, 32), C["void"])
        rect(d, (52, 26, 59, 33), C["body"])
        line(d, [(8, 35), (54, 35)], C["cool"], 0.8)
        line(d, [(14, 32), (29, 32)], C["hi"], 0.65)
        line(d, [(45, 29), (53, 29)], C["hi"], 0.55)
    else:
        # AK-style gas tube / piston assembly, heavier than the AR gas system.
        rect(d, (8, 25, 50, 30), C["body"])
        rect(d, (10, 26, 48, 27), C["edge"])
        rect(d, (12, 31, 57, 37), C["body2"])
        rect(d, (15, 33, 53, 35), C["shadow"])
        rect(d, (21, 28, 34, 42), C["body"])
        poly(d, [(22, 29), (34, 29), (32, 41), (24, 41)], C["body2"])
        rect(d, (25, 34, 31, 38), C["deep"])
        ellipse(d, (3, 24, 12, 32), C["shadow"])
        ellipse(d, (6, 26, 11, 31), C["void"])
        rect(d, (48, 23, 58, 33), C["body"])
        rect(d, (51, 25, 56, 30), C["deep"])
        for x in (15, 39, 45):
            rect(d, (x, 32, x + 2, 36), C["black"])
        line(d, [(12, 31), (56, 31)], C["hi"], 0.55)
        line(d, [(12, 37), (55, 37)], C["black"], 0.8)
    return finish(image)


def base_barrel(platform: str) -> Image.Image:
    if platform == "ak" and AK_BARREL_SOURCE.exists():
        return source_lowres_icon(AK_BARREL_SOURCE, 60, 34)

    source = ITEM_DIR / "gunsmith_barrel.png"
    if source.exists():
        return source_lowres_icon(source, 60, 34)

    image, d = canvas()
    y = 31 if platform == "ar" else 30
    rect(d, (8, y, 52, y + 5), C["body"])
    rect(d, (10, y + 1, 49, y + 2), C["edge"])
    rect(d, (12, y + 4, 51, y + 6), C["shadow"])
    rect(d, (51, y - 2, 58, y + 8), C["body2"])
    ellipse(d, (56, y - 1, 62, y + 7), C["black"])
    ellipse(d, (57, y + 1, 61, y + 5), C["deep"])
    rect(d, (3, y + 1, 10, y + 4), C["body2"])
    ellipse(d, (1, y, 6, y + 6), C["black"])
    if platform == "ak":
        rect(d, (24, y - 5, 31, y), C["deep"])
        rect(d, (36, y - 3, 42, y + 1), C["body2"])
        line(d, [(25, y - 4), (30, y - 4)], C["hi"], 0.6)
    else:
        rect(d, (30, y - 2, 36, y + 7), C["deep"])
        line(d, [(31, y - 1), (35, y - 1)], C["hi"], 0.6)
    return finish(image)


def base_bolt(platform: str) -> Image.Image:
    if BOLT_SOURCE.exists():
        return source_lowres_icon(BOLT_SOURCE, 60, 36)

    image, d = canvas()
    # Reset baseline placeholder: intentionally simple until the final source art is approved.
    rect(d, (18, 22, 44, 30), C["body2"])
    rect(d, (15, 28, 50, 39), C["body"])
    rect(d, (26, 39, 45, 47), C["mid"])
    rect(d, (12, 30, 21, 38), C["deep"])
    line(d, [(20, 24), (41, 24)], C["edge"], 0.7)
    line(d, [(18, 31), (48, 31)], C["black"], 1)
    rect(d, (30, 41, 43, 44), C["shadow"])
    return finish(image)


def base_handguard(platform: str) -> Image.Image:
    if platform == "ak" and AK_HANDGUARD_SOURCE.exists():
        return source_lowres_icon(AK_HANDGUARD_SOURCE, 54, 54)

    source = ITEM_DIR / "gunsmith_handguard.png"
    if source.exists():
        return source_lowres_icon(source, 60, 34)

    image, d = canvas()
    if platform == "ar":
        poly(d, [(7, 27), (56, 27), (59, 32), (56, 40), (11, 40), (7, 36)], C["body"])
        poly(d, [(10, 29), (53, 29), (55, 33), (53, 37), (12, 37)], C["body2"])
        draw_rail(d, 11, 21, 43, 13)
        draw_cutouts(d, 14, 32, 10, 4, True)
        draw_cutouts(d, 13, 37, 9, 5, False)
    else:
        poly(d, [(9, 28), (53, 26), (58, 31), (54, 39), (13, 40), (9, 36)], C["body"])
        poly(d, [(13, 30), (50, 29), (53, 33), (50, 37), (14, 37)], C["body2"])
        draw_rail(d, 14, 21, 37, 10, 1)
        draw_cutouts(d, 16, 32, 8, 4, True)
        for x in range(15, 50, 7):
            line(d, [(x, 37), (x + 5, 36)], C["black"], 0.7)
    line(d, [(11, 30), (53, 30)], C["edge"], 0.65)
    line(d, [(12, 39), (53, 39)], C["black"], 1)
    return finish(image)


def base_grip(platform: str) -> Image.Image:
    if platform == "ak" and AK_GRIP_SOURCE.exists():
        return source_lowres_icon(AK_GRIP_SOURCE, 48, 58)

    if GRIP_SOURCE.exists():
        return source_lowres_icon(GRIP_SOURCE, 48, 58)

    image, d = canvas()
    # Reset baseline placeholder: small vertical grip shape.
    rect(d, (22, 17, 43, 25), C["body2"])
    poly(d, [(27, 25), (39, 25), (38, 55), (27, 55)], C["body"])
    poly(d, [(30, 28), (37, 28), (36, 52), (30, 52)], C["deep"])
    line(d, [(24, 19), (41, 19)], C["edge"], 0.7)
    for y in (31, 37, 43, 49):
        rect(d, (31, y, 36, y + 2), C["mid"])
    return finish(image)


def base_stock(platform: str) -> Image.Image:
    if platform == "ak" and AK_STOCK_SOURCE.exists():
        return source_lowres_icon(AK_STOCK_SOURCE, 60, 38)

    if STOCK_APPROVED.exists():
        return approved_icon_base(STOCK_APPROVED)

    image, d = canvas()
    # Reset baseline placeholder: simple tube plus stock block.
    rect(d, (12, 31, 31, 36), C["body2"])
    poly(d, [(28, 21), (52, 21), (58, 30), (54, 47), (39, 47), (35, 38), (28, 37)], C["body"])
    poly(d, [(33, 25), (49, 25), (54, 31), (51, 42), (41, 42), (37, 34), (33, 34)], C["body2"])
    rect(d, (48, 26, 56, 42), C["deep"])
    line(d, [(34, 26), (49, 25)], C["edge"], 0.65)
    line(d, [(29, 36), (53, 36)], C["black"], 1)
    return finish(image)


DRAWERS = {
    "core": base_core,
    "barrel": base_barrel,
    "bolt": base_bolt,
    "handguard": base_handguard,
    "grip": base_grip,
    "stock": base_stock,
}


def dilate(mask: Image.Image, radius: int) -> Image.Image:
    width, height = mask.size
    src = mask.load()
    out = Image.new("L", mask.size, 0)
    dst = out.load()
    rr = radius * radius
    for y in range(height):
        for x in range(width):
            if src[x, y] == 0:
                continue
            for dy in range(-radius, radius + 1):
                yy = y + dy
                if yy < 0 or yy >= height:
                    continue
                for dx in range(-radius, radius + 1):
                    xx = x + dx
                    if xx < 0 or xx >= width:
                        continue
                    if dx * dx + dy * dy <= rr + radius:
                        dst[xx, yy] = 255
    return out


def outlined(base: Image.Image, color: tuple[int, int, int, int], radius: int = 2) -> Image.Image:
    alpha = base.getchannel("A").point(lambda value: 255 if value > 16 else 0)
    outer = dilate(alpha, radius)
    separator = dilate(alpha, 1)
    result = Image.new("RGBA", base.size, (0, 0, 0, 0))
    result.paste(Image.new("RGBA", base.size, color), (0, 0), outer)
    result.paste(Image.new("RGBA", base.size, C["void"]), (0, 0), separator)
    result.alpha_composite(base)
    return result


def save_textures() -> dict[tuple[str, str, str], Image.Image]:
    ITEM_DIR.mkdir(parents=True, exist_ok=True)
    generated: dict[tuple[str, str, str], Image.Image] = {}
    for platform in PLATFORMS:
        for part in PARTS:
            base = DRAWERS[part](platform)
            for quality, color in QUALITIES.items():
                image = outlined(base, color, 2)
                image.save(ITEM_DIR / f"gunsmith_part_{platform}_{part}_{quality}.png")
                generated[(platform, part, quality)] = image
    return generated


def custom_model_data(platform: str, part: str, quality: str) -> int:
    return PLATFORMS.index(platform) * 100 + PARTS.index(part) * 10 + list(QUALITIES).index(quality) + 1


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def save_models() -> None:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    overrides: list[dict] = []
    for platform in PLATFORMS:
        for part in PARTS:
            for quality in QUALITIES:
                name = f"gunsmith_part_{platform}_{part}_{quality}"
                write_json(MODEL_DIR / f"{name}.json", {
                    "parent": "minecraft:item/generated",
                    "textures": {
                        "layer0": f"miningdim:item/{name}"
                    }
                })
                overrides.append({
                    "predicate": {
                        "custom_model_data": custom_model_data(platform, part, quality)
                    },
                    "model": f"miningdim:item/{name}"
                })

    write_json(MODEL_DIR / "gunsmith_part.json", {
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": "miningdim:item/gunsmith_part_ar_core_common"
        },
        "overrides": overrides
    })


def save_preview(generated: dict[tuple[str, str, str], Image.Image]) -> None:
    cell = 72
    preview = Image.new("RGBA", (cell * len(PARTS), cell * len(PLATFORMS)), rgba((21, 25, 32)))
    draw = ImageDraw.Draw(preview)
    for py, platform in enumerate(PLATFORMS):
        for px, part in enumerate(PARTS):
            x = px * cell
            y = py * cell
            draw.rectangle([x + 5, y + 5, x + cell - 6, y + cell - 6], outline=rgba((64, 72, 88)), width=2)
            preview.alpha_composite(generated[(platform, part, "legendary")], (x + 4, y + 4))
    preview.save(OUT_DIR / "gunsmith-all-parts-outline-preview.png")

    qcell = 76
    quality_preview = Image.new("RGBA", (qcell * len(QUALITIES), qcell), rgba((21, 25, 32)))
    qdraw = ImageDraw.Draw(quality_preview)
    for index, quality in enumerate(QUALITIES):
        x = index * qcell
        qdraw.rectangle([x + 5, 5, x + qcell - 6, qcell - 6], outline=rgba((64, 72, 88)), width=2)
        quality_preview.alpha_composite(generated[("ar", "handguard", quality)], (x + 6, 6))
    quality_preview.save(OUT_DIR / "gunsmith-quality-outline-preview.png")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    generated = save_textures()
    save_models()
    save_preview(generated)
    print(f"Generated {len(generated)} gunsmith part icons")
    print(OUT_DIR / "gunsmith-all-parts-outline-preview.png")


if __name__ == "__main__":
    main()
