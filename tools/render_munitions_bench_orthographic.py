from pathlib import Path

from PIL import Image, ImageDraw

import generate_munitions_bench_geckolib_assets as assets


OUTPUT = Path(__file__).resolve().parents[1] / "dist/munitions-bench-geckolib-orthographic.png"
CANVAS_SIZE = (4200, 720)
PANEL_WIDTH = 600
SCALE = 16
GROUP_COLORS = {
    "body": (80, 96, 112, 45),
    "press": (224, 78, 68, 245),
    "carousel": (224, 170, 56, 245),
    "belt": (58, 190, 212, 245),
    "drawer": (191, 89, 210, 245),
}


def shifted(color, amount, alpha=None):
    return assets.shifted(color, amount, alpha)


def rendered_cube(source):
    # 镜像换算与生成器共用一份, 避免两处约定分叉后三视图与实际产物对不上。
    return assets.rendered_cube(source)


def all_cubes():
    return ([('body', rendered_cube(cube)) for cube in assets.BODY]
            + [('press', rendered_cube(cube)) for cube in assets.PRESS]
            + [('carousel', rendered_cube(cube)) for cube in assets.CAROUSEL]
            + [('drawer', rendered_cube(cube)) for cube in assets.DRAWER])


def rectangle_for(cube, view, panel_x):
    x, y, z = cube["origin"]
    dx, dy, dz = cube["size"]
    if view == "front":
        left = panel_x + 172 + x * SCALE
        top = 610 - (y + dy) * SCALE
        right = left + dx * SCALE
        bottom = top + dy * SCALE
        depth = z
    elif view == "back":
        left = panel_x + 172 + (16 - x - dx) * SCALE
        top = 610 - (y + dy) * SCALE
        right = left + dx * SCALE
        bottom = top + dy * SCALE
        depth = z + dz
    elif view == "right":
        left = panel_x + 300 - (z + dz) * SCALE
        top = 610 - (y + dy) * SCALE
        right = left + dz * SCALE
        bottom = top + dy * SCALE
        depth = x
    elif view == "left":
        left = panel_x + 300 + z * SCALE
        top = 610 - (y + dy) * SCALE
        right = left + dz * SCALE
        bottom = top + dy * SCALE
        depth = x + dx
    else:
        left = panel_x + 172 + x * SCALE
        top = 360 + z * SCALE
        right = left + dx * SCALE
        bottom = top + dz * SCALE
        depth = y
    return (left, top, right, bottom), depth


def render_panel(canvas, view, panel_index, palette):
    panel_x = panel_index * PANEL_WIDTH
    panel_layer = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    panel_draw = ImageDraw.Draw(panel_layer, "RGBA")
    panel_draw.rectangle((panel_x + 22, 58, panel_x + 578, 674),
                         fill=(13, 17, 22, 255), outline=(66, 74, 84, 255), width=2)
    panel_draw.text((panel_x + 42, 78), view.upper(), fill=(218, 225, 232, 255),
                    stroke_width=1, stroke_fill=(0, 0, 0, 255))

    projected = [(group, cube, *rectangle_for(cube, view, panel_x))
                 for group, cube in all_cubes()]
    reverse = view in {"front", "left"}
    projected.sort(key=lambda item: item[3], reverse=reverse)
    for group, cube, rectangle, _ in projected:
        color = GROUP_COLORS[group]
        alpha = 245 if view in {"back", "left", "right"} and group == "body" else (
            color[3] if len(color) == 4 else 255)
        face = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
        draw = ImageDraw.Draw(face, "RGBA")
        draw.rectangle(rectangle, fill=(*shifted(color, -12)[:3], alpha),
                       outline=(4, 6, 9, 245), width=2)
        draw.line((rectangle[0] + 1, rectangle[1] + 1,
                   rectangle[2] - 1, rectangle[1] + 1),
                  fill=shifted(color, 55, alpha=min(alpha, 235)), width=2)
        panel_layer = Image.alpha_composite(panel_layer, face)
    return Image.alpha_composite(canvas, panel_layer)


def reflected_cube(source, mirror_x, mirror_z):
    cube = dict(source)
    x, y, z = source["origin"]
    dx, dy, dz = source["size"]
    cube["origin"] = [(-x - dx) if mirror_x else x, y,
                      (-z - dz) if mirror_z else z]
    return cube


def render_oblique_panel(canvas, title, panel_index, palette, mirror_x):
    panel_x = panel_index * PANEL_WIDTH
    panel_layer = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    panel_draw = ImageDraw.Draw(panel_layer, "RGBA")
    panel_draw.rectangle((panel_x + 22, 58, panel_x + 578, 674),
                         fill=(13, 17, 22, 255), outline=(66, 74, 84, 255), width=2)
    panel_draw.text((panel_x + 42, 78), title, fill=(218, 225, 232, 255),
                    stroke_width=1, stroke_fill=(0, 0, 0, 255))

    faces = []
    for group, source in all_cubes():
        reflected = reflected_cube(source, mirror_x=mirror_x, mirror_z=True)
        for face in assets.preview_faces(reflected, palette, 0, 0, 12.0):
            face["group"] = group
            faces.append(face)

    all_x = [point[0] for face in faces for point in face["points"]]
    all_y = [point[1] for face in faces for point in face["points"]]
    shift_x = panel_x + 300 - (min(all_x) + max(all_x)) / 2
    shift_y = 390 - (min(all_y) + max(all_y)) / 2
    for face in sorted(faces, key=lambda item: item["depth"]):
        points = [(x + shift_x, y + shift_y) for x, y in face["points"]]
        color = GROUP_COLORS[face["group"]]
        if face["group"] == "body":
            color = (*color[:3], 245)
        panel_draw.polygon(points, fill=color, outline=(4, 6, 9, 245))
        panel_draw.line((points[3], points[2]), fill=shifted(color, 50), width=2)
    return Image.alpha_composite(canvas, panel_layer)


def main():
    canvas = Image.new("RGBA", CANVAS_SIZE, (9, 12, 16, 255))
    palette = assets.resolved_palette(assets.TIERS[0][2])
    for index, view in enumerate(("front", "back", "left", "right", "top")):
        canvas = render_panel(canvas, view, index, palette)
    canvas = render_oblique_panel(canvas, "REAR LEFT", 5, palette, mirror_x=True)
    canvas = render_oblique_panel(canvas, "REAR RIGHT", 6, palette, mirror_x=False)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(OUTPUT)


if __name__ == "__main__":
    main()
