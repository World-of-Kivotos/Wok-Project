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
    "B6B23DigitalFloraArmorModel.java": ("plate_armor_6b23_1_digital_flora", 10),
    "B6B5ArmorModel.java": ("plate_armor_6b5_16", 10),
    "KirasaNArmorModel.java": ("plate_armor_kirasa_n_green", 8),
    "MfUntarArmorModel.java": ("plate_armor_mf_untar", 10),
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


def validate_routing() -> None:
    definition = (CLIENT / "PlateArmorModelDefinition.java").read_text(encoding="utf-8")
    item = (ROOT / "src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java").read_text(
        encoding="utf-8")
    expected = set(VARIANTS + ("THOR_INTEGRATED",))
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
    print(f"ROUTING exact_variants={len(VARIANTS)}, thor_preserved=true")


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
    validate_routing()
    validate_textures()
    print("PASS tier I-III armor model validation")


if __name__ == "__main__":
    main()
