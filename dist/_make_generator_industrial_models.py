from __future__ import annotations

import json
import math
import random
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "src/main/resources/assets/miningdim/models/block/generator/industrial"
TEXTURE_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block/generator/industrial"
PREVIEW_PATH = ROOT / "dist/generator-industrial-model-preview.png"

TEXTURES = {
    "base": "miningdim:block/generator/industrial/base_steel",
    "dark": "miningdim:block/generator/industrial/dark_steel",
    "panel": "miningdim:block/generator/industrial/panel_steel",
    "frame": "miningdim:block/generator/industrial/frame_black",
    "edge": "miningdim:block/generator/industrial/edge_steel",
    "vent": "miningdim:block/generator/industrial/vent_dark",
    "warning": "miningdim:block/generator/industrial/warning_stripe",
    "control": "miningdim:block/generator/industrial/control_panel",
    "indicator": "miningdim:block/generator/industrial/indicator_red",
    "exhaust": "miningdim:block/generator/industrial/exhaust_soot",
    "rivet": "miningdim:block/generator/industrial/rivet_steel",
}

PART_KEYS = [(x, z, y) for x in range(3) for z in range(2) for y in range(2)]
FACE_NAMES = ("north", "south", "east", "west", "up", "down")
ALLOWED_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}


@dataclass
class Element:
    name: str
    start: tuple[float, float, float]
    end: tuple[float, float, float]
    texture: str
    face_textures: dict[str, str] = field(default_factory=dict)
    rotation: dict[str, object] | None = None
    shade: bool = True


PARTS: dict[tuple[int, int, int], list[Element]] = {key: [] for key in PART_KEYS}


def vec(values: Iterable[float]) -> tuple[float, float, float]:
    result = tuple(float(value) for value in values)
    if len(result) != 3:
        raise ValueError(f"Expected three coordinates, received {result}")
    return result


def rotation(axis: str, angle: float, origin: Iterable[float]) -> dict[str, object]:
    return {"axis": axis, "angle": float(angle), "origin": vec(origin)}


def add(
    part: tuple[int, int, int],
    name: str,
    start: Iterable[float],
    end: Iterable[float],
    texture: str,
    *,
    face_textures: dict[str, str] | None = None,
    rotate: dict[str, object] | None = None,
    shade: bool = True,
) -> None:
    PARTS[part].append(
        Element(
            name=name,
            start=vec(start),
            end=vec(end),
            texture=texture,
            face_textures=dict(face_textures or {}),
            rotation=rotate,
            shade=shade,
        )
    )


def rivet(part: tuple[int, int, int], name: str, x: float, y: float, z: float, size: float = 0.55) -> None:
    add(part, name, (x, y, z), (x + size, y + size, z + size), "rivet", shade=False)


def rotated_brace(
    part: tuple[int, int, int],
    name: str,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    angle: float,
    origin: tuple[float, float, float],
) -> None:
    add(part, name, start, end, "edge", rotate=rotation("z", angle, origin))


def add_common_lower_frame(x_index: int) -> None:
    part = (x_index, 0, 0)
    add(part, "full_depth_base", (0, 0, 0), (16, 1.5, 16), "base")
    add(part, "front_lower_rail", (0, 1.5, 0), (16, 3.0, 2.0), "frame", face_textures={"up": "edge"})
    add(part, "rear_lower_rail", (0, 1.5, 14), (16, 3.0, 16), "frame", face_textures={"up": "edge"})
    add(part, "left_lower_rail", (0, 1.5, 2), (1.5, 3.0, 14), "frame", face_textures={"up": "edge"})
    add(part, "right_lower_rail", (14.5, 1.5, 2), (16, 3.0, 14), "frame", face_textures={"up": "edge"})
    add(part, "front_left_post", (0, 3, 0), (1.3, 16, 2.2), "frame", face_textures={"east": "edge"})
    add(part, "front_right_post", (14.7, 3, 0), (16, 16, 2.2), "frame", face_textures={"west": "edge"})
    add(part, "rear_left_post", (0, 3, 13.8), (1.3, 16, 16), "frame")
    add(part, "rear_right_post", (14.7, 3, 13.8), (16, 16, 16), "frame")
    for px in (0.25, 15.2):
        rivet(part, "base_post_rivet", px, 2.0, 0.15, 0.55)
        rivet(part, "upper_post_rivet", px, 14.2, 0.15, 0.55)


def add_common_upper_frame(x_index: int) -> None:
    part = (x_index, 0, 1)
    add(part, "front_left_upper_post", (0, 0, 0), (1.3, 8.5, 2.2), "frame", face_textures={"east": "edge"})
    add(part, "front_right_upper_post", (14.7, 0, 0), (16, 8.5, 2.2), "frame", face_textures={"west": "edge"})
    add(part, "rear_left_upper_post", (0, 0, 13.8), (1.3, 8.5, 16), "frame")
    add(part, "rear_right_upper_post", (14.7, 0, 13.8), (16, 8.5, 16), "frame")
    add(part, "front_upper_seam_beam", (0, 0, 0.1), (16, 1.4, 2.1), "frame", face_textures={"up": "edge"})
    for px in (0.25, 15.2):
        rivet(part, "upper_seam_rivet", px, 0.35, 0.05, 0.55)


def build_left_access_section() -> None:
    lower = (0, 0, 0)
    add(lower, "left_access_body", (2.1, 3, 2.3), (13.9, 13.4, 14.1), "dark", face_textures={"north": "panel", "up": "edge"})
    add(lower, "left_access_door", (3.2, 4.2, 1.75), (12.7, 10.6, 2.55), "panel")
    add(lower, "left_door_top_frame", (3.0, 10.5, 1.45), (13.0, 11.25, 2.75), "edge")
    add(lower, "left_door_bottom_frame", (3.0, 3.7, 1.45), (13.0, 4.45, 2.75), "edge")
    add(lower, "left_door_side_frame", (2.9, 4.3, 1.45), (3.65, 10.55, 2.75), "edge")
    add(lower, "right_door_side_frame", (12.25, 4.3, 1.45), (13.0, 10.55, 2.75), "edge")
    add(lower, "left_door_handle", (10.9, 6.0, 1.15), (11.7, 8.4, 1.65), "frame", face_textures={"north": "edge"})
    add(lower, "left_low_warning", (7.0, 2.15, 0.35), (13.9, 3.8, 0.95), "frame", face_textures={"north": "warning", "up": "warning"}, shade=False)
    rotated_brace(lower, "left_outer_diagonal", (1.45, 3.2, 0.45), (2.55, 14.1, 1.35), -22.5, (2.0, 3.2, 0.9))
    rotated_brace(lower, "right_outer_diagonal", (13.45, 3.2, 0.45), (14.55, 14.1, 1.35), 22.5, (14.0, 3.2, 0.9))
    add(lower, "left_side_inner_shadow", (1.4, 4.0, 3.0), (2.2, 13.0, 13.0), "exhaust")
    add(lower, "right_side_inner_shadow", (13.8, 4.0, 3.0), (14.6, 13.0, 13.0), "exhaust")
    for x in (3.35, 12.1):
        for y in (4.4, 9.65):
            rivet(lower, "left_door_rivet", x, y, 1.1)

    upper = (0, 0, 1)
    add(upper, "access_upper_plinth", (1.4, 0, 2.0), (14.5, 2.5, 15.0), "base", face_textures={"north": "edge", "up": "panel"})
    add(upper, "access_upper_core", (2.2, 2.2, 5.2), (13.9, 7.6, 14.3), "dark")
    slope = rotation("x", -22.5, (8.0, 5.7, 10.5))
    add(upper, "access_sloped_armor", (3.0, 5.15, 1.7), (13.0, 6.25, 9.3), "panel", rotate=slope)
    add(upper, "access_sloped_left_edge", (2.55, 4.95, 1.6), (3.45, 6.55, 9.5), "edge", rotate=slope)
    add(upper, "access_sloped_right_edge", (12.55, 4.95, 1.6), (13.45, 6.55, 9.5), "edge", rotate=slope)
    for index in range(4):
        z0 = 2.5 + index * 1.15
        add(upper, f"access_sloped_louver_{index}", (5.0, 6.28, z0), (10.8, 6.58, z0 + 0.48), "exhaust", rotate=slope, shade=False)
    add(upper, "access_rear_housing", (3.4, 5.8, 8.5), (13.2, 10.4, 14.5), "dark", face_textures={"up": "panel"})
    add(upper, "access_roof", (3.0, 9.4, 5.0), (13.6, 11.2, 15.0), "panel", face_textures={"north": "edge", "up": "panel"})
    add(upper, "access_roof_inset", (4.15, 11.2, 6.1), (12.45, 11.7, 14.0), "dark")
    add(upper, "access_top_rear_rail", (2.1, 12.3, 12.0), (14.8, 14.1, 15.0), "frame", face_textures={"up": "edge"})
    add(upper, "access_top_left_cap", (2.1, 10.0, 11.7), (3.3, 13.0, 15.2), "frame")
    add(upper, "access_top_right_cap", (13.6, 9.5, 11.7), (14.8, 13.0, 15.2), "frame")
    for x, y, z in ((3.35, 3.3, 2.2), (12.1, 3.3, 2.2), (4.0, 10.2, 4.6), (12.1, 10.2, 4.6), (3.0, 12.8, 11.6), (13.8, 12.8, 11.6)):
        rivet(upper, "access_upper_rivet", x, y, z)


def build_center_power_section() -> None:
    lower = (1, 0, 0)
    add(lower, "power_lower_body", (1.5, 3.0, 2.2), (14.5, 15.2, 14.2), "dark", face_textures={"north": "panel", "up": "edge"})
    add(lower, "power_service_recess", (2.3, 4.0, 1.55), (13.7, 10.5, 2.55), "exhaust")
    door_ranges = ((2.8, 6.0), (6.35, 9.65), (10.0, 13.2))
    for index, (x0, x1) in enumerate(door_ranges):
        add(lower, f"power_service_door_{index}", (x0, 4.45, 1.2), (x1, 9.8, 1.85), "dark", face_textures={"north": "panel"})
        rivet(lower, f"power_door_rivet_{index}", x0 + 1.15, 6.8, 0.85)
    add(lower, "power_service_top_beam", (2.0, 10.2, 1.0), (14.0, 12.0, 2.8), "frame", face_textures={"up": "edge"})
    add(lower, "power_service_bottom_beam", (2.0, 3.2, 1.0), (14.0, 4.4, 2.8), "frame", face_textures={"up": "edge"})
    rotated_brace(lower, "power_left_service_brace", (3.2, 4.0, 0.65), (4.25, 10.2, 1.45), -22.5, (3.7, 4.0, 1.0))
    rotated_brace(lower, "power_right_service_brace", (11.75, 4.0, 0.65), (12.8, 10.2, 1.45), 22.5, (12.3, 4.0, 1.0))
    add(lower, "power_heavy_crossbeam", (1.1, 12.0, 0.6), (14.9, 14.2, 3.0), "frame", face_textures={"north": "edge", "up": "edge"})
    for x in (1.5, 5.4, 10.0, 13.9):
        rivet(lower, "power_crossbeam_rivet", x, 12.8, 0.25, 0.6)

    upper = (1, 0, 1)
    add(upper, "power_upper_body", (1.3, 0, 2.4), (14.7, 13.6, 15.0), "dark", face_textures={"north": "panel", "up": "panel"})
    add(upper, "power_louver_recess", (2.15, 1.8, 1.35), (13.85, 9.7, 3.0), "exhaust")
    add(upper, "power_louver_left_frame", (1.75, 1.3, 0.95), (3.0, 10.2, 3.4), "frame", face_textures={"north": "edge"})
    add(upper, "power_louver_right_frame", (13.0, 1.3, 0.95), (14.25, 10.2, 3.4), "frame", face_textures={"north": "edge"})
    add(upper, "power_louver_top_frame", (2.0, 9.4, 0.95), (14.0, 10.7, 3.4), "frame", face_textures={"north": "edge"})
    add(upper, "power_louver_bottom_frame", (2.0, 1.15, 0.95), (14.0, 2.45, 3.4), "frame", face_textures={"north": "edge"})
    for index in range(5):
        y0 = 2.55 + index * 1.35
        add(upper, f"power_louver_slat_{index}", (3.0, y0, 0.7), (13.0, y0 + 0.55, 2.25), "edge", face_textures={"north": "vent"}, shade=False)
    for x in (6.3, 9.45):
        add(upper, "power_louver_separator", (x, 2.1, 0.6), (x + 0.55, 9.7, 2.4), "frame")
    add(upper, "power_louver_lower_shelf", (1.9, 0.2, 0.8), (14.1, 1.65, 4.0), "frame", face_textures={"up": "edge"})
    add(upper, "power_upper_cowl", (2.2, 9.8, 3.8), (13.8, 14.1, 15.0), "dark", face_textures={"up": "panel"})
    add(upper, "power_cowl_roof", (3.0, 13.6, 5.0), (13.0, 15.3, 15.2), "panel", face_textures={"north": "edge"})
    rotated_brace(upper, "power_cowl_left_bevel", (1.75, 9.4, 2.0), (3.0, 14.1, 3.2), -22.5, (2.35, 9.4, 2.6))
    rotated_brace(upper, "power_cowl_right_bevel", (13.0, 9.4, 2.0), (14.25, 14.1, 3.2), 22.5, (13.65, 9.4, 2.6))
    box_ranges = ((2.5, 5.2), (6.6, 9.3), (10.6, 13.3))
    for index, (x0, x1) in enumerate(box_ranges):
        add(upper, f"power_terminal_box_{index}", (x0, 10.2, 1.2), (x1, 12.9, 4.2), "panel", face_textures={"north": "edge", "up": "edge"})
        rivet(upper, f"power_terminal_rivet_{index}", x0 + 0.9, 10.7, 0.85)
    add(upper, "power_top_rear_rail", (1.1, 15.0, 12.0), (14.9, 16.0, 15.2), "frame", face_textures={"up": "edge"})
    for x, y in ((1.8, 10.2), (13.7, 10.2), (2.2, 13.0), (13.25, 13.0)):
        rivet(upper, "power_upper_rivet", x, y, 1.15)


def build_right_control_section() -> None:
    lower = (2, 0, 0)
    add(lower, "control_lower_body", (2.0, 3.0, 5.0), (14.0, 15.0, 14.3), "dark", face_textures={"north": "panel", "up": "edge"})
    add(lower, "control_door", (3.8, 3.8, 1.55), (12.1, 9.4, 2.55), "panel")
    add(lower, "control_door_top_frame", (3.4, 9.1, 1.15), (12.5, 10.0, 2.9), "edge")
    add(lower, "control_door_bottom_frame", (3.4, 3.4, 1.15), (12.5, 4.25, 2.9), "edge")
    add(lower, "control_door_left_frame", (3.35, 4.0, 1.15), (4.2, 9.25, 2.9), "edge")
    add(lower, "control_door_right_frame", (11.7, 4.0, 1.15), (12.55, 9.25, 2.9), "edge")
    add(lower, "control_door_vent_back", (4.65, 5.0, 1.0), (8.2, 8.25, 1.7), "exhaust")
    for index in range(3):
        y0 = 5.35 + index * 0.9
        add(lower, f"control_door_vent_slat_{index}", (4.9, y0, 0.7), (8.0, y0 + 0.35, 1.35), "edge")
    add(lower, "control_door_handle", (9.9, 5.2, 0.85), (10.75, 7.9, 1.45), "frame", face_textures={"north": "edge"})
    console_slope = rotation("x", -22.5, (8.0, 12.65, 9.0))
    add(lower, "control_sloped_console", (3.1, 12.0, 1.0), (13.0, 13.3, 7.3), "panel", rotate=console_slope)
    add(lower, "control_console_inset", (4.7, 13.32, 1.55), (11.8, 13.64, 6.1), "control", rotate=console_slope, shade=False)
    add(lower, "control_console_screen", (5.3, 13.66, 2.0), (8.4, 13.9, 3.65), "exhaust", rotate=console_slope, shade=False)
    add(lower, "control_console_red_button", (5.45, 13.92, 4.2), (6.45, 14.12, 5.15), "indicator", rotate=console_slope, shade=False)
    add(lower, "control_console_switch_a", (9.1, 13.68, 2.3), (10.2, 13.92, 3.35), "frame", rotate=console_slope)
    add(lower, "control_console_switch_b", (9.1, 13.68, 3.95), (10.2, 13.92, 5.0), "frame", rotate=console_slope)
    add(lower, "control_side_vent_back", (12.65, 4.7, 1.0), (14.0, 8.8, 2.0), "exhaust")
    for index in range(3):
        y0 = 5.2 + index * 1.0
        add(lower, f"control_side_vent_slat_{index}", (12.4, y0, 0.7), (14.2, y0 + 0.35, 1.4), "edge")
    rotated_brace(lower, "control_left_diagonal", (1.4, 3.2, 0.45), (2.5, 13.2, 1.35), -22.5, (1.95, 3.2, 0.9))
    rotated_brace(lower, "control_right_diagonal", (13.5, 3.2, 0.45), (14.6, 13.2, 1.35), 22.5, (14.05, 3.2, 0.9))
    for x in (3.6, 11.75):
        for y in (3.8, 8.75):
            rivet(lower, "control_door_rivet", x, y, 0.8)

    upper = (2, 0, 1)
    add(upper, "control_top_platform", (1.3, 0, 2.0), (14.7, 3.2, 15.0), "base", face_textures={"north": "edge", "up": "panel"})
    add(upper, "control_platform_front_rail", (0, 2.0, 0), (16, 4.0, 2.3), "frame", face_textures={"up": "edge"})
    add(upper, "control_platform_warning", (10.1, 2.2, 0.1), (13.4, 3.8, 0.65), "frame", face_textures={"north": "warning", "up": "warning"}, shade=False)
    add(upper, "control_platform_rear_rail", (1.0, 2.0, 13.7), (15.0, 4.0, 16.0), "frame", face_textures={"up": "edge"})
    add(upper, "exhaust_pedestal", (5.0, 3.2, 7.0), (12.0, 5.2, 14.2), "frame", face_textures={"up": "edge"})
    add(upper, "exhaust_silencer_body", (4.4, 5.0, 6.0), (12.6, 11.1, 14.5), "panel", face_textures={"north": "dark", "east": "dark", "west": "dark"})
    for x0 in (4.4, 11.6):
        add(upper, "exhaust_silencer_vertical_edge", (x0, 5.0, 5.7), (x0 + 1.0, 11.2, 14.7), "frame")
    add(upper, "exhaust_silencer_top_edge", (4.2, 10.4, 5.7), (12.8, 11.5, 14.7), "edge")
    add(upper, "exhaust_silencer_bottom_edge", (4.2, 4.8, 5.7), (12.8, 5.9, 14.7), "frame")
    add(upper, "exhaust_louver_back", (5.1, 6.1, 5.35), (11.9, 9.8, 6.15), "exhaust")
    for index in range(3):
        y0 = 6.6 + index * 1.05
        add(upper, f"exhaust_louver_slat_{index}", (5.4, y0, 5.0), (11.6, y0 + 0.42, 5.75), "edge", face_textures={"north": "vent"})
    add(upper, "chimney_left_wall", (5.5, 11.0, 8.0), (6.5, 16.0, 14.0), "exhaust", face_textures={"west": "edge", "up": "edge"})
    add(upper, "chimney_right_wall", (10.5, 11.0, 8.0), (11.5, 16.0, 14.0), "exhaust", face_textures={"east": "edge", "up": "edge"})
    add(upper, "chimney_front_wall", (6.5, 11.0, 8.0), (10.5, 16.0, 9.0), "exhaust", face_textures={"north": "dark", "up": "edge"})
    add(upper, "chimney_back_wall", (6.5, 11.0, 13.0), (10.5, 16.0, 14.0), "exhaust", face_textures={"south": "dark", "up": "edge"})
    add(upper, "chimney_front_lip", (5.25, 15.2, 7.75), (11.75, 16.0, 9.25), "edge")
    add(upper, "chimney_back_lip", (5.25, 15.2, 12.75), (11.75, 16.0, 14.25), "edge")
    add(upper, "chimney_left_lip", (5.25, 15.2, 9.25), (6.75, 16.0, 12.75), "edge")
    add(upper, "chimney_right_lip", (10.25, 15.2, 9.25), (11.75, 16.0, 12.75), "edge")
    add(upper, "output_terminal", (12.5, 3.2, 9.8), (15.0, 6.1, 13.1), "dark", face_textures={"north": "panel", "up": "edge"})
    add(upper, "output_terminal_socket", (13.05, 4.0, 9.35), (14.45, 5.35, 10.15), "exhaust")
    for x, y, z in ((4.7, 5.5, 5.25), (11.7, 5.5, 5.25), (4.7, 10.0, 5.25), (11.7, 10.0, 5.25)):
        rivet(upper, "exhaust_rivet", x, y, z)


def add_rear_lower_frame(x_index: int) -> None:
    part = (x_index, 1, 0)
    add(part, "rear_full_depth_base", (0, 0, 0), (16, 1.5, 16), "base")
    add(part, "rear_join_crossbeam", (0, 1.5, 0), (16, 3.0, 1.8), "frame", face_textures={"up": "edge"})
    add(part, "rear_end_crossbeam", (0, 1.5, 14.0), (16, 3.0, 16), "frame", face_textures={"up": "edge", "south": "edge"})
    add(part, "rear_left_depth_rail", (0, 1.5, 1.8), (1.5, 3.0, 14.0), "frame", face_textures={"up": "edge"})
    add(part, "rear_right_depth_rail", (14.5, 1.5, 1.8), (16, 3.0, 14.0), "frame", face_textures={"up": "edge"})
    add(part, "rear_left_join_post", (0, 3.0, 0), (1.3, 16, 1.8), "frame", face_textures={"east": "edge"})
    add(part, "rear_right_join_post", (14.7, 3.0, 0), (16, 16, 1.8), "frame", face_textures={"west": "edge"})
    add(part, "rear_left_end_post", (0, 3.0, 14.0), (1.3, 16, 16), "frame", face_textures={"south": "edge"})
    add(part, "rear_right_end_post", (14.7, 3.0, 14.0), (16, 16, 16), "frame", face_textures={"south": "edge"})
    add(part, "rear_mid_chassis_tie", (1.3, 2.0, 7.2), (14.7, 3.4, 8.7), "edge")
    for px in (0.25, 15.2):
        rivet(part, "rear_base_rivet", px, 2.0, 15.2, 0.55)
        rivet(part, "rear_post_rivet", px, 14.2, 15.2, 0.55)


def add_rear_upper_frame(x_index: int) -> None:
    part = (x_index, 1, 1)
    add(part, "rear_left_join_upper_post", (0, 0, 0), (1.3, 8.5, 1.8), "frame", face_textures={"east": "edge"})
    add(part, "rear_right_join_upper_post", (14.7, 0, 0), (16, 8.5, 1.8), "frame", face_textures={"west": "edge"})
    add(part, "rear_left_end_upper_post", (0, 0, 14.0), (1.3, 13.8, 16), "frame", face_textures={"south": "edge"})
    add(part, "rear_right_end_upper_post", (14.7, 0, 14.0), (16, 13.8, 16), "frame", face_textures={"south": "edge"})
    add(part, "rear_left_roof_depth_rail", (0, 12.5, 1.8), (1.5, 14.4, 14.0), "frame", face_textures={"up": "edge"})
    add(part, "rear_right_roof_depth_rail", (14.5, 12.5, 1.8), (16, 14.4, 14.0), "frame", face_textures={"up": "edge"})
    add(part, "rear_end_crown_beam", (0, 12.5, 14.0), (16, 14.4, 16), "frame", face_textures={"up": "edge", "south": "edge"})
    add(part, "rear_roof_mid_tie", (1.3, 12.8, 7.2), (14.7, 14.2, 8.7), "edge")
    for px in (0.25, 15.2):
        rivet(part, "rear_crown_rivet", px, 12.9, 15.15, 0.55)


def build_rear_access_section() -> None:
    lower = (0, 1, 0)
    add(lower, "rear_access_engine_housing", (2.0, 3.0, 1.4), (14.0, 14.0, 14.3), "dark", face_textures={"south": "panel", "up": "edge"})
    add(lower, "rear_access_service_recess", (3.0, 4.1, 13.8), (13.0, 11.6, 15.1), "exhaust")
    add(lower, "rear_access_service_door", (3.7, 4.8, 14.7), (12.3, 10.9, 15.6), "panel")
    add(lower, "rear_access_door_top_frame", (3.2, 10.6, 14.3), (12.8, 11.6, 15.8), "edge")
    add(lower, "rear_access_door_bottom_frame", (3.2, 4.2, 14.3), (12.8, 5.2, 15.8), "edge")
    add(lower, "rear_access_door_left_frame", (3.1, 5.0, 14.3), (4.1, 10.8, 15.8), "edge")
    add(lower, "rear_access_door_right_frame", (11.9, 5.0, 14.3), (12.9, 10.8, 15.8), "edge")
    add(lower, "rear_access_door_handle", (9.9, 6.3, 15.35), (10.8, 8.8, 15.85), "frame", face_textures={"south": "edge"})
    add(lower, "rear_access_depth_pipe", (11.8, 5.0, 0.2), (13.8, 7.0, 14.7), "exhaust", face_textures={"east": "edge", "up": "edge"})
    for z0 in (1.0, 7.0, 12.7):
        add(lower, "rear_access_pipe_collar", (11.45, 4.65, z0), (14.15, 7.35, z0 + 0.8), "edge")
    for x, y in ((3.45, 4.65), (12.05, 4.65), (3.45, 10.5), (12.05, 10.5)):
        rivet(lower, "rear_access_door_rivet", x, y, 15.25)

    upper = (0, 1, 1)
    add(upper, "rear_access_upper_engine_shell", (2.0, 0, 1.2), (14.0, 10.8, 14.5), "dark", face_textures={"south": "panel", "up": "panel"})
    add(upper, "rear_access_upper_roof", (2.8, 10.2, 1.8), (13.2, 12.2, 14.8), "panel", face_textures={"up": "edge"})
    add(upper, "rear_access_louver_recess", (2.8, 2.0, 13.7), (13.2, 9.2, 15.25), "exhaust")
    add(upper, "rear_access_louver_left_frame", (2.4, 1.6, 13.35), (3.5, 9.8, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_access_louver_right_frame", (12.5, 1.6, 13.35), (13.6, 9.8, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_access_louver_top_frame", (2.6, 8.9, 13.35), (13.4, 10.1, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_access_louver_bottom_frame", (2.6, 1.5, 13.35), (13.4, 2.7, 15.7), "frame", face_textures={"south": "edge"})
    for index in range(4):
        y0 = 2.9 + index * 1.35
        add(upper, f"rear_access_louver_slat_{index}", (3.5, y0, 14.65), (12.5, y0 + 0.52, 15.75), "edge", face_textures={"south": "vent"}, shade=False)
    add(upper, "rear_access_roof_spine", (6.8, 11.8, 1.0), (9.2, 13.2, 14.8), "frame", face_textures={"up": "edge"})
    for x, y in ((2.9, 2.1), (12.55, 2.1), (2.9, 9.1), (12.55, 9.1)):
        rivet(upper, "rear_access_louver_rivet", x, y, 15.25)


def build_rear_power_section() -> None:
    lower = (1, 1, 0)
    add(lower, "rear_power_engine_bay_shell", (1.5, 3.0, 0.8), (14.5, 15.0, 14.3), "dark", face_textures={"south": "panel", "up": "edge"})
    add(lower, "rear_power_crankcase", (2.3, 3.6, 1.0), (13.7, 8.4, 13.9), "base", face_textures={"east": "panel", "west": "panel"})
    for x0 in (3.0, 11.0):
        add(lower, "rear_power_depth_pipe", (x0, 8.0, 0.2), (x0 + 2.0, 10.0, 14.8), "exhaust", face_textures={"up": "edge"})
        for z0 in (1.0, 7.1, 13.0):
            add(lower, "rear_power_pipe_collar", (x0 - 0.3, 7.7, z0), (x0 + 2.3, 10.3, z0 + 0.8), "edge")
    add(lower, "rear_power_service_recess", (3.0, 4.2, 13.8), (13.0, 12.4, 15.2), "exhaust")
    add(lower, "rear_power_service_panel", (3.6, 4.8, 14.75), (12.4, 11.8, 15.65), "panel")
    add(lower, "rear_power_panel_top_frame", (3.2, 11.5, 14.3), (12.8, 12.6, 15.8), "edge")
    add(lower, "rear_power_panel_bottom_frame", (3.2, 4.3, 14.3), (12.8, 5.4, 15.8), "edge")
    add(lower, "rear_power_panel_left_frame", (3.1, 5.0, 14.3), (4.2, 11.8, 15.8), "edge")
    add(lower, "rear_power_panel_right_frame", (11.8, 5.0, 14.3), (12.9, 11.8, 15.8), "edge")
    add(lower, "rear_power_warning_plate", (5.7, 2.1, 15.1), (10.3, 3.5, 15.75), "warning", shade=False)
    for x, y in ((3.45, 4.75), (12.05, 4.75), (3.45, 11.45), (12.05, 11.45)):
        rivet(lower, "rear_power_panel_rivet", x, y, 15.25)

    upper = (1, 1, 1)
    add(upper, "rear_power_cooling_shroud", (1.5, 0, 0.7), (14.5, 12.8, 14.5), "dark", face_textures={"south": "panel", "up": "panel"})
    add(upper, "rear_power_radiator_recess", (2.2, 1.5, 13.65), (13.8, 10.5, 15.25), "exhaust")
    add(upper, "rear_power_radiator_left_frame", (1.8, 1.1, 13.3), (3.0, 11.1, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_power_radiator_right_frame", (13.0, 1.1, 13.3), (14.2, 11.1, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_power_radiator_top_frame", (2.0, 10.2, 13.3), (14.0, 11.5, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_power_radiator_bottom_frame", (2.0, 1.0, 13.3), (14.0, 2.3, 15.7), "frame", face_textures={"south": "edge"})
    for index in range(5):
        y0 = 2.45 + index * 1.45
        add(upper, f"rear_power_radiator_louver_{index}", (3.0, y0, 14.6), (13.0, y0 + 0.58, 15.75), "edge", face_textures={"south": "vent"}, shade=False)
    for x in (6.3, 9.45):
        add(upper, "rear_power_radiator_separator", (x, 2.0, 14.3), (x + 0.55, 10.4, 15.8), "frame")
    add(upper, "rear_power_roof_cowl", (2.2, 11.0, 1.0), (13.8, 14.3, 14.8), "panel", face_textures={"up": "edge"})
    add(upper, "rear_power_roof_left_rib", (2.0, 12.0, 1.2), (3.2, 14.7, 14.4), "frame", face_textures={"up": "edge"})
    add(upper, "rear_power_roof_right_rib", (12.8, 12.0, 1.2), (14.0, 14.7, 14.4), "frame", face_textures={"up": "edge"})
    for x, y in ((2.2, 1.6), (13.25, 1.6), (2.2, 10.4), (13.25, 10.4)):
        rivet(upper, "rear_power_radiator_rivet", x, y, 15.25)


def build_rear_exhaust_section() -> None:
    lower = (2, 1, 0)
    add(lower, "rear_exhaust_manifold_base", (1.5, 3.0, 0.6), (14.5, 7.0, 14.5), "dark", face_textures={"up": "edge", "south": "panel"})
    for x0 in (3.0, 10.5):
        add(lower, "rear_exhaust_depth_pipe", (x0, 6.5, 0.2), (x0 + 2.5, 9.0, 14.8), "exhaust", face_textures={"up": "edge"})
        for z0 in (1.0, 7.0, 13.0):
            add(lower, "rear_exhaust_pipe_collar", (x0 - 0.35, 6.15, z0), (x0 + 2.85, 9.35, z0 + 0.85), "edge")
    add(lower, "rear_exhaust_maintenance_box", (2.0, 7.0, 10.8), (14.0, 14.2, 14.6), "dark", face_textures={"south": "panel", "up": "edge"})
    add(lower, "rear_exhaust_maintenance_recess", (3.0, 8.0, 14.0), (13.0, 12.8, 15.25), "exhaust")
    add(lower, "rear_exhaust_maintenance_door", (3.6, 8.5, 14.8), (12.4, 12.2, 15.65), "panel")
    add(lower, "rear_exhaust_door_top_frame", (3.2, 11.9, 14.35), (12.8, 13.0, 15.8), "edge")
    add(lower, "rear_exhaust_door_bottom_frame", (3.2, 7.9, 14.35), (12.8, 9.0, 15.8), "edge")
    add(lower, "rear_exhaust_door_left_frame", (3.1, 8.5, 14.35), (4.2, 12.2, 15.8), "edge")
    add(lower, "rear_exhaust_door_right_frame", (11.8, 8.5, 14.35), (12.9, 12.2, 15.8), "edge")
    add(lower, "rear_exhaust_side_service_panel", (14.15, 4.2, 3.2), (15.55, 12.2, 12.8), "panel", face_textures={"east": "control"})
    add(lower, "rear_exhaust_side_service_handle", (15.4, 6.4, 9.5), (15.9, 9.0, 10.4), "edge")
    for x, y in ((3.45, 8.3), (12.05, 8.3), (3.45, 11.85), (12.05, 11.85)):
        rivet(lower, "rear_exhaust_door_rivet", x, y, 15.25)

    upper = (2, 1, 1)
    add(upper, "rear_exhaust_upper_pedestal", (1.4, 0, 0.5), (14.6, 4.0, 14.8), "base", face_textures={"up": "edge", "south": "panel"})
    for x0 in (3.0, 10.0):
        add(upper, "rear_exhaust_riser", (x0, 3.8, 0.4), (x0 + 3.0, 12.8, 14.7), "exhaust", face_textures={"east": "dark", "west": "dark"})
        for z0 in (1.0, 7.0, 13.0):
            add(upper, "rear_exhaust_riser_collar", (x0 - 0.35, 5.5, z0), (x0 + 3.35, 8.0, z0 + 0.85), "edge")
    add(upper, "rear_exhaust_riser_top_bridge", (2.5, 11.8, 1.0), (13.5, 14.2, 14.6), "frame", face_textures={"up": "edge"})
    add(upper, "rear_exhaust_end_housing", (2.2, 4.2, 11.4), (13.8, 11.8, 14.8), "dark", face_textures={"south": "panel", "up": "edge"})
    add(upper, "rear_exhaust_louver_recess", (3.0, 5.1, 14.1), (13.0, 10.8, 15.25), "exhaust")
    add(upper, "rear_exhaust_louver_left_frame", (2.6, 4.7, 13.75), (3.7, 11.4, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_exhaust_louver_right_frame", (12.3, 4.7, 13.75), (13.4, 11.4, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_exhaust_louver_top_frame", (2.8, 10.5, 13.75), (13.2, 11.7, 15.7), "frame", face_textures={"south": "edge"})
    add(upper, "rear_exhaust_louver_bottom_frame", (2.8, 4.6, 13.75), (13.2, 5.8, 15.7), "frame", face_textures={"south": "edge"})
    for index in range(3):
        y0 = 6.0 + index * 1.45
        add(upper, f"rear_exhaust_louver_slat_{index}", (3.7, y0, 14.65), (12.3, y0 + 0.58, 15.75), "edge", face_textures={"south": "vent"}, shade=False)
    add(upper, "rear_exhaust_side_duct_guard", (14.15, 4.0, 3.0), (15.55, 11.8, 12.8), "frame", face_textures={"east": "panel"})
    add(upper, "rear_exhaust_side_warning", (15.35, 6.2, 5.0), (15.85, 8.1, 10.8), "warning", shade=False)
    for x, y in ((2.9, 5.1), (12.55, 5.1), (2.9, 10.75), (12.55, 10.75)):
        rivet(upper, "rear_exhaust_louver_rivet", x, y, 15.25)


def build_geometry() -> None:
    for elements in PARTS.values():
        elements.clear()
    for x_index in range(3):
        add_common_lower_frame(x_index)
        add_common_upper_frame(x_index)
        add_rear_lower_frame(x_index)
        add_rear_upper_frame(x_index)
    build_left_access_section()
    build_center_power_section()
    build_right_control_section()
    build_rear_access_section()
    build_rear_power_section()
    build_rear_exhaust_section()


def clean_number(value: float) -> int | float:
    rounded = round(value, 4)
    if rounded.is_integer():
        return int(rounded)
    return rounded


def element_json(element: Element) -> dict[str, object]:
    faces: dict[str, dict[str, object]] = {}
    for face in FACE_NAMES:
        texture = element.face_textures.get(face, element.texture)
        faces[face] = {"uv": [0, 0, 16, 16], "texture": f"#{texture}"}
    output: dict[str, object] = {
        "from": [clean_number(value) for value in element.start],
        "to": [clean_number(value) for value in element.end],
        "faces": faces,
    }
    if element.rotation is not None:
        output["rotation"] = {
            "origin": [clean_number(value) for value in element.rotation["origin"]],
            "axis": element.rotation["axis"],
            "angle": clean_number(float(element.rotation["angle"])),
            "rescale": False,
        }
    if not element.shade:
        output["shade"] = False
    return output


def write_models() -> list[Path]:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for old_path in MODEL_DIR.glob("part_x*_y*.json"):
        old_path.unlink()
    paths: list[Path] = []
    for x_index, z_index, y_index in PART_KEYS:
        path = MODEL_DIR / f"part_x{x_index}_z{z_index}_y{y_index}.json"
        payload = {
            "parent": "minecraft:block/block",
            "ambientocclusion": True,
            "textures": {**TEXTURES, "particle": TEXTURES["base"]},
            "elements": [element_json(element) for element in PARTS[(x_index, z_index, y_index)]],
        }
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        paths.append(path)
    return paths


def noisy_fill(image: Image.Image, palette: tuple[tuple[int, int, int, int], ...], seed: int) -> None:
    randomizer = random.Random(seed)
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            selector = (x * 3 + y * 5 + randomizer.randrange(4)) % len(palette)
            pixels[x, y] = palette[selector]


def make_texture(name: str) -> Image.Image:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image)
    palettes = {
        "base_steel": ((50, 51, 50, 255), (55, 56, 55, 255), (45, 46, 46, 255), (61, 61, 59, 255)),
        "dark_steel": ((34, 35, 34, 255), (39, 40, 39, 255), (29, 30, 30, 255), (45, 45, 43, 255)),
        "panel_steel": ((67, 68, 66, 255), (74, 74, 71, 255), (59, 60, 59, 255), (81, 80, 76, 255)),
        "frame_black": ((20, 21, 21, 255), (25, 26, 25, 255), (15, 16, 16, 255), (31, 31, 29, 255)),
        "edge_steel": ((92, 91, 86, 255), (106, 104, 98, 255), (77, 78, 76, 255), (119, 116, 108, 255)),
        "exhaust_soot": ((13, 14, 14, 255), (19, 20, 19, 255), (9, 10, 10, 255), (27, 27, 25, 255)),
        "rivet_steel": ((82, 82, 78, 255), (108, 106, 98, 255), (56, 57, 56, 255), (130, 126, 115, 255)),
    }
    if name in palettes:
        noisy_fill(image, palettes[name], sum(ord(char) for char in name))
        draw.line((0, 0, 15, 0), fill=(130, 128, 119, 110))
        draw.line((0, 15, 15, 15), fill=(8, 9, 9, 150))
        draw.line((0, 0, 0, 15), fill=(112, 110, 103, 80))
        draw.line((15, 0, 15, 15), fill=(7, 8, 8, 140))
    elif name == "vent_dark":
        image.paste((14, 15, 15, 255), (0, 0, 16, 16))
        for y in range(1, 16, 4):
            draw.rectangle((1, y, 14, y + 1), fill=(78, 78, 73, 255))
            draw.line((2, y + 2, 13, y + 2), fill=(3, 4, 4, 255))
    elif name == "warning_stripe":
        image.paste((28, 27, 23, 255), (0, 0, 16, 16))
        yellow = (196, 143, 28, 255)
        gold = (226, 171, 39, 255)
        for x in range(-16, 24, 8):
            draw.polygon(((x, 15), (x + 4, 15), (x + 16, 0), (x + 12, 0)), fill=yellow)
            draw.line((x + 4, 15, x + 16, 0), fill=gold)
    elif name == "control_panel":
        image.paste((35, 39, 39, 255), (0, 0, 16, 16))
        draw.rectangle((1, 1, 14, 14), outline=(104, 103, 96, 255), width=1)
        draw.rectangle((3, 3, 9, 7), fill=(10, 15, 15, 255), outline=(75, 80, 77, 255))
        draw.rectangle((11, 3, 13, 5), fill=(115, 119, 108, 255))
        draw.rectangle((11, 7, 13, 9), fill=(65, 68, 65, 255))
        draw.rectangle((3, 10, 5, 12), fill=(151, 48, 31, 255))
        draw.rectangle((7, 10, 9, 12), fill=(72, 79, 73, 255))
    elif name == "indicator_red":
        noisy_fill(image, ((145, 36, 25, 255), (190, 50, 31, 255), (111, 25, 20, 255), (222, 67, 38, 255)), 917)
        draw.rectangle((0, 0, 15, 15), outline=(55, 16, 13, 255), width=2)
        draw.rectangle((3, 3, 8, 8), fill=(239, 88, 51, 255))
    else:
        raise ValueError(f"No texture recipe for {name}")
    return image


def write_textures() -> list[Path]:
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for resource_path in TEXTURES.values():
        name = resource_path.rsplit("/", 1)[-1]
        path = TEXTURE_DIR / f"{name}.png"
        make_texture(name).save(path)
        paths.append(path)
    return paths


def rotate_point(point: tuple[float, float, float], rotate: dict[str, object] | None) -> tuple[float, float, float]:
    if rotate is None:
        return point
    ox, oy, oz = rotate["origin"]
    x, y, z = point[0] - ox, point[1] - oy, point[2] - oz
    angle = math.radians(float(rotate["angle"]))
    sine, cosine = math.sin(angle), math.cos(angle)
    axis = rotate["axis"]
    if axis == "x":
        y, z = y * cosine - z * sine, y * sine + z * cosine
    elif axis == "y":
        x, z = x * cosine + z * sine, -x * sine + z * cosine
    elif axis == "z":
        x, y = x * cosine - y * sine, x * sine + y * cosine
    else:
        raise ValueError(f"Unsupported axis {axis}")
    return (x + ox, y + oy, z + oz)


def element_vertices(element: Element, offset: tuple[float, float, float]) -> list[tuple[float, float, float]]:
    x0, y0, z0 = element.start
    x1, y1, z1 = element.end
    raw = [
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    ]
    return [
        tuple(value + delta for value, delta in zip(rotate_point(point, element.rotation), offset))
        for point in raw
    ]


def dot(left: tuple[float, float, float], right: tuple[float, float, float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def subtract(left: tuple[float, float, float], right: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(a - b for a, b in zip(left, right))


def cross(left: tuple[float, float, float], right: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    )


def normalize(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    length = math.sqrt(dot(vector, vector))
    return tuple(value / length for value in vector)


PREVIEW_COLORS = {
    "base": (49, 51, 51),
    "dark": (35, 37, 37),
    "panel": (67, 69, 68),
    "frame": (19, 21, 21),
    "edge": (106, 105, 99),
    "vent": (16, 18, 18),
    "warning": (201, 146, 28),
    "control": (42, 47, 47),
    "indicator": (211, 55, 34),
    "exhaust": (12, 14, 14),
    "rivet": (121, 119, 110),
}


def shaded(color: tuple[int, int, int], factor: float) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, round(channel * factor))) for channel in color) + (255,)


def polygon_centroid(points: list[tuple[float, float, float]]) -> tuple[float, float, float]:
    count = len(points)
    return tuple(sum(point[index] for point in points) / count for index in range(3))


def draw_warning_face(canvas: Image.Image, polygon: list[tuple[int, int]], base_fill: tuple[int, int, int, int]) -> None:
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).polygon(polygon, fill=255)
    layer = Image.new("RGBA", canvas.size, base_fill)
    layer_draw = ImageDraw.Draw(layer)
    left = min(point[0] for point in polygon) - 80
    right = max(point[0] for point in polygon) + 80
    top = min(point[1] for point in polygon) - 80
    bottom = max(point[1] for point in polygon) + 80
    for x in range(left - 200, right + 200, 34):
        layer_draw.polygon(((x, bottom), (x + 16, bottom), (x + 140, top), (x + 124, top)), fill=(24, 25, 23, 255))
    canvas.alpha_composite(Image.composite(layer, Image.new("RGBA", canvas.size), mask))


def render_preview() -> None:
    scale = 3
    width, height = 1500 * scale, 960 * scale
    canvas = Image.new("RGBA", (width, height), (225, 226, 224, 255))
    draw = ImageDraw.Draw(canvas, "RGBA")
    for y in range(height):
        shade_value = round(233 - 28 * (y / height))
        draw.line((0, y, width, y), fill=(shade_value, shade_value, shade_value - 2, 255))

    camera = (94.0, 62.0, -108.0)
    target = (24.0, 14.0, 16.0)
    forward = normalize(subtract(target, camera))
    screen_right = normalize(cross(forward, (0.0, 1.0, 0.0)))
    screen_up = normalize(cross(screen_right, forward))
    zoom = 16.6 * scale
    center_x, center_y = 750 * scale, 548 * scale

    def project(point: tuple[float, float, float]) -> tuple[int, int]:
        relative = subtract(point, target)
        return (
            round(center_x - dot(relative, screen_right) * zoom),
            round(center_y - dot(relative, screen_up) * zoom),
        )

    floor_points = [(-4.0, -0.05, -4.0), (52.0, -0.05, -4.0), (52.0, -0.05, 36.0), (-4.0, -0.05, 36.0)]
    floor_polygon = [project(point) for point in floor_points]
    draw.polygon(floor_polygon, fill=(178, 180, 178, 110))
    draw.line(floor_polygon + [floor_polygon[0]], fill=(125, 127, 124, 90), width=2 * scale)
    depth_seam = [project((-4.0, -0.04, 16.0)), project((52.0, -0.04, 16.0))]
    draw.line(depth_seam, fill=(177, 133, 33, 125), width=2 * scale)

    faces = {
        "north": (0, 3, 2, 1),
        "south": (4, 5, 6, 7),
        "west": (0, 4, 7, 3),
        "east": (1, 2, 6, 5),
        "up": (3, 7, 6, 2),
        "down": (0, 1, 5, 4),
    }
    light = normalize((-0.35, 0.82, -0.55))
    render_faces: list[tuple[float, list[tuple[int, int]], str, float, str]] = []
    for (x_index, z_index, y_index), elements in PARTS.items():
        offset = (x_index * 16.0, y_index * 16.0, z_index * 16.0)
        for element in elements:
            vertices = element_vertices(element, offset)
            for face_name, indices in faces.items():
                points = [vertices[index] for index in indices]
                normal = normalize(cross(subtract(points[1], points[0]), subtract(points[2], points[0])))
                face_center = polygon_centroid(points)
                if dot(normal, subtract(camera, face_center)) <= 0:
                    continue
                depth = dot(subtract(face_center, camera), forward)
                lighting = 0.62 + max(0.0, dot(normal, light)) * 0.43
                texture = element.face_textures.get(face_name, element.texture)
                render_faces.append((depth, [project(point) for point in points], texture, lighting, element.name))

    render_faces.sort(key=lambda item: item[0], reverse=True)
    for _, polygon, texture, lighting, _ in render_faces:
        fill = shaded(PREVIEW_COLORS[texture], lighting)
        if texture == "warning":
            draw_warning_face(canvas, polygon, fill)
        else:
            draw.polygon(polygon, fill=fill)
        outline = (8, 9, 9, 215) if texture != "rivet" else (38, 39, 38, 210)
        draw.line(polygon + [polygon[0]], fill=outline, width=max(2, scale))

    draw = ImageDraw.Draw(canvas, "RGBA")
    try:
        title_font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 34 * scale)
        subtitle_font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 18 * scale)
        label_font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 16 * scale)
    except OSError:
        title_font = subtitle_font = label_font = ImageFont.load_default()
    draw.text((52 * scale, 40 * scale), "INDUSTRIAL GENERATOR", font=title_font, fill=(33, 35, 35, 255))
    draw.text((55 * scale, 88 * scale), "3 x 2 x 2 MULTIBLOCK MODEL  |  NORTH-FACING ASSEMBLY", font=subtitle_font, fill=(75, 77, 75, 255))
    labels = ((8.0, "ACCESS"), (24.0, "POWER CORE"), (40.0, "CONTROL / EXHAUST"))
    for x_position, label in labels:
        anchor = project((x_position, 0.0, -1.1))
        text_box = draw.textbbox((0, 0), label, font=label_font)
        label_width = text_box[2] - text_box[0]
        draw.rounded_rectangle((anchor[0] - label_width // 2 - 11 * scale, anchor[1] + 10 * scale, anchor[0] + label_width // 2 + 11 * scale, anchor[1] + 37 * scale), radius=5 * scale, fill=(26, 28, 28, 218))
        draw.text((anchor[0] - label_width // 2, anchor[1] + 13 * scale), label, font=label_font, fill=(219, 190, 107, 255))
    depth_labels = ((8.0, "FRONT ROW"), (24.0, "REAR ROW"))
    for z_position, label in depth_labels:
        anchor = project((50.4, 0.0, z_position))
        text_box = draw.textbbox((0, 0), label, font=label_font)
        label_width = text_box[2] - text_box[0]
        draw.rounded_rectangle((anchor[0] + 8 * scale, anchor[1] - 13 * scale, anchor[0] + label_width + 27 * scale, anchor[1] + 14 * scale), radius=5 * scale, fill=(26, 28, 28, 218))
        draw.text((anchor[0] + 17 * scale, anchor[1] - 10 * scale), label, font=label_font, fill=(219, 190, 107, 255))
    canvas = canvas.resize((1500, 960), Image.Resampling.LANCZOS).convert("RGB")
    canvas.save(PREVIEW_PATH, quality=95)


def validate_models(model_paths: list[Path], texture_paths: list[Path]) -> dict[str, object]:
    errors: list[str] = []
    referenced_textures: set[str] = set()
    actual_bounds = [[math.inf, -math.inf] for _ in range(3)]
    feature_counts = {feature: 0 for feature in ("frame", "sloped", "louver", "control", "exhaust", "rivet", "warning")}
    rear_feature_counts = {feature: 0 for feature in ("depth_pipe", "maintenance", "power", "exhaust", "access", "roof")}
    part_elements: dict[str, int] = {}
    element_total = 0

    expected_model_names = {f"part_x{x}_z{z}_y{y}.json" for x, z, y in PART_KEYS}
    actual_model_names = {path.name for path in model_paths}
    if actual_model_names != expected_model_names:
        errors.append(f"Model filenames {sorted(actual_model_names)}, expected {sorted(expected_model_names)}")
    if len(model_paths) != 12:
        errors.append(f"Expected 12 model parts, received {len(model_paths)}")

    for (x_index, z_index, y_index), path in zip(PART_KEYS, model_paths):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            errors.append(f"{path.name}: invalid JSON: {exc}")
            continue
        if not payload.get("elements"):
            errors.append(f"{path.name}: no elements")
        texture_keys = set(payload.get("textures", {}))
        for output_element in payload.get("elements", []):
            for coordinate_name in ("from", "to"):
                coordinates = output_element[coordinate_name]
                if any(value < 0 or value > 16 for value in coordinates):
                    errors.append(f"{path.name}: {coordinate_name} outside 0..16: {coordinates}")
            if any(start >= end for start, end in zip(output_element["from"], output_element["to"])):
                errors.append(f"{path.name}: non-positive element dimensions")
            if "rotation" in output_element:
                rotate = output_element["rotation"]
                if rotate["axis"] not in ("x", "y", "z"):
                    errors.append(f"{path.name}: invalid rotation axis {rotate['axis']}")
                if float(rotate["angle"]) not in ALLOWED_ANGLES:
                    errors.append(f"{path.name}: invalid rotation angle {rotate['angle']}")
                if any(value < 0 or value > 16 for value in rotate["origin"]):
                    errors.append(f"{path.name}: rotation origin outside 0..16: {rotate['origin']}")
            for face in output_element["faces"].values():
                reference = face["texture"]
                if not reference.startswith("#") or reference[1:] not in texture_keys:
                    errors.append(f"{path.name}: unresolved texture reference {reference}")
                else:
                    referenced_textures.add(payload["textures"][reference[1:]])

        offset = (x_index * 16.0, y_index * 16.0, z_index * 16.0)
        elements = PARTS[(x_index, z_index, y_index)]
        part_elements[path.stem] = len(elements)
        for element in elements:
            element_total += 1
            lowered_name = element.name.lower()
            for feature in feature_counts:
                if feature in lowered_name or (feature == "warning" and "warning" in element.face_textures.values()):
                    feature_counts[feature] += 1
            for feature in rear_feature_counts:
                if lowered_name.startswith("rear_") and feature in lowered_name:
                    rear_feature_counts[feature] += 1
            for point in element_vertices(element, (0.0, 0.0, 0.0)):
                if any(value < -1e-6 or value > 16 + 1e-6 for value in point):
                    errors.append(f"{path.name}: rotated geometry exceeds local 0..16 bounds at {point}")
            for point in element_vertices(element, offset):
                for axis in range(3):
                    actual_bounds[axis][0] = min(actual_bounds[axis][0], point[axis])
                    actual_bounds[axis][1] = max(actual_bounds[axis][1], point[axis])
                    limits = ((0, 48), (0, 32), (0, 32))[axis]
                    if point[axis] < limits[0] - 1e-6 or point[axis] > limits[1] + 1e-6:
                        errors.append(f"{path.name}: rotated geometry exceeds assembly bounds at {point}")

    texture_path_set = {path.resolve() for path in texture_paths}
    for resource in referenced_textures:
        if not resource.startswith("miningdim:block/generator/industrial/"):
            errors.append(f"Texture outside industrial namespace: {resource}")
            continue
        filename = resource.rsplit("/", 1)[-1] + ".png"
        path = (TEXTURE_DIR / filename).resolve()
        if path not in texture_path_set or not path.is_file():
            errors.append(f"Missing texture: {resource}")
    for path in texture_paths:
        with Image.open(path) as image:
            if image.size != (16, 16) or image.mode != "RGBA":
                errors.append(f"{path.name}: expected 16x16 RGBA, received {image.size} {image.mode}")
    if any("gunsmith" in path.read_text(encoding="utf-8").lower() for path in model_paths):
        errors.append("Generated model contains a gunsmith texture reference")
    expected_bounds = ((0.0, 48.0), (0.0, 32.0), (0.0, 32.0))
    for axis, expected in enumerate(expected_bounds):
        actual = tuple(round(value, 5) for value in actual_bounds[axis])
        if actual != expected:
            errors.append(f"Assembly axis {axis} bounds {actual}, expected {expected}")
    for feature, count in feature_counts.items():
        if count == 0:
            errors.append(f"Required visual feature missing: {feature}")
    for feature, count in rear_feature_counts.items():
        if count == 0:
            errors.append(f"Required rear-depth feature missing: {feature}")
    if not PREVIEW_PATH.is_file():
        errors.append("Preview image was not created")
    else:
        with Image.open(PREVIEW_PATH) as preview:
            if preview.size != (1500, 960):
                errors.append(f"Preview expected 1500x960, received {preview.size}")
    if errors:
        raise RuntimeError("Industrial generator validation failed:\n- " + "\n- ".join(dict.fromkeys(errors)))
    return {
        "models": len(model_paths),
        "textures": len(texture_paths),
        "elements": element_total,
        "part_elements": part_elements,
        "bounds": {"x": [0, 48], "y": [0, 32], "z": [0, 32]},
        "features": feature_counts,
        "rear_features": rear_feature_counts,
        "status": "PASS",
    }


def main() -> None:
    build_geometry()
    model_paths = write_models()
    texture_paths = write_textures()
    render_preview()
    summary = validate_models(model_paths, texture_paths)
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
