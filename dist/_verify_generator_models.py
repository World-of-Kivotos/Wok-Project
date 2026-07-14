from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Iterable

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/miningdim"
STYLES = ("industrial", "modern", "future")
EXPECTED_MODELS = {
    f"part_x{x}_z{z}_y{y}.json"
    for x in range(3)
    for z in range(2)
    for y in range(2)
}

FACE_NORMALS = {
    "north": (0.0, 0.0, -1.0),
    "south": (0.0, 0.0, 1.0),
    "west": (-1.0, 0.0, 0.0),
    "east": (1.0, 0.0, 0.0),
    "down": (0.0, -1.0, 0.0),
    "up": (0.0, 1.0, 0.0),
}

OVERLAP_EPSILON = 1.0e-4


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def texture_path(resource: str) -> Path:
    namespace, relative = resource.split(":", 1)
    require(namespace == "miningdim", f"unexpected texture namespace: {resource}")
    require(relative.startswith("block/"), f"unexpected texture path: {resource}")
    return ASSETS / "textures" / f"{relative}.png"


def add(left: tuple[float, float, float],
        right: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(a + b for a, b in zip(left, right))


def subtract(left: tuple[float, float, float],
             right: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(a - b for a, b in zip(left, right))


def scale(vector: tuple[float, float, float], factor: float) -> tuple[float, float, float]:
    return tuple(value * factor for value in vector)


def dot(left: tuple[float, ...], right: tuple[float, ...]) -> float:
    return sum(a * b for a, b in zip(left, right))


def cross(left: tuple[float, float, float],
          right: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    )


def normalize(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    length = math.sqrt(dot(vector, vector))
    require(length > 0.0, f"cannot normalize zero vector: {vector}")
    return scale(vector, 1.0 / length)


def rotate_vector(vector: tuple[float, float, float], axis: str,
                  angle_degrees: float) -> tuple[float, float, float]:
    angle = math.radians(angle_degrees)
    sine = math.sin(angle)
    cosine = math.cos(angle)
    x, y, z = vector
    if axis == "x":
        return (x, y * cosine - z * sine, y * sine + z * cosine)
    if axis == "y":
        return (x * cosine + z * sine, y, -x * sine + z * cosine)
    if axis == "z":
        return (x * cosine - y * sine, x * sine + y * cosine, z)
    raise AssertionError(f"unsupported rotation axis: {axis}")


def transform_point(point: tuple[float, float, float], rotation: dict | None,
                    offset: tuple[float, float, float]) -> tuple[float, float, float]:
    if rotation is None:
        return add(point, offset)
    origin = tuple(float(value) for value in rotation["origin"])
    rotated = rotate_vector(
        subtract(point, origin),
        str(rotation["axis"]),
        float(rotation["angle"]),
    )
    return add(add(rotated, origin), offset)


def face_vertices(start: tuple[float, float, float], end: tuple[float, float, float],
                  face: str) -> tuple[tuple[float, float, float], ...]:
    x0, y0, z0 = start
    x1, y1, z1 = end
    vertices = {
        "north": ((x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)),
        "south": ((x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)),
        "west": ((x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)),
        "east": ((x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)),
        "down": ((x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)),
        "up": ((x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)),
    }
    return vertices[face]


def project_polygon(vertices: Iterable[tuple[float, float, float]],
                    normal: tuple[float, float, float]) -> tuple[tuple[float, float], ...]:
    drop_axis = max(range(3), key=lambda axis: abs(normal[axis]))
    keep_axes = tuple(axis for axis in range(3) if axis != drop_axis)
    return tuple((vertex[keep_axes[0]], vertex[keep_axes[1]]) for vertex in vertices)


def polygons_overlap(first: tuple[tuple[float, float], ...],
                     second: tuple[tuple[float, float], ...]) -> bool:
    """Separating-axis test requiring positive area, not edge-only contact."""
    for polygon in (first, second):
        for index, current in enumerate(polygon):
            following = polygon[(index + 1) % len(polygon)]
            edge = (following[0] - current[0], following[1] - current[1])
            axis = (-edge[1], edge[0])
            axis_length = math.hypot(*axis)
            if axis_length <= OVERLAP_EPSILON:
                continue
            axis = (axis[0] / axis_length, axis[1] / axis_length)
            first_projection = [dot(point, axis) for point in first]
            second_projection = [dot(point, axis) for point in second]
            overlap = min(max(first_projection), max(second_projection)) - max(
                min(first_projection), min(second_projection))
            if overlap <= OVERLAP_EPSILON:
                return False
    return True


def find_coplanar_overlaps(style: str) -> list[str]:
    """Find same-facing quads sharing a plane and positive surface area.

    Opposite-facing internal seams are intentionally not treated as Z-fighting;
    they are a separate mesh-optimization concern. Same-facing overlaps make the
    depth buffer choose between two textures and are never valid in these models.
    """
    model_dir = ASSETS / "models/block/generator" / style
    face_groups: dict[
        tuple[float, float, float, float],
        list[tuple[str, int, str, tuple[tuple[float, float, float], ...],
                   tuple[float, float, float], float]],
    ] = {}

    for model_path in sorted(model_dir.glob("*.json")):
        stem = model_path.stem
        part_x = int(stem.split("_x", 1)[1].split("_", 1)[0])
        part_z = int(stem.split("_z", 1)[1].split("_", 1)[0])
        part_y = int(stem.rsplit("_y", 1)[1])
        offset = (part_x * 16.0, part_y * 16.0, part_z * 16.0)
        model = json.loads(model_path.read_text(encoding="utf-8"))

        for element_index, element in enumerate(model.get("elements", [])):
            start = tuple(float(value) for value in element["from"])
            end = tuple(float(value) for value in element["to"])
            rotation = element.get("rotation")
            for face in element.get("faces", {}):
                vertices = tuple(
                    transform_point(vertex, rotation, offset)
                    for vertex in face_vertices(start, end, face)
                )
                normal = FACE_NORMALS[face]
                if rotation is not None:
                    normal = rotate_vector(
                        normal,
                        str(rotation["axis"]),
                        float(rotation["angle"]),
                    )
                normal = normalize(normal)
                plane = dot(normal, vertices[0])
                key = (*tuple(round(value, 6) for value in normal), round(plane, 5))
                face_groups.setdefault(key, []).append(
                    (model_path.name, element_index, face, vertices, normal, plane)
                )

    conflicts: list[str] = []
    for group in face_groups.values():
        for first_index, first in enumerate(group):
            for second in group[first_index + 1:]:
                if dot(first[4], second[4]) < 1.0 - 1.0e-7:
                    continue
                if abs(first[5] - second[5]) > OVERLAP_EPSILON:
                    continue
                first_polygon = project_polygon(first[3], first[4])
                second_polygon = project_polygon(second[3], second[4])
                if polygons_overlap(first_polygon, second_polygon):
                    conflicts.append(
                        f"{first[0]} element {first[1]} {first[2]} overlaps "
                        f"{second[0]} element {second[1]} {second[2]}"
                    )
    return conflicts


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
        z = int(stem.split("_z", 1)[1].split("_", 1)[0])
        y = int(stem.rsplit("_y", 1)[1])
        occupied_slots.add((x, z, y))

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

    require(occupied_slots == {(x, z, y) for x in range(3) for z in range(2) for y in range(2)},
            f"{style}: layout is not exactly 3x2x2")
    for texture in referenced_textures:
        with Image.open(texture) as image:
            alpha_extrema = image.convert("RGBA").getchannel("A").getextrema()
        require(alpha_extrema == (255, 255),
                f"{style}: solid-layer texture must be fully opaque: {texture} has alpha {alpha_extrema}")
    require(total_elements >= 120,
            f"{style}: only {total_elements} elements; geometry is too simple for the approved preview")
    coplanar_overlaps = find_coplanar_overlaps(style)
    require(not coplanar_overlaps,
            f"{style}: found {len(coplanar_overlaps)} same-facing coplanar overlaps; "
            f"first conflicts: {coplanar_overlaps[:8]}")
    return total_elements, len(referenced_textures)


def main() -> None:
    for style in STYLES:
        elements, textures = verify_style(style)
        print(f"PASS {style}: 12 parts, {elements} elements, {textures} textures, layout 3x2x2")


if __name__ == "__main__":
    main()
