#!/usr/bin/env python3
"""Prepare a softly edged square shrub-grass patch with dual-color in-game blending."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image

SIZE = 320


def make_patch(source_path: Path, output_path: Path) -> None:
    source = Image.open(source_path).convert("RGBA")
    # Resize keeping aspect ratio 1:1
    patch = source.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    patch.save(output_path, "WEBP", lossless=True, method=6)
    print(f"Saved {output_path} (size {SIZE}x{SIZE})")


def main() -> None:
    parser = argparse.ArgumentParser()
    default_source = Path(__file__).resolve().parents[2] / 'output/imagegen/grass-redesign-20261015/optionA_steppe_calibrated.png'
    default_output = Path(__file__).resolve().parents[2] / 'frontend/img/map/wild-shrub-grass-patch.webp'
    parser.add_argument("--source", type=Path, default=default_source, help="Calibrated square lawn source")
    parser.add_argument("--output", type=Path, default=default_output, help="Output patch WebP path")
    args = parser.parse_args()
    make_patch(args.source, args.output)


if __name__ == "__main__":
    main()
