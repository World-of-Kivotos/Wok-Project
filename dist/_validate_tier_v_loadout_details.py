from __future__ import annotations

from dataclasses import replace
from typing import Callable
import sys


sys.path.insert(0, str(__file__.rsplit("\\", 1)[0] if "\\" in __file__ else __file__.rsplit("/", 1)[0]))

from _validate_tier_iv_loadout_details import (  # noqa: E402
    Box,
    ContractError,
    Model,
    axis_overlap,
    collar_ring_boxes,
    require,
    require_arm_clearance,
    validate_common,
)
from _validate_tier_v_geometry_integrity import MODELS, load_tier_v_model  # noqa: E402


Contract = Callable[[Model], Box]


def boxes_matching(model: Model, predicate: Callable[[Box], bool]) -> list[Box]:
    return [box for box in model.body if predicate(box)]


def require_count(model: Model, boxes: list[Box], expected: int, label: str) -> None:
    require(
        len(boxes) == expected,
        f"{model.name}: {label} count regressed ({len(boxes)} != {expected})",
    )


def require_no_shoulders(model: Model) -> None:
    counts = {bone: len(model.bones[bone]) for bone in ("right_arm", "left_arm")}
    require(
        counts == {"right_arm": 0, "left_arm": 0},
        f"{model.name}: reference has no articulated shoulder armor: {counts}",
    )


def require_shoulders(
    model: Model,
    pieces_per_arm: int,
    minimum_top_width: float,
    minimum_outer_height: float,
) -> Box:
    critical: Box | None = None
    for bone in ("right_arm", "left_arm"):
        pieces = model.bones[bone]
        require(
            len(pieces) == pieces_per_arm,
            f"{model.name}: {bone} must retain {pieces_per_arm} articulated pieces",
        )
        top = [
            box
            for box in pieces
            if box.width >= minimum_top_width and box.height <= 0.80 and box.depth >= 4.0
        ]
        outer = [
            box
            for box in pieces
            if box.width <= 0.75 and box.height >= minimum_outer_height and box.depth >= 4.0
        ]
        require(top, f"{model.name}: {bone} top shoulder cap missing")
        require(outer, f"{model.name}: {bone} outer shoulder plate missing")
        if critical is None:
            critical = max(outer, key=lambda box: box.height)
    require(critical is not None, f"{model.name}: shoulder contract produced no critical piece")
    return critical


def require_collar(
    model: Model,
    minimum_height: float,
    minimum_pieces: int,
    maximum_y: float,
) -> list[Box]:
    collar_by_uid = {
        box.uid: box for box in collar_ring_boxes(model, minimum_height)
    }
    # Tier V collars sit slightly inside the Tier IV +/-4.0 perimeter on some
    # references; include those real side walls without accepting chest straps.
    collar_by_uid.update({
        box.uid: box
        for box in model.body
        if box.y < 0.0
        and box.y1 >= -0.25
        and box.height >= minimum_height
        and (
            box.x <= -3.40
            or box.x1 >= 3.40
            or box.z <= -3.40
            or box.z1 >= 3.40
        )
    })
    collar = list(collar_by_uid.values())
    require(
        len(collar) >= minimum_pieces and min(box.y for box in collar) <= maximum_y,
        f"{model.name}: reference collar ring is missing or too low",
    )
    return collar


def collar_rim_boxes(model: Model) -> list[Box]:
    return boxes_matching(
        model,
        lambda box: box.y <= -0.80
        and 0.25 <= box.height <= 0.60
        and (
            box.x <= -3.40
            or box.x1 >= 3.40
            or box.z <= -3.30
            or box.z1 >= 3.30
        ),
    )


def large_bag_candidates(model: Model) -> list[Box]:
    return boxes_matching(
        model,
        lambda box: 1.25 <= box.y <= 9.50
        and box.z <= -3.05
        and box.width >= 0.75
        and box.height >= 1.50
        and box.depth >= 0.55,
    )


def forbid_large_bags(model: Model) -> None:
    bags = large_bag_candidates(model)
    require(
        not bags,
        f"{model.name}: pouch-free reference gained external cargo: {[box.uid for box in bags]}",
    )


def pouch_lid_candidates(model: Model, pouch: Box) -> list[Box]:
    return boxes_matching(
        model,
        lambda box: box.uid != pouch.uid
        and 0.25 <= box.height <= 1.55
        and pouch.width * 0.55 <= box.width <= pouch.width * 1.45
        and 0.15 <= box.depth <= pouch.depth + 0.35
        and axis_overlap(box.x, box.x1, pouch.x, pouch.x1)
        >= min(box.width, pouch.width) * 0.72
        and abs(box.center_x - pouch.center_x) <= max(0.18, pouch.width * 0.25)
        and box.y <= pouch.y + 0.35
        and box.y1 >= pouch.y - 0.35
        and box.z <= pouch.z + 0.30,
    )


def require_independent_lids(model: Model, pouches: list[Box], label: str) -> list[Box]:
    lids: list[Box] = []
    for pouch in pouches:
        candidates = pouch_lid_candidates(model, pouch)
        require(candidates, f"{model.name}.{pouch.uid}: {label} has no independent lid")
        lids.append(
            min(
                candidates,
                key=lambda box: (
                    abs(box.center_x - pouch.center_x),
                    abs(box.y1 - pouch.y),
                ),
            )
        )
    require(
        len({lid.uid for lid in lids}) == len(pouches),
        f"{model.name}: {label} pouches must not share one decorative lid",
    )
    return lids


def side_load_bags(model: Model) -> tuple[Box, Box]:
    candidates = boxes_matching(
        model,
        lambda box: 4.0 <= box.y <= 6.5
        and box.z <= -3.35
        and box.width >= 1.45
        and box.height >= 3.0
        and box.depth >= 0.85,
    )
    left = [box for box in candidates if box.x <= -4.15]
    right = [box for box in candidates if box.x1 >= 4.15]
    require_count(model, left, 1, "left load-bearing side bag")
    require_count(model, right, 1, "right load-bearing side bag")
    bags = [left[0], right[0]]
    require_independent_lids(model, bags, "side bag")
    return left[0], right[0]


def front_pouch_bank(model: Model, expected: int) -> list[Box]:
    pouches = boxes_matching(
        model,
        lambda box: 4.0 <= box.y <= 6.2
        and box.z <= -3.15
        and -3.50 <= box.x
        and box.x1 <= 3.80
        and 0.75 <= box.width <= 1.85
        and 2.5 <= box.height <= 4.4
        and box.depth >= 0.55,
    )
    require_count(model, pouches, expected, "front pouch/magazine bank")
    require_independent_lids(model, pouches, "front pouch")
    return sorted(pouches, key=lambda box: box.x)


def drop_pouch(model: Model) -> Box:
    pouches = boxes_matching(
        model,
        lambda box: box.y >= 8.70
        and box.z <= -3.45
        and abs(box.center_x) <= 0.80
        and box.width >= 4.5
        and box.height >= 3.0
        and box.depth >= 0.85,
    )
    require_count(model, pouches, 1, "broad hanging/drop pouch")
    pouch = pouches[0]
    require_independent_lids(model, [pouch], "drop pouch")
    straps = boxes_matching(
        model,
        lambda box: box.uid != pouch.uid
        and pouch.y <= box.y <= pouch.y + 1.20
        and box.z <= pouch.z - 0.10
        and box.width <= 0.30
        and box.height >= 1.80
        and box.depth <= 0.20
        and axis_overlap(box.x, box.x1, pouch.x, pouch.x1) > 0.0,
    )
    require_count(model, straps, 2, "drop-pouch suspension straps")
    return pouch


def tapered_groin(
    model: Model,
    minimum_y: float,
    expected_widths: tuple[float, ...],
    minimum_end_y: float,
) -> Box:
    stages = sorted(
        boxes_matching(
            model,
            lambda box: box.y >= minimum_y
            and box.z <= -2.85
            and abs(box.center_x) <= 0.20
            and box.width >= 1.5
            and box.height >= 0.85
            and box.depth <= 0.75,
        ),
        key=lambda box: box.y,
    )
    require_count(model, stages, len(expected_widths), "tapered groin stages")
    for stage, expected_width in zip(stages, expected_widths):
        require(
            abs(stage.width - expected_width) <= 0.26,
            f"{model.name}.{stage.uid}: groin width {stage.width:.2f} != {expected_width:.2f}",
        )
    require(
        all(first.width > second.width for first, second in zip(stages, stages[1:])),
        f"{model.name}: groin protector must narrow at every stage",
    )
    require(
        stages[-1].y1 >= minimum_end_y,
        f"{model.name}: groin protector no longer reaches its reference length",
    )
    return stages[-1]


def groin_protection_candidates(model: Model) -> list[Box]:
    return boxes_matching(
        model,
        lambda box: box.y >= 10.25
        and box.z <= -2.85
        and box.width >= 1.5
        and box.height >= 1.2,
    )


def validate_tactec(model: Model) -> Box:
    require_no_shoulders(model)
    left_body = boxes_matching(
        model,
        lambda box: box.x <= -6.0
        and 4.9 <= box.y <= 5.1
        and box.z <= -4.0
        and 2.7 <= box.width <= 3.0
        and box.height >= 5.3
        and box.depth >= 1.7,
    )
    left_crown = boxes_matching(
        model,
        lambda box: box.x <= -6.2
        and 4.6 <= box.y <= 4.8
        and box.z <= -4.1
        and box.width >= 3.0
        and 1.0 <= box.height <= 1.25
        and box.depth >= 1.8,
    )
    left_face = boxes_matching(
        model,
        lambda box: box.x <= -5.9
        and 5.7 <= box.y <= 5.9
        and box.z <= -4.2
        and box.width >= 2.3
        and box.height >= 3.7
        and box.depth <= 0.35,
    )
    left_zipper = boxes_matching(
        model,
        lambda box: box.x <= -6.0
        and 5.2 <= box.y <= 5.5
        and box.z <= -4.35
        and box.width <= 0.25
        and box.height >= 4.3
        and box.depth <= 0.20,
    )
    left_step = boxes_matching(
        model,
        lambda box: box.x <= -5.9
        and box.y >= 9.9
        and box.z <= -3.9
        and box.width >= 2.4
        and box.height <= 0.80
        and box.depth >= 1.45,
    )
    for pieces, label in (
        (left_body, "stepped left zipper-bag body"),
        (left_crown, "stepped left zipper-bag crown"),
        (left_face, "raised left zipper-bag face"),
        (left_zipper, "left zipper pull/seam"),
        (left_step, "left zipper-bag lower step"),
    ):
        require_count(model, pieces, 1, label)

    right_bodies = boxes_matching(
        model,
        lambda box: box.x >= 3.25
        and 5.3 <= box.y <= 5.6
        and box.z <= -3.7
        and 0.95 <= box.width <= 1.10
        and box.height >= 4.2
        and box.depth >= 1.35,
    )
    require_count(model, right_bodies, 2, "separate right-waist pocket bodies")
    require_independent_lids(model, right_bodies, "right-waist pocket")

    magazines = front_pouch_bank(model, 5)
    lower_pulls = boxes_matching(
        model,
        lambda box: 6.1 <= box.y <= 6.3
        and box.z <= -3.4
        and 1.0 <= box.width <= 1.2
        and 0.35 <= box.height <= 0.50
        and box.depth <= 0.25,
    )
    require_count(model, lower_pulls, 5, "dense five-column magazine pull bank")
    require(len(magazines) == len(lower_pulls), f"{model.name}: TacTec column details desynchronised")
    drop_pouch(model)
    return left_body[0]


def validate_cpc(model: Model) -> Box:
    require_no_shoulders(model)
    left, _ = side_load_bags(model)
    front_pouch_bank(model, 3)
    crown = boxes_matching(
        model,
        lambda box: box.x <= -6.2
        and 4.6 <= box.y <= 4.8
        and box.z <= -4.2
        and box.width >= 3.3
        and 1.15 <= box.height <= 1.35
        and box.depth >= 1.9,
    )
    lower_bevel = boxes_matching(
        model,
        lambda box: box.x <= -5.9
        and 9.3 <= box.y <= 9.5
        and box.z <= -3.9
        and 2.5 <= box.width <= 2.7
        and 0.95 <= box.height <= 1.15
        and box.depth >= 1.5,
    )
    bulging_face = boxes_matching(
        model,
        lambda box: box.x <= -5.9
        and 5.7 <= box.y <= 6.0
        and box.z <= -4.3
        and box.width >= 2.6
        and box.height >= 3.2
        and box.depth <= 0.35,
    )
    require_count(model, crown, 1, "CPC round left-bag crown")
    require_count(model, lower_bevel, 1, "CPC round left-bag lower bevel")
    require_count(model, bulging_face, 1, "CPC round left-bag raised face")
    lower = boxes_matching(
        model,
        lambda box: 7.0 <= box.y <= 7.6
        and box.z <= -3.35
        and 1.35 <= box.width <= 1.65
        and 1.9 <= box.height <= 2.3
        and box.depth >= 0.75,
    )
    require_count(model, lower, 2, "paired lower utility pouches")
    require_independent_lids(model, lower, "lower utility pouch")
    return left


def validate_fcpc(model: Model) -> Box:
    require_no_shoulders(model)
    side_load_bags(model)
    front_pouch_bank(model, 4)
    radio = boxes_matching(
        model,
        lambda box: box.x <= -5.15
        and 5.0 <= box.y <= 5.2
        and box.z <= -3.6
        and 2.0 <= box.width <= 2.2
        and box.height >= 4.4
        and box.depth >= 1.4,
    )
    antenna = boxes_matching(
        model,
        lambda box: box.x <= -4.65
        and 0.2 <= box.y <= 0.5
        and box.z <= -3.55
        and box.width <= 0.25
        and box.height >= 4.0
        and box.depth <= 0.25,
    )
    require_count(model, radio, 1, "FCPC left radio body")
    require_independent_lids(model, radio, "FCPC radio")
    require_count(model, antenna, 1, "FCPC long radio antenna")

    dangler = boxes_matching(
        model,
        lambda box: 8.75 <= box.y <= 9.10
        and box.z <= -3.7
        and abs(box.center_x) <= 0.10
        and 4.6 <= box.width <= 5.2
        and 1.6 <= box.height <= 2.1
        and 1.0 <= box.depth <= 1.3,
    )
    require_count(model, dangler, 1, "FCPC broad horizontal lower dangler")
    require(
        dangler[0].width >= dangler[0].height * 2.4,
        f"{model.name}: FCPC lower dangler must remain broad and shallow",
    )
    dangler_lids = require_independent_lids(model, dangler, "FCPC broad horizontal dangler")
    require(
        all(lid.width >= 4.8 and lid.height <= 0.85 for lid in dangler_lids),
        f"{model.name}: FCPC dangler lid must span the broad soft pouch",
    )
    straps = boxes_matching(
        model,
        lambda box: 9.10 <= box.y <= 9.30
        and box.z <= -4.0
        and abs(box.center_x) <= 0.8
        and box.width <= 0.26
        and 1.05 <= box.height <= 1.35
        and box.depth <= 0.24,
    )
    require_count(model, straps, 2, "FCPC dangler suspension straps")
    return dangler[0]


def validate_gladiator_light(model: Model) -> Box:
    require_no_shoulders(model)
    soft_yokes = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and 1.25 <= box.width <= 1.40
        and box.height >= 4.0
        and box.depth <= 0.45,
    )
    shoulder_bridges = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and 1.20 <= box.width <= 1.35
        and box.height <= 0.60
        and box.depth >= 4.8,
    )
    require_count(model, soft_yokes, 4, "Gladiator Light soft load-bearing yokes")
    require_count(model, shoulder_bridges, 2, "Gladiator Light shoulder bridges")
    side_load_bags(model)
    front_pouch_bank(model, 4)
    upper = boxes_matching(
        model,
        lambda box: 1.0 <= box.y <= 1.8
        and box.z <= -3.30
        and 2.0 <= box.width <= 2.5
        and 2.4 <= box.height <= 2.9
        and box.depth >= 0.85,
    )
    require_count(model, upper, 2, "Gladiator Light upper pouch panels")
    require_independent_lids(model, upper, "Gladiator Light upper pouch")
    right_group = boxes_matching(
        model,
        lambda box: box.x >= 2.65
        and 5.7 <= box.y <= 6.4
        and box.z <= -3.9
        and 0.65 <= box.width <= 0.90
        and 1.75 <= box.height <= 2.40
        and box.depth >= 0.40,
    )
    require_count(model, right_group, 2, "Gladiator Light right grouped sub-pockets")
    require_independent_lids(model, right_group, "Gladiator Light right sub-pocket")
    return tapered_groin(model, 9.50, (6.2, 5.4, 4.5, 3.6), 16.90)


def validate_hexatac(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    split_upper = boxes_matching(
        model,
        lambda box: 0.40 <= box.y <= 1.0
        and box.z <= -2.35
        and 2.6 <= box.width <= 3.1
        and 3.3 <= box.height <= 3.9
        and box.depth <= 0.50,
    )
    require_count(model, split_upper, 2, "split Hexatac upper panels")
    split_rear = boxes_matching(
        model,
        lambda box: 0.40 <= box.y <= 1.0
        and box.z >= 2.0
        and 2.6 <= box.width <= 3.1
        and 3.3 <= box.height <= 3.9
        and box.depth <= 0.50,
    )
    require_count(model, split_rear, 2, "split Hexatac rear upper panels")
    soft_yokes = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and 1.45 <= box.width <= 1.60
        and box.height >= 4.2
        and box.depth <= 0.40,
    )
    shoulder_bridges = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and 1.40 <= box.width <= 1.55
        and box.height <= 0.60
        and box.depth >= 4.7,
    )
    require_count(model, soft_yokes, 4, "Hexatac soft mesh shoulder straps")
    require_count(model, shoulder_bridges, 2, "Hexatac shoulder bridges")
    lattice = boxes_matching(
        model,
        lambda box: 5.4 <= box.y <= 6.0
        and abs(box.center_x) >= 3.70
        and box.width <= 0.25
        and box.height >= 2.5
        and box.depth <= 0.35,
    )
    require_count(model, lattice, 6, "Hexatac side lattice cords")
    side_rails = boxes_matching(
        model,
        lambda box: abs(box.center_x) >= 3.70
        and 4.9 <= box.y <= 8.4
        and box.width <= 0.40
        and 0.9 <= box.height <= 1.05
        and box.depth >= 4.0,
    )
    require_count(model, side_rails, 4, "Hexatac open horizontal side rails")
    rows = boxes_matching(
        model,
        lambda box: 5.4 <= box.width <= 5.8
        and box.height <= 0.22
        and box.depth <= 0.28
        and box.z <= -2.65,
    )
    require_count(model, rows, 4, "Hexatac MOLLE rows")
    structural_panels = boxes_matching(
        model,
        lambda box: abs(box.center_x) <= 0.10
        and box.z <= -2.75
        and box.width >= 5.5
        and box.height >= 2.0
        and box.depth <= 0.42,
    )
    require_count(model, structural_panels, 2, "Hexatac shallow structural fields")
    require(not groin_protection_candidates(model), f"{model.name}: Hexatac has no groin apron")
    return split_upper[0]


def validate_b6b45_general(model: Model) -> Box:
    require_collar(model, 1.50, 5, -1.50)
    require_shoulders(model, 2, 2.1, 1.0)
    medical = boxes_matching(
        model,
        lambda box: -3.6 <= box.x <= -3.3
        and 5.1 <= box.y <= 5.4
        and box.z <= -3.7
        and 3.4 <= box.width <= 3.6
        and box.height >= 5.0
        and box.depth >= 1.2,
    )
    require_count(model, medical, 1, "General dominant left-centre medical pack")
    require_independent_lids(model, medical, "General medical pack")

    outer_left = boxes_matching(
        model,
        lambda box: box.x <= -4.8
        and 5.7 <= box.y <= 6.0
        and box.z <= -3.5
        and 1.35 <= box.width <= 1.50
        and box.height >= 3.0
        and box.depth >= 0.95,
    )
    require_count(model, outer_left, 1, "General compact outer-left stepped pouch")
    require_independent_lids(model, outer_left, "General outer-left pouch")
    outer_step = boxes_matching(
        model,
        lambda box: box.x <= -4.65
        and box.y >= 8.6
        and box.z <= -3.45
        and 1.05 <= box.width <= 1.25
        and box.height <= 0.70
        and box.depth >= 0.80,
    )
    require_count(model, outer_step, 1, "General outer-left rounded lower step")

    right = boxes_matching(
        model,
        lambda box: 0.0 <= box.x <= 2.2
        and 5.4 <= box.y <= 6.0
        and box.z <= -3.45
        and 1.3 <= box.width <= 1.7
        and 4.2 <= box.height <= 4.9
        and box.depth >= 0.90,
    )
    require_count(model, right, 2, "General twin right magazine pouches")
    require_independent_lids(model, right, "General right magazine pouch")
    right_pulls = boxes_matching(
        model,
        lambda box: 0.9 <= box.x <= 2.8
        and 6.0 <= box.y <= 6.3
        and box.z <= -3.9
        and box.width <= 0.25
        and box.height >= 2.2
        and box.depth <= 0.32,
    )
    require_count(model, right_pulls, 2, "General independent right pouch pulls")

    radio = boxes_matching(
        model,
        lambda box: box.x >= 3.15
        and 1.5 <= box.y <= 2.2
        and box.z <= -3.35
        and 1.1 <= box.width <= 1.4
        and 3.4 <= box.height <= 4.0
        and box.depth >= 0.9,
    )
    require_count(model, radio, 1, "General right radio pouch")
    require_independent_lids(model, radio, "radio pouch")
    antenna = boxes_matching(
        model,
        lambda box: box.x >= 4.0
        and box.y < 0.0
        and box.z <= -3.2
        and box.width <= 0.25
        and box.height >= 2.8
        and box.depth <= 0.25,
    )
    require_count(model, antenna, 1, "General radio antenna")
    return medical[0]


def validate_b6b45_medic(model: Model) -> Box:
    require_collar(model, 1.70, 5, -0.80)
    require_shoulders(model, 2, 2.1, 1.25)
    broad_general = boxes_matching(
        model,
        lambda box: box.y >= 5.0
        and box.z <= -3.5
        and box.width >= 3.3
        and box.height >= 4.8,
    )
    require(not broad_general, f"{model.name}: Medic variant must not inherit the General left bag")

    medical = boxes_matching(
        model,
        lambda box: 2.3 <= box.y <= 2.9
        and box.z <= -3.6
        and 2.7 <= box.width <= 3.1
        and 4.8 <= box.height <= 5.3
        and box.depth >= 1.0,
    )
    require_count(model, medical, 1, "central medical pouch")
    require_independent_lids(model, medical, "medical pouch")
    horizontal_cross = boxes_matching(
        model,
        lambda box: box.z <= -4.0
        and box.width >= 1.4
        and box.height <= 0.40
        and box.depth <= 0.20,
    )
    vertical_cross = boxes_matching(
        model,
        lambda box: box.z <= -4.0
        and box.width <= 0.40
        and box.height >= 1.4
        and box.depth <= 0.20,
    )
    require_count(model, horizontal_cross, 1, "medical cross horizontal bar")
    require_count(model, vertical_cross, 1, "medical cross vertical bar")
    zipper = boxes_matching(
        model,
        lambda box: 1.4 <= box.x <= 1.7
        and 3.2 <= box.y <= 3.5
        and box.z <= -3.9
        and box.width <= 0.20
        and box.height >= 3.4
        and box.depth <= 0.20,
    )
    require_count(model, zipper, 1, "central medical pouch zipper")

    long_pouches = boxes_matching(
        model,
        lambda box: box.x < -2.3
        and 3.8 <= box.y <= 4.4
        and box.z <= -3.4
        and 1.0 <= box.width <= 1.35
        and 4.2 <= box.height <= 4.8
        and box.depth >= 0.85,
    )
    lower_pouches = boxes_matching(
        model,
        lambda box: 7.8 <= box.y <= 8.3
        and box.z <= -3.35
        and 1.0 <= box.width <= 1.35
        and 2.4 <= box.height <= 2.8
        and box.depth >= 0.80,
    )
    require_count(model, long_pouches, 2, "Medic paired long pouches")
    require_count(model, lower_pouches, 2, "Medic paired lower pouches")
    require_independent_lids(model, long_pouches + lower_pouches, "Medic auxiliary pouch")
    auxiliary_pulls = boxes_matching(
        model,
        lambda box: -3.4 <= box.x <= 1.0
        and 4.7 <= box.y <= 8.7
        and box.z <= -3.6
        and box.width <= 0.25
        and 1.4 <= box.height <= 3.1
        and box.depth <= 0.18,
    )
    require_count(model, auxiliary_pulls, 4, "Medic independent auxiliary-pouch pulls")

    radio = boxes_matching(
        model,
        lambda box: box.x >= 2.2
        and 3.0 <= box.y <= 3.5
        and box.z <= -3.25
        and 1.1 <= box.width <= 1.4
        and box.height >= 5.4
        and box.depth >= 0.70,
    )
    require_count(model, radio, 1, "Medic right radio module")
    require_independent_lids(model, radio, "Medic radio module")
    antenna = boxes_matching(
        model,
        lambda box: box.x >= 3.0
        and 0.4 <= box.y <= 1.0
        and box.z <= -3.2
        and box.width <= 0.20
        and box.height >= 2.4
        and box.depth <= 0.20,
    )
    require_count(model, antenna, 1, "Medic radio antenna")
    return medical[0]


def validate_gzhel(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    require_collar(model, 2.0, 5, -1.2)
    rim = collar_rim_boxes(model)
    require_count(model, rim, 5, "Gzhel raised outer collar rim")
    segments = boxes_matching(
        model,
        lambda box: 0.85 <= box.width <= 1.0
        and 0.24 <= box.height <= 0.32
        and box.depth <= 0.20
        and box.z <= -2.75
        and 2.0 <= box.y <= 5.5,
    )
    require_count(model, segments, 18, "Gzhel three-by-six MOLLE grid")
    buckle = boxes_matching(
        model,
        lambda box: 8.4 <= box.y <= 9.1
        and box.z <= -3.25
        and abs(box.center_x) <= 0.10
        and 2.1 <= box.width <= 2.6
        and 0.9 <= box.height <= 1.2
        and box.depth >= 0.35,
    )
    require_count(model, buckle, 1, "Gzhel broad waist buckle")
    centre_clasp = boxes_matching(
        model,
        lambda box: 8.8 <= box.y <= 9.0
        and box.z <= -3.5
        and abs(box.center_x) <= 0.05
        and 1.0 <= box.width <= 1.2
        and 0.65 <= box.height <= 0.80
        and box.depth <= 0.25,
    )
    belt_courses = boxes_matching(
        model,
        lambda box: 8.5 <= box.y <= 8.8
        and box.height >= 1.2
        and ((box.width >= 7.7 and box.depth <= 0.40) or (box.width <= 0.40 and box.depth >= 4.7)),
    )
    shoulder_steps = boxes_matching(
        model,
        lambda box: 0.8 <= box.y <= 1.1
        and abs(box.center_x) >= 3.4
        and 0.55 <= box.width <= 0.70
        and box.height >= 1.4
        and box.depth <= 0.35,
    )
    require_count(model, centre_clasp, 1, "Gzhel central belt clasp")
    require_count(model, belt_courses, 4, "Gzhel continuous four-sided belt")
    require_count(model, shoulder_steps, 4, "Gzhel soft shoulder-transition steps")
    require(not groin_protection_candidates(model), f"{model.name}: Gzhel has no groin apron")
    return segments[0]


def validate_gladiator_gray(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    require_collar(model, 1.6, 5, -0.95)
    rim = collar_rim_boxes(model)
    require_count(model, rim, 5, "Gladiator Gray raised collar rim")
    wings = boxes_matching(
        model,
        lambda box: 3.0 <= box.y <= 3.7
        and abs(box.center_x) >= 4.0
        and 0.60 <= box.width <= 0.75
        and box.height >= 6.7
        and 0.4 <= box.depth <= 0.6,
    )
    require_count(model, wings, 4, "Gladiator Gray thin side wings")
    rows = boxes_matching(
        model,
        lambda box: 6.7 <= box.width <= 7.1
        and box.height <= 0.30
        and box.depth <= 0.22
        and box.z <= -2.6
        and 1.5 <= box.y <= 8.6,
    )
    require_count(model, rows, 6, "Gladiator Gray full-width MOLLE rows")
    belt_courses = boxes_matching(
        model,
        lambda box: 9.3 <= box.y <= 9.5
        and box.height >= 1.0
        and ((box.width >= 7.5 and box.depth <= 0.35) or (box.width <= 0.40 and box.depth >= 4.3)),
    )
    chest_patch = boxes_matching(
        model,
        lambda box: 0.7 <= box.y <= 0.9
        and box.z <= -2.65
        and abs(box.center_x) <= 0.05
        and 2.7 <= box.width <= 2.9
        and 0.9 <= box.height <= 1.1
        and box.depth <= 0.25,
    )
    require_count(model, belt_courses, 4, "Gladiator Gray continuous waist belt")
    require_count(model, chest_patch, 1, "Gladiator Gray centred chest patch")
    require(not groin_protection_candidates(model), f"{model.name}: Gray variant has no groin apron")
    return rows[0]


def validate_gladiator_viking(model: Model) -> Box:
    require_no_shoulders(model)
    require(
        not collar_ring_boxes(model, 1.45),
        f"{model.name}: Viking uses a low padded neckline, not a raised collar",
    )
    side_load_bags(model)
    front_pouch_bank(model, 3)
    antenna = boxes_matching(
        model,
        lambda box: box.x >= 3.1
        and 0.1 <= box.y <= 0.3
        and box.z <= -3.25
        and box.width <= 0.22
        and box.height >= 3.9
        and box.depth <= 0.22,
    )
    antenna_base = boxes_matching(
        model,
        lambda box: box.x >= 2.9
        and 3.7 <= box.y <= 3.9
        and box.z <= -3.4
        and box.width <= 0.60
        and 0.6 <= box.height <= 0.8
        and box.depth <= 0.50,
    )
    require_count(model, antenna, 1, "Viking radio whip antenna")
    require_count(model, antenna_base, 1, "Viking antenna base")

    equipment = boxes_matching(
        model,
        lambda box: 8.9 <= box.y <= 9.2
        and box.z <= -3.5
        and abs(box.center_x) <= 0.10
        and 4.9 <= box.width <= 5.1
        and 4.0 <= box.height <= 4.2
        and box.depth >= 0.9,
    )
    require_count(model, equipment, 1, "Viking rectangular hanging equipment pouch")
    require_independent_lids(model, equipment, "Viking equipment pouch")
    centre_panel = boxes_matching(
        model,
        lambda box: 10.0 <= box.y <= 10.2
        and box.z <= -3.8
        and abs(box.center_x) <= 0.05
        and 2.3 <= box.width <= 2.5
        and 1.2 <= box.height <= 1.4
        and box.depth <= 0.40,
    )
    suspension = boxes_matching(
        model,
        lambda box: 9.7 <= box.y <= 10.0
        and box.z <= -3.7
        and abs(box.center_x) >= 1.4
        and box.width <= 0.25
        and box.height >= 2.4
        and box.depth <= 0.20,
    )
    require_count(model, centre_panel, 1, "Viking equipment-pouch centre panel")
    require_count(model, suspension, 2, "Viking equipment-pouch suspension straps")
    require(
        not boxes_matching(model, lambda box: box.y >= 13.0 and box.z <= -2.85 and box.width >= 2.0),
        f"{model.name}: Viking equipment pouch must not become a protective groin apron",
    )
    return antenna[0]


def validate_tt_mkiii(model: Model) -> Box:
    require_no_shoulders(model)
    left_bag, _ = side_load_bags(model)
    front_pouch_bank(model, 3)
    rows = boxes_matching(
        model,
        lambda box: 5.8 <= box.width <= 6.2
        and box.height <= 0.22
        and box.depth <= 0.45
        and box.z <= -3.0
        and 2.8 <= box.y <= 5.5,
    )
    require_count(model, rows, 4, "TT MKIII MOLLE rows")

    left_modules = boxes_matching(
        model,
        lambda box: box.x <= -4.25
        and 5.4 <= box.y <= 6.0
        and box.z <= -3.9
        and box.width <= 0.55
        and box.height >= 3.5
        and box.depth >= 0.40,
    )
    right_modules = boxes_matching(
        model,
        lambda box: box.x >= 4.0
        and 5.4 <= box.y <= 6.0
        and box.z <= -3.8
        and box.width <= 0.55
        and box.height >= 3.4
        and box.depth >= 0.40,
    )
    require_count(model, left_modules, 2, "TT MKIII paired left narrow modules")
    require_count(model, right_modules, 1, "TT MKIII right narrow module")
    for module in left_modules + right_modules:
        details = boxes_matching(
            model,
            lambda box: box.uid != module.uid
            and 0.30 <= box.width <= 0.60
            and 0.25 <= box.height <= 0.50
            and box.depth <= 0.22
            and box.z <= module.z - 0.05
            and axis_overlap(box.x, box.x1, module.x, module.x1) >= module.width * 0.75
            and axis_overlap(box.y, box.y1, module.y, module.y1) >= 0.20,
        )
        require(details, f"{model.name}.{module.uid}: narrow module detail tab missing")
    antenna = boxes_matching(
        model,
        lambda box: box.x >= 2.85
        and 2.0 <= box.y <= 2.2
        and box.z <= -3.2
        and box.width <= 0.22
        and box.height >= 2.7
        and box.depth <= 0.22,
    )
    require_count(model, antenna, 1, "TT MKIII right-side antenna")
    require(not groin_protection_candidates(model), f"{model.name}: TT MKIII has no groin apron")
    return antenna[0]


def validate_osprey_protection(model: Model) -> Box:
    require_collar(model, 1.6, 5, -0.8)
    require_shoulders(model, 4, 3.4, 4.9)
    for bone in ("right_arm", "left_arm"):
        sleeve_faces = [
            box for box in model.bones[bone]
            if box.depth <= 0.45 and box.width >= 3.6 and box.height >= 2.5
        ]
        require_count(model, sleeve_faces, 2, f"{bone} front/rear half-sleeve faces")
    side_load_bags(model)
    pouches = front_pouch_bank(model, 4)
    details: list[Box] = []
    for pouch in pouches:
        candidates = boxes_matching(
            model,
            lambda box: box.uid != pouch.uid
            and box.z <= pouch.z - 0.10
            and box.width >= pouch.width * 0.90
            and box.height <= 0.50
            and box.depth <= 0.25
            and axis_overlap(box.x, box.x1, pouch.x, pouch.x1) >= pouch.width * 0.85
            and axis_overlap(box.y, box.y1, pouch.y, pouch.y1) >= 0.20,
        )
        require(candidates, f"{model.name}.{pouch.uid}: protection pouch lower seam missing")
        details.append(candidates[0])
    require(
        len({detail.uid for detail in details}) == 4,
        f"{model.name}: four protection pouches need four independent lower seams",
    )
    upper_cargo = boxes_matching(
        model,
        lambda box: box.y >= 1.0
        and box.y1 <= 4.7
        and box.z <= -3.15
        and box.width >= 1.2
        and box.height >= 1.5
        and box.depth >= 0.55
        and box.x1 <= 2.2,
    )
    require(
        not upper_cargo,
        f"{model.name}: Osprey upper chest must remain empty MOLLE: {[box.uid for box in upper_cargo]}",
    )
    require(not groin_protection_candidates(model), f"{model.name}: Osprey Protection has no groin apron")
    return max(model.bones["right_arm"], key=lambda box: box.height)


def validate_defender(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    require(
        not collar_ring_boxes(model, 1.45),
        f"{model.name}: Defender 2 uses suspension straps, not a raised collar ring",
    )
    suspension = boxes_matching(
        model,
        lambda box: box.y <= 0.10
        and 1.2 <= box.width <= 1.4
        and 3.7 <= box.height <= 4.1
        and 0.30 <= box.depth <= 0.40,
    )
    bridges = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and 1.15 <= box.width <= 1.35
        and box.height <= 0.40
        and box.depth >= 4.8,
    )
    require_count(model, suspension, 4, "Defender 2 front/rear suspension straps")
    require_count(model, bridges, 2, "Defender 2 shoulder bridges")
    split_upper = boxes_matching(
        model,
        lambda box: 0.5 <= box.y <= 0.7
        and box.z <= -2.5
        and 2.1 <= box.width <= 2.3
        and 3.2 <= box.height <= 3.5
        and box.depth <= 0.55,
    )
    v_tabs = boxes_matching(
        model,
        lambda box: 1.1 <= box.y <= 1.3
        and box.z <= -2.7
        and 0.70 <= box.width <= 0.80
        and 0.9 <= box.height <= 1.1
        and box.depth >= 0.65,
    )
    belt_courses = boxes_matching(
        model,
        lambda box: 9.8 <= box.y <= 9.9
        and box.height >= 1.2
        and ((box.width >= 7.7 and box.depth <= 0.40) or (box.width <= 0.45 and box.depth >= 4.4)),
    )
    require_count(model, split_upper, 2, "Defender 2 split V-front pads")
    require_count(model, v_tabs, 2, "Defender 2 central V tabs")
    require_count(model, belt_courses, 4, "Defender 2 broad four-sided wrap belt")
    return tapered_groin(model, 10.50, (6.2, 5.6, 4.5), 17.30)


def validate_gladiator_deathless(model: Model) -> Box:
    require_collar(model, 2.2, 5, -1.5)
    shoulder = require_shoulders(model, 9, 4.0, 2.8)
    ammunition = boxes_matching(
        model,
        lambda box: 3.1 <= box.y <= 3.4
        and box.z <= -3.3
        and 0.70 <= box.width <= 0.95
        and 2.6 <= box.height <= 2.9
        and box.depth >= 0.70,
    )
    require_count(model, ammunition, 6, "Deathless six-cell ammunition bank")
    require_independent_lids(model, ammunition, "Deathless ammunition cell")
    red_rounds = boxes_matching(
        model,
        lambda box: box.x <= -3.7
        and 6.2 <= box.y <= 8.9
        and box.z <= -3.55
        and 0.85 <= box.width <= 1.0
        and 0.55 <= box.height <= 0.70
        and box.depth >= 0.95,
    )
    require_count(model, red_rounds, 4, "Deathless four-round left red column")
    require(
        not boxes_matching(
            model,
            lambda box: box.x >= 2.8
            and 6.2 <= box.y <= 8.9
            and box.z <= -3.55
            and 0.85 <= box.width <= 1.0
            and 0.55 <= box.height <= 0.70
            and box.depth >= 0.95,
        ),
        f"{model.name}: Deathless reference has no mirrored right red-ammo column",
    )
    shell_seams = boxes_matching(
        model,
        lambda box: box.z <= -2.95
        and box.width >= 6.6
        and box.height <= 0.22
        and box.depth <= 0.40
        and 1.5 <= box.y <= 8.7,
    )
    require_count(model, shell_seams, 3, "Deathless raised shell seams")
    tapered_groin(model, 10.50, (6.5, 5.8, 4.7), 17.50)
    return shoulder


def validate_redut(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    require_collar(model, 1.45, 5, -1.0)
    yokes = boxes_matching(
        model,
        lambda box: 0.20 <= box.y <= 0.45
        and 2.4 <= box.width <= 2.8
        and box.height <= 0.50
        and box.depth >= 1.2,
    )
    require_count(model, yokes, 4, "Redut-M four shoulder-top yokes")
    belt_courses = boxes_matching(
        model,
        lambda box: 9.7 <= box.y <= 9.9
        and box.height >= 1.2
        and ((box.width >= 7.9 and box.depth <= 0.45) or (box.width <= 0.40 and box.depth >= 4.4)),
    )
    require_count(model, belt_courses, 4, "Redut-M continuous padded belt")
    waist_flap = boxes_matching(
        model,
        lambda box: 10.2 <= box.y <= 10.4
        and box.z <= -3.1
        and abs(box.center_x) <= 0.05
        and 6.4 <= box.width <= 6.6
        and 1.2 <= box.height <= 1.5
        and box.depth <= 0.55,
    )
    skirt = sorted(
        boxes_matching(
            model,
            lambda box: box.y >= 11.4
            and box.z <= -3.15
            and abs(box.center_x) <= 0.05
            and box.width >= 5.4
            and box.height >= 1.5
            and box.depth <= 0.50,
        ),
        key=lambda box: box.y,
    )
    require_count(model, waist_flap, 1, "Redut-M short waist flap")
    require_count(model, skirt, 3, "Redut-M layered soft skirt")
    require(
        5.4 <= skirt[0].width <= 5.6
        and 6.3 <= skirt[1].width <= 6.5
        and 5.7 <= skirt[2].width <= 5.9
        and skirt[1].width > skirt[0].width
        and skirt[1].width > skirt[2].width
        and skirt[-1].y1 >= 17.3,
        f"{model.name}: Redut-M skirt must flare through a broad middle course to a blunt hem",
    )
    raised_rails = boxes_matching(
        model,
        lambda box: box.z <= -2.8
        and box.width >= 5.5
        and box.height <= 0.25
        and box.depth <= 0.40
        and 2.0 <= box.y <= 9.0,
    )
    require(not raised_rails, f"{model.name}: Redut-M stitching must not become raised rail geometry")
    shoulder_straps = boxes_matching(
        model,
        lambda box: 0.4 <= box.y <= 0.6
        and box.z <= -2.9
        and 1.0 <= box.width <= 1.2
        and box.height >= 2.8
        and box.depth <= 0.36,
    )
    chest_clasp = boxes_matching(
        model,
        lambda box: 1.4 <= box.y <= 1.7
        and box.z <= -2.9
        and abs(box.center_x) <= 0.05
        and 0.5 <= box.width <= 0.6
        and 0.5 <= box.height <= 0.6
        and box.depth <= 0.36,
    )
    require_count(model, shoulder_straps, 2, "Redut-M purple-brown shoulder straps")
    require_count(model, chest_clasp, 1, "Redut-M central chest clasp")
    return skirt[1]


def validate_iotv_common(model: Model) -> None:
    forbid_large_bags(model)
    collar = require_collar(model, 1.25, 5, -0.5)
    require_count(model, collar, 5, "IOTV low soft inner collar ring")
    rim = collar_rim_boxes(model)
    require_count(model, rim, 5, "IOTV raised outer collar rim")
    rows = boxes_matching(
        model,
        lambda box: 6.8 <= box.width <= 7.3
        and box.height <= 0.28
        and box.depth <= 0.36
        and box.z <= -2.6
        and 1.8 <= box.y <= 8.3,
    )
    require_count(model, rows, 5, "IOTV five front MOLLE rows")
    chest_tabs = boxes_matching(
        model,
        lambda box: 0.7 <= box.y <= 1.3
        and box.z <= -2.75
        and abs(box.center_x) >= 2.1
        and box.width <= 1.05
        and 0.5 <= box.height <= 1.6
        and box.depth <= 0.26,
    )
    require_count(model, chest_tabs, 4, "IOTV paired chest straps and clasps")


def validate_iotv_high_mobility(model: Model) -> Box:
    validate_iotv_common(model)
    require_no_shoulders(model)
    tapered_groin(model, 10.50, (5.4, 4.8, 4.1, 3.2, 1.8), 17.45)
    hips = boxes_matching(
        model,
        lambda box: 10.9 <= box.y <= 11.1
        and box.z <= -2.8
        and abs(box.center_x) >= 2.7
        and 2.4 <= box.width <= 2.5
        and 3.7 <= box.height <= 3.9
        and box.depth <= 0.35,
    )
    require_count(model, hips, 2, "IOTV High Mobility hip plates")
    groin_seams = boxes_matching(
        model,
        lambda box: 11.0 <= box.y <= 12.5
        and box.z <= -3.5
        and abs(box.center_x) <= 0.05
        and 3.7 <= box.width <= 4.7
        and box.height <= 0.22
        and box.depth <= 0.18,
    )
    hip_seams = boxes_matching(
        model,
        lambda box: 11.3 <= box.y <= 11.6
        and box.z <= -3.0
        and abs(box.center_x) >= 3.6
        and box.width <= 0.20
        and box.height >= 2.7
        and box.depth <= 0.18,
    )
    require_count(model, groin_seams, 3, "IOTV High Mobility groin overlap seams")
    require_count(model, hip_seams, 2, "IOTV High Mobility hip edge seams")
    return hips[0]


def validate_iotv_full_protection(model: Model) -> Box:
    validate_iotv_common(model)
    shoulder = require_shoulders(model, 6, 3.7, 3.7)
    for bone in ("right_arm", "left_arm"):
        sleeve_faces = [
            box for box in model.bones[bone]
            if box.depth <= 0.45 and box.width >= 3.5 and box.height >= 1.3
        ]
        require_count(model, sleeve_faces, 4, f"{bone} full front/rear sleeve courses")
    tapered_groin(model, 10.50, (5.7, 5.1, 4.4, 3.4, 2.0), 17.70)
    hips = boxes_matching(
        model,
        lambda box: 10.9 <= box.y <= 11.1
        and box.z <= -2.8
        and abs(box.center_x) >= 2.7
        and 2.4 <= box.width <= 2.5
        and 4.0 <= box.height <= 4.4
        and box.depth <= 0.35,
    )
    require_count(model, hips, 2, "IOTV Full Protection hip plates")
    groin_seams = boxes_matching(
        model,
        lambda box: 11.0 <= box.y <= 12.5
        and box.z <= -3.5
        and abs(box.center_x) <= 0.05
        and 3.9 <= box.width <= 4.9
        and box.height <= 0.22
        and box.depth <= 0.18,
    )
    hip_seams = boxes_matching(
        model,
        lambda box: 11.3 <= box.y <= 11.6
        and box.z <= -3.0
        and abs(box.center_x) >= 3.6
        and box.width <= 0.20
        and box.height >= 3.0
        and box.depth <= 0.18,
    )
    require_count(model, groin_seams, 3, "IOTV Full Protection groin overlap seams")
    require_count(model, hip_seams, 2, "IOTV Full Protection hip edge seams")
    return shoulder


def validate_iotv_assault(model: Model) -> Box:
    validate_iotv_common(model)
    shoulder = require_shoulders(model, 4, 3.5, 3.6)
    outer_heights = [
        box.height
        for bone in ("right_arm", "left_arm")
        for box in model.bones[bone]
        if box.width <= 0.75 and box.depth >= 4.0
    ]
    require(
        outer_heights and 3.7 <= max(outer_heights) <= 3.9,
        f"{model.name}: Assault variant must retain its reference-length four-piece sleeves",
    )
    for bone in ("right_arm", "left_arm"):
        sleeve_faces = [
            box
            for box in model.bones[bone]
            if box.depth <= 0.45
            and box.width >= 3.7
            and 3.4 <= box.height <= 3.6
        ]
        require_count(model, sleeve_faces, 2, f"{bone} long front/rear sleeve faces")
    require(
        not groin_protection_candidates(model),
        f"{model.name}: Assault variant has neither groin apron nor hip plates",
    )
    return shoulder


def validate_korund(model: Model) -> Box:
    require_no_shoulders(model)
    forbid_large_bags(model)
    soft_collar = boxes_matching(
        model,
        lambda box: box.y < 0.0
        and box.y1 >= 0.40
        and box.height >= 1.70
        and (box.x <= -3.30 or box.x1 >= 3.30 or box.z <= -3.30 or box.z1 >= 3.30),
    )
    require_count(model, soft_collar, 5, "Korund five-piece soft collar")
    require(
        min(box.y for box in soft_collar) <= -1.25,
        f"{model.name}: Korund collar lost its raised rear course",
    )
    front_collar = boxes_matching(
        model,
        lambda box: -1.25 <= box.y <= -1.15
        and box.z <= -2.9
        and 2.3 <= box.width <= 2.4
        and 1.7 <= box.height <= 1.8
        and box.depth <= 0.55,
    )
    require_count(model, front_collar, 2, "Korund split-front soft collar pads")
    require(
        all(abs(box.center_x) >= 2.1 for box in front_collar),
        f"{model.name}: Korund collar must keep its wide throat opening",
    )
    straps = boxes_matching(
        model,
        lambda box: 0.6 <= box.y <= 0.8
        and box.z <= -2.8
        and 1.1 <= box.width <= 1.3
        and box.height >= 3.3
        and box.depth <= 0.36,
    )
    buckles = boxes_matching(
        model,
        lambda box: 1.2 <= box.y <= 1.5
        and box.z <= -3.1
        and 0.9 <= box.width <= 1.1
        and 0.6 <= box.height <= 0.8
        and box.depth <= 0.32,
    )
    strap_tails = boxes_matching(
        model,
        lambda box: 2.0 <= box.y <= 2.3
        and box.z <= -3.0
        and box.width <= 0.45
        and box.height >= 2.3
        and box.depth <= 0.22,
    )
    require_count(model, straps, 2, "Korund front shoulder straps")
    require_count(model, buckles, 2, "Korund metal strap buckles")
    require_count(model, strap_tails, 2, "Korund loose strap tails")

    seam_rows = boxes_matching(
        model,
        lambda box: box.z <= -2.75
        and box.width >= 6.1
        and box.height <= 0.22
        and box.depth <= 0.30
        and 4.3 <= box.y <= 9.2,
    )
    seam_columns = boxes_matching(
        model,
        lambda box: box.z <= -2.9
        and box.width <= 0.22
        and box.height >= 5.1
        and box.depth <= 0.30
        and 5.7 <= box.y <= 5.9,
    )
    require_count(model, seam_rows, 2, "Korund horizontal carrier seams")
    require_count(model, seam_columns, 3, "Korund centre and side carrier seams")
    halves = boxes_matching(
        model,
        lambda box: 11.1 <= box.y <= 11.6
        and box.z <= -2.7
        and 3.6 <= box.width <= 3.8
        and 3.4 <= box.height <= 3.7
        and 0.55 <= box.depth <= 0.65,
    )
    require_count(model, halves, 2, "Korund split groin panels")
    require(
        any(box.center_x < 0 for box in halves) and any(box.center_x > 0 for box in halves),
        f"{model.name}: Korund needs one groin panel on each side",
    )
    lower_steps = boxes_matching(
        model,
        lambda box: 14.4 <= box.y <= 14.6
        and box.z <= -2.7
        and 3.2 <= box.width <= 3.4
        and 1.3 <= box.height <= 1.6
        and box.depth >= 0.50,
    )
    side_tabs = boxes_matching(
        model,
        lambda box: 10.85 <= box.y <= 11.05
        and box.z <= -3.0
        and abs(box.center_x) >= 3.7
        and box.width <= 0.75
        and box.height >= 3.2,
    )
    waist_band = boxes_matching(
        model,
        lambda box: 10.8 <= box.y <= 11.1
        and box.z <= -3.0
        and abs(box.center_x) <= 0.05
        and box.width >= 7.1
        and box.height <= 0.60
        and box.depth <= 0.30,
    )
    require_count(model, lower_steps, 2, "Korund inset lower groin steps")
    require_count(model, waist_band, 1, "Korund groin top band")
    require_count(model, side_tabs, 2, "Korund groin side tabs")
    return min(halves, key=lambda box: box.center_x)


CONTRACTS: dict[str, Contract] = {
    "TactecArmorModel": validate_tactec,
    "CpcMod1ArmorModel": validate_cpc,
    "FcpcV5ArmorModel": validate_fcpc,
    "GladiatorSLightArmorModel": validate_gladiator_light,
    "HexatacHpcArmorModel": validate_hexatac,
    "B6B45GeneralArmorModel": validate_b6b45_general,
    "B6B45MedicArmorModel": validate_b6b45_medic,
    "GzhelKArmorModel": validate_gzhel,
    "GladiatorSGrayArmorModel": validate_gladiator_gray,
    "GladiatorSVikingArmorModel": validate_gladiator_viking,
    "TtMkiiiArmorModel": validate_tt_mkiii,
    "OspreyMk4AProtectionArmorModel": validate_osprey_protection,
    "Defender2ArmorModel": validate_defender,
    "GladiatorSDeathlessArmorModel": validate_gladiator_deathless,
    "RedutMArmorModel": validate_redut,
    "IotvGen4HighMobilityArmorModel": validate_iotv_high_mobility,
    "IotvGen4FullProtectionArmorModel": validate_iotv_full_protection,
    "IotvGen4AssaultArmorModel": validate_iotv_assault,
    "KorundVmArmorModel": validate_korund,
}


def without_box(model: Model, target: Box) -> Model:
    if target.bone == "body":
        return replace(model, body=tuple(box for box in model.body if box.uid != target.uid))
    bones = dict(model.bones)
    bones[target.bone] = tuple(
        box for box in bones[target.bone] if box.uid != target.uid
    )
    return replace(model, bones=bones)


def mutation_probe(model: Model, validator: Contract, target: Box) -> None:
    mutated = without_box(model, target)
    try:
        validator(mutated)
    except ContractError:
        return
    raise ContractError(
        f"{model.name}: deletion probe did not detect removal of {target.bone}.{target.uid}"
    )


def main() -> None:
    require(
        tuple(CONTRACTS) == MODELS,
        "Tier V detail contracts must cover every geometry model in canonical order",
    )
    models: dict[str, Model] = {}
    critical: dict[str, Box] = {}
    for name in MODELS:
        model = load_tier_v_model(name)
        validate_common(model, 12)
        require_arm_clearance(model, model.body)
        critical[name] = CONTRACTS[name](model)
        models[name] = model
        print(
            f"MODEL {name}: body={len(model.body)}, world={len(model.world_boxes)}, "
            f"contract=PASS, critical={critical[name].bone}.{critical[name].uid}"
        )

    for name in MODELS:
        mutation_probe(models[name], CONTRACTS[name], critical[name])
    require(len(MODELS) >= 19, "Tier V validator requires at least 19 deletion probes")
    print(f"MUTATION deletion_probes={len(MODELS)}")
    print("PASS tier V loadout detail contract")


if __name__ == "__main__":
    try:
        main()
    except ContractError as error:
        raise SystemExit(str(error)) from error
