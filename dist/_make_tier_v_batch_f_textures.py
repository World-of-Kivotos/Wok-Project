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


def c(name: str, width: float, height: float, depth: float, material: str) -> Cube:
    return Cube(name, width, height, depth, material)


def base_cubes() -> tuple[Cube, ...]:
    return (
        c("front_upper", 7.20, 3.10, .48, "carrier"),
        c("front_middle", 7.80, 4.00, .56, "carrier"),
        c("front_lower", 8.00, 3.50, .58, "carrier"),
        c("rear_upper", 7.20, 3.10, .48, "carrier"),
        c("rear_middle", 7.80, 4.00, .56, "carrier"),
        c("rear_lower", 8.00, 3.50, .58, "carrier"),
        c("left_side", .42, 8.30, 4.00, "side"),
        c("right_side", .42, 8.30, 4.00, "side"),
        c("front_left_bridge", 3.20, 1.20, .58, "reinforcement"),
        c("front_right_bridge", 3.20, 1.20, .58, "reinforcement"),
        c("rear_left_bridge", 3.20, 1.20, .58, "reinforcement"),
        c("rear_right_bridge", 3.20, 1.20, .58, "reinforcement"),
        c("collar_front_left", 3.05, 1.35, .50, "collar"),
        c("collar_front_right", 3.05, 1.35, .50, "collar"),
        c("collar_rear", 7.40, 1.35, .50, "collar"),
        c("collar_left", .38, 1.30, 6.10, "collar"),
        c("collar_right", .38, 1.30, 6.10, "collar"),
        c("rim_front_left", 2.90, .32, .58, "collar_rim"),
        c("rim_front_right", 2.90, .32, .58, "collar_rim"),
        c("rim_rear", 7.30, .32, .58, "collar_rim"),
        c("rim_left", .45, .30, 6.10, "collar_rim"),
        c("rim_right", .45, .30, 6.10, "collar_rim"),
        *(c(f"molle_row_{index}", 7.10, .24, .18, "molle") for index in range(5)),
        c("front_belt", 7.80, 1.00, .28, "belt"),
        c("rear_belt", 7.80, 1.00, .28, "belt"),
        c("left_belt", .36, 1.00, 4.40, "belt"),
        c("right_belt", .36, 1.00, 4.40, "belt"),
        c("left_chest_tab", 1.00, 1.50, .24, "webbing"),
        c("right_chest_tab", 1.00, 1.50, .24, "webbing"),
        c("left_clasp", .30, .60, .20, "hardware"),
        c("right_clasp", .30, .60, .20, "hardware"),
    )


HIGH_MOBILITY = base_cubes() + (
    c("groin_upper", 5.40, 1.85, .42, "groin"),
    c("groin_mid_upper", 4.80, 1.75, .40, "groin"),
    c("groin_mid_lower", 4.10, 1.65, .38, "groin"),
    c("groin_lower", 3.20, 1.40, .36, "groin"),
    c("groin_tip", 1.80, .90, .34, "groin"),
    c("left_hip", 2.45, 3.80, .30, "hip_liner"),
    c("right_hip", 2.45, 3.80, .30, "hip"),
    c("left_hip_top_trim", 2.45, .32, .16, "hip"),
    c("groin_molle_0", 4.60, .20, .17, "molle"),
    c("groin_molle_1", 4.20, .20, .17, "molle"),
    c("groin_molle_2", 3.80, .20, .17, "molle"),
    c("left_hip_seam", .18, 2.80, .15, "seam"),
    c("right_hip_seam", .18, 2.80, .15, "seam"),
)


FULL_PROTECTION = base_cubes() + (
    c("groin_upper", 5.70, 1.95, .44, "groin"),
    c("groin_mid_upper", 5.10, 1.85, .42, "groin"),
    c("groin_mid_lower", 4.40, 1.70, .40, "groin"),
    c("groin_lower", 3.40, 1.45, .38, "groin"),
    c("groin_tip", 2.00, .95, .36, "groin"),
    c("left_hip", 2.45, 4.10, .30, "hip_liner"),
    c("right_hip", 2.45, 4.10, .30, "hip"),
    c("left_hip_top_trim", 2.45, .32, .16, "hip"),
    c("groin_molle_0", 4.80, .20, .17, "molle"),
    c("groin_molle_1", 4.40, .20, .17, "molle"),
    c("groin_molle_2", 4.00, .20, .17, "molle"),
    c("left_hip_seam", .18, 3.10, .15, "seam"),
    c("right_hip_seam", .18, 3.10, .15, "seam"),
    c("right_shoulder_top", 3.80, .52, 4.40, "shoulder"),
    c("right_shoulder_outer", .42, 3.80, 4.20, "shoulder"),
    c("right_shoulder_front_mid", 4.15, 2.25, .42, "shoulder"),
    c("right_shoulder_rear_mid", 4.15, 2.25, .42, "shoulder"),
    c("right_shoulder_front_lower", 3.55, 1.35, .38, "shoulder"),
    c("right_shoulder_rear_lower", 3.55, 1.35, .38, "shoulder"),
    c("left_shoulder_top", 3.80, .52, 4.40, "shoulder"),
    c("left_shoulder_outer", .42, 3.80, 4.20, "shoulder"),
    c("left_shoulder_front_mid", 4.15, 2.25, .42, "shoulder"),
    c("left_shoulder_rear_mid", 4.15, 2.25, .42, "shoulder"),
    c("left_shoulder_front_lower", 3.55, 1.35, .38, "shoulder"),
    c("left_shoulder_rear_lower", 3.55, 1.35, .38, "shoulder"),
)


ASSAULT = base_cubes() + (
    c("right_shoulder_top", 3.60, .45, 4.30, "shoulder"),
    c("right_shoulder_outer", .40, 2.50, 4.00, "shoulder"),
    c("right_shoulder_front", 3.85, 2.20, .38, "shoulder"),
    c("right_shoulder_rear", 3.85, 2.20, .38, "shoulder"),
    c("left_shoulder_top", 3.60, .45, 4.30, "shoulder"),
    c("left_shoulder_outer", .40, 2.50, 4.00, "shoulder"),
    c("left_shoulder_front", 3.85, 2.20, .38, "shoulder"),
    c("left_shoulder_rear", 3.85, 2.20, .38, "shoulder"),
)


def pack(cubes: tuple[Cube, ...]) -> tuple[Cube, ...]:
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


SPECS = (
    ModelSpec("IOTV Gen4 High Mobility", "IotvGen4HighMobilityArmorModel.java",
              "plate_armor_iotv_gen4_high_mobility_layer_1.png", pack(HIGH_MOBILITY)),
    ModelSpec("IOTV Gen4 Full Protection", "IotvGen4FullProtectionArmorModel.java",
              "plate_armor_iotv_gen4_full_protection_layer_1.png", pack(FULL_PROTECTION)),
    ModelSpec("IOTV Gen4 Assault", "IotvGen4AssaultArmorModel.java",
              "plate_armor_iotv_gen4_assault_layer_1.png", pack(ASSAULT)),
)


PALETTE = {
    "carrier": (113, 105, 73, 255), "side": (75, 78, 57, 255),
    "reinforcement": (128, 118, 81, 255), "collar": (84, 88, 63, 255),
    "collar_rim": (100, 102, 72, 255), "molle": (112, 105, 72, 255),
    "belt": (91, 88, 63, 255), "webbing": (120, 111, 76, 255),
    "hardware": (75, 76, 66, 255), "groin": (116, 107, 74, 255),
    "hip": (104, 98, 69, 255), "hip_liner": (24, 27, 25, 255),
    "seam": (70, 72, 56, 255),
    "shoulder": (108, 102, 72, 255),
}
CAMOUFLAGE = frozenset({"carrier", "reinforcement", "collar", "collar_rim", "molle",
                        "belt", "webbing", "groin", "hip", "shoulder"})
MULTICAM = (
    (183, 168, 116), (145, 132, 88), (111, 112, 73),
    (69, 82, 55), (157, 146, 99), (91, 96, 61),
)


def cube_faces(cube: Cube) -> dict[str, Face]:
    u, v, w, h, d = cube.u, cube.v, cube.width, cube.height, cube.depth
    return {
        "down": Face(u + d, v, u + d + w, v + d),
        "up": Face(u + d + w, v, u + d + w * 2, v + d),
        "west": Face(u, v + d, u + d, v + d + h),
        "north": Face(u + d, v + d, u + d + w, v + d + h),
        "east": Face(u + d + w, v + d, u + d * 2 + w, v + d + h),
        "south": Face(u + d * 2 + w, v + d, u + d * 2 + w * 2, v + d + h),
    }


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(value)) % 65521


def color(base: RGBA, x: int, y: int, seed: int, camouflage: bool) -> RGBA:
    value = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    value ^= value >> 13
    source = base[:3]
    if camouflage:
        macro = ((x // 9) * 17 + (y // 7) * 29 + seed) % len(MULTICAM)
        notch = ((x // 4) * 11 + (y // 5) * 7 + seed // 3) % 5
        patch = (macro + (1 if notch == 0 else 0)) % len(MULTICAM)
        source = tuple((MULTICAM[patch][index] * 3 + base[index]) // 4 for index in range(3))
    weave = ((x + seed) % 4 == 0) - ((y + seed) % 5 == 0)
    delta = int(value % 9) - 4 + weave * 2
    offsets = tuple(int((value >> shift) % 3) - 1 for shift in (8, 12, 16))
    return tuple(max(0, min(255, source[index] + delta + offsets[index])) for index in range(3)) + (255,)


def shade(base: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in base[:3]) + (255,)


def paint(image: Image.Image, face: Face, base: RGBA, seed: int, camouflage: bool,
          direction: str, material: str) -> None:
    x0, y0, x1, y1 = max(0, floor(face.x0)), max(0, floor(face.y0)), min(SIZE, ceil(face.x1)), min(SIZE, ceil(face.y1))
    directional = {"up": 10, "down": -10, "north": 2, "south": -4, "west": -7, "east": -3}[direction]
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = shade(color(base, x, y, seed, camouflage), directional)
    if x1 <= x0 or y1 <= y0:
        return
    draw = ImageDraw.Draw(image)
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=shade(base, -22))
    if direction in {"north", "south"} and material in {"molle", "webbing", "groin", "hip", "shoulder"}:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.point((x, y0), fill=shade(base, 18))
            if y1 - y0 > 2:
                draw.point((x, y1 - 1), fill=shade(base, -28))
    if direction in {"north", "south"} and material == "molle" and x1 - x0 >= 5:
        for x in range(x0 + 2, x1 - 1, 2):
            draw.line((x, y0, x, y1 - 1), fill=shade(base, -25))
        draw.line((x0 + 1, y0, x1 - 2, y0), fill=shade(base, 20))


def build(spec: ModelSpec) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), shade(PALETTE["carrier"], -25))
    for cube in spec.cubes:
        for direction, face in cube_faces(cube).items():
            paint(image, face, PALETTE[cube.material], stable_seed(f"{spec.name}:{cube.name}:{direction}"),
                  cube.material in CAMOUFLAGE, direction, cube.material)
    return image


JAVA_BOX = re.compile(r"\.texOffs\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\.addBox\("
                      r"[^,]+,[^,]+,[^,]+,\s*([0-9.]+)F,\s*([0-9.]+)F,\s*([0-9.]+)F\)", re.DOTALL)


def validate(spec: ModelSpec) -> None:
    if len({cube.name for cube in spec.cubes}) != len(spec.cubes):
        raise RuntimeError(f"{spec.name}: duplicate cube name")
    source = (CLIENT / spec.java_file).read_text(encoding="utf-8")
    actual = [(int(u), int(v), float(w), float(h), float(d)) for u, v, w, h, d in JAVA_BOX.findall(source)]
    expected = [(cube.u, cube.v, cube.width, cube.height, cube.depth) for cube in spec.cubes]
    if actual != expected:
        raise RuntimeError(f"{spec.name}: Java UV/cuboid order differs from generator")
    for cube in spec.cubes:
        for face in cube_faces(cube).values():
            if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                raise RuntimeError(f"{spec.name}.{cube.name}: UV overflow")


def main() -> None:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for spec in SPECS:
        validate(spec)
        first, second = build(spec), build(spec)
        if first.tobytes() != second.tobytes():
            raise RuntimeError(f"{spec.name}: nondeterministic texture")
        output = TEXTURES / spec.texture_file
        first.save(output, format="PNG", optimize=False)
        with Image.open(output) as written:
            colors = written.getcolors(SIZE * SIZE)
            if written.size != (SIZE, SIZE) or written.mode != "RGBA" or written.getchannel("A").getextrema() != (255, 255):
                raise RuntimeError(f"{spec.name}: output must be opaque 128x128 RGBA")
            if colors is None or len(colors) < 100:
                raise RuntimeError(f"{spec.name}: surface detail is too flat")
        print(f"{output.name} cubes={len(spec.cubes)} colors={len(colors)} sha256={sha256(output.read_bytes()).hexdigest().upper()}")
    print("uv=unique alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
