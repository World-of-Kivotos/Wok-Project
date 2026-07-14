from __future__ import annotations

import binascii
import json
import math
import re
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/block/generator/modern"
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block/generator/modern"
PREVIEW_PATH = ROOT / "dist/generator-modern-model-preview.png"

TEXTURES = {
    "frame": "miningdim:block/generator/modern/frame",
    "panel": "miningdim:block/generator/modern/panel",
    "panel_light": "miningdim:block/generator/modern/panel_light",
    "panel_dark": "miningdim:block/generator/modern/panel_dark",
    "grille": "miningdim:block/generator/modern/grille",
    "vent": "miningdim:block/generator/modern/vent",
    "cyan": "miningdim:block/generator/modern/display_cyan",
    "amber": "miningdim:block/generator/modern/display_amber",
    "exhaust": "miningdim:block/generator/modern/exhaust_inner",
}

BASE_COLORS = {
    "frame": (28, 31, 35, 255),
    "panel": (76, 83, 92, 255),
    "panel_light": (102, 110, 120, 255),
    "panel_dark": (48, 53, 59, 255),
    "grille": (18, 21, 24, 255),
    "vent": (23, 27, 31, 255),
    "cyan": (23, 226, 212, 255),
    "amber": (255, 161, 24, 255),
    "exhaust": (10, 12, 14, 255),
}

PARTS: dict[tuple[int, int, int], list[dict]] = {
    (block_x, block_z, block_y): []
    for block_x in range(3)
    for block_z in range(2)
    for block_y in range(2)
}


def clean_number(value: float) -> int | float:
    rounded = round(value, 4)
    if abs(rounded - round(rounded)) < 0.00001:
        return int(round(rounded))
    return rounded


def add_local(
    block_x: int,
    block_z: int,
    block_y: int,
    name: str,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    material: str,
    rotation: dict | None = None,
    shade: bool = True,
) -> None:
    if material not in TEXTURES:
        raise ValueError(f"Unknown material for {name}: {material}")
    if (block_x, block_z, block_y) not in PARTS:
        raise ValueError(f"Unknown part for {name}: {(block_x, block_z, block_y)}")
    if any(value < 0 or value > 16 for value in (*start, *end)):
        raise ValueError(f"Out-of-block element coordinates for {name}: {start} -> {end}")
    if any(start[index] >= end[index] for index in range(3)):
        raise ValueError(f"Degenerate element for {name}: {start} -> {end}")
    element = {
        "name": name,
        "from": [clean_number(value) for value in start],
        "to": [clean_number(value) for value in end],
        "material": material,
        "shade": shade,
    }
    if rotation is not None:
        element["rotation"] = {
            "origin": [clean_number(value) for value in rotation["origin"]],
            "axis": rotation["axis"],
            "angle": rotation["angle"],
            "rescale": False,
        }
    PARTS[(block_x, block_z, block_y)].append(element)


def add_global_box(
    name: str,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    material: str,
    shade: bool = True,
) -> None:
    if any(value < 0 for value in start):
        raise ValueError(f"Negative global coordinates for {name}")
    if end[0] > 48 or end[1] > 32 or end[2] > 32:
        raise ValueError(f"Global coordinates exceed 48x32x32 for {name}")
    for block_x in range(3):
        x0 = max(start[0], block_x * 16)
        x1 = min(end[0], (block_x + 1) * 16)
        if x0 >= x1:
            continue
        for block_z in range(2):
            z0 = max(start[2], block_z * 16)
            z1 = min(end[2], (block_z + 1) * 16)
            if z0 >= z1:
                continue
            for block_y in range(2):
                y0 = max(start[1], block_y * 16)
                y1 = min(end[1], (block_y + 1) * 16)
                if y0 >= y1:
                    continue
                add_local(
                    block_x,
                    block_z,
                    block_y,
                    f"{name}_x{block_x}_z{block_z}_y{block_y}",
                    (x0 - block_x * 16, y0 - block_y * 16, z0 - block_z * 16),
                    (x1 - block_x * 16, y1 - block_y * 16, z1 - block_z * 16),
                    material,
                    shade=shade,
                )


def add_rotated(
    block_x: int,
    block_z: int,
    block_y: int,
    name: str,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    material: str,
    axis: str,
    angle: float,
    origin: tuple[float, float, float],
) -> None:
    add_local(
        block_x,
        block_z,
        block_y,
        name,
        start,
        end,
        material,
        rotation={"origin": origin, "axis": axis, "angle": angle},
    )


def build_geometry() -> None:
    # Full-span underframe and skids establish the exact 48x32 footprint.
    add_global_box("front_floor_rail", (0, 0, 0), (48, 1.25, 1.5), "frame")
    add_global_box("center_floor_rail", (0, 0, 15.25), (48, 1.25, 16.75), "frame")
    add_global_box("rear_floor_rail", (0, 0, 30.5), (48, 1.25, 32), "frame")
    add_global_box("left_floor_rail", (0, 0, 1.5), (1.5, 1.25, 30.5), "frame")
    add_global_box("right_floor_rail", (46.5, 0, 1.5), (48, 1.25, 30.5), "frame")
    add_global_box("front_skid", (1.25, 1.25, 0.75), (46.75, 2.25, 2.25), "frame")
    add_global_box("center_skid", (1.25, 1.25, 14.75), (46.75, 2.25, 17.25), "frame")
    add_global_box("rear_skid", (1.25, 1.25, 29.75), (46.75, 2.25, 31.25), "frame")

    # Corner, center-seam, and rear posts tie the two block rows into one frame.
    for index, (x0, x1) in enumerate(((0.25, 1.75), (15.25, 16.75), (31.25, 33.0), (46.25, 47.75))):
        add_global_box(f"front_post_{index}", (x0, 2.25, 0.25), (x1, 27.25, 1.7), "frame")
        add_global_box(f"center_post_{index}", (x0, 2.25, 15.25), (x1, 27.25, 16.75), "frame")
        add_global_box(f"rear_post_{index}", (x0, 2.25, 30.25), (x1, 27.25, 31.75), "frame")
        add_global_box(f"front_foot_{index}", (max(0, x0 - 0.35), 0, 0), (min(48, x1 + 0.35), 4, 2.5), "frame")
        add_global_box(f"center_foot_{index}", (max(0, x0 - 0.35), 0, 14.75), (min(48, x1 + 0.35), 4, 17.25), "frame")
        add_global_box(f"rear_foot_{index}", (max(0, x0 - 0.35), 0, 29.5), (min(48, x1 + 0.35), 4, 32), "frame")
        add_global_box(f"front_cap_{index}", (max(0, x0 - 0.35), 25.5, 0), (min(48, x1 + 0.35), 28.25, 2.5), "frame")
        add_global_box(f"center_cap_{index}", (max(0, x0 - 0.35), 25.5, 14.75), (min(48, x1 + 0.35), 28.25, 17.25), "frame")
        add_global_box(f"rear_cap_{index}", (max(0, x0 - 0.35), 25.5, 29.5), (min(48, x1 + 0.35), 28.25, 32), "frame")

    add_global_box("front_top_rail", (1.4, 26.25, 0.5), (46.6, 27.5, 1.55), "frame")
    add_global_box("center_top_rail", (1.4, 26.25, 15.45), (46.6, 27.5, 16.55), "frame")
    add_global_box("rear_top_rail", (1.4, 26.25, 30.45), (46.6, 27.5, 31.5), "frame")
    add_global_box("left_top_depth_tube", (0.5, 26.25, 1.5), (1.55, 27.5, 30.5), "frame")
    add_global_box("right_top_depth_tube", (46.45, 26.25, 1.5), (47.5, 27.5, 30.5), "frame")
    for index, (x0, x1) in enumerate(((15.45, 16.55), (31.45, 32.55))):
        add_global_box(f"lower_depth_tube_{index}", (x0, 2.2, 1.5), (x1, 3.35, 30.5), "frame")
        add_global_box(f"upper_depth_tube_{index}", (x0, 24.85, 1.5), (x1, 26.0, 30.5), "frame")

    # Left control bay: deep cabinet, octagonal door, displays, and intake louvers.
    add_global_box("left_cabinet_core", (2, 3.25, 2.1), (15, 20.25, 13.9), "panel_dark")
    add_global_box("left_front_shell", (2.15, 4, 0.85), (14.85, 19.5, 2.3), "panel")
    add_global_box("left_shell_left_edge", (2.15, 7.5, 0.35), (3.35, 17.5, 1.2), "panel_light")
    add_global_box("left_shell_right_edge", (13.65, 7.5, 0.35), (14.85, 17.5, 1.2), "panel_light")
    add_global_box("left_shell_top_edge", (4.3, 18.3, 0.35), (12.7, 19.5, 1.2), "panel_light")
    add_global_box("left_shell_bottom_edge", (4.3, 4, 0.35), (12.7, 5.2, 1.2), "panel_light")

    add_rotated(0, 0, 0, "left_door_lower_left_bevel", (2.65, 4.7, 0.3), (3.75, 8.1, 1.25), "panel_light", "z", 45, (4.0, 6.4, 0.8))
    add_rotated(0, 0, 0, "left_door_lower_right_bevel", (13.25, 4.7, 0.3), (14.35, 8.1, 1.25), "panel_light", "z", -45, (13.0, 6.4, 0.8))
    add_rotated(0, 0, 1, "left_door_upper_left_bevel", (2.65, 0.1, 0.3), (3.75, 3.5, 1.25), "panel_light", "z", -45, (4.0, 1.8, 0.8))
    add_rotated(0, 0, 1, "left_door_upper_right_bevel", (13.25, 0.1, 0.3), (14.35, 3.5, 1.25), "panel_light", "z", 45, (13.0, 1.8, 0.8))

    add_global_box("left_door_recess", (4, 6, 0.08), (13, 17.5, 0.72), "frame")
    add_global_box("left_door_plate", (4.65, 6.65, 0), (12.35, 16.85, 0.28), "panel_dark")
    add_global_box("left_display_cyan", (5.25, 13, 0), (7.9, 15.1, 0.18), "cyan", shade=False)
    add_global_box("left_display_amber", (9.55, 13.25, 0), (10.85, 14.7, 0.18), "amber", shade=False)
    add_global_box("left_switch_a", (5.55, 11.45, 0), (6.7, 12.25, 0.2), "frame")
    add_global_box("left_switch_b", (9.45, 11.2, 0), (10.85, 12.2, 0.2), "frame")
    add_global_box("left_lower_vent", (5.15, 7.1, 0), (9.1, 10.4, 0.2), "vent")
    for row in range(4):
        y = 7.35 + row * 0.68
        add_global_box(f"left_lower_louver_{row}", (5.4, y, 0), (8.85, y + 0.22, 0.1), "frame")
    add_global_box("left_door_hinge_upper", (12.15, 14.2, 0), (12.65, 15.7, 0.35), "frame")
    add_global_box("left_door_hinge_lower", (12.15, 7.3, 0), (12.65, 8.8, 0.35), "frame")

    # The left roof is stepped and includes a real inclined front armor sheet.
    add_global_box("left_roof_lower", (2.1, 19.5, 2), (14.9, 21.5, 14), "panel")
    add_rotated(0, 0, 1, "left_roof_front_slope", (3.0, 4.0, 1.2), (14.4, 5.15, 5.2), "panel_light", "x", -22.5, (8.7, 4.6, 3.2))
    add_global_box("left_roof_middle", (3.2, 21.25, 3.1), (14.6, 23.3, 13.1), "panel")
    add_global_box("left_roof_upper", (4.2, 23.1, 4), (14.2, 24.65, 12.25), "panel_light")
    add_global_box("left_roof_intake", (5.3, 22.7, 0.55), (12.3, 23.75, 1.4), "vent")
    for index in range(5):
        x = 5.65 + index * 1.25
        add_global_box(f"left_roof_intake_fin_{index}", (x, 22.75, 0.4), (x + 0.32, 23.65, 1.2), "frame")

    # Center bay: oversized three-section octagonal radiator set into a folded shell.
    add_global_box("center_cabinet_core", (16.65, 3.25, 2.1), (32.35, 22.5, 13.9), "panel_dark")
    add_global_box("center_grille_back_middle", (17.25, 8.55, 0), (31.5, 18.45, 0.38), "grille")
    add_global_box("center_grille_back_lower", (19.45, 5.75, 0), (29.3, 8.75, 0.38), "grille")
    add_global_box("center_grille_back_upper", (19.45, 18.25, 0), (29.3, 20.65, 0.38), "grille")
    add_global_box("center_grille_top", (20.1, 20.1, 0), (28.65, 21.45, 1.55), "panel_light")
    add_global_box("center_grille_bottom", (20.1, 5, 0), (28.65, 6.35, 1.55), "panel_light")
    add_global_box("center_grille_left", (17, 8.8, 0), (18.4, 18.3, 1.55), "panel_light")
    add_global_box("center_grille_right", (30.35, 8.8, 0), (31.75, 18.3, 1.55), "panel_light")

    add_rotated(1, 0, 0, "center_octagon_lower_left", (1.1, 5.4, 0), (2.35, 10.0, 1.55), "panel_light", "z", 45, (3.0, 7.7, 0.8))
    add_rotated(1, 0, 0, "center_octagon_lower_right", (13.2, 5.4, 0), (14.6, 10.0, 1.55), "panel_light", "z", -45, (13.75, 7.7, 0.8))
    add_rotated(1, 0, 1, "center_octagon_upper_left", (1.1, 0.0, 0), (2.35, 4.6, 1.55), "panel_light", "z", -45, (3.0, 2.3, 0.8))
    add_rotated(1, 0, 1, "center_octagon_upper_right", (13.2, 0.0, 0), (14.6, 4.6, 1.55), "panel_light", "z", 45, (13.75, 2.3, 0.8))

    for index, x in enumerate((21.8, 26.55)):
        add_global_box(f"center_grille_divider_{index}", (x, 6.25, 0), (x + 0.48, 20.2, 0.85), "frame")
    add_global_box("center_lower_armor", (17.1, 3.25, 0.65), (31.8, 5.25, 2.1), "panel")
    add_global_box("center_lower_armor_step", (20, 2.6, 0.25), (28.8, 3.75, 1.5), "panel_dark")
    add_rotated(1, 0, 0, "center_lower_left_fold", (1.0, 3.0, 0.55), (2.1, 6.0, 1.8), "panel", "z", 45, (2.2, 4.5, 1.0))
    add_rotated(1, 0, 0, "center_lower_right_fold", (13.95, 3.0, 0.55), (15.05, 6.0, 1.8), "panel", "z", -45, (14.6, 4.5, 1.0))

    add_global_box("center_upper_brow", (16.7, 20.5, 0.75), (32.3, 22.55, 3.2), "panel")
    add_rotated(1, 0, 1, "center_brow_slope", (1.1, 5.05, 0.9), (15.8, 6.35, 5.5), "panel_light", "x", -22.5, (8.45, 5.7, 3.2))
    add_global_box("center_roof_middle", (17.8, 22.4, 3.1), (32, 25.35, 13.35), "panel")
    add_rotated(1, 0, 1, "center_roof_fold", (2.65, 8.35, 2.5), (15.5, 10.0, 13.5), "panel_light", "x", -22.5, (9.1, 9.2, 8.0))
    add_global_box("center_roof_upper", (19.2, 25.7, 3.8), (31.7, 27.75, 13.1), "panel_light")
    add_global_box("center_roof_ridge", (20.2, 27.35, 4.8), (30.8, 28.35, 12.4), "panel_dark")

    # Right bay: narrow front control spine and a deep rear/side louver radiator.
    add_global_box("right_cabinet_core", (33, 3.25, 2.1), (46.1, 20.75, 13.9), "panel_dark")
    add_global_box("right_front_shell", (33.3, 4.2, 0.8), (45.5, 19.2, 2.45), "panel")
    add_global_box("right_control_spine", (33.35, 5.0, 0), (36.45, 15.5, 1.15), "frame")
    add_global_box("right_control_plate", (33.85, 5.6, 0), (35.95, 12.2, 0.3), "panel_dark")
    add_global_box("right_display_amber", (34.9, 10.5, 0), (35.55, 11.2, 0.15), "amber", shade=False)
    add_global_box("right_display_cyan", (34.05, 9.0, 0), (34.8, 9.8, 0.15), "cyan", shade=False)
    add_global_box("right_switch", (35.0, 8.85, 0), (35.6, 9.5, 0.15), "frame")
    add_global_box("right_front_radiator", (37.0, 6.2, 0), (45.0, 18.8, 0.4), "vent")
    for index in range(5):
        x = 37.45 + index * 1.45
        add_global_box(f"right_front_radiator_fin_{index}", (x, 6.55, 0), (x + 0.38, 18.45, 0.75), "frame")

    add_global_box("right_rear_radiator_body", (43.7, 5.1, 3.4), (47.35, 20.8, 14.8), "panel_dark")
    add_global_box("right_side_grille", (47.2, 6.1, 3.8), (48, 20.0, 14.35), "grille")
    add_global_box("right_side_grille_top", (46.8, 19.7, 3.5), (48, 21.0, 14.6), "panel_light")
    add_global_box("right_side_grille_bottom", (46.8, 5.1, 3.5), (48, 6.4, 14.6), "panel_light")
    add_global_box("right_side_grille_front", (46.8, 6.2, 3.4), (48, 19.9, 4.65), "panel_light")
    add_global_box("right_side_grille_rear", (46.8, 6.2, 13.5), (48, 19.9, 14.75), "panel_light")
    for index in range(6):
        y = 7.0 + index * 2.0
        add_global_box(f"right_side_louver_{index}", (47.35, y, 4.25), (48, y + 0.42, 13.95), "frame")

    add_rotated(2, 0, 0, "right_lower_left_bevel", (1.15, 4.1, 0.45), (2.3, 7.4, 1.65), "panel_light", "z", 45, (2.5, 5.75, 1.0))
    add_rotated(2, 0, 0, "right_lower_right_bevel", (13.25, 4.1, 0.45), (14.4, 7.4, 1.65), "panel_light", "z", -45, (13.05, 5.75, 1.0))
    add_rotated(2, 0, 1, "right_upper_left_bevel", (1.15, 0.9, 0.45), (2.3, 4.2, 1.65), "panel_light", "z", -45, (2.5, 2.55, 1.0))
    add_rotated(2, 0, 1, "right_upper_right_bevel", (13.25, 0.9, 0.45), (14.4, 4.2, 1.65), "panel_light", "z", 45, (13.05, 2.55, 1.0))

    add_global_box("right_roof_lower", (32.8, 20.3, 2), (46.2, 22.6, 14), "panel")
    add_rotated(2, 0, 1, "right_roof_front_slope", (1.15, 5.25, 1.2), (14.2, 6.55, 5.3), "panel_light", "x", -22.5, (7.7, 5.9, 3.25))
    add_global_box("right_roof_middle", (33.7, 22.3, 3), (45.8, 24.65, 13.35), "panel")
    add_global_box("right_roof_upper", (34.7, 24.4, 4.1), (44.8, 26.25, 12.7), "panel_light")

    # Two-tier plinth and four walls form a visibly hollow square exhaust tower.
    add_global_box("exhaust_plinth_lower", (36.0, 25.7, 6.8), (43.4, 27.2, 14.0), "frame")
    add_global_box("exhaust_plinth_upper", (36.8, 27.0, 7.6), (42.6, 28.2, 13.2), "panel_dark")
    add_global_box("exhaust_north_wall", (37.2, 28.0, 8.0), (42.2, 32, 9.0), "frame")
    add_global_box("exhaust_south_wall", (37.2, 28.0, 12.0), (42.2, 32, 13.0), "frame")
    add_global_box("exhaust_west_wall", (37.2, 28.0, 9.0), (38.2, 32, 12.0), "frame")
    add_global_box("exhaust_east_wall", (41.2, 28.0, 9.0), (42.2, 32, 12.0), "frame")
    add_global_box("exhaust_inner_floor", (38.2, 28.1, 9.0), (41.2, 28.35, 12.0), "exhaust")
    add_global_box("exhaust_front_seam", (37.1, 29.6, 7.85), (42.3, 29.9, 9.1), "panel_dark")

    # Rear row: a real engine compartment, not a copy of the front facade.
    # The center seam is deliberately open around the service channels so the
    # full-depth frame and engine bed remain legible in the assembled model.
    add_global_box("rear_engine_bed", (2.0, 2.35, 17.15), (32.0, 4.15, 29.75), "frame")
    add_global_box("rear_engine_core", (3.0, 4.0, 17.25), (31.25, 18.75, 29.15), "panel_dark")
    add_global_box("rear_engine_left_cheek", (1.85, 5.0, 17.8), (3.35, 19.4, 29.3), "panel")
    add_global_box("rear_engine_right_cheek", (30.85, 5.0, 17.8), (32.35, 19.4, 29.3), "panel")
    add_global_box("rear_engine_hood_lower", (2.35, 18.4, 17.0), (31.8, 20.5, 29.65), "panel")
    add_global_box("rear_engine_hood_middle", (4.0, 20.25, 18.15), (30.35, 22.4, 28.65), "panel")
    add_global_box("rear_engine_hood_ridge", (7.0, 22.2, 19.55), (28.5, 24.4, 27.45), "panel_light")
    add_global_box("rear_engine_top_hatch", (11.0, 24.15, 20.7), (24.75, 24.7, 26.4), "panel_dark")
    add_global_box("rear_engine_hatch_handle", (16.25, 24.55, 22.55), (19.5, 25.25, 24.4), "frame")

    # Long square-section tubes continue the front cage through both depth cells.
    add_global_box("left_mid_depth_tube", (1.35, 11.8, 1.5), (2.45, 13.05, 30.5), "frame")
    add_global_box("right_mid_depth_tube", (45.55, 11.8, 1.5), (46.65, 13.05, 30.5), "frame")
    add_global_box("rear_engine_pipe_left", (5.0, 21.7, 16.75), (6.4, 23.05, 30.25), "frame")
    add_global_box("rear_engine_pipe_center", (23.0, 22.15, 16.75), (24.4, 23.5, 30.25), "frame")
    add_global_box("rear_engine_pipe_bridge", (5.0, 22.0, 27.9), (24.4, 23.3, 29.2), "frame")
    add_global_box("rear_engine_pipe_collar_left", (4.45, 21.3, 19.0), (6.95, 23.45, 21.0), "panel_light")
    add_global_box("rear_engine_pipe_collar_center", (22.45, 21.7, 24.7), (24.95, 23.9, 26.7), "panel_light")

    # Side-access details make the engine half readable from the isometric view.
    add_global_box("rear_engine_side_recess", (31.8, 7.0, 19.0), (32.45, 17.9, 27.8), "vent")
    for index in range(5):
        z = 19.45 + index * 1.65
        add_global_box(
            f"rear_engine_side_fin_{index}",
            (31.7, 7.4, z),
            (32.7, 17.5, z + 0.38),
            "frame",
        )
    add_global_box("rear_engine_service_plate", (31.75, 18.15, 20.0), (32.55, 21.0, 27.0), "panel_light")
    add_global_box("rear_engine_service_light", (32.35, 19.15, 22.2), (32.65, 20.05, 23.6), "cyan", shade=False)

    # Rear-mounted radiator has its own volume, side grille, and south-face grille.
    add_global_box("rear_radiator_core", (33.0, 4.15, 17.35), (46.15, 21.9, 30.2), "panel_dark")
    add_global_box("rear_radiator_shell_lower", (33.2, 3.65, 18.0), (45.95, 6.0, 30.65), "panel")
    add_global_box("rear_radiator_shell_upper", (33.2, 20.4, 18.0), (45.95, 22.75, 30.65), "panel_light")
    add_global_box("rear_radiator_side_grille", (46.0, 6.0, 18.2), (48.0, 20.4, 29.9), "grille")
    add_global_box("rear_radiator_side_front_edge", (45.75, 5.6, 17.75), (48.0, 20.8, 19.1), "panel_light")
    add_global_box("rear_radiator_side_rear_edge", (45.75, 5.6, 29.0), (48.0, 20.8, 30.35), "panel_light")
    add_global_box("rear_radiator_back_grille", (34.0, 6.0, 31.45), (45.55, 20.6, 32.0), "grille")
    add_global_box("rear_radiator_back_top", (33.3, 20.3, 30.85), (46.0, 21.7, 32.0), "panel_light")
    add_global_box("rear_radiator_back_bottom", (33.3, 5.0, 30.85), (46.0, 6.4, 32.0), "panel_light")
    add_global_box("rear_radiator_back_left", (33.3, 6.0, 30.85), (34.7, 20.6, 32.0), "panel_light")
    add_global_box("rear_radiator_back_right", (44.9, 6.0, 30.85), (46.3, 20.6, 32.0), "panel_light")
    for index in range(6):
        x = 34.65 + index * 1.75
        add_global_box(
            f"rear_radiator_back_fin_{index}",
            (x, 6.4, 31.2),
            (x + 0.38, 20.2, 32.0),
            "frame",
        )

    # Exhaust ducting now has a substantial rear-row base connected to the tower.
    add_global_box("rear_exhaust_duct", (37.0, 22.0, 13.0), (42.6, 24.0, 23.0), "panel_dark")
    add_global_box("rear_exhaust_plinth_lower", (35.5, 22.4, 18.4), (44.0, 24.4, 29.0), "frame")
    add_global_box("rear_exhaust_plinth_upper", (36.5, 24.15, 20.0), (43.0, 26.15, 27.5), "panel_dark")
    add_global_box("rear_exhaust_riser_north", (37.4, 25.9, 21.0), (42.2, 30.3, 22.0), "frame")
    add_global_box("rear_exhaust_riser_south", (37.4, 25.9, 25.5), (42.2, 30.3, 26.5), "frame")
    add_global_box("rear_exhaust_riser_west", (37.4, 25.9, 22.0), (38.4, 30.3, 25.5), "frame")
    add_global_box("rear_exhaust_riser_east", (41.2, 25.9, 22.0), (42.2, 30.3, 25.5), "frame")
    add_global_box("rear_exhaust_riser_inner", (38.4, 26.0, 22.0), (41.2, 26.25, 25.5), "exhaust")

    # Back service wall: left maintenance door, center ventilation, right radiator.
    add_global_box("back_service_shell_left", (2.15, 4.0, 30.8), (15.0, 20.15, 32.0), "panel")
    add_global_box("back_door_recess", (3.55, 5.5, 31.35), (13.6, 18.6, 32.0), "frame")
    add_global_box("back_door_plate", (4.2, 6.15, 31.7), (12.95, 17.95, 32.0), "panel_dark")
    add_global_box("back_door_left_edge", (3.45, 7.3, 31.55), (4.65, 16.8, 32.0), "panel_light")
    add_global_box("back_door_right_edge", (12.5, 7.3, 31.55), (13.7, 16.8, 32.0), "panel_light")
    add_global_box("back_door_top_edge", (4.4, 17.45, 31.55), (12.75, 18.65, 32.0), "panel_light")
    add_global_box("back_door_bottom_edge", (4.4, 5.5, 31.55), (12.75, 6.7, 32.0), "panel_light")
    add_global_box("back_door_handle", (11.25, 10.9, 31.85), (12.15, 13.3, 32.0), "frame")
    add_global_box("back_door_hinge_upper", (4.0, 14.4, 31.8), (4.55, 16.0, 32.0), "frame")
    add_global_box("back_door_hinge_lower", (4.0, 8.1, 31.8), (4.55, 9.7, 32.0), "frame")
    add_global_box("back_door_status", (10.3, 15.1, 31.9), (11.4, 16.1, 32.0), "amber", shade=False)

    add_global_box("back_center_shell", (16.75, 4.3, 30.75), (32.0, 21.0, 32.0), "panel")
    add_global_box("back_center_vent", (18.0, 6.1, 31.45), (30.75, 19.4, 32.0), "vent")
    add_global_box("back_center_vent_top", (17.6, 19.0, 31.25), (31.2, 20.4, 32.0), "panel_light")
    add_global_box("back_center_vent_bottom", (17.6, 5.2, 31.25), (31.2, 6.6, 32.0), "panel_light")
    for index in range(6):
        y = 7.0 + index * 1.85
        add_global_box(
            f"back_center_louver_{index}",
            (18.4, y, 31.7),
            (30.35, y + 0.38, 32.0),
            "frame",
        )


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def write_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    rows = bytearray()
    stride = width * 4
    for y in range(height):
        rows.append(0)
        rows.extend(pixels[y * stride : (y + 1) * stride])
    data = bytearray(b"\x89PNG\r\n\x1a\n")
    data.extend(png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
    data.extend(png_chunk(b"IDAT", zlib.compress(bytes(rows), 9)))
    data.extend(png_chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def make_texture(base: tuple[int, int, int, int], style: str) -> bytearray:
    width = height = 16
    pixels = bytearray(base * (width * height))

    def set_pixel(x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if 0 <= x < width and 0 <= y < height:
            offset = (y * width + x) * 4
            pixels[offset : offset + 4] = bytes(color)

    def line(x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
        dx = abs(x1 - x0)
        sx = 1 if x0 < x1 else -1
        dy = -abs(y1 - y0)
        sy = 1 if y0 < y1 else -1
        error = dx + dy
        while True:
            set_pixel(x0, y0, color)
            if x0 == x1 and y0 == y1:
                break
            twice = 2 * error
            if twice >= dy:
                error += dy
                x0 += sx
            if twice <= dx:
                error += dx
                y0 += sy

    if style in {"frame", "panel", "panel_light", "panel_dark"}:
        highlight = tuple(min(255, channel + 18) for channel in base[:3]) + (255,)
        shadow = tuple(max(0, channel - 16) for channel in base[:3]) + (255,)
        line(0, 0, 15, 0, highlight)
        line(0, 0, 0, 15, highlight)
        line(0, 15, 15, 15, shadow)
        line(15, 0, 15, 15, shadow)
        for x, y, delta in ((3, 4, 7), (11, 2, -6), (7, 12, 5), (14, 8, -5), (1, 10, 4)):
            set_pixel(x, y, tuple(max(0, min(255, channel + delta)) for channel in base[:3]) + (255,))
        if style != "frame":
            line(2, 5, 13, 5, tuple(min(255, channel + 6) for channel in base[:3]) + (255,))
            line(2, 11, 13, 11, tuple(max(0, channel - 7) for channel in base[:3]) + (255,))
    elif style == "grille":
        for y in range(1, 16, 3):
            line(0, y, 15, y, (45, 50, 55, 255))
        for x in range(1, 16, 3):
            line(x, 0, x, 15, (42, 47, 52, 255))
        for y in range(2, 16, 3):
            for x in range(2, 16, 3):
                set_pixel(x, y, (5, 7, 8, 255))
    elif style == "vent":
        for y in range(1, 16, 3):
            line(0, y, 15, y, (64, 70, 76, 255))
            if y + 1 < 16:
                line(0, y + 1, 15, y + 1, (8, 10, 12, 255))
    elif style in {"cyan", "amber"}:
        glow = base
        dark = tuple(max(0, channel // 4) for channel in base[:3]) + (255,)
        for y in range(16):
            for x in range(16):
                if x in (0, 15) or y in (0, 15):
                    set_pixel(x, y, dark)
                elif x in (1, 14) or y in (1, 14):
                    set_pixel(x, y, tuple(max(0, channel // 2) for channel in base[:3]) + (255,))
                else:
                    set_pixel(x, y, glow)
    elif style == "exhaust":
        for y in range(16):
            for x in range(16):
                distance = min(x, y, 15 - x, 15 - y)
                value = 42 if distance < 2 else 6
                set_pixel(x, y, (value, value + 2, value + 3, 255))
    return pixels


def write_textures() -> list[Path]:
    paths = []
    for material, color in BASE_COLORS.items():
        filename = {
            "cyan": "display_cyan.png",
            "amber": "display_amber.png",
            "exhaust": "exhaust_inner.png",
        }.get(material, f"{material}.png")
        path = TEXTURE_DIR / filename
        write_png(path, 16, 16, make_texture(color, material))
        paths.append(path)
    return paths


def json_element(element: dict) -> dict:
    faces = {
        direction: {"texture": f"#{element['material']}", "uv": [0, 0, 16, 16]}
        for direction in ("north", "south", "east", "west", "up", "down")
    }
    result = {
        "name": element["name"],
        "from": element["from"],
        "to": element["to"],
        "faces": faces,
    }
    if not element["shade"]:
        result["shade"] = False
    if "rotation" in element:
        result["rotation"] = element["rotation"]
    return result


def write_models() -> list[Path]:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    # Remove both the legacy x/y names and any stale regenerated x/z/y parts.
    for stale_path in MODEL_DIR.glob("part_x*_y*.json"):
        stale_path.unlink()
    paths = []
    for block_x in range(3):
        for block_z in range(2):
            for block_y in range(2):
                model = {
                    "parent": "minecraft:block/block",
                    "ambientocclusion": True,
                    "textures": {**TEXTURES, "particle": TEXTURES["panel"]},
                    "elements": [json_element(element) for element in PARTS[(block_x, block_z, block_y)]],
                }
                path = MODEL_DIR / f"part_x{block_x}_z{block_z}_y{block_y}.json"
                path.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
                paths.append(path)
    return paths


def rotate_point(point: tuple[float, float, float], rotation: dict | None) -> tuple[float, float, float]:
    if rotation is None:
        return point
    ox, oy, oz = rotation["origin"]
    x, y, z = point[0] - ox, point[1] - oy, point[2] - oz
    angle = math.radians(rotation["angle"])
    cosine, sine = math.cos(angle), math.sin(angle)
    if rotation["axis"] == "x":
        y, z = y * cosine - z * sine, y * sine + z * cosine
    elif rotation["axis"] == "y":
        x, z = x * cosine + z * sine, -x * sine + z * cosine
    elif rotation["axis"] == "z":
        x, y = x * cosine - y * sine, x * sine + y * cosine
    else:
        raise ValueError(f"Unsupported rotation axis: {rotation['axis']}")
    return x + ox, y + oy, z + oz


def element_vertices(block_x: int, block_z: int, block_y: int, element: dict) -> list[tuple[float, float, float]]:
    x0, y0, z0 = element["from"]
    x1, y1, z1 = element["to"]
    local = [
        (x0, y0, z0),
        (x1, y0, z0),
        (x1, y1, z0),
        (x0, y1, z0),
        (x0, y0, z1),
        (x1, y0, z1),
        (x1, y1, z1),
        (x0, y1, z1),
    ]
    transformed = [rotate_point(point, element.get("rotation")) for point in local]
    return [
        (x + block_x * 16, y + block_y * 16, z + block_z * 16)
        for x, y, z in transformed
    ]


def shade_color(color: tuple[int, int, int, int], factor: float) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, int(channel * factor))) for channel in color[:3]) + (color[3],)


def set_canvas_pixel(canvas: bytearray, width: int, height: int, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if 0 <= x < width and 0 <= y < height:
        offset = (y * width + x) * 4
        canvas[offset : offset + 4] = bytes(color)


def draw_line(
    canvas: bytearray,
    width: int,
    height: int,
    start: tuple[float, float],
    end: tuple[float, float],
    color: tuple[int, int, int, int],
    thickness: int = 1,
) -> None:
    x0, y0 = int(round(start[0])), int(round(start[1]))
    x1, y1 = int(round(end[0])), int(round(end[1]))
    dx = abs(x1 - x0)
    sx = 1 if x0 < x1 else -1
    dy = -abs(y1 - y0)
    sy = 1 if y0 < y1 else -1
    error = dx + dy
    radius = max(0, thickness // 2)
    while True:
        for py in range(y0 - radius, y0 + radius + 1):
            for px in range(x0 - radius, x0 + radius + 1):
                set_canvas_pixel(canvas, width, height, px, py, color)
        if x0 == x1 and y0 == y1:
            break
        twice = 2 * error
        if twice >= dy:
            error += dy
            x0 += sx
        if twice <= dx:
            error += dx
            y0 += sy


def fill_polygon(
    canvas: bytearray,
    width: int,
    height: int,
    points: list[tuple[float, float]],
    color: tuple[int, int, int, int],
) -> None:
    minimum_y = max(0, int(math.floor(min(point[1] for point in points))))
    maximum_y = min(height - 1, int(math.ceil(max(point[1] for point in points))))
    for y in range(minimum_y, maximum_y + 1):
        scan_y = y + 0.5
        intersections = []
        for index, point in enumerate(points):
            following = points[(index + 1) % len(points)]
            if (point[1] <= scan_y < following[1]) or (following[1] <= scan_y < point[1]):
                ratio = (scan_y - point[1]) / (following[1] - point[1])
                intersections.append(point[0] + ratio * (following[0] - point[0]))
        intersections.sort()
        for index in range(0, len(intersections) - 1, 2):
            x0 = max(0, int(math.ceil(intersections[index])))
            x1 = min(width - 1, int(math.floor(intersections[index + 1])))
            for x in range(x0, x1 + 1):
                set_canvas_pixel(canvas, width, height, x, y, color)


def interpolate(start: tuple[float, float], end: tuple[float, float], amount: float) -> tuple[float, float]:
    return start[0] + (end[0] - start[0]) * amount, start[1] + (end[1] - start[1]) * amount


def draw_surface_detail(
    canvas: bytearray,
    width: int,
    height: int,
    points: list[tuple[float, float]],
    material: str,
    face: str,
) -> None:
    if material not in {"grille", "vent"} or face not in {"north", "east"}:
        return
    detail = (61, 68, 74, 255) if material == "grille" else (7, 9, 11, 255)
    count = 7 if material == "grille" else 6
    if material == "grille":
        for index in range(1, count):
            amount = index / count
            draw_line(canvas, width, height, interpolate(points[0], points[1], amount), interpolate(points[3], points[2], amount), detail)
    for index in range(1, count):
        amount = index / count
        draw_line(canvas, width, height, interpolate(points[0], points[3], amount), interpolate(points[1], points[2], amount), detail, 1 if material == "grille" else 2)


def project(point: tuple[float, float, float]) -> tuple[float, float]:
    x, y, z = point
    return 80 + x * 20.0 + z * 9.5, 900 - y * 19.0 - z * 6.5 + x * 1.2


def render_preview() -> None:
    width, height = 1500, 1000
    canvas = bytearray((236, 238, 240, 255) * (width * height))
    ground = [(45, 915), (1055, 978), (1460, 730), (430, 675)]
    fill_polygon(canvas, width, height, ground, (210, 214, 218, 255))

    renderables = []
    for (block_x, block_z, block_y), elements in PARTS.items():
        for element in elements:
            vertices = element_vertices(block_x, block_z, block_y, element)
            center = tuple(sum(vertex[axis] for vertex in vertices) / 8 for axis in range(3))
            depth = center[0] - center[2] + center[1] * 0.18
            renderables.append((depth, vertices, element))
    renderables.sort(key=lambda item: item[0])

    face_definitions = (
        ("north", (0, 1, 2, 3), 0.88),
        ("east", (1, 5, 6, 2), 0.68),
        ("up", (3, 2, 6, 7), 1.12),
    )
    outline = (15, 17, 20, 255)
    for _, vertices, element in renderables:
        for face, indices, factor in face_definitions:
            points = [project(vertices[index]) for index in indices]
            color = shade_color(BASE_COLORS[element["material"]], factor)
            if element["material"] in {"cyan", "amber"}:
                color = BASE_COLORS[element["material"]]
            fill_polygon(canvas, width, height, points, color)
            for index, point in enumerate(points):
                draw_line(canvas, width, height, point, points[(index + 1) % len(points)], outline)
            draw_surface_detail(canvas, width, height, points, element["material"], face)

    for _, vertices, element in renderables:
        if element["material"] not in {"cyan", "amber"}:
            continue
        for face, indices, factor in face_definitions:
            points = [project(vertices[index]) for index in indices]
            fill_polygon(canvas, width, height, points, BASE_COLORS[element["material"]])
            for index, point in enumerate(points):
                draw_line(canvas, width, height, point, points[(index + 1) % len(points)], outline)

    write_png(PREVIEW_PATH, width, height, canvas)


def validate_models(model_paths: list[Path], texture_paths: list[Path]) -> dict:
    errors = []
    if len(model_paths) != 12:
        errors.append(f"Expected 12 models, found {len(model_paths)}")
    texture_set = {path.resolve() for path in texture_paths}
    expected_texture_set = {
        (TEXTURE_DIR / f"{resource.rsplit('/', 1)[1]}.png").resolve()
        for resource in TEXTURES.values()
    }
    missing_textures = expected_texture_set - texture_set
    if missing_textures:
        errors.append(f"Missing generated textures: {sorted(str(path) for path in missing_textures)}")

    element_count = 0
    elements_by_part = {}
    transformed_vertices = []
    allowed_angles = {-45, -22.5, 0, 22.5, 45}
    for path in model_paths:
        parsed = json.loads(path.read_text(encoding="utf-8"))
        elements_by_part[path.name] = len(parsed.get("elements", []))
        if not parsed.get("elements"):
            errors.append(f"Model has no elements: {path.name}")
        for element in parsed.get("elements", []):
            element_count += 1
            for key in ("from", "to"):
                if len(element[key]) != 3 or any(value < 0 or value > 16 for value in element[key]):
                    errors.append(f"Illegal {key} coordinates in {path.name}: {element[key]}")
            if any(element["from"][index] >= element["to"][index] for index in range(3)):
                errors.append(f"Degenerate element in {path.name}: {element['name']}")
            if "rotation" in element:
                rotation = element["rotation"]
                if rotation["axis"] not in {"x", "y", "z"} or rotation["angle"] not in allowed_angles:
                    errors.append(f"Illegal rotation in {path.name}: {element['name']}")
                if any(value < 0 or value > 16 for value in rotation["origin"]):
                    errors.append(f"Illegal rotation origin in {path.name}: {element['name']}")
            for face in element["faces"].values():
                reference = face["texture"]
                if not reference.startswith("#") or reference[1:] not in parsed["textures"]:
                    errors.append(f"Unresolved texture reference in {path.name}: {reference}")

        match = re.fullmatch(r"part_x([0-2])_z([0-1])_y([0-1])", path.stem)
        if match is None:
            errors.append(f"Unexpected model name: {path.name}")
            continue
        block_x, block_z, block_y = (int(value) for value in match.groups())
        for source in PARTS[(block_x, block_z, block_y)]:
            x0, y0, z0 = source["from"]
            x1, y1, z1 = source["to"]
            local_vertices = [
                (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
            ]
            rotated_local_vertices = [
                rotate_point(vertex, source.get("rotation")) for vertex in local_vertices
            ]
            for vertex in rotated_local_vertices:
                if any(value < -0.0001 or value > 16.0001 for value in vertex):
                    errors.append(
                        f"Rotated element leaves local block in {path.name}: "
                        f"{source['name']} -> {tuple(clean_number(value) for value in vertex)}"
                    )
            transformed_vertices.extend(element_vertices(block_x, block_z, block_y, source))

    for path in expected_texture_set:
        if not path.is_file():
            errors.append(f"Texture file does not exist: {path}")

    minimum = [min(vertex[axis] for vertex in transformed_vertices) for axis in range(3)]
    maximum = [max(vertex[axis] for vertex in transformed_vertices) for axis in range(3)]
    expected_minimum = [0, 0, 0]
    expected_maximum = [48, 32, 32]
    for axis in range(3):
        if minimum[axis] < -0.0001 or maximum[axis] > expected_maximum[axis] + 0.0001:
            errors.append(f"Rotated geometry exceeds assembly bounds on axis {axis}: {minimum[axis]}..{maximum[axis]}")
        if abs(minimum[axis] - expected_minimum[axis]) > 0.0001 or abs(maximum[axis] - expected_maximum[axis]) > 0.0001:
            errors.append(f"Assembly does not exactly fill expected bounds on axis {axis}: {minimum[axis]}..{maximum[axis]}")

    if errors:
        raise RuntimeError("\n".join(errors))
    return {
        "status": "PASS",
        "models": len(model_paths),
        "textures": len(texture_paths),
        "elements": element_count,
        "elements_by_part": elements_by_part,
        "bounds": {"min": [clean_number(value) for value in minimum], "max": [clean_number(value) for value in maximum]},
    }


def main() -> None:
    build_geometry()
    texture_paths = write_textures()
    model_paths = write_models()
    render_preview()
    validation = validate_models(model_paths, texture_paths)
    print(json.dumps({
        "models": [str(path.relative_to(ROOT)) for path in model_paths],
        "textures": [str(path.relative_to(ROOT)) for path in texture_paths],
        "preview": str(PREVIEW_PATH.relative_to(ROOT)),
        "validation": validation,
    }, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
