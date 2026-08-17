from __future__ import annotations

import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "miningdim"
DATA = RESOURCES / "data"

GENERATORS = (
    ("industrial_generator", "industrial", "Industrial Generator", "工业发电机", "part_x2_z0_y0"),
    ("modern_generator", "modern", "Modern Generator", "现代发电机", "part_x0_z0_y0"),
    (
        "future_energy_generator",
        "future",
        "Future Energy Generator",
        "未来能源发电机",
        "part_x1_z0_y0",
    ),
)
FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
PARTS = tuple(
    f"x{x}_z{z}_y{y}"
    for x in range(3)
    for z in range(2)
    for y in range(2)
)


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def make_blockstate(style: str) -> dict[str, Any]:
    variants: dict[str, Any] = {}
    for part in PARTS:
        model = f"miningdim:block/generator/{style}/part_{part}"
        for facing, rotation in FACINGS:
            variants[f"part={part},facing={facing}"] = {
                "model": model,
                "y": rotation,
            }
    return {"variants": variants}


def make_loot_table(block_id: str) -> dict[str, Any]:
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1,
                "entries": [
                    {
                        "type": "minecraft:item",
                        "name": f"miningdim:{block_id}",
                    }
                ],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }
        ],
        "random_sequence": f"miningdim:blocks/{block_id}",
    }


def extend_block_tag(path: Path) -> None:
    payload = read_json(path) if path.exists() else {"replace": False, "values": []}
    values = payload["values"]
    for block_id, *_ in GENERATORS:
        value = f"miningdim:{block_id}"
        if value not in values:
            values.append(value)
    write_json(path, payload)


def extend_languages() -> None:
    language_entries = {
        "en_us": {
            "itemGroup.miningdim_power": "Power Systems",
            **{
                f"block.miningdim.{block_id}": english_name
                for block_id, _, english_name, _, _ in GENERATORS
            },
            **{
                f"item.miningdim.{block_id}": english_name
                for block_id, _, english_name, _, _ in GENERATORS
            },
        },
        "zh_cn": {
            "itemGroup.miningdim_power": "能源系统",
            **{
                f"block.miningdim.{block_id}": chinese_name
                for block_id, _, _, chinese_name, _ in GENERATORS
            },
            **{
                f"item.miningdim.{block_id}": chinese_name
                for block_id, _, _, chinese_name, _ in GENERATORS
            },
        },
    }

    for locale, entries in language_entries.items():
        path = ASSETS / "lang" / f"{locale}.json"
        payload = read_json(path)
        payload.update(entries)
        write_json(path, payload)


def generate() -> None:
    for block_id, style, _, _, item_part in GENERATORS:
        write_json(ASSETS / "blockstates" / f"{block_id}.json", make_blockstate(style))
        write_json(
            ASSETS / "models" / "item" / f"{block_id}.json",
            {"parent": f"miningdim:block/generator/{style}/{item_part}"},
        )
        write_json(
            DATA / "miningdim" / "loot_tables" / "blocks" / f"{block_id}.json",
            make_loot_table(block_id),
        )

    extend_block_tag(DATA / "minecraft" / "tags" / "blocks" / "mineable" / "pickaxe.json")
    extend_block_tag(DATA / "minecraft" / "tags" / "blocks" / "needs_stone_tool.json")
    extend_languages()


def validate() -> None:
    generated_json = []
    total_variants = 0

    for block_id, style, _, _, _ in GENERATORS:
        blockstate_path = ASSETS / "blockstates" / f"{block_id}.json"
        item_path = ASSETS / "models" / "item" / f"{block_id}.json"
        loot_path = DATA / "miningdim" / "loot_tables" / "blocks" / f"{block_id}.json"
        generated_json.extend((blockstate_path, item_path, loot_path))

        blockstate = read_json(blockstate_path)
        variants = blockstate["variants"]
        if len(variants) != 48:
            raise ValueError(f"{block_id} has {len(variants)} variants; expected 48")
        total_variants += len(variants)

        for variant in variants.values():
            prefix = "miningdim:"
            model = variant["model"]
            if not model.startswith(prefix):
                raise ValueError(f"Unexpected model reference: {model}")
            model_path = ASSETS / "models" / f"{model.removeprefix(prefix)}.json"
            if not model_path.is_file():
                raise FileNotFoundError(model_path)

        expected_models = {
            ASSETS / "models" / "block" / "generator" / style / f"part_{part}.json"
            for part in PARTS
        }
        missing_models = sorted(path for path in expected_models if not path.is_file())
        if missing_models:
            raise FileNotFoundError(missing_models[0])

    generated_json.extend(
        (
            ASSETS / "lang" / "en_us.json",
            ASSETS / "lang" / "zh_cn.json",
            DATA / "minecraft" / "tags" / "blocks" / "mineable" / "pickaxe.json",
            DATA / "minecraft" / "tags" / "blocks" / "needs_stone_tool.json",
        )
    )
    for path in generated_json:
        read_json(path)

    if total_variants != 144:
        raise ValueError(f"Found {total_variants} total variants; expected 144")

    print(
        f"Validated {len(generated_json)} JSON files, {total_variants} blockstate variants, "
        f"and {len(GENERATORS) * len(PARTS)} referenced block models."
    )


if __name__ == "__main__":
    generate()
    validate()
