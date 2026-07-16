"""Generate five original plasma-shield sound assets deterministically.

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


def _saturate(signal: list[float], drive: float) -> list[float]:
    normalized = _normalize(signal, 1.0)
    ceiling = math.tanh(drive)
    return [math.tanh(drive * value) / ceiling for value in normalized]


def _shield_hit(seed: int, duration: float, variation: float) -> list[float]:
    rng = random.Random(seed)
    count = int(duration * SAMPLE_RATE)
    times = [index / SAMPLE_RATE for index in range(count)]

    arc_noise = _high_pass([rng.gauss(0.0, 1.0) for _ in range(count)], 13)
    low_frequency = [88.0 + variation * 7.0 - 34.0 * min(1.0, t / 0.09) for t in times]
    low_impact = _phase_tone(low_frequency, variation)
    shell_frequency = [
        650.0 + (1500.0 + variation * 130.0 - 650.0) * math.exp(-18.0 * t)
        for t in times
    ]
    shell = _phase_tone(shell_frequency, 0.3 + variation)

    crystal_bands = (
        (0.018, 2410.0 + variation * 70.0, 20.0, 0.2),
        (0.031, 4130.0 - variation * 90.0, 25.0, 1.1),
        (0.047, 6830.0 + variation * 110.0, 31.0, 2.0),
    )
    signal: list[float] = []
    for index, t in enumerate(times):
        arc = arc_noise[index] * math.exp(-92.0 * t)
        thump = low_impact[index] * math.exp(-20.0 * t)
        shell_ring = shell[index] * math.exp(-17.0 * t)
        crystal = 0.0
        for delay, frequency, decay, phase in crystal_bands:
            if t >= delay:
                local = t - delay
                crystal += math.sin(2.0 * math.pi * frequency * local + phase) * math.exp(-decay * local)
        flicker = 0.0
        for delay in (0.125 + variation * 0.004, 0.178 - variation * 0.003):
            if t >= delay:
                local = t - delay
                flicker += (math.sin(2.0 * math.pi * (3100.0 - 5200.0 * local) * local)
                            * math.exp(-58.0 * local))
        signal.append(0.46 * arc + 0.58 * thump + 0.34 * shell_ring
                      + 0.22 * crystal + 0.10 * flicker)
    return _normalize(_fade(_saturate(signal, 2.0), 1.0, 24.0), 0.76)


def _overheat() -> list[float]:
    rng = random.Random(0x504C5348)
    duration = 0.86
    count = int(duration * SAMPLE_RATE)
    times = [index / SAMPLE_RATE for index in range(count)]

    breaker_noise = _high_pass([rng.gauss(0.0, 1.0) for _ in range(count)], 11)
    shutdown_frequency = [90.0 + 1010.0 * math.exp(-8.0 * max(0.0, t - 0.035)) for t in times]
    shutdown = _phase_tone(shutdown_frequency, 0.5)
    cavity_frequency = [132.0 - 54.0 * min(1.0, t / duration) for t in times]
    cavity = _phase_tone(cavity_frequency, 1.2)

    signal: list[float] = []
    for index, t in enumerate(times):
        breaker = breaker_noise[index] * math.exp(-58.0 * t)
        thump = math.sin(2.0 * math.pi * (68.0 - 18.0 * min(1.0, t / 0.07)) * t)
        thump *= math.exp(-18.0 * t)
        crystal = 0.0
        for delay, frequency, decay in ((0.006, 1920.0, 20.0),
                                        (0.012, 3680.0, 24.0),
                                        (0.019, 7240.0, 31.0)):
            if t >= delay:
                local = t - delay
                crystal += math.sin(2.0 * math.pi * frequency * local) * math.exp(-decay * local)
        power_down = shutdown[index] * math.exp(-3.9 * max(0.0, t - 0.035)) if t >= 0.035 else 0.0
        glitch = 0.0
        for delay in (0.19, 0.29, 0.39):
            if delay <= t < delay + 0.045:
                local = t - delay
                gate = 1.0 if int(local * 420.0) % 2 == 0 else -0.55
                glitch += (gate * math.sin(2.0 * math.pi * (980.0 - 720.0 * local) * local)
                           * math.exp(-27.0 * local))
        metal_tail = cavity[index] * math.exp(-5.0 * max(0.0, t - 0.40)) if t >= 0.40 else 0.0
        signal.append(0.58 * breaker + 0.74 * thump + 0.26 * crystal
                      + 0.42 * power_down + 0.15 * glitch + 0.18 * metal_tail)
    return _normalize(_fade(_saturate(signal, 2.7), 0.5, 48.0), 0.68)


def _steam_vent() -> list[float]:
    rng = random.Random(0x53544541)
    duration = 1.42
    count = int(duration * SAMPLE_RATE)
    times = [index / SAMPLE_RATE for index in range(count)]

    noise = [rng.gauss(0.0, 1.0) for _ in range(count)]
    bright_steam = _low_pass(_high_pass(noise, 47), 4)
    dark_steam = _low_pass(_high_pass(noise, 173), 12)
    valve_noise = _high_pass([rng.gauss(0.0, 1.0) for _ in range(count)], 19)
    hull_frequency = [310.0 - 125.0 * min(1.0, t / 0.85) for t in times]
    hull = _phase_tone(hull_frequency, 0.8)

    signal: list[float] = []
    for index, t in enumerate(times):
        open_valve = valve_noise[index] * math.exp(-86.0 * t)
        open_valve += math.sin(2.0 * math.pi * 1240.0 * t) * math.exp(-72.0 * t)
        attack = min(1.0, max(0.0, (t - 0.012) / 0.018))
        release = 1.0 if t < 0.34 else math.exp(-2.55 * (t - 0.34))
        bright_mix = max(0.0, 1.0 - t / 0.78)
        pressure = 0.91 + 0.09 * math.sin(2.0 * math.pi * 7.1 * t)
        steam = (bright_steam[index] * bright_mix
                 + dark_steam[index] * (1.0 - bright_mix)) * attack * release * pressure
        resonance = hull[index] * attack * math.exp(-3.5 * t)
        close_valve = 0.0
        if t >= 1.12:
            local = t - 1.12
            close_valve = valve_noise[index] * math.exp(-52.0 * local)
            close_valve += math.sin(2.0 * math.pi * 720.0 * local) * math.exp(-38.0 * local)
        signal.append(0.42 * open_valve + 0.70 * steam + 0.14 * resonance + 0.20 * close_valve)
    return _normalize(_fade(_saturate(signal, 1.45), 0.5, 75.0), 0.68)


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
        "hit_01.ogg": _shield_hit(0x48495431, 0.24, -0.45),
        "hit_02.ogg": _shield_hit(0x48495432, 0.27, 0.0),
        "hit_03.ogg": _shield_hit(0x48495433, 0.31, 0.55),
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
