#!/usr/bin/env python3
"""Create looping map animation frames from existing integrated resource icons."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[2] / "frontend" / "img" / "map"
ROOT = ROOT.resolve()
FRAME_COUNT = 12
SIZE = 384


def glow(layer: Image.Image, center: tuple[int, int], radius: int, color: tuple[int, int, int], alpha: int) -> None:
    light = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(light).ellipse(
        (center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius),
        fill=(*color, alpha),
    )
    layer.alpha_composite(light.filter(ImageFilter.GaussianBlur(max(2, radius // 2))))


def steelworks_motion(index: int) -> Image.Image:
    phase = math.tau * index / FRAME_COUNT
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # Furnace mouth and nearby heat glow.
    furnace = (164, 257)
    pulse = 0.5 + 0.5 * math.sin(phase * 2)
    glow(layer, furnace, 14 + round(pulse * 5), (255, 104, 27), round(48 + pulse * 54))
    flame_h = 10 + round(pulse * 9)
    flame_x = furnace[0] + round(math.sin(phase * 3) * 3)
    draw.polygon(
        [(flame_x - 5, 260), (flame_x - 6, 255), (flame_x - 2, 252 - flame_h // 2), (flame_x, 255), (flame_x + 4, 251 - flame_h), (flame_x + 6, 260)],
        fill=(255, 170, 48, 235),
    )
    draw.ellipse((flame_x - 2, 253 - flame_h // 2, flame_x + 2, 260), fill=(255, 232, 139, 235))

    # Heat shimmer and smoke from the two stacks.
    for origin, drift_base in [((196, 51), 0), ((257, 54), 8)]:
        for puff in range(3):
            drift = (index * 1.3 + puff * 7 + drift_base) % 22
            x = origin[0] + round(drift * 0.38)
            y = origin[1] - puff * 9 - round((index % 6) * 0.7)
            r = 3 + puff % 2
            draw.ellipse((x - r, y - r, x + r, y + r), fill=(175, 166, 147, 48 - puff * 8))

    # Hot sparks travel along the conveyor entering the furnace.
    for dot in range(3):
        t = (index / FRAME_COUNT + dot / 3) % 1
        x = round(89 + t * 73)
        y = round(226 - t * 47)
        r = 2 + (dot == 0)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 192, 79, 230))
    return layer


def rarefactory_motion(index: int) -> Image.Image:
    phase = math.tau * index / FRAME_COUNT
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # Mineral-processing vats churn with quiet pulsing rings and surface glints.
    vats = [(143, 205, 25, 12), (198, 177, 23, 11), (238, 205, 22, 11)]
    for vat_index, (x, y, rx, ry) in enumerate(vats):
        pulse = 0.5 + 0.5 * math.sin(phase * 2 + vat_index * 1.6)
        alpha = round(60 + pulse * 75)
        draw.arc((x - rx + 3, y - ry + 3, x + rx - 3, y + ry - 3), start=round(index * 30 + vat_index * 38), end=round(index * 30 + vat_index * 38 + 225), fill=(176, 224, 154, alpha), width=3)
        angle = phase + vat_index * 1.9
        gx = x + round(math.cos(angle) * rx * 0.48)
        gy = y + round(math.sin(angle) * ry * 0.38)
        draw.ellipse((gx - 3, gy - 2, gx + 3, gy + 2), fill=(239, 232, 161, 230))

    # Ore glints move down the slanted feed conveyors toward the vats.
    conveyor = [(91, 101), (110, 120), (129, 139), (149, 158), (169, 176)]
    travel = (index / FRAME_COUNT) * (len(conveyor) - 1)
    for dot in range(3):
        t = (travel + dot * 1.25) % (len(conveyor) - 1)
        left = int(t)
        frac = t - left
        x = round(conveyor[left][0] + (conveyor[left + 1][0] - conveyor[left][0]) * frac)
        y = round(conveyor[left][1] + (conveyor[left + 1][1] - conveyor[left][1]) * frac)
        draw.ellipse((x - 3, y - 3, x + 3, y + 3), fill=(255, 218, 123, 230))

    # Steam from the separator stack drifts upward and thins out.
    for puff in range(4):
        drift = (index * 1.1 + puff * 6) % 24
        x = 298 + round(drift * 0.32)
        y = 49 - puff * 8 - round(index * 0.35)
        r = 3 + puff % 2
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(168, 163, 148, 32 - puff * 6))
    glow(layer, (189, 147), 8, (255, 151, 47), round(22 + 14 * (0.5 + 0.5 * math.sin(phase * 2))))
    return layer


def write_frames(kind: str, source_name: str, draw_motion) -> None:
    source_path = ROOT / source_name
    output = ROOT / f"wild-{kind}-frames"
    output.mkdir(exist_ok=True)
    base = Image.open(source_path).convert("RGBA")
    mask = base.getchannel("A")
    for index in range(FRAME_COUNT):
        frame = base.copy()
        effect = draw_motion(index)
        effect.putalpha(ImageChops.multiply(effect.getchannel("A"), mask))
        frame.alpha_composite(effect)
        frame.putalpha(mask)
        frame.save(output / f"frame_{index:02d}.png", optimize=True)


def main() -> None:
    write_frames("ironworks", "wild-ironworks-integrated-map-embedded.png", steelworks_motion)
    write_frames("rarefactory", "wild-rarefactory-integrated-map-embedded.png", rarefactory_motion)


if __name__ == "__main__":
    main()
