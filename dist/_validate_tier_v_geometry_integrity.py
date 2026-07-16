from __future__ import annotations

from dataclasses import dataclass, replace
import math
import re
import sys


sys.path.insert(0, str(__file__.rsplit("\\", 1)[0] if "\\" in __file__ else __file__.rsplit("/", 1)[0]))

from _validate_tier_iv_loadout_details import (  # noqa: E402
    Box,
    ContractError,
    balanced_block,
    load_model,
    parse_boxes,
    require,
    require_arm_clearance,
    validate_common,
)


MODELS = (
    "TactecArmorModel",
    "CpcMod1ArmorModel",
    "FcpcV5ArmorModel",
    "GladiatorSLightArmorModel",
    "HexatacHpcArmorModel",
    "B6B45GeneralArmorModel",
    "B6B45MedicArmorModel",
    "GzhelKArmorModel",
    "GladiatorSGrayArmorModel",
    "GladiatorSVikingArmorModel",
    "TtMkiiiArmorModel",
    "OspreyMk4AProtectionArmorModel",
    "Defender2ArmorModel",
    "GladiatorSDeathlessArmorModel",
    "RedutMArmorModel",
    "IotvGen4HighMobilityArmorModel",
    "IotvGen4FullProtectionArmorModel",
    "IotvGen4AssaultArmorModel",
    "KorundVmArmorModel",
)

SHOULDER_MODELS = {
    "B6B45GeneralArmorModel",
    "B6B45MedicArmorModel",
    "OspreyMk4AProtectionArmorModel",
    "GladiatorSDeathlessArmorModel",
    "IotvGen4FullProtectionArmorModel",
    "IotvGen4AssaultArmorModel",
}


@dataclass(frozen=True)
class UvFootprint:
    uid: str
    u0: int
    v0: int
    u1: int
    v1: int
    width: float
    height: float
    depth: float

    @classmethod
    def from_box(cls, box: Box) -> "UvFootprint":
        return cls(
            box.uid,
            box.u,
            box.v,
            box.u + math.ceil(2.0 * (box.width + box.depth)),
            box.v + math.ceil(box.height + box.depth),
            box.width,
            box.height,
            box.depth,
        )

    def overlap_area(self, other: "UvFootprint") -> int:
        width = min(self.u1, other.u1) - max(self.u0, other.u0)
        height = min(self.v1, other.v1) - max(self.v0, other.v0)
        return max(0, width) * max(0, height)

    def intentional_slot_reuse(self, other: "UvFootprint") -> bool:
        return (self.u0, self.v0) == (other.u0, other.v0)


def validate_uvs(name: str, source: str) -> int:
    deformations = {
        match.group(1): float(match.group(2).removesuffix("F"))
        for match in re.finditer(
            r"CubeDeformation\s+(\w+)\s*=\s*new\s+CubeDeformation\(\s*(-?[0-9.]+F?)\s*\)",
            source,
        )
    }
    boxes = parse_boxes(source, "all", deformations)
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


def load_tier_v_model(name: str):
    model = load_model(name)
    deformations = {
        match.group(1): float(match.group(2).removesuffix("F"))
        for match in re.finditer(
            r"CubeDeformation\s+(\w+)\s*=\s*new\s+CubeDeformation\(\s*(-?[0-9.]+F?)\s*\)",
            model.source,
        )
    }
    bones = dict(model.bones)
    for bone in ("right_arm", "left_arm"):
        if bones[bone]:
            continue
        route = re.search(
            rf'addOrReplaceChild\("{bone}",\s*(\w+)\(\)',
            model.source,
        )
        if route is None:
            continue
        method_name = route.group(1)
        method = re.search(
            rf"private\s+static\s+CubeListBuilder\s+{method_name}\s*\(\s*\)\s*\{{",
            model.source,
        )
        require(method is not None, f"{name}: missing helper {method_name}")
        block = balanced_block(model.source, method.end() - 1, f"{name}.{method_name}")
        bones[bone] = parse_boxes(block, bone, deformations)
    return replace(model, bones=bones)


def validate_shoulders(name: str, model) -> None:
    counts = {bone: len(model.bones[bone]) for bone in ("right_arm", "left_arm")}
    if name in SHOULDER_MODELS:
        require(all(count >= 1 for count in counts.values()),
                f"{name}: reference shoulder armor must be attached to both arm bones: {counts}")
    else:
        require(all(count == 0 for count in counts.values()),
                f"{name}: reference has no articulated shoulder armor: {counts}")


def main() -> None:
    for name in MODELS:
        model = load_tier_v_model(name)
        validate_common(model, 12)
        require_arm_clearance(model, model.body)
        validate_shoulders(name, model)
        uv_count = validate_uvs(name, model.source)
        print(f"MODEL {name}: body={len(model.body)} world={len(model.world_boxes)} uv={uv_count}")
    print("PASS tier V geometry integrity validation")


if __name__ == "__main__":
    try:
        main()
    except ContractError as error:
        raise SystemExit(str(error)) from error
