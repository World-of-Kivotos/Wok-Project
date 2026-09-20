"""矿石鱼与鱼羹物品图标的确定性生成器。

image_gen 出的原画是 1536x1024 / 1254x1254 这类任意尺寸, 直接当物品贴图会踩原版图集的两条硬规则:

1. 非正方形且不是帧尺寸整数倍的贴图, SpriteLoader.loadSprite 会走
   "Image {} size {},{} is not multiple of frame size {},{}" 这一支返回 null, 该物品直接退化成缺失贴图。
   无 .mcmeta 时帧尺寸取 min(宽, 高), 所以 1536x1024 推出 1024x1024, 1536 不是它的整数倍。
2. 边长只能被 2 整除有限次的贴图会拉低整张方块/物品图集的 mipmap 等级
   ("Texture {} with size {}x{} limits mip level from {} to {}"), 1254 只被 2 整除一次, 4 级直接掉到 1 级,
   全服所有远景贴图跟着糊。

所以图集里只放 256x256 的派生图 (与本仓塔罗牌、枪匠蓝图这些高精度物品图标同规格: 正方形、2 的幂、
能被 2^4 整除故保住 4 级 mipmap), 原画留在 tools/assets/fishing/v1/source/ 不进 JAR。

派生规则: 按 alpha 包围盒裁掉四周空白 -> 等比缩放到长边 256 -> 居中贴到 256x256 全透明画布。
等比缩放保证鱼不被拉扁; 先裁包围盒保证 16x16 渲染出来的主体尽可能占满格子。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "tools/assets/fishing/v1/source"
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item/fishing"
ICON_SIZE = 256

FISH_TYPES = ("iron", "gold", "diamond", "emerald", "dark_gold")


def icon_names() -> list[str]:
    names = []
    for fish in FISH_TYPES:
        names.append(f"{fish}_ore_fish")
        names.append(f"{fish}_ore_fish_soup")
    return names


def build(source: Path) -> Image.Image:
    original = Image.open(source).convert("RGBA")
    bbox = original.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError(f"{source.name} 整张全透明, 没有可用的图像内容")
    cropped = original.crop(bbox)
    scale = ICON_SIZE / max(cropped.width, cropped.height)
    scaled = cropped.resize(
        (max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    canvas.paste(scaled, ((ICON_SIZE - scaled.width) // 2, (ICON_SIZE - scaled.height) // 2))
    return canvas


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for name in icon_names():
        source = SOURCE_DIR / f"{name}.png"
        if not source.exists():
            raise FileNotFoundError(f"缺少原画: {source.relative_to(ROOT)}")
        target = OUTPUT_DIR / f"{name}.png"
        build(source).save(target, optimize=True)
        print(f"wrote {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
