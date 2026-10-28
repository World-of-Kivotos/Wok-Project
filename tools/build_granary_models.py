"""Build vanilla-wood granary models and previews for Minecraft 1.20.1.

Run with Python + Pillow. Geometry stays within one block; the preview reads the
same JSON and UVs as the game, including the vent's 22.5 degree louvres.
"""
from __future__ import annotations

import argparse
import json
import math
from io import BytesIO
from zipfile import ZipFile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path("src/main/resources/assets/miningdim")
WOODS = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped")
WOOD_NAMES = ("橡木", "云杉木", "白桦木", "丛林木", "金合欢木", "深色橡木", "红树木", "樱花木", "竹", "绯红", "诡异")
PARTS = ("center", "bin", "vent")
DETAILS = {
    "iron": "minecraft:block/iron_block", "latch": "minecraft:block/iron_block",
    "brass": "minecraft:block/gold_block", "cloth": "minecraft:block/green_wool",
    "seal": "minecraft:block/red_terracotta", "ink": "minecraft:block/black_concrete",
    "paper": "miningdim:block/granary/page_edges", "ledger": "miningdim:block/granary/ledger_page",
    "wheat": "miningdim:block/granary/wheat_mark",
}
DIRS = ("down", "up", "north", "south", "west", "east")
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


def block_name(wood: str, part: str) -> str:
    return f"granary_{part}" if wood == "oak" else f"{wood}_granary_{part}"


def wood_textures(wood: str) -> dict:
    frame = "stripped_bamboo_block" if wood == "bamboo" else f"stripped_{wood}_stem" if wood in ("crimson", "warped") else f"stripped_{wood}_log"
    return {"wood": f"minecraft:block/{wood}_planks", "trim": f"minecraft:block/{frame}",
            "trim_top": f"minecraft:block/{frame}_top", "particle": f"minecraft:block/{wood}_planks"}


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
        mat = {"top": "wood", "drawer": "wood"}.get(mat, mat)
        if mat == "trim" and face in ("up", "down"):
            mat = "trim_top"
        uv = [0, 0, 16, 16] if face in full else default_uv(face, f, t)
        element["faces"][face] = {"uv": [round(v, 5) for v in uv], "texture": "#" + mat}
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
    return {f"template_{part}": {"parent": "minecraft:block/block", "render_type": "minecraft:cutout",
            "textures": DETAILS, "elements": elements}
            for part, elements in zip(PARTS, (center, bin_parts, vent))}


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


def resolve_model(root: Path, model_id: str, vanilla: ZipFile) -> dict:
    chain, seen = [], set()
    while model_id:
        if model_id in seen:
            raise ValueError(f"Cyclic model parent: {model_id}")
        seen.add(model_id)
        ns, name = model_id.split(":", 1)
        path = f"assets/{ns}/models/{name}.json"
        model = json.loads(vanilla.read(path) if ns == "minecraft" else (root / "src/main/resources" / path).read_bytes())
        chain.append(model)
        model_id = model.get("parent")
    textures = {}
    for model in reversed(chain):
        textures.update(model.get("textures", {}))
    return {"textures": textures, "elements": next(m["elements"] for m in chain if "elements" in m)}


def texture_id(ref: str, textures: dict) -> str:
    seen = set()
    while ref.startswith("#"):
        if ref in seen:
            raise ValueError(f"Cyclic texture: {ref}")
        seen.add(ref)
        ref = textures[ref[1:]]
    return ref


def load_texture(root: Path, resource: str, vanilla: ZipFile) -> Image.Image:
    ns, name = resource.split(":", 1)
    path = f"assets/{ns}/textures/{name}.png"
    raw = vanilla.read(path) if ns == "minecraft" else (root / "src/main/resources" / path).read_bytes()
    return Image.open(BytesIO(raw)).convert("RGBA")


def validate(root: Path, vanilla: ZipFile) -> dict:
    textures, count = set(), 0
    for wood in WOODS:
        for part in PARTS:
            name = block_name(wood, part)
            model = resolve_model(root, f"miningdim:block/{name}", vanilla)
            assert model["textures"]["wood"] == f"minecraft:block/{wood}_planks", name
            for element in model["elements"]:
                assert all(element["from"][i] < element["to"][i] for i in range(3)), element["name"]
                for face in DIRS:
                    assert all(0 <= v <= 16 for p in vertices(element, face) for v in p), element["name"]
                    assert all(0 <= v <= 16 for v in element["faces"][face]["uv"])
                    textures.add(texture_id(element["faces"][face]["texture"], model["textures"]))
            item = json.loads((root / ASSETS / f"models/item/{name}.json").read_bytes())
            assert item["parent"] == f"miningdim:block/{name}"
            states = json.loads((root / ASSETS / f"blockstates/{name}.json").read_bytes())["variants"]
            if part == "bin":
                assert states == {"": {"model": f"miningdim:block/{name}"}}
            else:
                assert list(states) == [f"facing={d}" for d in ("north", "east", "south", "west")]
                assert [v.get("y", 0) for v in states.values()] == [0, 90, 180, 270]
            count += 1
    for resource in textures:
        image = load_texture(root, resource, vanilla)
        assert image.size == (16, 16), resource
    return {"wood_types": len(WOODS), "blocks": count, "resolved_textures": len(textures)}


def render(root: Path, output: Path, vanilla: ZipFile, woods: list[str]) -> None:
    canvas = Image.new("RGB", (1320, 105 + 480 * len(woods)), "#202b29")
    draw = ImageDraw.Draw(canvas)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 25) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 16) if font_path.exists() else ImageFont.load_default()
    draw.text((35, 20), "WOK  /  原版木材粮仓", font=font, fill="#eddfb8")
    draw.text((35, 58), "直接引用 Minecraft 1.20.1 木板与去皮原木  /  实际模型的软件渲染预览", font=small, fill="#aebdb0")
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
            model = resolve_model(root, f"miningdim:block/{block_name(wood, name)}", vanilla)
            for element in model["elements"]:
                for face in DIRS:
                    resource = texture_id(element["faces"][face]["texture"], model["textures"])
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
            draw.text((left+24, 467+row*480), WOOD_NAMES[WOODS.index(wood)] + title, font=font, fill="#eadbb2")
            draw.text((left+24, 507+row*480), detail, font=small, fill="#acbcae")
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if "elements" in value:
        header = {k: v for k, v in value.items() if k != "elements"}
        text = json.dumps(header, ensure_ascii=False, indent=2)[:-2] + ',\n  "elements": [\n'
        text += ",\n".join("    " + json.dumps(e, separators=(",", ":")) for e in value["elements"])
        text += "\n  ]\n}\n"
    else:
        text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--minecraft-jar", type=Path, default=Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar")
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--preview-woods", default="oak,birch")
    parser.add_argument("--check", action="store_true", help="Validate existing outputs without rewriting them")
    args = parser.parse_args()
    if not args.check:
        for name, model in models().items():
            write_json(args.root / ASSETS / f"models/block/granary/{name}.json", model)
        for wood in WOODS:
            for part in PARTS:
                name = block_name(wood, part)
                write_json(args.root / ASSETS / f"models/block/{name}.json", {
                    "parent": f"miningdim:block/granary/template_{part}", "textures": wood_textures(wood)})
                write_json(args.root / ASSETS / f"models/item/{name}.json", {"parent": f"miningdim:block/{name}"})
                states = {"": {"model": f"miningdim:block/{name}"}} if part == "bin" else {
                    f"facing={d}": {"model": f"miningdim:block/{name}", **({"y": a} if a else {})}
                    for d, a in zip(("north", "east", "south", "west"), (0, 90, 180, 270))}
                write_json(args.root / ASSETS / f"blockstates/{name}.json", {"variants": states})
        for name, image in detail_textures().items():
            target = args.root / ASSETS / f"textures/block/granary/{name}.png"
            target.parent.mkdir(parents=True, exist_ok=True)
            image.save(target)
    with ZipFile(args.minecraft_jar) as vanilla:
        print(json.dumps(validate(args.root, vanilla), ensure_ascii=False))
        if args.preview:
            woods = args.preview_woods.split(",")
            assert all(wood in WOODS for wood in woods)
            render(args.root, args.preview, vanilla, woods)
            print(args.preview)


if __name__ == "__main__":
    main()
