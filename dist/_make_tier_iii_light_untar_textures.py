from dataclasses import dataclass
from math import ceil, floor
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "miningdim"
    / "textures"
    / "models"
    / "armor"
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
class CubeUV:
    u: float
    v: float
    width: float
    height: float
    depth: float


@dataclass(frozen=True)
class ModelSpec:
    name: str
    output_name: str
    background: RGBA
    cubes: dict[str, CubeUV]
    materials: dict[str, tuple[RGBA, str]]
    detail_faces: tuple[tuple[str, str, str], ...]
    instance_counts: dict[str, int]


def cube_faces(cube: CubeUV) -> dict[str, Face]:
    u = cube.u
    v = cube.v
    width = cube.width
    height = cube.height
    depth = cube.depth
    return {
        "down": Face(u + depth, v, u + depth + width, v + depth),
        "up": Face(u + depth + width, v, u + depth + width * 2, v + depth),
        "west": Face(u, v + depth, u + depth, v + depth + height),
        "north": Face(u + depth, v + depth, u + depth + width, v + depth + height),
        "east": Face(
            u + depth + width,
            v + depth,
            u + depth * 2 + width,
            v + depth + height,
        ),
        "south": Face(
            u + depth * 2 + width,
            v + depth,
            u + depth * 2 + width * 2,
            v + depth + height,
        ),
    }


def shade(color: RGBA, delta: int) -> RGBA:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (255,)


def stable_seed(value: str) -> int:
    return sum((index + 1) * ord(character) for index, character in enumerate(value)) % 1009


def pixel_bounds(face: Face) -> tuple[int, int, int, int]:
    return (
        max(0, floor(face.x0)),
        max(0, floor(face.y0)),
        min(SIZE, ceil(face.x1)),
        min(SIZE, ceil(face.y1)),
    )


def material_pixel(base: RGBA, kind: str, x: int, y: int, seed: int) -> RGBA:
    value = (x * 71 + y * 149 + seed * 197 + (x + 13) * (y + 5) * 19) & 0xFF
    noise = value % 7 - 3
    if kind == "nylon":
        weave = 4 if (x + 2 * y + seed) % 9 in (0, 1) else 0
        shadow = -3 if (2 * x + y + seed) % 13 == 0 else 0
        delta = noise + weave + shadow
    elif kind == "soft":
        weave = 3 if (x - y + seed) % 7 in (0, 1) else -1
        delta = noise // 2 + weave
    elif kind == "webbing":
        delta = noise + (-4 if (y + seed) % 3 == 0 else 1)
    elif kind == "velcro":
        delta = noise * 2 - (3 if (x + y + seed) % 5 == 0 else 0)
    elif kind == "metal":
        delta = noise * 2 + (8 if (x + 2 * y + seed) % 11 == 0 else -2)
    elif kind == "rubber":
        delta = noise // 2 - 2
    else:
        delta = noise
    return shade(base, delta)


def paint_base_face(
    image: Image.Image,
    face: Face,
    base: RGBA,
    kind: str,
    seed: int,
) -> tuple[int, int, int, int]:
    bounds = pixel_bounds(face)
    x0, y0, x1, y1 = bounds
    pixels = image.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[x, y] = material_pixel(base, kind, x, y, seed)
    return bounds


def draw_edges(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 2 or y1 - y0 < 2:
        return
    draw.line((x0, y0, x1 - 1, y0), fill=shade(base, 8))
    draw.line((x0, y0, x0, y1 - 1), fill=shade(base, 4))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(base, -13))
    draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=shade(base, -9))


def draw_stitches(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 - x0 < 4 or y1 - y0 < 3:
        return
    stitch = shade(base, 17)
    seam = shade(base, -18)
    for x in range(x0 + 1, x1 - 1, 2):
        draw.point((x, y0 + 1), fill=stitch)
        draw.point((x, y1 - 2), fill=seam)
    for y in range(y0 + 2, y1 - 2, 3):
        draw.point((x0 + 1, y), fill=stitch)
        draw.point((x1 - 2, y), fill=seam)


def draw_molle(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    if x1 <= x0 or y1 <= y0:
        return
    dark = shade(base, -28)
    light = shade(base, 8)
    y = y0 + max(0, (y1 - y0 - 1) // 2)
    for x in range(x0, x1, 2):
        draw.point((x, min(y, y1 - 1)), fill=dark)
        if x + 1 < x1:
            draw.point((x + 1, min(y, y1 - 1)), fill=light)


def draw_pouch(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    draw_edges(draw, bounds, base)
    draw_stitches(draw, bounds, base)
    if x1 - x0 >= 3 and y1 - y0 >= 4:
        center = x0 + (x1 - x0) // 2
        draw.line((center, y0 + 1, center, y1 - 2), fill=shade(base, -12))


def draw_badge(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=shade(base, -12), outline=shade(base, 12))
    if x1 - x0 >= 4 and y1 - y0 >= 2:
        center = x0 + (x1 - x0) // 2
        draw.point((center - 1, y0), fill=shade(base, 38))
        draw.point((center, min(y0 + 1, y1 - 1)), fill=shade(base, 34))


def draw_magazine(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int], base: RGBA) -> None:
    x0, y0, x1, y1 = bounds
    draw_edges(draw, bounds, base)
    if x1 > x0 and y1 > y0:
        draw.line((x0, y0, x1 - 1, y0), fill=(123, 43, 52, 255))


def draw_label(draw: ImageDraw.ImageDraw, bounds: tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = bounds
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(29, 35, 40, 255))
    for x in range(x0, x1, 2):
        draw.point((x, y0), fill=(211, 215, 212, 255))
    if y1 - y0 > 1:
        for x in range(x0 + 1, x1, 2):
            draw.point((x, y1 - 1), fill=(170, 179, 180, 255))


def paint_detail(
    image: Image.Image,
    spec: ModelSpec,
    cube_name: str,
    direction: str,
    style: str,
) -> None:
    cube = spec.cubes[cube_name]
    base, kind = spec.materials[cube_name]
    face = cube_faces(cube)[direction]
    bounds = paint_base_face(
        image,
        face,
        base,
        kind,
        stable_seed(spec.name + cube_name + direction),
    )
    draw = ImageDraw.Draw(image)
    if style == "panel":
        draw_edges(draw, bounds, base)
        draw_stitches(draw, bounds, base)
    elif style == "webbing":
        draw_edges(draw, bounds, base)
        draw_molle(draw, bounds, base)
    elif style == "pouch":
        draw_pouch(draw, bounds, base)
    elif style == "badge":
        draw_badge(draw, bounds, base)
    elif style == "buckle":
        draw.rectangle(
            (bounds[0], bounds[1], bounds[2] - 1, bounds[3] - 1),
            fill=shade(base, 7),
            outline=shade(base, -22),
        )
    elif style == "magazine":
        draw_magazine(draw, bounds, base)
    elif style == "label":
        draw_label(draw, bounds)


MBSS_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_panel": CubeUV(25, 0, 7.10, 7.70, 0.40),
    "rear_panel": CubeUV(42, 0, 6.80, 7.40, 0.36),
    "side_panel": CubeUV(58, 0, 0.30, 5.20, 4.10),
    "front_strap": CubeUV(68, 0, 1.15, 4.10, 0.40),
    "rear_strap": CubeUV(73, 0, 1.15, 4.10, 0.40),
    "admin": CubeUV(0, 20, 5.10, 1.25, 0.16),
    "webbing": CubeUV(12, 20, 6.20, 0.22, 0.12),
    "buckle": CubeUV(28, 20, 0.60, 0.75, 0.14),
    "pouch": CubeUV(0, 28, 2.00, 4.80, 0.34),
    "lid": CubeUV(6, 28, 2.00, 1.00, 0.18),
    "side_pouch": CubeUV(12, 28, 0.30, 3.40, 3.10),
    "belt": CubeUV(20, 28, 7.50, 1.00, 0.28),
    "shoulder_top": CubeUV(38, 28, 3.10, 0.36, 4.92),
}

MBSS_MATERIALS = {
    "carrier": ((76, 75, 52, 255), "nylon"),
    "front_panel": ((98, 92, 63, 255), "nylon"),
    "rear_panel": ((83, 82, 56, 255), "nylon"),
    "side_panel": ((84, 81, 55, 255), "webbing"),
    "front_strap": ((117, 106, 72, 255), "webbing"),
    "rear_strap": ((105, 97, 65, 255), "webbing"),
    "admin": ((72, 71, 49, 255), "velcro"),
    "webbing": ((109, 99, 67, 255), "webbing"),
    "buckle": ((57, 60, 54, 255), "metal"),
    "pouch": ((104, 96, 65, 255), "nylon"),
    "lid": ((115, 104, 70, 255), "webbing"),
    "side_pouch": ((91, 87, 59, 255), "nylon"),
    "belt": ((81, 78, 53, 255), "webbing"),
    "shoulder_top": ((108, 99, 67, 255), "nylon"),
}

TV115_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_panel": CubeUV(25, 0, 6.70, 7.20, 0.34),
    "rear_panel": CubeUV(41, 0, 6.50, 7.40, 0.34),
    "front_strap": CubeUV(56, 0, 1.05, 3.90, 0.36),
    "rear_strap": CubeUV(61, 0, 1.05, 3.90, 0.36),
    "side_rail": CubeUV(0, 20, 0.22, 0.28, 4.16),
    "front_rail": CubeUV(10, 20, 6.20, 0.22, 0.10),
    "belt": CubeUV(24, 20, 7.40, 0.90, 0.30),
    "pouch": CubeUV(0, 28, 1.35, 4.35, 0.30),
    "magazine": CubeUV(5, 28, 1.05, 1.10, 0.24),
    "tool": CubeUV(10, 28, 0.80, 2.90, 0.18),
    "buckle": CubeUV(15, 28, 0.55, 0.70, 0.12),
    "shoulder_top": CubeUV(20, 28, 3.70, 0.32, 4.92),
}

TV115_MATERIALS = {
    "carrier": ((32, 35, 36, 255), "nylon"),
    "front_panel": ((43, 46, 46, 255), "nylon"),
    "rear_panel": ((37, 40, 41, 255), "nylon"),
    "front_strap": ((25, 27, 28, 255), "webbing"),
    "rear_strap": ((28, 30, 31, 255), "webbing"),
    "side_rail": ((22, 24, 25, 255), "webbing"),
    "front_rail": ((24, 26, 27, 255), "webbing"),
    "belt": ((29, 31, 32, 255), "webbing"),
    "pouch": ((47, 49, 46, 255), "nylon"),
    "magazine": ((65, 61, 46, 255), "metal"),
    "tool": ((38, 40, 39, 255), "rubber"),
    "buckle": ((73, 76, 74, 255), "metal"),
    "shoulder_top": ((29, 31, 32, 255), "nylon"),
}

MF_UNTAR_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_panel": CubeUV(25, 0, 7.60, 10.40, 0.36),
    "rear_panel": CubeUV(42, 0, 7.60, 10.50, 0.36),
    "side_panel": CubeUV(59, 0, 0.36, 8.50, 4.68),
    "front_yoke": CubeUV(71, 0, 1.55, 3.65, 0.40),
    "rear_yoke": CubeUV(76, 0, 1.55, 3.65, 0.40),
    "patch": CubeUV(0, 20, 5.30, 2.15, 0.18),
    "label": CubeUV(12, 20, 3.60, 1.05, 0.12),
    "molle": CubeUV(22, 20, 6.90, 0.24, 0.14),
    "hem": CubeUV(38, 20, 7.60, 0.90, 0.30),
    "side_band": CubeUV(55, 20, 0.12, 0.28, 4.20),
    "shoulder_core": CubeUV(65, 20, 3.35, 3.10, 4.00),
    "shoulder_top": CubeUV(81, 20, 4.25, 0.24, 4.92),
}

MF_UNTAR_MATERIALS = {
    "carrier": ((47, 128, 171, 255), "soft"),
    "front_panel": ((54, 145, 191, 255), "soft"),
    "rear_panel": ((47, 132, 176, 255), "soft"),
    "side_panel": ((42, 119, 160, 255), "soft"),
    "front_yoke": ((61, 150, 193, 255), "webbing"),
    "rear_yoke": ((52, 136, 178, 255), "webbing"),
    "patch": ((37, 47, 54, 255), "velcro"),
    "label": ((26, 33, 38, 255), "velcro"),
    "molle": ((34, 78, 105, 255), "webbing"),
    "hem": ((42, 116, 156, 255), "webbing"),
    "side_band": ((32, 74, 99, 255), "webbing"),
    "shoulder_core": ((55, 141, 184, 255), "soft"),
    "shoulder_top": ((69, 157, 197, 255), "webbing"),
}

SPECS = (
    ModelSpec(
        name="MBSS",
        output_name="plate_armor_mbss_layer_1.png",
        background=(67, 66, 46, 255),
        cubes=MBSS_CUBES,
        materials=MBSS_MATERIALS,
        detail_faces=(
            ("carrier", "north", "panel"),
            ("carrier", "south", "panel"),
            ("front_panel", "north", "panel"),
            ("rear_panel", "south", "panel"),
            ("side_panel", "west", "webbing"),
            ("side_panel", "east", "webbing"),
            ("front_strap", "north", "panel"),
            ("rear_strap", "south", "panel"),
            ("admin", "north", "badge"),
            ("webbing", "north", "webbing"),
            ("buckle", "north", "buckle"),
            ("pouch", "north", "pouch"),
            ("lid", "north", "pouch"),
            ("side_pouch", "west", "pouch"),
            ("side_pouch", "east", "pouch"),
            ("belt", "north", "webbing"),
            ("belt", "south", "webbing"),
            ("shoulder_top", "up", "panel"),
        ),
        instance_counts={
            "side_panel": 2,
            "front_strap": 2,
            "rear_strap": 2,
            "webbing": 2,
            "buckle": 2,
            "pouch": 3,
            "lid": 3,
            "side_pouch": 2,
            "belt": 2,
            "shoulder_top": 2,
        },
    ),
    ModelSpec(
        name="TV-115",
        output_name="plate_armor_tv115_layer_1.png",
        background=(23, 25, 26, 255),
        cubes=TV115_CUBES,
        materials=TV115_MATERIALS,
        detail_faces=(
            ("carrier", "north", "panel"),
            ("front_panel", "north", "panel"),
            ("rear_panel", "south", "panel"),
            ("front_strap", "north", "panel"),
            ("rear_strap", "south", "panel"),
            ("side_rail", "west", "webbing"),
            ("side_rail", "east", "webbing"),
            ("front_rail", "north", "webbing"),
            ("belt", "north", "webbing"),
            ("belt", "south", "webbing"),
            ("pouch", "north", "pouch"),
            ("magazine", "north", "magazine"),
            ("tool", "north", "pouch"),
            ("buckle", "north", "buckle"),
            ("shoulder_top", "up", "panel"),
        ),
        instance_counts={
            "front_strap": 2,
            "rear_strap": 2,
            "side_rail": 6,
            "front_rail": 2,
            "belt": 2,
            "pouch": 4,
            "magazine": 4,
            "tool": 2,
            "buckle": 2,
            "shoulder_top": 2,
        },
    ),
    ModelSpec(
        name="MF-UNTAR",
        output_name="plate_armor_mf_untar_layer_1.png",
        background=(35, 101, 139, 255),
        cubes=MF_UNTAR_CUBES,
        materials=MF_UNTAR_MATERIALS,
        detail_faces=(
            ("carrier", "north", "panel"),
            ("carrier", "south", "panel"),
            ("front_panel", "north", "panel"),
            ("rear_panel", "south", "panel"),
            ("side_panel", "west", "panel"),
            ("side_panel", "east", "panel"),
            ("front_yoke", "north", "panel"),
            ("rear_yoke", "south", "panel"),
            ("patch", "north", "badge"),
            ("label", "north", "label"),
            ("molle", "north", "webbing"),
            ("hem", "north", "webbing"),
            ("hem", "south", "webbing"),
            ("side_band", "west", "webbing"),
            ("side_band", "east", "webbing"),
            ("shoulder_core", "north", "panel"),
            ("shoulder_top", "up", "panel"),
        ),
        instance_counts={
            "side_panel": 2,
            "front_yoke": 2,
            "rear_yoke": 2,
            "molle": 6,
            "hem": 2,
            "side_band": 4,
            "shoulder_core": 2,
            "shoulder_top": 2,
        },
    ),
)


def validate_uvs(spec: ModelSpec) -> None:
    if set(spec.cubes) != set(spec.materials):
        raise RuntimeError(f"{spec.name}: cube/material definitions differ")
    for cube_name, cube in spec.cubes.items():
        for direction, face in cube_faces(cube).items():
            if not (
                0.0 <= face.x0 <= face.x1 <= SIZE
                and 0.0 <= face.y0 <= face.y1 <= SIZE
            ):
                raise RuntimeError(f"{spec.name}: UV overflow {cube_name}.{direction}={face}")


def build_texture(spec: ModelSpec) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = material_pixel(spec.background, "nylon", x, y, stable_seed(spec.name))

    for cube_name, cube in spec.cubes.items():
        base, kind = spec.materials[cube_name]
        for direction, face in cube_faces(cube).items():
            paint_base_face(
                image,
                face,
                base,
                kind,
                stable_seed(spec.name + cube_name + direction),
            )

    for cube_name, direction, style in spec.detail_faces:
        paint_detail(image, spec, cube_name, direction, style)
    return image


def runtime_cube_count(spec: ModelSpec) -> int:
    return sum(spec.instance_counts.get(cube_name, 1) for cube_name in spec.cubes)


def write_texture(spec: ModelSpec) -> None:
    validate_uvs(spec)
    image = build_texture(spec)
    if image.tobytes() != build_texture(spec).tobytes():
        raise RuntimeError(f"{spec.name}: texture generation is not deterministic")

    output = OUTPUT_DIR / spec.output_name
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG", optimize=False)

    with Image.open(output) as written:
        if written.mode != "RGBA" or written.size != (SIZE, SIZE):
            raise RuntimeError(f"{spec.name}: output must be a 128x128 RGBA PNG")
        if written.getextrema()[3] != (255, 255):
            raise RuntimeError(f"{spec.name}: output alpha must remain fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 30:
            raise RuntimeError(f"{spec.name}: material texture lost surface variation")

    print(f"{output} colors={len(colors)} cubes={runtime_cube_count(spec)} uv=ok alpha=255")


def main() -> None:
    expected_counts = {"MBSS": 26, "TV-115": 31, "MF-UNTAR": 27}
    for spec in SPECS:
        actual_count = runtime_cube_count(spec)
        if actual_count != expected_counts[spec.name]:
            raise RuntimeError(
                f"{spec.name}: expected {expected_counts[spec.name]} cuboids, got {actual_count}"
            )
        write_texture(spec)


if __name__ == "__main__":
    main()
