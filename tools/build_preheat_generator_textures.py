"""生成煤炭/地热发电机的方块占位贴图。

配色与 build_power_ui_assets.py 同源，保证占位与既有 power 界面风格一致。
美术出成品时直接覆盖同名 PNG 即可，不需要改 blockstate 或 model JSON。
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block"

SIZE = 16

VOID = (2, 5, 9, 255)
DEEP_SHADOW = (4, 10, 16, 255)
NAVY = (9, 24, 35, 255)
NAVY_RAISED = (14, 34, 48, 255)
STEEL_DARK = (39, 52, 63, 255)
STEEL = (77, 96, 111, 255)
STEEL_LIGHT = (166, 201, 220, 255)
CYAN = (139, 235, 255, 255)
AMBER = (240, 166, 62, 255)
RED = (238, 92, 112, 255)
BASALT = (52, 48, 56, 255)
BASALT_LIGHT = (86, 80, 92, 255)
LAVA = (232, 118, 40, 255)
LAVA_CORE = (255, 196, 92, 255)


def save(image: Image.Image, name: str) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT_DIR / f"{name}.png")
    print(f"wrote {name}.png")


def plate(base, edge, speckle=None) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    """通用金属板底: 外框一圈暗边, 四角铆钉。"""
    image = Image.new("RGBA", (SIZE, SIZE), base)
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, SIZE - 1, SIZE - 1), outline=edge)
    draw.line((1, 1, SIZE - 2, 1), fill=STEEL_LIGHT)
    draw.line((1, SIZE - 2, SIZE - 2, SIZE - 2), fill=DEEP_SHADOW)
    for rx, ry in ((2, 2), (SIZE - 3, 2), (2, SIZE - 3), (SIZE - 3, SIZE - 3)):
        draw.point((rx, ry), fill=STEEL_LIGHT)
    if speckle:
        for sx, sy in ((5, 6), (10, 4), (7, 11), (12, 9)):
            draw.point((sx, sy), fill=speckle)
    return image, draw


def coal_top() -> None:
    image, draw = plate(STEEL_DARK, VOID)
    draw.rectangle((4, 4, 11, 11), fill=NAVY_RAISED, outline=STEEL)
    draw.rectangle((6, 6, 9, 9), fill=VOID)
    draw.line((6, 6, 9, 6), fill=STEEL)
    save(image, "coal_generator_top")


def coal_side() -> None:
    image, draw = plate(STEEL_DARK, VOID)
    draw.rectangle((3, 5, 12, 10), fill=NAVY, outline=STEEL)
    for x in range(4, 12, 2):
        draw.line((x, 6, x, 9), fill=STEEL_DARK)
    save(image, "coal_generator_side")


def coal_front(lit: bool) -> None:
    image, draw = plate(STEEL_DARK, VOID)
    draw.rectangle((3, 4, 12, 12), fill=NAVY, outline=STEEL)
    # 炉门与观火口
    draw.rectangle((5, 6, 10, 11), fill=VOID, outline=STEEL_DARK)
    if lit:
        draw.rectangle((6, 8, 9, 10), fill=AMBER)
        draw.line((6, 9, 9, 9), fill=LAVA_CORE)
        draw.point((7, 7), fill=RED)
        draw.point((9, 7), fill=RED)
    else:
        draw.rectangle((6, 8, 9, 10), fill=DEEP_SHADOW)
    draw.line((4, 3, 11, 3), fill=STEEL)
    save(image, f"coal_generator_front{'_on' if lit else ''}")


def geothermal_top() -> None:
    image, draw = plate(BASALT, VOID, speckle=BASALT_LIGHT)
    draw.rectangle((4, 4, 11, 11), fill=NAVY_RAISED, outline=STEEL_DARK)
    draw.line((5, 7, 10, 7), fill=CYAN)
    draw.line((5, 9, 10, 9), fill=STEEL)
    save(image, "geothermal_generator_top")


def geothermal_side() -> None:
    image, draw = plate(BASALT, VOID, speckle=BASALT_LIGHT)
    # 侧面竖向散热柱
    for x in (4, 7, 10):
        draw.line((x, 3, x, 12), fill=BASALT_LIGHT)
        draw.point((x, 3), fill=STEEL)
    draw.line((2, 13, 13, 13), fill=DEEP_SHADOW)
    save(image, "geothermal_generator_side")


def geothermal_front(lit: bool) -> None:
    image, draw = plate(BASALT, VOID, speckle=BASALT_LIGHT)
    draw.rectangle((3, 4, 12, 12), fill=NAVY, outline=STEEL_DARK)
    # 热交换窗: 熄火时是冷却的岩缝, 工作时透出岩浆
    draw.rectangle((5, 6, 10, 11), fill=VOID, outline=STEEL_DARK)
    if lit:
        draw.rectangle((6, 7, 9, 10), fill=LAVA)
        draw.line((6, 8, 9, 8), fill=LAVA_CORE)
        draw.point((7, 10), fill=LAVA_CORE)
    else:
        draw.rectangle((6, 7, 9, 10), fill=DEEP_SHADOW)
        draw.line((6, 8, 9, 8), fill=BASALT_LIGHT)
    save(image, f"geothermal_generator_front{'_on' if lit else ''}")


def main() -> None:
    coal_top()
    coal_side()
    coal_front(False)
    coal_front(True)
    geothermal_top()
    geothermal_side()
    geothermal_front(False)
    geothermal_front(True)
    print("Built 8 preheat generator block textures.")


if __name__ == "__main__":
    main()
