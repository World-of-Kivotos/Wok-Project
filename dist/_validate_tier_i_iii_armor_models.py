from __future__ import annotations

import hashlib
import re
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"

MODELS = {
    "JaypcArmorModel.java": ("plate_armor_jaypc", 10),
    "PacaArmorModel.java": ("plate_armor_paca", 10),
    "MbssArmorModel.java": ("plate_armor_mbss", 10),
    "Tv115ArmorModel.java": ("plate_armor_tv115", 10),
    "B6B23DigitalFloraArmorModel.java": ("plate_armor_6b23_1_digital_flora", 12),
    "B6B5ArmorModel.java": ("plate_armor_6b5_16", 17),
    "KirasaNArmorModel.java": ("plate_armor_kirasa_n_green", 19),
    "MfUntarArmorModel.java": ("plate_armor_mf_untar", 18),
    "KoraKulonArmorModel.java": ("plate_armor_kora_kulon", 10),
}

VARIANTS = (
    "JAYPC_OLIVE",
    "JAYPC_BLACK",
    "PACA",
    "MBSS",
    "TV115",
    "B6B23_1_DIGITAL_FLORA",
    "B6B5_16",
    "KIRASA_N_GREEN",
    "MF_UNTAR",
    "KORA_KULON",
    "KORA_KULON_DIGITAL",
    "MMAC_RANGER_GREEN",
    "RBAV_AF_RANGER_GREEN",
    "STRANDHOGG_RANGER_GREEN",
    "STRANDHOGG_BLACK_MULTICAM",
    "TROOPER_TFO_MULTICAM",
    "BANSHEE_ATACS_AU",
    "B6B13_FLORA",
    "B6B3TM_01M_KHAKI",
    "ANA_M1_OLIVE",
    "A18_SKANDA_MULTICAM",
    "AVS_RANGER_GREEN",
    "AVS_MULTICAM",
    "THOR_CONCEALABLE",
    "STICH_PROFI_V2_BLACK",
    "TV110_COYOTE",
    "B6B23_2_MOUNTAIN_FLORA",
    "B6B5_15_FLORA",
    "OSPREY_MK4A_ASSAULT",
)

TIER_V_VARIANTS = (
    "TACTEC_RANGER_GREEN",
    "CPC_MOD1_ATACS_FG",
    "FCPC_V5",
    "GLADIATOR_S_LIGHT_MULTICAM",
    "HEXATAC_HPC_BLACK_MULTICAM",
    "B6B45_GENERAL",
    "B6B45_MEDIC",
    "GZHEL_K",
    "GLADIATOR_S_GRAY",
    "GLADIATOR_S_VIKING",
    "TT_MKIII_COYOTE",
    "OSPREY_MK4A_PROTECTION",
    "DEFENDER_2_SPOT_CAMO",
    "DEFENDER_2",
    "GLADIATOR_S_DEATHLESS",
    "REDUT_M",
    "IOTV_GEN4_HIGH_MOBILITY",
    "IOTV_GEN4_FULL_PROTECTION",
    "IOTV_GEN4_ASSAULT",
    "KORUND_VM_BLACK",
)

TIER_VI_VARIANTS = (
    "HEXGRID",
    "SLICK",
    "STICH_DEFENSE_MOD2",
    "B6B43_ZABRALO_SH",
    "THOR_INTEGRATED",
)

TEXTURE_NAMES = (
    "plate_armor_jaypc_olive_layer_1.png",
    "plate_armor_jaypc_black_layer_1.png",
    "plate_armor_paca_layer_1.png",
    "plate_armor_mbss_layer_1.png",
    "plate_armor_tv115_layer_1.png",
    "plate_armor_6b23_1_digital_flora_layer_1.png",
    "plate_armor_6b5_16_layer_1.png",
    "plate_armor_kirasa_n_green_layer_1.png",
    "plate_armor_mf_untar_layer_1.png",
    "plate_armor_kora_kulon_layer_1.png",
    "plate_armor_kora_kulon_digital_layer_1.png",
)

REQUIRED_BONES = ("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg")

SLEEVELESS_MODELS = (
    "B6B23DigitalFloraArmorModel.java",
    "B6B5ArmorModel.java",
    "KirasaNArmorModel.java",
    "MfUntarArmorModel.java",
)

LITERAL_BOX = re.compile(
    r"\.addBox\(\s*"
    r"(-?\d+(?:\.\d+)?F?)\s*,\s*"
    r"(-?\d+(?:\.\d+)?F?)\s*,\s*"
    r"(-?\d+(?:\.\d+)?F?)\s*,\s*"
    r"(-?\d+(?:\.\d+)?F?)\s*,\s*"
    r"(-?\d+(?:\.\d+)?F?)\s*,\s*"
    r"(-?\d+(?:\.\d+)?F?)"
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def validate_models() -> None:
    observed_layers: set[str] = set()
    for filename, (expected_layer, minimum_static_cuboids) in MODELS.items():
        path = CLIENT / filename
        require(path.is_file(), f"missing model: {path}")
        source = path.read_text(encoding="utf-8")
        require("LayerDefinition.create(mesh, 128, 128)" in source, f"wrong atlas size: {filename}")
        for bone in REQUIRED_BONES:
            require(f'"{bone}"' in source, f"missing bone {bone}: {filename}")
        match = re.search(r'new ResourceLocation\(MiningConstants\.MODID, "([^"]+)"\)', source)
        require(match is not None, f"missing layer id: {filename}")
        layer = match.group(1)
        require(layer == expected_layer, f"unexpected layer id in {filename}: {layer}")
        require(layer not in observed_layers, f"duplicate layer id: {layer}")
        observed_layers.add(layer)
        static_cuboids = source.count(".addBox(")
        require(static_cuboids >= minimum_static_cuboids,
                f"too few static cuboid definitions in {filename}: {static_cuboids}")
        print(f"MODEL {filename}: layer={layer}, static_addBox={static_cuboids}")


def literal_boxes(source: str) -> list[tuple[float, float, float, float, float, float]]:
    return [
        tuple(float(value.removesuffix("F")) for value in match.groups())
        for match in LITERAL_BOX.finditer(source)
    ]


def validate_user_corrections() -> None:
    for filename in SLEEVELESS_MODELS:
        source = (CLIENT / filename).read_text(encoding="utf-8")
        for bone in ("right_arm", "left_arm"):
            require(
                re.search(
                    rf'addOrReplaceChild\("{bone}",\s*CubeListBuilder\.create\(\),',
                    source,
                )
                is not None,
                f"{filename} must keep {bone} empty",
            )
        require("createRightShoulder" not in source, f"shoulder helper remains: {filename}")
        require("createLeftShoulder" not in source, f"shoulder helper remains: {filename}")
        require("createRightArm" not in source, f"arm armor helper remains: {filename}")
        require("createLeftArm" not in source, f"arm armor helper remains: {filename}")

    for filename in ("B6B23DigitalFloraArmorModel.java", "B6B5ArmorModel.java"):
        source = (CLIENT / filename).read_text(encoding="utf-8")
        shoulder_caps = []
        for box in literal_boxes(source):
            x, y, _z, width, height, depth = box
            reaches_arm = x < -4.15 or x + width > 4.15
            crosses_shoulder_line = y < 1.0 and y + height > -0.75
            wraps_shoulder = depth >= 4.0 and height >= 0.50
            if reaches_arm and crosses_shoulder_line and wraps_shoulder:
                shoulder_caps.append(box)
        require(not shoulder_caps, f"body-mounted shoulder caps remain in {filename}: {shoulder_caps}")

    kirasa = (CLIENT / "KirasaNArmorModel.java").read_text(encoding="utf-8")
    kirasa_boxes = literal_boxes(kirasa)
    collar_panels = [
        box
        for box in kirasa_boxes
        if box[1] <= -0.70
        and box[4] >= 1.20
        and (
            box[0] <= -4.0
            or box[0] + box[3] >= 4.0
            or box[2] <= -4.0
            or box[2] + box[5] >= 4.0
        )
    ]
    require(len(collar_panels) == 5, "Kirasa-N needs exactly five visible outer stand-collar panels")

    front_panels = sorted(
        (
            box
            for box in kirasa_boxes
            if box[2] < -1.90 and box[3] >= 6.0 and box[4] >= 3.0 and box[5] <= 0.50
        ),
        key=lambda box: box[1],
    )
    rear_panels = sorted(
        (
            box
            for box in kirasa_boxes
            if box[2] >= 1.90 and box[3] >= 6.0 and box[4] >= 3.0 and box[5] <= 0.50
        ),
        key=lambda box: box[1],
    )
    for label, panels in (("front", front_panels), ("rear", rear_panels)):
        require(len(panels) == 3, f"Kirasa-N needs three tapered {label} soft panels")
        widths = [panel[3] for panel in panels]
        require(widths[0] < widths[1] < widths[2], f"Kirasa-N {label} panels must widen downward")

    upper_side_caps = [
        box
        for box in kirasa_boxes
        if box[1] >= 0.50
        and box[1] < 3.50
        and box[4] >= 0.50
        and box[5] >= 3.0
        and (box[0] < -4.15 or box[0] + box[3] > 4.15)
    ]
    require(not upper_side_caps, f"Kirasa-N upper armholes are blocked: {upper_side_caps}")
    print(
        "CORRECTIONS sleeveless=4, b6_body_shoulder_caps=0, "
        f"kirasa_stand_collar_panels={len(collar_panels)}, kirasa_taper=3x2"
    )


def validate_routing() -> None:
    definition = (CLIENT / "PlateArmorModelDefinition.java").read_text(encoding="utf-8")
    item = (ROOT / "src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java").read_text(
        encoding="utf-8")
    expected = set(VARIANTS + TIER_V_VARIANTS + TIER_VI_VARIANTS)
    for source, label in ((definition, "model"), (item, "texture")):
        switch = re.search(r"return switch \(variant\) \{(.*?)\n\s*default -> null;", source, re.DOTALL)
        require(switch is not None, f"missing {label} routing switch")
        cases: list[str] = []
        for case_group in re.findall(r"case\s+(.*?)\s*->", switch.group(1), re.DOTALL):
            cases.extend(re.findall(r"\b[A-Z][A-Z0-9_]*\b", case_group))
        require(len(cases) == len(set(cases)), f"duplicate {label} routing case: {cases}")
        require(set(cases) == expected,
                f"unexpected {label} routing cases: missing={sorted(expected - set(cases))}, "
                f"extra={sorted(set(cases) - expected)}")
    require(not (CLIENT / "ThorIntegratedArmorClient.java").exists(), "obsolete THOR-only client remains")
    require(not (CLIENT / "ThorIntegratedArmorClientRegistration.java").exists(),
            "obsolete THOR-only registration remains")
    require((CLIENT / "PlateArmorClient.java").is_file(), "missing shared armor client")
    require((CLIENT / "PlateArmorClientRegistration.java").is_file(), "missing shared layer registration")
    print(
        f"ROUTING legacy_variants={len(VARIANTS)}, "
        f"tier_v_variants={len(TIER_V_VARIANTS)}, "
        f"tier_vi_variants={len(TIER_VI_VARIANTS)}, thor_preserved=true"
    )


def validate_textures() -> None:
    hashes: set[str] = set()
    for filename in TEXTURE_NAMES:
        path = TEXTURES / filename
        require(path.is_file(), f"missing texture: {path}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest().upper()
        require(digest not in hashes, f"duplicated texture bytes: {filename}")
        hashes.add(digest)
        with Image.open(path) as image:
            require(image.size == (128, 128), f"wrong texture size {image.size}: {filename}")
            require(image.mode == "RGBA", f"wrong texture mode {image.mode}: {filename}")
            require(image.getchannel("A").getextrema() == (255, 255), f"non-opaque texture: {filename}")
            colors = len(image.getcolors(maxcolors=128 * 128) or ())
            require(colors >= 32, f"texture lacks material variation ({colors} colors): {filename}")
        print(f"TEXTURE {filename}: colors={colors}, sha256={digest}")


def main() -> None:
    validate_models()
    validate_user_corrections()
    validate_routing()
    validate_textures()
    print("PASS tier I-III armor model validation")


if __name__ == "__main__":
    main()
