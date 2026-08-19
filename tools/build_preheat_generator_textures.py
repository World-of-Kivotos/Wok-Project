"""生成预热发电机与三级储电单元的最终方块贴图。

贴图严格使用 16x16 Minecraft 硬像素语言。脚本在写盘前验证尺寸、颜色模式、
不透明度、纯白像素与工作状态差异，避免预览画布的白边进入游戏资源。
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "src/main/resources/assets/miningdim/textures/block"
SIZE = 16

Color = tuple[int, int, int, int]


def make_palette(spec: str) -> dict[str, Color]:
    result: dict[str, Color] = {}
    for entry in spec.split():
        symbol, raw = entry.split("=", maxsplit=1)
        value = raw.removeprefix("#")
        if len(value) != 6:
            raise ValueError(f"Invalid palette color {raw!r}")
        result[symbol] = (
            int(value[0:2], 16),
            int(value[2:4], 16),
            int(value[4:6], 16),
            255,
        )
    return result


def rows(value: str) -> tuple[str, ...]:
    return tuple(line.strip() for line in value.strip().splitlines())


COAL_PALETTE = make_palette(
    "K=070808 Q=131413 D=1D1E1E S=2D2E2E B=373837 M=4A4A47 "
    "L=5C5B56 H=706E67 P=827F77 R=4B3022 U=68452F W=232727 "
    "E=4F130D C=9B2912 O=D45516 A=F28E24 Y=FFD264"
)
COAL_ROWS = {
    "top": rows("""
        KKKKKKKKKKKKKKKK
        KHHHHHHHHHHHHHHK
        KHPBBBBBBBBBBPDK
        KHBSSSSSSSSSSBDK
        KHBRBBHHHHBBRBDK
        KHBBBHHLLSDBUBDK
        KHBBHLKKKKSDBBDK
        KHBRHMKQQKSDBBDK
        KHBBHMKQKKSDBRDK
        KHBBLMKKQKSDUBDK
        KHBRLMSSSSSDBBDK
        KHBBBLMMSSDBBBDK
        KHSSSSDDDDSSSSDK
        KHPBBBBRBBBBBPDK
        KDDDDDDDDDDDDDDK
        KKKKKKKKKKKKKKKK
    """),
    "side": rows("""
        KKKKKKKKKKKKKKKK
        KHHHHHHHHHHHHHHK
        KHPBBBBBBBBBBPDK
        KHHHHHHHHHHHHHDK
        KHSSSSSSSSSSSSDK
        KHBKHHHHHHHHKBDK
        KHRLMMMMMMMMDBDK
        KHBLKKKKKKKKDBDK
        KHBLMMMMMMMMDRDK
        KHBLKKKKKKKKDBDK
        KHULMMMMMMMMDBDK
        KHBKDDDDDDDDKBDK
        KHSSSSSSSSSSSSDK
        KHPBBBBRBBBBBPDK
        KDDDDDDDDDDDDDDK
        KKKKKKKKKKKKKKKK
    """),
    "front": rows("""
        KKKKKKKKKKKKKKKK
        KHHHHHHHHHHHHHHK
        KHPBBBBBBBBBBPDK
        KHBBBKKKKKKBBBDK
        KHBBKHHHHHHKBBDK
        KHBKHLLLLLLSKBDK
        KHBKHKHHHHKSKBDK
        KHRKPKWWWWKSKBDK
        KHBKLKQWWQKPKBDK
        KHBKLKWQQWKHKBDK
        KHUKPKQQQQKSKBDK
        KHBKLKDDDDKSKBDK
        KHBBKLSSSSSKBBDK
        KHPBBKKKKKKBBPDK
        KDDDDDDDDDDDDDDK
        KKKKKKKKKKKKKKKK
    """),
    "front_on": rows("""
        KKKKKKKKKKKKKKKK
        KHHHHHHHHHHHHHHK
        KHPBBBBBBBBBBPDK
        KHBBBKKKKKKBBBDK
        KHBBKHHHHHHKBBDK
        KHBKHLLLLLLSKBDK
        KHBKHKHHHHKSKBDK
        KHRKPKECCEKSKBDK
        KHBKLKCOACKPKBDK
        KHBKLKOAYOKHKBDK
        KHUKPKECOEKSKBDK
        KHBKLKDDDDKSKBDK
        KHBBKLSSSSSKBBDK
        KHPBBKKKKKKBBPDK
        KDDDDDDDDDDDDDDK
        KKKKKKKKKKKKKKKK
    """),
}

GEOTHERMAL_PALETTE = make_palette(
    "a=1B2632 b=32333D c=3A3B48 d=4F4B4F e=5C5C5C f=747474 "
    "g=160F10 h=27221C i=312C36 j=4E4B54 p=366451 q=3D7864 "
    "r=4C9484 s=6EC59F v=652828 w=CA4E06 x=E66410 z=FBAA59"
)
GEOTHERMAL_ROWS = {
    "top": rows("""
        deedddcddeddbcde
        edfdccddeeccddcd
        ceeddcdsrddfecdd
        ddcppppppppppcde
        bddprssssssrpcdb
        ccdpsddrrdeqpccd
        addpsdjjhhcqpdba
        bdspsrjgghqqppcb
        derpsrhgghqqpped
        ccdpsdhhhhdqpccd
        eddpscdqqdeqpdbc
        ddcprqqqqqqrpcde
        cdeppppqqppppbcd
        edccddeppbccedde
        deeddbcddedccded
        cddedccdeeddbcdd
    """),
    "side": rows("""
        cddeedddccdeeddd
        bchjjjjjjjjjjhdd
        abggggggggggggdd
        abcpsrrrrrrrpedd
        bccpsedpsccpsedd
        bcdpredprccprddd
        abcpredprccpredd
        abcgjedgjccgjddd
        bccpsedpsccpsedd
        bcdpredprccprddd
        abcpredprccpredd
        abcpqedpqccpqded
        bccpqqqqqqqqpddd
        bcggggggggggggdd
        abhiiiiiiiiiihdd
        bccddeddcbcdeddd
    """),
    "front": rows("""
        deedddcddeddbcde
        edfdccddeeccddcd
        ceeddcdsrddfecdd
        ddgggggppgggggde
        bdgijjjjjjjjigdb
        ccgjgjjjjjjghgcd
        adgjjabggabhhgba
        bdsrjchggchhrpcb
        depqjggiigghqped
        ccgjjabggabhhgcd
        edgjjchggchhhgbc
        ddgjghhhhhhghgde
        cdgihhhhhhhhigcd
        edggggggggggggde
        deeddbcddedccded
        cddedccdeeddbcdd
    """),
    "front_on": rows("""
        deedddcddeddbcde
        edfdccddeeccddcd
        ceeddcdsrddfecdd
        ddgggggppgggggde
        bdgijjjjjjjjigdb
        ccgjgjjjjjjghgcd
        adgjjvwggvwhhgba
        bdsrjxzggxzhrpcb
        depqjggiigghqped
        ccgjjvwggvwhhgcd
        edgjjxzggxzhhgbc
        ddgjghhhhhhghgde
        cdgihhhhhhhhigcd
        edggggggggggggde
        deeddbcddedccded
        cddedccdeeddbcdd
    """),
}

INDUSTRIAL_PALETTE = make_palette(
    "K0=0B0D0D K1=1D1E1E S0=2D2E2E S1=373837 S2=464744 S3=5C5B56 "
    "S4=706E67 S5=827F77 G0=101819 G1=1B292A G2=2C393A "
    "C0=0D4B53 C1=137C89 C2=29B9C5 C3=8DE5E6"
)


def industrial_shell() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    p = INDUSTRIAL_PALETTE
    image = Image.new("RGBA", (SIZE, SIZE), p["S2"])
    draw = ImageDraw.Draw(image)
    draw.rectangle((1, 0, 14, 0), fill=p["S5"])
    draw.rectangle((0, 1, 0, 14), fill=p["S4"])
    draw.rectangle((15, 1, 15, 14), fill=p["K1"])
    draw.rectangle((1, 15, 14, 15), fill=p["K0"])
    for point in ((0, 0), (15, 0), (0, 15), (15, 15)):
        draw.point(point, fill=p["K0"])
    draw.rectangle((1, 1, 14, 1), fill=p["S3"])
    draw.rectangle((1, 2, 1, 13), fill=p["S1"])
    draw.rectangle((14, 2, 14, 13), fill=p["S0"])
    draw.rectangle((2, 14, 13, 14), fill=p["S1"])
    for point in ((1, 1), (14, 1), (1, 14), (14, 14)):
        draw.point(point, fill=p["S5"])
    return image, draw


def draw_industrial_cell_2x2(draw: ImageDraw.ImageDraw, x: int, y: int) -> None:
    p = INDUSTRIAL_PALETTE
    draw.point((x, y), fill=p["S5"])
    draw.point((x + 1, y), fill=p["S3"])
    draw.point((x, y + 1), fill=p["S1"])
    draw.point((x + 1, y + 1), fill=p["K1"])


def industrial_top() -> Image.Image:
    p = INDUSTRIAL_PALETTE
    image, draw = industrial_shell()
    draw.rectangle((2, 2, 13, 13), fill=p["K0"])
    draw.rectangle((3, 3, 12, 12), fill=p["S1"])
    draw.rectangle((4, 3, 11, 3), fill=p["S4"])
    draw.rectangle((3, 4, 3, 11), fill=p["S3"])
    draw.rectangle((12, 4, 12, 11), fill=p["K1"])
    draw.rectangle((4, 12, 11, 12), fill=p["K1"])
    for point, tone in (
        ((5, 4), "S2"), ((9, 11), "S2"),
        ((10, 4), "S0"), ((5, 11), "S0"),
    ):
        draw.point(point, fill=p[tone])
    draw.rectangle((4, 7, 11, 7), fill=p["S4"])
    draw.rectangle((4, 8, 11, 8), fill=p["S0"])
    for cell_x, cell_y in ((4, 5), (7, 5), (10, 5), (4, 9), (7, 9), (10, 9)):
        draw_industrial_cell_2x2(draw, cell_x, cell_y)
    return image


def industrial_side() -> Image.Image:
    p = INDUSTRIAL_PALETTE
    image, draw = industrial_shell()
    draw.rectangle((2, 2, 13, 13), fill=p["K0"])
    draw.rectangle((3, 3, 12, 12), fill=p["S0"])
    draw.rectangle((4, 3, 11, 3), fill=p["S4"])
    draw.rectangle((3, 4, 3, 11), fill=p["S3"])
    draw.rectangle((12, 4, 12, 11), fill=p["K1"])
    draw.rectangle((4, 12, 11, 12), fill=p["K1"])
    draw.rectangle((7, 4, 7, 11), fill=p["S4"])
    draw.rectangle((8, 4, 8, 11), fill=p["K1"])
    for y in (6, 9):
        draw.rectangle((4, y, 6, y), fill=p["K1"])
        draw.rectangle((9, y, 11, y), fill=p["K1"])
    for cell_x, cell_y in ((4, 4), (9, 4), (4, 7), (9, 7), (4, 10), (9, 10)):
        draw.rectangle((cell_x, cell_y, cell_x + 2, cell_y), fill=p["S2"])
        draw.point((cell_x, cell_y), fill=p["S5"])
        draw.point((cell_x + 1, cell_y), fill=p["S3"])
        draw.rectangle((cell_x, cell_y + 1, cell_x + 2, cell_y + 1), fill=p["S2"])
        draw.point((cell_x, cell_y + 1), fill=p["S1"])
        draw.point((cell_x + 2, cell_y + 1), fill=p["K1"])
    return image


def industrial_front() -> tuple[Image.Image, Image.Image]:
    p = INDUSTRIAL_PALETTE
    off, draw = industrial_shell()
    draw.rectangle((2, 2, 13, 13), fill=p["K0"])
    draw.rectangle((3, 3, 12, 12), fill=p["S1"])
    draw.rectangle((4, 3, 11, 3), fill=p["S4"])
    draw.rectangle((3, 4, 3, 11), fill=p["S3"])
    draw.rectangle((12, 4, 12, 11), fill=p["K1"])
    draw.rectangle((4, 12, 11, 12), fill=p["K1"])
    for point in ((3, 3), (12, 3), (3, 9), (12, 9)):
        draw.point(point, fill=p["S5"])
    draw.rectangle((4, 4, 11, 8), fill=p["K0"])
    draw.rectangle((5, 4, 10, 4), fill=p["S5"])
    draw.rectangle((4, 5, 4, 7), fill=p["S4"])
    draw.rectangle((11, 5, 11, 7), fill=p["K1"])
    off_window = (
        ("G1", "G2", "G2", "G2", "G2", "G1"),
        ("G0", "G1", "G1", "G1", "G1", "G0"),
        ("G0", "G0", "G1", "G1", "G0", "G0"),
    )
    for y_offset, window_row in enumerate(off_window):
        for x_offset, tone in enumerate(window_row):
            draw.point((5 + x_offset, 5 + y_offset), fill=p[tone])
    draw.rectangle((3, 10, 12, 13), fill=p["K0"])
    draw.rectangle((4, 11, 11, 12), fill=p["S0"])
    draw.rectangle((4, 10, 11, 10), fill=p["S4"])
    draw.rectangle((3, 11, 3, 12), fill=p["S3"])
    draw.rectangle((12, 11, 12, 12), fill=p["K1"])
    for cell_x, cell_y in ((4, 11), (7, 11), (10, 11)):
        draw_industrial_cell_2x2(draw, cell_x, cell_y)

    on = off.copy()
    on_draw = ImageDraw.Draw(on)
    on_window = (
        ("C1", "C2", "C3", "C2", "C2", "C1"),
        ("C0", "C1", "C2", "C2", "C1", "C0"),
        ("C0", "C0", "C1", "C1", "C0", "C0"),
    )
    for y_offset, window_row in enumerate(on_window):
        for x_offset, tone in enumerate(window_row):
            on_draw.point((5 + x_offset, 5 + y_offset), fill=p[tone])
    return off, on


MODERN_PALETTE = make_palette(
    "0=070B0F 1=101820 2=1A2833 3=2B3B48 4=3D5262 5=587181 "
    "6=78919F 7=A6B8C0 8=101D24 9=2A3E46 A=07515A B=0D8E94 "
    "C=19C9C2 D=77EEE0"
)
MODERN_ROWS = {
    "top": rows("""
        0000000000000000
        0266666666666610
        0574444444444720
        0544426666244420
        0525253523125220
        0510251661120120
        0531551731151320
        0510251331120120
        0531553523151320
        0510253523120120
        0531553523151320
        0523253523123220
        0544411111144420
        0574444444444720
        0122222222222200
        0000000000000000
    """),
    "side": rows("""
        0000000000000000
        0266666666666610
        0574444444444720
        0553333333333120
        0551112111171120
        0554444444444120
        0522222222222220
        0526666666666220
        0553030303030120
        0553030303030120
        0555151515151120
        0553030303030120
        0553030303030120
        0571111111111720
        0122222222222200
        0000000000000000
    """),
    "front": rows("""
        0000000000000000
        0266666666666610
        0574533333314720
        0544666666664420
        0544522222214420
        0544528888014420
        0544528998014420
        0544528998014420
        0544528888014420
        0544500000014420
        0544111111114420
        0546666666666420
        0545101010101420
        0575313131311720
        0122222222222200
        0000000000000000
    """),
    "front_on": rows("""
        0000000000000000
        0266666666666610
        0574533333314720
        0544666666664420
        0544522222214420
        054452ABBA014420
        054452BCCB014420
        054452BCDA014420
        054452ABBA014420
        0544500000014420
        0544111111114420
        0546666666666420
        0545101010101420
        0575313131311720
        0122222222222200
        0000000000000000
    """),
}

FUTURE_PALETTE = make_palette(
    "X=070A10 K=11161C F=20272F B=292448 P=3A315F V=655185 S=66727C "
    "M=A4AEB4 L=CCD2D5 W=E1E5E7 G=111726 Q=281F3D R=18313B "
    "U=62419A Y=A878FF C=1384A1 T=48D9EE H=D4F7FA"
)
FUTURE_ROWS = {
    "top": rows("""
        XXXXXXXXXXXXXXXX
        XWLLMKKKKKKMLLWX
        XLMSFFFFFFFFSMLX
        XLSFBBBBBBBBFSLX
        XMKBBBBBBBBBBKMX
        XKFBKKKKKKKKBFKX
        XKFBKFVPPVFKBFKX
        XKFBKVPQQPVKBFKX
        XKFBKVPQQPVKBFKX
        XKFBKFVPPVFKBFKX
        XKFBKKKKKKKKBFKX
        XMKBBBBBBBBBBKMX
        XLSFBBBBBBBBFSLX
        XLMSFFFFFFFFSMLX
        XWLLMKKKKKKMLLWX
        XXXXXXXXXXXXXXXX
    """),
    "side": rows("""
        XXXXXXXXXXXXXXXX
        XWLLMKKKKKKMLLWX
        XLMSFFFFFFFFSMLX
        XMSFBBBBBBBBFSMX
        XMSFKKKKKKKKFSMX
        XMSFKVFFFFVKFSMX
        XMSFKKKKKKKKFSMX
        XMSFBBBBBBBBFSMX
        XMSFKKKKKKKKFSMX
        XMSFKVFFFFVKFSMX
        XMSFKKKKKKKKFSMX
        XMSFBBBBBBBBFSMX
        XMSFKPPFFPPKFSMX
        XLMSFFFFFFFFSMLX
        XWLLMKKKKKKMLLWX
        XXXXXXXXXXXXXXXX
    """),
    "front": rows("""
        XXXXXXXXXXXXXXXX
        XWLLMKKKKKKMLLWX
        XLMSFFFFFFFFSMLX
        XMSFBBBBBBBBFSMX
        XMSFKKKKKKKKFSMX
        XMLFKGRRRRGKFLMX
        XWMSKGQQQQGKSMWX
        XLMFKQQQQQQKFLMX
        XLMFKQQQQQQKFLMX
        XWMSKGQQQQGKSMWX
        XMLFKGRRRRGKFLMX
        XMSFKKKKKKKKFSMX
        XMSFBBBBBBBBFSMX
        XLMSFFFFFFFFSMLX
        XWLLMKKKKKKMLLWX
        XXXXXXXXXXXXXXXX
    """),
    "front_on": rows("""
        XXXXXXXXXXXXXXXX
        XWLLMKKKKKKMLLWX
        XLMSFFFFFFFFSMLX
        XMSFBBBBBBBBFSMX
        XMSFKKKKKKKKFSMX
        XMLFKCTTTTCKFLMX
        XWMSKCUYYUCKSMWX
        XLMFKUYHHYUKFLMX
        XLMFKUYHHYUKFLMX
        XWMSKCUYYUCKSMWX
        XMLFKCTTTTCKFLMX
        XMSFKKKKKKKKFSMX
        XMSFBBBBBBBBFSMX
        XLMSFFFFFFFFSMLX
        XWLLMKKKKKKMLLWX
        XXXXXXXXXXXXXXXX
    """),
}

MATRIX_FAMILIES = {
    "coal_generator": (COAL_PALETTE, COAL_ROWS),
    "geothermal_generator": (GEOTHERMAL_PALETTE, GEOTHERMAL_ROWS),
    "modern_power_cell": (MODERN_PALETTE, MODERN_ROWS),
    "future_power_cell": (FUTURE_PALETTE, FUTURE_ROWS),
}

STATE_MASKS = {
    "coal_generator": {(x, y) for y in range(7, 11) for x in range(6, 10)},
    "geothermal_generator": {(x, y) for y in (6, 7, 9, 10) for x in (5, 6, 9, 10)},
    "industrial_power_cell": {(x, y) for y in range(5, 8) for x in range(5, 11)},
    "modern_power_cell": {(x, y) for y in range(5, 9) for x in range(6, 10)},
    "future_power_cell": {(x, y) for y in range(5, 11) for x in range(5, 11)},
}


def matrix_image(
    asset_name: str,
    pixel_rows: tuple[str, ...],
    colors: dict[str, Color],
) -> Image.Image:
    if len(pixel_rows) != SIZE:
        raise ValueError(f"{asset_name} has {len(pixel_rows)} rows, expected {SIZE}")
    invalid_lengths = [
        (index, len(pixel_row))
        for index, pixel_row in enumerate(pixel_rows)
        if len(pixel_row) != SIZE
    ]
    if invalid_lengths:
        raise ValueError(f"{asset_name} has invalid row lengths: {invalid_lengths}")
    unknown = sorted(set("".join(pixel_rows)) - colors.keys())
    if unknown:
        raise ValueError(f"{asset_name} uses unknown palette symbols: {unknown}")

    image = Image.new("RGBA", (SIZE, SIZE))
    image.putdata([colors[symbol] for pixel_row in pixel_rows for symbol in pixel_row])
    return image


def build_assets() -> dict[str, Image.Image]:
    assets: dict[str, Image.Image] = {}
    for block_id, (colors, family_rows) in MATRIX_FAMILIES.items():
        for face in ("top", "side", "front", "front_on"):
            asset_name = f"{block_id}_{face}"
            assets[asset_name] = matrix_image(asset_name, family_rows[face], colors)

    industrial_front_off, industrial_front_on = industrial_front()
    assets["industrial_power_cell_top"] = industrial_top()
    assets["industrial_power_cell_side"] = industrial_side()
    assets["industrial_power_cell_front"] = industrial_front_off
    assets["industrial_power_cell_front_on"] = industrial_front_on
    return assets


def validate_image(asset_name: str, image: Image.Image) -> None:
    if image.size != (SIZE, SIZE):
        raise ValueError(f"{asset_name} has size {image.size}, expected {(SIZE, SIZE)}")
    if image.mode != "RGBA":
        raise ValueError(f"{asset_name} has mode {image.mode}, expected RGBA")

    pixels = [
        image.getpixel((x, y))
        for y in range(SIZE)
        for x in range(SIZE)
    ]
    non_opaque = [index for index, pixel in enumerate(pixels) if pixel[3] != 255]
    if non_opaque:
        raise ValueError(f"{asset_name} has non-opaque pixels at indices {non_opaque[:8]}")
    pure_white = [index for index, pixel in enumerate(pixels) if pixel[:3] == (255, 255, 255)]
    if pure_white:
        raise ValueError(f"{asset_name} contains #FFFFFF at indices {pure_white[:8]}")


def validate_state_pair(
    block_id: str,
    off: Image.Image,
    on: Image.Image,
    expected_mask: set[tuple[int, int]],
) -> None:
    changed = {
        (x, y)
        for y in range(SIZE)
        for x in range(SIZE)
        if off.getpixel((x, y)) != on.getpixel((x, y))
    }
    if not changed:
        raise ValueError(f"{block_id} front_on does not differ from front")
    if changed != expected_mask:
        missing = sorted(expected_mask - changed)
        unexpected = sorted(changed - expected_mask)
        raise ValueError(
            f"{block_id} state mask mismatch; missing={missing}, unexpected={unexpected}"
        )


def validate_assets(assets: dict[str, Image.Image]) -> None:
    expected_names = {
        f"{block_id}_{face}"
        for block_id in STATE_MASKS
        for face in ("top", "side", "front", "front_on")
    }
    actual_names = set(assets)
    if actual_names != expected_names:
        raise ValueError(
            "Asset set mismatch; "
            f"missing={sorted(expected_names - actual_names)}, "
            f"unexpected={sorted(actual_names - expected_names)}"
        )
    for asset_name, image in assets.items():
        validate_image(asset_name, image)
    for block_id, state_mask in STATE_MASKS.items():
        validate_state_pair(
            block_id,
            assets[f"{block_id}_front"],
            assets[f"{block_id}_front_on"],
            state_mask,
        )


def save_assets(assets: dict[str, Image.Image]) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for asset_name, image in assets.items():
        path = OUTPUT_DIR / f"{asset_name}.png"
        image.save(path, format="PNG", optimize=False, compress_level=9)
        print(f"wrote {path.relative_to(ROOT)}")


def main() -> None:
    assets = build_assets()
    validate_assets(assets)
    save_assets(assets)
    print("Built and validated 20 final preheat generator and power cell textures.")


if __name__ == "__main__":
    main()
