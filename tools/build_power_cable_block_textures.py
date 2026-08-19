from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block"
SIZE = 32
BAND_TOP = 12
BAND_BOTTOM = 19
TRANSPARENT = (0, 0, 0, 0)


def color(value: str) -> tuple[int, int, int, int]:
    value = value.removeprefix("#")
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4)) + (255,)


@dataclass(frozen=True)
class CableStyle:
    jacket_shadow: tuple[int, int, int, int]
    jacket: tuple[int, int, int, int]
    jacket_light: tuple[int, int, int, int]
    conductor_shadow: tuple[int, int, int, int]
    conductor: tuple[int, int, int, int]
    conductor_light: tuple[int, int, int, int]
    clamp_shadow: tuple[int, int, int, int]
    clamp: tuple[int, int, int, int]
    clamp_light: tuple[int, int, int, int]
    motif: str = "plain"


STYLES = {
    "iron_energy_cable": CableStyle(
        color("1D2024"), color("343A40"), color("66717A"),
        color("44484C"), color("888D91"), color("C0C4C5"),
        color("3A2C29"), color("745043"), color("AD7560"), "worn"),
    "aluminum_energy_cable": CableStyle(
        color("222934"), color("3E4A59"), color("718398"),
        color("6D747C"), color("B8C1C8"), color("EDF3F5"),
        color("33434F"), color("668499"), color("A7C4D4")),
    "copper_energy_cable": CableStyle(
        color("241C1A"), color("49312B"), color("805040"),
        color("7B2F1F"), color("C45E36"), color("F2A06A"),
        color("3A3028"), color("7D6950"), color("BDA076")),
    "tinned_copper_energy_cable": CableStyle(
        color("25292D"), color("4A5157"), color("7F8990"),
        color("665E58"), color("B7A89F"), color("E4DBD4"),
        color("4C3A31"), color("8F674F"), color("C88E69"), "plated"),
    "ofc_copper_energy_cable": CableStyle(
        color("171719"), color("302A2B"), color("554548"),
        color("812B18"), color("D1602F"), color("FFAA64"),
        color("44302A"), color("865846"), color("C68A68"), "clean"),
    "ofe_copper_energy_cable": CableStyle(
        color("1E2630"), color("394A5B"), color("6F8499"),
        color("953B20"), color("E3793E"), color("FFC080"),
        color("394B58"), color("7694A5"), color("B9D7E3"), "clean"),
    "silver_plated_copper_energy_cable": CableStyle(
        color("202833"), color("3C5063"), color("7890A3"),
        color("747B82"), color("C6D0D7"), color("F7FCFF"),
        color("4A5966"), color("91A9B9"), color("D8EDF5"), "plated"),
    "gold_energy_cable": CableStyle(
        color("282519"), color("51492B"), color("8E7C3E"),
        color("8E5C14"), color("D9A32D"), color("FFE17A"),
        color("50472C"), color("98834B"), color("E3C46E"), "clean"),
    "silver_energy_cable": CableStyle(
        color("351B1C"), color("672D2D"), color("A94E45"),
        color("737A80"), color("C8D1D6"), color("FFFFFF"),
        color("49373B"), color("8E6666"), color("D4A09A"), "plated"),
    "graphene_energy_cable": CableStyle(
        color("120E12"), color("26212A"), color("4A3B48"),
        color("16171B"), color("3F434A"), color("858D98"),
        color("42171B"), color("7E2931"), color("C44C53"), "lattice"),
    "nbti_superconductor_energy_cable": CableStyle(
        color("0D1D35"), color("183B68"), color("376DA5"),
        color("376788"), color("82B6D0"), color("E4FBFF"),
        color("385D79"), color("79A9BD"), color("D8F5FA"), "frost"),
    "ybco_superconductor_energy_cable": CableStyle(
        color("07171B"), color("0D3039"), color("185566"),
        color("14232C"), color("315164"), color("79C8D7"),
        color("174856"), color("2692A4"), color("73EFF2"), "ceramic"),
    "tungsten_heat_resistant_wire": CableStyle(
        color("171419"), color("302A31"), color("5D535E"),
        color("3A363B"), color("716B70"), color("AAA4A8"),
        color("55261E"), color("9A4A2F"), color("E7894B"), "heat"),
}


def draw_motif(draw: ImageDraw.ImageDraw, style: CableStyle) -> None:
    if style.motif == "lattice":
        for x in range(4, SIZE, 6):
            draw.point((x, 15), fill=style.conductor_light)
            draw.point((x + 2, 16), fill=style.conductor_shadow)
    elif style.motif == "frost":
        for x in (5, 8, 13, 16, 21, 24, 29):
            draw.point((x, 13), fill=style.conductor_light)
            draw.point((x + 1, 14), fill=style.jacket_light)
    elif style.motif == "ceramic":
        for x in range(4, SIZE, 5):
            draw.point((x, 14), fill=style.clamp_light)
            draw.point((x + 1, 17), fill=style.jacket_shadow)
    elif style.motif == "heat":
        for x in (5, 14, 23, 30):
            draw.point((x, 14), fill=style.clamp)
            draw.point((x + 1, 17), fill=style.conductor_shadow)
    elif style.motif == "worn":
        for x in (6, 15, 23, 29):
            draw.point((x, 13), fill=style.jacket_shadow)
            draw.point((x + 1, 18), fill=style.jacket_light)
    elif style.motif == "plated":
        for x in (6, 14, 22, 30):
            draw.point((x, 15), fill=style.conductor_light)
    elif style.motif == "clean":
        for x in (6, 14, 22, 30):
            draw.point((x, 14), fill=style.jacket_light)


def build_texture(style: CableStyle) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)
    draw = ImageDraw.Draw(image)

    draw.rectangle((0, BAND_TOP, SIZE - 1, BAND_BOTTOM), fill=style.jacket_shadow)
    draw.line((0, 13, SIZE - 1, 13), fill=style.jacket_light)
    draw.line((0, 14, SIZE - 1, 14), fill=style.jacket)
    draw.line((0, 15, SIZE - 1, 15), fill=style.conductor_light)
    draw.line((0, 16, SIZE - 1, 16), fill=style.conductor)
    draw.line((0, 17, SIZE - 1, 17), fill=style.jacket)
    draw.line((0, 18, SIZE - 1, 18), fill=style.jacket_shadow)

    for x in (2, 10, 18, 26):
        draw.line((x, 12, x, 19), fill=style.clamp_shadow)
        draw.line((x + 1, 12, x + 1, 19), fill=style.clamp)
        draw.point((x + 1, 13), fill=style.clamp_light)
        draw.point((x + 1, 18), fill=style.clamp_shadow)

    draw_motif(draw, style)
    return image


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, style in STYLES.items():
        image = build_texture(style)
        image.save(OUTPUT_DIR / f"{name}.png", optimize=True)
    print(f"Built {len(STYLES)} power cable block textures at {SIZE}x{SIZE}.")


if __name__ == "__main__":
    main()
