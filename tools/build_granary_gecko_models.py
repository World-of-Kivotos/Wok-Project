"""Generate detailed GeckoLib 4.8.2 granary bones and previews from their actual geo files.

Native textures are resolved by the client renderer per material bone. No vanilla
texture is copied into the mod; a resource pack continues to replace those assets.
"""
from __future__ import annotations
import argparse
import json
import math
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
from PIL import Image, ImageDraw, ImageFont
ASSETS = Path("src/main/resources/assets/miningdim")
PARTS = ("center", "bin", "vent")
ITEM_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [.625, .625, .625]},
    "ground": {"rotation": [0, 180, 0], "translation": [0, 3, 0], "scale": [.25, .25, .25]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [.5, .5, .5]},
    "thirdperson_righthand": {"rotation": [75, 135, 0], "translation": [0, 2.5, 0], "scale": [.375, .375, .375]},
    "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 0, 0], "scale": [.4, .4, .4]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}
DIRS = ("down", "up", "north", "south", "west", "east")
DETAILS = {"iron": "minecraft:block/iron_block", "latch": "minecraft:block/iron_block",
           "metal_dark": "minecraft:block/anvil", "brass": "minecraft:block/gold_block",
           "cloth": "minecraft:block/green_wool", "seal": "minecraft:block/red_terracotta",
           "ink": "minecraft:block/black_concrete", "shadow": "minecraft:block/black_concrete",
           "feather": "minecraft:block/white_wool", "paper": "miningdim:block/granary/page_edges",
           "ledger": "miningdim:block/granary/ledger_page", "wheat": "miningdim:block/granary/wheat_mark"}
VERTS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}


def detail_textures() -> dict[str, Image.Image]:
    paper = Image.new("RGBA", (16, 16), "#e4d8b2")
    draw = ImageDraw.Draw(paper)
    for y in (3, 6, 9, 12):
        draw.line((0, y, 15, y), fill="#b8aa83")
    ledger = Image.new("RGBA", (16, 16), "#e9dfbe")
    draw = ImageDraw.Draw(ledger)
    draw.line((3, 2, 3, 13), fill="#ba8e77")
    for y in (4, 7, 10, 13):
        draw.line((5, y, 11 if y % 2 else 13, y), fill="#69765f")
    wheat = Image.new("RGBA", (16, 16))
    draw = ImageDraw.Draw(wheat)
    draw.line((8, 2, 8, 14), fill="#e8bc59")
    for y in (4, 7, 10):
        draw.line((5, y-1, 7, y+1), fill="#d7a342", width=2)
        draw.line((9, y+1, 11, y-1), fill="#f0cf78", width=2)
    return {"page_edges": paper, "ledger_page": ledger, "wheat_mark": wheat}


def default_uv(face: str, f: list, t: list) -> list:
    return {
        "down": [f[0], 16-t[2], t[0], 16-f[2]],
        "up": [f[0], f[2], t[0], t[2]],
        "north": [16-t[0], 16-t[1], 16-f[0], 16-f[1]],
        "south": [f[0], 16-t[1], t[0], 16-f[1]],
        "west": [f[2], 16-t[1], t[2], 16-f[1]],
        "east": [16-t[2], 16-t[1], 16-f[2], 16-f[1]],
    }[face]


def box(out, group, name, f, t, material="wood", faces=None, full=(), rotation=None, pivot=None):
    face_data = {}
    for face in DIRS:
        mat = (faces or {}).get(face, material)
        if mat == "trim" and face in ("up", "down"):
            mat = "trim_top"
        if mat == "wood" and face in ("up", "down"):
            mat = "wood_top" if face == "up" else "wood_bottom"
        uv = [0, 0, 16, 16] if face in full else default_uv(face, f, t)
        face_data[face] = {"texture": "#" + mat, "uv": [round(v, 5) for v in uv]}
    element = {"name": name, "group": group, "from": f, "to": t, "faces": face_data}
    if rotation:
        element["rotation"] = {"pivot": pivot, "euler": rotation}
    out.append(element)


def geometry():
    center, storage, vent = [], [], []
    add = lambda *a, **kw: box(center, *a, **kw)
    add("cabinet", "recessed_toe", [.6, 0, .7], [15.4, .8, 15.3], "trim")
    add("cabinet", "plinth", [0, .8, 0], [16, 1.65, 16], "trim")
    add("cabinet", "carcass", [1, 1.65, 1.2], [15, 11.8, 15], "wood")
    for x in (0, 14.9):
        add("cabinet", "corner_stile", [x, 1.65, .7], [x+1.1, 11.9, 15.7], "trim")
    add("cabinet", "back_panel", [1.1, 2.2, 15], [14.9, 11.4, 15.8])
    for x in (.12, 15.62):
        for z in (2, 13.8):
            add("cabinet", "side_panel_rail", [x, 2.3, z], [x+.26, 11.25, z+.7], "trim")
    add("countertop", "edge_shadow", [0, 11.8, 0], [16, 12.1, 16], "trim")
    add("countertop", "beveled_edge", [.18, 12.1, .18], [15.82, 12.48, 15.82], "trim")
    add("countertop", "writing_surface", [0, 12.48, 0], [16, 13, 16])
    add("drawers", "drawer_shadow", [1.1, 1.8, .85], [14.9, 11.65, 1.2], "shadow")
    for y in (2.15, 7):
        add("drawers", "recessed_front", [1.5, y, .5], [14.5, y+4.05, .85])
        for x in (1.3, 14.05):
            add("drawers", "vertical_moulding", [x, y-.15, .32], [x+.65, y+4.2, .55], "trim")
        for h in (y-.15, y+3.55):
            add("drawers", "horizontal_moulding", [1.95, h, .32], [14.05, h+.65, .55], "trim")
        add("drawers", "inset_field", [2.25, y+.65, .44], [13.75, y+3.2, .5])
        for x in (6.0, 9.5):
            add("hardware", "handle_escutcheon", [x-.25, y+1.5, .22], [x+.75, y+2.5, .44], "metal_dark")
            add("hardware", "handle_standoff", [x, y+1.8, .05], [x+.5, y+2.2, .27], "brass")
            add("hardware", "mounting_pin", [x+.16, y+1.62, .18], [x+.34, y+1.8, .23], "brass")
        add("hardware", "raised_pull", [6, y+1.85, 0], [10, y+2.15, .12], "brass")
    # Open leaves use genuine seven-degree cube rotation, beyond vanilla block-model angles.
    for left, angle in ((True, 7), (False, -7)):
        f, t = ([2.2, 13.72, 4.45], [6.5, 13.98, 12.75]) if left else ([6.7, 13.72, 4.45], [11, 13.98, 12.75])
        pivot = [6.6, 13.72, 8.5]
        add("book", "cloth_bound_cover", f, t, "cloth", rotation=[0, 0, angle], pivot=pivot)
        pf, pt = [f[0]+.22, 13.99, 4.7], [t[0]-.2, 14.38, 12.5]
        add("book", "page_block", pf, pt, "paper", {"up": "ledger"}, ("up",), [0, 0, angle], pivot)
        for yy in (14.07, 14.2):
            add("book", "page_edge", [pf[0]-.02, yy, 12.5], [pt[0]+.02, yy+.05, 12.6], "paper", rotation=[0, 0, angle], pivot=pivot)
    add("book", "rounded_spine", [6.45, 13.6, 4.4], [6.75, 14.2, 12.8], "cloth")
    add("book", "ribbon_bookmark", [8.7, 13.35, 12.5], [9.05, 13.5, 13], "seal")
    add("inkwell", "bottle_base", [12.1, 13, 5.1], [14, 13.35, 7], "metal_dark")
    add("inkwell", "ink_bottle", [12.25, 13.35, 5.25], [13.85, 14.5, 6.85], "ink")
    add("inkwell", "brass_neck", [12.6, 14.5, 5.6], [13.5, 14.85, 6.5], "brass")
    add("inkwell", "ink_aperture", [12.78, 14.85, 5.78], [13.32, 14.9, 6.32], "ink")
    add("quill", "pen_shaft", [12.93, 14.5, 6], [13.07, 15.87, 6.14], "feather", rotation=[0, 0, -14], pivot=[13, 14.5, 6.07])
    for i, width in enumerate((.18, .32, .4, .3)):
        y = 15.05 + i*.18
        add("quill", "feather_vane", [13-width, y, 6.02], [13+width, y+.2, 6.1], "feather", rotation=[0, 0, -14], pivot=[13, 14.5, 6.07])
    add("seal", "stamp_base", [12.1, 13, 10], [14.9, 13.5, 12.8], "seal")
    add("seal", "stamp_collar", [12.55, 13.5, 10.45], [14.45, 13.75, 12.35], "brass")
    add("seal", "stamp_stem", [13, 13.75, 10.9], [14, 14.6, 11.9], "trim")
    add("seal", "stamp_knob", [12.7, 14.6, 10.6], [14.3, 15, 12.2], "trim")

    add = lambda *a, **kw: box(storage, *a, **kw)
    for x in (1, 13):
        add("body", "skid", [x, .65, 1], [x+2, 2.15, 15], "trim")
        for z in (1.4, 11.6):
            add("body", "foot", [x+.12, 0, z], [x+1.88, .65, z+3], "trim")
    add("body", "raised_base", [1.1, 2.15, 1.1], [14.9, 3, 14.9], "trim")
    add("body", "storage_body", [1.4, 3, 1.4], [14.6, 14.1, 14.6])
    for x in (1.22, 13.22):
        for z in (1.22, 13.22):
            add("body", "corner_post", [x, 3, z], [x+1.56, 14.72, z+1.56], "trim")
    for y in (4, 10.8):
        for f, t in (([1.12, y, 1.18], [14.88, y+1.05, 1.38]),
                     ([1.12, y, 14.62], [14.88, y+1.05, 14.82]),
                     ([1.18, y, 1.38], [1.38, y+1.05, 14.62]),
                     ([14.62, y, 1.38], [14.82, y+1.05, 14.62])):
            add("bands", "recessed_band_edge", f, t, "metal_dark")
        for f, t in (([1.12, y+.2, 1.1], [14.88, y+.85, 1.18]),
                     ([1.12, y+.2, 14.82], [14.88, y+.85, 14.9]),
                     ([1.1, y+.2, 1.38], [1.18, y+.85, 14.62]),
                     ([14.82, y+.2, 1.38], [14.9, y+.85, 14.62])):
            add("bands", "steel_band", f, t, "iron")
        for x in (2, 5, 11, 14):
            for z in (1.02, 14.9):
                add("hardware", "band_rivet", [x-.13, y+.38, z], [x+.13, y+.64, z+.08], "metal_dark")
    add("lid", "lid_shadow", [1.2, 14.1, 1.2], [14.8, 14.42, 14.8], "metal_dark")
    add("lid", "lid_frame", [1.1, 14.42, 1.1], [14.9, 15.1, 14.9], "trim")
    for i in range(4):
        x = 1.45+i*3.3
        add("lid", "lid_plank", [x, 15.1, 1.45], [x+3.2, 15.65, 14.55])
    for x in (2.5, 12.5):
        add("lid", "lid_batten", [x, 15.6, 2], [x+1, 15.85, 14], "trim")
        for z in (3, 13):
            add("lid", "batten_rivet", [x+.3, 15.85, z-.2], [x+.7, 16, z+.2], "metal_dark")
    for x in (3.5, 10.5):
        add("hinges", "hinge_plate", [x, 13.2, 14.73], [x+2, 14.4, 14.88], "metal_dark")
        add("hinges", "hinge_barrel", [x-.1, 14.35, 14.55], [x+2.1, 14.9, 15], "iron")
    add("latch", "lock_backplate", [6.7, 12.15, 1.05], [9.3, 14.7, 1.25], "metal_dark")
    add("latch", "locking_strap", [7.4, 12.45, 1], [8.6, 15.4, 1.05], "iron")
    add("latch", "padlock", [7.15, 12.5, 1.01], [8.85, 13.65, 1.09], "brass")
    add("latch", "keyhole", [7.87, 12.75, 1], [8.13, 13.05, 1.01], "ink")
    add("label", "wheat_plaque", [5, 6.5, 1.18], [11, 9.9, 1.4], "trim")
    add("label", "wheat_inlay", [5.4, 6.7, 1.16], [10.6, 9.7, 1.18], "trim", {"north": "wheat"}, ("north",))
    for z in (6, 9.5):
        add("handle", "side_mount", [14.68, 7, z], [14.87, 8.5, z+.65], "metal_dark")
    for z in (6.2, 9.65):
        add("handle", "handle_stem", [14.87, 7.5, z], [15, 7.8, z+.25], "iron")
    add("handle", "side_pull", [14.92, 7.5, 6.2], [15, 7.8, 9.9], "iron")

    add = lambda *a, **kw: box(vent, *a, **kw)
    for x in (0, 14.5):
        add("frame", "outer_stile", [x, 0, 6.25], [x+1.5, 16, 9.75], "trim")
    for y in (0, 14.5):
        add("frame", "outer_rail", [1.5, y, 6.25], [14.5, y+1.5, 9.75], "trim")
    for z in (6.12, 9.75):
        for x in (.35, 14.75):
            add("frame", "raised_beading", [x, .35, z], [x+.9, 15.65, z+.13], "wood")
        for y in (.35, 14.75):
            add("frame", "raised_beading", [1.25, y, z], [14.75, y+.9, z+.13], "wood")
    for y in (3, 5, 7, 9, 11, 13):
        pivot = [8, y, 8]
        add("slats", "louvre", [1.5, y-.16, 7], [14.5, y+.16, 9], "wood", rotation=[28, 0, 0], pivot=pivot)
        add("slats", "rolled_lip", [1.5, y-.25, 6.99], [14.5, y-.16, 7.2], "trim", rotation=[28, 0, 0], pivot=pivot)
        for x in (1.35, 14.5):
            add("pivots", "louvre_pivot", [x, y-.2, 7.75], [x+.15, y+.2, 8.25], "metal_dark")
    add("slats", "linkage_rod", [7.85, 1.8, 9.3], [8.15, 14.2, 9.55], "trim")
    for x in (.6, 14.85):
        for y in (.6, 14.85):
            add("fixings", "corner_plate", [x-.1, y-.1, 6.05], [x+.65, y+.65, 6.12], "metal_dark")
            add("fixings", "corner_screw", [x+.12, y+.12, 6], [x+.4, y+.4, 6.05], "iron")
    return {"center": center, "bin": storage, "vent": vent}


def export_geo(part, elements):
    groups = dict.fromkeys(e["group"] for e in elements)
    bones = [{"name": "root", "pivot": [0, 0, 0]}]
    bones += [{"name": g, "parent": "root", "pivot": [0, 0, 0]} for g in groups]
    material_bones = {}
    for element in elements:
        f, t = element["from"], element["to"]
        by_material = {}
        for direction, face in element["faces"].items():
            uv = face["uv"]
            geo_direction = {"east": "west", "west": "east"}.get(direction, direction)
            by_material.setdefault(face["texture"][1:], {})[geo_direction] = {
                "uv": uv[:2], "uv_size": [round(uv[2]-uv[0], 5), round(uv[3]-uv[1], 5)]}
        for material, uv in by_material.items():
            key = (element["group"], material)
            bone = material_bones.setdefault(key, {"name": f"mat_{material}__{element['group']}",
                                                   "parent": element["group"], "pivot": [0, 0, 0], "cubes": []})
            cube = {"origin": [round(8-t[0], 5), f[1], round(f[2]-8, 5)],
                    "size": [round(t[i]-f[i], 5) for i in range(3)], "uv": uv}
            if "rotation" in element:
                r = element["rotation"]; p = r["pivot"]; x, y, z = r["euler"]
                cube.update({"pivot": [8-p[0], p[1], p[2]-8], "rotation": [-x, -y, z]})
            bone["cubes"].append(cube)
    bones += list(material_bones.values())
    return {"format_version": "1.12.0", "minecraft:geometry": [{"description": {
        "identifier": "geometry.granary."+part, "texture_width": 16, "texture_height": 16,
        "visible_bounds_width": 2, "visible_bounds_height": 2, "visible_bounds_offset": [0, .5, 0]}, "bones": bones}]}


def read_geo(root, part):
    data = json.loads((root / ASSETS / f"geo/block/granary_{part}.geo.json").read_bytes())
    elements = []
    for bone in data["minecraft:geometry"][0]["bones"]:
        if not bone["name"].startswith("mat_"):
            continue
        material = bone["name"].split("__", 1)[0][4:]
        for cube in bone["cubes"]:
            o, s = cube["origin"], cube["size"]
            element = {"from": [round(8-o[0]-s[0], 5), o[1], o[2]+8],
                       "to": [8-o[0], o[1]+s[1], o[2]+s[2]+8], "faces": {}}
            for direction, face in cube["uv"].items():
                direction = {"east": "west", "west": "east"}.get(direction, direction)
                u, v = face["uv"]; w, h = face["uv_size"]
                element["faces"][direction] = {"uv": [u, v, u+w, v+h], "texture": "#"+material}
            if "rotation" in cube:
                p = cube["pivot"]; x, y, z = cube["rotation"]
                element["rotation"] = {"pivot": [8-p[0], p[1], p[2]+8], "euler": [-x, -y, z]}
            elements.append(element)
    return elements


def vertices(element, face):
    points = [[element["to"][i] if bit[i] else element["from"][i] for i in range(3)] for bit in VERTS[face]]
    if "rotation" not in element:
        return points
    r = element["rotation"]; out = []
    for p in points:
        x, y, z = [p[i]-r["pivot"][i] for i in range(3)]
        for axis, angle in enumerate(r["euler"]):
            c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
            if axis == 0: y, z = y*c-z*s, y*s+z*c
            elif axis == 1: x, z = x*c+z*s, -x*s+z*c
            else: x, y = x*c-y*s, x*s+y*c
        out.append([x+r["pivot"][0], y+r["pivot"][1], z+r["pivot"][2]])
    return out


def block_name(wood: str, part: str) -> str:
    return f"granary_{part}" if wood == "oak" else f"{wood}_granary_{part}"


def load_texture(root: Path, resource: str, vanilla: ZipFile) -> Image.Image:
    ns, name = resource.split(":", 1)
    path = f"assets/{ns}/textures/{name}.png"
    raw = vanilla.read(path) if ns == "minecraft" else (root / "src/main/resources" / path).read_bytes()
    return Image.open(BytesIO(raw)).convert("RGBA")


def material_textures(material):
    wood = material["wood"]
    return DETAILS | {"wood": wood, "wood_top": material.get("wood_top", wood),
                      "wood_bottom": material.get("wood_bottom", wood), "trim": material["trim"],
                      "trim_top": material["trim_top"]}


def render(root: Path, output: Path, vanilla: ZipFile, woods: list[str], materials: dict) -> None:
    canvas = Image.new("RGB", (1320, 105 + 480 * len(woods)), "#202b29")
    draw = ImageDraw.Draw(canvas)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 25) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 16) if font_path.exists() else ImageFont.load_default()
    draw.text((35, 20), "WOK  /  GeckoLib 精细粮仓", font=font, fill="#eddfb8")
    draw.text((35, 58), "实际 .geo.json 骨骼文件渲染  /  原版材质  /  书页、锁扣、边框与百叶细化", font=small, fill="#aebdb0")
    texture_cache = {}
    for row, wood in enumerate(woods):
        for column, (name, title, detail) in enumerate((
                ("center", "粮仓账台", "账本 / 印章 / 抽屉"),
                ("bin", "储粮囤", "木板 / 金属箍 / 小麦铭牌"),
                ("vent", "粮仓气窗", "木框百叶 / 四向放置"))):
            left = column * 430 + 25
            draw.rounded_rectangle((left, 105+row*480, left+410, 555+row*480), radius=12, fill="#2b3832", outline="#455248")
            draw.ellipse((left+65, 402+row*480, left+345, 445+row*480), fill="#202c27")
            layer = Image.new("RGBA", (400, 340))
            pixels, depths = layer.load(), [-1e9] * (400*340)
            model = {"elements": read_geo(root, name), "textures": material_textures(materials[wood])}
            for element in model["elements"]:
                for face in element["faces"]:
                    resource = model["textures"][element["faces"][face]["texture"][1:]]
                    if resource not in texture_cache:
                        texture_cache[resource] = load_texture(root, resource, vanilla)
                    texture = texture_cache[resource]
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
                    uvs = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
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
                                tx = min(15, int(w0*uv[0][0]+w1*uv[1][0]+w2*uv[2][0]))
                                ty = min(15, int(w0*uv[0][1]+w1*uv[1][1]+w2*uv[2][1]))
                                color = texture.getpixel((tx, ty))
                                if color[3] < 128:
                                    continue
                                pixels[px, py] = tuple(int(c*shade) for c in color[:3])+(255,)
                                depths[py*400+px] = depth
            canvas.paste(layer, (left+5, 115+row*480), layer)
            draw.text((left+24, 467+row*480), materials[wood]["zh"] + title, font=font, fill="#eadbb2")
            draw.text((left+24, 507+row*480), detail, font=small, fill="#acbcae")
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)


def validate(root, vanilla, materials):
    counts, resources = {}, set()
    for part in PARTS:
        elements = read_geo(root, part)
        counts[part] = {"material_cubes": len(elements), "visible_faces": sum(len(e["faces"]) for e in elements)}
        for element in elements:
            for face, definition in element["faces"].items():
                points = vertices(element, face)
                low, high = (1, 15) if part == "bin" else (0, 16)
                assert all(low-1e-6 <= p[0] <= high+1e-6 and 0 <= p[1] <= 16+1e-6 and low-1e-6 <= p[2] <= high+1e-6 for p in points), (part, element)
                if part == "vent":
                    assert all(6-1e-6 <= p[2] <= 10+1e-6 for p in points), element
                if part == "center":
                    assert all(p[1] <= 13+1e-6 or 2-1e-6 <= p[0] <= 15+1e-6 and 4-1e-6 <= p[2] <= 13+1e-6 for p in points), element
                assert all(0 <= v <= 16 for v in definition["uv"]), definition
                for material in materials.values():
                    resources.add(material_textures(material)[definition["texture"][1:]])
    for resource in resources:
        assert load_texture(root, resource, vanilla).size == (16, 16), resource
    for material in materials.values():
        for part in PARTS:
            name = block_name(material["id"], part)
            model = json.loads((root / ASSETS / f"models/item/{name}.json").read_bytes())
            assert model["parent"] == "builtin/entity", name
            assert model["display"] == ITEM_DISPLAY, name
            state = json.loads((root / ASSETS / f"blockstates/{name}.json").read_bytes())["variants"]
            assert len(state) == (1 if part == "bin" else 4), name
    return {"materials": len(materials), "blocks": len(materials)*3, "geometry": counts, "textures": len(resources)}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--minecraft-jar", type=Path, default=Path.home()/".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar")
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--preview-materials", "--preview-woods", dest="preview_materials", default="oak,quartz,stone_bricks")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    materials = {m["id"]: m for m in json.loads((args.root/"tools/assets/granary/materials.json").read_bytes())}
    if not args.check:
        for part, elements in geometry().items():
            write_json(args.root/ASSETS/f"geo/block/granary_{part}.geo.json", export_geo(part, elements))
        for material in materials.values():
            for part in PARTS:
                name = block_name(material["id"], part)
                write_json(args.root/ASSETS/f"models/item/{name}.json", {"parent": "builtin/entity", "display": ITEM_DISPLAY})
                write_json(args.root/ASSETS/f"models/block/{name}.json", {"textures": {"particle": material["wood"]}})
                states = {"": {"model": f"miningdim:block/{name}"}} if part == "bin" else {
                    f"facing={d}": {"model": f"miningdim:block/{name}", **({"y": a} if a else {})}
                    for d, a in zip(("north", "east", "south", "west"), (0, 90, 180, 270))}
                write_json(args.root/ASSETS/f"blockstates/{name}.json", {"variants": states})
        for name, image in detail_textures().items():
            path = args.root/ASSETS/f"textures/block/granary/{name}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            image.save(path)
    with ZipFile(args.minecraft_jar) as vanilla:
        print(json.dumps(validate(args.root, vanilla, materials), ensure_ascii=False))
        if args.preview:
            render(args.root, args.preview, vanilla, args.preview_materials.split(","), materials)
            print(args.preview)


if __name__ == "__main__":
    main()
