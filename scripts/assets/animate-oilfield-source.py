#!/usr/bin/env python3
"""Build map-sized oil-field animation frames from a supplied source image."""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


SIZE = 384
FRAME_COUNT = 12


def fit_source(source: Image.Image, mask_source: Path) -> Image.Image:
    source = source.convert("RGB")
    scale = min(366 / source.width, 338 / source.height)
    resized = source.resize((round(source.width * scale), round(source.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    x = (SIZE - resized.width) // 2
    y = 22 + (338 - resized.height) // 2
    canvas.paste(resized.convert("RGBA"), (x, y))
    edge = Image.open(mask_source).convert("RGBA").getchannel("A")
    canvas.putalpha(edge)
    return canvas


def add_glow(layer: Image.Image, center: tuple[int, int], radius: int, color: tuple[int, int, int], alpha: int) -> None:
    glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        (center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius),
        fill=(*color, alpha),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(max(2, radius // 2)))
    layer.alpha_composite(glow)


def draw_motion(frame: Image.Image, index: int, mask: Image.Image) -> Image.Image:
    phase = (index / FRAME_COUNT) * math.tau
    motion = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(motion)

    # Flare stack: a pulsing glow and a changing flame silhouette.
    flare = (224, 72)
    glow_alpha = 35 + round(28 * (0.5 + 0.5 * math.sin(phase * 2)))
    add_glow(motion, flare, 13 + round(3 * math.sin(phase * 2)), (255, 139, 32), glow_alpha)
    flame_h = 11 + round(5 * (0.5 + 0.5 * math.sin(phase * 2)))
    flame_x = flare[0] + round(2 * math.sin(phase * 3))
    draw.polygon(
        [
            (flame_x - 3, flare[1] + 3), (flame_x - 5, flare[1] - flame_h // 2),
            (flame_x - 1, flare[1] - flame_h), (flame_x + 1, flare[1] - flame_h // 2),
            (flame_x + 5, flare[1] - flame_h + 3), (flame_x + 3, flare[1] + 3),
        ],
        fill=(255, 164, 43, 210),
    )
    draw.ellipse((flame_x - 2, flare[1] - flame_h + 3, flame_x + 2, flare[1] - 2), fill=(255, 235, 143, 220))

    # Smoke puffs drift up and to the right from the flare.
    for puff in range(4):
        drift = (index * 1.15 + puff * 5.5) % 26
        px = flare[0] + 5 + drift
        py = flare[1] - 13 - puff * 8 - index * 0.45
        radius = 4 + (puff % 2)
        draw.ellipse((px - radius, py - radius, px + radius, py + radius), fill=(89, 86, 73, 35 - puff * 5))

    # Two prominent pumpjacks get moving rod highlights, making the work cycle readable at map scale.
    pumpjacks = [
        ((49, 79), (49, 111), 0.0),
        ((130, 93), (130, 125), 1.7),
        ((54, 257), (54, 289), 3.3),
        ((178, 293), (178, 322), 4.9),
    ]
    for (top, bottom, offset) in pumpjacks:
        travel = math.sin(phase + offset) * 2.0
        x = round(top[0] + travel)
        draw.line((x, top[1], x, bottom[1]), fill=(207, 175, 108, 105), width=1)
        draw.ellipse((x - 2, bottom[1] - 2, x + 2, bottom[1] + 2), fill=(218, 137, 53, 125))
        # A narrow specular sweep on the walking beam marks the alternating stroke.
        beam_x = round(top[0] + 8 + math.sin(phase + offset) * 3)
        draw.line((beam_x - 5, top[1] - 3, beam_x + 6, top[1] - 3), fill=(230, 207, 153, 80), width=1)

    # Amber flow glints travel through the main refinery pipe run.
    pipe = [(202, 153), (220, 143), (238, 129), (255, 112), (278, 94), (301, 81)]
    travel = (index / FRAME_COUNT) * (len(pipe) - 1)
    for dot in range(3):
        at = (travel + dot * 1.5) % (len(pipe) - 1)
        left = int(at)
        frac = at - left
        x = round(pipe[left][0] + (pipe[left + 1][0] - pipe[left][0]) * frac)
        y = round(pipe[left][1] + (pipe[left + 1][1] - pipe[left][1]) * frac)
        draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(255, 197, 75, 165))

    motion.putalpha(ImageChops.multiply(motion.getchannel("A"), mask))
    frame.alpha_composite(motion)
    return frame


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--edge-reference", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    base = fit_source(Image.open(args.source), args.edge_reference)
    mask = base.getchannel("A")
    for index in range(FRAME_COUNT):
        frame = draw_motion(base.copy(), index, mask)
        frame.save(args.output / f"frame_{index:02d}.png", optimize=True)

    base.save(args.output.parent / "wild-oil-map-embedded.png", optimize=True)
    base.save(args.output.parent / "wild-oil-integrated-map-embedded.png", optimize=True)
    base.save(args.output.parent / "wild-oil-integrated.webp", "WEBP", quality=92, method=6)
    base.save(args.output.parent / "wild-oil.webp", "WEBP", quality=92, method=6)


if __name__ == "__main__":
    from PIL import ImageChops

    main()
