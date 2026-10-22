from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
RECIPE_DIR = RESOURCES / "data/miningdim/recipes/farmer"
LOOT_DIR = RESOURCES / "data/miningdim/loot_tables/blocks"
LANG_DIR = RESOURCES / "assets/miningdim/lang"
CATALOG_PATH = ROOT / "tools/assets/granary/materials.json"
PARTS = ("bin", "center", "vent")


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, value) -> None:
    original = path.read_bytes() if path.exists() else b""
    newline = "\r\n" if b"\r\n" in original else "\n"
    rendered = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path.write_bytes(rendered.replace("\n", newline).encode("utf-8"))


def replace_values(value, replacements: dict[str, str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"item", "name"} and isinstance(child, str):
                value[key] = replacements.get(child, child)
            else:
                replace_values(child, replacements)
    elif isinstance(value, list):
        for child in value:
            replace_values(child, replacements)


def variant_id(material: str, part: str) -> str:
    return f"granary_{part}" if material == "oak" else f"{material}_granary_{part}"


def load_materials() -> list[dict[str, str]]:
    materials = read_json(CATALOG_PATH)
    if len(materials) != 31 or len({material["id"] for material in materials}) != 31:
        raise ValueError(f"Expected 31 unique granary materials in {CATALOG_PATH}")
    for material in materials:
        required = {"id", "kind", "zh", "en", "ingredient", "wood", "trim", "trim_top"}
        if not required.issubset(material):
            raise ValueError(f"Incomplete material entry in {CATALOG_PATH}: {material}")
        if material["kind"] not in {"wood", "stone"}:
            raise ValueError(f"Unknown material kind in {CATALOG_PATH}: {material}")
    return materials


def translations(materials: list[dict[str, str]]) -> dict[str, dict[str, str]]:
    result = {}
    for material in materials:
        for part, zh_part, en_part in (
            ("bin", "储粮囤", "Storage Bin"),
            ("center", "粮仓账台", "Granary Ledger"),
            ("vent", "粮仓气窗", "Granary Air Vent"),
        ):
            result[f"block.miningdim.{variant_id(material['id'], part)}"] = {
                "zh_cn": material["zh"] + zh_part,
                "en_us": f"{material['en']} {en_part}",
            }
    return result


def update_language(path: Path, locale: str, entries: dict[str, str]) -> None:
    text = path.read_text(encoding="utf-8")
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines(keepends=True)
    key_pattern = re.compile(r'^\s*"([^"]+)"\s*:')
    found: dict[str, int] = {}
    for index, line in enumerate(lines):
        match = key_pattern.match(line)
        if match and match.group(1) in entries:
            found[match.group(1)] = index

    for key, value in entries.items():
        encoded = json.dumps(value, ensure_ascii=False)
        if key in found:
            index = found[key]
            match = re.match(r'^(\s*"[^"]+"\s*:\s*)("(?:[^"\\]|\\.)*")(,?)(\r?\n?)$', lines[index])
            if not match:
                raise ValueError(f"Unexpected language line for {key}: {lines[index]!r}")
            lines[index] = f"{match.group(1)}{encoded}{match.group(3)}{match.group(4)}"

    missing = [key for key in entries if key not in found]
    if missing:
        anchor_key = "block.miningdim.granary_vent"
        anchor_index = next((i for i, line in enumerate(lines)
                             if (match := key_pattern.match(line)) and match.group(1) == anchor_key), None)
        if anchor_index is None:
            raise ValueError(f"Missing insertion anchor {anchor_key} in {path}")
        anchor_line = lines[anchor_index]
        if not anchor_line.rstrip("\r\n").endswith(","):
            lines[anchor_index] = anchor_line.rstrip("\r\n") + "," + (newline if anchor_line.endswith(("\n", "\r")) else "")
        new_lines = [f"  {json.dumps(key, ensure_ascii=False)}: {json.dumps(entries[key], ensure_ascii=False)},{newline}"
                     for key in missing]
        lines[anchor_index + 1:anchor_index + 1] = new_lines
    path.write_text("".join(lines), encoding="utf-8", newline="")
    parsed = read_json(path)
    absent = [key for key in entries if key not in parsed or parsed[key] != entries[key]]
    if absent:
        raise ValueError(f"Language validation failed for {path}: {absent}")


def wood_recipe(template: dict, material: dict, part: str) -> dict:
    name = material["id"]
    log = {"bamboo": "bamboo_block", "crimson": "crimson_stem", "warped": "warped_stem"}.get(
        name, f"{name}_log")
    substitutions = {
        "minecraft:oak_planks": f"minecraft:{name}_planks",
        "minecraft:oak_log": f"minecraft:{log}",
        "minecraft:oak_fence": f"minecraft:{name}_fence",
        "minecraft:oak_trapdoor": f"minecraft:{name}_trapdoor",
        "miningdim:granary_" + part: f"miningdim:{variant_id(name, part)}",
    }
    recipe = json.loads(json.dumps(template))
    replace_values(recipe, substitutions)
    return recipe


def stone_recipe(material: dict, part: str) -> dict:
    ingredient = {"item": material["ingredient"]}
    if part == "bin":
        ingredients = [{"item": "minecraft:barrel"}, *[ingredient.copy() for _ in range(4)],
                       *[{"item": "minecraft:wheat"} for _ in range(3)]]
        return {"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ingredients,
                "result": {"item": f"miningdim:{variant_id(material['id'], part)}"}}
    if part == "center":
        ingredients = [{"item": "minecraft:chest"}, {"item": "minecraft:book"},
                       *[ingredient.copy() for _ in range(4)], *[{"item": "minecraft:wheat"} for _ in range(3)]]
        return {"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ingredients,
                "result": {"item": f"miningdim:{variant_id(material['id'], part)}"}}
    return {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["S", "S"],
            "key": {"S": ingredient}, "result": {"item": f"miningdim:{variant_id(material['id'], part)}", "count": 2}}


def generate() -> None:
    materials = load_materials()
    recipe_templates = {part: read_json(RECIPE_DIR / f"granary_{part}.json") for part in PARTS}
    loot_templates = {part: read_json(LOOT_DIR / f"granary_{part}.json") for part in PARTS}

    for material in materials:
        name = material["id"]
        if name == "oak":
            continue
        for part in PARTS:
            output_id = variant_id(name, part)
            if material["kind"] == "wood":
                recipe = wood_recipe(recipe_templates[part], material, part)
            else:
                recipe = stone_recipe(material, part)
            write_json(RECIPE_DIR / f"{output_id}.json", recipe)

            loot = json.loads(json.dumps(loot_templates[part]))
            replace_values(loot, {f"miningdim:granary_{part}": f"miningdim:{output_id}"})
            write_json(LOOT_DIR / f"{output_id}.json", loot)

    names = translations(materials)
    for locale in ("zh_cn", "en_us"):
        update_language(LANG_DIR / f"{locale}.json", locale,
                        {key: values[locale] for key, values in names.items()})

    for material in materials:
        for part in PARTS:
            output_id = variant_id(material["id"], part)
            recipe_path = RECIPE_DIR / f"{output_id}.json"
            loot_path = LOOT_DIR / f"{output_id}.json"
            recipe = read_json(recipe_path)
            loot = read_json(loot_path)
            result_id = f"miningdim:{output_id}"
            if recipe["result"]["item"] != result_id:
                raise ValueError(f"Wrong recipe result in {recipe_path}")
            loot_names = [entry.get("name") for pool in loot["pools"] for entry in pool["entries"]]
            if not loot_names or any(name != result_id for name in loot_names):
                raise ValueError(f"Wrong loot result in {loot_path}: {loot_names}")
            if material["kind"] == "stone":
                if part in {"bin", "center"}:
                    actual = sum(1 for entry in recipe["ingredients"] if entry.get("item") == material["ingredient"])
                    if actual != 4:
                        raise ValueError(f"Expected four material ingredients in {recipe_path}, found {actual}")
                elif recipe["pattern"] != ["S", "S"] or recipe["result"].get("count") != 2:
                    raise ValueError(f"Wrong stone vent recipe in {recipe_path}")

    print(f"Generated and validated {len(materials) * 3} recipes and {len(materials) * 3} loot tables.")


if __name__ == "__main__":
    generate()
