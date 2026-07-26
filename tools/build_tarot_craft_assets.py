from __future__ import annotations

from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
BLOCK_TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block"
GUI_TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/gui/container"

NAVY = (13, 25, 50, 255)
NAVY_2 = (20, 42, 75, 255)
NAVY_3 = (29, 61, 101, 255)
BLUE = (62, 160, 222, 255)
CYAN = (139, 235, 255, 255)
WHITE = (235, 248, 255, 255)
WHITE_SHADE = (166, 201, 220, 255)
GOLD = (224, 190, 104, 255)
GOLD_SHADE = (139, 103, 47, 255)
PURPLE = (190, 112, 246, 255)
RED = (238, 92, 112, 255)


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def bordered_rect(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
                  fill: tuple[int, int, int, int], outline: tuple[int, int, int, int],
                  width: int = 1, radius: int = 0) -> None:
    if radius:
        draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
    else:
        draw.rectangle(box, fill=fill, outline=outline, width=width)


def draw_slot(draw: ImageDraw.ImageDraw, x: int, y: int) -> None:
    draw.rectangle((x, y, x + 17, y + 17), fill=(7, 17, 36, 255), outline=WHITE_SHADE)
    draw.line((x + 1, y + 1, x + 16, y + 1), fill=WHITE)
    draw.line((x + 1, y + 1, x + 1, y + 16), fill=WHITE)
    draw.line((x + 2, y + 16, x + 16, y + 16), fill=(48, 92, 127, 255))
    draw.line((x + 16, y + 2, x + 16, y + 16), fill=(48, 92, 127, 255))


def draw_star(draw: ImageDraw.ImageDraw, cx: int, cy: int, radius: int,
              color: tuple[int, int, int, int]) -> None:
    points = [
        (cx, cy - radius),
        (cx + max(1, radius // 4), cy - max(1, radius // 4)),
        (cx + radius, cy),
        (cx + max(1, radius // 4), cy + max(1, radius // 4)),
        (cx, cy + radius),
        (cx - max(1, radius // 4), cy + max(1, radius // 4)),
        (cx - radius, cy),
        (cx - max(1, radius // 4), cy - max(1, radius // 4)),
    ]
    draw.polygon(points, fill=color)


def draw_octagon(draw: ImageDraw.ImageDraw, cx: int, cy: int, radius: int,
                 color: tuple[int, int, int, int], width: int = 1) -> None:
    cut = max(1, radius // 3)
    points = [
        (cx - cut, cy - radius), (cx + cut, cy - radius),
        (cx + radius, cy - cut), (cx + radius, cy + cut),
        (cx + cut, cy + radius), (cx - cut, cy + radius),
        (cx - radius, cy + cut), (cx - radius, cy - cut),
    ]
    draw.line(points + [points[0]], fill=color, width=width, joint="curve")


def build_gui() -> None:
    image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Main 218x222 panel. The remaining texture space is intentionally transparent.
    bordered_rect(draw, (0, 0, 217, 221), NAVY, WHITE_SHADE, width=2, radius=8)
    draw.rounded_rectangle((3, 3, 214, 218), radius=6, outline=WHITE, width=1)
    draw.rounded_rectangle((6, 6, 211, 215), radius=5, outline=BLUE, width=1)
    draw.rectangle((8, 18, 209, 130), fill=NAVY_2, outline=(45, 101, 151, 255))
    draw.rectangle((8, 134, 209, 216), fill=(12, 29, 52, 255), outline=BLUE)

    # Decorative academy-tech corner brackets and circuit traces.
    for x in (11, 199):
        draw.rectangle((x, 9, x + 7, 11), fill=WHITE)
        draw.rectangle((x, 9, x + 1, 17), fill=WHITE)
    draw.line((17, 24, 49, 24, 55, 30), fill=BLUE, width=1)
    draw.line((201, 24, 169, 24, 163, 30), fill=BLUE, width=1)
    draw.line((18, 112, 56, 112, 62, 106), fill=(45, 111, 164, 255), width=1)
    draw.line((200, 112, 162, 112, 156, 106), fill=(45, 111, 164, 255), width=1)

    # Tall visual recesses for the two tarot inputs; vanilla 18x18 slots sit in their centers.
    for frame_x in (22, 158):
        bordered_rect(draw, (frame_x, 30, frame_x + 37, 91), (7, 17, 37, 255), WHITE_SHADE, 2, 5)
        draw.rounded_rectangle((frame_x + 3, 33, frame_x + 34, 88), radius=4, outline=GOLD, width=1)
        draw.line((frame_x + 7, 36, frame_x + 30, 36), fill=BLUE)
        draw.line((frame_x + 7, 85, frame_x + 30, 85), fill=BLUE)
        draw_star(draw, frame_x + 19, 35, 3, GOLD)
        draw_star(draw, frame_x + 19, 86, 3, GOLD)
    draw_slot(draw, 32, 51)
    draw_slot(draw, 168, 51)

    # Static astrolabe bed; rotating glyphs are rendered over this by TarotCraftScreen.
    cx, cy = 109, 59
    draw.ellipse((82, 32, 136, 86), fill=(8, 28, 56, 255), outline=WHITE_SHADE, width=2)
    draw.ellipse((85, 35, 133, 83), outline=GOLD, width=1)
    draw.ellipse((89, 39, 129, 79), outline=BLUE, width=1)
    draw.line((cx, 35, cx, 83), fill=(55, 121, 174, 255))
    draw.line((85, cy, 133, cy), fill=(55, 121, 174, 255))
    draw_star(draw, cx, cy, 6, CYAN)

    # Craft button and probability strip.
    button = [(109, 89), (124, 100), (109, 111), (94, 100)]
    draw.polygon(button, fill=(18, 82, 130, 255), outline=WHITE)
    inner = [(109, 92), (120, 100), (109, 108), (98, 100)]
    draw.polygon(inner, fill=(52, 190, 236, 255), outline=GOLD)
    draw_star(draw, 109, 100, 5, WHITE)

    result_colors = (CYAN, PURPLE, GOLD, RED)
    for index, color in enumerate(result_colors):
        x = 17 + index * 50
        bordered_rect(draw, (x, 116, x + 45, 127), (10, 25, 49, 255), (54, 105, 148, 255), 1, 3)
        draw.rectangle((x + 3, 119, x + 5, 124), fill=color)

    # Exact vanilla player inventory grid: x=28, y=142, 9 columns and 3 rows; hotbar y=200.
    for row in range(3):
        for col in range(9):
            draw_slot(draw, 28 + col * 18, 142 + row * 18)
    for col in range(9):
        draw_slot(draw, 28 + col * 18, 200)
    draw.line((25, 196, 192, 196), fill=(48, 105, 150, 255))
    draw_star(draw, 11, 176, 4, BLUE)
    draw_star(draw, 207, 176, 4, BLUE)

    save(image, GUI_TEXTURE_DIR / "tarot_craft.png")


def build_glyphs() -> None:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Sprite 0: 32x32 outer rotating astrolabe.
    draw_octagon(draw, 16, 16, 14, GOLD, 1)
    draw.ellipse((5, 5, 27, 27), outline=CYAN, width=1)
    for x, y in ((16, 1), (31, 16), (16, 31), (1, 16)):
        draw_star(draw, x, y, 2, WHITE)
    draw.line((5, 16, 27, 16), fill=(81, 177, 223, 230))
    draw.line((16, 5, 16, 27), fill=(81, 177, 223, 230))
    # Sprite 1: 32x32 inner counter-rotating ring at u=32.
    draw.ellipse((36, 4, 60, 28), outline=BLUE, width=2)
    draw_octagon(draw, 48, 16, 9, WHITE_SHADE, 1)
    for dx, dy in ((0, -9), (9, 0), (0, 9), (-9, 0)):
        draw.rectangle((48 + dx - 1, 16 + dy - 1, 48 + dx + 1, 16 + dy + 1), fill=CYAN)
    save(image, GUI_TEXTURE_DIR / "tarot_craft_glyphs.png")


def texture(fill: tuple[int, int, int, int]) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), fill)
    return image, ImageDraw.Draw(image)


def build_block_textures() -> None:
    # Main navy casing.
    image, draw = texture(NAVY)
    draw.rectangle((0, 0, 15, 1), fill=NAVY_3)
    draw.rectangle((0, 14, 15, 15), fill=(7, 15, 31, 255))
    draw.line((2, 3, 2, 12), fill=(34, 75, 119, 255))
    draw.line((13, 3, 13, 12), fill=(34, 75, 119, 255))
    draw.line((3, 8, 6, 8, 8, 6, 12, 6), fill=BLUE)
    draw.rectangle((7, 5, 8, 7), fill=CYAN)
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_base.png")

    # Porcelain-white frame with cool shadows.
    image, draw = texture(WHITE_SHADE)
    draw.rectangle((1, 1, 14, 13), fill=WHITE)
    draw.line((1, 14, 14, 14), fill=(101, 143, 169, 255))
    draw.line((14, 1, 14, 14), fill=(101, 143, 169, 255))
    draw.rectangle((3, 3, 12, 4), fill=(207, 232, 244, 255))
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_white.png")

    # Gold mechanics.
    image, draw = texture(GOLD_SHADE)
    draw.rectangle((1, 1, 14, 14), fill=GOLD)
    draw.line((2, 2, 13, 2), fill=(255, 226, 143, 255))
    draw.line((13, 3, 13, 13), fill=(109, 76, 33, 255))
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_gold.png")

    # Cyan luminous insert.
    image, draw = texture((16, 83, 127, 255))
    draw.rectangle((2, 2, 13, 13), fill=(35, 177, 224, 255))
    draw.rectangle((5, 1, 10, 14), fill=CYAN)
    draw.rectangle((7, 0, 8, 15), fill=WHITE)
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_cyan.png")

    # Top surface with two card channels and central astrolabe.
    image, draw = texture(NAVY_2)
    draw.rectangle((0, 0, 15, 15), outline=WHITE)
    draw.rectangle((1, 1, 14, 14), outline=BLUE)
    draw.rounded_rectangle((2, 3, 5, 12), radius=1, fill=(6, 15, 32, 255), outline=GOLD)
    draw.rounded_rectangle((10, 3, 13, 12), radius=1, fill=(6, 15, 32, 255), outline=GOLD)
    draw.ellipse((5, 5, 10, 10), fill=(11, 46, 84, 255), outline=GOLD)
    draw.point((7, 7), fill=CYAN)
    draw.point((8, 8), fill=WHITE)
    draw.line((1, 8, 4, 8), fill=CYAN)
    draw.line((11, 8, 14, 8), fill=CYAN)
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_top.png")

    # Narrow card-slot top plate.
    image, draw = texture((0, 0, 0, 0))
    draw.rounded_rectangle((2, 1, 13, 14), radius=2, fill=(5, 12, 27, 255), outline=WHITE_SHADE)
    draw.rounded_rectangle((4, 2, 11, 13), radius=1, outline=GOLD)
    draw_star(draw, 8, 8, 3, BLUE)
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_card_slot.png")

    # Transparent vertical ring plane used twice at right angles.
    image, draw = texture((0, 0, 0, 0))
    draw_octagon(draw, 8, 8, 7, GOLD, 1)
    draw.ellipse((3, 3, 13, 13), outline=CYAN, width=1)
    draw.line((1, 8, 15, 8), fill=GOLD)
    draw.line((8, 1, 8, 15), fill=GOLD)
    for x, y in ((8, 1), (15, 8), (8, 15), (1, 8)):
        draw.rectangle((x - 1, y - 1, x + 1, y + 1), fill=CYAN)
    draw_star(draw, 8, 8, 4, WHITE)
    draw_star(draw, 8, 8, 2, CYAN)
    save(image, BLOCK_TEXTURE_DIR / "tarot_craft_ring.png")


def main() -> None:
    build_gui()
    build_glyphs()
    build_block_textures()
    print("Built tarot synthesis table UI, animated glyphs, and seven block textures.")


if __name__ == "__main__":
    main()
