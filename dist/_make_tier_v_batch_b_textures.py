from hashlib import sha256

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


GLADIATOR_MULTICAM = tuple(
    rgba(*color)
    for color in (
        (35, 43, 31),
        (54, 65, 39),
        (77, 77, 49),
        (103, 88, 56),
        (145, 126, 82),
        (72, 50, 33),
        (28, 34, 27),
    )
)

HEXATAC_BLACK_CAMO = tuple(
    rgba(*color)
    for color in (
        (10, 12, 12),
        (19, 22, 22),
        (30, 34, 33),
        (43, 47, 44),
        (56, 58, 54),
    )
)

B6B45_FLORA = tuple(
    rgba(*color)
    for color in (
        (38, 43, 31),
        (53, 59, 39),
        (68, 69, 45),
        (83, 78, 53),
        (99, 89, 61),
        (48, 42, 31),
    )
)


GLADIATOR_CUBES = {
    "front_upper": CubeUV(0, 0, 6.70, 3.65, 0.50),
    "front_lower": CubeUV(25, 0, 7.72, 7.55, 0.56),
    "rear_upper": CubeUV(50, 0, 6.70, 3.65, 0.50),
    "rear_lower": CubeUV(75, 0, 7.72, 7.55, 0.56),
    "side_wrap": CubeUV(100, 0, 0.45, 7.70, 4.10),
    "front_yoke": CubeUV(0, 22, 1.35, 4.20, 0.42),
    "rear_yoke": CubeUV(25, 22, 1.35, 4.20, 0.42),
    "top_bridge": CubeUV(50, 22, 1.29, 0.55, 4.92),
    "upper_pouch": CubeUV(75, 22, 2.25, 2.65, 0.96),
    "upper_lid": CubeUV(100, 22, 2.15, 0.82, 0.35),
    "main_pouch": CubeUV(0, 44, 1.20, 4.15, 1.02),
    "main_lid": CubeUV(25, 44, 1.14, 0.86, 0.34),
    "left_round_bag": CubeUV(50, 44, 2.60, 4.90, 1.22),
    "left_round_lid": CubeUV(75, 44, 2.70, 1.20, 0.35),
    "groin_top": CubeUV(100, 44, 6.20, 1.70, 0.58),
    "groin_mid": CubeUV(0, 66, 5.40, 2.40, 0.50),
    "groin_tip": CubeUV(25, 66, 3.60, 1.75, 0.42),
    "molle": CubeUV(50, 66, 6.00, 0.20, 0.38),
    "belt": CubeUV(75, 66, 7.50, 0.86, 0.38),
    "pull": CubeUV(100, 66, 0.22, 2.30, 0.38),
    "right_group_base": CubeUV(0, 88, 1.70, 4.70, 1.05),
    "right_group_lid": CubeUV(8, 88, 1.70, 1.00, 0.35),
    "left_round_step": CubeUV(15, 88, 2.10, 0.72, 0.92),
    "right_upper_pocket": CubeUV(24, 88, 0.72, 1.85, 0.45),
    "right_upper_lid": CubeUV(29, 88, 0.80, 0.45, 0.22),
    "right_lower_pocket": CubeUV(34, 88, 0.82, 2.35, 0.48),
    "right_lower_lid": CubeUV(39, 88, 0.90, 0.48, 0.22),
    "groin_lower": CubeUV(47, 88, 4.50, 2.15, 0.46),
}

GLADIATOR_MATERIALS = {
    "front_upper": Material(rgba(81, 78, 54), "trooper_multicam", GLADIATOR_MULTICAM),
    "front_lower": Material(rgba(73, 72, 49), "trooper_multicam", GLADIATOR_MULTICAM),
    "rear_upper": Material(rgba(62, 65, 45), "trooper_multicam", GLADIATOR_MULTICAM),
    "rear_lower": Material(rgba(57, 61, 42), "trooper_multicam", GLADIATOR_MULTICAM),
    "side_wrap": Material(rgba(56, 60, 42), "trooper_multicam", GLADIATOR_MULTICAM),
    "front_yoke": Material(rgba(96, 88, 62), "trooper_multicam", GLADIATOR_MULTICAM),
    "rear_yoke": Material(rgba(74, 72, 51), "trooper_multicam", GLADIATOR_MULTICAM),
    "top_bridge": Material(rgba(107, 95, 68), "trooper_multicam", GLADIATOR_MULTICAM),
    "upper_pouch": Material(rgba(69, 69, 48), "trooper_multicam", GLADIATOR_MULTICAM),
    "upper_lid": Material(rgba(89, 82, 57), "webbing", GLADIATOR_MULTICAM),
    "main_pouch": Material(rgba(65, 65, 44), "trooper_multicam", GLADIATOR_MULTICAM),
    "main_lid": Material(rgba(91, 82, 56), "webbing", GLADIATOR_MULTICAM),
    "left_round_bag": Material(rgba(59, 61, 42), "trooper_multicam", GLADIATOR_MULTICAM),
    "left_round_lid": Material(rgba(83, 77, 53), "webbing", GLADIATOR_MULTICAM),
    "groin_top": Material(rgba(72, 70, 48), "trooper_multicam", GLADIATOR_MULTICAM),
    "groin_mid": Material(rgba(66, 65, 44), "trooper_multicam", GLADIATOR_MULTICAM),
    "groin_tip": Material(rgba(59, 59, 41), "trooper_multicam", GLADIATOR_MULTICAM),
    "molle": Material(rgba(57, 60, 42), "webbing", GLADIATOR_MULTICAM),
    "belt": Material(rgba(50, 54, 39), "webbing", GLADIATOR_MULTICAM),
    "pull": Material(rgba(43, 47, 36), "webbing"),
    "right_group_base": Material(rgba(67, 63, 43), "trooper_multicam", GLADIATOR_MULTICAM),
    "right_group_lid": Material(rgba(92, 81, 55), "webbing", GLADIATOR_MULTICAM),
    "left_round_step": Material(rgba(55, 58, 39), "trooper_multicam", GLADIATOR_MULTICAM),
    "right_upper_pocket": Material(rgba(79, 70, 47), "trooper_multicam", GLADIATOR_MULTICAM),
    "right_upper_lid": Material(rgba(105, 91, 61), "webbing", GLADIATOR_MULTICAM),
    "right_lower_pocket": Material(rgba(61, 68, 43), "trooper_multicam", GLADIATOR_MULTICAM),
    "right_lower_lid": Material(rgba(88, 80, 53), "webbing", GLADIATOR_MULTICAM),
    "groin_lower": Material(rgba(62, 63, 42), "trooper_multicam", GLADIATOR_MULTICAM),
}


HEXATAC_CUBES = {
    "front_upper": CubeUV(0, 0, 2.85, 3.58, 0.42),
    "front_lower": CubeUV(25, 0, 7.20, 6.92, 0.50),
    "rear_upper": CubeUV(50, 0, 2.85, 3.58, 0.42),
    "rear_lower": CubeUV(75, 0, 7.20, 6.92, 0.50),
    "front_yoke": CubeUV(100, 0, 1.55, 4.32, 0.38),
    "rear_yoke": CubeUV(0, 22, 1.55, 4.32, 0.38),
    "top_bridge": CubeUV(25, 22, 1.49, 0.56, 4.76),
    "side_rail": CubeUV(50, 22, 0.38, 1.00, 4.10),
    "side_connector": CubeUV(75, 22, 0.22, 2.72, 0.30),
    "front_patch": CubeUV(100, 22, 5.60, 2.05, 0.40),
    "lower_flap": CubeUV(0, 44, 6.30, 2.48, 0.35),
    "molle": CubeUV(25, 44, 5.60, 0.18, 0.24),
    "hem": CubeUV(50, 44, 7.12, 0.52, 0.35),
    "buckle": CubeUV(75, 44, 0.30, 0.70, 0.96),
}

HEXATAC_MATERIALS = {
    "front_upper": Material(rgba(31, 34, 33), "black_camo", HEXATAC_BLACK_CAMO),
    "front_lower": Material(rgba(26, 29, 28), "black_camo", HEXATAC_BLACK_CAMO),
    "rear_upper": Material(rgba(22, 25, 24), "black_camo", HEXATAC_BLACK_CAMO),
    "rear_lower": Material(rgba(19, 22, 21), "black_camo", HEXATAC_BLACK_CAMO),
    "front_yoke": Material(rgba(28, 31, 30), "mesh"),
    "rear_yoke": Material(rgba(24, 27, 26), "mesh"),
    "top_bridge": Material(rgba(34, 37, 35), "mesh"),
    "side_rail": Material(rgba(27, 30, 29), "webbing", HEXATAC_BLACK_CAMO),
    "side_connector": Material(rgba(20, 23, 22), "webbing"),
    "front_patch": Material(rgba(24, 27, 26), "velcro"),
    "lower_flap": Material(rgba(20, 23, 22), "velcro"),
    "molle": Material(rgba(36, 39, 37), "webbing", HEXATAC_BLACK_CAMO),
    "hem": Material(rgba(18, 21, 20), "webbing"),
    "buckle": Material(rgba(25, 29, 28), "plastic"),
}


B6B45_CUBES = {
    "front_upper": CubeUV(0, 0, 6.80, 3.35, 0.52),
    "front_lower": CubeUV(25, 0, 7.70, 7.90, 0.58),
    "rear_upper": CubeUV(50, 0, 6.80, 3.35, 0.52),
    "rear_lower": CubeUV(75, 0, 7.70, 7.90, 0.58),
    "side_wrap": CubeUV(100, 0, 0.45, 8.20, 4.10),
    "collar_front": CubeUV(0, 22, 3.75, 2.28, 0.48),
    "collar_back": CubeUV(25, 22, 8.60, 2.23, 0.48),
    "collar_side": CubeUV(50, 22, 0.48, 2.33, 8.26),
    "medical": CubeUV(75, 22, 3.50, 5.15, 1.28),
    "medical_flap": CubeUV(100, 22, 3.40, 1.32, 0.35),
    "medical_patch": CubeUV(0, 44, 1.20, 1.20, 0.30),
    "right_pouch": CubeUV(25, 44, 1.50, 4.55, 1.00),
    "point_lid": CubeUV(50, 44, 1.44, 1.30, 0.35),
    "pull": CubeUV(75, 44, 0.22, 2.30, 0.30),
    "radio": CubeUV(100, 44, 1.25, 3.70, 1.00),
    "radio_lid": CubeUV(0, 66, 1.17, 0.82, 0.32),
    "antenna": CubeUV(25, 66, 0.22, 3.20, 0.22),
    "molle": CubeUV(50, 66, 5.60, 0.20, 0.32),
    "tourniquet": CubeUV(75, 66, 4.40, 0.52, 0.64),
    "clasp": CubeUV(100, 66, 0.64, 0.64, 0.28),
    "hem": CubeUV(0, 88, 7.60, 1.20, 0.42),
    "belt": CubeUV(25, 88, 7.52, 0.76, 0.34),
    "shoulder_top": CubeUV(50, 88, 2.25, 0.55, 4.30),
    "shoulder_lip": CubeUV(75, 88, 0.45, 1.55, 4.00),
    "collar_root": CubeUV(100, 88, 2.70, 0.88, 2.22),
    "left_step_bag": CubeUV(0, 100, 1.42, 3.10, 1.02),
    "left_step_lid": CubeUV(7, 100, 1.34, 0.82, 0.32),
    "left_step_round": CubeUV(13, 100, 1.16, 0.62, 0.86),
    "left_step_pull": CubeUV(19, 100, 0.22, 1.90, 0.20),
}

B6B45_MATERIALS = {
    "front_upper": Material(rgba(66, 68, 45), "multicam", B6B45_FLORA),
    "front_lower": Material(rgba(59, 62, 41), "multicam", B6B45_FLORA),
    "rear_upper": Material(rgba(54, 58, 39), "multicam", B6B45_FLORA),
    "rear_lower": Material(rgba(48, 53, 36), "multicam", B6B45_FLORA),
    "side_wrap": Material(rgba(51, 56, 38), "multicam", B6B45_FLORA),
    "collar_front": Material(rgba(76, 74, 49), "soft", B6B45_FLORA),
    "collar_back": Material(rgba(66, 67, 45), "soft", B6B45_FLORA),
    "collar_side": Material(rgba(62, 64, 43), "soft", B6B45_FLORA),
    "medical": Material(rgba(61, 63, 42), "multicam", B6B45_FLORA),
    "medical_flap": Material(rgba(78, 74, 50), "webbing", B6B45_FLORA),
    "medical_patch": Material(rgba(102, 100, 77), "velcro"),
    "right_pouch": Material(rgba(58, 61, 40), "multicam", B6B45_FLORA),
    "point_lid": Material(rgba(77, 73, 49), "webbing", B6B45_FLORA),
    "pull": Material(rgba(47, 49, 35), "webbing"),
    "radio": Material(rgba(44, 48, 38), "plastic"),
    "radio_lid": Material(rgba(64, 65, 45), "webbing", B6B45_FLORA),
    "antenna": Material(rgba(25, 29, 27), "plastic"),
    "molle": Material(rgba(52, 56, 38), "webbing", B6B45_FLORA),
    "tourniquet": Material(rgba(36, 39, 34), "webbing"),
    "clasp": Material(rgba(128, 43, 34), "plastic"),
    "hem": Material(rgba(52, 56, 38), "webbing", B6B45_FLORA),
    "belt": Material(rgba(45, 49, 35), "webbing", B6B45_FLORA),
    "shoulder_top": Material(rgba(72, 71, 48), "multicam", B6B45_FLORA),
    "shoulder_lip": Material(rgba(58, 61, 41), "multicam", B6B45_FLORA),
    "collar_root": Material(rgba(70, 69, 47), "multicam", B6B45_FLORA),
    "left_step_bag": Material(rgba(55, 61, 40), "multicam", B6B45_FLORA),
    "left_step_lid": Material(rgba(81, 76, 49), "webbing", B6B45_FLORA),
    "left_step_round": Material(rgba(61, 66, 43), "multicam", B6B45_FLORA),
    "left_step_pull": Material(rgba(42, 46, 33), "webbing"),
}


SPECS = (
    ModelSpec(
        "Gladiator-S Light Multicam",
        "plate_armor_gladiator_s_light_multicam_layer_1.png",
        Material(rgba(52, 55, 39), "trooper_multicam", GLADIATOR_MULTICAM),
        GLADIATOR_CUBES,
        GLADIATOR_MATERIALS,
        (
            ("front_upper", "north", "panel"),
            ("front_lower", "north", "panel"),
            ("rear_upper", "south", "panel"),
            ("rear_lower", "south", "panel"),
            ("side_wrap", "west", "panel"),
            ("side_wrap", "east", "panel"),
            ("front_yoke", "north", "panel"),
            ("rear_yoke", "south", "panel"),
            ("top_bridge", "up", "panel"),
            ("upper_pouch", "north", "enhanced_pouch"),
            ("upper_lid", "north", "enhanced_pouch"),
            ("main_pouch", "north", "enhanced_pouch"),
            ("main_lid", "north", "enhanced_pouch"),
            ("left_round_bag", "north", "enhanced_pouch"),
            ("left_round_lid", "north", "enhanced_pouch"),
            ("left_round_step", "north", "enhanced_pouch"),
            ("right_group_base", "north", "enhanced_pouch"),
            ("right_group_lid", "north", "enhanced_pouch"),
            ("right_upper_pocket", "north", "enhanced_pouch"),
            ("right_upper_lid", "north", "enhanced_pouch"),
            ("right_lower_pocket", "north", "enhanced_pouch"),
            ("right_lower_lid", "north", "enhanced_pouch"),
            ("groin_top", "north", "panel"),
            ("groin_mid", "north", "panel"),
            ("groin_lower", "north", "panel"),
            ("groin_tip", "north", "panel"),
            ("molle", "north", "dense_webbing"),
            ("belt", "north", "dense_webbing"),
            ("belt", "south", "dense_webbing"),
            ("pull", "north", "webbing"),
        ),
        {
            "side_wrap": 2,
            "front_yoke": 2,
            "rear_yoke": 2,
            "top_bridge": 2,
            "upper_pouch": 2,
            "upper_lid": 2,
            "main_pouch": 4,
            "main_lid": 4,
            "molle": 3,
            "belt": 2,
            "pull": 6,
        },
    ),
    ModelSpec(
        "Hexatac HPC Black Multicam",
        "plate_armor_hexatac_hpc_black_multicam_layer_1.png",
        Material(rgba(17, 20, 19), "black_camo", HEXATAC_BLACK_CAMO),
        HEXATAC_CUBES,
        HEXATAC_MATERIALS,
        (
            ("front_upper", "north", "panel"),
            ("front_lower", "north", "panel"),
            ("rear_upper", "south", "panel"),
            ("rear_lower", "south", "panel"),
            ("front_yoke", "north", "mesh"),
            ("rear_yoke", "south", "mesh"),
            ("top_bridge", "up", "mesh"),
            ("side_rail", "west", "webbing"),
            ("side_rail", "east", "webbing"),
            ("side_connector", "west", "webbing"),
            ("side_connector", "east", "webbing"),
            ("front_patch", "north", "patch"),
            ("lower_flap", "north", "panel"),
            ("molle", "north", "dense_webbing"),
            ("hem", "north", "dense_webbing"),
            ("hem", "south", "dense_webbing"),
            ("buckle", "west", "buckle"),
            ("buckle", "east", "buckle"),
        ),
        {
            "front_upper": 2,
            "rear_upper": 2,
            "front_yoke": 2,
            "rear_yoke": 2,
            "top_bridge": 2,
            "side_rail": 4,
            "side_connector": 6,
            "molle": 4,
            "hem": 2,
            "buckle": 4,
        },
    ),
    ModelSpec(
        "6B45 General",
        "plate_armor_6b45_general_layer_1.png",
        Material(rgba(49, 53, 36), "multicam", B6B45_FLORA),
        B6B45_CUBES,
        B6B45_MATERIALS,
        (
            ("front_upper", "north", "panel"),
            ("front_lower", "north", "panel"),
            ("rear_upper", "south", "panel"),
            ("rear_lower", "south", "panel"),
            ("side_wrap", "west", "panel"),
            ("side_wrap", "east", "panel"),
            ("collar_front", "north", "panel"),
            ("collar_back", "south", "panel"),
            ("collar_side", "west", "panel"),
            ("collar_side", "east", "panel"),
            ("medical", "north", "enhanced_pouch"),
            ("medical_flap", "north", "enhanced_pouch"),
            ("medical_patch", "north", "medical_patch"),
            ("right_pouch", "north", "enhanced_pouch"),
            ("point_lid", "north", "enhanced_pouch"),
            ("pull", "north", "webbing"),
            ("radio", "north", "radio"),
            ("radio_lid", "north", "enhanced_pouch"),
            ("antenna", "north", "radio"),
            ("molle", "north", "dense_webbing"),
            ("tourniquet", "north", "tourniquet"),
            ("clasp", "north", "buckle"),
            ("hem", "north", "dense_webbing"),
            ("hem", "south", "dense_webbing"),
            ("belt", "north", "dense_webbing"),
            ("belt", "south", "dense_webbing"),
            ("shoulder_top", "up", "panel"),
            ("shoulder_lip", "west", "panel"),
            ("shoulder_lip", "east", "panel"),
            ("collar_root", "north", "panel"),
            ("collar_root", "south", "panel"),
            ("left_step_bag", "north", "enhanced_pouch"),
            ("left_step_lid", "north", "enhanced_pouch"),
            ("left_step_round", "north", "enhanced_pouch"),
            ("left_step_pull", "north", "webbing"),
        ),
        {
            "side_wrap": 2,
            "collar_front": 2,
            "collar_side": 2,
            "right_pouch": 2,
            "point_lid": 2,
            "pull": 2,
            "molle": 2,
            "hem": 2,
            "belt": 2,
            "shoulder_top": 2,
            "shoulder_lip": 2,
            "collar_root": 4,
        },
    ),
)


def apply_reference_details(image: Image.Image, spec: ModelSpec) -> None:
    draw = ImageDraw.Draw(image)
    if spec.name == "Gladiator-S Light Multicam":
        for cube_name in ("groin_top", "groin_mid", "groin_lower", "groin_tip"):
            material = spec.materials[cube_name]
            x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes[cube_name])["north"])
            center = x0 + max(0, (x1 - x0 - 1) // 2)
            draw.line((center, y0, center, y1 - 1), fill=shade(material.base, -22))
    elif spec.name == "Hexatac HPC Black Multicam":
        material = spec.materials["buckle"]
        for face in cube_faces(spec.cubes["buckle"]).values():
            x0, y0, x1, y1 = pixel_bounds(face)
            if x1 > x0 and y1 > y0:
                draw.rectangle(
                    (x0, y0, x1 - 1, y1 - 1),
                    fill=shade(material.base, -2),
                    outline=shade(material.base, 8),
                )
        patch_material = spec.materials["front_patch"]
        for face in cube_faces(spec.cubes["front_patch"]).values():
            x0, y0, x1, y1 = pixel_bounds(face)
            if x1 > x0 and y1 > y0:
                draw.rectangle(
                    (x0, y0, x1 - 1, y1 - 1),
                    fill=shade(patch_material.base, -4),
                    outline=shade(patch_material.base, 4),
                )
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["front_patch"])["north"])
        for x in (x0 + 1, x1 - 3):
            if x0 <= x < x1:
                draw.line((x, y0, x, y1 - 1), fill=shade(material.base, 8))
    elif spec.name == "6B45 General":
        material = spec.materials["point_lid"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["point_lid"])["north"])
        center = x0 + max(0, (x1 - x0 - 1) // 2)
        draw.line((x0, y0, center, y1 - 1), fill=shade(material.base, -28))
        draw.line((center, y1 - 1, x1 - 1, y0), fill=shade(material.base, -28))
        flap = spec.materials["medical_flap"]
        x0, y0, x1, y1 = pixel_bounds(cube_faces(spec.cubes["medical_flap"])["north"])
        draw.line((x0, y0, x1 - 1, y0), fill=shade(flap.base, 22))
        draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=shade(flap.base, -30))


def build_batch_texture(spec: ModelSpec) -> Image.Image:
    image = build_texture(spec)
    apply_reference_details(image, spec)
    return image


def write_texture(spec: ModelSpec) -> None:
    validate_uvs(spec)
    image = build_batch_texture(spec)
    if image.tobytes() != build_batch_texture(spec).tobytes():
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
        if colors is None or len(colors) < 40:
            raise RuntimeError(f"{spec.name}: material texture lost surface variation")
    digest = sha256(output.read_bytes()).hexdigest().upper()
    print(f"{output.name} cubes={runtime_cube_count(spec)} colors={len(colors)} sha256={digest} uv=unique alpha=255")


def main() -> None:
    expected_counts = {
        "Gladiator-S Light Multicam": 48,
        "Hexatac HPC Black Multicam": 34,
        "6B45 General": 43,
    }
    for spec in SPECS:
        actual = runtime_cube_count(spec)
        if actual != expected_counts[spec.name]:
            raise RuntimeError(f"{spec.name}: expected {expected_counts[spec.name]} cuboids, got {actual}")
        write_texture(spec)


if __name__ == "__main__":
    main()
