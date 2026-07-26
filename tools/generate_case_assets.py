"""Generate original case-opening sounds and procedural TaCZ skin textures.

The output is deterministic. No Counter-Strike or TaCZ texture/audio bytes are
used. TaCZ display JSON values are read from the locally installed dependency so
the new textures keep the correct model, animation and sound references.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "src/main/resources/assets/miningdim"
AUDIO_ROOT = RESOURCE_ROOT / "sounds/ui/case"
PACK_ROOT = RESOURCE_ROOT / "custom/miningdim_cases"
DISPLAY_ROOT = PACK_ROOT / "assets/miningdim/display/guns"
TEXTURE_ROOT = PACK_ROOT / "assets/miningdim/textures/gun/uv"
TACZ_JAR = ROOT / "libs/tacz-1.20.1-1.1.8-hotfix.jar"
SAMPLE_RATE = 44_100


SKINS = (
    ("arctic_grid", "BLUE", "m4a1", (19, 75, 118), (112, 222, 245)),
    ("copper_wasp", "BLUE", "ak47", (66, 43, 34), (223, 131, 51)),
    ("midnight_tide", "BLUE", "glock_17", (7, 22, 56), (39, 128, 210)),
    ("desert_signal", "BLUE", "hk_mp5a5", (82, 68, 43), (211, 181, 103)),
    ("jade_circuit", "BLUE", "scar_l", (15, 64, 57), (57, 208, 155)),
    ("urban_rain", "BLUE", "m1014", (35, 46, 62), (100, 169, 213)),
    ("ember_trace", "BLUE", "p90", (60, 39, 37), (227, 111, 55)),
    ("violet_reactor", "PURPLE", "aug", (52, 26, 76), (170, 76, 232)),
    ("crimson_current", "PURPLE", "deagle", (73, 17, 45), (225, 55, 116)),
    ("cobalt_fang", "PURPLE", "ai_awp", (16, 32, 87), (74, 101, 238)),
    ("neon_rift", "PURPLE", "vector45", (41, 17, 63), (215, 64, 218)),
    ("aurora_protocol", "PINK", "hk416d", (45, 18, 72), (249, 92, 191)),
    ("dragon_glass", "PINK", "ak47", (73, 19, 42), (252, 95, 153)),
    ("eclipse_bloom", "PINK", "m4a1", (23, 17, 58), (235, 99, 210)),
    ("vermilion_sovereign", "RED", "ai_awp", (72, 10, 19), (244, 42, 61)),
    ("obsidian_crown", "RED", "deagle", (20, 18, 25), (217, 35, 68)),
    ("gilded_omen", "GOLD", "timeless50", (46, 32, 11), (247, 196, 68)),
)


def _seed(name: str) -> int:
    return int.from_bytes(hashlib.sha256(name.encode("utf-8")).digest()[:8], "big")


def _fade(signal: np.ndarray, milliseconds: float = 8.0) -> np.ndarray:
    count = min(len(signal) // 2, int(SAMPLE_RATE * milliseconds / 1000.0))
    if count:
        ramp = np.linspace(0.0, 1.0, count, endpoint=False)
        signal[:count] *= ramp
        signal[-count:] *= ramp[::-1]
    return signal


def _normalize(signal: np.ndarray, peak: float = 0.88) -> np.ndarray:
    maximum = float(np.max(np.abs(signal))) if signal.size else 0.0
    if maximum > 0.0:
        signal = signal * (peak / maximum)
    return _fade(signal.astype(np.float32))


def _tone(duration: float, start_hz: float, end_hz: float | None = None,
          decay: float = 4.5, phase: float = 0.0) -> np.ndarray:
    count = int(duration * SAMPLE_RATE)
    frequency = np.linspace(start_hz, end_hz or start_hz, count)
    angles = phase + 2.0 * math.pi * np.cumsum(frequency) / SAMPLE_RATE
    envelope = np.exp(-decay * np.linspace(0.0, 1.0, count))
    return np.sin(angles) * envelope


def _place(target: np.ndarray, source: np.ndarray, at_seconds: float, gain: float = 1.0) -> None:
    start = int(at_seconds * SAMPLE_RATE)
    end = min(len(target), start + len(source))
    if end > start:
        target[start:end] += source[:end - start] * gain


def _metal_click(duration: float, rng: np.random.Generator) -> np.ndarray:
    count = int(duration * SAMPLE_RATE)
    t = np.arange(count) / SAMPLE_RATE
    noise = rng.normal(0.0, 1.0, count) * np.exp(-42.0 * t)
    ring = (np.sin(2 * math.pi * 1470 * t) + 0.42 * np.sin(2 * math.pi * 2360 * t))
    ring *= np.exp(-31.0 * t)
    return noise * 0.34 + ring * 0.66


def _make_sounds() -> None:
    AUDIO_ROOT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(0xC45E2026)

    unlock = np.zeros(int(0.92 * SAMPLE_RATE))
    _place(unlock, _tone(0.42, 118, 72, 6.5), 0.0, 0.7)
    for at, gain in ((0.06, 0.75), (0.22, 0.6), (0.39, 0.48)):
        _place(unlock, _metal_click(0.12, rng), at, gain)
    _place(unlock, _tone(0.5, 330, 620, 3.8), 0.32, 0.25)

    opening = np.zeros(int(1.18 * SAMPLE_RATE))
    _place(opening, _tone(0.36, 82, 54, 7.0), 0.0, 0.85)
    whoosh = rng.normal(0.0, 1.0, int(0.88 * SAMPLE_RATE))
    whoosh *= np.linspace(0.0, 1.0, len(whoosh)) ** 1.6
    whoosh *= np.linspace(1.0, 0.0, len(whoosh)) ** 0.35
    _place(opening, whoosh, 0.18, 0.13)
    _place(opening, _tone(0.75, 190, 740, 1.8), 0.28, 0.22)
    _place(opening, _metal_click(0.16, rng), 0.02, 0.75)

    tick = _metal_click(0.085, rng)

    def reveal(duration: float, notes: tuple[float, ...], impact: float,
               shimmer: float) -> np.ndarray:
        signal = np.zeros(int(duration * SAMPLE_RATE))
        _place(signal, _tone(0.45, 95, 56, 7.0), 0.0, impact)
        _place(signal, _metal_click(0.15, rng), 0.0, 0.5)
        spacing = max(0.075, (duration - 0.55) / max(1, len(notes)))
        for index, note in enumerate(notes):
            chord = _tone(duration - index * spacing, note, note * 1.006, 3.0)
            chord += _tone(duration - index * spacing, note * 2.0, note * 2.01, 4.2) * shimmer
            _place(signal, chord, 0.1 + index * spacing, 0.31)
        dust = rng.normal(0.0, 1.0, len(signal)) * np.exp(-2.2 * np.linspace(0, 1, len(signal)))
        signal += dust * (0.018 + shimmer * 0.01)
        return signal

    sounds = {
        "unlock": unlock,
        "open": opening,
        "tick": tick,
        "reveal_blue": reveal(1.00, (392.0, 523.25), 0.45, 0.18),
        "reveal_purple": reveal(1.15, (349.23, 523.25, 698.46), 0.52, 0.25),
        "reveal_pink": reveal(1.30, (440.0, 659.25, 880.0), 0.58, 0.34),
        "reveal_red": reveal(1.48, (261.63, 523.25, 783.99, 1046.5), 0.82, 0.42),
        "reveal_gold": reveal(2.05, (329.63, 493.88, 659.25, 830.61, 1046.5), 0.95, 0.62),
    }

    for name, signal in sounds.items():
        path = AUDIO_ROOT / f"{name}.ogg"
        sf.write(path, _normalize(signal), SAMPLE_RATE, format="OGG", subtype="VORBIS")
        info = sf.info(path)
        if info.channels != 1 or info.samplerate != SAMPLE_RATE or info.format != "OGG":
            raise RuntimeError(f"invalid generated sound: {path}: {info}")
        print(f"sound {path.relative_to(ROOT)} ({info.duration:.2f}s mono {info.samplerate}Hz)")


def _skin_texture(skin_id: str, rarity: str, dark: tuple[int, int, int],
                  accent: tuple[int, int, int]) -> Image.Image:
    size = 256
    rng = np.random.default_rng(_seed(skin_id))
    yy, xx = np.mgrid[0:size, 0:size]
    direction = (xx * (0.55 + rng.random()) + yy * (0.35 + rng.random())) / (size * 1.8)
    wave = 0.5 + 0.5 * np.sin((xx + yy * (0.3 + rng.random())) / (7.0 + rng.random() * 13.0))
    noise = rng.normal(0.0, 0.07, (size, size))
    blend = np.clip(0.16 + direction * 0.38 + wave * 0.16 + noise, 0.0, 1.0)
    dark_array = np.array(dark, dtype=np.float32)
    accent_array = np.array(accent, dtype=np.float32)
    rgb = dark_array[None, None, :] * (1.0 - blend[:, :, None])
    rgb += accent_array[None, None, :] * blend[:, :, None]
    image = Image.fromarray(np.uint8(np.clip(rgb, 0, 255)), "RGB").convert("RGBA")
    draw = ImageDraw.Draw(image, "RGBA")

    step = int(rng.integers(22, 38))
    offset = int(rng.integers(-step, step))
    line_alpha = {"BLUE": 88, "PURPLE": 105, "PINK": 120, "RED": 140, "GOLD": 175}[rarity]
    for x in range(-size, size * 2, step):
        draw.line((x + offset, 0, x - size + offset, size), fill=(*accent, line_alpha), width=2)
        if rarity in {"PINK", "RED", "GOLD"}:
            draw.line((x + offset + 5, 0, x - size + offset + 5, size), fill=(255, 255, 255, 36), width=1)

    circuit_count = {"BLUE": 11, "PURPLE": 15, "PINK": 18, "RED": 22, "GOLD": 27}[rarity]
    for _ in range(circuit_count):
        x = int(rng.integers(0, size))
        y = int(rng.integers(0, size))
        length = int(rng.integers(18, 78))
        bend = int(rng.integers(-34, 35))
        points = [(x, y), (x + length // 2, y), (x + length // 2, y + bend), (x + length, y + bend)]
        draw.line(points, fill=(*accent, line_alpha + 30), width=int(rng.integers(1, 4)))
        radius = int(rng.integers(2, 5))
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=(245, 248, 255, line_alpha))

    if rarity == "GOLD":
        for _ in range(35):
            x, y = (int(rng.integers(0, size)), int(rng.integers(0, size)))
            radius = int(rng.integers(1, 4))
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=(255, 232, 139, 185))

    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay, "RGBA")
    overlay_draw.rectangle((0, 0, size - 1, size - 1), outline=(*accent, 96), width=5)
    overlay = overlay.filter(ImageFilter.GaussianBlur(1.3))
    return Image.alpha_composite(image, overlay)


def _strip_json_comments(text: str) -> str:
    output: list[str] = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        nxt = text[index + 1] if index + 1 < len(text) else ""
        if in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            output.append(char)
            index += 1
            continue
        if char == "/" and nxt == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and nxt == "*":
            index += 2
            while index + 1 < len(text) and text[index:index + 2] != "*/":
                index += 1
            index += 2
            continue
        output.append(char)
        index += 1
    clean = "".join(output)
    return re.sub(r",\s*([}\]])", r"\1", clean)


def _read_base_display(archive: zipfile.ZipFile, gun_id: str) -> dict:
    entry = f"assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/{gun_id}_display.json"
    text = archive.read(entry).decode("utf-8-sig")
    return json.loads(_strip_json_comments(text))


def _make_skins() -> None:
    DISPLAY_ROOT.mkdir(parents=True, exist_ok=True)
    TEXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    PACK_ROOT.mkdir(parents=True, exist_ok=True)
    (PACK_ROOT / "gunpack.meta.json").write_text(
        json.dumps({"namespace": "miningdim"}, indent=2) + "\n", encoding="utf-8")

    if not TACZ_JAR.is_file():
        raise FileNotFoundError(f"TaCZ dependency is missing: {TACZ_JAR}")

    with zipfile.ZipFile(TACZ_JAR) as archive:
        for skin_id, rarity, gun_id, dark, accent in SKINS:
            texture = _skin_texture(skin_id, rarity, dark, accent)
            texture_path = TEXTURE_ROOT / f"case_{skin_id}.png"
            texture.save(texture_path, format="PNG", optimize=True)

            display = _read_base_display(archive, gun_id)
            display["texture"] = f"miningdim:gun/uv/case_{skin_id}"
            display.pop("lod", None)
            display_path = DISPLAY_ROOT / f"case_{skin_id}_display.json"
            display_path.write_text(
                json.dumps(display, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"skin  {texture_path.relative_to(ROOT)} -> tacz:{gun_id}")


def main() -> None:
    _make_sounds()
    _make_skins()
    print(f"generated {len(SKINS)} skins and 8 original sounds")


if __name__ == "__main__":
    main()
