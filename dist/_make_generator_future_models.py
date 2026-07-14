from __future__ import annotations

import json
import math
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/block/generator/future"
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block/generator/future"
PREVIEW_PATH = ROOT / "dist/generator-future-model-preview.png"

FACES = ("north", "south", "east", "west", "up", "down")
MATERIALS = {
    "frame": (39, 45, 52),
    "frame_edge": (20, 25, 31),
    "armor": (207, 213, 216),
    "armor_edge": (139, 151, 159),
    "cyan_glow": (0, 207, 244),
    "cyan_core": (45, 229, 255),
    "glass": (14, 91, 116),
    "vent": (27, 34, 41),
    "control": (48, 57, 64),
}


def element(name, start, end, material, *, face_materials=None, shade=True, rotation=None):
    return {
        "name": name,
        "from": [float(value) for value in start],
        "to": [float(value) for value in end],
        "material": material,
        "face_materials": dict(face_materials or {}),
        "shade": shade,
        "rotation": rotation,
    }


GLOBAL = []
LOCAL = {(x, z, y): [] for x in range(3) for z in range(2) for y in range(2)}


def add(name, start, end, material, **kwargs):
    GLOBAL.append(element(name, start, end, material, **kwargs))


def add_local(x, z, y, name, start, end, material, **kwargs):
    LOCAL[(x, z, y)].append(element(name, start, end, material, **kwargs))


def build_geometry():
    # Continuous 3 x 1 foundation and the deep structural shells.
    add("front foundation rail", (0, 0, 0), (48, 2, 3), "frame_edge")
    add("foundation core", (0, 0, 3), (48, 2, 13), "frame")
    add("rear foundation rail", (0, 0, 13), (48, 2, 16), "frame_edge")
    add("raised front sill", (2, 2, 1), (46, 3, 4), "armor_edge")
    add("left structural shell", (2, 3, 3), (15, 24, 15), "frame")
    add("core structural shell", (17, 3, 3.5), (31, 24, 15), "frame")
    add("radiator structural shell", (33, 3, 3), (46, 25, 15), "frame")
    add("rear structural spine", (2, 2, 13), (46, 27, 16), "frame_edge")

    for index, (x0, x1) in enumerate(((0, 2), (15, 17), (31, 33), (46, 48))):
        add(f"full depth frame post {index}", (x0, 2, 1), (x1, 28, 15), "frame_edge")
        add(f"front post cap {index}", (x0, 2, 0), (x1, 27, 3), "frame_edge")
    for index, (x0, x1) in enumerate(((0, 3), (14.5, 17.5), (30.5, 33.5), (45, 48))):
        add(f"projecting foot {index}", (x0, 0, 0), (x1, 4, 4), "frame_edge")

    # Stepped roof armor: three distinct heights keep the silhouette angular.
    add("left roof armor", (2, 24, 2), (15, 27, 14), "armor")
    add("left roof step", (4, 27, 4), (13, 29, 12), "armor")
    add("left roof dark crown", (0, 26, 1), (3, 29, 15), "frame_edge")
    add("core roof armor", (17, 23, 2), (31, 26, 14), "armor")
    add("core roof bevel", (19, 26, 3), (29, 28, 13), "armor_edge")
    add("radiator roof armor", (33, 24, 2), (46, 27, 14), "armor")
    add("radiator roof step", (35, 27, 3), (45, 29, 13), "armor")
    add("radiator crown rail", (44.5, 26, 1), (48, 29, 15), "frame_edge")
    add("rear cyan bus", (20, 25.5, 14), (28, 26.5, 16), "cyan_glow", shade=False)

    # Left control cabin. The white chassis projects farther north than its dark inset.
    add("control upper brow", (5, 22, 0), (13, 25, 2.2), "armor")
    add("control upper left step", (3, 20, 0), (6, 23, 2.2), "armor_edge")
    add("control upper right step", (12, 20, 0), (15, 23, 2.2), "armor_edge")
    add("control left armor rail", (2.5, 6, 0), (5, 21, 2.2), "armor")
    add("control right armor rail", (13, 6, 0), (15.5, 21, 2.2), "armor")
    add("control lower sill", (3, 3, 0), (15, 6, 2.2), "armor_edge")
    add("control dark recess", (4.5, 5, 2.1), (14, 21, 3.2), "frame_edge")
    add("control white chassis", (5, 6, 1.2), (13.5, 20, 2.4), "armor")
    add("control screen", (6, 17.2, 0.35), (12.5, 19.2, 1.25), "glass", face_materials={"north": "control"})
    add("control status housing", (5.7, 8.2, 0.35), (8.4, 16.4, 1.25), "frame_edge")
    for index, y0 in enumerate((9.2, 11.2, 13.2, 15.2)):
        add(f"control cyan status {index}", (6.3, y0, 0), (7.8, y0 + 0.85, 0.45), "cyan_glow", shade=False)
    add("control vent bank", (9.5, 8.2, 0.35), (12.6, 16.4, 1.25), "control", face_materials={"north": "vent"})
    for index, y0 in enumerate((9.3, 12.1, 14.9)):
        add(f"control vent slot {index}", (10.1, y0, 0), (12, y0 + 0.65, 0.45), "frame_edge")
    add("control bottom service slot", (6, 6.7, 0.2), (12.5, 7.55, 1.15), "vent")

    # Visible end-cap electronics on the west face.
    add("left end armor panel", (0, 6, 4), (0.8, 22, 12), "armor_edge")
    add("left end inset", (0, 8, 5.5), (0.45, 20, 10.5), "control")
    add("left end cyan strip", (0, 10, 7.1), (0.2, 18, 8.7), "cyan_glow", shade=False)

    # Central recessed energy chamber and its projecting cage.
    add("core cavity back", (18, 5, 2.7), (30, 20, 4.3), "frame_edge")
    add("core cavity glass", (20, 7, 2.15), (28, 18, 2.8), "glass")
    add("core luminous block", (22, 9, 0.55), (26, 16, 2.2), "cyan_core", shade=False)
    add("core outer left upright", (17.5, 6, 0.5), (20, 20, 2.6), "frame_edge")
    add("core outer right upright", (28, 6, 0.5), (30.5, 20, 2.6), "frame_edge")
    add("core outer upper rail", (20, 18, 0.5), (28, 20.5, 2.6), "frame_edge")
    add("core outer lower rail", (20, 5.5, 0.5), (28, 8, 2.6), "frame_edge")
    add("core cage left", (20.5, 7.8, 0.1), (22, 17.2, 1.1), "armor_edge")
    add("core cage right", (26, 7.8, 0.1), (27.5, 17.2, 1.1), "armor_edge")
    add("core cage top", (20.5, 16, 0.1), (27.5, 17.4, 1.1), "armor_edge")
    add("core cage bottom", (20.5, 7.5, 0.1), (27.5, 9, 1.1), "armor_edge")
    add("core cyan left slit", (16.9, 8, 0), (17.8, 18, 0.65), "cyan_glow", shade=False)
    add("core cyan right slit", (30.2, 8, 0), (31.1, 18, 0.65), "cyan_glow", shade=False)
    add("core upper armor hood", (18, 20, 0), (30, 23, 2.7), "armor")
    add("core upper armor step", (20, 23, 0.8), (28, 25, 3), "armor_edge")
    add("core lower armor sill", (18, 3, 0), (30, 6, 2.2), "armor")

    # Right radiator: a deep vent bed, cyan channels, and separate projecting fins.
    add("radiator vent bed", (34, 5, 2.4), (46, 23, 4), "vent")
    for index, x0 in enumerate((34.5, 36.5, 38.5, 40.5, 42.5, 44.5)):
        add(f"radiator fin {index}", (x0, 5.8, 0), (x0 + 1.1, 22.2, 2.45), "frame_edge")
    for index, x0 in enumerate((35.75, 37.75, 39.75, 41.75, 43.75)):
        add(f"radiator cyan channel {index}", (x0, 7.5, 0.35), (x0 + 0.65, 20, 1.2), "cyan_glow", shade=False)
    add("radiator upper armor", (33, 22, 0), (46.5, 25, 2.2), "armor")
    add("radiator lower armor", (33, 3, 0), (46.5, 6, 2.2), "armor_edge")
    add("radiator left armor", (32.5, 6, 0), (34.5, 22, 2.2), "armor_edge")
    add("radiator right armor", (45, 6, 0), (47, 22, 2.2), "armor")

    # East output cap is visible in the isometric preview and reaches the x=48 envelope.
    add("east output recess", (46.4, 7, 4), (48, 21, 12), "frame_edge")
    add("east output armor upper", (47.1, 19, 4.5), (48, 22, 11.5), "armor_edge")
    add("east output armor lower", (47.1, 5, 4.5), (48, 8, 11.5), "armor_edge")
    add("east output socket", (47.35, 10, 6), (48, 18, 10), "glass")
    add("east output core", (47.7, 12, 7), (48, 16, 9), "cyan_glow", shade=False)

    # Top coupler. Its cap reaches y=32, giving an exact two-block height.
    add("coupler base", (18, 26, 4), (30, 28, 12), "frame_edge")
    add("coupler main", (20, 28, 5), (28, 32, 11), "frame")
    add("coupler left rib", (19.5, 28, 4.5), (21.2, 32, 11.5), "frame_edge")
    add("coupler right rib", (26.8, 28, 4.5), (28.5, 32, 11.5), "frame_edge")
    add("coupler top cap", (20, 31.5, 4.5), (28, 32, 11.5), "frame_edge")
    add("coupler cyan socket", (22, 29, 4.1), (26, 31.5, 5.05), "cyan_glow", shade=False)
    add("coupler cyan core", (23, 29.5, 3.7), (25, 31, 4.2), "cyan_core", shade=False)

    # The second depth row is a true machinery bay, not a mirrored front facade.
    # A continuous dark skeleton ties both rows together while keeping the three
    # rear service zones readable from the south and east sides.
    add("rear deck core", (0, 0, 16), (48, 2, 29), "frame")
    add("rear deck edge", (0, 0, 29), (48, 3, 32), "frame_edge")
    add("depth seam lower beam", (0, 2, 14), (48, 4, 18), "frame_edge")
    add("rear crown crossbeam", (0, 26, 28.5), (48, 29, 32), "frame_edge")
    for index, (x0, x1) in enumerate(((0, 2), (15, 17), (31, 33), (46, 48))):
        add(f"rear depth frame post {index}", (x0, 2, 16), (x1, 28, 31), "frame_edge")
        add(f"rear roof runner {index}", (x0, 27, 15), (x1, 30, 31), "frame_edge")
    for index, (x0, x1) in enumerate(((0, 3), (14.5, 17.5), (30.5, 33.5), (45, 48))):
        add(f"rear projecting foot {index}", (x0, 0, 27.5), (x1, 4, 32), "frame_edge")

    # Rear-left service bay: closed machinery, a recessed maintenance hatch,
    # and diagonal armor framing replace the front row's control interface.
    add("rear left machinery shell", (2, 4, 17), (15, 24, 29.5), "frame")
    add("rear left roof armor", (2, 24, 17), (15, 27, 29), "armor")
    add("rear left roof inset", (4, 27, 19), (13, 29, 27), "armor_edge")
    add("rear left west armor", (0, 6, 18), (1.2, 23, 29), "armor_edge")
    add("rear left west service strip", (0, 9, 20), (0.45, 20, 27), "control")
    add("maintenance hatch recess", (3, 6, 29), (14, 22, 31), "frame_edge")
    add("maintenance hatch", (4, 7, 30.5), (13, 21, 32), "armor")
    add("maintenance hatch inner", (5, 9, 31.35), (12, 19, 32), "control")
    add("maintenance hatch top clamp", (4, 20, 30), (13, 23, 32), "armor_edge")
    add("maintenance hatch bottom clamp", (4, 4, 30), (13, 7, 32), "armor_edge")
    for index, y0 in enumerate((10, 13, 16)):
        add(f"maintenance vent slot {index}", (6, y0, 31.65), (11, y0 + 0.8, 32), "vent")
    add("maintenance status lamp", (4.5, 17, 31.7), (5.4, 19.2, 32), "cyan_glow", shade=False)

    # Central rear energy chamber is a sealed containment shell. The restrained
    # flow indicators and dorsal coupling base communicate stored energy without
    # cloning the luminous front core.
    add("energy bay rear shell", (17, 4, 17), (31, 24, 29.5), "frame")
    add("energy bay left shutter", (17, 7, 19), (20, 22, 28), "armor_edge")
    add("energy bay right shutter", (28, 7, 19), (31, 22, 28), "armor_edge")
    add("energy bay roof armor", (17, 23, 17), (31, 27, 29), "armor")
    add("energy bay lower cradle", (18, 3, 18), (30, 7, 29), "armor_edge")
    add("energy bay inner casing", (20, 7, 19), (28, 22, 28), "control")
    add("energy bay rear bulkhead", (18, 6, 28), (30, 23, 30.5), "frame_edge")
    add("energy bay service plate", (20, 8, 30), (28, 20, 32), "armor_edge")
    add("energy bay service inset", (21, 10, 31.35), (27, 18, 32), "control")
    add("energy bay flow slit left", (21, 11, 31.7), (22, 17, 32), "cyan_glow", shade=False)
    add("energy bay flow slit right", (26, 11, 31.7), (27, 17, 32), "cyan_glow", shade=False)
    add("rear coupler foundation", (18, 26, 18), (30, 29, 30), "frame_edge")
    add("rear coupler pedestal", (20, 28, 20), (28, 31, 28), "frame")
    add("rear coupler left lock", (19, 28, 19), (21, 32, 29), "frame_edge")
    add("rear coupler right lock", (27, 28, 19), (29, 32, 29), "frame_edge")
    add("rear coupler bridge", (21, 30, 20), (27, 32, 28), "armor_edge")
    add("dorsal energy conduit casing", (21.5, 27, 11), (26.5, 30, 22), "frame_edge")
    add("dorsal energy conduit", (23, 28, 11.5), (25, 29, 22.5), "cyan_glow", shade=False)

    # Rear-right thermal bay: horizontal south-facing fins, deep exhaust ducts,
    # and longitudinal guide channels form a different rhythm from the front fins.
    add("rear thermal shell", (33, 4, 17), (46, 24, 29), "frame")
    add("rear thermal roof armor", (33, 24, 17), (46, 28, 29), "armor")
    add("rear thermal lower cradle", (33, 3, 18), (46, 7, 29), "armor_edge")
    add("rear thermal vent bed", (34, 5, 28.5), (46, 23, 31), "vent")
    for index, y0 in enumerate((6, 8.5, 11, 13.5, 16, 18.5, 21)):
        add(f"rear horizontal cooling fin {index}", (34.5, y0, 30), (45.5, y0 + 1.15, 32), "frame_edge")
    add("rear thermal upper surround", (33, 22, 29), (47, 25, 32), "armor")
    add("rear thermal lower surround", (33, 3, 29), (47, 6, 32), "armor_edge")
    add("rear thermal left surround", (32.5, 6, 29), (35, 22, 32), "armor_edge")
    add("rear thermal right surround", (45, 6, 29), (47.5, 22, 32), "armor")
    add("east rear exhaust recess", (46, 7, 18), (48, 21, 29), "frame_edge")
    add("east rear exhaust grille", (47.2, 9, 20), (48, 19, 27), "vent")
    for index, z0 in enumerate((18.5, 22.5, 26.5)):
        add(f"thermal guide casing {index}", (34, 17.5 + index, z0), (45, 20 + index, z0 + 1.8), "frame_edge")
        add(f"thermal energy guide {index}", (35, 18.35 + index, z0 + 0.4), (44, 18.9 + index, z0 + 1.4), "cyan_glow", shade=False)

    # Rotated local plates provide real sloped geometry while staying inside each block.
    add_local(0, 0, 0, "control lower left brace", (2, 2, 0), (6.5, 3.6, 2), "armor_edge",
              rotation={"origin": [4.5, 4, 1], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(0, 0, 0, "control lower right brace", (10, 2, 0), (14.5, 3.6, 2), "armor_edge",
              rotation={"origin": [12, 4, 1], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(1, 0, 0, "core lower left chevron", (1.3, 1.2, 0), (6.2, 2.8, 2), "armor",
              rotation={"origin": [4, 3.7, 1], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(1, 0, 0, "core lower right chevron", (9.8, 1.2, 0), (14.7, 2.8, 2), "armor",
              rotation={"origin": [12, 3.7, 1], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(1, 0, 1, "core hood left bevel", (1.3, 4.4, 0), (6, 6.2, 2), "armor_edge",
              rotation={"origin": [4, 5.2, 1], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(1, 0, 1, "core hood right bevel", (10, 4.4, 0), (14.7, 6.2, 2), "armor_edge",
              rotation={"origin": [12, 5.2, 1], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(2, 0, 0, "radiator lower left brace", (1.2, 1.4, 0), (6, 3.1, 2), "armor_edge",
              rotation={"origin": [3.8, 4, 1], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(2, 0, 0, "radiator lower right brace", (10, 1.4, 0), (14.8, 3.1, 2), "armor_edge",
              rotation={"origin": [12.2, 4, 1], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(2, 0, 1, "radiator upper left brace", (1.2, 5, 0), (6, 6.7, 2), "armor",
              rotation={"origin": [3.8, 6, 1], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(2, 0, 1, "radiator upper right brace", (10, 5, 0), (14.8, 6.7, 2), "armor",
              rotation={"origin": [12.2, 6, 1], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(0, 1, 0, "maintenance lower left brace", (2, 2, 14), (6.5, 3.6, 16), "armor_edge",
              rotation={"origin": [4.5, 4, 15], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(0, 1, 0, "maintenance lower right brace", (9.5, 2, 14), (14, 3.6, 16), "armor_edge",
              rotation={"origin": [11.5, 4, 15], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(0, 1, 1, "maintenance upper left clamp", (2, 4.5, 14), (6.5, 6.2, 16), "armor",
              rotation={"origin": [4.5, 5.5, 15], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(0, 1, 1, "maintenance upper right clamp", (9.5, 4.5, 14), (14, 6.2, 16), "armor",
              rotation={"origin": [11.5, 5.5, 15], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(1, 1, 0, "energy cradle left brace", (1.2, 1.4, 13.8), (6, 3.1, 16), "armor_edge",
              rotation={"origin": [3.8, 4, 14.9], "axis": "z", "angle": -22.5, "rescale": False})
    add_local(1, 1, 0, "energy cradle right brace", (10, 1.4, 13.8), (14.8, 3.1, 16), "armor_edge",
              rotation={"origin": [12.2, 4, 14.9], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(2, 1, 1, "thermal upper left brace", (1.2, 5, 14), (6, 6.7, 16), "armor",
              rotation={"origin": [3.8, 6, 15], "axis": "z", "angle": 22.5, "rescale": False})
    add_local(2, 1, 1, "thermal upper right brace", (10, 5, 14), (14.8, 6.7, 16), "armor",
              rotation={"origin": [12.2, 6, 15], "axis": "z", "angle": -22.5, "rescale": False})


def split_geometry():
    cells = {(x, z, y): [] for x in range(3) for z in range(2) for y in range(2)}
    for source in GLOBAL:
        for part_x in range(3):
            for part_z in range(2):
                for part_y in range(2):
                    origin = (part_x * 16, part_y * 16, part_z * 16)
                    lower = [max(source["from"][i], origin[i]) for i in range(3)]
                    upper = [min(source["to"][i], origin[i] + 16) for i in range(3)]
                    if all(lower[i] < upper[i] for i in range(3)):
                        piece = dict(source)
                        piece["from"] = [lower[i] - origin[i] for i in range(3)]
                        piece["to"] = [upper[i] - origin[i] for i in range(3)]
                        cells[(part_x, part_z, part_y)].append(piece)
    for key, elements in LOCAL.items():
        cells[key].extend(elements)
    return cells


def clean_number(value):
    rounded = round(value)
    return rounded if abs(value - rounded) < 1e-8 else round(value, 4)


def model_element(source):
    output = {
        "from": [clean_number(value) for value in source["from"]],
        "to": [clean_number(value) for value in source["to"]],
        "faces": {
            face: {"texture": f"#{source['face_materials'].get(face, source['material'])}"}
            for face in FACES
        },
    }
    if not source["shade"]:
        output["shade"] = False
    if source["rotation"]:
        rotation = dict(source["rotation"])
        rotation["origin"] = [clean_number(value) for value in rotation["origin"]]
        output["rotation"] = rotation
    return output


def write_models(cells):
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for old_path in MODEL_DIR.glob("part_x*_y*.json"):
        old_path.unlink()
    texture_map = {
        material: f"miningdim:block/generator/future/{material}"
        for material in MATERIALS
    }
    texture_map["particle"] = "miningdim:block/generator/future/frame"
    paths = []
    for part_x in range(3):
        for part_z in range(2):
            for part_y in range(2):
                payload = {
                    "parent": "minecraft:block/block",
                    "ambientocclusion": True,
                    "textures": texture_map,
                    "elements": [model_element(item) for item in cells[(part_x, part_z, part_y)]],
                }
                path = MODEL_DIR / f"part_x{part_x}_z{part_z}_y{part_y}.json"
                path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
                paths.append(path)
    return paths


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def write_png(path, width, height, pixels):
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        row_start = y * width * 4
        rows.extend(pixels[row_start:row_start + width * 4])
    signature = b"\x89PNG\r\n\x1a\n"
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.write_bytes(signature + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + png_chunk(b"IEND", b""))


def clamp(value):
    return max(0, min(255, int(round(value))))


def make_texture(material):
    base = MATERIALS[material]
    pixels = bytearray()
    for y in range(16):
        for x in range(16):
            noise = ((x * 13 + y * 7 + x * y * 3) % 7) - 3
            color = [clamp(channel + noise) for channel in base]
            if material == "frame":
                if x in (0, 15) or y in (0, 15):
                    color = [clamp(channel - 12) for channel in base]
                if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
                    color = [83, 91, 98]
            elif material == "frame_edge":
                if x in (1, 14) or y in (1, 14):
                    color = [32, 39, 47]
                if (x + y) % 8 == 0:
                    color = [45, 52, 60]
            elif material == "armor":
                if x in (0, 15) or y in (0, 15):
                    color = [164, 174, 180]
                elif (x == 8 and 3 <= y <= 12) or (y == 8 and 3 <= x <= 12):
                    color = [196, 203, 207]
            elif material == "armor_edge":
                if x == y or x + y == 15:
                    color = [174, 184, 189]
                elif x in (0, 15) or y in (0, 15):
                    color = [102, 114, 124]
            elif material == "cyan_glow":
                distance = min(x, y, 15 - x, 15 - y)
                color = [0, clamp(175 + distance * 9), clamp(215 + distance * 8)]
                if 5 <= x <= 10 and 4 <= y <= 11:
                    color = [72, 241, 255]
            elif material == "cyan_core":
                ring = max(abs(x - 7.5), abs(y - 7.5))
                color = [clamp(66 - ring * 5), clamp(245 - ring * 6), 255]
                if int(ring) in (3, 6):
                    color = [0, 172, 226]
            elif material == "glass":
                if x in (0, 1, 14, 15) or y in (0, 1, 14, 15):
                    color = [4, 42, 58]
                elif x in (5, 10) or y in (5, 10):
                    color = [11, 115, 143]
                if 6 <= x <= 9 and 6 <= y <= 9:
                    color = [23, 170, 198]
            elif material == "vent":
                color = [15, 20, 25] if x % 4 in (0, 1) else [43, 51, 58]
                if y in (0, 15):
                    color = [62, 69, 75]
            elif material == "control":
                color = [31, 38, 44]
                if x in (1, 14) or y in (1, 14):
                    color = [82, 91, 97]
                if 4 <= x <= 11 and y in (4, 8, 12):
                    color = [6, 151, 190]
            pixels.extend((*color, 255))
    return pixels


def write_textures():
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    paths = []
    for material in MATERIALS:
        path = TEXTURE_DIR / f"{material}.png"
        write_png(path, 16, 16, make_texture(material))
        paths.append(path)
    return paths


def vector_add(a, b):
    return tuple(a[i] + b[i] for i in range(3))


def vector_sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def vector_scale(vector, scale):
    return tuple(value * scale for value in vector)


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def normalize(vector):
    length = math.sqrt(dot(vector, vector))
    return tuple(value / length for value in vector)


def rotate_point(point, rotation):
    if not rotation:
        return tuple(point)
    origin = rotation["origin"]
    angle = math.radians(rotation["angle"])
    cosine, sine = math.cos(angle), math.sin(angle)
    x, y, z = (point[i] - origin[i] for i in range(3))
    if rotation["axis"] == "z":
        rotated = (x * cosine - y * sine, x * sine + y * cosine, z)
    elif rotation["axis"] == "y":
        rotated = (x * cosine + z * sine, y, -x * sine + z * cosine)
    else:
        rotated = (x, y * cosine - z * sine, y * sine + z * cosine)
    return tuple(rotated[i] + origin[i] for i in range(3))


def box_vertices(source, offset):
    x0, y0, z0 = source["from"]
    x1, y1, z1 = source["to"]
    vertices = [
        (x0, y0, z0), (x1, y0, z0), (x0, y1, z0), (x1, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x0, y1, z1), (x1, y1, z1),
    ]
    return [vector_add(rotate_point(vertex, source["rotation"]), offset) for vertex in vertices]


def blend_pixel(canvas, width, height, x, y, color):
    if not (0 <= x < width and 0 <= y < height):
        return
    index = (y * width + x) * 4
    alpha = color[3] / 255.0
    inverse = 1.0 - alpha
    canvas[index] = clamp(color[0] * alpha + canvas[index] * inverse)
    canvas[index + 1] = clamp(color[1] * alpha + canvas[index + 1] * inverse)
    canvas[index + 2] = clamp(color[2] * alpha + canvas[index + 2] * inverse)
    canvas[index + 3] = 255


def fill_polygon(canvas, width, height, points, color):
    minimum_y = max(0, int(math.floor(min(point[1] for point in points))))
    maximum_y = min(height - 1, int(math.ceil(max(point[1] for point in points))))
    for y in range(minimum_y, maximum_y + 1):
        scan_y = y + 0.5
        intersections = []
        for index, first in enumerate(points):
            second = points[(index + 1) % len(points)]
            if (first[1] <= scan_y < second[1]) or (second[1] <= scan_y < first[1]):
                ratio = (scan_y - first[1]) / (second[1] - first[1])
                intersections.append(first[0] + ratio * (second[0] - first[0]))
        intersections.sort()
        for index in range(0, len(intersections) - 1, 2):
            start = max(0, int(math.ceil(intersections[index] - 0.5)))
            end = min(width - 1, int(math.floor(intersections[index + 1] - 0.5)))
            for x in range(start, end + 1):
                blend_pixel(canvas, width, height, x, y, color)


def draw_line(canvas, width, height, first, second, color, thickness=1):
    x0, y0 = (int(round(value)) for value in first)
    x1, y1 = (int(round(value)) for value in second)
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    step_x, step_y = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    error = dx + dy
    while True:
        radius = max(0, thickness // 2)
        for py in range(y0 - radius, y0 + radius + 1):
            for px in range(x0 - radius, x0 + radius + 1):
                blend_pixel(canvas, width, height, px, py, color)
        if x0 == x1 and y0 == y1:
            break
        doubled = 2 * error
        if doubled >= dy:
            error += dy
            x0 += step_x
        if doubled <= dx:
            error += dx
            y0 += step_y


def render_preview(cells):
    final_width, final_height, supersample = 1200, 720, 2
    width, height = final_width * supersample, final_height * supersample
    canvas = bytearray(width * height * 4)
    for y in range(height):
        blend = y / max(1, height - 1)
        background = (clamp(232 - 36 * blend), clamp(236 - 39 * blend), clamp(239 - 40 * blend), 255)
        row = bytes(background) * width
        canvas[y * width * 4:(y + 1) * width * 4] = row

    camera = (80, 67, -94)
    target = (24, 14, 16)
    forward = normalize(vector_sub(target, camera))
    right = normalize(cross(forward, (0, 1, 0)))
    camera_up = cross(right, forward)
    scale = 12.5 * supersample
    center_x, center_y = 600 * supersample, 370 * supersample

    def project(point):
        relative = vector_sub(point, target)
        return (
            center_x - dot(relative, right) * scale,
            center_y - dot(relative, camera_up) * scale,
            dot(vector_sub(point, camera), forward),
        )

    ground = [project(point)[:2] for point in ((-2, 0, -2), (52, 0, -2), (52, 0, 35), (-2, 0, 35))]
    fill_polygon(canvas, width, height, ground, (38, 47, 55, 28))

    face_indices = {
        "north": (0, 1, 3, 2), "south": (5, 4, 6, 7),
        "west": (4, 0, 2, 6), "east": (1, 5, 7, 3),
        "down": (4, 5, 1, 0), "up": (2, 3, 7, 6),
    }
    face_normals = {
        "north": (0, 0, -1), "south": (0, 0, 1),
        "west": (-1, 0, 0), "east": (1, 0, 0),
        "down": (0, -1, 0), "up": (0, 1, 0),
    }
    light = normalize((-0.45, 0.9, -0.65))
    polygons = []
    for (part_x, part_z, part_y), items in cells.items():
        offset = (part_x * 16, part_y * 16, part_z * 16)
        for source in items:
            vertices = box_vertices(source, offset)
            for face, indices in face_indices.items():
                points_3d = [vertices[index] for index in indices]
                normal_end = rotate_point(face_normals[face], {**source["rotation"], "origin": [0, 0, 0]} if source["rotation"] else None)
                normal = normalize(normal_end)
                center = tuple(sum(point[axis] for point in points_3d) / 4 for axis in range(3))
                if dot(normal, vector_sub(camera, center)) <= 0.01:
                    continue
                projected = [project(point) for point in points_3d]
                depth = sum(point[2] for point in projected) / 4
                material = source["face_materials"].get(face, source["material"])
                brightness = 0.62 + 0.35 * max(0.0, dot(normal, light))
                if material in ("cyan_glow", "cyan_core"):
                    brightness = max(0.94, brightness)
                base = MATERIALS[material]
                fill = tuple(clamp(channel * brightness) for channel in base) + (255,)
                polygons.append((depth, [point[:2] for point in projected], fill, material))
    polygons.sort(key=lambda item: item[0], reverse=True)
    for _, points, fill, material in polygons:
        fill_polygon(canvas, width, height, points, fill)
        edge = (3, 10, 14, 205) if material not in ("cyan_glow", "cyan_core") else (91, 244, 255, 220)
        for index, first in enumerate(points):
            draw_line(canvas, width, height, first, points[(index + 1) % len(points)], edge, supersample)

    downsampled = bytearray(final_width * final_height * 4)
    for y in range(final_height):
        for x in range(final_width):
            samples = []
            for sy in range(supersample):
                for sx in range(supersample):
                    index = (((y * supersample + sy) * width) + x * supersample + sx) * 4
                    samples.append(canvas[index:index + 4])
            output = (y * final_width + x) * 4
            for channel in range(4):
                downsampled[output + channel] = sum(sample[channel] for sample in samples) // len(samples)
    write_png(PREVIEW_PATH, final_width, final_height, downsampled)


def rotated_bounds(source):
    vertices = box_vertices(source, (0, 0, 0))
    return tuple(min(point[axis] for point in vertices) for axis in range(3)), tuple(max(point[axis] for point in vertices) for axis in range(3))


def validate(model_paths, texture_paths, cells):
    expected_models = {
        f"part_x{x}_z{z}_y{y}.json"
        for x in range(3)
        for z in range(2)
        for y in range(2)
    }
    if {path.name for path in model_paths} != expected_models:
        raise AssertionError("The future generator must contain exactly twelve named model parts")
    actual_models = {path.name for path in MODEL_DIR.glob("part_x*.json")}
    if actual_models != expected_models:
        raise AssertionError(f"Stale or missing future model files: {sorted(actual_models ^ expected_models)}")
    texture_set = {path.resolve() for path in texture_paths}
    combined_min = [math.inf, math.inf, math.inf]
    combined_max = [-math.inf, -math.inf, -math.inf]
    total_elements = 0
    for path in model_paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        part_x = int(path.stem.split("_x")[1].split("_")[0])
        part_z = int(path.stem.split("_z")[1].split("_")[0])
        part_y = int(path.stem.split("_y")[1])
        source_items = cells[(part_x, part_z, part_y)]
        if len(data["elements"]) != len(source_items):
            raise AssertionError(f"Generated element count mismatch in {path.name}")
        total_elements += len(source_items)
        for source, output in zip(source_items, data["elements"]):
            for axis in range(3):
                if not 0 <= output["from"][axis] < output["to"][axis] <= 16:
                    raise AssertionError(f"Raw element bounds escape 0..16 in {path.name}: {output}")
            local_min, local_max = rotated_bounds(source)
            for axis in range(3):
                if local_min[axis] < -1e-6 or local_max[axis] > 16 + 1e-6:
                    raise AssertionError(f"Rotated element escapes 0..16 in {path.name}: {source['name']}")
                offset = (part_x * 16, part_y * 16, part_z * 16)[axis]
                combined_min[axis] = min(combined_min[axis], local_min[axis] + offset)
                combined_max[axis] = max(combined_max[axis], local_max[axis] + offset)
            for face in output["faces"].values():
                texture_key = face["texture"].removeprefix("#")
                if texture_key not in data["textures"]:
                    raise AssertionError(f"Unknown texture key {texture_key} in {path.name}")
                texture_ref = data["textures"][texture_key]
                relative = texture_ref.removeprefix("miningdim:block/") + ".png"
                texture_path = (ROOT / "src/main/resources/assets/miningdim/textures/block" / relative).resolve()
                if texture_path not in texture_set or not texture_path.is_file():
                    raise AssertionError(f"Missing texture {texture_ref} referenced by {path.name}")
    expected_min = (0, 0, 0)
    expected_max = (48, 32, 32)
    if any(abs(combined_min[i] - expected_min[i]) > 1e-6 for i in range(3)):
        raise AssertionError(f"Combined minimum bounds are {combined_min}, expected {expected_min}")
    if any(abs(combined_max[i] - expected_max[i]) > 1e-6 for i in range(3)):
        raise AssertionError(f"Combined maximum bounds are {combined_max}, expected {expected_max}")
    if not PREVIEW_PATH.is_file():
        raise AssertionError("Preview was not generated")
    return total_elements


def main():
    build_geometry()
    cells = split_geometry()
    texture_paths = write_textures()
    model_paths = write_models(cells)
    render_preview(cells)
    total_elements = validate(model_paths, texture_paths, cells)
    print(f"Generated models: {len(model_paths)}")
    print(f"Generated textures: {len(texture_paths)}")
    for part_x in range(3):
        for part_z in range(2):
            for part_y in range(2):
                print(f"part_x{part_x}_z{part_z}_y{part_y}: {len(cells[(part_x, part_z, part_y)])} elements")
    print(f"Geometry elements across twelve parts: {total_elements}")
    print("Combined bounds: 48 x 32 x 32 (X x Z x Y)")
    print(f"Preview: {PREVIEW_PATH}")
    print("Validation: PASS")


if __name__ == "__main__":
    main()
