from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "塔罗牌图片资源"
CARD_BACK_SOURCE = ROOT / "docs/assets/tarot/tarot_card_back_concept_v1.png"
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item/tarot"
HAND_TEXTURE_DIR = TEXTURE_DIR / "hand"
GUI_CARD_DIR = ROOT / "src/main/resources/assets/miningdim/textures/gui/tarot/cards"
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/item"
THIN_CARD_PARENT = "miningdim:item/tarot_card_thin"

CANVAS_SIZE = 256
PREVIEW_WIDTH = 184
HAND_CARD_HEIGHT = 248
HAND_CARD_WIDTH = 140
CARD_FILES = {
    0: "The Fool 0.png",
    1: "魔术师 The Magician I.png",
    2: "女祭司 The High Priestess II.png",
    3: "女皇 The Empress III.png",
    4: "皇帝 The Emperor IV.png",
    5: "教皇 The Hierophant or the Pope V.png",
    6: "恋人 The Lovers VI.png",
    7: "战车 The Chariot VII.png",
    8: "力量 Strength VIII.png",
    9: "隐士 The Hermit IX.png",
    10: "命运之轮 The Wheel of Fortune X.png",
    11: "正义 Justice XI.png",
    12: "倒吊人 The Hanged Man XII.png",
    13: "死神 Death XIII.png",
    14: "节制 Temperance XIV.png",
    15: "恶魔 The Devil XV.png",
    16: "高塔 The Tower XVI.png",
    17: "星星 The Star XVII.png",
    18: "月亮 The Moon XVIII.png",
    19: "太阳 The Sun XIX.png",
    20: "审判 Judgement XX.png",
    21: "世界 The World XXI.png",
}

QUALITY_COLORS = {
    "r": (240, 247, 255, 255),
    "sr": (52, 126, 255, 255),
    "ssr": (166, 79, 255, 255),
    "ur": (255, 105, 184, 255),
    "shiny": (255, 49, 62, 255),
}


def build_card_textures() -> None:
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    GUI_CARD_DIR.mkdir(parents=True, exist_ok=True)
    for card_id, filename in CARD_FILES.items():
        source_path = SOURCE_DIR / filename
        if not source_path.is_file():
            raise FileNotFoundError(f"missing tarot source image: {source_path}")

        with Image.open(source_path) as source:
            source = source.convert("RGBA")
            preview_height = round(source.height * PREVIEW_WIDTH / source.width)
            preview = source.resize((PREVIEW_WIDTH, preview_height), Image.Resampling.LANCZOS)

            # 物品栏与手持统一使用完整竖牌。在方形 atlas 纹理内留透明侧边，实际可见轮廓
            # 是 140x248 的卡牌而不是方块；增强对比度/饱和度以抵抗 16x16 缩小时的发灰。
            item_source = ImageEnhance.Contrast(source).enhance(1.38)
            item_source = ImageEnhance.Color(item_source).enhance(1.50)
            item_source = ImageEnhance.Brightness(item_source).enhance(1.08)
            item_source = item_source.filter(ImageFilter.UnsharpMask(radius=1.4, percent=175, threshold=3))
            item_card = item_source.resize((HAND_CARD_WIDTH, HAND_CARD_HEIGHT), Image.Resampling.LANCZOS)
            item_texture = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
            item_texture.paste(
                item_card,
                ((CANVAS_SIZE - HAND_CARD_WIDTH) // 2, (CANVAS_SIZE - HAND_CARD_HEIGHT) // 2),
                item_card,
            )

        item_texture.save(TEXTURE_DIR / f"{card_id:02d}.png", optimize=True)
        item_texture.rotate(180).save(TEXTURE_DIR / f"{card_id:02d}_reversed.png", optimize=True)
        preview.save(GUI_CARD_DIR / f"{card_id:02d}.png", optimize=True)


def build_card_back_textures() -> None:
    """Downsample the approved symmetric concept into runtime card-back assets."""
    if not CARD_BACK_SOURCE.is_file():
        raise FileNotFoundError(f"missing tarot card-back source image: {CARD_BACK_SOURCE}")

    with Image.open(CARD_BACK_SOURCE) as source:
        source = source.convert("RGBA")
        source = ImageEnhance.Contrast(source).enhance(1.10)
        source = ImageEnhance.Color(source).enhance(1.08)
        source = source.filter(ImageFilter.UnsharpMask(radius=1.0, percent=130, threshold=2))
        preview = source.resize((PREVIEW_WIDTH, 326), Image.Resampling.LANCZOS)
        item_card = source.resize((HAND_CARD_WIDTH, HAND_CARD_HEIGHT), Image.Resampling.LANCZOS)

    item_texture = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    item_texture.paste(
        item_card,
        ((CANVAS_SIZE - HAND_CARD_WIDTH) // 2, (CANVAS_SIZE - HAND_CARD_HEIGHT) // 2),
        item_card,
    )
    preview.save(GUI_CARD_DIR / "card_back.png", optimize=True)
    item_texture.save(TEXTURE_DIR / "card_back.png", optimize=True)


def build_legacy_quality_borders() -> None:
    """Build Blue-Archive-inspired rarity frames on the shared 256px item canvas.

    The actual card remains 140x248.  Rarity decoration grows outwards from that
    silhouette instead of filling the square atlas, so inventory and hand-held
    rendering keep reading as a long tarot card.
    """
    hand_left = (CANVAS_SIZE - HAND_CARD_WIDTH) // 2
    hand_top = (CANVAS_SIZE - HAND_CARD_HEIGHT) // 2
    hand_bounds = (
        hand_left,
        hand_top,
        hand_left + HAND_CARD_WIDTH - 1,
        hand_top + HAND_CARD_HEIGHT - 1,
    )

    HAND_TEXTURE_DIR.mkdir(parents=True, exist_ok=True)

    def diamond(draw: ImageDraw.ImageDraw, x: int, y: int, radius: int,
                fill: tuple[int, int, int, int], outline: tuple[int, int, int, int],
                width: int = 1) -> None:
        points = [(x, y - radius), (x + radius, y), (x, y + radius), (x - radius, y)]
        draw.polygon(points, fill=fill)
        draw.line(points + [points[0]], fill=outline, width=width, joint="curve")

    def sparkle(draw: ImageDraw.ImageDraw, x: int, y: int, radius: int,
                 color: tuple[int, int, int, int]) -> None:
        draw.polygon(
            [(x, y - radius), (x + max(1, radius // 4), y - max(1, radius // 4)),
             (x + radius, y), (x + max(1, radius // 4), y + max(1, radius // 4)),
             (x, y + radius), (x - max(1, radius // 4), y + max(1, radius // 4)),
             (x - radius, y), (x - max(1, radius // 4), y - max(1, radius // 4))],
            fill=color,
        )

    def corner_brackets(draw: ImageDraw.ImageDraw, color: tuple[int, int, int, int],
                        reach: int, offset: int = 0, width: int = 2) -> None:
        left, top, right, bottom = hand_bounds
        left -= offset
        top -= offset
        right += offset
        bottom += offset
        segments = (
            ((left, top + reach), (left, top), (left + reach, top)),
            ((right - reach, top), (right, top), (right, top + reach)),
            ((left, bottom - reach), (left, bottom), (left + reach, bottom)),
            ((right - reach, bottom), (right, bottom), (right, bottom - reach)),
        )
        for points in segments:
            draw.line(points, fill=color, width=width, joint="curve")

    def add_glow(base: Image.Image, source: Image.Image, radius: float, opacity: float = 1.0) -> None:
        glow = source.filter(ImageFilter.GaussianBlur(radius))
        if opacity < 1.0:
            alpha = glow.getchannel("A").point(lambda value: int(value * opacity))
            glow.putalpha(alpha)
        base.alpha_composite(glow)

    def build_frame(quality: str) -> Image.Image:
        overlay = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
        glow_source = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
        glow_draw = ImageDraw.Draw(glow_source)
        direct = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(direct)
        left, top, right, bottom = hand_bounds

        # Deep academy-blue backing keeps pale frames legible on every card face.
        draw.rounded_rectangle(hand_bounds, radius=8, outline=(10, 23, 50, 245), width=7)

        if quality == "r":
            draw.rounded_rectangle((left + 1, top + 1, right - 1, bottom - 1), radius=7,
                                   outline=(224, 235, 248, 255), width=3)
            draw.rounded_rectangle((left + 4, top + 4, right - 4, bottom - 4), radius=5,
                                   outline=(105, 161, 206, 230), width=1)
            corner_brackets(draw, (119, 213, 244, 245), reach=11, offset=-2, width=2)
            diamond(draw, 128, bottom - 1, 3, (210, 245, 255, 255), (73, 151, 205, 255))

        elif quality == "sr":
            glow_draw.rounded_rectangle((left - 1, top - 1, right + 1, bottom + 1), radius=9,
                                        outline=(58, 222, 255, 230), width=5)
            glow_draw.arc((96, -11, 160, 39), 194, 346, fill=(72, 224, 255, 220), width=4)
            add_glow(overlay, glow_source, 3.2, 0.85)
            draw.rounded_rectangle(hand_bounds, radius=8, outline=(68, 225, 255, 255), width=4)
            draw.rounded_rectangle((left + 4, top + 4, right - 4, bottom - 4), radius=5,
                                   outline=(214, 250, 255, 235), width=2)
            draw.arc((96, -11, 160, 39), 194, 346, fill=(117, 237, 255, 255), width=3)
            draw.arc((103, -5, 153, 33), 196, 344, fill=(225, 253, 255, 235), width=1)
            corner_brackets(draw, (232, 253, 255, 255), reach=14, offset=2, width=2)
            for x, y in ((left, top + 18), (right, top + 18), (left, bottom - 18), (right, bottom - 18)):
                diamond(draw, x, y, 4, (68, 225, 255, 255), (232, 253, 255, 255))

        elif quality == "ssr":
            glow_draw.rounded_rectangle((left - 2, top - 2, right + 2, bottom + 2), radius=10,
                                        outline=(107, 174, 255, 225), width=6)
            glow_draw.arc((89, -16, 167, 48), 192, 348, fill=(155, 97, 255, 235), width=5)
            add_glow(overlay, glow_source, 4.2, 0.9)
            draw.rounded_rectangle((left - 1, top - 1, right + 1, bottom + 1), radius=9,
                                   outline=(105, 180, 255, 255), width=4)
            draw.rounded_rectangle((left + 3, top + 3, right - 3, bottom - 3), radius=6,
                                   outline=(190, 100, 255, 255), width=3)
            draw.rounded_rectangle((left + 6, top + 6, right - 6, bottom - 6), radius=4,
                                   outline=(223, 239, 255, 220), width=1)
            draw.arc((89, -16, 167, 48), 192, 348, fill=(118, 204, 255, 255), width=3)
            draw.arc((98, -9, 158, 39), 197, 343, fill=(203, 126, 255, 255), width=2)
            corner_brackets(draw, (229, 235, 255, 255), reach=17, offset=4, width=2)
            for x, y in ((left - 2, top + 22), (right + 2, top + 22),
                         (left - 2, bottom - 22), (right + 2, bottom - 22)):
                diamond(draw, x, y, 6, (139, 104, 255, 255), (220, 245, 255, 255), width=2)
            for x, y in ((48, 91), (208, 164), (128, 1), (128, 252)):
                sparkle(draw, x, y, 4, (161, 225, 255, 230))

        elif quality == "ur":
            glow_draw.rounded_rectangle((left - 3, top - 2, right + 3, bottom + 2), radius=10,
                                        outline=(255, 183, 65, 235), width=7)
            glow_draw.ellipse((86, -25, 170, 51), outline=(255, 207, 92, 220), width=5)
            add_glow(overlay, glow_source, 4.8, 0.95)
            draw.rounded_rectangle((left - 2, top - 1, right + 2, bottom + 1), radius=9,
                                   outline=(255, 190, 70, 255), width=5)
            draw.rounded_rectangle((left + 3, top + 3, right - 3, bottom - 3), radius=6,
                                   outline=(245, 248, 238, 255), width=2)
            draw.rounded_rectangle((left + 6, top + 6, right - 6, bottom - 6), radius=4,
                                   outline=(70, 218, 246, 235), width=2)
            draw.arc((86, -25, 170, 51), 183, 357, fill=(255, 205, 83, 255), width=4)
            draw.arc((95, -17, 161, 42), 187, 353, fill=(108, 229, 250, 255), width=2)
            draw.arc((37, 79, 219, 203), 124, 236, fill=(80, 206, 245, 170), width=1)
            draw.arc((37, 79, 219, 203), 304, 56, fill=(255, 201, 91, 190), width=2)
            corner_brackets(draw, (255, 224, 149, 255), reach=19, offset=5, width=3)
            for x, y in ((left - 4, 68), (right + 4, 68), (left - 4, 190), (right + 4, 190)):
                diamond(draw, x, y, 7, (255, 183, 55, 255), (255, 245, 202, 255), width=2)
            sparkle(draw, 128, 2, 8, (255, 238, 176, 255))
            diamond(draw, 128, bottom + 1, 7, (64, 215, 246, 255), (255, 234, 155, 255), width=2)

        elif quality == "shiny":
            glow_draw.rounded_rectangle((left - 5, top - 3, right + 5, bottom + 3), radius=11,
                                        outline=(255, 247, 199, 245), width=9)
            glow_draw.ellipse((75, -36, 181, 61), outline=(255, 247, 181, 235), width=7)
            glow_draw.ellipse((88, -24, 168, 49), outline=(79, 229, 255, 235), width=5)
            add_glow(overlay, glow_source, 6.2, 1.0)
            draw.rounded_rectangle((left - 4, top - 2, right + 4, bottom + 2), radius=10,
                                   outline=(255, 253, 224, 255), width=6)
            draw.rounded_rectangle((left + 1, top + 2, right - 1, bottom - 2), radius=7,
                                   outline=(255, 205, 99, 255), width=3)
            draw.rounded_rectangle((left + 5, top + 6, right - 5, bottom - 6), radius=4,
                                   outline=(87, 227, 255, 255), width=2)
            draw.arc((75, -36, 181, 61), 181, 359, fill=(255, 249, 206, 255), width=5)
            draw.arc((88, -24, 168, 49), 185, 355, fill=(91, 230, 255, 255), width=3)
            draw.arc((98, -15, 158, 39), 188, 352, fill=(238, 147, 255, 255), width=2)
            corner_brackets(draw, (255, 251, 218, 255), reach=21, offset=7, width=3)

            prism_colors = (
                (110, 231, 255, 255), (187, 139, 255, 255),
                (255, 179, 226, 255), (255, 229, 130, 255),
            )
            crystal_points = (
                (left - 10, 38, 0), (right + 10, 38, 1),
                (left - 11, 216, 2), (right + 11, 216, 3),
                (left - 15, 129, 1), (right + 15, 129, 0),
            )
            for x, y, color_index in crystal_points:
                radius = 8 if y != 129 else 6
                diamond(draw, x, y, radius, prism_colors[color_index],
                        (255, 253, 232, 255), width=2)
            sparkle(draw, 128, 1, 11, (255, 252, 220, 255))
            sparkle(draw, 128, 251, 9, (102, 231, 255, 255))
            for x, y, radius, color in (
                (39, 82, 5, prism_colors[0]), (216, 72, 4, prism_colors[3]),
                (44, 179, 4, prism_colors[2]), (213, 188, 5, prism_colors[1]),
                (33, 128, 3, (255, 255, 255, 245)), (223, 143, 3, (255, 255, 255, 245)),
            ):
                sparkle(draw, x, y, radius, color)

        else:
            raise ValueError(f"unknown tarot quality: {quality}")

        overlay.alpha_composite(direct)
        return overlay

    for quality in QUALITY_COLORS:
        overlay = build_frame(quality)
        overlay.save(TEXTURE_DIR / f"border_{quality}.png", optimize=True)
        overlay.rotate(180).save(TEXTURE_DIR / f"border_{quality}_reversed.png", optimize=True)
        # Keep the legacy hand namespace synchronized; current generated models use
        # the main overlay, while custom/preview renderers may still address hand/.
        overlay.save(HAND_TEXTURE_DIR / f"border_{quality}.png", optimize=True)


def build_quality_borders() -> None:
    """Build the approved multicolor frames and keep hand textures synchronized."""
    from generate_tarot_quality_borders import save_assets

    assets = save_assets()
    HAND_TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for quality, overlay in assets.items():
        overlay.save(HAND_TEXTURE_DIR / f"border_{quality}.png", optimize=True)


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_item_models() -> None:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    overrides = []
    quality_names = tuple(QUALITY_COLORS)

    write_json(
        MODEL_DIR / "tarot_card_thin.json",
        {
            "parent": "builtin/entity",
            "gui_light": "front",
            "display": {
                "thirdperson_righthand": {
                    "rotation": [0, -90, 55],
                    "translation": [0, 4, 0.5],
                    "scale": [0.85, 0.85, 0.12],
                },
                "thirdperson_lefthand": {
                    "rotation": [0, 90, -55],
                    "translation": [0, 4, 0.5],
                    "scale": [0.85, 0.85, 0.12],
                },
                "firstperson_righthand": {
                    "rotation": [0, -90, 25],
                    "translation": [1.13, 3.2, 1.13],
                    "scale": [0.68, 0.68, 0.10],
                },
                "firstperson_lefthand": {
                    "rotation": [0, 90, -25],
                    "translation": [1.13, 3.2, 1.13],
                    "scale": [0.68, 0.68, 0.10],
                },
                "gui": {
                    "rotation": [0, 0, 0],
                    "translation": [0, 0, 0],
                    "scale": [1, 1, 0.10],
                },
                "ground": {
                    "rotation": [0, 0, 0],
                    "translation": [0, 3, 0],
                    "scale": [0.25, 0.25, 0.04],
                },
                "fixed": {
                    "rotation": [0, 180, 0],
                    "translation": [0, 0, 0],
                    "scale": [0.5, 0.5, 0.08],
                },
                "head": {
                    "rotation": [0, 180, 0],
                    "translation": [0, 13, 7],
                    "scale": [1, 1, 0.14],
                },
            },
        },
    )
    write_json(
        MODEL_DIR / "tarot_card_back.json",
        {
            "parent": THIN_CARD_PARENT,
            "render_type": "forge:item_unlit",
            "textures": {"layer0": "miningdim:item/tarot/card_back"},
        },
    )

    for card_id in range(22):
        for quality_index, quality in enumerate(quality_names):
            for upright in (True, False):
                orientation_suffix = "" if upright else "_reversed"
                model_name = f"tarot_card_{card_id:02d}_{quality}{orientation_suffix}"
                write_json(
                    MODEL_DIR / f"{model_name}.json",
                    {
                            "parent": THIN_CARD_PARENT,
                        "render_type": "forge:item_unlit",
                        "textures": {
                            "layer0": f"miningdim:item/tarot/{card_id:02d}{orientation_suffix}",
                            "layer1": f"miningdim:item/tarot/border_{quality}{orientation_suffix}",
                        },
                    },
                )
                overrides.append(
                    {
                        "predicate": {
                            "miningdim:tarot_card": round((card_id + 1) / 100, 2),
                            "miningdim:tarot_quality": round((quality_index + 1) / 10, 1),
                            "miningdim:tarot_orientation": 0.1 if upright else 0.2,
                        },
                        "model": f"miningdim:item/{model_name}",
                    }
                )

    write_json(
        MODEL_DIR / "tarot_card.json",
        {
            "parent": THIN_CARD_PARENT,
            "render_type": "forge:item_unlit",
            "textures": {
                "layer0": "miningdim:item/tarot/00",
                "layer1": "miningdim:item/tarot/border_r",
            },
            "overrides": overrides,
        },
    )


def main() -> None:
    if set(CARD_FILES) != set(range(22)):
        raise ValueError("tarot source mapping must contain exactly card ids 0 through 21")
    build_card_textures()
    build_card_back_textures()
    build_quality_borders()
    build_item_models()
    print(
        "Built 22 full-card item textures, 22 full-card previews, one card back, "
        "5 rarity borders (upright/reversed), and 220 unlit item models."
    )


if __name__ == "__main__":
    main()
