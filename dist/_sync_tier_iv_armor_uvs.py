import argparse
import importlib.util
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent.parent
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"

TARGETS = (
    ("medium", "b6b13", "B6B13ArmorModel.java"),
    ("medium", "ana", "AnaM1ArmorModel.java"),
    ("medium", "a18", "A18SkandaArmorModel.java"),
    ("medium", "avs", "AvsArmorModel.java"),
    ("medium", "stich", "StichProfiV2ArmorModel.java"),
    ("medium", "tv110", "Tv110ArmorModel.java"),
    ("heavy", "b6b23_mountain", "B6B23MountainFloraArmorModel.java"),
    ("heavy", "b6b5_flora", "B6B5FloraArmorModel.java"),
)

BOX = re.compile(
    r"(?P<head>\.texOffs\()\s*\d+\s*,\s*\d+"
    r"(?P<tail>\)\s*\.addBox\("
    r"[^,]+,[^,]+,[^,]+,\s*"
    r"(?P<width>[0-9.]+)F,\s*(?P<height>[0-9.]+)F,\s*(?P<depth>[0-9.]+)F\))",
    re.DOTALL,
)


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load texture generator: {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def rewrite(source: str, cubes, label: str) -> tuple[str, int, int]:
    index = 0
    changed = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal index, changed
        if index >= len(cubes):
            raise RuntimeError(f"{label}: Java has more cuboids than generator")
        cube = cubes[index]
        actual_size = tuple(float(match.group(name)) for name in ("width", "height", "depth"))
        expected_size = (cube.width, cube.height, cube.depth)
        if any(abs(actual - expected) > 1.0e-6 for actual, expected in zip(actual_size, expected_size)):
            raise RuntimeError(
                f"{label}[{index}] size mismatch: Java {actual_size} != generator {expected_size} ({cube.name})"
            )
        expected_uv = f"{int(cube.u)}, {int(cube.v)}"
        replacement = f"{match.group('head')}{expected_uv}{match.group('tail')}"
        if replacement != match.group(0):
            changed += 1
        index += 1
        return replacement

    rewritten = BOX.sub(replace, source)
    if index != len(cubes):
        raise RuntimeError(f"{label}: Java has {index} cuboids, generator has {len(cubes)}")
    return rewritten, index, changed


def main() -> None:
    parser = argparse.ArgumentParser(description="Synchronize Tier IV Java texOffs with packed texture generators.")
    parser.add_argument("--write", action="store_true", help="Rewrite Java texOffs; otherwise only verify.")
    args = parser.parse_args()

    medium = load_module("tier_iv_medium_textures", "_make_tier_iv_medium_armor_textures.py")
    heavy = load_module("tier_iv_heavy_textures", "_make_tier_iv_heavy_armor_textures.py")
    generators = {"medium": medium, "heavy": heavy}

    total_changed = 0
    for generator_name, model_name, filename in TARGETS:
        path = CLIENT / filename
        source = path.read_text(encoding="utf-8")
        rewritten, count, changed = rewrite(
            source,
            generators[generator_name].MODELS[model_name],
            f"{filename}:{model_name}",
        )
        total_changed += changed
        if args.write and rewritten != source:
            path.write_text(rewritten, encoding="utf-8", newline="")
        status = "written" if args.write and changed else "ok" if not changed else "needs-sync"
        print(f"{filename}: cuboids={count} changed_uvs={changed} status={status}")

    if not args.write and total_changed:
        raise RuntimeError(f"Tier IV Java UVs require synchronization: {total_changed} entries")
    print(f"PASS tier IV Java UV synchronization entries_changed={total_changed}")


if __name__ == "__main__":
    main()
