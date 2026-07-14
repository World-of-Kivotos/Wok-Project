from __future__ import annotations

import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/miningdim"
STYLES = ("industrial", "modern", "future")
EXPECTED_MODELS = {
    f"part_x{x}_y{y}.json"
    for x in range(3)
    for y in range(2)
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def texture_path(resource: str) -> Path:
    namespace, relative = resource.split(":", 1)
    require(namespace == "miningdim", f"unexpected texture namespace: {resource}")
    require(relative.startswith("block/"), f"unexpected texture path: {resource}")
    return ASSETS / "textures" / f"{relative}.png"


def verify_style(style: str) -> tuple[int, int]:
    model_dir = ASSETS / "models/block/generator" / style
    actual = {path.name for path in model_dir.glob("*.json")}
    require(actual == EXPECTED_MODELS,
            f"{style}: expected {sorted(EXPECTED_MODELS)}, got {sorted(actual)}")

    total_elements = 0
    referenced_textures: set[Path] = set()
    occupied_slots: set[tuple[int, int, int]] = set()

    for model_path in sorted(model_dir.glob("*.json")):
        stem = model_path.stem
        x = int(stem.split("_x", 1)[1].split("_", 1)[0])
        y = int(stem.rsplit("_y", 1)[1])
        occupied_slots.add((x, 0, y))

        model = json.loads(model_path.read_text(encoding="utf-8"))
        require(model.get("parent") == "minecraft:block/block",
                f"{model_path}: parent must be minecraft:block/block")
        textures = model.get("textures", {})
        require("particle" in textures, f"{model_path}: missing particle texture")
        for key, resource in textures.items():
            require(isinstance(resource, str) and not resource.startswith("#"),
                    f"{model_path}: texture {key} must be a concrete resource")
            require(resource.startswith(f"miningdim:block/generator/{style}/"),
                    f"{model_path}: cross-style or legacy texture reference {resource}")
            resolved = texture_path(resource)
            require(resolved.is_file(), f"{model_path}: missing texture {resolved}")
            referenced_textures.add(resolved)

        elements = model.get("elements", [])
        require(elements, f"{model_path}: model has no geometry")
        total_elements += len(elements)
        for index, element in enumerate(elements):
            start = element.get("from")
            end = element.get("to")
            require(isinstance(start, list) and isinstance(end, list)
                    and len(start) == 3 and len(end) == 3,
                    f"{model_path} element {index}: invalid bounds")
            for axis, (low, high) in enumerate(zip(start, end)):
                require(isinstance(low, (int, float)) and isinstance(high, (int, float)),
                        f"{model_path} element {index}: non-numeric bounds")
                require(math.isfinite(low) and math.isfinite(high),
                        f"{model_path} element {index}: non-finite bounds")
                require(0.0 <= low < high <= 16.0,
                        f"{model_path} element {index} axis {axis}: {low}..{high} outside 0..16")
            rotation = element.get("rotation")
            if rotation is not None:
                require(rotation.get("axis") in {"x", "y", "z"},
                        f"{model_path} element {index}: invalid rotation axis")
                require(rotation.get("angle") in {-45, -22.5, 0, 22.5, 45},
                        f"{model_path} element {index}: invalid Minecraft rotation angle")
            faces = element.get("faces", {})
            require(faces, f"{model_path} element {index}: missing faces")
            for face_name, face in faces.items():
                require(face_name in {"north", "south", "east", "west", "up", "down"},
                        f"{model_path} element {index}: invalid face {face_name}")
                texture_ref = face.get("texture", "")
                require(texture_ref.startswith("#") and texture_ref[1:] in textures,
                        f"{model_path} element {index}: unresolved texture {texture_ref}")

    require(occupied_slots == {(x, 0, y) for x in range(3) for y in range(2)},
            f"{style}: layout is not exactly 3x1x2")
    require(total_elements >= 60,
            f"{style}: only {total_elements} elements; geometry is too simple for the approved preview")
    return total_elements, len(referenced_textures)


def main() -> None:
    for style in STYLES:
        elements, textures = verify_style(style)
        print(f"PASS {style}: 6 parts, {elements} elements, {textures} textures, layout 3x1x2")


if __name__ == "__main__":
    main()
