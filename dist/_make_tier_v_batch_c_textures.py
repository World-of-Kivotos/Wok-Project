from __future__ import annotations

from dataclasses import dataclass, replace
from hashlib import sha256
from math import ceil, floor
from pathlib import Path
import re

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"
SIZE = 128
RGBA = tuple[int, int, int, int]


@dataclass(frozen=True)
class Face:
    x0: float
    y0: float
    x1: float
    y1: float


@dataclass(frozen=True)
class Cube:
    name: str
    width: float
    height: float
    depth: float
    material: str
    u: int = 0
    v: int = 0


@dataclass(frozen=True)
class ModelSpec:
    name: str
    java_file: str
    texture_file: str
    cubes: tuple[Cube, ...]
    palette: dict[str, RGBA]
    camouflage: frozenset[str]


def c(name: str, width: float, height: float, depth: float, material: str) -> Cube:
    return Cube(name, width, height, depth, material)


RAW_MODELS = {
    "b6b45_medic": (
        c("front_upper", 7.20, 3.00, .48, "flora"),
        c("front_mid", 7.80, 4.15, .56, "flora"),
        c("front_lower", 8.00, 3.50, .58, "flora"),
        c("rear_upper", 7.20, 3.00, .48, "flora"),
        c("rear_mid", 7.80, 4.15, .56, "flora"),
        c("rear_lower", 8.00, 3.50, .58, "flora"),
        c("left_side", .42, 8.50, 4.00, "dark_fabric"),
        c("right_side", .42, 8.50, 4.00, "dark_fabric"),
        c("collar_front_left", 3.80, 2.15, .58, "collar"),
        c("collar_front_right", 3.80, 2.15, .58, "collar"),
        c("collar_rear", 8.20, 2.15, .58, "collar"),
        c("collar_left", .50, 2.10, 7.44, "collar"),
        c("collar_right", .50, 2.10, 7.44, "collar"),
        c("front_left_yoke", 3.20, 1.20, .32, "reinforcement"),
        c("front_right_yoke", 3.20, 1.20, .32, "reinforcement"),
        c("rear_left_yoke", 3.20, 1.20, .32, "reinforcement"),
        c("rear_right_yoke", 3.20, 1.20, .32, "reinforcement"),
        c("front_waist", 7.80, 1.10, .30, "webbing"),
        c("rear_waist", 7.80, 1.10, .30, "webbing"),
        c("molle_top", 7.00, .22, .18, "webbing"),
        c("molle_middle", 7.00, .22, .18, "webbing"),
        c("molle_lower", 7.00, .22, .18, "webbing"),
        c("medical_bag", 2.90, 5.10, 1.15, "pouch"),
        c("medical_lid", 3.00, .72, 1.25, "pouch_lid"),
        c("medical_zip", .18, 3.50, .18, "zipper"),
        c("medical_cross_h", 1.55, .35, .16, "medical_red"),
        c("medical_cross_v", .35, 1.55, .16, "medical_red"),
        c("left_long_outer", 1.20, 4.50, .92, "pouch"),
        c("left_long_outer_lid", 1.26, .70, 1.05, "pouch_lid"),
        c("left_long_outer_pull", .22, 3.00, .16, "webbing"),
        c("left_long_inner", 1.20, 4.50, .92, "pouch"),
        c("left_long_inner_lid", 1.26, .70, 1.05, "pouch_lid"),
        c("left_long_inner_pull", .22, 3.00, .16, "webbing"),
        c("lower_left", 1.22, 2.60, .88, "pouch"),
        c("lower_left_lid", 1.28, .62, .98, "pouch_lid"),
        c("lower_left_pull", .18, 1.50, .15, "webbing"),
        c("lower_right", 1.22, 2.60, .88, "pouch"),
        c("lower_right_lid", 1.28, .62, .98, "pouch_lid"),
        c("lower_right_pull", .18, 1.50, .15, "webbing"),
        c("right_module", 1.25, 5.70, .78, "module"),
        c("right_module_lid", 1.29, .65, .88, "pouch_lid"),
        c("right_module_rail", .18, 4.20, .15, "webbing"),
        c("right_antenna", .16, 2.60, .16, "antenna"),
        c("right_shoulder_top", 2.30, .55, 4.20, "shoulder"),
        c("right_shoulder_outer", .45, 1.55, 4.00, "shoulder"),
        c("left_shoulder_top", 2.30, .55, 4.20, "shoulder"),
        c("left_shoulder_outer", .45, 1.55, 4.00, "shoulder"),
    ),
    "gzhel_k": (
        c("front_shell", 7.80, 10.40, .62, "shell"),
        c("rear_shell", 7.80, 10.40, .62, "shell"),
        c("left_side", .42, 8.20, 4.00, "side"),
        c("right_side", .42, 8.20, 4.00, "side"),
        c("front_left_yoke", 3.45, 1.25, .32, "reinforcement"),
        c("front_right_yoke", 3.45, 1.25, .32, "reinforcement"),
        c("rear_left_yoke", 3.45, 1.25, .32, "reinforcement"),
        c("rear_right_yoke", 3.45, 1.25, .32, "reinforcement"),
        c("collar_front_left", 3.75, 2.15, .65, "soft_collar"),
        c("collar_front_right", 3.75, 2.15, .65, "soft_collar"),
        c("collar_rear", 8.10, 2.15, .65, "soft_collar"),
        c("collar_left", .50, 2.15, 7.20, "soft_collar"),
        c("collar_right", .50, 2.15, 7.20, "soft_collar"),
        c("rim_front_left", 3.65, .48, .72, "collar_rim"),
        c("rim_front_right", 3.65, .48, .72, "collar_rim"),
        c("rim_rear", 8.00, .48, .72, "collar_rim"),
        c("rim_left", .56, .48, 7.10, "collar_rim"),
        c("rim_right", .56, .48, 7.10, "collar_rim"),
        *(c(f"molle_{row}_{column}", .92, .28, .18, "molle")
          for row in range(3) for column in range(6)),
        c("front_belt", 7.80, 1.25, .36, "belt"),
        c("rear_belt", 7.80, 1.25, .36, "belt"),
        c("left_belt", .38, 1.25, 4.80, "belt"),
        c("right_belt", .38, 1.25, 4.80, "belt"),
        c("buckle_body", 2.35, 1.05, .42, "buckle"),
        c("buckle_face", 1.10, .72, .20, "buckle_dark"),
        c("left_vertical_tab", .40, 2.10, .24, "webbing"),
        c("right_vertical_tab", .40, 2.10, .24, "webbing"),
        c("lower_hem", 7.60, .45, .30, "webbing"),
        c("front_left_armhole_step", .62, 1.45, .32, "armhole_edge"),
        c("front_right_armhole_step", .62, 1.45, .32, "armhole_edge"),
        c("rear_left_armhole_step", .62, 1.45, .32, "armhole_edge"),
        c("rear_right_armhole_step", .62, 1.45, .32, "armhole_edge"),
    ),
    "gladiator_gray": (
        c("front_upper", 7.00, 3.00, .46, "carrier"),
        c("front_lower", 7.70, 6.90, .54, "carrier"),
        c("rear_upper", 7.00, 3.00, .46, "carrier"),
        c("rear_lower", 7.70, 6.90, .54, "carrier"),
        c("left_side", .40, 7.80, 4.00, "side"),
        c("right_side", .40, 7.80, 4.00, "side"),
        c("front_left_wing", .68, 7.00, .50, "wing"),
        c("front_right_wing", .68, 7.00, .50, "wing"),
        c("rear_left_wing", .68, 7.00, .50, "wing"),
        c("rear_right_wing", .68, 7.00, .50, "wing"),
        c("front_left_yoke", 2.90, 1.20, .30, "reinforcement"),
        c("front_right_yoke", 2.90, 1.20, .30, "reinforcement"),
        c("rear_left_yoke", 2.90, 1.20, .30, "reinforcement"),
        c("rear_right_yoke", 2.90, 1.20, .30, "reinforcement"),
        c("collar_front_left", 3.70, 1.75, .52, "collar"),
        c("collar_front_right", 3.70, 1.75, .52, "collar"),
        c("collar_rear", 8.00, 1.75, .52, "collar"),
        c("collar_left", .46, 1.75, 7.10, "collar"),
        c("collar_right", .46, 1.75, 7.10, "collar"),
        c("rim_front_left", 3.60, .35, .60, "collar_rim"),
        c("rim_front_right", 3.60, .35, .60, "collar_rim"),
        c("rim_rear", 7.90, .35, .60, "collar_rim"),
        c("rim_left", .52, .35, 7.00, "collar_rim"),
        c("rim_right", .52, .35, 7.00, "collar_rim"),
        *(c(f"molle_row_{row}", 6.90, .25, .19, "segmented_molle") for row in range(6)),
        c("front_waist", 7.60, 1.10, .30, "webbing"),
        c("rear_waist", 7.60, 1.10, .30, "webbing"),
        c("left_waist", .36, 1.10, 4.40, "webbing"),
        c("right_waist", .36, 1.10, 4.40, "webbing"),
        c("upper_admin_panel", 2.80, 1.00, .22, "molle"),
    ),
}


def pack(cubes: tuple[Cube, ...]) -> tuple[Cube, ...]:
    """Deterministic shelf packing with a one-pixel gutter between cube nets."""
    packed: list[Cube] = []
    x = y = row_height = 0
    for cube in cubes:
        net_width = ceil(2 * (cube.width + cube.depth))
        net_height = ceil(cube.height + cube.depth)
        if x and x + net_width > SIZE:
            x = 0
            y += row_height + 1
            row_height = 0
        if y + net_height > SIZE:
            raise RuntimeError(f"UV atlas overflow at {cube.name}")
        packed.append(replace(cube, u=x, v=y))
        x += net_width + 1
        row_height = max(row_height, net_height)
    return tuple(packed)


MODELS = {name: pack(cubes) for name, cubes in RAW_MODELS.items()}


SPECS = (
    ModelSpec("6B45 Medic", "B6B45MedicArmorModel.java", "plate_armor_6b45_medic_layer_1.png",
              MODELS["b6b45_medic"], {
                  "flora": (83, 91, 65, 255), "dark_fabric": (47, 56, 43, 255),
                  "collar": (69, 76, 55, 255), "reinforcement": (91, 96, 67, 255),
                  "webbing": (78, 81, 56, 255), "pouch": (79, 87, 61, 255),
                  "pouch_lid": (91, 95, 65, 255), "zipper": (39, 45, 37, 255),
                  "medical_red": (133, 43, 36, 255), "module": (64, 71, 52, 255),
                  "antenna": (29, 34, 31, 255), "shoulder": (76, 84, 59, 255),
              }, frozenset({"flora", "collar", "reinforcement", "pouch", "pouch_lid", "module", "shoulder"})),
    ModelSpec("Gzhel-K", "GzhelKArmorModel.java", "plate_armor_gzhel_k_layer_1.png",
               MODELS["gzhel_k"], {
                   "shell": (151, 158, 160, 255), "side": (103, 115, 119, 255),
                   "reinforcement": (169, 175, 175, 255), "soft_collar": (103, 116, 121, 255),
                   "collar_rim": (124, 136, 140, 255), "molle": (132, 142, 144, 255),
                   "belt": (122, 132, 134, 255), "buckle": (70, 77, 80, 255),
                   "buckle_dark": (43, 48, 50, 255), "webbing": (121, 130, 131, 255),
                   "armhole_edge": (139, 149, 151, 255),
               }, frozenset()),
    ModelSpec("Gladiator-S Gray", "GladiatorSGrayArmorModel.java",
              "plate_armor_gladiator_s_gray_layer_1.png", MODELS["gladiator_gray"], {
                   "carrier": (84, 94, 90, 255), "side": (59, 70, 67, 255),
                   "wing": (67, 79, 75, 255), "reinforcement": (101, 111, 105, 255),
                   "collar": (76, 88, 84, 255), "collar_rim": (94, 105, 100, 255),
                   "molle": (94, 104, 98, 255), "segmented_molle": (98, 109, 102, 255),
                   "webbing": (75, 86, 82, 255),
              }, frozenset()),
)


def cube_faces(cube: Cube) -> dict[str, Face]:
    u, v, width, height, depth = cube.u, cube.v, cube.width, cube.height, cube.depth
    return {
        "down": Face(u + depth, v, u + depth + width, v + depth),
        "up": Face(u + depth + width, v, u + depth + width * 2, v + depth),
        "west": Face(u, v + depth, u + depth, v + depth + height),
        "north": Face(u + depth, v + depth, u + depth + width, v + depth + height),
        "east": Face(u + depth + width, v + depth, u + depth * 2 + width, v + depth + height),
        "south": Face(u + depth * 2 + width, v + depth,
                      u + depth * 2 + width * 2, v + depth + height),
    }


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(value)) % 65521


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def bounds(face: Face) -> tuple[int, int, int, int]:
    return max(0, floor(face.x0)), max(0, floor(face.y0)), min(SIZE, ceil(face.x1)), min(SIZE, ceil(face.y1))


def material_pixel(base: RGBA, x: int, y: int, seed: int, camouflage: bool) -> RGBA:
    value = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    value ^= value >> 13
    weave = ((x + seed) % 4 == 0) - ((y + seed) % 5 == 0)
    delta = int(value % 9) - 4 + weave * 2
    channel_shift = (0, 0, 0)
    if camouflage:
        cell_x = (x + seed % 3) // 3
        cell_y = (y + seed % 2) // 2
        patch = (cell_x * 17 + cell_y * 31 + (cell_x ^ cell_y) * 7 + seed) % 13
        channel_shift = (
            (-23, -20, -15),
            (-13, -8, -10),
            (-3, 4, -6),
            (12, 10, 1),
            (23, 17, 6),
        )[patch % 5]
    offsets = (
        int((value >> 8) % 3) - 1,
        int((value >> 12) % 3) - 1,
        int((value >> 16) % 3) - 1,
    )
    return tuple(
        max(0, min(255, base[index] + delta + offsets[index] + channel_shift[index]))
        for index in range(3)
    ) + (255,)


def paint_face(image: Image.Image, face: Face, base: RGBA, seed: int, camouflage: bool,
               direction: str, material: str) -> None:
    x0, y0, x1, y1 = bounds(face)
    direction_delta = {"up": 9, "down": -10, "north": 2, "south": -4, "west": -7, "east": -3}[direction]
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = shade(material_pixel(base, x, y, seed, camouflage), direction_delta)
    if x1 <= x0 or y1 <= y0:
        return
    draw = ImageDraw.Draw(image)
    outline = shade(base, -18)
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=outline)
    if direction not in {"north", "south"}:
        return
    if material in {"molle", "webbing", "segmented_molle"}:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.point((x, y0), fill=shade(base, 16))
            if y1 - y0 > 2:
                draw.point((x, y1 - 1), fill=shade(base, -24))
        if material == "segmented_molle" and x1 - x0 >= 5:
            for x in range(x0 + 2, x1 - 1, 3):
                draw.line((x, y0, x, y1 - 1), fill=shade(base, -34))
    elif material in {"pouch", "pouch_lid", "module", "soft_collar", "collar", "collar_rim"}:
        if x1 - x0 > 3 and y1 - y0 > 3:
            draw.line((x0 + 1, y0 + 1, x1 - 2, y0 + 1), fill=shade(base, 15))
            draw.line((x0 + 1, y1 - 2, x1 - 2, y1 - 2), fill=shade(base, -24))
    elif material in {"shell", "carrier", "reinforcement", "wing", "armhole_edge"}:
        if x1 - x0 > 4 and y1 - y0 > 3:
            center = x0 + (x1 - x0) // 2
            draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -22))
            draw.line((x0 + 1, y0 + 1, x1 - 2, y0 + 1), fill=shade(base, 13))
    elif material == "buckle":
        draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, 24))


def build(spec: ModelSpec) -> Image.Image:
    background = shade(next(iter(spec.palette.values())), -20)
    image = Image.new("RGBA", (SIZE, SIZE), background)
    for cube in spec.cubes:
        base = spec.palette[cube.material]
        for direction, face in cube_faces(cube).items():
            paint_face(image, face, base, stable_seed(f"{spec.name}:{cube.name}:{direction}"),
                       cube.material in spec.camouflage, direction, cube.material)
    return image


JAVA_BOX = re.compile(
    r"\.texOffs\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\.addBox\("
    r"[^,]+,[^,]+,[^,]+,\s*([0-9.]+)F,\s*([0-9.]+)F,\s*([0-9.]+)F\)", re.DOTALL)


def validate_java(spec: ModelSpec) -> None:
    source = (CLIENT / spec.java_file).read_text(encoding="utf-8")
    actual = [(int(u), int(v), float(width), float(height), float(depth))
              for u, v, width, height, depth in JAVA_BOX.findall(source)]
    expected = [(cube.u, cube.v, cube.width, cube.height, cube.depth) for cube in spec.cubes]
    if actual != expected:
        raise RuntimeError(f"{spec.name}: Java UV/cuboid order differs from generator")


def validate_spec(spec: ModelSpec) -> None:
    if len({cube.name for cube in spec.cubes}) != len(spec.cubes):
        raise RuntimeError(f"{spec.name}: duplicate cube name")
    for cube in spec.cubes:
        if cube.material not in spec.palette:
            raise RuntimeError(f"{spec.name}.{cube.name}: missing material {cube.material}")
        for face in cube_faces(cube).values():
            if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                raise RuntimeError(f"{spec.name}.{cube.name}: UV overflow")
    validate_java(spec)


def main() -> None:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for spec in SPECS:
        validate_spec(spec)
        first = build(spec)
        second = build(spec)
        if first.tobytes() != second.tobytes():
            raise RuntimeError(f"{spec.name}: texture generation is not deterministic")
        output = TEXTURES / spec.texture_file
        first.save(output, format="PNG", optimize=False)
        with Image.open(output) as written:
            colors = written.getcolors(SIZE * SIZE)
            if written.size != (SIZE, SIZE) or written.mode != "RGBA" or written.getchannel("A").getextrema() != (255, 255):
                raise RuntimeError(f"{spec.name}: output must be opaque 128x128 RGBA")
            if colors is None or len(colors) < 64:
                raise RuntimeError(f"{spec.name}: surface detail is too flat")
        print(f"{output.name} cubes={len(spec.cubes)} colors={len(colors)} sha256={sha256(output.read_bytes()).hexdigest().upper()}")
    print("uv=unique alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
