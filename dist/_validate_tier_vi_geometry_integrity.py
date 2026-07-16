from __future__ import annotations

from dataclasses import replace
import math
import re

from _validate_tier_iv_loadout_details import (
    Box,
    ContractError,
    axis_overlap,
    balanced_block,
    load_model,
    parse_boxes,
    require,
    require_arm_clearance,
    require_connected_model,
    require_no_coplanar_faces,
    require_no_duplicates,
    validate_common,
)
from _validate_tier_v_geometry_integrity import UvFootprint, load_tier_v_model


MODELS = (
    "HexgridArmorModel",
    "SlickArmorModel",
    "StichDefenseMod2ArmorModel",
    "B6B43ZabraloShArmorModel",
    "ThorIntegratedArmorModel",
)

SHOULDER_MODELS = {
    "B6B43ZabraloShArmorModel",
    "ThorIntegratedArmorModel",
}


def method_boxes(source: str, name: str) -> tuple[Box, ...]:
    match = re.search(
        rf"(?:private\s+)?static\s+(?:CubeListBuilder|void)\s+{name}\s*\([^)]*\)\s*\{{",
        source,
    )
    require(match is not None, f"missing helper method {name}")
    block = balanced_block(source, match.end() - 1, name)
    return parse_boxes(block, name, {})


def validate_uv_boxes(name: str, boxes: tuple[Box, ...]) -> int:
    footprints = [UvFootprint.from_box(box) for box in boxes]
    for footprint in footprints:
        require(0 <= footprint.u0 < footprint.u1 <= 128,
                f"{name}.{footprint.uid}: U footprint outside atlas")
        require(0 <= footprint.v0 < footprint.v1 <= 128,
                f"{name}.{footprint.uid}: V footprint outside atlas")
    conflicts: list[str] = []
    for index, first in enumerate(footprints):
        for second in footprints[index + 1:]:
            if first.overlap_area(second) and not first.intentional_slot_reuse(second):
                conflicts.append(f"{first.uid}/{second.uid}")
    require(not conflicts, f"{name}: overlapping unrelated UV footprints: {conflicts[:8]}")
    return len(footprints)


def validate_standard_uvs(model) -> int:
    deformations = {
        match.group(1): float(match.group(2).removesuffix("F"))
        for match in re.finditer(
            r"CubeDeformation\s+(\w+)\s*=\s*new\s+CubeDeformation\(\s*(-?[0-9.]+F?)\s*\)",
            model.source,
        )
    }
    boxes = parse_boxes(model.source, "all", deformations)
    return validate_uv_boxes(model.name, boxes)


def validate_hexgrid() -> tuple[object, int, int]:
    model = load_tier_v_model("HexgridArmorModel")
    require(len(model.body) == 20, f"Hexgrid base geometry regressed: {len(model.body)}")
    require(all(not model.bones[bone] for bone in ("right_arm", "left_arm")),
            "Hexgrid must not acquire articulated shoulder armor")
    require_no_duplicates(model)
    require_no_coplanar_faces(model)
    require_connected_model(model)
    require_arm_clearance(model, model.body)

    front_plate = model.body[0]
    side_sheets = [
        box for box in model.body
        if box.height >= 5.40 and box.depth >= 4.20 and abs(box.center_x) >= 3.60
    ]
    side_rails = [
        box for box in model.body
        if 0.70 <= box.height <= 0.80 and box.depth >= 3.60
        and abs(box.center_x) >= 3.60
    ]
    require(len(side_sheets) == 2 and len(side_rails) == 4,
            f"Hexgrid side layering regressed: sheets={side_sheets}, rails={side_rails}")
    require(all(abs(max(abs(box.x), abs(box.x1)) - 3.90) <= 0.001
                for box in side_sheets),
            "Hexgrid side sheets lost their inset anti-flicker plane")
    require(all(abs(max(abs(box.x), abs(box.x1)) - 3.94) <= 0.001
                for box in side_rails),
            "Hexgrid side rails lost their middle anti-flicker plane")
    horizontal_rims = [
        box for box in model.body
        if abs(box.width - 6.60) <= 0.001
        and abs(box.height - 0.20) <= 0.001
        and abs(box.depth - 0.20) <= 0.001
    ]
    require(len(horizontal_rims) == 2,
            f"Hexgrid must retain two horizontal plate rims: {horizontal_rims}")
    bottom_rim = max(horizontal_rims, key=lambda box: box.y)
    require(abs(bottom_rim.y1 - front_plate.y1) >= 0.03,
            "Hexgrid lower rim shares an exposed plane with the front plate")

    source = model.source
    braces = method_boxes(source, "addFlexibleSideBraces")
    edges = method_boxes(source, "addHoneycombCell")
    require(len(braces) == 2, f"Hexgrid must retain two diagonal side braces: {len(braces)}")
    require(len(edges) == 6, f"Hexgrid cells must retain six modeled edges: {len(edges)}")
    require("HONEYCOMB_ROWS = 8" in source,
            "Hexgrid must retain the eight-row honeycomb field")
    require("row % 2 == 0 ? 5 : 4" in source,
            "Hexgrid honeycomb must retain staggered 5/4-column rows")
    require(source.count("addHoneycombCell(body") == 1,
            "Hexgrid honeycomb must be generated through one bounded call site")
    require(source.count("offsetAndRotation") >= 6,
            "Hexgrid honeycomb/side braces lost their angled construction")
    require("offsetAndRotation(-3.88F" in source
            and "offsetAndRotation(3.88F" in source,
            "Hexgrid diagonal braces lost their outer 3.98 anti-flicker plane")

    local_fronts = sorted(round(box.z, 3) for box in edges)
    require(local_fronts == [-0.12, -0.11, -0.10, -0.09, -0.08, -0.07],
            f"Hexgrid honeycomb faces lost anti-flicker depth staggering: {local_fronts}")
    require(all(abs(box.z1 - 0.10) <= 0.001 for box in edges),
            "Hexgrid honeycomb edges must overlap the plate by a common 0.02 back seam")
    require(source.count("-2.80F") == 6,
            "Hexgrid honeycomb edges lost their common plate-depth placement")

    uv_count = validate_uv_boxes(
        model.name,
        model.body + braces + edges,
    )
    runtime_count = len(model.body) + len(braces) + 36 * len(edges)
    require(runtime_count == 238, f"Hexgrid runtime cuboid count regressed: {runtime_count}")
    return model, uv_count, runtime_count


def validate_shoulders(name: str, model) -> None:
    counts = {bone: len(model.bones[bone]) for bone in ("right_arm", "left_arm")}
    if name in SHOULDER_MODELS:
        require(all(count >= 4 for count in counts.values()),
                f"{name}: reference shoulder armor must remain articulated: {counts}")
        minimum_span = 6.20 if name == "B6B43ZabraloShArmorModel" else 5.50
        for bone in ("right_arm", "left_arm"):
            pieces = model.bones[bone]
            span = max(box.y1 for box in pieces) - min(box.y for box in pieces)
            require(span >= minimum_span,
                    f"{name}.{bone}: segmented upper-arm coverage is too short: {span:.2f}")
    else:
        require(all(count == 0 for count in counts.values()),
                f"{name}: reference has no articulated shoulder armor: {counts}")


def require_thor_body_sleeve_clearance(model) -> None:
    collisions: list[str] = []
    pivots = {
        "right_arm": (-5.0, 2.0, 0.0),
        "left_arm": (5.0, 2.0, 0.0),
    }
    for body_box in model.body:
        for bone, pivot in pivots.items():
            for sleeve_box in model.bones[bone]:
                sleeve = sleeve_box.shifted(*pivot)
                overlap = (
                    axis_overlap(body_box.gx, body_box.gx1, sleeve.gx, sleeve.gx1),
                    axis_overlap(body_box.gy, body_box.gy1, sleeve.gy, sleeve.gy1),
                    axis_overlap(body_box.gz, body_box.gz1, sleeve.gz, sleeve.gz1),
                )
                if all(value > 0.001 for value in overlap):
                    collisions.append(
                        f"{body_box.uid}/{bone}.{sleeve_box.uid}:{overlap}"
                    )
    require(not collisions,
            f"{model.name}: fixed body armor intersects inflated shoulder sleeves: {collisions[:8]}")


def load_tier_vi_model(name: str):
    if name == "HexgridArmorModel":
        return load_tier_v_model(name)
    return load_tier_v_model(name)


def main() -> None:
    hexgrid, uv_count, runtime_count = validate_hexgrid()
    print(f"MODEL {hexgrid.name}: base={len(hexgrid.body)} runtime={runtime_count} uv_slots={uv_count}")

    for name in MODELS[1:]:
        model = load_tier_vi_model(name)
        if name == "ThorIntegratedArmorModel":
            validate_common(model, 30)
            require_arm_clearance(model, model.body)
            require_thor_body_sleeve_clearance(model)
        else:
            minimum = 30 if name in {"SlickArmorModel", "StichDefenseMod2ArmorModel"} else 20
            validate_common(model, minimum)
            require_arm_clearance(model, model.body)
        validate_shoulders(name, model)
        uv_count = validate_standard_uvs(model)
        print(f"MODEL {name}: body={len(model.body)} world={len(model.world_boxes)} uv={uv_count}")
    print("PASS tier VI geometry integrity validation")


if __name__ == "__main__":
    try:
        main()
    except ContractError as error:
        raise SystemExit(str(error)) from error
