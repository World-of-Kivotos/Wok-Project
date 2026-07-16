from __future__ import annotations

from dataclasses import dataclass, replace
from hashlib import sha256
from io import BytesIO
from math import ceil, floor
from pathlib import Path
import re

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
MODEL = (
    ROOT
    / "src"
    / "main"
    / "java"
    / "com"
    / "miningdim"
    / "job"
    / "engineer"
    / "armor"
    / "client"
    / "B6B43ZabraloShArmorModel.java"
)
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
    / "plate_armor_6b43_zabralo_sh_layer_1.png"
)
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
    bone: str
    x: float
    y: float
    z: float
    width: float
    height: float
    depth: float
    material: str
    u: int = 0
    v: int = 0


def cube(
    name: str,
    bone: str,
    x: float,
    y: float,
    z: float,
    width: float,
    height: float,
    depth: float,
    material: str,
) -> Cube:
    return Cube(name, bone, x, y, z, width, height, depth, material)


GEOMETRY = (
    cube("front_upper", "body", -3.50, 0.25, -2.68, 7.00, 3.25, 0.64, "shell"),
    cube("front_middle", "body", -3.88, 3.30, -2.76, 7.76, 4.25, 0.72, "shell"),
    cube("front_lower", "body", -4.00, 7.35, -2.82, 8.00, 4.20, 0.78, "shell"),
    cube("rear_upper", "body", -3.50, 0.25, 2.04, 7.00, 3.25, 0.64, "shell"),
    cube("rear_middle", "body", -3.88, 3.30, 2.04, 7.76, 4.25, 0.72, "shell"),
    cube("rear_lower", "body", -4.00, 7.35, 2.04, 8.00, 4.20, 0.78, "shell"),
    cube("left_side", "body", -3.96, 2.25, -2.30, 0.43, 9.24, 4.60, "side"),
    cube("right_side", "body", 3.53, 2.25, -2.30, 0.43, 9.24, 4.60, "side"),

    # A five-piece stand collar leaves a broad central face opening while retaining
    # the high, thick silhouette of the reference armor.
    cube("collar_front_left", "body", -4.15, -1.85, -4.52, 3.25, 1.85, 0.55, "collar"),
    cube("collar_front_right", "body", 0.90, -1.85, -4.52, 3.25, 1.85, 0.55, "collar"),
    cube("collar_rear", "body", -4.20, -2.00, 3.97, 8.40, 2.00, 0.55, "collar"),
    cube("collar_left", "body", -4.52, -1.95, -3.98, 0.55, 1.95, 7.96, "collar"),
    cube("collar_right", "body", 3.97, -1.95, -3.98, 0.55, 1.95, 7.96, "collar"),
    cube("front_root_left", "body", -3.35, -0.15, -4.00, 2.45, 0.65, 1.45, "reinforcement"),
    cube("front_root_right", "body", 0.90, -0.15, -4.00, 2.45, 0.65, 1.45, "reinforcement"),
    cube("rear_root_left", "body", -3.35, -0.15, 2.55, 2.45, 0.65, 1.45, "reinforcement"),
    cube("rear_root_right", "body", 0.90, -0.15, 2.55, 2.45, 0.65, 1.45, "reinforcement"),

    # Seven empty horizontal webbing courses; Zabralo-Sh has no conventional
    # magazine pouches on this front face.
    *(cube(f"webbing_{index}", "body", -3.50, y, -2.94, 7.00, 0.22, 0.20, "webbing")
      for index, y in enumerate((2.65, 3.75, 4.85, 5.95, 7.05, 8.20, 9.35))),
    cube("front_belt", "body", -4.00, 10.35, -3.10, 8.00, 1.25, 0.30, "belt"),
    cube("rear_belt", "body", -4.00, 10.35, 2.80, 8.00, 1.25, 0.30, "belt"),
    cube("left_belt", "body", -3.90, 10.38, -2.34, 0.39, 1.05, 4.68, "belt"),
    cube("right_belt", "body", 3.51, 10.38, -2.34, 0.39, 1.05, 4.68, "belt"),

    # Long overlapping courses produce a wide guard whose lower edge visibly tapers.
    cube("groin_upper", "body", -3.00, 11.10, -3.36, 6.00, 2.00, 0.50, "groin"),
    cube("groin_mid_upper", "body", -2.72, 12.90, -3.40, 5.44, 2.05, 0.48, "groin"),
    cube("groin_mid_lower", "body", -2.38, 14.75, -3.44, 4.76, 1.95, 0.46, "groin"),
    cube("groin_lower", "body", -2.00, 16.50, -3.48, 4.00, 1.75, 0.44, "groin"),
    cube("groin_tip", "body", -1.50, 18.05, -3.52, 3.00, 1.35, 0.42, "groin"),
    cube("groin_overlay", "body", -2.35, 11.65, -3.70, 4.70, 3.15, 0.30, "overlay"),
    cube("groin_overlay_band", "body", -2.15, 12.48, -3.94, 4.30, 0.24, 0.18, "webbing"),

    # Right arm: top bridge, two outer courses and three stepped front/rear courses.
    cube("right_top", "right_arm", -3.25, -2.35, -2.45, 4.30, 0.58, 4.90, "shoulder"),
    cube("right_outer_upper", "right_arm", -3.40, -1.85, -2.22, 0.52, 3.15, 4.44, "shoulder"),
    cube("right_outer_lower", "right_arm", -3.36, 1.20, -2.18, 0.48, 3.35, 4.36, "shoulder"),
    cube("right_front_upper", "right_arm", -3.25, -1.78, -2.82, 4.15, 2.15, 0.46, "shoulder"),
    cube("right_front_middle", "right_arm", -3.15, 0.28, -2.88, 3.95, 2.05, 0.44, "shoulder"),
    cube("right_front_lower", "right_arm", -3.00, 2.22, -2.84, 3.65, 2.18, 0.42, "shoulder"),
    cube("right_rear_upper", "right_arm", -3.25, -1.78, 2.36, 4.15, 2.15, 0.46, "shoulder"),
    cube("right_rear_middle", "right_arm", -3.15, 0.28, 2.44, 3.95, 2.05, 0.44, "shoulder"),
    cube("right_rear_lower", "right_arm", -3.00, 2.22, 2.42, 3.65, 2.18, 0.42, "shoulder"),

    # Left arm mirrors the right while preserving Minecraft's asymmetric arm origins.
    cube("left_top", "left_arm", -1.05, -2.35, -2.45, 4.30, 0.58, 4.90, "shoulder"),
    cube("left_outer_upper", "left_arm", 2.88, -1.85, -2.22, 0.52, 3.15, 4.44, "shoulder"),
    cube("left_outer_lower", "left_arm", 2.88, 1.20, -2.18, 0.48, 3.35, 4.36, "shoulder"),
    cube("left_front_upper", "left_arm", -0.90, -1.78, -2.82, 4.15, 2.15, 0.46, "shoulder"),
    cube("left_front_middle", "left_arm", -0.80, 0.28, -2.88, 3.95, 2.05, 0.44, "shoulder"),
    cube("left_front_lower", "left_arm", -0.65, 2.22, -2.84, 3.65, 2.18, 0.42, "shoulder"),
    cube("left_rear_upper", "left_arm", -0.90, -1.78, 2.36, 4.15, 2.15, 0.46, "shoulder"),
    cube("left_rear_middle", "left_arm", -0.80, 0.28, 2.44, 3.95, 2.05, 0.44, "shoulder"),
    cube("left_rear_lower", "left_arm", -0.65, 2.22, 2.42, 3.65, 2.18, 0.42, "shoulder"),
)


def pack(cubes: tuple[Cube, ...]) -> tuple[Cube, ...]:
    packed: list[Cube] = []
    x = 0
    y = 0
    row_height = 0
    for item in cubes:
        net_width = ceil(2.0 * (item.width + item.depth))
        net_height = ceil(item.height + item.depth)
        if x and x + net_width > SIZE:
            x = 0
            y += row_height + 1
            row_height = 0
        if y + net_height > SIZE:
            raise RuntimeError(f"UV atlas overflow at {item.name}: y={y}, height={net_height}")
        packed.append(replace(item, u=x, v=y))
        x += net_width + 1
        row_height = max(row_height, net_height)
    return tuple(packed)


CUBES = pack(GEOMETRY)


PALETTE: dict[str, tuple[int, int, int]] = {
    "shell": (109, 111, 55),
    "side": (70, 77, 39),
    "collar": (101, 104, 51),
    "reinforcement": (126, 124, 65),
    "webbing": (91, 92, 48),
    "belt": (79, 82, 43),
    "groin": (113, 112, 56),
    "overlay": (129, 126, 67),
    "shoulder": (105, 107, 53),
}

DIGITAL_WOODLAND = (
    (43, 56, 31),
    (58, 72, 35),
    (76, 87, 40),
    (97, 101, 49),
    (118, 118, 58),
    (139, 135, 70),
    (156, 149, 82),
)

CAMOUFLAGE_MATERIALS = frozenset({
    "shell", "side", "collar", "reinforcement", "groin", "overlay", "shoulder"
})


def shade(color: tuple[int, int, int] | RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(value: str) -> int:
    return int.from_bytes(sha256(value.encode("utf-8")).digest()[:4], "big")


def noise(x: int, y: int, seed: int) -> int:
    value = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return (value ^ (value >> 16)) & 0xFF


def material_color(material: str, x: int, y: int, seed: int) -> RGBA:
    base = PALETTE[material]
    if material in CAMOUFLAGE_MATERIALS:
        # Hard-edged 2-4 px cells reproduce the yellow-green EMR/digital woodland
        # character without relying on randomness or resampling.
        block_x = x // (2 + ((seed >> 3) & 1))
        block_y = y // (2 + ((seed >> 5) & 1))
        field = noise(block_x, block_y, seed)
        notch = noise(x // 5, y // 4, seed + 0x6B43)
        index = field * len(DIGITAL_WOODLAND) // 256
        if notch < 42:
            index = max(0, index - 1)
        elif notch > 221:
            index = min(len(DIGITAL_WOODLAND) - 1, index + 1)
        source = DIGITAL_WOODLAND[index]
        source = tuple((source[channel] * 3 + base[channel]) // 4 for channel in range(3))
    else:
        source = base
    grain = noise(x, y, seed + 97) % 9 - 4
    weave = 4 if (x + y + seed) % 7 in (0, 1) else -3 if (x - y + seed) % 11 == 0 else 0
    return shade(source, grain + weave)


def cube_faces(item: Cube) -> dict[str, Face]:
    u, v, width, height, depth = item.u, item.v, item.width, item.height, item.depth
    return {
        "down": Face(u + depth, v, u + depth + width, v + depth),
        "up": Face(u + depth + width, v, u + depth + width * 2.0, v + depth),
        "west": Face(u, v + depth, u + depth, v + depth + height),
        "north": Face(u + depth, v + depth, u + depth + width, v + depth + height),
        "east": Face(u + depth + width, v + depth, u + depth * 2.0 + width, v + depth + height),
        "south": Face(
            u + depth * 2.0 + width,
            v + depth,
            u + depth * 2.0 + width * 2.0,
            v + depth + height,
        ),
    }


def paint_face(image: Image.Image, item: Cube, direction: str, face: Face) -> None:
    x0 = max(0, floor(face.x0))
    y0 = max(0, floor(face.y0))
    x1 = min(SIZE, ceil(face.x1))
    y1 = min(SIZE, ceil(face.y1))
    directional = {"up": 11, "down": -12, "north": 3, "south": -5, "west": -8, "east": -4}[direction]
    seed = stable_seed(f"6B43:{item.name}:{direction}")
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = shade(material_color(item.material, x, y, seed), directional)
    if x1 <= x0 or y1 <= y0:
        return

    draw = ImageDraw.Draw(image)
    base = PALETTE[item.material]
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=shade(base, -22))
    if x1 - x0 >= 4 and y1 - y0 >= 3:
        draw.line((x0 + 1, y0, x1 - 2, y0), fill=shade(base, 16))
        draw.line((x0 + 1, y1 - 1, x1 - 2, y1 - 1), fill=shade(base, -18))
    if direction in {"north", "south"} and x1 - x0 >= 5:
        for x in range(x0 + 2, x1 - 1, 3):
            draw.point((x, y0), fill=shade(base, 19))
            if y1 - y0 > 2:
                draw.point((x, y1 - 1), fill=shade(base, -24))
    if item.material == "webbing" and direction in {"north", "south"}:
        middle = y0 + max(0, (y1 - y0 - 1) // 2)
        for x in range(x0 + 1, x1 - 1, 2):
            draw.point((x, middle), fill=shade(base, -27))
    if item.material == "overlay" and direction == "north" and y1 - y0 >= 4:
        draw.line((x0 + 1, y0 + 1, x0 + 1, y1 - 2), fill=shade(base, 14))
        draw.line((x1 - 2, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -18))


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (48, 57, 31, 255))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            base = (48, 57, 31)
            pixels[x, y] = shade(base, noise(x, y, 643) % 7 - 3)
    for item in CUBES:
        for direction, face in cube_faces(item).items():
            paint_face(image, item, direction, face)
    return image


JAVA_BOX = re.compile(
    r"\.texOffs\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\.addBox\(\s*"
    r"(-?\d+(?:\.\d+)?)F\s*,\s*(-?\d+(?:\.\d+)?)F\s*,\s*"
    r"(-?\d+(?:\.\d+)?)F\s*,\s*(\d+(?:\.\d+)?)F\s*,\s*"
    r"(\d+(?:\.\d+)?)F\s*,\s*(\d+(?:\.\d+)?)F\s*\)",
    re.DOTALL,
)


def validate_model() -> None:
    source = MODEL.read_text(encoding="utf-8")
    actual = [
        (int(u), int(v), *(float(value) for value in values))
        for u, v, *values in JAVA_BOX.findall(source)
    ]
    expected = [
        (item.u, item.v, item.x, item.y, item.z, item.width, item.height, item.depth)
        for item in CUBES
    ]
    if actual != expected:
        raise RuntimeError("B6B43 Java UV/cuboid order differs from deterministic texture specification")
    if source.count('addOrReplaceChild("head", CubeListBuilder.create()') != 1:
        raise RuntimeError("B6B43 head bone must remain empty so the collar cannot replace or cover the face")
    if len([item for item in CUBES if item.name.startswith("collar_") and "root" not in item.name]) != 5:
        raise RuntimeError("B6B43 requires exactly five stand-collar panels")
    webbing = [item for item in CUBES if item.name.startswith("webbing_")]
    if len(webbing) != 7:
        raise RuntimeError("B6B43 requires seven empty front webbing courses")
    groin = [item for item in CUBES if item.name.startswith("groin_") and item.name not in {
        "groin_overlay", "groin_overlay_band"
    }]
    if [item.width for item in groin] != sorted((item.width for item in groin), reverse=True):
        raise RuntimeError("B6B43 groin guard must taper continuously toward its lower edge")
    if groin[-1].y + groin[-1].height < 19.0:
        raise RuntimeError("B6B43 groin guard is not long enough")
    for bone in ("right_arm", "left_arm"):
        pieces = [item for item in CUBES if item.bone == bone]
        if len(pieces) != 9 or max(item.y + item.height for item in pieces) < 4.4:
            raise RuntimeError(f"B6B43 {bone} must retain nine long segmented upper-arm panels")
    forbidden = ("pouch", "magazine", "mag_pouch")
    if any(token in item.name for item in CUBES for token in forbidden):
        raise RuntimeError("B6B43 must not invent conventional magazine pouches")


def png_bytes(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def main() -> None:
    validate_model()
    first = png_bytes(render())
    second = png_bytes(render())
    if first != second:
        raise RuntimeError("B6B43 texture generation is not deterministic")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(first)
    with Image.open(OUTPUT) as written:
        colors = written.getcolors(SIZE * SIZE)
        if written.size != (SIZE, SIZE) or written.mode != "RGBA":
            raise RuntimeError("B6B43 texture must be a 128x128 RGBA PNG")
        if written.getchannel("A").getextrema() != (255, 255):
            raise RuntimeError("B6B43 texture must remain fully opaque")
        if colors is None or len(colors) < 120:
            raise RuntimeError("B6B43 texture lacks digital woodland material variation")
    print(
        f"{OUTPUT.name} cubes={len(CUBES)} colors={len(colors)} "
        f"sha256={sha256(first).hexdigest().upper()} alpha=255 deterministic=yes"
    )


if __name__ == "__main__":
    main()
