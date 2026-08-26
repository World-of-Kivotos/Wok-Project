"""Generate the five runtime gunsmith rarity nameplates from the approved source sheet.

Image generation prompt used for the source sheet:
    Edit the approved five-tier academy-tactical sci-fi preview into a blank
    production sprite sheet. Preserve one shared compact holographic circuit-frame
    geometry, remove every baked label and logo, keep a clean dark center for
    localized runtime text, and use the exact white, blue, green, purple, red tier
    order with genuine transparent alpha between nameplates.

The checked-in source has already had the generator's baked checkerboard removed.
This script locks that cleaned source by SHA-256 and only performs deterministic
crop, fit, and downscale operations.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "assets" / "gunsmith" / "gunsmith_rarity_nameplates_source.png"
SOURCE_SHA256 = "e8a2c03c879a4746109ba0c5b063eac7b54d8210b86601bb503503403c9fe367"
OUTPUT_DIR = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "miningdim"
    / "textures"
    / "gui"
    / "gunsmith"
    / "rarity"
)

CANVAS_SIZE = (76, 20)
CANVAS_PADDING = 0
TIERS = {
    "standard": (231, 221, 797, 373),
    "modified": (231, 465, 796, 619),
    "special": (231, 710, 796, 863),
    "advanced": (231, 953, 796, 1104),
    "prototype": (231, 1193, 796, 1344),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fit_on_canvas(sprite: Image.Image) -> Image.Image:
    usable_width = CANVAS_SIZE[0] - CANVAS_PADDING * 2
    usable_height = CANVAS_SIZE[1] - CANVAS_PADDING * 2
    scale = min(usable_width / sprite.width, usable_height / sprite.height)
    scaled_size = (
        max(1, round(sprite.width * scale)),
        max(1, round(sprite.height * scale)),
    )
    sprite = sprite.resize(scaled_size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    position = (
        (CANVAS_SIZE[0] - sprite.width) // 2,
        (CANVAS_SIZE[1] - sprite.height) // 2,
    )
    canvas.alpha_composite(sprite, position)
    return canvas


def main() -> None:
    actual_hash = sha256(SOURCE)
    if actual_hash != SOURCE_SHA256:
        raise RuntimeError(
            f"Rarity nameplate source hash mismatch: expected {SOURCE_SHA256}, got {actual_hash}"
        )

    source = Image.open(SOURCE).convert("RGBA")
    if source.getchannel("A").getextrema() != (0, 255):
        raise RuntimeError("Rarity nameplate source must contain genuine transparency")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for tier, crop_box in TIERS.items():
        texture = fit_on_canvas(source.crop(crop_box))
        target = OUTPUT_DIR / f"{tier}.png"
        texture.save(target, optimize=True)
        print(f"generated {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
