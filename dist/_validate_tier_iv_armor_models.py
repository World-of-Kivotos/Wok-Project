from __future__ import annotations

import hashlib
import re
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"

MODELS = {
    "MmacArmorModel.java": "plate_armor_mmac_ranger_green",
    "RbavAfArmorModel.java": "plate_armor_rbav_af_ranger_green",
    "StrandhoggArmorModel.java": "plate_armor_strandhogg",
    "TrooperTfoArmorModel.java": "plate_armor_trooper_tfo_multicam",
    "BansheeArmorModel.java": "plate_armor_banshee_atacs_au",
    "B6B13ArmorModel.java": "plate_armor_6b13_flora",
    "B6B3Tm01MArmorModel.java": "plate_armor_6b3tm_01m_khaki",
    "AnaM1ArmorModel.java": "plate_armor_ana_m1_olive",
    "A18SkandaArmorModel.java": "plate_armor_a18_skanda_multicam",
    "AvsArmorModel.java": "plate_armor_avs",
    "ThorConcealableArmorModel.java": "plate_armor_thor_concealable",
    "StichProfiV2ArmorModel.java": "plate_armor_stich_profi_v2_black",
    "Tv110ArmorModel.java": "plate_armor_tv110_coyote",
    "B6B23MountainFloraArmorModel.java": "plate_armor_6b23_2_mountain_flora",
    "B6B5FloraArmorModel.java": "plate_armor_6b5_15_flora",
    "OspreyMk4AAssaultArmorModel.java": "plate_armor_osprey_mk4a_assault",
}

VARIANTS = {
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
}

TEXTURE_NAMES = (
    "plate_armor_mmac_ranger_green_layer_1.png",
    "plate_armor_rbav_af_ranger_green_layer_1.png",
    "plate_armor_strandhogg_ranger_green_layer_1.png",
    "plate_armor_strandhogg_black_multicam_layer_1.png",
    "plate_armor_trooper_tfo_multicam_layer_1.png",
    "plate_armor_banshee_atacs_au_layer_1.png",
    "plate_armor_6b13_flora_layer_1.png",
    "plate_armor_6b3tm_01m_khaki_layer_1.png",
    "plate_armor_ana_m1_olive_layer_1.png",
    "plate_armor_a18_skanda_multicam_layer_1.png",
    "plate_armor_avs_ranger_green_layer_1.png",
    "plate_armor_avs_multicam_layer_1.png",
    "plate_armor_thor_concealable_layer_1.png",
    "plate_armor_stich_profi_v2_black_layer_1.png",
    "plate_armor_tv110_coyote_layer_1.png",
    "plate_armor_6b23_2_mountain_flora_layer_1.png",
    "plate_armor_6b5_15_flora_layer_1.png",
    "plate_armor_osprey_mk4a_assault_layer_1.png",
)

GENERATORS = (
    "_make_tier_iv_light_armor_textures.py",
    "_make_tier_iv_medium_armor_textures.py",
    "_make_tier_iv_heavy_armor_textures.py",
)

REQUIRED_BONES = ("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg")
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


def literal_boxes(source: str) -> tuple[tuple[float, ...], ...]:
    return tuple(
        tuple(float(value.removesuffix("F")) for value in match.groups())
        for match in LITERAL_BOX.finditer(source)
    )


def validate_models() -> None:
    layers: set[str] = set()
    geometry_signatures: dict[tuple[tuple[float, ...], ...], str] = {}
    for filename, expected_layer in MODELS.items():
        path = CLIENT / filename
        require(path.is_file(), f"missing model: {filename}")
        source = path.read_text(encoding="utf-8")
        require("LayerDefinition.create(mesh, 128, 128)" in source, f"wrong atlas size: {filename}")
        for bone in REQUIRED_BONES:
            require(f'"{bone}"' in source, f"missing bone {bone}: {filename}")
        match = re.search(r'new ResourceLocation\(MiningConstants\.MODID, "([^"]+)"\)', source)
        require(match is not None, f"missing layer id: {filename}")
        layer = match.group(1)
        require(layer == expected_layer, f"unexpected layer id in {filename}: {layer}")
        require(layer not in layers, f"duplicate layer id: {layer}")
        layers.add(layer)

        boxes = literal_boxes(source)
        require(len(boxes) >= 8, f"model is under-detailed ({len(boxes)} literal boxes): {filename}")
        require(len(boxes) == len(set(boxes)), f"exact duplicate cuboid in {filename}")
        for box in boxes:
            require(all(value > 0.0 for value in box[3:]), f"non-positive cuboid in {filename}: {box}")
        signature = tuple(sorted(boxes))
        require(signature not in geometry_signatures,
                f"cloned geometry: {filename} == {geometry_signatures.get(signature)}")
        geometry_signatures[signature] = filename
        print(f"MODEL {filename}: layer={layer}, literal_boxes={len(boxes)}")


def switch_cases(source: str) -> list[str]:
    switch = re.search(r"return switch \(variant\) \{(.*?)\n\s*default -> null;", source, re.DOTALL)
    require(switch is not None, "missing variant routing switch")
    cases: list[str] = []
    for case_group in re.findall(r"case\s+(.*?)\s*->", switch.group(1), re.DOTALL):
        cases.extend(re.findall(r"\b[A-Z][A-Z0-9_]*\b", case_group))
    return cases


def validate_routing() -> None:
    variant_source = (
        ROOT / "src/main/java/com/miningdim/job/engineer/armor/PlateArmorVariant.java"
    ).read_text(encoding="utf-8")
    tier_iv = set(
        re.findall(r"^\s*([A-Z][A-Z0-9_]*)\([^\n]*PlateArmorTier\.IV,", variant_source, re.MULTILINE)
    )
    require(tier_iv == VARIANTS,
            f"tier IV enum mismatch: missing={sorted(VARIANTS - tier_iv)}, extra={sorted(tier_iv - VARIANTS)}")

    definition = (CLIENT / "PlateArmorModelDefinition.java").read_text(encoding="utf-8")
    item = (
        ROOT / "src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java"
    ).read_text(encoding="utf-8")
    for source, label in ((definition, "model"), (item, "texture")):
        cases = switch_cases(source)
        require(len(cases) == len(set(cases)), f"duplicate {label} route")
        require(VARIANTS <= set(cases), f"missing {label} routes: {sorted(VARIANTS - set(cases))}")
    print(f"ROUTING tier_iv_variants={len(VARIANTS)}, shared_geometry_variants=2")


def validate_textures() -> None:
    hashes: dict[str, str] = {}
    for filename in TEXTURE_NAMES:
        path = TEXTURES / filename
        require(path.is_file(), f"missing texture: {filename}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest().upper()
        require(digest not in hashes, f"duplicate texture bytes: {filename} == {hashes.get(digest)}")
        hashes[digest] = filename
        with Image.open(path) as image:
            require(image.size == (128, 128), f"wrong texture size {image.size}: {filename}")
            require(image.mode == "RGBA", f"wrong texture mode {image.mode}: {filename}")
            require(image.getchannel("A").getextrema() == (255, 255), f"non-opaque texture: {filename}")
            colors = len(image.getcolors(maxcolors=128 * 128) or ())
            require(colors >= 32, f"texture lacks material variation ({colors} colors): {filename}")
        print(f"TEXTURE {filename}: colors={colors}, sha256={digest}")


def validate_generators() -> None:
    for filename in GENERATORS:
        path = ROOT / "dist" / filename
        require(path.is_file(), f"missing generator: {filename}")
        source = path.read_text(encoding="utf-8")
        require("determin" in source.lower(), f"generator lacks deterministic check: {filename}")
        require("128" in source, f"generator lacks 128 atlas contract: {filename}")
    print(f"GENERATORS count={len(GENERATORS)}")


def main() -> None:
    validate_models()
    validate_routing()
    validate_textures()
    validate_generators()
    print("PASS tier IV armor model validation")


if __name__ == "__main__":
    main()
