from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


OUT = Path(r"D:\Wok\dist\munitions-parts-v2")
OUT.mkdir(parents=True, exist_ok=True)
REFERENCE_PRIMER = OUT / "primer_reference_source.png"
REFERENCE_CASE = OUT / "case_reference_source.png"
REFERENCE_CORE = OUT / "core_reference_source.png"
REFERENCE_PROPELLANT = OUT / "propellant_reference_source.png"

TRANSPARENT = (0, 0, 0, 0)


def img():
    return Image.new("RGBA", (16, 16), TRANSPARENT)


def save_scaled(icon, name):
    icon.save(OUT / f"{name}.png")


def draw_primer():
    im = img()
    d = ImageDraw.Draw(im)

    outline = (55, 34, 12, 255)
    deep_brass = (91, 55, 14, 255)
    shadow_brass = (137, 88, 21, 255)
    brass = (194, 132, 38, 255)
    light_brass = (238, 188, 76, 255)
    highlight = (255, 226, 132, 255)
    cavity = (64, 45, 25, 255)
    cavity_light = (112, 75, 31, 255)
    steel_dark = (84, 88, 87, 255)
    steel = (166, 173, 170, 255)
    steel_light = (232, 235, 224, 255)

    # Short side wall first, then the elliptical rim. This keeps the part
    # visibly cup-shaped at 16 px instead of reading as a flat coin.
    d.polygon(
        [(2, 6), (13, 6), (13, 10), (12, 10), (12, 12),
         (10, 12), (10, 13), (5, 13), (5, 12), (3, 12),
         (3, 10), (2, 10)],
        fill=outline,
    )
    d.polygon(
        [(3, 7), (12, 7), (12, 10), (11, 10), (11, 12),
         (5, 12), (4, 11), (4, 9), (3, 9)],
        fill=shadow_brass,
    )
    d.rectangle((6, 8, 10, 11), fill=brass)
    d.rectangle((7, 8, 9, 10), fill=light_brass)
    d.rectangle((7, 9, 8, 10), fill=highlight)
    d.rectangle((11, 8, 12, 10), fill=deep_brass)
    d.rectangle((5, 12, 10, 12), fill=deep_brass)

    # Outer rim: a low, oblique ellipse with a bright rear lip.
    d.rectangle((6, 2, 9, 2), fill=outline)
    d.rectangle((4, 3, 11, 3), fill=outline)
    d.rectangle((3, 4, 12, 4), fill=outline)
    d.rectangle((2, 5, 13, 6), fill=outline)
    d.rectangle((3, 7, 12, 7), fill=outline)
    d.rectangle((4, 8, 11, 8), fill=outline)

    d.rectangle((6, 3, 9, 3), fill=highlight)
    d.rectangle((4, 4, 11, 4), fill=light_brass)
    d.rectangle((3, 5, 12, 5), fill=brass)
    d.rectangle((3, 6, 12, 6), fill=shadow_brass)
    d.rectangle((4, 7, 11, 7), fill=brass)
    d.rectangle((5, 8, 10, 8), fill=light_brass)
    d.rectangle((4, 4, 6, 4), fill=highlight)
    d.rectangle((3, 5, 4, 6), fill=light_brass)
    d.rectangle((11, 4, 11, 4), fill=shadow_brass)
    d.rectangle((12, 5, 12, 6), fill=deep_brass)

    # Recess and tiny anvil detail.
    d.rectangle((6, 4, 9, 4), fill=cavity)
    d.rectangle((5, 5, 10, 6), fill=cavity)
    d.rectangle((6, 7, 9, 7), fill=cavity_light)
    d.rectangle((7, 5, 8, 5), fill=steel_light)
    d.rectangle((6, 6, 9, 6), fill=steel)
    d.rectangle((7, 7, 8, 7), fill=steel_dark)
    d.point((6, 6), fill=steel_light)
    return im


def draw_case():
    if REFERENCE_CASE.exists():
        source = Image.open(REFERENCE_CASE).convert("RGBA")
        pixels = source.load()
        mask_points = set()
        for y in range(source.height):
            for x in range(source.width):
                r, g, b, a = pixels[x, y]
                if a == 0 or (r > 224 and g > 224 and b > 224 and max(r, g, b) - min(r, g, b) < 26):
                    pixels[x, y] = (255, 255, 255, 0)
                else:
                    mask_points.add((x, y))
        # The reference image contains a neighboring object at the left edge.
        # Keep only the largest connected shape so the case is not squeezed.
        seen = set()
        best = []
        for point in list(mask_points):
            if point in seen:
                continue
            stack = [point]
            seen.add(point)
            component = []
            while stack:
                px, py = stack.pop()
                component.append((px, py))
                for nxt in ((px + 1, py), (px - 1, py), (px, py + 1), (px, py - 1)):
                    if nxt in mask_points and nxt not in seen:
                        seen.add(nxt)
                        stack.append(nxt)
            if len(component) > len(best):
                best = component
        bbox = None
        if best:
            xs = [p[0] for p in best]
            ys = [p[1] for p in best]
            bbox = (min(xs), min(ys), max(xs) + 1, max(ys) + 1)
        if bbox:
            cropped = source.crop(bbox)
            target_h = 14
            target_w = 7
            small = cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)
            im = img()
            x = (16 - target_w) // 2
            y = (16 - target_h) // 2
            im.alpha_composite(small, (x, y))
            crisp = Image.new("RGBA", (16, 16), TRANSPARENT)
            for yy in range(16):
                for xx in range(16):
                    r, g, b, a = im.getpixel((xx, yy))
                    if a > 42:
                        crisp.putpixel((xx, yy), (r, g, b, 255))
            previous_mask = {
                1: range(5, 9),
                2: range(5, 10),
                3: range(4, 10),
                4: range(4, 10),
                5: range(4, 10),
                6: range(4, 10),
                7: range(4, 10),
                8: range(4, 10),
                9: range(4, 10),
                10: range(4, 10),
                11: range(4, 10),
                12: range(4, 10),
                13: range(4, 10),
                14: range(4, 11),
            }
            restored = Image.new("RGBA", (16, 16), TRANSPARENT)
            for yy, allowed in previous_mask.items():
                row_colors = [(xx, crisp.getpixel((xx, yy))) for xx in range(16)
                              if crisp.getpixel((xx, yy))[3] > 0]
                if not row_colors:
                    continue
                for xx in allowed:
                    color = crisp.getpixel((xx, yy))
                    if color[3] == 0:
                        color = min(row_colors, key=lambda item: abs(item[0] - xx))[1]
                    restored.putpixel((xx, yy), color)
            return restored

    im = img()
    d = ImageDraw.Draw(im)
    # Straight single-mouth sleeve: no bulged double-ended profile.
    d.rectangle((5, 3, 11, 13), fill=(48, 31, 15, 255))
    d.rectangle((6, 4, 10, 12), fill=(180, 119, 36, 255))
    d.rectangle((7, 4, 9, 12), fill=(224, 164, 62, 255))
    d.rectangle((5, 3, 11, 4), fill=(238, 183, 78, 255))
    d.rectangle((6, 4, 10, 5), fill=(78, 50, 24, 255))
    d.rectangle((5, 12, 11, 13), fill=(117, 72, 25, 255))
    d.point((9, 5), fill=(255, 215, 112, 255))
    return im


def draw_core():
    if REFERENCE_CORE.exists():
        source = Image.open(REFERENCE_CORE).convert("RGBA")
        pixels = source.load()
        for y in range(source.height):
            for x in range(source.width):
                r, g, b, a = pixels[x, y]
                if a == 0 or (r > 245 and g > 245 and b > 245):
                    pixels[x, y] = (255, 255, 255, 0)
        bbox = source.getbbox()
        if bbox:
            cropped = source.crop(bbox)
            cropped = cropped.rotate(270, expand=True, resample=Image.Resampling.BICUBIC)
            target_h = 16
            target_w = max(1, min(16, round(cropped.width * target_h / cropped.height)))
            small = cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)
            im = img()
            x = (16 - target_w) // 2
            im.alpha_composite(small, (x, 0))
            # Keep the reference silhouette crisp at item-texture scale.
            crisp = Image.new("RGBA", (16, 16), TRANSPARENT)
            for yy in range(16):
                for xx in range(16):
                    r, g, b, a = im.getpixel((xx, yy))
                    if a > 36:
                        crisp.putpixel((xx, yy), (r, g, b, 255))
            # Shape the top as a slim rounded cone. It should taper into the body
            # instead of reading like a thick rounded cap.
            nose_mask = {
                0: (),
                1: (7, 8),
                2: (7, 8),
                3: (6, 7, 8, 9),
                4: (6, 7, 8, 9),
                5: (6, 7, 8, 9),
                6: (5, 6, 7, 8, 9, 10),
                7: (5, 6, 7, 8, 9, 10),
            }
            for yy, keep_cols in nose_mask.items():
                for xx in range(16):
                    if xx not in keep_cols:
                        crisp.putpixel((xx, yy), TRANSPARENT)
            return crisp

    im = img()
    d = ImageDraw.Draw(im)
    # Horizontal reference-inspired core: pointed nose, straight body, flat rear.
    d.polygon([(1, 8), (4, 5), (13, 5), (14, 6), (14, 11), (13, 12), (4, 12)],
              fill=(62, 35, 10, 255))
    d.polygon([(2, 8), (5, 6), (12, 6), (13, 7), (13, 10), (12, 11), (5, 11)],
              fill=(201, 121, 26, 255))
    d.polygon([(3, 8), (5, 6), (8, 6), (6, 8), (5, 10)],
              fill=(246, 180, 47, 255))
    d.rectangle((7, 6, 12, 8), fill=(231, 153, 37, 255))
    d.rectangle((5, 10, 12, 11), fill=(131, 68, 13, 255))
    d.line((5, 6, 5, 11), fill=(87, 45, 12, 255))
    d.line((6, 6, 6, 11), fill=(232, 169, 57, 255))
    d.line((7, 7, 11, 7), fill=(255, 212, 73, 255))
    d.point((12, 6), fill=(255, 222, 92, 255))
    return im.rotate(270, resample=Image.Resampling.NEAREST)


def draw_powder():
    if REFERENCE_PROPELLANT.exists():
        source = Image.open(REFERENCE_PROPELLANT).convert("RGBA")
        w, h = source.size
        cropped = source.crop((int(w * 0.32), int(h * 0.50), w, h))
        pixels = cropped.load()
        points = set()
        for y in range(cropped.height):
            for x in range(cropped.width):
                r, g, b, a = pixels[x, y]
                if a > 0 and r > 120 and g > 105 and b > 55 and r + g + b > 330:
                    points.add((x, y))
                else:
                    pixels[x, y] = (255, 255, 255, 0)

        seen = set()
        best = []
        for point in list(points):
            if point in seen:
                continue
            stack = [point]
            seen.add(point)
            component = []
            while stack:
                px, py = stack.pop()
                component.append((px, py))
                for nxt in ((px + 1, py), (px - 1, py), (px, py + 1), (px, py - 1)):
                    if nxt in points and nxt not in seen:
                        seen.add(nxt)
                        stack.append(nxt)
            if len(component) > len(best):
                best = component

        if best:
            xs = [p[0] for p in best]
            ys = [p[1] for p in best]
            powder = cropped.crop((min(xs), min(ys), max(xs) + 1, max(ys) + 1))
            target_w = 14
            target_h = max(1, min(9, round(powder.height * target_w / powder.width)))
            small = powder.resize((target_w, target_h), Image.Resampling.LANCZOS)
            im = img()
            x = (16 - target_w) // 2
            y = 14 - target_h
            im.alpha_composite(small, (x, y))

            crisp = Image.new("RGBA", (16, 16), TRANSPARENT)
            ramp = [
                (88, 65, 20),
                (151, 119, 44),
                (213, 187, 86),
                (238, 224, 137),
                (255, 246, 181),
            ]
            for yy in range(16):
                for xx in range(16):
                    r, g, b, a = im.getpixel((xx, yy))
                    if a <= 32:
                        continue
                    lum = int(0.2126 * r + 0.7152 * g + 0.0722 * b)
                    idx = max(0, min(len(ramp) - 1, lum * len(ramp) // 256))
                    crisp.putpixel((xx, yy), (*ramp[idx], 255))
            for p in [(5, 10), (9, 9), (11, 11), (6, 12), (13, 13), (3, 13)]:
                if crisp.getpixel(p)[3] > 0:
                    crisp.putpixel(p, (255, 250, 205, 255))
            return crisp

    im = img()
    d = ImageDraw.Draw(im)
    d.rectangle((4, 5, 11, 12), fill=(61, 35, 16, 255))
    d.rectangle((5, 4, 10, 5), fill=(174, 112, 44, 255))
    d.rectangle((5, 6, 10, 11), fill=(118, 77, 34, 255))
    for p in [(6, 7), (8, 7), (9, 9), (6, 10), (10, 8), (7, 9)]:
        d.point(p, fill=(24, 23, 24, 255))
    d.point((7, 6), fill=(210, 152, 73, 255))
    d.rectangle((4, 12, 11, 13), fill=(48, 29, 14, 230))
    return im


icons = [
    ("primer", "底火", draw_primer()),
    ("case", "弹壳", draw_case()),
    ("core", "弹头", draw_core()),
    ("propellant", "发射药", draw_powder()),
]

for name, _, icon in icons:
    save_scaled(icon, name)

tile = 128
gap = 24
margin_x = 36
margin_y = 36
label_h = 28
sheet_w = margin_x * 2 + tile * 4 + gap * 3
sheet_h = margin_y * 2 + tile + label_h
sheet = Image.new("RGBA", (sheet_w, sheet_h), (24, 24, 26, 255))
d = ImageDraw.Draw(sheet)

try:
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 18)
except OSError:
    font = ImageFont.load_default()

for idx, (_, label, icon) in enumerate(icons):
    x = margin_x + idx * (tile + gap)
    y = margin_y
    d.rectangle((x - 1, y - 1, x + tile, y + tile), outline=(82, 82, 88, 255))
    for yy in range(0, tile, 16):
        for xx in range(0, tile, 16):
            color = (44, 44, 48, 255) if ((xx // 16 + yy // 16) % 2 == 0) else (36, 36, 40, 255)
            d.rectangle((x + xx, y + yy, x + xx + 15, y + yy + 15), fill=color)
    enlarged = icon.resize((tile, tile), Image.Resampling.NEAREST)
    sheet.alpha_composite(enlarged, (x, y))
    text_w = d.textlength(label, font=font)
    d.text((x + (tile - text_w) / 2, y + tile + 8), label, fill=(224, 224, 228, 255), font=font)

sheet.save(r"D:\Wok\dist\munitions-parts-preview-v2.png")
