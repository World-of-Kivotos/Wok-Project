from dataclasses import dataclass, replace
from hashlib import sha256
from math import ceil, floor
from pathlib import Path
import re

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
TEXTURE_ROOT = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"
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


def cube_faces(cube: Cube) -> dict[str, Face]:
    u, v = cube.u, cube.v
    w, h, d = cube.width, cube.height, cube.depth
    return {
        "down": Face(u + d, v, u + d + w, v + d),
        "up": Face(u + d + w, v, u + d + 2 * w, v + d),
        "west": Face(u, v + d, u + d, v + d + h),
        "north": Face(u + d, v + d, u + d + w, v + d + h),
        "east": Face(u + d + w, v + d, u + 2 * d + w, v + d + h),
        "south": Face(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


def pack(cubes: tuple[Cube, ...]) -> tuple[Cube, ...]:
    """Deterministic shelf packer with a one-pixel gutter between cube nets."""
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


def c(name: str, w: float, h: float, d: float, material: str) -> Cube:
    return Cube(name, w, h, d, material)


RAW_MODELS: dict[str, tuple[Cube, ...]] = {
    "b6b13": (
        c("front_yoke", 6.8, 2.8, .38, "fabric"),
        c("front_plate", 7.5, 6.8, .48, "plate"),
        c("front_lower", 7.7, 2.0, .42, "plate"),
        c("rear_yoke", 6.8, 2.8, .38, "fabric"),
        c("rear_plate", 7.5, 8.6, .44, "plate"),
        c("left_side", .44, 8.4, 4.1, "side"),
        c("right_side", .44, 8.4, 4.1, "side"),
        c("front_collar_left", 3.8, 1.65, .42, "collar"),
        c("front_collar_right", 3.8, 1.65, .42, "collar"),
        c("rear_collar", 8.4, 1.65, .42, "collar"),
        c("left_collar", .42, 1.65, 8.1, "collar"),
        c("right_collar", .42, 1.65, 8.1, "collar"),
        c("groin", 5.0, 3.2, .40, "lower"),
        c("front_flap", 3.4, 1.3, .22, "webbing"),
        c("right_shoulder", 4.0, .45, 4.2, "shoulder"),
        c("left_shoulder", 4.0, .45, 4.2, "shoulder"),
        c("front_collar_root_left", 1.65, 1.8, .50, "fabric"),
        c("front_collar_root_right", 1.65, 1.8, .50, "fabric"),
        c("rear_collar_root_left", 1.65, 1.8, .50, "fabric"),
        c("rear_collar_root_right", 1.65, 1.8, .50, "fabric"),
    ),
    "b6b3": (
        c("front_upper", 6.8, 3.7, .40, "fabric"),
        c("front_lower", 7.5, 7.4, .45, "fabric"),
        c("rear_upper", 6.8, 3.7, .40, "fabric"),
        c("rear_lower", 7.5, 7.4, .45, "fabric"),
        c("left_side", .42, 8.8, 4.0, "side"),
        c("right_side", .42, 8.8, 4.0, "side"),
        c("front_strap_left", .85, 3.7, .32, "strap"),
        c("front_strap_right", .85, 3.7, .32, "strap"),
        c("rear_strap_left", .85, 3.7, .32, "strap"),
        c("rear_strap_right", .85, 3.7, .32, "strap"),
        c("center_flap", 3.1, 5.4, .35, "flap"),
        c("front_belt", 7.8, 1.3, .45, "belt"),
        c("rear_belt", 7.8, 1.3, .45, "belt"),
        c("pouch_far_left", 1.45, 3.9, .70, "pouch"),
        c("pouch_left", 1.45, 3.9, .70, "pouch"),
        c("pouch_right", 1.35, 3.9, .70, "pouch"),
        c("radio", 1.1, 4.4, .65, "dark"),
        c("buckle", 1.4, 1.5, .30, "metal"),
        c("side_belt", .28, 2.0, 3.9, "strap"),
    ),
    "ana": (
        c("front_plate", 6.9, 6.0, .42, "plate"),
        c("rear_plate", 6.9, 6.0, .42, "plate"),
        c("left_side", .40, 6.3, 4.0, "side"),
        c("right_side", .40, 6.3, 4.0, "side"),
        c("left_bridge", 1.1, 1.0, 4.8, "strap"),
        c("right_bridge", 1.1, 1.0, 4.8, "strap"),
        c("upper_webbing", 6.6, .28, .16, "webbing"),
        c("waist_front", 7.8, 3.8, .45, "belt"),
        c("waist_rear", 7.8, 3.8, .45, "belt"),
        c("medical", 1.7, 4.4, .80, "pouch"),
        c("small_pouch", 1.35, 2.3, .68, "pouch"),
        c("mag_left", 1.65, 4.2, .75, "mag"),
        c("mag_mid", 1.65, 4.2, .75, "mag"),
        c("mag_right", 1.65, 4.2, .75, "mag"),
        c("radio", 1.05, 4.6, .65, "dark"),
        c("antenna", .16, 5.0, .16, "dark"),
        c("tool", .45, 3.1, .20, "accent"),
    ),
    "a18": (
        c("front_plate", 6.8, 6.2, .45, "plate"),
        c("rear_plate", 6.8, 6.2, .45, "plate"),
        c("left_side", .42, 6.2, 4.0, "side"),
        c("right_side", .42, 6.2, 4.0, "side"),
        c("left_bridge", 1.15, 1.0, 4.8, "strap"),
        c("right_bridge", 1.15, 1.0, 4.8, "strap"),
        c("front_belt", 7.8, 2.5, .50, "belt"),
        c("rear_belt", 7.8, 2.5, .50, "belt"),
        c("left_pouch", 2.1, 4.5, .85, "pouch"),
        c("right_pouch", 2.1, 4.5, .85, "pouch"),
        c("mag_left", 1.8, 3.8, .75, "dark"),
        c("mag_right", 1.8, 3.8, .75, "dark"),
        c("utility", 1.1, 4.4, .70, "utility"),
        c("radio", .95, 4.7, .65, "dark"),
        c("webbing", 6.4, .25, .16, "webbing"),
        c("buckle", .8, 1.0, .22, "metal"),
    ),
    "avs": (
        c("front_plate", 6.9, 6.3, .45, "plate"),
        c("rear_plate", 6.9, 6.3, .45, "plate"),
        c("left_side", .42, 6.4, 4.0, "side"),
        c("right_side", .42, 6.4, 4.0, "side"),
        c("left_bridge", 1.2, 1.1, 4.8, "strap"),
        c("right_bridge", 1.2, 1.1, 4.8, "strap"),
        c("front_belt", 7.8, 2.4, .50, "belt"),
        c("rear_belt", 7.8, 2.4, .50, "belt"),
        c("left_pouch", 1.7, 4.3, .85, "pouch"),
        c("right_pouch", 1.5, 4.1, .75, "pouch"),
        c("mag_left", 1.55, 4.2, .72, "mag"),
        c("mag_mid", 1.55, 4.2, .72, "mag"),
        c("mag_right", 1.55, 4.2, .72, "mag"),
        c("radio", .85, 4.9, .60, "dark"),
        c("groin", 5.4, 5.8, .42, "groin"),
        c("molle_1", 4.6, .28, .18, "webbing"),
        c("molle_2", 4.6, .28, .18, "webbing"),
        c("molle_3", 4.6, .28, .18, "webbing"),
        c("molle_4", 4.6, .28, .18, "webbing"),
        c("chest_webbing_1", 6.5, .28, .16, "webbing"),
        c("chest_webbing_2", 6.5, .28, .16, "webbing"),
    ),
    "thor": (
        c("front_upper", 6.6, 4.0, .34, "upper"),
        c("front_lower", 7.6, 6.8, .36, "lower"),
        c("rear_upper", 6.6, 4.0, .34, "upper"),
        c("rear_lower", 7.6, 6.8, .36, "lower"),
        c("left_side", .36, 8.8, 4.0, "side"),
        c("right_side", .36, 8.8, 4.0, "side"),
        c("left_bridge", 1.4, 1.2, 4.7, "shoulder"),
        c("right_bridge", 1.4, 1.2, 4.7, "shoulder"),
        c("waist_front", 7.8, 1.1, .35, "waist"),
        c("waist_rear", 7.8, 1.1, .35, "waist"),
        c("lower_pad_left", 3.4, 2.0, .20, "pad"),
        c("lower_pad_right", 3.4, 2.0, .20, "pad"),
        c("chest_patch", 2.2, .7, .16, "patch"),
    ),
    "stich": (
        c("front_plate", 6.8, 6.1, .45, "plate"),
        c("rear_plate", 6.8, 6.1, .45, "plate"),
        c("left_side", .40, 6.4, 4.0, "side"),
        c("right_side", .40, 6.4, 4.0, "side"),
        c("left_bridge", 1.2, 1.05, 4.8, "strap"),
        c("right_bridge", 1.2, 1.05, 4.8, "strap"),
        c("front_belt", 7.8, 2.5, .48, "belt"),
        c("rear_belt", 7.8, 2.5, .48, "belt"),
        c("mag_left", 1.8, 4.1, .72, "mag"),
        c("mag_right", 1.8, 4.1, .72, "mag"),
        c("medical", 1.55, 4.4, .80, "pouch"),
        c("radio", 1.0, 4.8, .65, "dark"),
        c("antenna", .16, 5.0, .16, "dark"),
        c("drop_pouch", 4.8, 3.8, .75, "pouch"),
        c("molle", 6.2, .30, .16, "webbing"),
    ),
    "tv110": (
        c("front_plate", 6.8, 6.2, .45, "plate"),
        c("rear_plate", 6.8, 6.2, .45, "plate"),
        c("left_side", .42, 6.2, 4.0, "side"),
        c("right_side", .42, 6.2, 4.0, "side"),
        c("left_bridge", 1.25, 1.05, 4.8, "strap"),
        c("right_bridge", 1.25, 1.05, 4.8, "strap"),
        c("front_belt", 7.8, 2.1, .45, "belt"),
        c("rear_belt", 7.8, 2.1, .45, "belt"),
        c("left_pouch", 2.0, 4.1, .78, "pouch"),
        c("mid_pouch", 2.0, 4.1, .78, "pouch"),
        c("right_pouch", 1.4, 4.3, .70, "pouch"),
        c("radio", .8, 4.7, .62, "dark"),
        c("upper_molle", 6.3, .30, .16, "webbing"),
    ),
}

MODELS = {name: pack(cubes) for name, cubes in RAW_MODELS.items()}

JAVA_MODELS = {
    "b6b13": "B6B13ArmorModel.java",
    "b6b3": "B6B3Tm01MArmorModel.java",
    "ana": "AnaM1ArmorModel.java",
    "a18": "A18SkandaArmorModel.java",
    "avs": "AvsArmorModel.java",
    "thor": "ThorConcealableArmorModel.java",
    "stich": "StichProfiV2ArmorModel.java",
    "tv110": "Tv110ArmorModel.java",
}


THEMES = {
    "plate_armor_6b13_flora_layer_1.png": ("b6b13", {
        "fabric": (91, 111, 83, 255), "plate": (105, 124, 92, 255),
        "side": (61, 76, 58, 255), "collar": (78, 100, 76, 255),
        "lower": (96, 114, 84, 255), "webbing": (99, 105, 72, 255),
        "shoulder": (82, 105, 79, 255)}, {"fabric", "plate", "lower", "shoulder"}),
    "plate_armor_6b3tm_01m_khaki_layer_1.png": ("b6b3", {
        "fabric": (112, 108, 72, 255), "side": (73, 77, 53, 255),
        "strap": (84, 83, 55, 255), "flap": (129, 121, 78, 255),
        "belt": (85, 80, 52, 255), "pouch": (119, 112, 72, 255),
        "dark": (40, 40, 34, 255), "metal": (74, 68, 55, 255)}, set()),
    "plate_armor_ana_m1_olive_layer_1.png": ("ana", {
        "plate": (56, 65, 43, 255), "side": (41, 49, 34, 255),
        "strap": (65, 74, 50, 255), "webbing": (70, 77, 50, 255),
        "belt": (51, 59, 39, 255), "pouch": (58, 66, 43, 255),
        "mag": (73, 78, 52, 255), "dark": (27, 32, 27, 255),
        "accent": (125, 39, 31, 255)}, set()),
    "plate_armor_a18_skanda_multicam_layer_1.png": ("a18", {
        "plate": (126, 117, 82, 255), "side": (100, 92, 64, 255),
        "strap": (130, 121, 85, 255), "belt": (107, 97, 67, 255),
        "pouch": (142, 127, 85, 255), "dark": (31, 33, 31, 255),
        "utility": (82, 82, 63, 255), "webbing": (118, 107, 74, 255),
        "metal": (126, 124, 101, 255)}, {"plate", "side", "strap", "belt", "pouch", "webbing"}),
    "plate_armor_avs_ranger_green_layer_1.png": ("avs", {
        "plate": (68, 78, 54, 255), "side": (51, 61, 44, 255),
        "strap": (73, 83, 58, 255), "belt": (60, 70, 48, 255),
        "pouch": (64, 76, 52, 255), "mag": (73, 78, 72, 255),
        "dark": (31, 37, 34, 255), "groin": (65, 75, 51, 255),
        "webbing": (79, 87, 59, 255)}, set()),
    "plate_armor_avs_multicam_layer_1.png": ("avs", {
        "plate": (122, 114, 76, 255), "side": (92, 88, 62, 255),
        "strap": (132, 121, 83, 255), "belt": (108, 99, 67, 255),
        "pouch": (119, 110, 73, 255), "mag": (72, 76, 70, 255),
        "dark": (31, 35, 32, 255), "groin": (120, 110, 75, 255),
        "webbing": (115, 105, 71, 255)}, {"plate", "side", "strap", "belt", "pouch", "groin", "webbing"}),
    "plate_armor_thor_concealable_layer_1.png": ("thor", {
        "upper": (38, 41, 41, 255), "lower": (32, 36, 36, 255),
        "side": (24, 28, 28, 255), "shoulder": (42, 45, 45, 255),
        "waist": (25, 29, 29, 255), "pad": (35, 38, 38, 255),
        "patch": (24, 26, 26, 255)}, set()),
    "plate_armor_stich_profi_v2_black_layer_1.png": ("stich", {
        "plate": (31, 34, 34, 255), "side": (22, 26, 26, 255),
        "strap": (36, 39, 39, 255), "belt": (27, 30, 30, 255),
        "mag": (43, 38, 41, 255), "pouch": (31, 34, 34, 255),
        "dark": (17, 20, 20, 255), "webbing": (45, 48, 47, 255)}, set()),
    "plate_armor_tv110_coyote_layer_1.png": ("tv110", {
        "plate": (107, 104, 79, 255), "side": (77, 81, 64, 255),
        "strap": (116, 111, 83, 255), "belt": (85, 83, 64, 255),
        "pouch": (116, 109, 78, 255), "dark": (43, 46, 40, 255),
        "webbing": (96, 92, 70, 255)}, set()),
}


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, value + delta)) for value in color[:3]) + (255,)


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(char) for index, char in enumerate(value)) % 4093


def bounds(face: Face) -> tuple[int, int, int, int]:
    return max(0, floor(face.x0)), max(0, floor(face.y0)), min(SIZE, ceil(face.x1)), min(SIZE, ceil(face.y1))


def pixel(base: RGBA, x: int, y: int, seed: int, camouflage: bool) -> RGBA:
    fine = ((x * 73 + y * 151 + seed * 37 + (x + 7) * (y + 11) * 9) & 255) % 5 - 2
    if camouflage:
        cx, cy = x // 3, y // 3
        value = (cx * 29 + cy * 43 + seed * 17 + (cx ^ cy) * 11) % 19
        camo = -22 if value in (0, 1, 2) else 18 if value in (3, 4) else -9 if value in (5, 6) else 0
        return shade(base, fine + camo)
    weave = 3 if (x + 2 * y + seed) % 19 == 0 else -3 if (2 * x + y + seed) % 23 == 0 else 0
    return shade(base, fine + weave)


def paint(image: Image.Image, face: Face, base: RGBA, seed: int, camouflage: bool) -> tuple[int, int, int, int]:
    region = bounds(face)
    pixels = image.load()
    for y in range(region[1], region[3]):
        for x in range(region[0], region[2]):
            pixels[x, y] = pixel(base, x, y, seed, camouflage)
    return region


def detail(image: Image.Image, region: tuple[int, int, int, int], base: RGBA, kind: str) -> None:
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = region
    w, h = x1 - x0, y1 - y0
    if w >= 3 and h >= 3:
        draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 7))
        draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -10))
        draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 4))
        draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -7))
    if kind in {"plate", "pouch", "belt", "groin", "flap"} and w >= 4 and h >= 4:
        for x in range(x0 + 1, x1 - 1, 3):
            draw.point((x, y0 + 1), fill=shade(base, 12))
            draw.point((x, y1 - 2), fill=shade(base, -13))
    if kind in {"webbing", "strap"} and h >= 3:
        for y in range(y0 + 1, y1 - 1, 2):
            draw.line((x0 + 1, y, max(x0 + 1, x1 - 2), y), fill=shade(base, -8))
    if kind in {"pouch", "mag"} and h >= 4:
        draw.line((x0, min(y0 + 2, y1 - 1), x1 - 1, min(y0 + 2, y1 - 1)), fill=shade(base, -12))
        if w >= 4:
            draw.line((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=shade(base, -6))


def net_bounds(cube: Cube) -> Face:
    faces = tuple(cube_faces(cube).values())
    return Face(min(f.x0 for f in faces), min(f.y0 for f in faces), max(f.x1 for f in faces), max(f.y1 for f in faces))


def overlaps(a: Face, b: Face) -> bool:
    return min(a.x1, b.x1) > max(a.x0, b.x0) and min(a.y1, b.y1) > max(a.y0, b.y0)


def validate() -> None:
    for model, cubes in MODELS.items():
        if len({cube.name for cube in cubes}) != len(cubes):
            raise RuntimeError(f"duplicate cube name: {model}")
        for index, cube in enumerate(cubes):
            for direction, face in cube_faces(cube).items():
                if not (0 <= face.x0 <= face.x1 <= SIZE and 0 <= face.y0 <= face.y1 <= SIZE):
                    raise RuntimeError(f"UV overflow: {model}.{cube.name}.{direction}")
            for other in cubes[index + 1:]:
                if overlaps(net_bounds(cube), net_bounds(other)):
                    raise RuntimeError(f"UV overlap: {model}.{cube.name}/{other.name}")


def validate_java_uvs() -> None:
    source_root = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
    pattern = re.compile(
        r"texOffs\((\d+),\s*(\d+)\)\s*\.addBox\("
        r"[^,]+,[^,]+,[^,]+,\s*([0-9.]+)F,\s*([0-9.]+)F,\s*([0-9.]+)F\)"
    )
    for model_name, filename in JAVA_MODELS.items():
        source = (source_root / filename).read_text(encoding="utf-8")
        actual = [
            (int(u), int(v), float(width), float(height), float(depth))
            for u, v, width, height, depth in pattern.findall(source)
        ]
        expected = [
            (cube.u, cube.v, cube.width, cube.height, cube.depth)
            for cube in MODELS[model_name]
        ]
        if sorted(actual) != sorted(expected):
            raise RuntimeError(f"Java UV/cuboid mismatch: {model_name}: {actual} != {expected}")


def build(filename: str) -> Image.Image:
    model, palette, camouflage = THEMES[filename]
    background = tuple(max(8, value - 8) for value in next(iter(palette.values()))[:3]) + (255,)
    image = Image.new("RGBA", (SIZE, SIZE), background)
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = pixel(background, x, y, 31, False)
    for cube in MODELS[model]:
        base = palette[cube.material]
        for direction, face in cube_faces(cube).items():
            region = paint(image, face, base, stable_seed(f"{filename}:{cube.name}:{direction}"), cube.material in camouflage)
            if direction in {"north", "south", "west", "east", "up"}:
                detail(image, region, base, cube.material)
    return image


def main() -> None:
    validate()
    validate_java_uvs()
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for filename in THEMES:
        image = build(filename)
        if image.tobytes() != build(filename).tobytes():
            raise RuntimeError(f"nondeterministic texture: {filename}")
        output = TEXTURE_ROOT / filename
        image.save(output, format="PNG", optimize=False)
        with Image.open(output) as written:
            if written.size != (SIZE, SIZE) or written.mode != "RGBA" or written.getextrema()[3] != (255, 255):
                raise RuntimeError(f"invalid texture: {filename}")
            colors = written.getcolors(SIZE * SIZE)
            if colors is None or len(colors) < 24:
                raise RuntimeError(f"texture lost detail: {filename}")
        model_name = THEMES[filename][0]
        print(f"{filename} model={model_name} cuboids={len(MODELS[model_name])} colors={len(colors)} sha256={sha256(output.read_bytes()).hexdigest()}")
    print("uv=nonoverlapping alpha=255 deterministic=yes")


if __name__ == "__main__":
    main()
