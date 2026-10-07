#!/usr/bin/env python3
"""Prepare a softly edged shrub-grass patch from the supplied reference image."""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter


SIZE = 320


def make_patch(source_path: Path, output_path: Path) -> None:
    source = Image.open(source_path).convert("RGB")
    side = min(source.size)
    left = (source.width - side) // 2
    top = (source.height - side) // 2
    source = source.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    source = ImageEnhance.Brightness(source).enhance(1.16)
    source = ImageEnhance.Color(source).enhance(0.9)
    grass_tint = Image.new("RGB", source.size, (142, 153, 104))
    source = Image.blend(source, grass_tint, 0.18)

    mask = Image.new("L", (SIZE, SIZE), 0)
    pixels = mask.load()
    center = (SIZE - 1) / 2
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = (x - center) / (SIZE * 0.46), (y - center) / (SIZE * 0.44)
            angle = math.atan2(dy, dx)
            radius = math.sqrt(dx * dx + dy * dy)
            # Low-frequency lobes make the edge plant-like instead of a regular oval.
            edge = 1 + 0.075 * math.sin(angle * 5 + 0.4) + 0.045 * math.sin(angle * 9 - 1.1) + 0.025 * math.sin(angle * 13 + 2.2)
            t = max(0.0, min(1.0, (edge + 0.025 - radius) / 0.16))
            t = t * t * (3 - 2 * t)
            pixels[x, y] = round(t * 255)
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))

    output = source.convert("RGBA")
    output.putalpha(mask)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output.save(output_path, "WEBP", lossless=True, method=6)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Supplied shrub-grass reference image")
    parser.add_argument("output", type=Path, help="Output patch WebP path")
    args = parser.parse_args()
    make_patch(args.source, args.output)


if __name__ == "__main__":
    main()
