from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


OUT = Path(r"D:\Wok\dist")
ICON = OUT / "munitions-parts-v2" / "core.png"
SHEET = OUT / "munitions-core-angle-options.png"
TRANSPARENT = (0, 0, 0, 0)


base = Image.open(ICON).convert("RGBA")
options = [
    ("左倾20", 20),
    ("左倾10", 10),
    ("正立", 0),
    ("右倾10", -10),
    ("右倾20", -20),
]

tile = 112
gap = 18
margin_x = 28
margin_y = 28
label_h = 28
sheet_w = margin_x * 2 + tile * len(options) + gap * (len(options) - 1)
sheet_h = margin_y * 2 + tile + label_h
sheet = Image.new("RGBA", (sheet_w, sheet_h), (24, 24, 26, 255))
draw = ImageDraw.Draw(sheet)

try:
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 17)
except OSError:
    font = ImageFont.load_default()

for idx, (label, angle) in enumerate(options):
    rotated = base.rotate(angle, resample=Image.Resampling.NEAREST, expand=False)
    x = margin_x + idx * (tile + gap)
    y = margin_y
    draw.rectangle((x - 1, y - 1, x + tile, y + tile), outline=(82, 82, 88, 255))
    for yy in range(0, tile, 16):
        for xx in range(0, tile, 16):
            color = (44, 44, 48, 255) if ((xx // 16 + yy // 16) % 2 == 0) else (36, 36, 40, 255)
            draw.rectangle((x + xx, y + yy, x + xx + 15, y + yy + 15), fill=color)
    sheet.alpha_composite(rotated.resize((tile, tile), Image.Resampling.NEAREST), (x, y))
    text_w = draw.textlength(label, font=font)
    draw.text((x + (tile - text_w) / 2, y + tile + 8), label, fill=(224, 224, 228, 255), font=font)

sheet.save(SHEET)
