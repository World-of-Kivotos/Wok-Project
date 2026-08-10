from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "塔罗牌图片资源"
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item/tarot"
GUI_CARD_DIR = ROOT / "src/main/resources/assets/miningdim/textures/gui/tarot/cards"
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/item"

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
    "r": (220, 228, 236, 255),
    "sr": (76, 226, 255, 255),
    "ssr": (203, 110, 255, 255),
    "ur": (255, 174, 52, 255),
    "shiny": (255, 244, 132, 255),
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


def build_quality_borders() -> None:
    hand_left = (CANVAS_SIZE - HAND_CARD_WIDTH) // 2
    hand_top = (CANVAS_SIZE - HAND_CARD_HEIGHT) // 2
    hand_bounds = (
        hand_left,
        hand_top,
        hand_left + HAND_CARD_WIDTH - 1,
        hand_top + HAND_CARD_HEIGHT - 1,
    )

    for quality, color in QUALITY_COLORS.items():
        overlay = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))

        if quality == "shiny":
            glow = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
            glow_draw = ImageDraw.Draw(glow)
            glow_draw.rounded_rectangle(
                hand_bounds,
                radius=8,
                outline=(255, 244, 132, 210),
                width=8,
            )
            overlay.alpha_composite(glow.filter(ImageFilter.GaussianBlur(2)))

        draw = ImageDraw.Draw(overlay)
        draw.rounded_rectangle(
            hand_bounds,
            radius=8,
            outline=(18, 28, 50, 230),
            width=7,
        )
        draw.rounded_rectangle(
            (hand_bounds[0] + 2, hand_bounds[1] + 2, hand_bounds[2] - 2, hand_bounds[3] - 2),
            radius=6,
            outline=color,
            width=4 if quality != "shiny" else 5,
        )
        overlay.save(TEXTURE_DIR / f"border_{quality}.png", optimize=True)
        overlay.rotate(180).save(TEXTURE_DIR / f"border_{quality}_reversed.png", optimize=True)


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_item_models() -> None:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    overrides = []
    quality_names = tuple(QUALITY_COLORS)

    for card_id in range(22):
        for quality_index, quality in enumerate(quality_names):
            for upright in (True, False):
                orientation_suffix = "" if upright else "_reversed"
                model_name = f"tarot_card_{card_id:02d}_{quality}{orientation_suffix}"
                write_json(
                    MODEL_DIR / f"{model_name}.json",
                    {
                        "parent": "minecraft:item/generated",
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
            "parent": "minecraft:item/generated",
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
    build_quality_borders()
    build_item_models()
    print(
        "Built 22 full-card item textures, 22 full-card previews, "
        "5 rarity borders (upright/reversed), and 220 unlit item models."
    )


if __name__ == "__main__":
    main()
