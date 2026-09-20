"""12 档导线中间物图标的确定性生成器。

色值不在这里另立一套: 每档的五级色阶由 build_power_cable_block_textures.STYLES 中同材料线缆的
conductor_shadow/conductor/conductor_light 三停插值而来, 使"导线中间物"与"它合成出的线缆"共用
同一份材料色真源。形状与明暗由下面的 SHADE_GRID 固化, 因此本脚本是纯函数, 重复运行字节一致。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

from build_power_cable_block_textures import STYLES


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item"
SIZE = 16
TRANSPARENT = (0, 0, 0, 0)

# 线卷轮廓与五级明暗索引 ('.' = 透明, 0 最暗 -> 4 最亮)。12 档共用同一形状, 只换调色板。
SHADE_GRID = (
    "................",
    "................",
    "................",
    "................",
    "....22222222....",
    "..224444444422..",
    ".23420000002432.",
    "23420......02432",
    "3230........0323",
    "031..........130",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
)

MATERIALS = (
    "iron",
    "aluminum",
    "copper",
    "tinned_copper",
    "ofc_copper",
    "ofe_copper",
    "silver_plated_copper",
    "gold",
    "silver",
    "graphene",
    "nbti_superconductor",
    "ybco_superconductor",
)


def lerp(start: tuple[int, int, int], end: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return tuple(round(start[index] + (end[index] - start[index]) * factor) for index in range(3))


def palette(material: str) -> list[tuple[int, int, int, int]]:
    style = STYLES[material + "_energy_cable"]
    shadow, base, light = style.conductor_shadow[:3], style.conductor[:3], style.conductor_light[:3]
    stops = []
    for level in range(5):
        position = level / 4
        rgb = lerp(shadow, base, position * 2) if position <= 0.5 else lerp(base, light, (position - 0.5) * 2)
        stops.append(rgb + (255,))
    return stops


def build(material: str) -> Image.Image:
    stops = palette(material)
    image = Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)
    pixels = image.load()
    for y, row in enumerate(SHADE_GRID):
        for x, cell in enumerate(row):
            if cell != ".":
                pixels[x, y] = stops[int(cell)]
    return image


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for material in MATERIALS:
        target = OUTPUT_DIR / f"{material}_wire.png"
        build(material).save(target)
        print(f"wrote {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
