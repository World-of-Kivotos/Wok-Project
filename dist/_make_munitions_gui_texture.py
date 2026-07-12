from pathlib import Path
import io
import zipfile

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "src/main/resources/assets/miningdim/textures/gui/container"
OUT = BASE / "munitions_bench.png"
OUT_META = BASE / "munitions_bench.png.mcmeta"
FONT_OUT = BASE / "munitions_ui_font.png"
FONT_META = BASE / "munitions_ui_font.png.mcmeta"
TITLE_OUT = BASE / "munitions_titles.png"
TITLE_META = BASE / "munitions_titles.png.mcmeta"
AMMO_OUT = BASE / "munitions_ammo_profiles.png"
AMMO_META = BASE / "munitions_ammo_profiles.png.mcmeta"
PREVIEW = ROOT / "dist/munitions-gui-preview.png"
BASE.mkdir(parents=True, exist_ok=True)
PREVIEW.parent.mkdir(parents=True, exist_ok=True)

S = 3
W, H = 360, 240
TITLE_W, TITLE_H = 220, 32
AMMO_W, AMMO_H = 154, 40

FONT = Path(r"C:\Windows\Fonts\msyh.ttc")
FONT_BOLD = Path(r"C:\Windows\Fonts\msyhbd.ttc")
TACZ_JAR = ROOT / "libs/tacz-1.20.1-1.1.8-hotfix.jar"
TACZ_SLOT_ROOT = "assets/tacz/custom/tacz_default_gun/assets/tacz/textures/ammo/slot/"
TACZ_ICON_BY_LABEL = {
    "9MM": "9mm.png",
    "7.62": "762x39.png",
    "5.56": "556x45.png",
    "12G": "12g.png",
    "54R": "762x54.png",
    ".338": "338.png",
    "50AE": "50ae.png",
    "BMG": "50bmg.png",
    "40M": "40mm.png",
    "SP": "68x51fury.png",
}

img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)


def sc(v):
    return int(round(v * S))


def xy(x, y):
    return sc(x), sc(y)


def box(x0, y0, x1, y1):
    return sc(x0), sc(y0), sc(x1), sc(y1)


def font(size, bold=False):
    return ImageFont.truetype(str(FONT_BOLD if bold else FONT), sc(size))


def rect(x0, y0, x1, y1, fill):
    d.rectangle(box(x0, y0, x1, y1), fill=fill)


def rr(x0, y0, x1, y1, r, fill, outline=None, width=1):
    d.rounded_rectangle(box(x0, y0, x1, y1), radius=sc(r), fill=fill, outline=outline, width=sc(width))


def line(points, fill, width=1):
    d.line([(sc(x), sc(y)) for x, y in points], fill=fill, width=max(1, sc(width)))


def text(x, y, value, size=8, fill=(226, 231, 240, 255), bold=False):
    d.text(xy(x, y), value, font=font(size, bold), fill=fill)


def center_text(x0, x1, y, value, size=8, fill=(226, 231, 240, 255), bold=False):
    f = font(size, bold)
    bounds = d.textbbox((0, 0), value, font=f)
    width = bounds[2] - bounds[0]
    d.text((sc(x0) + (sc(x1 - x0) - width) / 2, sc(y)), value, font=f, fill=fill)


def panel(x, y, w, h, fill, outline, accent=None):
    rr(x + 1.5, y + 2.5, x + w + 1, y + h + 2, 6, (0, 0, 0, 88))
    rr(x, y, x + w - 1, y + h - 1, 6, fill, outline)
    line([(x + 7, y + 2), (x + w - 8, y + 2)], (83, 87, 102, 128), 0.8)
    if accent is not None:
        line([(x + 7, y + h - 4), (x + w - 8, y + h - 4)], accent, 0.9)


def soft_slot(x, y, accent=False):
    rr(x - 1, y - 1, x + 16, y + 16, 3, (16, 17, 24, 255), (58, 60, 72, 255), 1)
    rr(x + 1, y + 1, x + 14, y + 14, 2, (41, 42, 53, 255))
    line([(x + 3, y + 2), (x + 12, y + 2)], (83, 86, 100, 135), 0.8)
    if accent:
        line([(x + 3, y + 15), (x + 13, y + 15)], (62, 164, 142, 255), 0.8)


def ammo_button(x, y, selected=False, enabled=True):
    fill = (55, 50, 40, 255) if selected else ((39, 40, 50, 255) if enabled else (31, 32, 40, 255))
    outline = (92, 72, 43, 255) if selected else (23, 25, 34, 255)
    rr(x, y, x + 22, y + 12, 2, fill, outline, 1)
    line([(x + 3, y + 2), (x + 18, y + 2)], (82, 85, 98, 110), 0.6)
    if selected:
        rect(x + 1, y + 2, x + 2, y + 10, (176, 137, 76, 255))
        line([(x + 4, y + 10), (x + 18, y + 10)], (145, 112, 62, 255), 0.8)


def draw_search_icon(x, y):
    d.ellipse(box(x, y, x + 7, y + 7), outline=(221, 226, 238, 255), width=sc(1))
    line([(x + 6, y + 6), (x + 10, y + 10)], (221, 226, 238, 255), 1)


def draw_grid_icon(x, y):
    for row in range(2):
        for col in range(2):
            rect(x + col * 5, y + row * 5, x + col * 5 + 2, y + row * 5 + 2, (218, 224, 236, 255))


def draw_avatar_placeholder(x, y):
    rr(x, y, x + 18, y + 18, 4, (223, 230, 238, 255), (93, 99, 113, 255), 1)
    d.ellipse(box(x + 5, y + 3, x + 13, y + 11), fill=(116, 90, 73, 255))
    rr(x + 4, y + 12, x + 14, y + 16, 2, (72, 93, 112, 255))
    line([(x + 4, y + 17), (x + 14, y + 17)], (43, 55, 66, 255), 1)


# Tablet body.
rr(14, 14, 349, 231, 12, (0, 0, 0, 70))
rr(18, 10, 344, 226, 9, (32, 33, 43, 255), (62, 64, 78, 255))
line([(24, 13), (338, 13)], (78, 81, 96, 180), 0.8)

# Sidebar.
rr(18, 10, 86, 226, 9, (26, 27, 36, 255))
rect(82, 10, 87, 226, (20, 21, 29, 255))
for x, color in ((28, (201, 73, 73, 255)), (37, (196, 145, 62, 255)), (46, (65, 163, 92, 255))):
    d.ellipse(box(x, 20, x + 5, 25), fill=color)

panel(27, 37, 58, 43, (31, 32, 43, 255), (55, 58, 70, 255))
draw_avatar_placeholder(31, 46)
rect(31, 74, 80, 76, (18, 20, 29, 255))
line([(31, 73), (80, 73)], (62, 64, 80, 255), 0.8)

panel(27, 84, 54, 86, (30, 31, 42, 255), (52, 55, 66, 255), (166, 128, 69, 255))
center_text(27, 81, 89, "\u5f39\u836f\u9009\u62e9", 5.3, (205, 211, 224, 255), True)
categories = ("\u624b\u67aa\u5f39", "\u6b65\u67aa\u5f39", "\u9730\u5f39", "\u72d9\u51fb\u5f39", "\u7206\u7834\u5f39")
rifle_labels = ("7.62", "5.56", "54R", "68X")
for row, label in enumerate(categories):
    y = 102 + row * 13
    selected = label == "\u6b65\u67aa\u5f39"
    ammo_button(30, y, selected=selected)
    center_text(30, 52, y + 3.4, label, 3.2 if len(label) >= 3 else 3.8,
                (234, 217, 177, 255) if selected else (198, 204, 216, 255), True)
    ammo_button(56, y)
for row, label in enumerate(rifle_labels):
    y = 102 + row * 13
    selected = label == "5.56"
    ammo_button(56, y, selected=selected)
    center_text(56, 78, y + 3.0, label, 4.1 if len(label) >= 4 else 4.4,
                (234, 217, 177, 255) if selected else (205, 211, 224, 255), True)

line([(31, 184), (73, 184)], (44, 47, 58, 255), 0.8)

# Header area is intentionally blank; the live screen draws the current factory title here.
draw_search_icon(317, 29)
rect(333, 29, 335, 31, (255, 111, 126, 255))
line([(331, 35), (338, 35)], (224, 229, 239, 255), 1)
draw_grid_icon(329, 44)

# Main ammo preview card. The live screen draws the selected ammo profile inside this card.
panel(92, 52, 184, 72, (38, 45, 54, 255), (56, 67, 78, 255), (168, 128, 67, 255))
text(100, 64, "\u5f39\u836f\u9884\u89c8", 6.1, (186, 194, 208, 255), True)
rr(218, 64, 272, 78, 5, (21, 25, 32, 218), (49, 58, 68, 255), 1)

# Production card under the ammo preview.
panel(92, 126, 184, 17, (40, 41, 52, 255), (57, 59, 72, 255), (62, 164, 142, 255))
text(98, 130.5, "\u5269\u4f59\u5236\u9020\u65f6\u95f4", 5.0, (180, 188, 202, 255), True)
rect(96, 139, 268, 142, (22, 23, 32, 255))
line([(96, 138), (268, 138)], (62, 65, 78, 255), 0.7)

# Inventory drawer.
panel(96, 144, 174, 82, (32, 33, 44, 255), (48, 50, 62, 255))
for row in range(3):
    for col in range(9):
        soft_slot(100 + col * 18, 148 + row * 18)
for col in range(9):
    soft_slot(100 + col * 18, 206)

# Right assembly panel.
panel(286, 58, 60, 170, (35, 36, 47, 255), (55, 57, 70, 255), (105, 111, 132, 255))
text(296, 67, "\u4ea7\u51fa", 7.1, (194, 201, 214, 255), True)
panel(296, 78, 40, 30, (28, 29, 39, 255), (45, 47, 58, 255), (62, 164, 142, 255))
soft_slot(316, 84, True)
text(297, 117, "\u7f13\u51b2", 7.1, (119, 127, 144, 255), True)
panel(292, 136, 48, 82, (38, 39, 50, 255), (57, 59, 70, 255), (166, 128, 69, 255))
center_text(292, 340, 142, "\u653e\u5165\u6750\u6599", 6.1, (204, 211, 222, 255), True)
for x, y in ((296, 158), (322, 158), (296, 184), (322, 184)):
    soft_slot(x, y, True)


def make_title_atlas():
    title_tiers = {
        0: ("\u4f4e\u7ea7", (182, 189, 204, 255)),
        1: ("\u4f4e\u7ea7", (182, 189, 204, 255)),
        2: ("\u4f4e\u7ea7", (182, 189, 204, 255)),
        3: ("\u4e2d\u7ea7", (88, 211, 147, 255)),
        4: ("\u4e2d\u7ea7", (88, 211, 147, 255)),
        5: ("\u9ad8\u7ea7", (93, 172, 255, 255)),
        6: ("\u9ad8\u7ea7", (93, 172, 255, 255)),
        7: ("\u6781\u54c1", (238, 168, 82, 255)),
        8: ("\u6781\u54c1", (238, 168, 82, 255)),
        9: ("\u8d85\u51e1", (185, 116, 255, 255)),
        10: ("\u95ea\u8000", (255, 211, 83, 255)),
    }
    atlas = Image.new("RGBA", (TITLE_W * S, TITLE_H * S * 11), (0, 0, 0, 0))
    ad = ImageDraw.Draw(atlas)
    title_font = ImageFont.truetype(str(FONT_BOLD), sc(13.0))
    small_font = ImageFont.truetype(str(FONT), sc(5.4))
    for level in range(11):
        y0 = level * TITLE_H * S
        tier, tier_color = title_tiers.get(level, title_tiers[1])
        title_y = y0 + sc(5)
        title_x = sc(1)
        prefix = "\u5f39\u836f\u5236\u9020\u5de5\u5382 "
        ad.text((title_x, title_y), prefix, font=title_font, fill=(232, 236, 244, 255))
        prefix_bbox = ad.textbbox((title_x, title_y), prefix, font=title_font)
        ad.text((prefix_bbox[2] + sc(1), title_y), tier, font=title_font, fill=tier_color)
        ad.text((sc(2), y0 + sc(21)), "\u519b\u706b\u5546\u4e13\u5c5e\u4ea7\u7ebf",
                font=small_font, fill=(122, 131, 149, 255))
    return atlas


def make_fallback_profile(label):
    profile = Image.new("RGBA", (AMMO_W * S, AMMO_H * S), (0, 0, 0, 0))
    pd = ImageDraw.Draw(profile)

    def pxy(x, y):
        return sc(x), sc(y)

    def pbox(x0, y0, x1, y1):
        return sc(x0), sc(y0), sc(x1), sc(y1)

    def prr(x0, y0, x1, y1, r, fill, outline=None):
        pd.rounded_rectangle(pbox(x0, y0, x1, y1), radius=sc(r), fill=fill, outline=outline, width=sc(1))

    def pline(points, fill, width=1):
        pd.line([(sc(x), sc(y)) for x, y in points], fill=fill, width=max(1, sc(width)))

    brass = (202, 145, 54, 255)
    brass_dark = (111, 73, 28, 255)
    brass_hi = (250, 214, 117, 255)
    bullet = (229, 170, 51, 255)
    bullet_dark = (139, 88, 25, 255)

    def cartridge(total, bullet_len, height, base_w=10, body_color=brass, slim=False):
        x = (AMMO_W - total) / 2
        y = 20 - height / 2
        body_start = x + bullet_len + 7
        body_end = x + total - base_w
        nose_mid = y + height / 2
        pd.polygon([pxy(x, nose_mid), pxy(x + bullet_len, y + 2), pxy(body_start, y + 4),
                    pxy(body_start, y + height - 4), pxy(x + bullet_len, y + height - 2)],
                   fill=bullet)
        pd.polygon([pxy(x, nose_mid), pxy(x + bullet_len, y + height * 0.58),
                    pxy(body_start, y + height - 4), pxy(x + bullet_len, y + height - 2)],
                   fill=bullet_dark)
        prr(x + bullet_len - 2, y + 4, body_start + 1, y + height - 4, 2, (151, 94, 24, 255))
        prr(body_start, y + (2 if slim else 0), body_end, y + height - (2 if slim else 0), 3, body_color)
        prr(body_start + 12, y + 4, body_end - 13, y + 6.5, 2, brass_hi)
        prr(body_start + 12, y + height - 7, body_end - 10, y + height - 3, 2, brass_dark)
        prr(body_end, y + 3, x + total, y + height - 3, 2, (113, 78, 37, 255))
        pline([(body_start + 2, y + (2 if slim else 0)), (body_end - 3, y + (2 if slim else 0))],
              (240, 190, 86, 255), 0.8)

    if label == "9MM":
        cartridge(104, 25, 22, 9)
    elif label == "7.62":
        cartridge(142, 35, 18, 10, slim=True)
    elif label == "5.56":
        cartridge(136, 32, 17, 9, slim=True)
    elif label == "12G":
        x, y = 19, 11
        prr(x, y + 4, x + 20, y + 23, 3, (194, 130, 38, 255))
        prr(x + 18, y + 2, x + 128, y + 25, 4, (150, 41, 44, 255))
        prr(x + 29, y + 5, x + 105, y + 8, 2, (238, 110, 105, 255))
        prr(x + 29, y + 18, x + 112, y + 23, 2, (87, 22, 25, 255))
        prr(x + 128, y + 5, x + 137, y + 22, 2, (95, 61, 33, 255))
    elif label == "54R":
        cartridge(148, 38, 18, 11, slim=True)
    elif label == ".338":
        cartridge(150, 42, 16, 10, body_color=(191, 135, 49, 255), slim=True)
    elif label == "50AE":
        cartridge(116, 27, 24, 12, body_color=(205, 151, 58, 255))
    elif label == "BMG":
        cartridge(152, 44, 17, 10, body_color=(198, 143, 54, 255), slim=True)
    elif label == "40M":
        x, y = 30, 8
        pd.polygon([pxy(x, y + 18), pxy(x + 20, y + 6), pxy(x + 34, y + 8),
                    pxy(x + 34, y + 28), pxy(x + 20, y + 30)], fill=(92, 117, 76, 255))
        prr(x + 31, y + 6, x + 103, y + 30, 5, (70, 94, 67, 255))
        prr(x + 42, y + 9, x + 88, y + 12, 2, (151, 176, 111, 255))
        prr(x + 42, y + 24, x + 91, y + 28, 2, (34, 51, 36, 255))
        prr(x + 103, y + 8, x + 122, y + 28, 3, (187, 126, 48, 255))
    else:
        cartridge(136, 35, 18, 10, body_color=(188, 171, 126, 255), slim=True)
        prr(80, 13, 120, 16, 2, (229, 220, 171, 255))

    return profile


def make_profile(label):
    if TACZ_JAR.exists():
        icon_path = TACZ_SLOT_ROOT + TACZ_ICON_BY_LABEL[label]
        with zipfile.ZipFile(TACZ_JAR) as z:
            icon = Image.open(io.BytesIO(z.read(icon_path))).convert("RGBA")
        profile = Image.new("RGBA", (AMMO_W * S, AMMO_H * S), (0, 0, 0, 0))
        # Keep the TACZ artwork intact; only scale it to fit the preview card.
        icon = icon.resize((120, 120), Image.Resampling.NEAREST)
        shadow = Image.new("RGBA", icon.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow)
        sd.rounded_rectangle((14, 90, 106, 104), radius=10, fill=(0, 0, 0, 75))
        profile.alpha_composite(shadow, (sc(57), sc(0)))
        profile.alpha_composite(icon, (sc(57), sc(0)))
        return profile
    return make_fallback_profile(label)


titles = make_title_atlas()
titles.save(TITLE_OUT)

ammo_labels = ("9MM", "7.62", "12G", "54R", ".338", "50AE", "BMG", "40M", "SP", "5.56")
profiles = Image.new("RGBA", (AMMO_W * S, AMMO_H * S * len(ammo_labels)), (0, 0, 0, 0))
for i, label in enumerate(ammo_labels):
    profiles.alpha_composite(make_profile(label), (0, i * AMMO_H * S))
profiles.save(AMMO_OUT)

img.save(OUT)

preview = img.copy()
preview_title_level = 9
preview.alpha_composite(titles.crop((0, S * TITLE_H * preview_title_level,
                                     TITLE_W * S, S * TITLE_H * (preview_title_level + 1))),
                        (sc(98), sc(22)))
preview.alpha_composite(profiles.crop((0, 0, AMMO_W * S, AMMO_H * S)), (sc(108), sc(80)))
preview_draw = ImageDraw.Draw(preview)
preview_draw.text(xy(53, 46), "CQIN.", font=font(4.5, True), fill=(218, 224, 236, 255))
preview_draw.text(xy(53, 58), "LV 1", font=font(4.5, True), fill=(125, 134, 153, 255))
preview_draw.rectangle(box(31, 74, 36, 76), fill=(62, 164, 142, 255))
preview.save(PREVIEW)

# High-resolution dynamic glyph atlas for changing values.
glyphs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_/:.- "
font_scale = 4
cell_w, cell_h = 6, 8
src_w, src_h = cell_w * font_scale, cell_h * font_scale
atlas = Image.new("RGBA", (src_w * len(glyphs), src_h), (0, 0, 0, 0))
ad = ImageDraw.Draw(atlas)
af = ImageFont.truetype(str(FONT_BOLD), 25)
for idx, glyph in enumerate(glyphs):
    if glyph == " ":
        continue
    bounds = ad.textbbox((0, 0), glyph, font=af)
    gw = bounds[2] - bounds[0]
    gh = bounds[3] - bounds[1]
    gx = idx * src_w + (src_w - gw) / 2 - bounds[0]
    gy = (src_h - gh) / 2 - bounds[1] - 1
    ad.text((gx, gy), glyph, font=af, fill=(255, 255, 255, 255))
atlas.save(FONT_OUT)

meta = '{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n'
for path in (OUT_META, FONT_META, TITLE_META, AMMO_META):
    path.write_text(meta, encoding="utf-8")
