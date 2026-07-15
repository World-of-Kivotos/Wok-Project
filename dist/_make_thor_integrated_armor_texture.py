from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "miningdim"
    / "textures"
    / "models"
    / "armor"
    / "plate_armor_thor_integrated_layer_1.png"
)

SIZE = 128
PALETTE = (
    (54, 51, 36, 255),
    (68, 63, 43, 255),
    (81, 74, 51, 255),
    (96, 86, 60, 255),
    (112, 99, 70, 255),
    (74, 70, 48, 255),
)
EDGE = (41, 39, 29, 255)
SEAM = (50, 47, 33, 255)
SLOT = (36, 35, 27, 255)
HIGHLIGHT = (128, 113, 81, 255)


def mottled_canvas() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            cell_x = x // 3
            cell_y = y // 3
            index = (cell_x * 17 + cell_y * 31 + (cell_x ^ cell_y) * 7) % len(PALETTE)
            pixels[x, y] = PALETTE[index]
    return image


def panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], base: tuple[int, int, int, int]) -> None:
    x, y, width, height = box
    draw.rectangle((x, y, x + width - 1, y + height - 1), fill=base, outline=EDGE)
    if width >= 5 and height >= 5:
        draw.line((x + 1, y + 1, x + width - 2, y + 1), fill=HIGHLIGHT)
        draw.line((x + 1, y + height - 2, x + width - 2, y + height - 2), fill=SEAM)


def molle(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int]) -> None:
    x, y, width, height = box
    panel(draw, box, (91, 82, 57, 255))
    for row_y in range(y + 3, y + height - 1, 2):
        for slot_x in range(x + 1, x + width - 1, 2):
            draw.point((slot_x, row_y), fill=SLOT)


def seam_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int]) -> None:
    x, y, width, height = box
    panel(draw, box, (98, 87, 62, 255))
    center = x + width // 2
    draw.line((center, y + 1, center, y + height - 2), fill=SEAM)


def main() -> None:
    image = mottled_canvas()
    draw = ImageDraw.Draw(image)

    # Soft carrier front/back faces.
    seam_panel(draw, (4, 4, 8, 12))
    seam_panel(draw, (16, 4, 8, 12))

    # Rigid front and rear plate faces.
    molle(draw, (27, 1, 8, 9))
    molle(draw, (36, 1, 8, 9))
    molle(draw, (45, 1, 8, 10))
    molle(draw, (54, 1, 8, 10))

    # High collar, straps and lower armor panels.
    seam_panel(draw, (1, 19, 9, 4))
    seam_panel(draw, (11, 19, 9, 4))
    seam_panel(draw, (21, 19, 9, 4))
    seam_panel(draw, (31, 19, 9, 4))
    for x in (77, 82, 87, 92):
        panel(draw, (x, 19, 3, 5), (82, 74, 52, 255))
    panel(draw, (1, 31, 9, 3), (76, 69, 48, 255))
    panel(draw, (20, 31, 9, 3), (76, 69, 48, 255))
    seam_panel(draw, (39, 31, 5, 6))
    seam_panel(draw, (45, 31, 5, 6))

    # Shoulder cores and their rigid front/back shells.
    seam_panel(draw, (4, 52, 5, 6))
    seam_panel(draw, (22, 52, 5, 6))
    panel(draw, (37, 49, 6, 6), (104, 91, 64, 255))
    panel(draw, (58, 49, 6, 6), (104, 91, 64, 255))
    seam_panel(draw, (1, 61, 6, 6))
    seam_panel(draw, (13, 61, 6, 6))
    seam_panel(draw, (25, 61, 6, 6))
    seam_panel(draw, (37, 61, 6, 6))

    # Small two-buckle motif from the reference armor.
    draw.rectangle((29, 2, 30, 3), fill=SLOT)
    draw.rectangle((32, 2, 33, 3), fill=SLOT)
    draw.rectangle((47, 2, 48, 3), fill=SLOT)
    draw.rectangle((50, 2, 51, 3), fill=SLOT)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, format="PNG", optimize=False)

    with Image.open(OUTPUT) as written:
        if written.size != (SIZE, SIZE) or written.mode != "RGBA":
            raise RuntimeError("THOR armor texture must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError("THOR armor texture must remain fully opaque")

    print(OUTPUT)


if __name__ == "__main__":
    main()
