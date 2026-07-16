from collections import Counter
from hashlib import sha256
from pathlib import Path
import re

from PIL import Image, ImageDraw

from _make_tier_iv_light_armor_textures import (
    OUTPUT_DIR,
    SIZE,
    CubeUV,
    Material,
    ModelSpec,
    build_texture,
    cube_faces,
    pixel_bounds,
    rgba,
    runtime_cube_count,
    shade,
    validate_uvs,
)


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
NUMBER = r"-?(?:\d+(?:\.\d*)?|\.\d+)F?"
JAVA_BOX = re.compile(
    rf"\.texOffs\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\.addBox\(\s*"
    rf"({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*"
    rf"({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*\)",
    re.DOTALL,
)


DEFENDER_SPOT = tuple(
    rgba(*color)
    for color in (
        (34, 37, 27),
        (44, 45, 31),
        (56, 53, 35),
        (69, 60, 40),
        (82, 69, 47),
        (48, 36, 27),
    )
)


DEFENDER_CUBES = {
    "front_chest_side": CubeUV(0, 0, 2.20, 3.35, 0.52),
    "front_mid": CubeUV(25, 0, 7.44, 4.15, 0.58),
    "front_lower": CubeUV(50, 0, 7.68, 3.55, 0.62),
    "rear_upper": CubeUV(75, 0, 6.50, 3.45, 0.52),
    "rear_mid": CubeUV(100, 0, 7.44, 4.15, 0.58),
    "rear_lower": CubeUV(0, 22, 7.68, 3.55, 0.62),
    "side_wrap": CubeUV(25, 22, 0.43, 7.72, 4.10),
    "front_yoke": CubeUV(50, 22, 1.30, 3.90, 0.34),
    "rear_yoke": CubeUV(75, 22, 1.30, 3.90, 0.34),
    "top_bridge": CubeUV(100, 22, 1.24, 0.35, 5.00),
    "front_belt": CubeUV(0, 44, 7.80, 1.28, 0.38),
    "rear_belt": CubeUV(25, 44, 7.80, 1.28, 0.38),
    "side_belt": CubeUV(50, 44, 0.42, 1.30, 4.50),
    "soft_band": CubeUV(75, 44, 2.65, 1.10, 0.40),
    "groin_top": CubeUV(100, 44, 6.20, 1.90, 0.52),
    "groin_mid": CubeUV(0, 66, 5.60, 3.05, 0.48),
    "groin_tip": CubeUV(25, 66, 4.50, 1.90, 0.40),
    "front_chest_bridge": CubeUV(50, 66, 3.30, 1.90, 0.66),
    "velcro_panel": CubeUV(75, 66, 1.40, 1.20, 0.42),
    "front_chest_inner": CubeUV(100, 66, 0.75, 1.00, 0.71),
}

DEFENDER_PLAIN_MATERIALS = {
    "front_chest_side": Material(rgba(77, 78, 51), "nylon"),
    "front_mid": Material(rgba(70, 73, 47), "nylon"),
    "front_lower": Material(rgba(64, 68, 44), "nylon"),
    "rear_upper": Material(rgba(66, 70, 47), "nylon"),
    "rear_mid": Material(rgba(60, 65, 43), "nylon"),
    "rear_lower": Material(rgba(56, 61, 40), "nylon"),
    "side_wrap": Material(rgba(53, 59, 40), "soft"),
    "front_yoke": Material(rgba(88, 87, 59), "soft"),
    "rear_yoke": Material(rgba(73, 76, 51), "soft"),
    "top_bridge": Material(rgba(93, 91, 61), "soft"),
    "front_belt": Material(rgba(58, 63, 41), "webbing"),
    "rear_belt": Material(rgba(51, 57, 38), "webbing"),
    "side_belt": Material(rgba(50, 56, 38), "webbing"),
    "soft_band": Material(rgba(59, 63, 42), "soft"),
    "groin_top": Material(rgba(71, 72, 47), "nylon"),
    "groin_mid": Material(rgba(66, 68, 44), "nylon"),
    "groin_tip": Material(rgba(59, 63, 41), "nylon"),
    "front_chest_bridge": Material(rgba(69, 72, 47), "nylon"),
    "velcro_panel": Material(rgba(38, 44, 32), "velcro"),
    "front_chest_inner": Material(rgba(73, 75, 49), "nylon"),
}

DEFENDER_SPOT_MATERIALS = {
    name: Material(
        material.base,
        material.kind,
        DEFENDER_SPOT,
    )
    for name, material in DEFENDER_PLAIN_MATERIALS.items()
}
DEFENDER_SPOT_MATERIALS["soft_band"] = Material(rgba(62, 57, 43), "soft", DEFENDER_SPOT)
DEFENDER_SPOT_MATERIALS["velcro_panel"] = Material(rgba(55, 50, 39), "velcro", DEFENDER_SPOT)


DEATHLESS_CUBES = {
    "front_upper": CubeUV(0, 0, 6.96, 3.25, 0.56),
    "front_mid": CubeUV(25, 0, 7.64, 4.10, 0.64),
    "front_lower": CubeUV(50, 0, 7.76, 3.82, 0.70),
    "rear_upper": CubeUV(75, 0, 6.96, 3.25, 0.56),
    "rear_mid": CubeUV(100, 0, 7.64, 4.10, 0.64),
    "rear_lower": CubeUV(0, 22, 7.76, 3.82, 0.70),
    "side_wrap": CubeUV(25, 22, 0.40, 7.94, 4.16),
    "collar_front": CubeUV(50, 22, 3.63, 2.40, 0.52),
    "collar_back": CubeUV(75, 22, 7.96, 2.40, 0.52),
    "collar_side": CubeUV(100, 22, 0.44, 2.34, 7.66),
    "collar_root": CubeUV(0, 44, 2.75, 0.45, 1.82),
    "gold_round": CubeUV(25, 44, 0.82, 2.75, 0.80),
    "gold_cap": CubeUV(50, 44, 0.68, 0.50, 0.92),
    "red_round": CubeUV(75, 44, 0.92, 0.62, 1.00),
    "belt": CubeUV(0, 66, 7.92, 1.25, 0.40),
    "groin_top": CubeUV(25, 66, 6.50, 1.90, 0.56),
    "groin_mid": CubeUV(50, 66, 5.80, 3.20, 0.50),
    "groin_tip": CubeUV(75, 66, 4.70, 2.05, 0.42),
    "shell_seam": CubeUV(100, 66, 7.04, 0.20, 0.36),
    "shoulder_top": CubeUV(0, 88, 4.20, 0.55, 4.80),
    "shoulder_upper": CubeUV(25, 88, 4.10, 1.55, 0.46),
    "shoulder_mid": CubeUV(50, 88, 4.25, 2.35, 0.50),
    "shoulder_lower": CubeUV(75, 88, 3.55, 1.75, 0.42),
    "shoulder_outer_upper": CubeUV(100, 88, 0.48, 2.35, 4.50),
    "shoulder_outer_lower": CubeUV(0, 110, 0.42, 2.90, 4.00),
}

DEATHLESS_MATERIALS = {
    "front_upper": Material(rgba(65, 69, 69), "nylon"),
    "front_mid": Material(rgba(55, 60, 60), "nylon"),
    "front_lower": Material(rgba(48, 53, 54), "nylon"),
    "rear_upper": Material(rgba(56, 61, 61), "nylon"),
    "rear_mid": Material(rgba(48, 53, 54), "nylon"),
    "rear_lower": Material(rgba(43, 48, 49), "nylon"),
    "side_wrap": Material(rgba(40, 45, 46), "soft"),
    "collar_front": Material(rgba(72, 75, 74), "soft"),
    "collar_back": Material(rgba(61, 65, 65), "soft"),
    "collar_side": Material(rgba(57, 61, 61), "soft"),
    "collar_root": Material(rgba(62, 66, 66), "nylon"),
    "gold_round": Material(rgba(173, 124, 33), "metal"),
    "gold_cap": Material(rgba(208, 157, 54), "metal"),
    "red_round": Material(rgba(151, 42, 34), "plastic"),
    "belt": Material(rgba(42, 47, 48), "webbing"),
    "groin_top": Material(rgba(55, 59, 60), "nylon"),
    "groin_mid": Material(rgba(49, 54, 55), "nylon"),
    "groin_tip": Material(rgba(43, 48, 49), "nylon"),
    "shell_seam": Material(rgba(80, 82, 79), "webbing"),
    "shoulder_top": Material(rgba(68, 72, 72), "nylon"),
    "shoulder_upper": Material(rgba(63, 68, 68), "nylon"),
    "shoulder_mid": Material(rgba(56, 61, 62), "nylon"),
    "shoulder_lower": Material(rgba(49, 54, 55), "nylon"),
    "shoulder_outer_upper": Material(rgba(52, 57, 58), "nylon"),
    "shoulder_outer_lower": Material(rgba(45, 50, 51), "nylon"),
}


REDUT_CUBES = {
    "front_upper": CubeUV(0, 0, 6.96, 3.30, 0.56),
    "front_mid": CubeUV(25, 0, 7.64, 4.18, 0.64),
    "front_lower": CubeUV(50, 0, 7.84, 3.68, 0.70),
    "rear_upper": CubeUV(75, 0, 6.96, 3.30, 0.56),
    "rear_mid": CubeUV(100, 0, 7.64, 4.18, 0.64),
    "rear_lower": CubeUV(0, 22, 7.84, 3.68, 0.70),
    "side_wrap": CubeUV(25, 22, 0.38, 7.90, 4.20),
    "collar_front": CubeUV(50, 22, 3.25, 1.55, 0.42),
    "collar_back": CubeUV(75, 22, 7.30, 1.55, 0.42),
    "collar_side": CubeUV(100, 22, 0.40, 1.50, 6.60),
    "collar_root": CubeUV(0, 44, 2.62, 0.44, 1.30),
    "front_belt": CubeUV(25, 44, 7.96, 1.28, 0.42),
    "rear_belt": CubeUV(50, 44, 7.96, 1.28, 0.42),
    "side_belt": CubeUV(75, 44, 0.39, 1.25, 4.48),
    "skirt_upper": CubeUV(100, 44, 6.50, 1.35, 0.50),
    "skirt_mid": CubeUV(0, 66, 5.50, 2.25, 0.46),
    "skirt_lower": CubeUV(25, 66, 6.40, 2.45, 0.43),
    "skirt_tip": CubeUV(50, 66, 5.80, 1.55, 0.38),
    "shoulder_strap": CubeUV(75, 66, 1.10, 2.85, 0.34),
    "blue_buckle": CubeUV(100, 66, 0.56, 0.56, 0.35),
}

REDUT_MATERIALS = {
    "front_upper": Material(rgba(70, 85, 70), "nylon"),
    "front_mid": Material(rgba(64, 79, 65), "nylon"),
    "front_lower": Material(rgba(59, 74, 61), "nylon"),
    "rear_upper": Material(rgba(62, 76, 63), "nylon"),
    "rear_mid": Material(rgba(57, 70, 58), "nylon"),
    "rear_lower": Material(rgba(52, 66, 55), "nylon"),
    "side_wrap": Material(rgba(55, 70, 57), "soft"),
    "collar_front": Material(rgba(91, 70, 76), "soft"),
    "collar_back": Material(rgba(75, 87, 73), "soft"),
    "collar_side": Material(rgba(70, 82, 68), "soft"),
    "collar_root": Material(rgba(74, 86, 71), "soft"),
    "front_belt": Material(rgba(57, 72, 59), "webbing"),
    "rear_belt": Material(rgba(52, 66, 55), "webbing"),
    "side_belt": Material(rgba(50, 64, 53), "webbing"),
    "skirt_upper": Material(rgba(66, 80, 65), "nylon"),
    "skirt_mid": Material(rgba(62, 76, 62), "nylon"),
    "skirt_lower": Material(rgba(57, 71, 58), "nylon"),
    "skirt_tip": Material(rgba(52, 66, 54), "nylon"),
    "shoulder_strap": Material(rgba(82, 65, 69), "webbing"),
    "blue_buckle": Material(rgba(38, 91, 137), "plastic"),
}


DEFENDER_DETAILS = (
    ("front_chest_side", "north", "panel"),
    ("front_chest_inner", "north", "panel"),
    ("front_chest_bridge", "north", "panel"),
    ("front_mid", "north", "panel"),
    ("front_lower", "north", "panel"),
    ("rear_upper", "south", "panel"),
    ("rear_mid", "south", "panel"),
    ("rear_lower", "south", "panel"),
    ("side_wrap", "west", "panel"),
    ("side_wrap", "east", "panel"),
    ("front_yoke", "north", "panel"),
    ("rear_yoke", "south", "panel"),
    ("top_bridge", "up", "panel"),
    ("front_belt", "north", "dense_webbing"),
    ("rear_belt", "south", "dense_webbing"),
    ("side_belt", "west", "dense_webbing"),
    ("side_belt", "east", "dense_webbing"),
    ("soft_band", "north", "panel"),
    ("velcro_panel", "north", "dense_webbing"),
    ("groin_top", "north", "panel"),
    ("groin_mid", "north", "panel"),
    ("groin_tip", "north", "panel"),
)

DEATHLESS_DETAILS = (
    ("front_upper", "north", "panel"),
    ("front_mid", "north", "panel"),
    ("front_lower", "north", "panel"),
    ("rear_upper", "south", "panel"),
    ("rear_mid", "south", "panel"),
    ("rear_lower", "south", "panel"),
    ("side_wrap", "west", "panel"),
    ("side_wrap", "east", "panel"),
    ("collar_front", "north", "panel"),
    ("collar_back", "south", "panel"),
    ("collar_side", "west", "panel"),
    ("collar_side", "east", "panel"),
    ("gold_round", "north", "enhanced_magazine"),
    ("gold_cap", "up", "buckle"),
    ("red_round", "north", "enhanced_magazine"),
    ("belt", "north", "dense_webbing"),
    ("belt", "south", "dense_webbing"),
    ("groin_top", "north", "panel"),
    ("groin_mid", "north", "panel"),
    ("groin_tip", "north", "panel"),
    ("shell_seam", "north", "dense_webbing"),
    ("shoulder_top", "up", "panel"),
    ("shoulder_upper", "north", "panel"),
    ("shoulder_upper", "south", "panel"),
    ("shoulder_mid", "north", "panel"),
    ("shoulder_mid", "south", "panel"),
    ("shoulder_lower", "north", "panel"),
    ("shoulder_lower", "south", "panel"),
    ("shoulder_outer_upper", "west", "panel"),
    ("shoulder_outer_upper", "east", "panel"),
    ("shoulder_outer_lower", "west", "panel"),
    ("shoulder_outer_lower", "east", "panel"),
)

REDUT_DETAILS = (
    ("front_upper", "north", "panel"),
    ("front_mid", "north", "panel"),
    ("front_lower", "north", "panel"),
    ("rear_upper", "south", "panel"),
    ("rear_mid", "south", "panel"),
    ("rear_lower", "south", "panel"),
    ("side_wrap", "west", "panel"),
    ("side_wrap", "east", "panel"),
    ("collar_front", "north", "panel"),
    ("collar_back", "south", "panel"),
    ("collar_side", "west", "panel"),
    ("collar_side", "east", "panel"),
    ("front_belt", "north", "dense_webbing"),
    ("rear_belt", "south", "dense_webbing"),
    ("side_belt", "west", "dense_webbing"),
    ("side_belt", "east", "dense_webbing"),
    ("skirt_upper", "north", "panel"),
    ("skirt_mid", "north", "panel"),
    ("skirt_lower", "north", "panel"),
    ("skirt_tip", "north", "panel"),
    ("shoulder_strap", "north", "dense_webbing"),
    ("blue_buckle", "north", "buckle"),
)


SPECS = (
    ModelSpec(
        "Defender-2 Spot Camo",
        "plate_armor_defender_2_spot_camo_layer_1.png",
        Material(rgba(67, 65, 43), "multicam", DEFENDER_SPOT),
        DEFENDER_CUBES,
        DEFENDER_SPOT_MATERIALS,
        DEFENDER_DETAILS,
        {
            "front_chest_side": 2,
            "front_chest_inner": 2,
            "side_wrap": 2,
            "front_yoke": 2,
            "rear_yoke": 2,
            "top_bridge": 2,
            "side_belt": 2,
            "soft_band": 2,
        },
    ),
    ModelSpec(
        "Defender-2 Plain",
        "plate_armor_defender_2_layer_1.png",
        Material(rgba(60, 65, 43), "nylon"),
        DEFENDER_CUBES,
        DEFENDER_PLAIN_MATERIALS,
        DEFENDER_DETAILS,
        {
            "front_chest_side": 2,
            "front_chest_inner": 2,
            "side_wrap": 2,
            "front_yoke": 2,
            "rear_yoke": 2,
            "top_bridge": 2,
            "side_belt": 2,
            "soft_band": 2,
        },
    ),
    ModelSpec(
        "Gladiator-S Deathless",
        "plate_armor_gladiator_s_deathless_layer_1.png",
        Material(rgba(44, 49, 50), "nylon"),
        DEATHLESS_CUBES,
        DEATHLESS_MATERIALS,
        DEATHLESS_DETAILS,
        {
            "side_wrap": 2,
            "collar_front": 2,
            "collar_side": 2,
            "collar_root": 4,
            "gold_round": 6,
            "gold_cap": 6,
            "red_round": 4,
            "belt": 2,
            "shell_seam": 3,
            "shoulder_top": 2,
            "shoulder_upper": 4,
            "shoulder_mid": 4,
            "shoulder_lower": 4,
            "shoulder_outer_upper": 2,
            "shoulder_outer_lower": 2,
        },
    ),
    ModelSpec(
        "Redut-M",
        "plate_armor_redut_m_layer_1.png",
        Material(rgba(56, 72, 59), "nylon"),
        REDUT_CUBES,
        REDUT_MATERIALS,
        REDUT_DETAILS,
        {
            "side_wrap": 2,
            "collar_front": 2,
            "collar_side": 2,
            "collar_root": 4,
            "side_belt": 2,
            "shoulder_strap": 2,
        },
    ),
)

MODEL_FILES = {
    "Defender-2 Spot Camo": "Defender2ArmorModel.java",
    "Defender-2 Plain": "Defender2ArmorModel.java",
    "Gladiator-S Deathless": "GladiatorSDeathlessArmorModel.java",
    "Redut-M": "RedutMArmorModel.java",
}


def parse_float(value: str) -> float:
    return float(value.removesuffix("F"))


def validate_java_uv_sync(spec: ModelSpec) -> None:
    source = (CLIENT / MODEL_FILES[spec.name]).read_text(encoding="utf-8")
    matches = tuple(JAVA_BOX.finditer(source))
    if len(matches) != runtime_cube_count(spec):
        raise RuntimeError(
            f"{spec.name}: generator/model cuboid mismatch "
            f"({runtime_cube_count(spec)} != {len(matches)})"
        )

    origins = {(int(match.group(1)), int(match.group(2))) for match in matches}
    cube_origins = {(int(cube.u), int(cube.v)): name for name, cube in spec.cubes.items()}
    if len(cube_origins) != len(spec.cubes):
        raise RuntimeError(f"{spec.name}: generator UV origins must be unique")
    if origins != set(cube_origins):
        raise RuntimeError(
            f"{spec.name}: Java/generator UV origin mismatch "
            f"missing={sorted(set(cube_origins) - origins)} extra={sorted(origins - set(cube_origins))}"
        )

    counts = Counter((int(match.group(1)), int(match.group(2))) for match in matches)
    for origin, cube_name in cube_origins.items():
        expected = spec.instance_counts.get(cube_name, 1)
        if counts[origin] != expected:
            raise RuntimeError(
                f"{spec.name}.{cube_name}: Java instances={counts[origin]}, generator={expected}"
            )

    for match in matches:
        origin = int(match.group(1)), int(match.group(2))
        cube_name = cube_origins[origin]
        width, height, depth = (parse_float(value) for value in match.groups()[5:8])
        cube = spec.cubes[cube_name]
        if width > cube.width + 1.0e-6 or height > cube.height + 1.0e-6 or depth > cube.depth + 1.0e-6:
            raise RuntimeError(
                f"{spec.name}.{cube_name}: Java {width, height, depth} exceeds "
                f"UV envelope {(cube.width, cube.height, cube.depth)}"
            )


def apply_reference_details(image: Image.Image, spec: ModelSpec) -> None:
    draw = ImageDraw.Draw(image)
    if spec.name.startswith("Defender-2"):
        material = spec.materials["groin_mid"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["groin_mid"])["north"])
        center = x0 + max(0, (x1 - x0 - 1) // 2)
        draw.line((center, y0 + 1, center, y1 - 1), fill=shade(material.base, -22))

        # The previous generic patch painter introduced pure-white blocks. Repaint the
        # panel as low-contrast hook-and-loop cells derived from each colorway.
        velcro = spec.materials["velcro_panel"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["velcro_panel"])["north"])
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=shade(velcro.base, -5), outline=shade(velcro.base, -16))
        if x1 - x0 >= 3 and y1 - y0 >= 2:
            for index, delta in enumerate((5, -3, 7)):
                cell_x = x0 + 1 + index * max(1, (x1 - x0 - 2) // 3)
                if cell_x < x1 - 1:
                    draw.line((cell_x, y0 + 1, cell_x, y1 - 2), fill=shade(velcro.base, delta))
    elif spec.name == "Gladiator-S Deathless":
        material = spec.materials["gold_round"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["gold_round"])["north"])
        highlight = min(x1 - 1, x0 + 1)
        draw.line((highlight, y0, highlight, y1 - 1), fill=shade(material.base, 38))
        red = spec.materials["red_round"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["red_round"])["north"])
        draw.line((x0, y1 - 1, x1 - 1, y0), fill=shade(red.base, 26))
    elif spec.name == "Redut-M":
        for cube_name in ("skirt_mid", "skirt_lower", "skirt_tip"):
            material = spec.materials[cube_name]
            x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes[cube_name])["north"])
            for x in (x0 + max(1, (x1 - x0) // 4), x0 + max(1, 3 * (x1 - x0) // 4)):
                if x < x1:
                    for y in range(y0, y1, 2):
                        draw.point((x, y), fill=shade(material.base, -20))


def build_batch_texture(spec: ModelSpec) -> Image.Image:
    image = build_texture(spec)
    apply_reference_details(image, spec)
    return image


def write_texture(spec: ModelSpec) -> None:
    validate_uvs(spec)
    validate_java_uv_sync(spec)
    image = build_batch_texture(spec)
    if image.tobytes() != build_batch_texture(spec).tobytes():
        raise RuntimeError(f"{spec.name}: texture generation is not deterministic")
    output = OUTPUT_DIR / spec.output_name
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG", optimize=False)
    with Image.open(output) as written:
        if written.mode != "RGBA" or written.size != (SIZE, SIZE):
            raise RuntimeError(f"{spec.name}: output must be a 128x128 RGBA PNG")
        if written.getchannel("A").getextrema() != (255, 255):
            raise RuntimeError(f"{spec.name}: output alpha must remain fully opaque")
        colors = written.getcolors(maxcolors=SIZE * SIZE)
        if colors is None or len(colors) < 48:
            raise RuntimeError(f"{spec.name}: material texture lost surface variation")
    digest = sha256(output.read_bytes()).hexdigest().upper()
    print(
        f"{output.name} cubes={runtime_cube_count(spec)} colors={len(colors)} "
        f"sha256={digest} uv=semantic alpha=255"
    )


def main() -> None:
    expected_counts = {
        "Defender-2 Spot Camo": 28,
        "Defender-2 Plain": 28,
        "Gladiator-S Deathless": 59,
        "Redut-M": 28,
    }
    for spec in SPECS:
        actual = runtime_cube_count(spec)
        if actual != expected_counts[spec.name]:
            raise RuntimeError(f"{spec.name}: expected {expected_counts[spec.name]} cuboids, got {actual}")
        write_texture(spec)


if __name__ == "__main__":
    main()
