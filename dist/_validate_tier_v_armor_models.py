from __future__ import annotations

from pathlib import Path
import hashlib
import re

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"

MODELS = {
    "TactecArmorModel.java": "plate_armor_tactec_ranger_green",
    "CpcMod1ArmorModel.java": "plate_armor_cpc_mod1_atacs_fg",
    "FcpcV5ArmorModel.java": "plate_armor_fcpc_v5",
    "GladiatorSLightArmorModel.java": "plate_armor_gladiator_s_light_multicam",
    "HexatacHpcArmorModel.java": "plate_armor_hexatac_hpc_black_multicam",
    "B6B45GeneralArmorModel.java": "plate_armor_6b45_general",
    "B6B45MedicArmorModel.java": "plate_armor_6b45_medic",
    "GzhelKArmorModel.java": "plate_armor_gzhel_k",
    "GladiatorSGrayArmorModel.java": "plate_armor_gladiator_s_gray",
    "GladiatorSVikingArmorModel.java": "plate_armor_gladiator_s_viking",
    "TtMkiiiArmorModel.java": "plate_armor_tt_mkiii_coyote",
    "OspreyMk4AProtectionArmorModel.java": "plate_armor_osprey_mk4a_protection",
    "Defender2ArmorModel.java": "plate_armor_defender_2",
    "GladiatorSDeathlessArmorModel.java": "plate_armor_gladiator_s_deathless",
    "RedutMArmorModel.java": "plate_armor_redut_m",
    "IotvGen4HighMobilityArmorModel.java": "plate_armor_iotv_gen4_high_mobility",
    "IotvGen4FullProtectionArmorModel.java": "plate_armor_iotv_gen4_full_protection",
    "IotvGen4AssaultArmorModel.java": "plate_armor_iotv_gen4_assault",
    "KorundVmArmorModel.java": "plate_armor_korund_vm_black",
}

VARIANTS = {
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
}

TEXTURE_NAMES = tuple(
    f"plate_armor_{name}_layer_1.png"
    for name in (
        "tactec_ranger_green",
        "cpc_mod1_atacs_fg",
        "fcpc_v5",
        "gladiator_s_light_multicam",
        "hexatac_hpc_black_multicam",
        "6b45_general",
        "6b45_medic",
        "gzhel_k",
        "gladiator_s_gray",
        "gladiator_s_viking",
        "tt_mkiii_coyote",
        "osprey_mk4a_protection",
        "defender_2_spot_camo",
        "defender_2",
        "gladiator_s_deathless",
        "redut_m",
        "iotv_gen4_high_mobility",
        "iotv_gen4_full_protection",
        "iotv_gen4_assault",
        "korund_vm_black",
    )
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
    signatures: dict[tuple[tuple[float, ...], ...], str] = {}
    for filename, expected_layer in MODELS.items():
        path = CLIENT / filename
        require(path.is_file(), f"missing tier V model: {filename}")
        source = path.read_text(encoding="utf-8")
        require("LayerDefinition.create(mesh, 128, 128)" in source,
                f"wrong atlas size: {filename}")
        for bone in REQUIRED_BONES:
            require(f'"{bone}"' in source, f"missing bone {bone}: {filename}")
        match = re.search(r'new ResourceLocation\(MiningConstants\.MODID, "([^"]+)"\)', source)
        require(match is not None, f"missing layer id: {filename}")
        layer = match.group(1)
        require(layer == expected_layer, f"unexpected layer id in {filename}: {layer}")
        require(layer not in layers, f"duplicate layer id: {layer}")
        layers.add(layer)

        boxes = literal_boxes(source)
        require(len(boxes) >= 12, f"under-detailed tier V model ({len(boxes)}): {filename}")
        require(len(boxes) == len(set(boxes)), f"exact duplicate cuboid: {filename}")
        require(all(all(value > 0.0 for value in box[3:]) for box in boxes),
                f"non-positive cuboid: {filename}")
        signature = tuple(sorted(boxes))
        require(signature not in signatures,
                f"cloned geometry: {filename} == {signatures.get(signature)}")
        signatures[signature] = filename
        print(f"MODEL {filename}: layer={layer}, boxes={len(boxes)}")


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
    declared = set(re.findall(
        r"^\s*([A-Z][A-Z0-9_]*)\([^\n]*PlateArmorTier\.V,",
        variant_source,
        re.MULTILINE,
    ))
    require(declared == VARIANTS,
            f"tier V enum mismatch: missing={sorted(VARIANTS - declared)}, "
            f"extra={sorted(declared - VARIANTS)}")

    definition = (CLIENT / "PlateArmorModelDefinition.java").read_text(encoding="utf-8")
    item = (
        ROOT / "src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java"
    ).read_text(encoding="utf-8")
    for source, label in ((definition, "model"), (item, "texture")):
        cases = switch_cases(source)
        require(len(cases) == len(set(cases)), f"duplicate {label} route")
        require(VARIANTS <= set(cases), f"missing {label} routes: {sorted(VARIANTS - set(cases))}")
    require("case DEFENDER_2_SPOT_CAMO, DEFENDER_2 -> DEFENDER_2;" in definition,
            "Defender-2 color variants must share only their verified common geometry")
    print(f"ROUTING tier_v_variants={len(VARIANTS)}, geometry_layers={len(MODELS)}")


def validate_textures() -> None:
    hashes: dict[str, str] = {}
    for filename in TEXTURE_NAMES:
        path = TEXTURES / filename
        require(path.is_file(), f"missing tier V texture: {filename}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest().upper()
        require(digest not in hashes, f"duplicate texture bytes: {filename} == {hashes.get(digest)}")
        hashes[digest] = filename
        with Image.open(path) as image:
            require(image.size == (128, 128), f"wrong texture size {image.size}: {filename}")
            require(image.mode == "RGBA", f"wrong texture mode {image.mode}: {filename}")
            require(image.getchannel("A").getextrema() == (255, 255),
                    f"non-opaque texture: {filename}")
            colors = len(image.getcolors(maxcolors=128 * 128) or ())
            require(colors >= 48, f"texture lacks material detail ({colors} colors): {filename}")
        print(f"TEXTURE {filename}: colors={colors}, sha256={digest}")


def validate_generators() -> None:
    generators = sorted((ROOT / "dist").glob("_make_tier_v_batch_*_textures.py"))
    require(len(generators) >= 6, f"expected at least six bounded tier V generators, found {len(generators)}")
    for path in generators:
        source = path.read_text(encoding="utf-8")
        require("128" in source, f"generator lacks 128 atlas contract: {path.name}")
    print(f"GENERATORS count={len(generators)}")


def main() -> None:
    validate_models()
    validate_routing()
    validate_textures()
    validate_generators()
    print("PASS tier V armor model validation")


if __name__ == "__main__":
    main()
