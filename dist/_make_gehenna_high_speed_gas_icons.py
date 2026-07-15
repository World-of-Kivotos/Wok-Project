from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
DIST_DIR = ROOT / "dist"
SOURCE = DIST_DIR / "gunsmith-gehenna-high-speed-gas-source.png"
ITEM_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item"
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/item"
PREVIEW = DIST_DIR / "gunsmith-gehenna-high-speed-gas-quality-preview.png"

NAME = "gunsmith_part_ar_core_gehenna_high_speed_gas"
SIZE = 64
# The two-pixel quality outline must also stay inside the 56x56 safe area.
BASE_SAFE_MAX = 52
QUALITIES = {
    "common": (233, 238, 247, 255),
    "improved": (71, 227, 124, 255),
    "milspec": (86, 168, 255, 255),
    "precision": (197, 108, 255, 255),
    "legendary": (255, 55, 72, 255),
}
SEPARATOR = (7, 9, 11, 255)


def solid_alpha(image: Image.Image) -> Image.Image:
    return image.getchannel("A").point(lambda value: 255 if value >= 128 else 0)


def clear_transparent_rgb(image: Image.Image) -> Image.Image:
    cleaned = Image.new("RGBA", image.size, (0, 0, 0, 0))
    cleaned.paste(image, (0, 0), image.getchannel("A"))
    return cleaned


def source_lowres_icon() -> tuple[Image.Image, tuple[int, int, int, int]]:
    source = Image.open(SOURCE).convert("RGBA")
    alpha = solid_alpha(source)
    source_bbox = alpha.getbbox()
    if source_bbox is None:
        raise ValueError(f"Source image has no visible pixels: {SOURCE}")

    cropped = source.crop(source_bbox)
    cropped_alpha = alpha.crop(source_bbox)
    scale = min(BASE_SAFE_MAX / cropped.width, BASE_SAFE_MAX / cropped.height)
    resized_size = (
        max(1, round(cropped.width * scale)),
        max(1, round(cropped.height * scale)),
    )

    # Nearest-neighbour sampling keeps the supplied silhouette and produces a crisp low-resolution icon.
    lowres = cropped.resize(resized_size, Image.Resampling.NEAREST)
    lowres_alpha = cropped_alpha.resize(resized_size, Image.Resampling.NEAREST)
    lowres.putalpha(lowres_alpha)
    lowres = clear_transparent_rgb(lowres)

    result = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    offset = ((SIZE - resized_size[0]) // 2, (SIZE - resized_size[1]) // 2)
    result.alpha_composite(lowres, offset)
    return clear_transparent_rgb(result), source_bbox


def dilate(mask: Image.Image, radius: int) -> Image.Image:
    width, height = mask.size
    source = mask.load()
    result = Image.new("L", mask.size, 0)
    target = result.load()
    radius_squared = radius * radius
    for y in range(height):
        for x in range(width):
            if source[x, y] == 0:
                continue
            for dy in range(-radius, radius + 1):
                target_y = y + dy
                if target_y < 0 or target_y >= height:
                    continue
                for dx in range(-radius, radius + 1):
                    target_x = x + dx
                    if target_x < 0 or target_x >= width:
                        continue
                    if dx * dx + dy * dy <= radius_squared + radius:
                        target[target_x, target_y] = 255
    return result


def outlined(base: Image.Image, color: tuple[int, int, int, int]) -> Image.Image:
    alpha = solid_alpha(base)
    outer = dilate(alpha, 2)
    separator = dilate(alpha, 1)
    result = Image.new("RGBA", base.size, (0, 0, 0, 0))
    result.paste(Image.new("RGBA", base.size, color), (0, 0), outer)
    result.paste(Image.new("RGBA", base.size, SEPARATOR), (0, 0), separator)
    result.alpha_composite(base)
    return clear_transparent_rgb(result)


def write_model(quality: str) -> Path:
    name = f"{NAME}_{quality}"
    path = MODEL_DIR / f"{name}.json"
    data = {
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": f"miningdim:item/{name}",
        },
    }
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def checkerboard(width: int, height: int, tile: int = 16) -> Image.Image:
    image = Image.new("RGBA", (width, height), (26, 30, 38, 255))
    draw = ImageDraw.Draw(image)
    colors = ((31, 36, 45, 255), (45, 51, 62, 255))
    for y in range(0, height, tile):
        for x in range(0, width, tile):
            draw.rectangle(
                (x, y, min(width - 1, x + tile - 1), min(height - 1, y + tile - 1)),
                fill=colors[(x // tile + y // tile) % 2],
            )
    return image


def save_preview(generated: dict[str, Image.Image]) -> None:
    scale = 4
    icon_size = SIZE * scale
    cell_width = 288
    cell_height = 304
    preview = checkerboard(cell_width * len(QUALITIES), cell_height)
    draw = ImageDraw.Draw(preview)
    for index, (quality, color) in enumerate(QUALITIES.items()):
        left = index * cell_width
        draw.rectangle(
            (left + 8, 8, left + cell_width - 9, cell_height - 9),
            outline=color,
            width=4,
        )
        enlarged = generated[quality].resize((icon_size, icon_size), Image.Resampling.NEAREST)
        preview.alpha_composite(enlarged, (left + (cell_width - icon_size) // 2, 12))
        draw.text((left + 16, 278), quality, fill=(238, 241, 247, 255))
    preview.save(PREVIEW)


def validate_icon(path: Path, quality_color: tuple[int, int, int, int]) -> None:
    icon = Image.open(path).convert("RGBA")
    if icon.size != (SIZE, SIZE):
        raise ValueError(f"Unexpected icon size for {path}: {icon.size}")
    alpha_bbox = icon.getchannel("A").getbbox()
    if alpha_bbox is None:
        raise ValueError(f"Generated icon is empty: {path}")
    margins = (
        alpha_bbox[0],
        alpha_bbox[1],
        SIZE - alpha_bbox[2],
        SIZE - alpha_bbox[3],
    )
    if min(margins) < 4:
        raise ValueError(f"Icon exceeds 56x56 safe area for {path}: margins={margins}")
    if not any(
        icon.getpixel((x, y)) == quality_color
        for y in range(SIZE)
        for x in range(SIZE)
    ):
        raise ValueError(f"Quality outline color is missing from {path}")


def main() -> None:
    if not SOURCE.exists():
        raise FileNotFoundError(SOURCE)
    ITEM_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    DIST_DIR.mkdir(parents=True, exist_ok=True)

    base, source_bbox = source_lowres_icon()
    generated: dict[str, Image.Image] = {}
    for quality, color in QUALITIES.items():
        icon = outlined(base, color)
        texture_path = ITEM_DIR / f"{NAME}_{quality}.png"
        icon.save(texture_path)
        write_model(quality)
        validate_icon(texture_path, color)
        generated[quality] = icon

    save_preview(generated)
    print(f"Source visible bounds: {source_bbox}")
    print(f"Generated {len(generated)} quality icons for {NAME}")
    print(PREVIEW)


if __name__ == "__main__":
    main()
