from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/item/tarot"
PREVIEW_PATH = ROOT / "build/tarot_quality_borders_multicolor_preview.png"
SIZE = 256
FRAME = (55, 2, 201, 254)
CARD_WINDOW = (61, 8, 194, 247)


def layer() -> Image.Image:
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def glow_rect(image: Image.Image, box: tuple[int, int, int, int], color: tuple[int, int, int],
              width: int, blur: float, alpha: int) -> None:
    glow = layer()
    draw = ImageDraw.Draw(glow)
    draw.rounded_rectangle(box, radius=8, outline=(*color, alpha), width=width)
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(blur)))


def rail(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], color: tuple[int, int, int, int],
         width: int, radius: int = 8) -> None:
    draw.rounded_rectangle(box, radius=radius, outline=color, width=width)


def diamond(draw: ImageDraw.ImageDraw, x: int, y: int, size: int,
            color: tuple[int, int, int], hot: tuple[int, int, int] = (255, 255, 255)) -> None:
    draw.polygon([(x, y - size - 2), (x + size + 2, y), (x, y + size + 2), (x - size - 2, y)],
                 fill=(4, 12, 34, 245))
    draw.polygon([(x, y - size), (x + size, y), (x, y + size), (x - size, y)],
                 fill=(*color, 255))
    inner = max(2, size - 4)
    draw.polygon([(x, y - inner), (x + inner, y), (x, y + inner), (x - inner, y)],
                 fill=(*hot, 255))


def sparkle(draw: ImageDraw.ImageDraw, x: int, y: int, radius: int,
            color: tuple[int, int, int]) -> None:
    draw.polygon([(x, y - radius), (x + 2, y - 2), (x + radius, y), (x + 2, y + 2),
                  (x, y + radius), (x - 2, y + 2), (x - radius, y), (x - 2, y - 2)],
                 fill=(*color, 245))
    draw.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(255, 255, 255, 255))


def corner_brackets(draw: ImageDraw.ImageDraw, color: tuple[int, int, int], length: int, width: int) -> None:
    x0, y0, x1, y1 = FRAME
    segments = [
        [(x0, y0 + length), (x0, y0), (x0 + length, y0)],
        [(x1 - length, y0), (x1, y0), (x1, y0 + length)],
        [(x0, y1 - length), (x0, y1), (x0 + length, y1)],
        [(x1 - length, y1), (x1, y1), (x1, y1 - length)],
    ]
    for points in segments:
        draw.line(points, fill=(*color, 255), width=width, joint="curve")


def base_frame(glow_color: tuple[int, int, int], main_color: tuple[int, int, int],
               glow_radius: float, main_width: int) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = layer()
    glow_rect(image, FRAME, glow_color, main_width + 4, glow_radius, 210)
    draw = ImageDraw.Draw(image)
    # 深色衬线保证边框在浅色卡面上仍然清晰，实际有效宽度约 1 个物品栏像素。
    rail(draw, FRAME, (3, 10, 30, 245), main_width + 6)
    rail(draw, (58, 5, 198, 251), (*main_color, 255), main_width)
    rail(draw, (61, 8, 195, 248), (235, 250, 255, 250), 3)
    rail(draw, (67, 14, 189, 242), (5, 18, 44, 235), 3, 5)
    return image, draw


def make_r() -> Image.Image:
    image, draw = base_frame((216, 232, 248), (239, 247, 255), 4.0, 10)
    rail(draw, (64, 11, 192, 245), (183, 226, 244, 245), 3, 6)
    corner_brackets(draw, (255, 255, 255), 14, 3)
    diamond(draw, 128, 250, 5, (188, 220, 255), (255, 255, 255))
    return image


def make_sr() -> Image.Image:
    image, draw = base_frame((34, 105, 255), (48, 126, 255), 6.0, 12)
    rail(draw, (63, 10, 193, 246), (76, 224, 255, 255), 4, 6)
    rail(draw, (69, 16, 187, 240), (42, 57, 184, 245), 2, 4)
    corner_brackets(draw, (190, 220, 255), 18, 4)
    colors = [(55, 139, 255), (55, 224, 255)]
    for index, (x, y) in enumerate([(58, 20), (198, 20), (58, 236), (198, 236)]):
        diamond(draw, x, y, 6, colors[index % len(colors)])
    return image


def make_ssr() -> Image.Image:
    image, draw = base_frame((137, 58, 255), (127, 62, 255), 8.0, 14)
    rail(draw, (59, 6, 197, 250), (113, 139, 255, 255), 5, 7)
    rail(draw, (64, 11, 192, 245), (231, 88, 255, 255), 4, 6)
    rail(draw, (69, 16, 187, 240), (70, 29, 139, 245), 2, 4)
    corner_brackets(draw, (233, 220, 255), 20, 4)
    jewel_colors = [(121, 69, 255), (229, 88, 255)]
    for index, (x, y) in enumerate([(56, 26), (200, 26), (56, 230), (200, 230)]):
        diamond(draw, x, y, 7, jewel_colors[index % len(jewel_colors)], (229, 244, 255))
    sparkle_colors = [(130, 198, 255), (245, 112, 255)]
    for index, (x, y) in enumerate([(45, 92), (211, 164), (128, 6), (128, 250)]):
        sparkle(draw, x, y, 7, sparkle_colors[index % len(sparkle_colors)])
    return image


def make_ur() -> Image.Image:
    image, draw = base_frame((255, 70, 166), (255, 86, 174), 9.0, 15)
    rail(draw, (59, 6, 197, 250), (208, 132, 255, 255), 5, 7)
    rail(draw, (64, 11, 192, 245), (255, 119, 188, 255), 4, 6)
    rail(draw, (69, 16, 187, 240), (91, 24, 83, 245), 2, 4)
    # 顶部学院式光环，不横穿卡面中心。
    halo = layer()
    halo_draw = ImageDraw.Draw(halo)
    halo_draw.arc((83, -22, 173, 72), 8, 172, fill=(255, 100, 185, 220), width=6)
    halo_draw.arc((91, -14, 165, 62), 10, 170, fill=(222, 188, 255, 245), width=3)
    image.alpha_composite(halo.filter(ImageFilter.GaussianBlur(3.0)))
    draw = ImageDraw.Draw(image)
    corner_brackets(draw, (255, 220, 240), 22, 5)
    colors = [(255, 92, 178), (214, 111, 255), (255, 143, 160)]
    for index, (x, y) in enumerate([(54, 68), (202, 68), (54, 188), (202, 188), (128, 4), (128, 252)]):
        diamond(draw, x, y, 7, colors[index % len(colors)], (255, 232, 244))
    sparkle(draw, 128, 27, 9, (215, 153, 255))
    return image


def make_shiny() -> Image.Image:
    image, draw = base_frame((255, 38, 50), (255, 49, 62), 11.0, 16)
    # 红为主色，叠加金、粉、白高光形成最高档的多色宝石感。
    rail(draw, (58, 5, 198, 251), (255, 198, 76, 255), 5, 7)
    rail(draw, (62, 9, 194, 247), (255, 72, 91, 255), 5, 6)
    rail(draw, (66, 13, 190, 243), (255, 92, 177, 255), 4, 5)
    rail(draw, (70, 17, 186, 239), (255, 239, 239, 255), 3, 4)
    corner_brackets(draw, (255, 245, 245), 24, 6)
    colors = [(255, 48, 61), (255, 185, 62), (255, 91, 176), (255, 213, 213)]
    diamonds = [(52, 32), (204, 32), (52, 224), (204, 224), (128, 3), (128, 253)]
    for index, (x, y) in enumerate(diamonds):
        diamond(draw, x, y, 8, colors[index % len(colors)], (255, 255, 255))
    for index, (x, y, radius) in enumerate([
        (39, 82, 8), (216, 76, 7), (35, 156, 6), (220, 170, 9),
        (47, 205, 5), (209, 120, 5), (128, 27, 10), (128, 229, 8),
    ]):
        sparkle(draw, x, y, radius, colors[index % len(colors)])
    return image


def protect_card_window(image: Image.Image) -> Image.Image:
    ImageDraw.Draw(image).rectangle(CARD_WINDOW, fill=(0, 0, 0, 0))
    return image


def save_assets() -> dict[str, Image.Image]:
    assets = {
        "r": protect_card_window(make_r()),
        "sr": protect_card_window(make_sr()),
        "ssr": protect_card_window(make_ssr()),
        "ur": protect_card_window(make_ur()),
        "shiny": protect_card_window(make_shiny()),
    }
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for quality, image in assets.items():
        image.save(TEXTURE_DIR / f"border_{quality}.png", optimize=True)
        ImageOps.flip(image).save(TEXTURE_DIR / f"border_{quality}_reversed.png", optimize=True)
    return assets


def save_preview(assets: dict[str, Image.Image]) -> None:
    base = Image.open(TEXTURE_DIR / "02.png").convert("RGBA")
    preview = Image.new("RGB", (1110, 530), (12, 18, 34))
    draw = ImageDraw.Draw(preview)
    font = ImageFont.load_default(size=20)
    title_font = ImageFont.load_default(size=28)
    draw.text((28, 18), "Tarot quality borders - multicolor", fill=(236, 246, 255), font=title_font)
    for index, (quality, border) in enumerate(assets.items()):
        full_composite = Image.alpha_composite(base, border)
        composite = full_composite.resize((196, 196), Image.Resampling.LANCZOS)
        x = 18 + index * 218
        preview.paste(composite.convert("RGB"), (x, 78))
        label = quality.upper()
        box = draw.textbbox((0, 0), label, font=font)
        draw.text((x + (196 - (box[2] - box[0])) // 2, 292), label,
                  fill=(224, 240, 255), font=font)
        # 先缩到 32px 再最近邻放大，直观看到物品栏尺度的边框保留情况。
        inventory = full_composite.resize((32, 32), Image.Resampling.LANCZOS)
        inventory = inventory.resize((96, 96), Image.Resampling.NEAREST)
        preview.paste(inventory.convert("RGB"), (x + 50, 350))
    draw.text((28, 462), "32px inventory-scale simulation (nearest-neighbor enlarged)",
              fill=(224, 240, 255), font=font)
    draw.text((28, 495), "One shared transparent aperture keeps every quality at the same card-face size.",
              fill=(145, 180, 218), font=font)
    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW_PATH, optimize=True)


if __name__ == "__main__":
    generated = save_assets()
    save_preview(generated)
    print(PREVIEW_PATH)
