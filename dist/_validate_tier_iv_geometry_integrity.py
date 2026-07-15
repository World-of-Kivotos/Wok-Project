from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "src/main/java/com/miningdim/job/engineer/armor/client"
EPSILON = 1.0e-4
MIN_SEAM_OFFSET = 0.025
MIN_ATTACHMENT_OVERLAP = 0.019

NUMBER = r"-?(?:\d+(?:\.\d*)?|\.\d+)F?"
ADD_BOX = re.compile(
    rf"\.addBox\(\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*"
    rf"({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})(?:\s*,[^)]*)?\)"
)


class GeometryError(AssertionError):
    pass


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    z: float
    width: float
    height: float
    depth: float

    @property
    def x1(self) -> float:
        return self.x + self.width

    @property
    def y1(self) -> float:
        return self.y + self.height

    @property
    def z1(self) -> float:
        return self.z + self.depth

    def shifted(self, x: float, y: float, z: float) -> "Box":
        return Box(self.x + x, self.y + y, self.z + z, self.width, self.height, self.depth)


def number(value: str) -> float:
    return float(value.removesuffix("F"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise GeometryError(message)


def source(model: str) -> str:
    path = CLIENT / f"{model}.java"
    require(path.is_file(), f"{model}: missing Java model: {path}")
    return path.read_text(encoding="utf-8")


def method_block(java: str, model: str, method_names: tuple[str, ...]) -> str:
    for method_name in method_names:
        marker = f"private static CubeListBuilder {method_name}()"
        marker_start = java.find(marker)
        if marker_start < 0:
            continue
        opening = java.find("{", marker_start + len(marker))
        require(opening >= 0, f"{model}.{method_name}: missing method body")
        depth = 0
        for index in range(opening, len(java)):
            if java[index] == "{":
                depth += 1
            elif java[index] == "}":
                depth -= 1
                if depth == 0:
                    return java[opening + 1:index]
        raise GeometryError(f"{model}.{method_name}: unterminated method body")
    raise GeometryError(f"{model}: missing method {method_names}")


def boxes(block: str) -> list[Box]:
    return [Box(*(number(value) for value in match)) for match in ADD_BOX.findall(block)]


def body_boxes(model: str) -> list[Box]:
    return boxes(method_block(source(model), model, ("createBody", "body")))


def named_box(model: str, model_boxes: list[Box], index: int, part: str) -> Box:
    require(index < len(model_boxes), f"{model}.{part}: missing cuboid index {index}")
    return model_boxes[index]


def axis_overlap(first_start: float, first_end: float, second_start: float, second_end: float) -> float:
    return min(first_end, second_end) - max(first_start, second_start)


def overlaps(first: Box, second: Box) -> tuple[float, float, float]:
    return (
        axis_overlap(first.x, first.x1, second.x, second.x1),
        axis_overlap(first.y, first.y1, second.y, second.y1),
        axis_overlap(first.z, first.z1, second.z, second.z1),
    )


def require_connected(model: str, first_name: str, first: Box, second_name: str, second: Box) -> None:
    overlap = overlaps(first, second)
    require(
        all(value >= MIN_ATTACHMENT_OVERLAP for value in overlap),
        f"{model}.{first_name}: floating from {second_name}; overlap xyz={overlap}",
    )


def require_not_coplanar(model: str, first_name: str, first_value: float, second_name: str,
                         second_value: float, axis: str) -> None:
    offset = abs(first_value - second_value)
    require(
        offset >= MIN_SEAM_OFFSET,
        f"{model}.{first_name}: exposed {axis} face is coplanar with {second_name}; offset={offset:.4f}",
    )


LIGHT_BRIDGES = {
    "MmacArmorModel": (9, 10, 5, 6, 7, 8),
    "RbavAfArmorModel": (9, 10, 5, 6, 7, 8),
    "StrandhoggArmorModel": (10, 11, 6, 7, 8, 9),
    "TrooperTfoArmorModel": (10, 11, 6, 7, 8, 9),
    "BansheeArmorModel": (9, 10, 5, 6, 7, 8),
}

LIGHT_POUCH_LIDS = (
    "MmacArmorModel",
    "RbavAfArmorModel",
    "StrandhoggArmorModel",
    "BansheeArmorModel",
)


def validate_light_bridges() -> None:
    for model, indices in LIGHT_BRIDGES.items():
        model_boxes = body_boxes(model)
        left_bridge, right_bridge, front_left, front_right, rear_left, rear_right = (
            named_box(model, model_boxes, index, name)
            for index, name in zip(
                indices,
                ("left_bridge", "right_bridge", "front_left_strap", "front_right_strap",
                 "rear_left_strap", "rear_right_strap"),
            )
        )
        for side, bridge, front, rear in (
            ("left", left_bridge, front_left, rear_left),
            ("right", right_bridge, front_right, rear_right),
        ):
            require_connected(model, f"{side}_bridge", bridge, f"front_{side}_strap", front)
            require_connected(model, f"{side}_bridge", bridge, f"rear_{side}_strap", rear)
            require_not_coplanar(model, f"{side}_bridge", bridge.x, f"front_{side}_strap", front.x, "x-min")
            require_not_coplanar(model, f"{side}_bridge", bridge.x1, f"front_{side}_strap", front.x1, "x-max")
        print(f"LIGHT {model}: shoulder bridges connected and face-offset")


def loop_pouch_boxes(model: str) -> tuple[Box, Box]:
    java = source(model)
    body_pattern = re.compile(
        rf"\.addBox\(\s*x\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*"
        rf"({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*\)"
    )
    lid_pattern = re.compile(
        rf"\.addBox\(\s*x\s*-\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*,\s*"
        rf"({NUMBER})\s*,\s*({NUMBER})\s*,\s*({NUMBER})\s*\)"
    )
    body_matches = body_pattern.findall(java)
    lid_matches = lid_pattern.findall(java)
    require(len(body_matches) == 1, f"{model}.pouch_body: expected one x-based loop cuboid, got {len(body_matches)}")
    require(len(lid_matches) == 1, f"{model}.pouch_lid: expected one x-offset loop cuboid, got {len(lid_matches)}")
    body_y, body_z, body_w, body_h, body_d = (number(value) for value in body_matches[0])
    lid_offset, lid_y, lid_z, lid_w, lid_h, lid_d = (number(value) for value in lid_matches[0])
    return (
        Box(0.0, body_y, body_z, body_w, body_h, body_d),
        Box(-lid_offset, lid_y, lid_z, lid_w, lid_h, lid_d),
    )


def validate_light_pouch_lids() -> None:
    for model in LIGHT_POUCH_LIDS:
        pouch, lid = loop_pouch_boxes(model)
        overlap = overlaps(pouch, lid)
        require(
            overlap[1] >= 0.05 and overlap[2] >= 0.05,
            f"{model}.pouch_lid: lid is floating; yz overlap={overlap[1:]}",
        )
        require(
            pouch.x - lid.x >= MIN_SEAM_OFFSET and lid.x1 - pouch.x1 >= MIN_SEAM_OFFSET,
            f"{model}.pouch_lid: lid no longer overhangs both pouch sides; "
            f"left={pouch.x - lid.x:.4f}, right={lid.x1 - pouch.x1:.4f}",
        )
        require_not_coplanar(model, "pouch_lid", lid.z, "pouch_body", pouch.z, "front-z")
        print(f"LIGHT {model}: pouch lids embedded and face-offset")


def validate_b6b13() -> None:
    model = "B6B13ArmorModel"
    model_boxes = body_boxes(model)
    require(len(model_boxes) == 18, f"{model}: expected 18 body cuboids, got {len(model_boxes)}")
    front_yoke = named_box(model, model_boxes, 0, "front_yoke")
    front_lower = named_box(model, model_boxes, 2, "front_lower")
    rear_yoke = named_box(model, model_boxes, 3, "rear_yoke")
    collar_front_left = named_box(model, model_boxes, 7, "collar_front_left")
    collar_front_right = named_box(model, model_boxes, 8, "collar_front_right")
    collar_rear = named_box(model, model_boxes, 9, "collar_rear")
    collar_left = named_box(model, model_boxes, 10, "collar_left")
    collar_right = named_box(model, model_boxes, 11, "collar_right")
    apron = named_box(model, model_boxes, 12, "long_apron")
    front_root_left = named_box(model, model_boxes, 14, "front_collar_root_left")
    front_root_right = named_box(model, model_boxes, 15, "front_collar_root_right")
    rear_root_left = named_box(model, model_boxes, 16, "rear_collar_root_left")
    rear_root_right = named_box(model, model_boxes, 17, "rear_collar_root_right")

    for first_name, first, second_name, second in (
        ("collar_front_left", collar_front_left, "collar_left", collar_left),
        ("collar_front_right", collar_front_right, "collar_right", collar_right),
        ("collar_rear", collar_rear, "collar_left", collar_left),
        ("collar_rear", collar_rear, "collar_right", collar_right),
        ("front_collar_root_left", front_root_left, "collar_left", collar_left),
        ("front_collar_root_right", front_root_right, "collar_right", collar_right),
        ("rear_collar_root_left", rear_root_left, "collar_left", collar_left),
        ("rear_collar_root_right", rear_root_right, "collar_right", collar_right),
        ("front_collar_root_left", front_root_left, "front_yoke", front_yoke),
        ("front_collar_root_right", front_root_right, "front_yoke", front_yoke),
        ("rear_collar_root_left", rear_root_left, "rear_yoke", rear_yoke),
        ("rear_collar_root_right", rear_root_right, "rear_yoke", rear_yoke),
    ):
        require_connected(model, first_name, first, second_name, second)

    for name, root, yoke in (
        ("front_collar_root_left", front_root_left, front_yoke),
        ("front_collar_root_right", front_root_right, front_yoke),
    ):
        require_not_coplanar(model, name, root.z, "front_yoke", yoke.z, "front-z")
        require_not_coplanar(model, name, root.z1, "front_yoke", yoke.z1, "rear-z")
    for name, root in (("rear_collar_root_left", rear_root_left), ("rear_collar_root_right", rear_root_right)):
        require_not_coplanar(model, name, root.z, "rear_yoke", rear_yoke.z, "front-z")
        require_not_coplanar(model, name, root.z1, "rear_yoke", rear_yoke.z1, "rear-z")

    require_connected(model, "long_apron", apron, "front_lower", front_lower)
    require_not_coplanar(model, "long_apron", apron.z, "front_lower", front_lower.z, "front-z")
    print("MEDIUM B6B13ArmorModel: collar rooted, corner-connected, apron face-offset")


def validate_b6b3_side_pouch() -> None:
    model = "B6B3Tm01MArmorModel"
    model_boxes = body_boxes(model)
    left_side = named_box(model, model_boxes, 4, "left_side")
    side_pouch = named_box(model, model_boxes, 18, "side_pouch")
    require_connected(model, "side_pouch", side_pouch, "left_side", left_side)
    protrusion = left_side.x - side_pouch.x
    require(protrusion >= 0.05, f"{model}.side_pouch: embedded/coplanar with left side; protrusion={protrusion:.4f}")
    print(f"MEDIUM {model}: side pouch protrusion={protrusion:.2f}")


def validate_medium_attachments() -> None:
    rules = {
        "B6B3Tm01MArmorModel": (("center_flap", 10, "front_upper", 0),
                                  ("pouch_far_left", 13, "front_lower", 1),
                                  ("pouch_left", 14, "front_lower", 1),
                                  ("pouch_right", 15, "front_lower", 1),
                                  ("radio", 16, "front_lower", 1),
                                  ("buckle", 17, "front_belt", 11)),
        "AnaM1ArmorModel": (("upper_webbing", 6, "front_plate", 0),
                             ("medical", 9, "waist_front", 7),
                             ("small_pouch", 10, "waist_front", 7),
                             ("mag_left", 11, "waist_front", 7),
                             ("mag_mid", 12, "waist_front", 7),
                             ("mag_right", 13, "waist_front", 7),
                             ("radio", 14, "front_plate", 0),
                             ("tool", 16, "front_plate", 0)),
        "A18SkandaArmorModel": (("left_pouch", 8, "front_belt", 6),
                                 ("right_pouch", 9, "front_belt", 6),
                                 ("mag_left", 10, "front_plate", 0),
                                 ("mag_right", 11, "front_plate", 0),
                                 ("utility", 12, "front_plate", 0),
                                 ("radio", 13, "front_plate", 0),
                                 ("webbing", 14, "front_plate", 0),
                                 ("buckle", 15, "front_belt", 6)),
        "AvsArmorModel": (("left_pouch", 8, "front_belt", 6),
                           ("right_pouch", 9, "front_belt", 6),
                           ("mag_left", 10, "front_plate", 0),
                           ("mag_mid", 11, "front_plate", 0),
                           ("mag_right", 12, "front_plate", 0),
                           ("radio", 13, "front_plate", 0),
                           ("molle_1", 15, "groin", 14),
                           ("molle_2", 16, "groin", 14),
                           ("molle_3", 17, "groin", 14),
                           ("molle_4", 18, "groin", 14),
                           ("chest_webbing_1", 19, "front_plate", 0),
                           ("chest_webbing_2", 20, "front_plate", 0)),
        "ThorConcealableArmorModel": (("lower_pad_left", 10, "front_lower", 1),
                                       ("lower_pad_right", 11, "front_lower", 1),
                                       ("chest_patch", 12, "front_upper", 0)),
        "StichProfiV2ArmorModel": (("mag_left", 8, "front_plate", 0),
                                    ("mag_right", 9, "front_plate", 0),
                                    ("medical", 10, "front_belt", 6),
                                    ("radio", 11, "front_plate", 0),
                                    ("antenna", 12, "radio", 11),
                                    ("drop_pouch", 13, "front_belt", 6),
                                    ("molle", 14, "front_plate", 0)),
        "Tv110ArmorModel": (("left_pouch", 8, "front_belt", 6),
                             ("mid_pouch", 9, "front_belt", 6),
                             ("right_pouch", 10, "front_belt", 6),
                             ("radio", 11, "front_belt", 6),
                             ("upper_molle", 12, "front_plate", 0)),
    }
    for model, pairs in rules.items():
        model_boxes = body_boxes(model)
        for detail_name, detail_index, base_name, base_index in pairs:
            require_connected(
                model,
                detail_name,
                named_box(model, model_boxes, detail_index, detail_name),
                base_name,
                named_box(model, model_boxes, base_index, base_name),
            )
        print(f"MEDIUM {model}: {len(pairs)} exterior details have volume attachment")


def validate_medium_face_offsets() -> None:
    # ANA shoulder bridges must straddle the front/rear plates without sharing either outer z face.
    model = "AnaM1ArmorModel"
    model_boxes = body_boxes(model)
    front, rear = model_boxes[0], model_boxes[1]
    for name, bridge in (("left_bridge", model_boxes[4]), ("right_bridge", model_boxes[5])):
        require_connected(model, name, bridge, "front_plate", front)
        require_connected(model, name, bridge, "rear_plate", rear)
        require_not_coplanar(model, name, bridge.z, "front_plate", front.z, "front-z")
        require_not_coplanar(model, name, bridge.z1, "rear_plate", rear.z1, "rear-z")

    for model, plate_index, left_bridge_index, right_bridge_index in (
        ("A18SkandaArmorModel", 0, 4, 5),
        ("AvsArmorModel", 0, 4, 5),
    ):
        model_boxes = body_boxes(model)
        plate = model_boxes[plate_index]
        left_bridge, right_bridge = model_boxes[left_bridge_index], model_boxes[right_bridge_index]
        require_connected(model, "left_bridge", left_bridge, "front_plate", plate)
        require_connected(model, "right_bridge", right_bridge, "front_plate", plate)
        require(plate.x - left_bridge.x >= MIN_SEAM_OFFSET,
                f"{model}.left_bridge: x-min coplanar with plate; offset={plate.x - left_bridge.x:.4f}")
        require(right_bridge.x1 - plate.x1 >= MIN_SEAM_OFFSET,
                f"{model}.right_bridge: x-max coplanar with plate; offset={right_bridge.x1 - plate.x1:.4f}")

    for model, belt_index, left_index, right_index in (
        ("A18SkandaArmorModel", 6, 8, 9),
        ("StichProfiV2ArmorModel", 6, 10, 11),
        ("Tv110ArmorModel", 6, 8, 11),
    ):
        model_boxes = body_boxes(model)
        belt, left_pouch, right_pouch = model_boxes[belt_index], model_boxes[left_index], model_boxes[right_index]
        require_connected(model, "left_outer_pouch", left_pouch, "front_belt", belt)
        require_connected(model, "right_outer_pouch", right_pouch, "front_belt", belt)
        require(belt.x - left_pouch.x >= MIN_SEAM_OFFSET,
                f"{model}.left_outer_pouch: x-min coplanar with belt; offset={belt.x - left_pouch.x:.4f}")
        require(right_pouch.x1 - belt.x1 >= MIN_SEAM_OFFSET,
                f"{model}.right_outer_pouch: x-max coplanar with belt; offset={right_pouch.x1 - belt.x1:.4f}")
    print("MEDIUM face offsets: ANA/A18/AVS/Stich/TV110 pass")


HEAVY_COLLARS = (
    "B6B23MountainFloraArmorModel",
    "B6B5FloraArmorModel",
    "OspreyMk4AAssaultArmorModel",
)


def validate_heavy_collar_corners() -> None:
    for model in HEAVY_COLLARS:
        model_boxes = body_boxes(model)
        front_left, front_right, rear, left, right = model_boxes[8:13]
        for first_name, first, second_name, second in (
            ("front_left_collar", front_left, "left_collar", left),
            ("front_right_collar", front_right, "right_collar", right),
            ("rear_collar", rear, "left_collar", left),
            ("rear_collar", rear, "right_collar", right),
        ):
            require_connected(model, first_name, first, second_name, second)
            require_not_coplanar(model, first_name, first.y, second_name, second.y, "bottom-y")
            require_not_coplanar(model, first_name, first.y1, second_name, second.y1, "top-y")
        print(f"HEAVY {model}: four collar corners connected and y-face-offset")


def validate_osprey_clearance() -> None:
    model = "OspreyMk4AAssaultArmorModel"
    java = source(model)
    model_boxes = boxes(method_block(java, model, ("createBody",)))
    long_aprons = [
        box for box in model_boxes
        if box.y >= 10.5 and box.width >= 3.0 and box.height >= 1.25
    ]
    require(not long_aprons, f"{model}.lower_front: long groin apron regressed: {long_aprons}")

    high_pouches = [
        box for box in model_boxes
        if 3.5 <= box.x <= 4.0 and 4.5 <= box.y <= 6.0
        and 0.8 <= box.width <= 1.2 and box.height >= 3.5 and box.depth >= 1.0
    ]
    require(len(high_pouches) == 1, f"{model}.right_high_pouch: expected one high pouch, got {high_pouches}")
    high_pouch = high_pouches[0]

    arm_specs = (
        ("right_shoulder", "createRightShoulder", (-5.0, 2.0, 0.0)),
        ("left_shoulder", "createLeftShoulder", (5.0, 2.0, 0.0)),
    )
    closest_gap = float("inf")
    for shoulder_name, method_name, pivot in arm_specs:
        shoulder_boxes = boxes(method_block(java, model, (method_name,)))
        require(shoulder_boxes, f"{model}.{shoulder_name}: missing shoulder cuboids")
        for index, shoulder in enumerate(shoulder_boxes):
            world = shoulder.shifted(*pivot)
            overlap = overlaps(world, high_pouch)
            require(
                not all(value > EPSILON for value in overlap),
                f"{model}.{shoulder_name}[{index}]: intersects right_high_pouch after pivot {pivot}; "
                f"overlap xyz={overlap}",
            )
            if overlap[0] > 0 and overlap[2] > 0:
                vertical_gap = max(high_pouch.y - world.y1, world.y - high_pouch.y1, 0.0)
                closest_gap = min(closest_gap, vertical_gap)
    require(closest_gap >= 0.05, f"{model}.right_high_pouch: shoulder clearance too small: {closest_gap:.4f}")
    print(f"HEAVY {model}: no long apron; shoulder/high-pouch clearance={closest_gap:.2f}")


def main() -> None:
    validate_light_bridges()
    validate_light_pouch_lids()
    validate_b6b13()
    validate_b6b3_side_pouch()
    validate_medium_attachments()
    validate_medium_face_offsets()
    validate_heavy_collar_corners()
    validate_osprey_clearance()
    print("PASS tier IV geometry integrity validation")


if __name__ == "__main__":
    main()
