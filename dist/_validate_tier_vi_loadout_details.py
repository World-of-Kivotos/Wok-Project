from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageStat

from _validate_tier_iv_loadout_details import (
    Box,
    ContractError,
    Model,
    mutation_probe,
    require,
)
from _validate_tier_vi_geometry_integrity import load_tier_vi_model


ROOT = Path(__file__).resolve().parents[1]
TEXTURES = ROOT / "src/main/resources/assets/miningdim/textures/models/armor"


def collar_panels(model: Model, minimum_height: float) -> list[Box]:
    return [
        box for box in model.body
        if box.y < 0.0
        and box.height >= minimum_height
        and (box.z <= -3.30 or box.z1 >= 3.30 or box.x <= -3.80 or box.x1 >= 3.80)
    ]


def groin_panels(model: Model) -> list[Box]:
    return [
        box for box in model.body
        if box.y >= 11.0 and box.width >= 2.50 and box.x < 0.0 < box.x1 and box.z <= -2.80
    ]


def solid_front_pouches(model: Model) -> list[Box]:
    return [
        box for box in model.body
        if box.z <= -3.20 and box.depth >= 0.80 and box.height >= 3.50 and box.width >= 1.0
    ]


def require_empty_arms(model: Model) -> None:
    counts = {bone: len(model.bones[bone]) for bone in ("right_arm", "left_arm")}
    require(all(value == 0 for value in counts.values()),
            f"{model.name}: reference has no articulated arm armor: {counts}")


def validate_hexgrid(model: Model) -> Box:
    require_empty_arms(model)
    require(not collar_panels(model, 1.30), "Hexgrid must not acquire a collar")
    require(not groin_panels(model), "Hexgrid must not acquire a groin guard")
    require(not solid_front_pouches(model), "Hexgrid must remain an unloaded carrier")

    front_plates = [
        box for box in model.body
        if box.z <= -2.70 and box.width >= 6.80 and box.height >= 9.0 and box.depth >= 0.60
    ]
    require(len(front_plates) == 1, f"Hexgrid must retain one exposed honeycomb plate: {front_plates}")
    side_sheets = [
        box for box in model.body
        if abs(box.center_x) >= 3.60
        and max(abs(box.x), abs(box.x1)) >= 3.90
        and min(abs(box.x), abs(box.x1)) <= 3.40
        and box.height >= 5.30
        and box.depth >= 3.70
    ]
    require(len(side_sheets) == 2, f"Hexgrid must retain two flexible side sheets: {side_sheets}")
    shoulder_straps = [
        box for box in model.body
        if box.y < 0.0 and box.height >= 4.0 and 1.35 <= box.width <= 1.60 and box.depth <= 0.50
    ]
    require(len(shoulder_straps) == 4,
            f"Hexgrid must retain four broad textile strap faces: {shoulder_straps}")

    source = model.source
    require("HONEYCOMB_ROWS = 8" in source and "row % 2 == 0 ? 5 : 4" in source,
            "Hexgrid lost the 36-cell staggered honeycomb")
    require(source.count('prefix + "_') == 6,
            "Hexgrid honeycomb cells must retain six independently modeled edges")
    return front_plates[0]


def validate_slick(model: Model) -> Box:
    require_empty_arms(model)
    require(not collar_panels(model, 1.30), "Slick must not acquire a collar")
    require(not groin_panels(model), "Slick must not acquire a groin guard")
    require(not solid_front_pouches(model), "Slick must not acquire magazine or utility pouches")

    hook_fields = [
        box for box in model.body
        if 1.30 <= box.y <= 1.60 and box.z <= -2.80
        and box.width >= 5.60 and box.height >= 1.40
    ]
    require(len(hook_fields) == 1, f"Slick lost its upper hook-and-loop field: {hook_fields}")
    pull_tabs = [
        box for box in model.body
        if abs(box.center_x) <= 0.05 and box.y <= 0.60 and box.z <= -3.0
        and box.width <= 0.70 and box.height >= 1.50
    ]
    require(len(pull_tabs) == 1, f"Slick lost its central pull tab: {pull_tabs}")
    front_courses = [
        box for box in model.body
        if box.z <= -2.70 and box.width >= 6.0 and box.height <= 0.30
    ]
    require(len(front_courses) >= 10,
            f"Slick horizontal stitch/MOLLE field regressed: {len(front_courses)}")
    side_panels = [
        box for box in model.body
        if abs(box.center_x) >= 3.60 and box.height >= 5.0 and box.depth >= 4.30
    ]
    require(len(side_panels) == 2, f"Slick lost its broad structural cummerbund: {side_panels}")
    tails = [
        box for box in model.body
        if box.y >= 9.30 and box.z <= -2.60 and box.width <= 0.70 and box.height >= 1.60
    ]
    require(len(tails) == 3, f"Slick must retain three narrow adjustment tails: {tails}")
    return hook_fields[0]


def validate_stich(model: Model) -> Box:
    require_empty_arms(model)
    require(not collar_panels(model, 1.30), "Stich Defense must not acquire a collar")
    require(not groin_panels(model), "Stich Defense must not acquire a groin guard")

    pouches = solid_front_pouches(model)
    require(len(pouches) >= 7, f"Stich Defense loadout regressed: {len(pouches)} solid pouches")
    left_outer = [
        box for box in pouches
        if box.x <= -5.70 and box.width >= 2.30 and box.height >= 5.0
    ]
    right_outer = [
        box for box in pouches
        if box.x >= 4.0 and box.width >= 2.0 and box.height >= 4.8
    ]
    require(len(left_outer) == 1, f"Stich Defense lost its left side-waist bag: {left_outer}")
    require(len(right_outer) == 1, f"Stich Defense lost its right side-waist bag: {right_outer}")
    require(left_outer[0].x1 <= -3.30 and right_outer[0].x >= 4.0,
            "Stich Defense side bags no longer project on both sides")

    lids = [
        box for box in model.body
        if box.z <= -3.70 and box.depth >= 1.20 and 0.80 <= box.height <= 1.20
    ]
    require(len(lids) >= 7, f"Stich Defense pouches lost raised lids: {len(lids)}")
    cylinders = [
        box for box in model.body
        if box.x >= 3.20 and 0.40 <= box.width <= 0.55
        and box.height >= 4.20 and 0.40 <= box.depth <= 0.55 and box.z <= -4.0
    ]
    require(len(cylinders) == 3, f"Stich Defense must retain three tool cylinders: {cylinders}")
    lanyards = [
        box for box in model.body
        if box.x <= -4.70 and box.width <= 0.25 and box.depth <= 0.25 and box.height >= 3.0
        and box.z >= -3.30
    ]
    require(len(lanyards) == 2, f"Stich Defense lost its left-side lanyards: {lanyards}")
    badges = [
        box for box in model.body
        if abs(box.center_x) <= 0.05 and 1.0 <= box.y <= 1.3
        and 1.40 <= box.width <= 1.60 and box.height >= 1.15 and box.z <= -3.1
    ]
    require(len(badges) == 1, f"Stich Defense lost its raised chest badge: {badges}")
    badge_marks = [
        box for box in model.body
        if -0.60 <= box.x and box.x1 <= 0.60 and 1.25 <= box.y <= 1.85
        and box.height <= 0.24 and box.z <= -3.30
    ]
    require(len(badge_marks) == 3,
            f"Stich Defense badge must retain its three-step yellow triangle: {badge_marks}")
    return left_outer[0]


def validate_b6b43(model: Model) -> Box:
    collars = collar_panels(model, 1.80)
    require(len(collars) == 5, f"6B43 must retain a five-piece high collar: {collars}")
    front_collar = sorted((box for box in collars if box.z <= -4.40), key=lambda box: box.x)
    require(len(front_collar) == 2, f"6B43 split front collar regressed: {front_collar}")
    opening = front_collar[1].x - front_collar[0].x1
    require(1.75 <= opening <= 1.85, f"6B43 collar face opening changed: {opening:.2f}")
    require(min(box.y for box in collars) >= -2.0,
            "6B43 collar rose above its face-safe reference limit")

    for bone in ("right_arm", "left_arm"):
        pieces = model.bones[bone]
        require(len(pieces) == 9, f"6B43.{bone} must retain nine sleeve segments: {len(pieces)}")
        span = max(box.y1 for box in pieces) - min(box.y for box in pieces)
        require(span >= 6.80, f"6B43.{bone} long sleeve regressed: {span:.2f}")

    side_armor = [
        box for box in model.body
        if abs(box.center_x) >= 3.70 and box.height >= 9.0 and box.depth >= 4.5
    ]
    require(len(side_armor) == 2, f"6B43 lost its continuous side armor: {side_armor}")
    molle = [
        box for box in model.body
        if box.z <= -2.90 and box.width == 7.0 and 0.20 <= box.height <= 0.24
    ]
    require(len(molle) == 7, f"6B43 empty front MOLLE field regressed: {len(molle)}")
    require(not solid_front_pouches(model), "6B43 must not acquire traditional magazine pouches")

    main_levels = [
        box for box in model.body
        if box.y >= 11.0 and box.z <= -3.30 and box.width >= 3.0
        and 1.30 <= box.height <= 2.10 and box.depth >= 0.40
    ]
    require(len(main_levels) == 5, f"6B43 tapered groin levels regressed: {len(main_levels)}")
    tip = max(main_levels, key=lambda box: box.y1)
    require(tip.y1 >= 19.35 and abs(tip.width - 3.0) <= 0.01,
            f"6B43 groin tip is no longer long and tapered: {tip}")
    overlays = [
        box for box in model.body
        if box.y >= 11.5 and box.z <= -3.65 and box.width >= 4.5
        and box.height >= 3.0 and 0.25 <= box.depth <= 0.35
    ]
    require(len(overlays) == 1, f"6B43 lost its raised rectangular groin layer: {overlays}")
    return tip


def validate_thor(model: Model) -> Box:
    collars = collar_panels(model, 1.30)
    require(len(collars) == 6, f"THOR must retain its six-piece padded collar: {collars}")
    for bone in ("right_arm", "left_arm"):
        pieces = model.bones[bone]
        require(len(pieces) == 5, f"THOR.{bone} shoulder shell regressed: {len(pieces)}")
        span = max(box.y1 for box in pieces) - min(box.y for box in pieces)
        require(span >= 5.85, f"THOR.{bone} shoulder coverage regressed: {span:.2f}")

    groins = [
        box for box in groin_panels(model)
        if box.width >= 4.4 and box.height >= 4.8 and box.y1 >= 15.9
    ]
    require(len(groins) == 1, f"THOR long groin guard regressed: {groins}")
    slots = [
        box for box in model.body
        if abs(box.width - 1.20) <= 0.01 and abs(box.height - 0.24) <= 0.01
        and abs(box.depth - 0.12) <= 0.01 and abs(box.z + 3.12) <= 0.01
    ]
    require(len(slots) == 20, f"THOR laser-cut front array regressed: {len(slots)}")
    side_blocks = [
        box for box in model.body
        if abs(box.center_x) >= 4.80 and box.height >= 4.0 and box.depth >= 3.0
    ]
    require(len(side_blocks) == 2, f"THOR lost its side-waist armor blocks: {side_blocks}")
    return groins[0]


VALIDATORS = {
    "HexgridArmorModel": validate_hexgrid,
    "SlickArmorModel": validate_slick,
    "StichDefenseMod2ArmorModel": validate_stich,
    "B6B43ZabraloShArmorModel": validate_b6b43,
    "ThorIntegratedArmorModel": validate_thor,
}


def validate_texture_tones() -> None:
    means: dict[str, tuple[float, float, float]] = {}
    for name in ("hexgrid", "slick", "stich_defense_mod2", "6b43_zabralo_sh", "thor_integrated"):
        path = TEXTURES / f"plate_armor_{name}_layer_1.png"
        with Image.open(path) as image:
            means[name] = tuple(ImageStat.Stat(image.convert("RGB")).mean)
    require(max(means["hexgrid"]) < 35.0, f"Hexgrid coating is no longer charcoal: {means['hexgrid']}")
    require(max(means["slick"]) < 35.0, f"Slick coating is no longer warm black: {means['slick']}")
    stich = means["stich_defense_mod2"]
    require(stich[0] > stich[1] > stich[2] and stich[0] - stich[2] >= 35.0,
            f"Stich coating lost its sand/olive balance: {stich}")
    b6b43 = means["6b43_zabralo_sh"]
    require(b6b43[1] > b6b43[0] > b6b43[2] and b6b43[1] - b6b43[2] >= 20.0,
            f"6B43 coating lost its yellow-green digital flora: {b6b43}")
    thor = means["thor_integrated"]
    require(thor[0] > thor[1] > thor[2] and thor[0] - thor[2] >= 18.0,
            f"THOR coating lost its warm brown tone: {thor}")
    print("TEXTURES tone contracts=5")


def validate_hexgrid_source_mutation(model: Model) -> None:
    mutated = model.source.replace("HONEYCOMB_ROWS = 8", "HONEYCOMB_ROWS = 7", 1)
    require(mutated != model.source, "Hexgrid row mutation setup failed")
    mutated_model = Model(model.name, mutated, model.body, model.bones)
    try:
        validate_hexgrid(mutated_model)
    except ContractError:
        return
    raise ContractError("Hexgrid honeycomb row mutation was not detected")


def main() -> None:
    models: dict[str, Model] = {}
    critical: dict[str, Box] = {}
    for name, validator in VALIDATORS.items():
        model = load_tier_vi_model(name)
        critical[name] = validator(model)
        models[name] = model
        print(f"MODEL {name}: body={len(model.body)}, world={len(model.world_boxes)}, loadout=PASS")

    validate_texture_tones()
    validate_hexgrid_source_mutation(models["HexgridArmorModel"])
    for name, validator in VALIDATORS.items():
        mutation_probe(models[name], validator, critical[name])
    print(f"MUTATION probes={len(VALIDATORS) + 1}")
    print("PASS tier VI reference/loadout validation")


if __name__ == "__main__":
    try:
        main()
    except ContractError as error:
        raise SystemExit(str(error)) from error
