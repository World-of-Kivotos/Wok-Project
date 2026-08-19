from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/gui/power"

ATLAS_SIZE = 256
STANDARD_WIDTH = 218
STANDARD_HEIGHT = 222
CONTROLLER_HEIGHT = 176

TRANSPARENT = (0, 0, 0, 0)
VOID = (2, 5, 9, 255)
DEEP_SHADOW = (4, 10, 16, 255)
SLOT_SHADOW = (6, 15, 24, 255)
NAVY = (9, 24, 35, 255)
NAVY_RAISED = (14, 34, 48, 255)
NAVY_RECESS = (7, 20, 31, 255)
STEEL_DARK = (39, 52, 63, 255)
STEEL = (77, 96, 111, 255)
STEEL_MID = (112, 139, 154, 255)
STEEL_LIGHT = (166, 201, 220, 255)
WHITE = (235, 248, 255, 255)
BLUE = (62, 160, 222, 255)
CYAN = (139, 235, 255, 255)
TEAL = (49, 210, 180, 255)
GOLD = (224, 190, 104, 255)
AMBER = (240, 166, 62, 255)
RED = (238, 92, 112, 255)


def save(image: Image.Image, name: str) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT_DIR / f"{name}.png", optimize=True)


def chamfered_points(box: tuple[int, int, int, int], cut: int) -> list[tuple[int, int]]:
    x0, y0, x1, y1 = box
    return [
        (x0 + cut, y0),
        (x1 - cut, y0),
        (x1, y0 + cut),
        (x1, y1 - cut),
        (x1 - cut, y1),
        (x0 + cut, y1),
        (x0, y1 - cut),
        (x0, y0 + cut),
    ]


def chamfered_box(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], cut: int,
                  fill: tuple[int, int, int, int], outline: tuple[int, int, int, int]) -> None:
    points = chamfered_points(box, cut)
    draw.polygon(points, fill=fill)
    draw.line(points + [points[0]], fill=outline, width=1)


def draw_surface_grain(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], seed: int) -> None:
    """Low-contrast horizontal clusters keep broad panels metallic without competing with text."""
    x0, y0, x1, y1 = box
    if x1 - x0 < 24 or y1 - y0 < 10:
        return
    for y in range(y0 + 4, y1 - 2, 5):
        x = x0 + 6 + ((y * 11 + seed) % 19)
        while x < x1 - 7:
            length = 2 + ((x + y + seed) % 4)
            color = NAVY_RAISED if ((x + y + seed) // 3) % 2 == 0 else DEEP_SHADOW
            draw.line((x, y, min(x + length, x1 - 6), y), fill=color)
            x += 23


def draw_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
               accent: tuple[int, int, int, int] = BLUE) -> None:
    x0, y0, x1, y1 = box
    chamfered_box(draw, box, 3, NAVY_RECESS, STEEL)
    draw_surface_grain(draw, box, x0 + y0)
    draw.line((x0 + 4, y0 + 1, x1 - 4, y0 + 1), fill=STEEL_LIGHT)
    draw.line((x0 + 4, y0 + 2, x1 - 4, y0 + 2), fill=STEEL_DARK)
    draw.line((x0 + 4, y1 - 1, x1 - 4, y1 - 1), fill=STEEL_DARK)
    draw.point((x0 + 3, y0 + 3), fill=STEEL_MID)
    draw.point((x1 - 3, y0 + 3), fill=STEEL_MID)
    draw.line((x0 + 6, y1 - 3, x0 + 18, y1 - 3), fill=accent)
    draw.line((x1 - 18, y1 - 3, x1 - 6, y1 - 3), fill=accent)


def draw_slot(draw: ImageDraw.ImageDraw, x: int, y: int,
              accent: tuple[int, int, int, int] = BLUE) -> None:
    draw.rectangle((x, y, x + 17, y + 17), fill=SLOT_SHADOW, outline=STEEL)
    draw.line((x + 1, y + 1, x + 16, y + 1), fill=STEEL_LIGHT)
    draw.line((x + 1, y + 1, x + 1, y + 16), fill=STEEL_LIGHT)
    draw.line((x + 3, y + 3, x + 14, y + 3), fill=NAVY_RAISED)
    draw.line((x + 3, y + 3, x + 3, y + 14), fill=NAVY_RAISED)
    draw.line((x + 4, y + 14, x + 14, y + 14), fill=DEEP_SHADOW)
    draw.line((x + 14, y + 4, x + 14, y + 14), fill=DEEP_SHADOW)
    draw.line((x + 2, y + 16, x + 16, y + 16), fill=STEEL_DARK)
    draw.line((x + 16, y + 2, x + 16, y + 16), fill=STEEL_DARK)
    draw.point((x + 2, y + 2), fill=WHITE)
    draw.point((x + 15, y + 15), fill=VOID)
    draw.line((x + 5, y + 15, x + 12, y + 15), fill=accent)


def draw_machine_socket(draw: ImageDraw.ImageDraw, x: int, y: int,
                        accent: tuple[int, int, int, int]) -> None:
    draw_panel(draw, (x - 6, y - 6, x + 23, y + 23), accent)
    draw_slot(draw, x, y, accent)
    for bolt_x, bolt_y in ((x - 3, y - 3), (x + 20, y - 3),
                           (x - 3, y + 20), (x + 20, y + 20)):
        draw.rectangle((bolt_x - 1, bolt_y - 1, bolt_x + 1, bolt_y + 1), fill=STEEL_DARK)
        draw.point((bolt_x, bolt_y), fill=WHITE)


def draw_meter_track(draw: ImageDraw.ImageDraw, x: int, y: int, width: int, height: int,
                     accent: tuple[int, int, int, int]) -> None:
    x1 = x + width - 1
    y1 = y + height - 1
    draw.rectangle((x, y, x1, y1), fill=DEEP_SHADOW, outline=STEEL)
    draw.rectangle((x + 1, y + 1, x1 - 1, y1 - 1), fill=VOID)
    draw.line((x + 2, y + 1, x1 - 2, y + 1), fill=STEEL_DARK)
    for tick_x in range(x + 9, x1 - 4, 12):
        draw.line((tick_x, y1 - 2, tick_x, y1 - 1), fill=STEEL_DARK)
    if height >= 12:
        draw.line((x + 3, y + 3, x1 - 3, y + 3), fill=NAVY_RAISED)
        draw.line((x + 3, y1 - 3, x1 - 3, y1 - 3), fill=DEEP_SHADOW)
    draw.rectangle((x + 2, y - 2, x + 5, y - 1), fill=accent)
    draw.point((x1 - 3, y - 1), fill=accent)


def draw_button_base(draw: ImageDraw.ImageDraw, x: int, y: int, width: int, height: int) -> None:
    x1 = x + width - 1
    y1 = y + height - 1
    chamfered_box(draw, (x, y, x1, y1), 2, NAVY_RECESS, STEEL)
    draw.line((x + 3, y + 1, x1 - 3, y + 1), fill=STEEL_LIGHT)
    draw.line((x + 3, y + 2, x1 - 3, y + 2), fill=STEEL_DARK)
    draw.line((x + 4, y1 - 2, x1 - 4, y1 - 2), fill=BLUE)


def draw_inventory(draw: ImageDraw.ImageDraw, origin_y: int, hotbar_y: int) -> None:
    for row in range(3):
        for col in range(9):
            draw_slot(draw, 28 + col * 18, origin_y + row * 18)
    for col in range(9):
        draw_slot(draw, 28 + col * 18, hotbar_y, CYAN)
    draw.line((25, hotbar_y - 4, 192, hotbar_y - 4), fill=STEEL)
    draw.line((27, hotbar_y - 5, 190, hotbar_y - 5), fill=DEEP_SHADOW)
    draw.line((29, hotbar_y - 3, 65, hotbar_y - 3), fill=BLUE)
    draw.line((155, hotbar_y - 3, 191, hotbar_y - 3), fill=BLUE)


def draw_main_frame(height: int, inventory_top: int, inventory_y: int,
                    hotbar_y: int) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), TRANSPARENT)
    draw = ImageDraw.Draw(image)
    visible_bottom = height - 1

    chamfered_box(draw, (0, 0, STANDARD_WIDTH - 1, visible_bottom), 5, VOID, STEEL_DARK)
    chamfered_box(draw, (2, 2, STANDARD_WIDTH - 3, visible_bottom - 2), 4, NAVY, STEEL)
    draw_surface_grain(draw, (3, 3, STANDARD_WIDTH - 4, visible_bottom - 3), height)
    draw.line((7, 4, STANDARD_WIDTH - 8, 4), fill=STEEL_LIGHT)
    draw.line((7, 5, STANDARD_WIDTH - 8, 5), fill=STEEL_DARK)
    draw.line((4, 8, 4, visible_bottom - 8), fill=STEEL_DARK)
    draw.line((STANDARD_WIDTH - 5, 8, STANDARD_WIDTH - 5, visible_bottom - 8), fill=DEEP_SHADOW)
    draw.line((7, visible_bottom - 4, STANDARD_WIDTH - 8, visible_bottom - 4), fill=DEEP_SHADOW)

    draw_panel(draw, (8, 7, STANDARD_WIDTH - 9, 20), CYAN)
    draw.line((16, 17, 58, 17), fill=BLUE)
    draw.line((STANDARD_WIDTH - 59, 17, STANDARD_WIDTH - 17, 17), fill=BLUE)
    for rivet_x in (12, STANDARD_WIDTH - 13):
        draw.rectangle((rivet_x - 1, 10, rivet_x + 1, 12), fill=STEEL_LIGHT)
        draw.point((rivet_x, 11), fill=VOID)

    for corner_x, corner_y in ((7, 6), (STANDARD_WIDTH - 8, 6),
                               (7, visible_bottom - 7), (STANDARD_WIDTH - 8, visible_bottom - 7)):
        draw.rectangle((corner_x - 1, corner_y - 1, corner_x + 1, corner_y + 1), fill=STEEL_DARK)
        draw.point((corner_x, corner_y), fill=STEEL_LIGHT)

    draw_panel(draw, (8, inventory_top, STANDARD_WIDTH - 9, visible_bottom - 5), BLUE)
    draw_inventory(draw, inventory_y, hotbar_y)
    return image, draw


def draw_energy_node(draw: ImageDraw.ImageDraw, cx: int, cy: int,
                     accent: tuple[int, int, int, int]) -> None:
    draw.rectangle((cx - 4, cy - 4, cx + 4, cy + 4), fill=DEEP_SHADOW)
    draw.rectangle((cx - 3, cy - 3, cx + 3, cy + 3), fill=NAVY_RAISED, outline=accent)
    draw.rectangle((cx - 1, cy - 1, cx + 1, cy + 1), fill=CYAN)
    draw.point((cx, cy), fill=WHITE)


def build_generator() -> None:
    image, draw = draw_main_frame(STANDARD_HEIGHT, 134, 142, 200)
    draw_panel(draw, (12, 24, 205, 66), CYAN)
    draw_machine_socket(draw, 69, 37, CYAN)
    draw_machine_socket(draw, 133, 37, AMBER)
    draw.line((93, 45, 108, 45, 108, 37, 127, 37), fill=STEEL)
    draw.line((93, 47, 110, 47, 110, 39, 127, 39), fill=BLUE)
    draw_energy_node(draw, 109, 46, CYAN)

    draw_meter_track(draw, 20, 74, 178, 7, CYAN)
    draw_meter_track(draw, 20, 94, 178, 7, AMBER)
    draw_panel(draw, (12, 105, 205, 129), RED)
    for lamp_x, color in ((184, CYAN), (193, AMBER), (202, RED)):
        draw.rectangle((lamp_x - 2, 110, lamp_x + 1, 113), fill=DEEP_SHADOW, outline=STEEL)
        draw.point((lamp_x, 111), fill=color)
    save(image, "generator")


def build_preheat_generator() -> None:
    """煤炭/地热共用底图: 单燃料槽 + 温度/能量/燃烧三条量条。"""
    image, draw = draw_main_frame(STANDARD_HEIGHT, 134, 142, 200)
    draw_panel(draw, (12, 24, 205, 66), AMBER)
    draw_machine_socket(draw, 101, 36, AMBER)
    draw.line((95, 60, 123, 60), fill=STEEL)
    draw_energy_node(draw, 109, 60, CYAN)

    draw_meter_track(draw, 20, 74, 178, 7, RED)
    draw_meter_track(draw, 20, 94, 178, 7, CYAN)
    draw_meter_track(draw, 20, 114, 178, 7, AMBER)
    save(image, "preheat_generator")


def build_power_cell() -> None:
    """三级储电共用底图: 无槽位, 一条主容量表加进出两条细表。"""
    image, draw = draw_main_frame(STANDARD_HEIGHT, 134, 142, 200)
    draw_panel(draw, (12, 24, 205, 70), CYAN)
    draw_meter_track(draw, 20, 36, 178, 18, CYAN)
    draw_energy_node(draw, 30, 62, CYAN)
    draw_energy_node(draw, 188, 62, AMBER)

    draw_meter_track(draw, 20, 84, 178, 7, TEAL)
    draw_meter_track(draw, 20, 104, 178, 7, AMBER)
    draw_panel(draw, (12, 116, 205, 129), BLUE)
    save(image, "power_cell")


def build_metallurgic_purifier() -> None:
    image, draw = draw_main_frame(STANDARD_HEIGHT, 134, 142, 200)
    draw_panel(draw, (12, 24, 205, 65), TEAL)
    for slot_x, accent in ((51, BLUE), (101, GOLD), (151, TEAL)):
        draw_machine_socket(draw, slot_x, 36, accent)
    for arrow_x in (83, 133):
        draw.line((arrow_x - 5, 44, arrow_x + 4, 44), fill=STEEL_LIGHT)
        draw.polygon([(arrow_x + 1, 41), (arrow_x + 6, 44), (arrow_x + 1, 47)], fill=CYAN)

    draw_meter_track(draw, 20, 75, 178, 7, TEAL)
    draw_meter_track(draw, 20, 95, 178, 7, CYAN)
    draw_meter_track(draw, 20, 115, 178, 7, GOLD)
    save(image, "metallurgic_purifier")


def build_air_separation() -> None:
    image, draw = draw_main_frame(STANDARD_HEIGHT, 134, 142, 200)
    draw_panel(draw, (12, 24, 205, 88), CYAN)
    draw_button_base(draw, 20, 36, 84, 18)
    draw_button_base(draw, 114, 36, 84, 18)
    draw.line((62, 54, 62, 62, 103, 62), fill=BLUE)
    draw.line((156, 54, 156, 62, 116, 62), fill=STEEL_LIGHT)
    draw.line((109, 62, 109, 69), fill=CYAN)
    draw_machine_socket(draw, 101, 70, CYAN)

    draw_meter_track(draw, 20, 97, 178, 7, TEAL)
    draw_meter_track(draw, 20, 117, 178, 7, CYAN)
    save(image, "air_separation")


def build_low_temperature_controller() -> None:
    image, draw = draw_main_frame(CONTROLLER_HEIGHT, 86, 94, 152)
    draw_panel(draw, (12, 24, 205, 57), CYAN)
    draw_machine_socket(draw, 101, 35, CYAN)
    for offset in (-1, 1):
        draw.line((83, 43 + offset, 94, 43 + offset), fill=BLUE)
        draw.line((125, 43 + offset, 136, 43 + offset), fill=BLUE)
    draw.line((109, 29, 109, 34), fill=WHITE)
    draw.line((106, 31, 112, 31), fill=CYAN)

    draw_meter_track(draw, 20, 68, 178, 7, CYAN)
    draw.line((20, 79, 51, 79), fill=BLUE)
    draw.line((167, 79, 198, 79), fill=BLUE)
    save(image, "low_temperature_controller")


def main() -> None:
    build_generator()
    build_preheat_generator()
    build_power_cell()
    build_metallurgic_purifier()
    build_air_separation()
    build_low_temperature_controller()
    print("Built power machine GUI atlases.")


if __name__ == "__main__":
    main()
