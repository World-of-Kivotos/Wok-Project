"""Generate original procedural sound cues for tarot crafting, packs, and casting.

The sounds are deterministic and synthesized from basic oscillators/noise. They
do not contain or derive from third-party game audio.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/miningdim/sounds/job/tarot"
SAMPLE_RATE = 48_000
RNG = np.random.default_rng(0x5441524F54)


def _buffer(duration: float) -> np.ndarray:
    return np.zeros(int(round(duration * SAMPLE_RATE)), dtype=np.float64)


def _mix(target: np.ndarray, source: np.ndarray, start: float = 0.0) -> None:
    offset = int(round(start * SAMPLE_RATE))
    if offset >= len(target):
        return
    usable = min(len(source), len(target) - offset)
    target[offset:offset + usable] += source[:usable]


def _envelope(length: int, attack: float, release: float) -> np.ndarray:
    env = np.ones(length, dtype=np.float64)
    attack_samples = min(length, max(1, int(round(attack * SAMPLE_RATE))))
    release_samples = min(length, max(1, int(round(release * SAMPLE_RATE))))
    env[:attack_samples] *= np.sin(np.linspace(0.0, np.pi / 2.0, attack_samples)) ** 2
    env[-release_samples:] *= np.cos(np.linspace(0.0, np.pi / 2.0, release_samples)) ** 2
    return env


def _sweep(duration: float, start_hz: float, end_hz: float, amplitude: float,
           attack: float = 0.02, release: float = 0.18, harmonic: float = 0.16) -> np.ndarray:
    length = int(round(duration * SAMPLE_RATE))
    progress = np.linspace(0.0, 1.0, length)
    frequencies = start_hz * np.power(end_hz / start_hz, progress)
    phase = np.cumsum(frequencies) * (2.0 * np.pi / SAMPLE_RATE)
    signal = np.sin(phase) + harmonic * np.sin(phase * 2.003 + 0.37)
    return signal * _envelope(length, attack, release) * amplitude


def _bell(duration: float, frequency: float, amplitude: float, decay: float = 4.2) -> np.ndarray:
    length = int(round(duration * SAMPLE_RATE))
    time = np.arange(length) / SAMPLE_RATE
    attack = np.minimum(1.0, time / 0.006)
    body = (
        np.sin(2.0 * np.pi * frequency * time)
        + 0.38 * np.sin(2.0 * np.pi * frequency * 2.01 * time + 0.2)
        + 0.16 * np.sin(2.0 * np.pi * frequency * 3.97 * time + 0.7)
    )
    return body * attack * np.exp(-decay * time) * amplitude


def _noise(duration: float, amplitude: float, smooth: int = 1,
           attack: float = 0.002, release: float = 0.12) -> np.ndarray:
    length = int(round(duration * SAMPLE_RATE))
    signal = RNG.normal(0.0, 1.0, length)
    if smooth > 1:
        kernel = np.ones(smooth, dtype=np.float64) / smooth
        signal = np.convolve(signal, kernel, mode="same") * np.sqrt(smooth)
    return signal * _envelope(length, attack, release) * amplitude


def _fracture(duration: float, amplitude: float) -> np.ndarray:
    signal = _noise(duration, amplitude, smooth=2, release=max(0.05, duration * 0.7))
    for index, position in enumerate(np.linspace(0.02, duration * 0.72, 6)):
        frequency = 2100.0 + index * 370.0
        _mix(signal, _bell(min(0.18, duration - position), frequency,
                           amplitude * (0.32 - index * 0.025), decay=18.0), position)
    return signal


def _reverb(signal: np.ndarray, delays: tuple[tuple[float, float], ...]) -> np.ndarray:
    wet = signal.copy()
    for delay, gain in delays:
        samples = int(round(delay * SAMPLE_RATE))
        if samples < len(signal):
            wet[samples:] += signal[:-samples] * gain
    return wet


def _finish(signal: np.ndarray) -> np.ndarray:
    signal = _reverb(signal, ((0.071, 0.22), (0.137, 0.13), (0.223, 0.07)))
    signal = np.tanh(signal * 1.12)
    peak = float(np.max(np.abs(signal)))
    if peak > 0.0:
        signal *= 0.92 / peak
    return signal.astype(np.float32)


def _quality_chord(name: str, casting: bool) -> np.ndarray:
    definition = {
        "r": (0.48, (392.0,), 0.22),
        "sr": (0.64, (392.0, 523.25), 0.22),
        "ssr": (0.82, (329.63, 493.88, 659.25), 0.20),
        "ur": (1.02, (293.66, 440.0, 554.37, 880.0), 0.18),
        "shiny": (1.24, (261.63, 392.0, 523.25, 783.99, 1046.5), 0.16),
    }[name]
    duration, frequencies, level = definition
    signal = _buffer(duration)
    if casting:
        _mix(signal, _sweep(duration * 0.78, 78.0, 46.0, 0.18, release=0.30), 0.0)
        offset_step = 0.045
    else:
        _mix(signal, _sweep(min(0.38, duration), 980.0, 1320.0, 0.07,
                            attack=0.005, release=0.16), 0.0)
        offset_step = 0.032
    for index, frequency in enumerate(frequencies):
        _mix(signal, _bell(duration - index * offset_step, frequency,
                           level * (1.0 - index * 0.07), decay=3.5 + index * 0.35),
             index * offset_step)
    if name in {"ssr", "ur", "shiny"}:
        _mix(signal, _fracture(min(0.24, duration), 0.045), 0.035)
    if name == "shiny":
        for index in range(7):
            _mix(signal, _bell(0.34, 1180.0 + index * 173.0, 0.055, decay=9.0),
                 0.18 + index * 0.075)
    return signal


def _craft_charge() -> np.ndarray:
    signal = _buffer(1.72)
    _mix(signal, _sweep(1.65, 66.0, 118.0, 0.24, attack=0.12, release=0.28), 0.0)
    _mix(signal, _sweep(1.28, 260.0, 780.0, 0.12, attack=0.10, release=0.24), 0.22)
    for index, frequency in enumerate((293.66, 392.0, 523.25, 698.46)):
        _mix(signal, _bell(0.74, frequency, 0.17, decay=5.2), 0.36 + index * 0.27)
    _mix(signal, _fracture(0.30, 0.07), 1.36)
    return signal


def _pack_scan() -> np.ndarray:
    signal = _buffer(1.68)
    _mix(signal, _sweep(1.62, 82.0, 104.0, 0.20, attack=0.08, release=0.28), 0.0)
    _mix(signal, _sweep(1.25, 430.0, 1240.0, 0.13, attack=0.04, release=0.20), 0.24)
    for index, frequency in enumerate((740.0, 880.0, 1040.0, 1320.0)):
        _mix(signal, _bell(0.26, frequency, 0.12, decay=10.0), 0.24 + index * 0.31)
    return signal


def _pack_open() -> np.ndarray:
    signal = _buffer(1.02)
    _mix(signal, _noise(0.48, 0.16, smooth=7, release=0.26), 0.02)
    _mix(signal, _fracture(0.42, 0.12), 0.18)
    _mix(signal, _sweep(0.72, 126.0, 54.0, 0.25, attack=0.005, release=0.34), 0.14)
    _mix(signal, _bell(0.62, 523.25, 0.16, decay=5.8), 0.28)
    return signal


def _pack_complete() -> np.ndarray:
    signal = _buffer(1.08)
    _mix(signal, _sweep(0.84, 92.0, 58.0, 0.18, release=0.36), 0.0)
    for index, frequency in enumerate((261.63, 392.0, 523.25, 659.25)):
        _mix(signal, _bell(0.92 - index * 0.04, frequency, 0.18, decay=3.8), index * 0.055)
    _mix(signal, _bell(0.46, 1046.5, 0.09, decay=8.0), 0.30)
    return signal


def _cast_resolve(upright: bool) -> np.ndarray:
    signal = _buffer(0.92)
    if upright:
        _mix(signal, _sweep(0.74, 116.0, 48.0, 0.28, release=0.34), 0.0)
        for index, frequency in enumerate((293.66, 440.0, 659.25)):
            _mix(signal, _bell(0.72, frequency, 0.18, decay=4.8), 0.05 + index * 0.04)
    else:
        _mix(signal, _fracture(0.48, 0.17), 0.0)
        _mix(signal, _sweep(0.80, 330.0, 62.0, 0.26, release=0.32), 0.06)
        _mix(signal, _bell(0.58, 196.0, 0.13, decay=5.5), 0.18)
    return signal


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sounds: dict[str, np.ndarray] = {
        "craft_charge": _craft_charge(),
        "pack_scan": _pack_scan(),
        "pack_open": _pack_open(),
        "pack_complete": _pack_complete(),
        "cast_resolve_upright": _cast_resolve(True),
        "cast_resolve_reversed": _cast_resolve(False),
    }
    for quality in ("r", "sr", "ssr", "ur", "shiny"):
        sounds[f"pack_reveal_{quality}"] = _quality_chord(quality, casting=False)
        sounds[f"cast_reveal_{quality}"] = _quality_chord(quality, casting=True)

    for name, signal in sounds.items():
        path = OUTPUT / f"{name}.ogg"
        sf.write(path, _finish(signal), SAMPLE_RATE, format="OGG", subtype="VORBIS")
        info = sf.info(path)
        if info.frames <= 0 or info.samplerate != SAMPLE_RATE or info.channels != 1:
            raise RuntimeError(f"invalid generated sound: {path}: {info}")
        print(f"{path.relative_to(ROOT)} ({info.duration:.2f}s mono {info.samplerate}Hz)")


if __name__ == "__main__":
    main()
