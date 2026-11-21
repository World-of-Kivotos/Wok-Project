from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
RECIPE_DIR = RESOURCES / "data/miningdim/recipes/farmer"
LOOT_DIR = RESOURCES / "data/miningdim/loot_tables/blocks"
LANG_DIR = RESOURCES / "assets/miningdim/lang"
WOODS = {
    "spruce": ("云杉木", "Spruce"),
    "birch": ("白桦木", "Birch"),
    "jungle": ("丛林木", "Jungle"),
    "acacia": ("金合欢木", "Acacia"),
    "dark_oak": ("深色橡木", "Dark Oak"),
    "mangrove": ("红树木", "Mangrove"),
    "cherry": ("樱花木", "Cherry"),
    "bamboo": ("竹", "Bamboo"),
    "crimson": ("绯红木", "Crimson"),
    "warped": ("诡异木", "Warped"),
}


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


def variant_id(wood: str, part: str) -> str:
    return f"{wood}_granary_{part}"


def translations() -> dict[str, dict[str, str]]:
    result = {
        "block.miningdim.granary_bin": {"zh_cn": "橡木储粮囤", "en_us": "Oak Storage Bin"},
        "block.miningdim.granary_center": {"zh_cn": "橡木粮仓账台", "en_us": "Oak Granary Ledger"},
        "block.miningdim.granary_vent": {"zh_cn": "橡木粮仓气窗", "en_us": "Oak Granary Air Vent"},
    }
    for wood, (zh_wood, en_wood) in WOODS.items():
        for part, zh_part, en_part in (
            ("bin", "储粮囤", "Storage Bin"),
            ("center", "粮仓账台", "Granary Ledger"),
            ("vent", "粮仓气窗", "Granary Air Vent"),
        ):
            key = f"block.miningdim.{variant_id(wood, part)}"
            result[key] = {"zh_cn": f"{zh_wood}{zh_part}", "en_us": f"{en_wood} {en_part}"}
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


def generate() -> None:
    templates = {
        part: read_json(RECIPE_DIR / f"granary_{part}.json")
        for part in ("bin", "center", "vent")
    }
    loot_templates = {
        part: read_json(LOOT_DIR / f"granary_{part}.json")
        for part in ("bin", "center", "vent")
    }

    for wood in WOODS:
        log = {"bamboo": "bamboo_block", "crimson": "crimson_stem", "warped": "warped_stem"}.get(
            wood, f"{wood}_log")
        substitutions = {
            "minecraft:oak_planks": f"minecraft:{wood}_planks",
            "minecraft:oak_log": f"minecraft:{log}",
            "minecraft:oak_fence": f"minecraft:{wood}_fence",
            "minecraft:oak_trapdoor": f"minecraft:{wood}_trapdoor",
        }
        for part in ("bin", "center", "vent"):
            output_id = variant_id(wood, part)
            recipe = json.loads(json.dumps(templates[part]))
            replace_values(recipe, substitutions | {"miningdim:granary_" + part: f"miningdim:{output_id}"})
            write_json(RECIPE_DIR / f"{output_id}.json", recipe)

            loot = json.loads(json.dumps(loot_templates[part]))
            replace_values(loot, {"miningdim:granary_" + part: f"miningdim:{output_id}"})
            write_json(LOOT_DIR / f"{output_id}.json", loot)

    names = translations()
    for locale in ("zh_cn", "en_us"):
        update_language(LANG_DIR / f"{locale}.json", locale,
                        {key: values[locale] for key, values in names.items()})

    for wood in WOODS:
        for part in ("bin", "center", "vent"):
            output_id = variant_id(wood, part)
            recipe_path = RECIPE_DIR / f"{output_id}.json"
            loot_path = LOOT_DIR / f"{output_id}.json"
            recipe = read_json(recipe_path)
            loot = read_json(loot_path)
            if recipe["result"]["item"] != f"miningdim:{output_id}":
                raise ValueError(f"Wrong recipe result in {recipe_path}")
            names_found = [entry.get("name") for pool in loot["pools"] for entry in pool["entries"]]
            if not names_found or any(name != f"miningdim:{output_id}" for name in names_found):
                raise ValueError(f"Wrong loot result in {loot_path}: {names_found}")

    print(f"Generated and validated {len(WOODS) * 3} recipes and {len(WOODS) * 3} loot tables.")


if __name__ == "__main__":
    generate()
