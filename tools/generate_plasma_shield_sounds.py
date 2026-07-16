"""Generate the two original plasma-shield sound assets deterministically.

No samples or third-party audio are used. Every waveform is synthesized from
tones and seeded noise, written to temporary PCM, then encoded as OGG/Vorbis.
"""

from __future__ import annotations

import argparse
import array
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/miningdim/sounds/item/plasma_shield"
SAMPLE_RATE = 44_100


def _phase_tone(frequencies: list[float], phase: float = 0.0) -> list[float]:
    output: list[float] = []
    angle = phase
    scale = 2.0 * math.pi / SAMPLE_RATE
    for frequency in frequencies:
        angle += scale * frequency
        output.append(math.sin(angle))
    return output


def _moving_average(signal: list[float], window: int) -> list[float]:
    output: list[float] = []
    running = 0.0
    for index, value in enumerate(signal):
        running += value
        if index >= window:
            running -= signal[index - window]
        output.append(running / min(index + 1, window))
    return output


def _high_pass(signal: list[float], window: int) -> list[float]:
    average = _moving_average(signal, window)
    return [value - baseline for value, baseline in zip(signal, average)]


def _low_pass(signal: list[float], window: int) -> list[float]:
    return _moving_average(signal, window)


def _fade(signal: list[float], attack_ms: float, release_ms: float) -> list[float]:
    attack = min(len(signal), int(SAMPLE_RATE * attack_ms / 1000.0))
    release = min(len(signal), int(SAMPLE_RATE * release_ms / 1000.0))
    for index in range(attack):
        signal[index] *= index / max(1, attack)
    for index in range(release):
        signal[-release + index] *= 1.0 - index / max(1, release - 1)
    return signal


def _normalize(signal: list[float], peak: float) -> list[float]:
    maximum = max((abs(value) for value in signal), default=0.0)
    scale = peak / maximum if maximum > 0.0 else 1.0
    return [value * scale for value in signal]


def _overheat() -> list[float]:
    rng = random.Random(0x504C5348)
    duration = 1.16
    count = int(duration * SAMPLE_RATE)
    times = [index / SAMPLE_RATE for index in range(count)]

    carrier_frequency = [130.0 + 850.0 * math.exp(-4.0 * t) for t in times]
    carrier = _phase_tone(carrier_frequency)
    harmonic = _phase_tone([frequency * 2.01 for frequency in carrier_frequency], 0.4)
    for index, t in enumerate(times):
        carrier[index] = (carrier[index] + 0.32 * harmonic[index]) * math.exp(-2.6 * t)

    warning = [0.0] * count
    for start in (0.055, 0.275):
        begin = int(start * SAMPLE_RATE)
        length = int(0.145 * SAMPLE_RATE)
        frequencies = [860.0 + (440.0 - 860.0) * index / max(1, length - 1)
                       for index in range(length)]
        sweep = _phase_tone(frequencies)
        for index, sample in enumerate(sweep):
            envelope = math.sin(math.pi * index / max(1, length - 1)) ** 0.7
            warning[begin + index] += math.tanh(2.6 * sample) * envelope

    breaker = [0.0] * count
    begin = int(0.435 * SAMPLE_RATE)
    length = int(0.24 * SAMPLE_RATE)
    crackle = _high_pass([rng.gauss(0.0, 1.0) for _ in range(length)], 35)
    for index in range(length):
        t = index / SAMPLE_RATE
        thump = math.sin(2.0 * math.pi * 74.0 * t) * math.exp(-15.0 * t)
        breaker[begin + index] = 0.52 * crackle[index] * math.exp(-34.0 * t) + 0.9 * thump

    tail = [0.0] * count
    begin = int(0.50 * SAMPLE_RATE)
    length = count - begin
    frequencies = [220.0 + (55.0 - 220.0) * index / max(1, length - 1)
                   for index in range(length)]
    tail_tone = _phase_tone(frequencies)
    for index, sample in enumerate(tail_tone):
        tail[begin + index] = sample * math.exp(-4.5 * index / max(1, length - 1))

    texture = _high_pass([rng.gauss(0.0, 1.0) for _ in range(count)], 19)
    signal = [0.48 * carrier[index]
              + 0.42 * warning[index]
              + 0.72 * breaker[index]
              + 0.32 * tail[index]
              + 0.035 * texture[index] * math.exp(-6.5 * times[index])
              for index in range(count)]
    return _normalize(_fade(signal, 4.0, 45.0), 0.89)


def _steam_vent() -> list[float]:
    rng = random.Random(0x53544541)
    duration = 1.08
    count = int(duration * SAMPLE_RATE)
    times = [index / SAMPLE_RATE for index in range(count)]

    noise = [rng.gauss(0.0, 1.0) for _ in range(count)]
    steam = _low_pass(_high_pass(noise, 63), 5)
    for index, t in enumerate(times):
        attack = min(1.0, t / 0.012)
        release = 1.0 if t < 0.50 else math.exp(-5.4 * (t - 0.50))
        pressure = 0.88 + 0.12 * math.sin(2.0 * math.pi * 6.3 * t)
        pressure += 0.055 * math.sin(2.0 * math.pi * 17.1 * t + 0.7)
        steam[index] *= attack * release * pressure

    valve = _high_pass([rng.gauss(0.0, 1.0) for _ in range(count)], 29)
    frequencies = [92.0 + (60.0 - 92.0) * index / max(1, count - 1)
                   for index in range(count)]
    reservoir = _phase_tone(frequencies)
    signal = [0.76 * steam[index]
              + 0.44 * valve[index] * math.exp(-46.0 * times[index])
              + 0.10 * reservoir[index] * math.exp(-5.0 * times[index])
              for index in range(count)]
    return _normalize(_fade(signal, 2.0, 60.0), 0.71)


def _write_pcm(path: Path, signal: list[float]) -> None:
    samples = array.array("h", (
        int(max(-1.0, min(1.0, value)) * 32767.0) for value in signal
    ))
    if sys.byteorder != "little":
        samples.byteswap()
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(samples.tobytes())


def _find_ffmpeg(explicit: str | None) -> str:
    candidate = explicit or os.environ.get("FFMPEG") or shutil.which("ffmpeg")
    if not candidate or not Path(candidate).is_file():
        raise FileNotFoundError("ffmpeg not found; pass --ffmpeg or set FFMPEG")
    return candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", help="path to an ffmpeg executable with libvorbis")
    args = parser.parse_args()
    ffmpeg = _find_ffmpeg(args.ffmpeg)

    OUTPUT.mkdir(parents=True, exist_ok=True)
    sounds = {
        "overheat.ogg": _overheat(),
        "steam_vent.ogg": _steam_vent(),
    }
    with tempfile.TemporaryDirectory(prefix="miningdim-plasma-audio-") as temp:
        temp_root = Path(temp)
        for filename, signal in sounds.items():
            wav_path = temp_root / f"{Path(filename).stem}.wav"
            output_path = OUTPUT / filename
            _write_pcm(wav_path, signal)
            subprocess.run([
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(wav_path), "-map_metadata", "-1",
                "-ac", "1", "-ar", str(SAMPLE_RATE),
                "-c:a", "libvorbis", "-q:a", "5", str(output_path),
            ], check=True)
            peak = max(abs(value) for value in signal)
            rms = math.sqrt(sum(value * value for value in signal) / len(signal))
            print(f"{output_path.relative_to(ROOT)}: {len(signal) / SAMPLE_RATE:.2f}s "
                  f"mono {SAMPLE_RATE}Hz peak={peak:.3f} rms={rms:.3f}")


if __name__ == "__main__":
    main()
