from _make_tier_v_batch_a_textures import (
    Material,
    CubeUV,
    TextureSpec,
    rgba,
    write_texture,
)
import _make_tier_v_batch_a_textures as texture_base
from PIL import ImageDraw


VIKING_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 7.20, 8.70, 0.55),
    "rear_plate": CubeUV(42, 0, 7.20, 8.70, 0.55),
    "side_panel": CubeUV(59, 0, 0.40, 6.40, 4.00),
    "front_yoke": CubeUV(69, 0, 1.50, 3.50, 0.55),
    "rear_yoke": CubeUV(74, 0, 1.50, 3.50, 0.55),
    "bridge": CubeUV(79, 0, 1.20, 0.55, 4.50),
    "admin": CubeUV(92, 0, 5.80, 1.00, 0.22),
    "molle": CubeUV(105, 0, 6.20, 0.18, 0.28),
    "buckle": CubeUV(119, 0, 0.60, 0.65, 0.22),
    "left_bag": CubeUV(0, 20, 1.95, 4.60, 1.40),
    "left_lid": CubeUV(8, 20, 2.15, 1.00, 1.55),
    "right_utility": CubeUV(17, 20, 1.95, 4.60, 1.40),
    "right_lid": CubeUV(25, 20, 2.15, 1.00, 1.55),
    "mag_pouch": CubeUV(34, 20, 1.45, 2.70, 0.72),
    "mag_lid": CubeUV(40, 20, 1.55, 0.80, 0.25),
    "antenna": CubeUV(45, 20, 0.20, 4.00, 0.20),
    "antenna_base": CubeUV(47, 20, 0.55, 0.70, 0.45),
    "bottom_pouch": CubeUV(51, 20, 5.00, 4.10, 0.95),
    "bottom_lid": CubeUV(64, 20, 5.20, 1.00, 0.35),
    "emblem_patch": CubeUV(77, 20, 2.40, 1.30, 0.35),
    "bottom_seam": CubeUV(84, 20, 0.22, 2.50, 0.18),
    "mag_round_tab": CubeUV(0, 40, 0.85, 0.45, 0.20),
}

VIKING_CAMO = tuple(map(lambda color: rgba(*color), (
    (42, 43, 39), (58, 57, 49), (76, 70, 58), (94, 82, 65), (52, 58, 51)
)))
VIKING_MATERIALS = {
    name: Material(rgba(52, 57, 51), "black_camo", VIKING_CAMO)
    for name in VIKING_CUBES
}
VIKING_MATERIALS.update({
    "carrier": Material(rgba(29, 34, 32), "mesh"),
    "side_panel": Material(rgba(37, 42, 38), "webbing"),
    "front_yoke": Material(rgba(74, 75, 64), "soft"),
    "rear_yoke": Material(rgba(55, 60, 53), "soft"),
    "bridge": Material(rgba(67, 68, 58), "soft"),
    "admin": Material(rgba(28, 31, 30), "webbing"),
    "molle": Material(rgba(45, 50, 44), "webbing"),
    "buckle": Material(rgba(72, 75, 70), "plastic"),
    "left_bag": Material(rgba(52, 58, 50), "black_camo", VIKING_CAMO),
    "left_lid": Material(rgba(66, 69, 58), "black_camo", VIKING_CAMO),
    "right_utility": Material(rgba(43, 49, 44), "black_camo", VIKING_CAMO),
    "right_lid": Material(rgba(60, 64, 55), "black_camo", VIKING_CAMO),
    "mag_pouch": Material(rgba(55, 59, 52), "black_camo", VIKING_CAMO),
    "mag_lid": Material(rgba(70, 70, 59), "webbing"),
    "antenna": Material(rgba(23, 25, 24), "plastic"),
    "antenna_base": Material(rgba(41, 45, 41), "plastic"),
    "bottom_pouch": Material(rgba(37, 40, 38), "soft"),
    "bottom_lid": Material(rgba(52, 55, 50), "soft"),
    "emblem_patch": Material(rgba(25, 27, 26), "webbing"),
    "bottom_seam": Material(rgba(72, 73, 67), "webbing"),
    "mag_round_tab": Material(rgba(88, 78, 61), "webbing"),
})


TT_CUBES = {
    "carrier": CubeUV(0, 0, 8.0, 12.0, 4.0),
    "front_plate": CubeUV(25, 0, 6.90, 8.70, 0.55),
    "rear_plate": CubeUV(42, 0, 6.90, 8.70, 0.55),
    "side_panel": CubeUV(59, 0, 0.36, 6.00, 4.00),
    "front_yoke": CubeUV(69, 0, 1.60, 3.60, 0.65),
    "rear_yoke": CubeUV(75, 0, 1.60, 3.60, 0.65),
    "top_pad": CubeUV(81, 0, 1.40, 0.65, 4.40),
    "admin": CubeUV(93, 0, 5.60, 1.05, 0.22),
    "molle": CubeUV(105, 0, 6.00, 0.18, 0.42),
    "buckle": CubeUV(119, 0, 0.60, 0.65, 0.22),
    "left_bag": CubeUV(0, 20, 2.00, 4.60, 1.35),
    "left_lid": CubeUV(8, 20, 2.20, 1.00, 1.50),
    "cylinder": CubeUV(17, 20, 0.45, 3.80, 0.45),
    "cylinder_strap": CubeUV(20, 20, 0.50, 0.35, 0.18),
    "right_utility": CubeUV(23, 20, 1.75, 4.20, 1.25),
    "right_lid": CubeUV(30, 20, 1.95, 1.00, 1.40),
    "tool": CubeUV(38, 20, 0.45, 3.60, 0.45),
    "tool_strap": CubeUV(41, 20, 0.44, 0.40, 0.18),
    "flat_pouch": CubeUV(44, 20, 1.75, 3.00, 0.62),
    "flat_lid": CubeUV(50, 20, 1.85, 0.75, 0.22),
    "cable": CubeUV(56, 20, 0.20, 2.80, 0.20),
    "blue_ring": CubeUV(4, 40, 0.42, 0.42, 0.25),
}

TT_COYOTE = tuple(map(lambda color: rgba(*color), (
    (122, 91, 57), (143, 108, 70), (162, 127, 85), (181, 147, 100), (107, 80, 52)
)))
TT_MATERIALS = {
    name: Material(rgba(148, 113, 75), "nylon", TT_COYOTE)
    for name in TT_CUBES
}
TT_MATERIALS.update({
    "carrier": Material(rgba(91, 72, 52), "mesh"),
    "side_panel": Material(rgba(105, 80, 55), "webbing"),
    "front_yoke": Material(rgba(174, 138, 94), "soft"),
    "rear_yoke": Material(rgba(149, 115, 77), "soft"),
    "top_pad": Material(rgba(181, 145, 98), "soft"),
    "admin": Material(rgba(112, 84, 57), "webbing"),
    "molle": Material(rgba(126, 95, 63), "webbing"),
    "buckle": Material(rgba(64, 64, 60), "plastic"),
    "left_bag": Material(rgba(112, 112, 78), "nylon"),
    "left_lid": Material(rgba(132, 126, 86), "webbing"),
    "cylinder": Material(rgba(53, 64, 50), "plastic"),
    "cylinder_strap": Material(rgba(44, 49, 41), "webbing"),
    "right_utility": Material(rgba(139, 104, 67), "nylon", TT_COYOTE),
    "right_lid": Material(rgba(163, 128, 84), "nylon", TT_COYOTE),
    "tool": Material(rgba(38, 42, 39), "plastic"),
    "tool_strap": Material(rgba(53, 49, 42), "webbing"),
    "flat_pouch": Material(rgba(149, 112, 73), "nylon", TT_COYOTE),
    "flat_lid": Material(rgba(170, 133, 88), "webbing"),
    "cable": Material(rgba(29, 32, 31), "plastic"),
    "blue_ring": Material(rgba(37, 91, 142), "plastic"),
})


OSPREY_CUBES = {
    "front_upper": CubeUV(0, 0, 6.80, 3.00, 0.50),
    "rear_upper": CubeUV(16, 0, 6.80, 3.00, 0.50),
    "front_middle": CubeUV(32, 0, 8.00, 4.00, 0.55),
    "rear_middle": CubeUV(51, 0, 8.00, 4.00, 0.55),
    "front_lower": CubeUV(70, 0, 7.90, 3.60, 0.68),
    "rear_lower": CubeUV(89, 0, 7.90, 3.60, 0.58),
    "side_panel": CubeUV(108, 0, 0.32, 7.55, 4.00),
    "collar_front": CubeUV(0, 15, 3.25, 1.70, 0.45),
    "collar_rear": CubeUV(16, 15, 7.40, 1.70, 0.45),
    "collar_side": CubeUV(33, 15, 0.40, 1.70, 7.20),
    "collar_root": CubeUV(50, 15, 2.60, 0.40, 1.50),
    "molle": CubeUV(61, 15, 6.50, 0.18, 0.32),
    "radio": CubeUV(76, 15, 1.20, 2.20, 0.82),
    "radio_lid": CubeUV(81, 15, 1.36, 0.65, 0.25),
    "mag_pouch": CubeUV(0, 25, 1.55, 3.00, 0.82),
    "mag_insert": CubeUV(6, 25, 1.25, 1.50, 0.45),
    "mag_elastic": CubeUV(11, 25, 1.61, 0.40, 0.20),
    "lacing": CubeUV(16, 25, 0.22, 4.00, 0.62),
    "lower_belt": CubeUV(18, 25, 7.80, 1.80, 0.55),
    "pull": CubeUV(36, 25, 1.50, 0.70, 0.35),
    "shoulder_top": CubeUV(0, 40, 3.70, 0.55, 4.60),
    "shoulder_outer": CubeUV(21, 40, 0.50, 5.00, 4.20),
    "shoulder_front": CubeUV(33, 40, 4.10, 3.40, 0.42),
    "shoulder_rear": CubeUV(45, 40, 3.70, 2.60, 0.42),
    "left_tool_bag": CubeUV(0, 60, 2.10, 4.40, 1.25),
    "left_tool_lid": CubeUV(9, 60, 2.20, 0.90, 1.40),
    "left_tool_step": CubeUV(19, 60, 1.70, 0.65, 1.05),
    "right_side_bag": CubeUV(27, 60, 1.55, 3.60, 0.88),
    "right_side_lid": CubeUV(34, 60, 1.65, 0.75, 0.35),
    "right_side_seam": CubeUV(40, 60, 0.90, 2.00, 0.28),
}

MULTICAM = tuple(map(lambda color: rgba(*color), (
    (39, 52, 36), (61, 75, 45), (92, 86, 54), (118, 96, 59),
    (153, 135, 88), (82, 58, 38), (31, 39, 31)
)))
OSPREY_MATERIALS = {
    name: Material(rgba(102, 98, 68), "atacs", MULTICAM)
    for name in OSPREY_CUBES
}
OSPREY_MATERIALS.update({
    "rear_upper": Material(rgba(83, 84, 61), "atacs", MULTICAM),
    "rear_middle": Material(rgba(80, 82, 60), "atacs", MULTICAM),
    "rear_lower": Material(rgba(75, 78, 58), "atacs", MULTICAM),
    "side_panel": Material(rgba(72, 76, 57), "webbing"),
    "collar_front": Material(rgba(117, 108, 75), "atacs", MULTICAM),
    "collar_rear": Material(rgba(94, 91, 66), "atacs", MULTICAM),
    "collar_side": Material(rgba(88, 88, 64), "atacs", MULTICAM),
    "collar_root": Material(rgba(105, 99, 70), "soft"),
    "molle": Material(rgba(76, 78, 57), "webbing"),
    "radio": Material(rgba(71, 72, 55), "nylon"),
    "radio_lid": Material(rgba(97, 92, 65), "webbing"),
    "mag_pouch": Material(rgba(85, 82, 60), "atacs", MULTICAM),
    "mag_insert": Material(rgba(27, 30, 29), "plastic"),
    "mag_elastic": Material(rgba(52, 58, 46), "webbing"),
    "lacing": Material(rgba(49, 54, 44), "webbing"),
    "lower_belt": Material(rgba(72, 74, 55), "webbing"),
    "pull": Material(rgba(55, 58, 49), "plastic"),
    "shoulder_top": Material(rgba(120, 111, 77), "atacs", MULTICAM),
    "shoulder_outer": Material(rgba(97, 94, 67), "atacs", MULTICAM),
    "shoulder_front": Material(rgba(110, 103, 72), "atacs", MULTICAM),
    "shoulder_rear": Material(rgba(88, 87, 64), "atacs", MULTICAM),
    "left_tool_bag": Material(rgba(78, 80, 55), "atacs", MULTICAM),
    "left_tool_lid": Material(rgba(99, 91, 63), "webbing"),
    "left_tool_step": Material(rgba(68, 74, 50), "atacs", MULTICAM),
    "right_side_bag": Material(rgba(88, 83, 58), "atacs", MULTICAM),
    "right_side_lid": Material(rgba(105, 96, 65), "webbing"),
    "right_side_seam": Material(rgba(56, 61, 46), "webbing"),
})


SPECS = (
    TextureSpec(
        "Gladiator-S Viking",
        "plate_armor_gladiator_s_viking_layer_1.png",
        Material(rgba(31, 35, 33), "black_camo", VIKING_CAMO),
        VIKING_CUBES,
        VIKING_MATERIALS,
        {
            "side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "bridge": 2,
            "molle": 4, "buckle": 2, "left_bag": 1, "left_lid": 1,
            "right_utility": 1, "right_lid": 1, "mag_pouch": 3, "mag_lid": 3,
            "bottom_seam": 2, "mag_round_tab": 3,
        },
        38,
    ),
    TextureSpec(
        "TT MKIII Coyote",
        "plate_armor_tt_mkiii_coyote_layer_1.png",
        Material(rgba(83, 64, 47), "nylon"),
        TT_CUBES,
        TT_MATERIALS,
        {
            "side_panel": 2, "front_yoke": 2, "rear_yoke": 2, "top_pad": 2,
            "molle": 4, "buckle": 2, "cylinder": 2, "cylinder_strap": 2,
            "flat_pouch": 3, "flat_lid": 3,
        },
        36,
    ),
    TextureSpec(
        "Osprey MK4A Protection",
        "plate_armor_osprey_mk4a_protection_layer_1.png",
        Material(rgba(76, 79, 58), "atacs", MULTICAM),
        OSPREY_CUBES,
        OSPREY_MATERIALS,
        {
            "side_panel": 2, "collar_front": 2, "collar_side": 2, "collar_root": 4,
            "molle": 4, "mag_pouch": 4, "mag_insert": 4, "mag_elastic": 4,
            "lacing": 2, "shoulder_top": 2, "shoulder_outer": 2,
            "shoulder_front": 2, "shoulder_rear": 2,
        },
        53,
    ),
)


ORIGINAL_DETAIL_STYLE = texture_base.detail_style
ORIGINAL_CAMOUFLAGE_COLOR = texture_base.camouflage_color
ORIGINAL_BUILD_TEXTURE = texture_base.build_texture


def batch_d_detail_style(cube_name: str) -> str:
    if cube_name in {
        "front_upper", "rear_upper", "front_middle", "rear_middle", "front_lower", "rear_lower",
        "collar_front", "collar_rear", "collar_side", "collar_root",
        "shoulder_top", "shoulder_outer", "shoulder_front", "shoulder_rear",
    }:
        return "panel"
    if cube_name in {
        "molle", "mag_elastic", "bottom_seam", "cylinder_strap", "tool_strap",
        "lower_belt", "right_side_seam",
    }:
        return "webbing"
    if cube_name in {
        "mag_pouch", "mag_insert", "flat_pouch", "left_tool_bag", "left_tool_lid",
        "left_tool_step", "right_side_bag", "right_side_lid",
    }:
        return "magazine"
    if cube_name in {"cylinder", "antenna", "antenna_base", "tool", "cable", "radio"}:
        return "radio"
    if cube_name in {"lacing"}:
        return "zipper"
    if cube_name in {"emblem_patch"}:
        return "patch"
    if cube_name in {"pull", "mag_round_tab", "blue_ring"}:
        return "buckle"
    return ORIGINAL_DETAIL_STYLE(cube_name)


def batch_d_camouflage_color(material: Material, x: int, y: int, seed: int):
    if material.kind != "atacs" or not material.palette:
        return ORIGINAL_CAMOUFLAGE_COLOR(material, x, y, seed)
    coarse_x = x // 8
    coarse_y = y // 6
    field = texture_base.hash_noise(coarse_x, coarse_y, seed)
    fine = texture_base.hash_noise(x // 4, y // 3, seed + 101)
    index = field * len(material.palette) // 256
    if fine < 24:
        index = min(len(material.palette) - 1, index + 1)
    return material.palette[index]


def batch_d_build_texture(spec: TextureSpec):
    image = ORIGINAL_BUILD_TEXTURE(spec)
    draw = ImageDraw.Draw(image)
    if spec.name == "Gladiator-S Viking":
        material = spec.materials["mag_pouch"]
        x0, y0, x1, y1 = texture_base.pixel_bounds(
            texture_base.cube_faces(spec.cubes["mag_pouch"])["north"]
        )
        draw.line((x0, y0, x0, y1 - 1), fill=texture_base.shade(material.base, -26))
        draw.line((x1 - 1, y0, x1 - 1, y1 - 1), fill=texture_base.shade(material.base, -30))
    elif spec.name == "TT MKIII Coyote":
        material = spec.materials["flat_pouch"]
        x0, y0, x1, y1 = texture_base.pixel_bounds(
            texture_base.cube_faces(spec.cubes["flat_pouch"])["north"]
        )
        stitch = texture_base.shade(material.base, -34)
        draw.line((x0, y0, x1 - 1, y1 - 1), fill=stitch)
        draw.line((x1 - 1, y0, x0, y1 - 1), fill=stitch)
    return image


def main() -> None:
    texture_base.MODEL_NAMES.update({
        "Gladiator-S Viking": "GladiatorSVikingArmorModel",
        "TT MKIII Coyote": "TtMkiiiArmorModel",
        "Osprey MK4A Protection": "OspreyMk4AProtectionArmorModel",
    })
    texture_base.detail_style = batch_d_detail_style
    texture_base.camouflage_color = batch_d_camouflage_color
    texture_base.build_texture = batch_d_build_texture
    for spec in SPECS:
        write_texture(spec)


if __name__ == "__main__":
    main()
