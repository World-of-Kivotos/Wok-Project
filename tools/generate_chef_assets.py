"""Build exact-size WOK Chef PNG resources from the approved pixel-art style source."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "assets" / "chef" / "chef_asset_style_source.png"
TEXTURES = ROOT / "src" / "main" / "resources" / "assets" / "miningdim" / "textures"

TIERS = {
    "low": (0, 0, 310, 470),
    "medium": (305, 0, 625, 470),
    "high": (615, 0, 935, 470),
    "extraordinary": (925, 0, 1225, 470),
    "radiant": (1215, 0, 1536, 470),
}

ICONS = {
    "chef_endurance": (0, 748, 218, 1024),
    "chef_satiation": (218, 748, 440, 1024),
    "chef_shield": (440, 748, 660, 1024),
    "chef_grease": (660, 748, 875, 1024),
    "chef_aftertaste_regen": (875, 748, 1085, 1024),
    "chef_stable_aim": (1085, 748, 1305, 1024),
    "chef_fire_quell": (980, 420, 1090, 590),
    "chef_gills": (1335, 30, 1515, 255),
    "chef_feather": (1305, 748, 1536, 1024),
    "chef_firefly": (1305, 748, 1536, 1024),
}


def pixel_asset(image: Image.Image, size: tuple[int, int], colors: int) -> Image.Image:
    """Downsample, palette-limit, then return RGB/RGBA with deliberately hard edges."""
    converted = image.convert("RGBA").resize(size, Image.Resampling.BOX)
    alpha = converted.getchannel("A")
    rgb = converted.convert("RGB").quantize(colors=colors, method=Image.Quantize.MEDIANCUT)
    result = rgb.convert("RGBA")
    result.putalpha(alpha)
    return result


def build_block_textures(source: Image.Image) -> None:
    block_dir = TEXTURES / "block"
    block_dir.mkdir(parents=True, exist_ok=True)
    for tier, (left, top, right, bottom) in TIERS.items():
        width = right - left
        crops = {
            "top": (left, top + 170, right, top + 330),
            "side": (left, top + 285, right, bottom),
            "bottom": (left + width // 8, top + 350, right - width // 8, bottom),
        }
        for face, box in crops.items():
            texture = pixel_asset(source.crop(box), (16, 16), 20)
            texture.save(block_dir / f"seasoning_table_{tier}_{face}.png", optimize=True)


def slot(draw: ImageDraw.ImageDraw, x: int, y: int) -> None:
    draw.rectangle((x, y, x + 17, y + 17), fill=(45, 35, 29, 255))
    draw.line((x, y, x + 17, y), fill=(224, 184, 106, 255), width=1)
    draw.line((x, y, x, y + 17), fill=(224, 184, 106, 255), width=1)
    draw.line((x, y + 17, x + 17, y + 17), fill=(20, 17, 19, 255), width=1)
    draw.line((x + 17, y, x + 17, y + 17), fill=(20, 17, 19, 255), width=1)
    draw.rectangle((x + 2, y + 2, x + 15, y + 15), fill=(91, 72, 55, 255))


def bevel_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
                fill: tuple[int, int, int, int]) -> None:
    """Draw a one-pixel, Minecraft-style hard-edged panel without resampling."""
    left, top, right, bottom = box
    draw.rectangle(box, fill=(28, 24, 24, 255))
    draw.line((left + 1, top + 1, right - 1, top + 1), fill=(174, 132, 76, 255))
    draw.line((left + 1, top + 1, left + 1, bottom - 1), fill=(174, 132, 76, 255))
    draw.line((left + 1, bottom - 1, right - 1, bottom - 1), fill=(15, 14, 16, 255))
    draw.line((right - 1, top + 1, right - 1, bottom - 1), fill=(15, 14, 16, 255))
    draw.rectangle((left + 2, top + 2, right - 2, bottom - 2), fill=fill)


def build_gui(_source: Image.Image) -> None:
    gui_dir = TEXTURES / "gui"
    gui_dir.mkdir(parents=True, exist_ok=True)
    # UI 必须逐像素绘制，不能从概念图缩放：缩放图会产生模糊边缘，也无法与真实 Slot 坐标对齐。
    canvas = Image.new("RGBA", (256, 232), (24, 21, 22, 255))
    draw = ImageDraw.Draw(canvas)

    # 外框、标题栏、操作区和背包区各自独立，运行时文字不再压在装饰图或物品槽上。
    bevel_panel(draw, (0, 0, 255, 231), (57, 45, 39, 255))
    bevel_panel(draw, (5, 5, 250, 21), (70, 48, 37, 255))
    draw.line((8, 20, 247, 20), fill=(205, 147, 69, 255))
    bevel_panel(draw, (7, 23, 248, 121), (43, 38, 37, 255))
    bevel_panel(draw, (39, 125, 216, 224), (48, 42, 39, 255))

    # 火候槽与动作区只提供清晰边框；颜色、文本和指针全部由 Screen 实时绘制。
    draw.rectangle((75, 54, 241, 66), fill=(14, 19, 22, 255), outline=(147, 112, 67, 255))
    draw.rectangle((75, 71, 241, 93), fill=(19, 27, 30, 255), outline=(147, 112, 67, 255))
    draw.line((9, 37, 246, 37), fill=(81, 67, 57, 255))
    draw.line((9, 95, 246, 95), fill=(81, 67, 57, 255))

    # 成品菜与调料槽；参数为槽边框坐标，物品 Slot 坐标分别是 (18,55)/(44,55)。
    for x, y in ((18, 55), (44, 55)):
        slot(draw, x - 1, y - 1)

    # 玩家背包 Slot 坐标从 (47,140) 起，热键栏 y=198，与 SeasoningMenu 完全一致。
    for row in range(3):
        for column in range(9):
            slot(draw, 46 + column * 18, 139 + row * 18)
    for column in range(9):
        slot(draw, 46 + column * 18, 197)
    canvas.save(gui_dir / "seasoning_table.png", optimize=True)


def build_icons(source: Image.Image) -> None:
    icon_dir = TEXTURES / "mob_effect"
    icon_dir.mkdir(parents=True, exist_ok=True)
    for name, box in ICONS.items():
        icon = pixel_asset(source.crop(box), (18, 18), 28)
        if name == "chef_feather":
            recolored = icon.convert("RGBA")
            recolored_pixels = recolored.load()
            for y in range(recolored.height):
                for x in range(recolored.width):
                    red, green, blue, alpha = recolored_pixels[x, y]
                    luminance = (red * 3 + green * 6 + blue) // 10
                    recolored_pixels[x, y] = (
                        min(255, 92 + luminance), min(255, 130 + luminance),
                        min(255, 158 + luminance), alpha)
            icon = recolored
        pixels = icon.load()
        for y in range(icon.height):
            for x in range(icon.width):
                red, green, blue, alpha = pixels[x, y]
                if max(red, green, blue) < 34:
                    pixels[x, y] = (red, green, blue, 0)
                elif max(red, green, blue) < 58 and abs(red - green) + abs(green - blue) < 24:
                    pixels[x, y] = (red, green, blue, min(alpha, 96))
        icon.save(icon_dir / f"{name}.png", optimize=True)


def build_preview() -> None:
    """Assemble every runtime PNG into one enlarged QA sheet for visual inspection."""
    preview = Image.new("RGBA", (1000, 760), (22, 18, 22, 255))
    draw = ImageDraw.Draw(preview)
    block_dir = TEXTURES / "block"
    for index, tier in enumerate(TIERS):
        group_x = 20 + index * 194
        draw.text((group_x, 10), tier, fill=(238, 220, 176, 255))
        for face_index, face in enumerate(("top", "side", "bottom")):
            texture = Image.open(block_dir / f"seasoning_table_{tier}_{face}.png").convert("RGBA")
            preview.paste(texture.resize((56, 56), Image.Resampling.NEAREST),
                          (group_x + face_index * 60, 34))
            draw.text((group_x + face_index * 60, 94), face, fill=(174, 169, 166, 255))

    gui = Image.open(TEXTURES / "gui" / "seasoning_table.png").convert("RGBA")
    preview.paste(gui.resize((512, 464), Image.Resampling.NEAREST), (20, 145))
    draw.text((20, 125), "seasoning_table GUI 2x", fill=(238, 220, 176, 255))

    icon_dir = TEXTURES / "mob_effect"
    for index, name in enumerate(ICONS):
        column = index % 3
        row = index // 3
        x = 580 + column * 135
        y = 160 + row * 145
        icon = Image.open(icon_dir / f"{name}.png").convert("RGBA")
        draw.rectangle((x, y, x + 89, y + 89), fill=(158, 153, 145, 255))
        preview.alpha_composite(icon.resize((90, 90), Image.Resampling.NEAREST), (x, y))
        draw.text((x, y + 96), name.removeprefix("chef_"), fill=(210, 204, 198, 255))

    preview_path = ROOT / "docs" / "assets" / "chef" / "chef_runtime_asset_preview.png"
    preview.save(preview_path, optimize=True)


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    build_block_textures(source)
    build_gui(source)
    build_icons(source)
    build_preview()


if __name__ == "__main__":
    main()
