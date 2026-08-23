"""Build exact-size WOK Chef PNG resources from the approved pixel-art style source."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw

from generate_chef_gecko_assets import build_gecko_assets


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "assets" / "chef" / "chef_asset_style_source.png"
TEXTURES = ROOT / "src" / "main" / "resources" / "assets" / "miningdim" / "textures"
MODELS = ROOT / "src" / "main" / "resources" / "assets" / "miningdim" / "models" / "block"
BLOCKSTATES = ROOT / "src" / "main" / "resources" / "assets" / "miningdim" / "blockstates"

TIER_PALETTES = {
    "low": ((91, 62, 39), (51, 39, 32), (143, 132, 113)),
    "medium": ((119, 61, 34), (60, 34, 28), (221, 112, 48)),
    "high": ((43, 78, 76), (28, 46, 48), (77, 210, 188)),
    "extraordinary": ((61, 42, 78), (31, 26, 43), (174, 79, 224)),
    "radiant": ((142, 122, 71), (69, 60, 42), (255, 222, 91)),
}
TIERS = tuple(TIER_PALETTES)

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


def save_tile(path: Path, painter) -> None:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    painter(ImageDraw.Draw(image))
    image.save(path, optimize=True)


def build_block_textures(_source: Image.Image) -> None:
    """Create crisp authored tiles; never downsample the concept sheet for in-world block faces."""
    block_dir = TEXTURES / "block"
    block_dir.mkdir(parents=True, exist_ok=True)
    for tier, (base, dark, accent) in TIER_PALETTES.items():
        def top(draw: ImageDraw.ImageDraw) -> None:
            draw.rectangle((0, 0, 15, 15), fill=(*base, 255))
            draw.rectangle((0, 0, 15, 15), outline=(*dark, 255))
            draw.rectangle((2, 2, 13, 13), outline=(*accent, 255))
            draw.line((3, 7, 12, 7), fill=(*dark, 255))
            draw.line((7, 3, 7, 12), fill=(*dark, 255))

        def side(draw: ImageDraw.ImageDraw) -> None:
            draw.rectangle((0, 0, 15, 15), fill=(*base, 255))
            for y in (0, 5, 10, 15):
                draw.line((0, y, 15, y), fill=(*dark, 255))
            draw.line((5, 1, 5, 4), fill=(*dark, 255))
            draw.line((11, 6, 11, 9), fill=(*dark, 255))
            draw.line((7, 11, 7, 14), fill=(*dark, 255))

        def bottom(draw: ImageDraw.ImageDraw) -> None:
            draw.rectangle((0, 0, 15, 15), fill=(*dark, 255))
            draw.rectangle((2, 2, 13, 13), outline=(*base, 255))

        def accent_tile(draw: ImageDraw.ImageDraw) -> None:
            draw.rectangle((0, 0, 15, 15), fill=(*dark, 255))
            draw.rectangle((1, 1, 14, 14), outline=(*accent, 255))
            draw.line((3, 8, 12, 8), fill=(*accent, 255), width=2)
            draw.point((5, 4), fill=(255, 244, 210, 255))
            draw.point((11, 12), fill=(255, 244, 210, 255))

        save_tile(block_dir / f"seasoning_table_{tier}_top.png", top)
        save_tile(block_dir / f"seasoning_table_{tier}_side.png", side)
        save_tile(block_dir / f"seasoning_table_{tier}_bottom.png", bottom)
        save_tile(block_dir / f"seasoning_table_{tier}_accent.png", accent_tile)

    common_tiles = {
        "metal": ((52, 59, 62), (131, 143, 145), (24, 29, 31)),
        "pot": ((30, 34, 36), (89, 99, 102), (14, 17, 18)),
        "burner": ((38, 31, 28), (226, 71, 26), (17, 17, 18)),
        "board": ((153, 105, 56), (213, 161, 91), (91, 59, 35)),
        "spice_red": ((142, 39, 31), (226, 92, 47), (74, 24, 24)),
        "spice_yellow": ((171, 111, 28), (244, 191, 67), (92, 61, 23)),
        "spice_green": ((52, 104, 57), (110, 164, 76), (29, 57, 34)),
    }
    for name, (base, light, dark) in common_tiles.items():
        def common(draw: ImageDraw.ImageDraw, base=base, light=light, dark=dark) -> None:
            draw.rectangle((0, 0, 15, 15), fill=(*base, 255))
            draw.line((0, 0, 15, 0), fill=(*light, 255))
            draw.line((0, 0, 0, 15), fill=(*light, 255))
            draw.line((0, 15, 15, 15), fill=(*dark, 255))
            draw.line((15, 0, 15, 15), fill=(*dark, 255))
            for point in ((4, 4), (11, 6), (7, 12)):
                draw.point(point, fill=(*light, 255))
        save_tile(block_dir / f"seasoning_table_{name}.png", common)


def model_box(start: tuple[float, float, float], end: tuple[float, float, float],
              side: str, up: str | None = None, down: str | None = None) -> dict:
    up = up or side
    down = down or side
    return {
        "from": list(start),
        "to": list(end),
        "faces": {
            "down": {"texture": down},
            "up": {"texture": up},
            "north": {"texture": side},
            "south": {"texture": side},
            "west": {"texture": side},
            "east": {"texture": side},
        },
    }


def build_block_model() -> None:
    """Build a true two-block-wide cooking station and split it into left/right block models."""
    MODELS.mkdir(parents=True, exist_ok=True)
    BLOCKSTATES.mkdir(parents=True, exist_ok=True)
    elements = [
        # Two-block counter structure with centre supports.
        model_box((0, 8, 0), (32, 11, 16), "#side", "#top", "#bottom"),
        model_box((2, 3, 2), (30, 4, 14), "#side", "#top", "#bottom"),
        model_box((1, 0, 1), (3, 8, 3), "#side", "#top", "#bottom"),
        model_box((1, 0, 13), (3, 8, 15), "#side", "#top", "#bottom"),
        model_box((15, 0, 1), (17, 8, 3), "#side", "#top", "#bottom"),
        model_box((15, 0, 13), (17, 8, 15), "#side", "#top", "#bottom"),
        model_box((29, 0, 1), (31, 8, 3), "#side", "#top", "#bottom"),
        model_box((29, 0, 13), (31, 8, 15), "#side", "#top", "#bottom"),
        # Full-width backboard and tier strip.
        model_box((0, 11, 14), (32, 16, 16), "#side", "#top", "#bottom"),
        model_box((1, 13.5, 13.75), (31, 14.5, 14), "#accent"),
        # Left burner plate.
        model_box((1.5, 11, 1), (14.5, 11.75, 12.5), "#metal", "#burner", "#metal"),
        # Hollow cooking pot and handles.
        model_box((4, 11.75, 3), (12, 12.5, 10.5), "#pot"),
        model_box((3.25, 12.5, 2.5), (4.25, 15.5, 11), "#pot"),
        model_box((11.75, 12.5, 2.5), (12.75, 15.5, 11), "#pot"),
        model_box((4.25, 12.5, 2.5), (11.75, 15.5, 3.5), "#pot"),
        model_box((4.25, 12.5, 10), (11.75, 15.5, 11), "#pot"),
        model_box((1.75, 13.25, 5.5), (3.25, 14.25, 8), "#metal"),
        model_box((12.75, 13.25, 5.5), (14.25, 14.25, 8), "#metal"),
        # Broad central cutting board and a slim knife.
        model_box((17, 11, 1), (24, 11.4, 10.5), "#board", "#board", "#board"),
        model_box((18, 11.4, 7.75), (23, 11.7, 8.35), "#metal"),
        # Raised seasoning shelf.
        model_box((24, 11, 9), (31, 11.75, 14), "#side", "#accent", "#bottom"),
        model_box((24, 11.75, 12.75), (31, 12.25, 14), "#accent"),
        # Three spice jars with metal lids.
        model_box((24.5, 11.75, 10), (26.25, 14, 12), "#spice_red"),
        model_box((24.5, 14, 10), (26.25, 14.4, 12), "#metal"),
        model_box((26.75, 11.75, 10), (28.5, 14, 12), "#spice_yellow"),
        model_box((26.75, 14, 10), (28.5, 14.4, 12), "#metal"),
        model_box((29, 11.75, 10), (30.75, 14, 12), "#spice_green"),
        model_box((29, 14, 10), (30.75, 14.4, 12), "#metal"),
    ]

    textures = {
        "particle": "#side",
        "metal": "miningdim:block/seasoning_table_metal",
        "pot": "miningdim:block/seasoning_table_pot",
        "burner": "miningdim:block/seasoning_table_burner",
        "board": "miningdim:block/seasoning_table_board",
        "spice_red": "miningdim:block/seasoning_table_spice_red",
        "spice_yellow": "miningdim:block/seasoning_table_spice_yellow",
        "spice_green": "miningdim:block/seasoning_table_spice_green",
    }
    display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.55, 0.55, 0.55]},
        "ground": {"translation": [0, 3, 0], "scale": [0.2, 0.2, 0.2]},
        "fixed": {"scale": [0.35, 0.35, 0.35]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                  "scale": [0.25, 0.25, 0.25]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0],
                                  "scale": [0.3, 0.3, 0.3]},
    }

    def shifted(element: dict, amount: float) -> dict:
        copy = json.loads(json.dumps(element))
        copy["from"][0] += amount
        copy["to"][0] += amount
        return copy

    def clipped_half(element: dict, minimum: float, maximum: float) -> dict | None:
        if element["to"][0] <= minimum or element["from"][0] >= maximum:
            return None
        copy = json.loads(json.dumps(element))
        copy["from"][0] = max(copy["from"][0], minimum) - minimum
        copy["to"][0] = min(copy["to"][0], maximum) - minimum
        return copy

    item_model = {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": textures,
        "elements": [shifted(element, -8) for element in elements],
        "display": display,
    }
    (MODELS / "seasoning_table_template.json").write_text(
        json.dumps(item_model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for half, bounds in (("left", (0, 16)), ("right", (16, 32))):
        half_elements = [clipped_half(element, *bounds) for element in elements]
        half_model = {
            "parent": "minecraft:block/block",
            "ambientocclusion": True,
            "textures": textures,
            "elements": [element for element in half_elements if element is not None],
        }
        (MODELS / f"seasoning_table_template_{half}.json").write_text(
            json.dumps(half_model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    tier_texture_keys = lambda tier: {
        "top": f"miningdim:block/seasoning_table_{tier}_top",
        "bottom": f"miningdim:block/seasoning_table_{tier}_bottom",
        "side": f"miningdim:block/seasoning_table_{tier}_side",
        "accent": f"miningdim:block/seasoning_table_{tier}_accent",
    }
    rotations = {"north": 0, "east": 90, "south": 180, "west": 270}
    for tier in TIERS:
        for suffix, parent in (("", "seasoning_table_template"),
                               ("_left", "seasoning_table_template_left"),
                               ("_right", "seasoning_table_template_right")):
            child = {"parent": f"miningdim:block/{parent}", "textures": tier_texture_keys(tier)}
            (MODELS / f"seasoning_table_{tier}{suffix}.json").write_text(
                json.dumps(child, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        variants = {}
        for facing, rotation in rotations.items():
            for secondary, half in (("false", "left"), ("true", "right")):
                for lit in ("false", "true"):
                    variants[f"facing={facing},lit={lit},secondary={secondary}"] = {
                        "model": f"miningdim:block/seasoning_table_{tier}_{half}",
                        "y": rotation,
                        "uvlock": True,
                    }
        (BLOCKSTATES / f"seasoning_table_{tier}.json").write_text(
            json.dumps({"variants": variants}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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


def build_model_preview() -> Image.Image:
    """Render a small isometric QA view from the generated element bounds."""
    model = json.loads((MODELS / "seasoning_table_template.json").read_text(encoding="utf-8"))
    preview = Image.new("RGBA", (230, 180), (28, 24, 28, 255))
    draw = ImageDraw.Draw(preview)
    base, dark, accent = TIER_PALETTES["radiant"]
    texture_colors = {
        "#top": base, "#side": dark, "#bottom": dark, "#accent": accent,
        "#metal": (92, 101, 104), "#pot": (42, 47, 49), "#burner": (202, 58, 24),
        "#board": (181, 127, 67), "#spice_red": (191, 54, 39),
        "#spice_yellow": (222, 156, 42), "#spice_green": (67, 132, 70),
    }

    def project(point: tuple[float, float, float]) -> tuple[int, int]:
        x, y, z = point
        return (115 + round((x - z) * 4.2), 135 + round((x + z) * 2.1 - y * 4.2))

    def shade(color: tuple[int, int, int], amount: int) -> tuple[int, int, int, int]:
        return tuple(max(0, min(255, channel + amount)) for channel in color) + (255,)

    elements = sorted(model["elements"], key=lambda item: (
        (item["from"][0] + item["to"][0]) / 2
        + (item["from"][2] + item["to"][2]) / 2
        + ((item["from"][1] + item["to"][1]) / 2) * 2.5
    ))
    for element in elements:
        x1, y1, z1 = element["from"]
        x2, y2, z2 = element["to"]
        faces = element["faces"]
        top_color = texture_colors.get(faces["up"]["texture"], base)
        east_color = texture_colors.get(faces["east"]["texture"], dark)
        south_color = texture_colors.get(faces["south"]["texture"], dark)
        top = [project(p) for p in ((x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2))]
        east = [project(p) for p in ((x2, y1, z1), (x2, y2, z1), (x2, y2, z2), (x2, y1, z2))]
        south = [project(p) for p in ((x1, y1, z2), (x1, y2, z2), (x2, y2, z2), (x2, y1, z2))]
        draw.polygon(east, fill=shade(east_color, -6), outline=(18, 17, 18, 255))
        draw.polygon(south, fill=shade(south_color, -22), outline=(18, 17, 18, 255))
        draw.polygon(top, fill=shade(top_color, 22), outline=(18, 17, 18, 255))
    return preview


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

    model_preview = build_model_preview()
    preview.alpha_composite(model_preview, (750, 550))
    draw.text((750, 735), "radiant cooking workstation model", fill=(238, 220, 176, 255))

    preview_path = ROOT / "docs" / "assets" / "chef" / "chef_runtime_asset_preview.png"
    preview.save(preview_path, optimize=True)


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    build_block_textures(source)
    build_block_model()
    build_gecko_assets(ROOT, TIER_PALETTES)
    build_gui(source)
    build_icons(source)
    build_preview()


if __name__ == "__main__":
    main()
