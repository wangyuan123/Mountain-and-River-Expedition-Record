#!/usr/bin/env python3
"""Synthesize a layered 1.5-second march preview without source recordings."""

import argparse
import math
import random
import struct
import wave
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DURATION = 1.5
SAMPLE_RATE = 48000
STEP_TIMES = (0.065, 0.565, 1.065)


def lowpass(samples, cutoff):
    coefficient = 1.0 - math.exp(-2.0 * math.pi * cutoff / SAMPLE_RATE)
    previous = 0.0
    output = []
    for sample in samples:
        previous += coefficient * (sample - previous)
        output.append(previous)
    return output


def bandpass(samples, lower, upper):
    lower_band = lowpass(samples, lower)
    upper_band = lowpass(samples, upper)
    return [high - low for high, low in zip(upper_band, lower_band)]


def normalize(samples):
    peak = max(abs(sample) for sample in samples)
    return [sample / peak for sample in samples]


def envelope(instant, onset, attack, decay):
    elapsed = instant - onset
    if elapsed <= 0.0:
        return 0.0
    return (1.0 - math.exp(-elapsed / attack)) * math.exp(-elapsed / decay)


def boot_contact(seed, distance):
    generator = random.Random(seed)
    frame_count = round(0.4 * SAMPLE_RATE)
    noise = [generator.uniform(-1.0, 1.0) for _ in range(frame_count)]
    body = normalize(bandpass(noise, 42.0, 240.0))
    sole = normalize(bandpass(noise, 190.0, 1250.0))
    grit = normalize(bandpass(noise, 750.0, 4300.0))
    leather = normalize(bandpass(noise, 280.0, 2100.0))
    toe_onset = generator.uniform(0.028, 0.048)
    fragments = [0.0] * frame_count
    for _ in range(46):
        onset = generator.uniform(0.0, 0.17)
        width = generator.uniform(0.0015, 0.008)
        amplitude = generator.uniform(0.1, 0.5) * math.exp(-onset / 0.085)
        start = round(onset * SAMPLE_RATE)
        for offset in range(round(width * 5 * SAMPLE_RATE)):
            index = start + offset
            if index < frame_count:
                elapsed = offset / SAMPLE_RATE
                fragments[index] += grit[index] * amplitude * envelope(elapsed, 0.0, 0.0005, width)
    output = []
    for index in range(frame_count):
        instant = index / SAMPLE_RATE
        heel = envelope(instant, 0.0, 0.0035, 0.038)
        toe = envelope(instant, toe_onset, 0.009, 0.048)
        scrape = envelope(instant, 0.075, 0.025, 0.065)
        contact = body[index] * (1.55 * heel + 1.1 * toe)
        contact += sole[index] * (0.5 * heel + 0.55 * toe)
        contact += grit[index] * (0.07 * heel + 0.16 * scrape)
        contact += leather[index] * scrape * 0.12
        contact += fragments[index] * 0.38
        output.append(contact)
    return lowpass(output, 6200.0 / (1.0 + distance * 1.7))


def boot_lift(seed):
    generator = random.Random(seed)
    frame_count = round(0.21 * SAMPLE_RATE)
    noise = [generator.uniform(-1.0, 1.0) for _ in range(frame_count)]
    friction = normalize(bandpass(noise, 380.0, 3400.0))
    return [sample * envelope(index / SAMPLE_RATE, 0.0, 0.022, 0.029)
            for index, sample in enumerate(friction)]


def mix_contact(channels, contact, onset, pan, gain):
    start = round(onset * SAMPLE_RATE)
    left_gain = math.sqrt((1.0 - pan) / 2.0) * gain
    right_gain = math.sqrt((1.0 + pan) / 2.0) * gain
    for offset, sample in enumerate(contact):
        destination = start + offset
        if 0 <= destination < len(channels[0]):
            channels[0][destination] += sample * left_gain
            channels[1][destination] += sample * right_gain


def build(destination):
    generator = random.Random(231026)
    frame_count = round(DURATION * SAMPLE_RATE)
    channels = [[0.0] * frame_count for _ in range(2)]
    positions = (-0.85, -0.61, -0.36, -0.12, 0.12, 0.36, 0.61, 0.85)
    march_times = (-0.435,) + STEP_TIMES
    for step_index, onset in enumerate(march_times):
        emphasis = (0.9, 1.0, 0.94, 1.03)[step_index]
        for rank in range(7):
            for soldier, pan in enumerate(positions):
                seed = 10000 + step_index * 1000 + rank * 100 + soldier
                distance = rank / 6.0
                contact = boot_contact(seed, distance)
                spread = 0.012 + rank * 0.003
                timing = onset + rank * 0.016 + generator.uniform(-spread, spread)
                gain = emphasis * generator.uniform(0.85, 1.15) * (0.81 ** rank) / len(positions)
                mix_contact(channels, contact, timing, pan, gain)
                lift = boot_lift(seed + 50000)
                mix_contact(channels, lift, timing + 0.26, pan, gain * 0.13)

    for channel_index in range(2):
        noise = [generator.uniform(-1.0, 1.0) for _ in range(frame_count)]
        ground = normalize(bandpass(noise, 32.0, 150.0))
        fabric = normalize(bandpass(noise, 420.0, 2200.0))
        for index in range(frame_count):
            instant = index / SAMPLE_RATE
            weight = sum(envelope(instant, onset + 0.015, 0.04, 0.095) for onset in march_times)
            motion = sum(envelope(instant, onset + 0.22, 0.05, 0.065) for onset in march_times)
            channels[channel_index][index] += ground[index] * (0.004 + weight * 0.1)
            channels[channel_index][index] += fabric[index] * (0.0015 + motion * 0.012)
    for channel_index, channel in enumerate(channels):
        rumble = lowpass(channel, 28.0)
        channels[channel_index] = [math.tanh((sample - low) * 3.0) for sample, low in zip(channel, rumble)]
    peak = max(abs(sample) for channel in channels for sample in channel)
    gain = 0.89 / peak
    fade_in_frames = round(0.02 * SAMPLE_RATE)
    fade_out_frames = round(0.095 * SAMPLE_RATE)
    samples = []
    for frame in range(frame_count):
        output_gain = gain
        if frame < fade_in_frames:
            output_gain *= math.sin(math.pi * frame / (2 * (fade_in_frames - 1))) ** 2
        remaining = frame_count - 1 - frame
        if remaining < fade_out_frames:
            output_gain *= math.sin(math.pi * remaining / (2 * (fade_out_frames - 1))) ** 2
        for channel in channels:
            samples.append(round(channel[frame] * output_gain * 32767))

    with wave.open(str(destination), 'wb') as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(struct.pack(f'<{len(samples)}h', *samples))
    print(f'Created {destination}: {DURATION:.2f}s, {SAMPLE_RATE} Hz, stereo PCM 16-bit; no source audio')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'frontend/audio/infantry_march_heavy_1_5s.wav')
    arguments = parser.parse_args()
    build(arguments.output)


if __name__ == '__main__':
    main()
