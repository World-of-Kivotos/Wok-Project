"""Build the three granary block models, their shared pixel atlas and a textured preview.

Run with Python + Pillow. Geometry stays within one block; the preview reads the
same JSON and UVs as the game, including the vent's 22.5 degree louvres.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path("src/main/resources/assets/miningdim")
MATERIALS = ["wood", "top", "trim", "iron", "paper", "cloth", "drawer", "wheat",
             "latch", "shadow", "sack", "grain", "seal", "ink", "ledger", "brass"]
COLORS = ["946740", "b68a55", "67472e", "68716b", "decfa2", "385c4c", "9e7246", "735034",
          "b8a36f", "2d3028", "bfa36a", "bd9448", "975044", "34463e", "e1d3ab", "b3985a"]
DIRS = ("down", "up", "north", "south", "west", "east")
VERTS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}


def atlas() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
    for index, material in enumerate(MATERIALS):
        tile = Image.new("RGBA", (16, 16), "#" + COLORS[index])
        draw = ImageDraw.Draw(tile)
        base = tuple(bytes.fromhex(COLORS[index]))
        # Fixed integer patterns keep regeneration byte-for-byte deterministic.
        for y in range(16):
            for x in range(16):
                shift = ((x * 17 + y * 7 + index * 13) % 7 - 3) * 2
                tile.putpixel((x, y), tuple(c + shift for c in base) + (255,))
        if material in ("wood", "top", "trim", "drawer"):
            for x in (0, 4, 8, 12):
                draw.line((x, 0, x, 15), fill="#65472f")
                draw.line((x + 1, 0, x + 1, 15), fill="#b08754")
            for x, y in ((3, 5), (7, 11), (11, 3), (14, 9)):
                draw.line((x, y, x, y + 3), fill="#795435")
        if material in ("iron", "latch", "brass"):
            draw.line((0, 0, 15, 0), fill="#c2c0a1")
            draw.line((0, 15, 15, 15), fill="#424d48")
            for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
                draw.rectangle((x, y, x + 1, y + 1), fill="#424b41")
                draw.point((x, y), fill="#d2caab")
        if material in ("paper", "ledger"):
            draw.line((0, 0, 0, 15), fill="#a79c76")
            draw.line((15, 0, 15, 15), fill="#f2e6c5")
            if material == "paper":
                for y in (3, 6, 9, 12):
                    draw.line((0, y, 15, y), fill="#b4a783")
            else:
                draw.line((3, 2, 3, 13), fill="#b98569")
                for y in (4, 7, 10, 13):
                    draw.line((5, y, 11 if y % 2 else 13, y), fill="#6b7360")
        if material == "wheat":
            draw.rectangle((0, 0, 15, 15), fill="#725134", outline="#b68d50")
            draw.line((8, 3, 8, 13), fill="#e1bd68", width=1)
            for y in (4, 7, 10):
                draw.line((5, y - 1, 7, y + 1), fill="#dfb85e", width=2)
                draw.line((9, y + 1, 11, y - 1), fill="#f0d17c", width=2)
        if material in ("cloth", "sack"):
            for y in range(1, 16, 3):
                draw.line((0, y, 15, y), fill="#55725a" if material == "cloth" else "#a08757")
        if material == "grain":
            for x in range(1, 16, 3):
                for y in range(1, 16, 4):
                    draw.line((x, y, x + 1, y + 1), fill="#e0c06b")
        image.paste(tile, ((index % 4) * 16, (index // 4) * 16))
    return image


def default_uv(face: str, f: list, t: list) -> list:
    return {
        "down": [f[0], 16-t[2], t[0], 16-f[2]],
        "up": [f[0], f[2], t[0], t[2]],
        "north": [16-t[0], 16-t[1], 16-f[0], 16-f[1]],
        "south": [f[0], 16-t[1], t[0], 16-f[1]],
        "west": [f[2], 16-t[1], t[2], 16-f[1]],
        "east": [16-t[2], 16-t[1], 16-f[2], 16-f[1]],
    }[face]


def box(parts: list, name: str, f: list, t: list, material: str,
        faces: dict | None = None, full: tuple = (), rotation: dict | None = None) -> None:
    element = {"name": name, "from": f, "to": t, "faces": {}}
    for face in DIRS:
        mat = (faces or {}).get(face, material)
        index = MATERIALS.index(mat)
        ox, oy = index % 4 * 4, index // 4 * 4
        uv = [0, 0, 16, 16] if face in full else default_uv(face, f, t)
        element["faces"][face] = {"uv": [round(v / 4 + (ox if i % 2 == 0 else oy), 5)
                                          for i, v in enumerate(uv)], "texture": "#atlas"}
    if rotation:
        element["rotation"] = rotation
    parts.append(element)


def models() -> dict:
    center, bin_parts, vent = [], [], []
    box(center, "solid_plinth", [0, 0, 0], [16, 2, 16], "trim")
    box(center, "cabinet", [0, 2, .75], [16, 12, 16], "wood")
    box(center, "countertop", [0, 12, 0], [16, 13, 16], "top")
    box(center, "left_stile", [0, 2, 0], [1, 12, .75], "trim")
    box(center, "right_stile", [15, 2, 0], [16, 12, .75], "trim")
    for y in (3, 7.5):
        box(center, "ledger_drawer", [1.5, y, .2], [14.5, y + 3.5, .75], "drawer")
        box(center, "drawer_handle", [6.5, y + 1.4, 0], [9.5, y + 2, .2], "brass")
    box(center, "ledger_cover", [2, 13, 4], [11, 13.5, 13], "cloth")
    box(center, "left_pages", [2.4, 13.5, 4.4], [6.2, 14.2, 12.6], "paper",
        {"up": "ledger"}, ("up",))
    box(center, "right_pages", [6.6, 13.5, 4.4], [10.6, 14.2, 12.6], "paper",
        {"up": "ledger"}, ("up",))
    box(center, "book_spine", [6.2, 13.5, 4.2], [6.6, 14, 12.8], "cloth")
    box(center, "ink_bottle", [12, 13, 5], [14, 15, 7], "ink")
    box(center, "bottle_neck", [12.5, 15, 5.5], [13.5, 15.5, 6.5], "brass")
    box(center, "quill", [13.1, 14, 6], [13.45, 16, 6.35], "paper")
    box(center, "seal_base", [12, 13, 10], [15, 13.6, 13], "seal")
    box(center, "seal_handle", [12.8, 13.6, 10.8], [14.2, 15, 12.2], "trim")

    for x in (1, 13):
        box(bin_parts, "raised_skid", [x, 0, 1], [x + 2, 2, 15], "trim")
    box(bin_parts, "bottom", [1, 2, 1], [15, 3, 15], "trim")
    box(bin_parts, "stave_body", [1.4, 3, 1.4], [14.6, 14, 14.6], "wood")
    for x in (1, 13):
        for z in (1, 13):
            box(bin_parts, "corner_post", [x+.15, 3, z+.15], [x+1.85, 14.9, z+1.85], "trim")
    for y in (4, 11):
        box(bin_parts, "north_band", [1, y, 1], [15, y+1, 1.35], "iron")
        box(bin_parts, "south_band", [1, y, 14.65], [15, y+1, 15], "iron")
        box(bin_parts, "west_band", [1, y, 1.35], [1.35, y+1, 14.65], "iron")
        box(bin_parts, "east_band", [14.65, y, 1.35], [15, y+1, 14.65], "iron")
    box(bin_parts, "lid", [1, 14, 1.35], [15, 15.05, 14.65], "top")
    for x in (2, 12):
        box(bin_parts, "lid_batten", [x, 15, 2], [x+2, 16, 14], "trim")
    for side, z in (("north", 1.18), ("south", 14.6)):
        box(bin_parts, "wheat_label", [5, 6, z], [11, 10, z+.22], "trim",
            {side: "wheat"}, (side,))
    box(bin_parts, "lid_catch", [7, 12, 1], [9, 15.4, 1.3], "latch")

    for x in (0, 14):
        box(vent, "vertical_frame", [x, 0, 6.15], [x+2, 16, 10], "trim")
    for y in (0, 14):
        box(vent, "horizontal_frame", [2, y, 6.15], [14, y+2, 10], "top")
    for y in (3, 5.5, 8, 10.5, 13):
        box(vent, "sloping_louvre", [2, y-.4, 6.7], [14, y+.4, 9.3], "wood",
            rotation={"origin": [8, y, 8], "axis": "x", "angle": 22.5, "rescale": False})
    for x in (.6, 14.6):
        for y in (1, 14):
            box(vent, "frame_fastener", [x, y, 6], [x+.8, y+.8, 6.15], "iron")
    result = {}
    for name, elements in (("granary_center", center), ("granary_bin", bin_parts), ("granary_vent", vent)):
        result[name] = {"parent": "minecraft:block/block", "textures": {
            "particle": "miningdim:block/granary/materials", "atlas": "miningdim:block/granary/materials"},
            "elements": elements}
    return result


def vertices(element: dict, face: str) -> list:
    points = [[element["to"][i] if bit[i] else element["from"][i] for i in range(3)]
              for bit in VERTS[face]]
    if "rotation" in element:
        r = element["rotation"]
        angle = math.radians(r["angle"])
        c, s = math.cos(angle), math.sin(angle)
        out = []
        for point in points:
            x, y, z = [point[i]-r["origin"][i] for i in range(3)]
            out.append([x+r["origin"][0], y*c-z*s+r["origin"][1], y*s+z*c+r["origin"][2]])
        points = out
    return points


def validate(root: Path) -> dict:
    counts = {}
    for name in ("granary_center", "granary_bin", "granary_vent"):
        model = json.loads((root / ASSETS / f"models/block/{name}.json").read_text())
        for element in model["elements"]:
            assert all(element["from"][i] < element["to"][i] for i in range(3)), element["name"]
            for face in DIRS:
                assert all(0 <= v <= 16 for p in vertices(element, face) for v in p), element["name"]
                assert all(0 <= v <= 16 for v in element["faces"][face]["uv"])
        assert json.loads((root / ASSETS / f"models/item/{name}.json").read_text())["parent"] == f"miningdim:block/{name}"
        counts[name] = len(model["elements"])
    for name in ("granary_center", "granary_vent"):
        states = json.loads((root / ASSETS / f"blockstates/{name}.json").read_text())["variants"]
        assert list(states) == [f"facing={d}" for d in ("north", "east", "south", "west")]
        assert [v.get("y", 0) for v in states.values()] == [0, 90, 180, 270]
    image = Image.open(root / ASSETS / "textures/block/granary/materials.png")
    assert image.size == (64, 64) and image.mode == "RGBA"
    assert image.getchannel("A").getextrema() == (255, 255)
    return counts


def render(root: Path, output: Path) -> None:
    canvas = Image.new("RGB", (1320, 580), "#202b29")
    draw = ImageDraw.Draw(canvas)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 25) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 16) if font_path.exists() else ImageFont.load_default()
    draw.text((35, 20), "WOK  /  粮仓部件模型", font=font, fill="#eddfb8")
    draw.text((35, 58), "实际模型与贴图的软件渲染预览  /  单格尺寸  /  原版像素材质", font=small, fill="#aebdb0")
    texture = Image.open(root / ASSETS / "textures/block/granary/materials.png")
    for column, (name, title, detail) in enumerate((
            ("granary_center", "粮仓账台", "账本 / 印章 / 抽屉"),
            ("granary_bin", "储粮囤", "木板 / 金属箍 / 小麦铭牌"),
            ("granary_vent", "粮仓气窗", "木框百叶 / 四向放置"))):
        left = column * 430 + 25
        draw.rounded_rectangle((left, 105, left+410, 555), radius=12, fill="#2b3832", outline="#455248")
        draw.ellipse((left+65, 402, left+345, 445), fill="#202c27")
        layer = Image.new("RGBA", (400, 340))
        pixels, depths = layer.load(), [-1e9] * (400*340)
        model = json.loads((root / ASSETS / f"models/block/{name}.json").read_text())
        for element in model["elements"]:
            for face in DIRS:
                points = vertices(element, face)
                projected = []
                for x, y, z in points:
                    x, z = x-8, z-8
                    projected.append((200+(.8660254*x+.5*z)*15,
                                      245+(.25*x-.8660254*y-.4330127*z)*15,
                                      .4330127*x+.5*y-.75*z))
                a, b, c = projected[:3]
                cross = (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
                if cross <= 0:
                    continue
                u0, v0, u1, v1 = element["faces"][face]["uv"]
                uvs = [(u0*4, v0*4), (u0*4, v1*4), (u1*4, v1*4), (u1*4, v0*4)]
                shade = {"up": 1, "down": .5, "north": .85, "south": .85, "east": .67, "west": .67}[face]
                for ids in ((0, 1, 2), (0, 2, 3)):
                    tri = [projected[i] for i in ids]
                    uv = [uvs[i] for i in ids]
                    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = tri
                    den = (by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
                    if abs(den) < 1e-6:
                        continue
                    for py in range(max(0, math.floor(min(p[1] for p in tri))), min(340, math.ceil(max(p[1] for p in tri)))):
                        for px in range(max(0, math.floor(min(p[0] for p in tri))), min(400, math.ceil(max(p[0] for p in tri)))):
                            w0 = ((by-cy)*(px+.5-cx)+(cx-bx)*(py+.5-cy))/den
                            w1 = ((cy-ay)*(px+.5-cx)+(ax-cx)*(py+.5-cy))/den
                            w2 = 1-w0-w1
                            if min(w0, w1, w2) < -1e-7:
                                continue
                            depth = w0*az+w1*bz+w2*cz
                            if depth < depths[py*400+px]:
                                continue
                            tx = min(63, int(w0*uv[0][0]+w1*uv[1][0]+w2*uv[2][0]))
                            ty = min(63, int(w0*uv[0][1]+w1*uv[1][1]+w2*uv[2][1]))
                            color = texture.getpixel((tx, ty))
                            pixels[px, py] = tuple(int(c*shade) for c in color[:3])+(255,)
                            depths[py*400+px] = depth
        canvas.paste(layer, (left+5, 115), layer)
        draw.text((left+24, 467), title, font=font, fill="#eadbb2")
        draw.text((left+24, 507), detail, font=small, fill="#acbcae")
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--check", action="store_true", help="Validate existing outputs without rewriting them")
    args = parser.parse_args()
    if not args.check:
        for name, model in models().items():
            target = args.root / ASSETS / f"models/block/{name}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(model, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
        for name in ("granary_center", "granary_vent"):
            states = {"variants": {f"facing={d}": {"model": f"miningdim:block/{name}", **({"y": a} if a else {})}
                                   for d, a in zip(("north", "east", "south", "west"), (0, 90, 180, 270))}}
            path = args.root / ASSETS / f"blockstates/{name}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(states, indent=2)+"\n", encoding="utf-8", newline="\n")
        target = args.root / ASSETS / "textures/block/granary/materials.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        atlas().save(target)
    print(json.dumps(validate(args.root), ensure_ascii=False))
    if args.preview:
        render(args.root, args.preview)
        print(args.preview)


if __name__ == "__main__":
    main()
