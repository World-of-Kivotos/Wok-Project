from __future__ import annotations

from hashlib import sha256
from pathlib import Path
import re

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"

MODELS = {
    "HexgridArmorModel.java": "plate_armor_hexgrid",
    "SlickArmorModel.java": "plate_armor_slick",
    "StichDefenseMod2ArmorModel.java": "plate_armor_stich_defense_mod2",
    "B6B43ZabraloShArmorModel.java": "plate_armor_6b43_zabralo_sh",
    "ThorIntegratedArmorModel.java": "plate_armor_thor_integrated",
}

VARIANTS = {
    "HEXGRID",
    "SLICK",
    "STICH_DEFENSE_MOD2",
    "B6B43_ZABRALO_SH",
    "THOR_INTEGRATED",
}

TEXTURE_NAMES = tuple(
    f"plate_armor_{name}_layer_1.png"
    for name in (
        "hexgrid",
        "slick",
        "stich_defense_mod2",
        "6b43_zabralo_sh",
        "thor_integrated",
    )
)

GENERATORS = (
    "_make_tier_vi_hexgrid_texture.py",
    "_make_tier_vi_slick_texture.py",
    "_make_tier_vi_stich_defense_texture.py",
    "_make_tier_vi_6b43_texture.py",
    "_make_thor_integrated_armor_texture.py",
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


def switch_cases(source: str) -> list[str]:
    switch = re.search(r"return switch \(variant\) \{(.*?)\n\s*default -> null;", source, re.DOTALL)
    require(switch is not None, "missing variant routing switch")
    cases: list[str] = []
    for case_group in re.findall(r"case\s+(.*?)\s*->", switch.group(1), re.DOTALL):
        cases.extend(re.findall(r"\b[A-Z][A-Z0-9_]*\b", case_group))
    return cases


def validate_models() -> None:
    layers: set[str] = set()
    signatures: dict[tuple[tuple[float, ...], ...], str] = {}
    for filename, expected_layer in MODELS.items():
        path = CLIENT / filename
        require(path.is_file(), f"missing tier VI model: {filename}")
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
        require(len(boxes) >= 12, f"under-detailed tier VI model ({len(boxes)}): {filename}")
        require(all(all(value > 0.0 for value in box[3:]) for box in boxes),
                f"non-positive cuboid: {filename}")
        signature = tuple(sorted(boxes))
        require(signature not in signatures,
                f"cloned geometry: {filename} == {signatures.get(signature)}")
        signatures[signature] = filename
        print(f"MODEL {filename}: layer={layer}, literal_boxes={len(boxes)}")

    hexgrid = (CLIENT / "HexgridArmorModel.java").read_text(encoding="utf-8")
    require("HONEYCOMB_ROWS = 8" in hexgrid and "addHoneycombCell" in hexgrid,
            "Hexgrid lost its eight-row runtime honeycomb")
    require(hexgrid.count('prefix + "_') == 6,
            "Hexgrid honeycomb must retain six independently modeled edges")


def validate_routing() -> None:
    variant_source = (
        ROOT / "src/main/java/com/miningdim/job/engineer/armor/PlateArmorVariant.java"
    ).read_text(encoding="utf-8")
    declared = set(re.findall(
        r"^\s*([A-Z][A-Z0-9_]*)\([^\n]*PlateArmorTier\.VI,",
        variant_source,
        re.MULTILINE,
    ))
    require(declared == VARIANTS,
            f"tier VI enum mismatch: missing={sorted(VARIANTS - declared)}, "
            f"extra={sorted(declared - VARIANTS)}")

    definition = (CLIENT / "PlateArmorModelDefinition.java").read_text(encoding="utf-8")
    item = (
        ROOT / "src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java"
    ).read_text(encoding="utf-8")
    for source, label in ((definition, "model"), (item, "texture")):
        cases = switch_cases(source)
        require(len(cases) == len(set(cases)), f"duplicate {label} route")
        require(VARIANTS <= set(cases), f"missing {label} routes: {sorted(VARIANTS - set(cases))}")
    for variant in VARIANTS:
        require(re.search(rf"case\s+{variant}\s*->|\b{variant}\b[^;]*->", definition) is not None,
                f"missing explicit model route: {variant}")
    print(f"ROUTING tier_vi_variants={len(VARIANTS)}, geometry_layers={len(MODELS)}")


def validate_textures() -> None:
    hashes: dict[str, str] = {}
    for filename in TEXTURE_NAMES:
        path = TEXTURES / filename
        require(path.is_file(), f"missing tier VI texture: {filename}")
        digest = sha256(path.read_bytes()).hexdigest().upper()
        require(digest not in hashes, f"duplicate texture bytes: {filename} == {hashes.get(digest)}")
        hashes[digest] = filename
        with Image.open(path) as image:
            require(image.size == (128, 128), f"wrong texture size {image.size}: {filename}")
            require(image.mode == "RGBA", f"wrong texture mode {image.mode}: {filename}")
            require(image.getchannel("A").getextrema() == (255, 255),
                    f"non-opaque texture: {filename}")
            colors = len(image.getcolors(maxcolors=128 * 128) or ())
            require(colors >= 40, f"texture lacks material detail ({colors} colors): {filename}")
        print(f"TEXTURE {filename}: colors={colors}, sha256={digest}")


def validate_generators() -> None:
    for filename in GENERATORS:
        path = ROOT / "dist" / filename
        require(path.is_file(), f"missing deterministic tier VI texture generator: {filename}")
        source = path.read_text(encoding="utf-8")
        require("128" in source, f"generator lacks 128 atlas contract: {filename}")
        require("determin" in source.lower(), f"generator lacks determinism contract: {filename}")
    print(f"GENERATORS count={len(GENERATORS)}")


def main() -> None:
    validate_models()
    validate_routing()
    validate_textures()
    validate_generators()
    print("PASS tier VI armor model validation")


if __name__ == "__main__":
    main()
