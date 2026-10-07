#!/usr/bin/env python3
"""Rebuild the map oilfield from the resource-tab oilfield artwork."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "frontend/img/buildings/garden/oilfield.webp"
MAP = ROOT / "frontend/img/map"
SIZE = 384
FRAME_COUNT = 12
PUMPJACK_PIVOT = (151, 106)
PUMPJACK_BEAM = [
    (60, 27), (96, 27), (105, 47), (190, 82), (213, 96),
    (211, 112), (187, 104), (101, 69), (99, 111), (84, 115),
    (78, 91), (69, 77), (61, 73),
]


def smooth(edge0: float, edge1: float, value: float) -> float:
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


def square_ground() -> Image.Image:
    """Create map-colored ground with a square footprint and feathered uneven edges."""
    ground = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pixels = ground.load()
    for y in range(SIZE):
        for x in range(SIZE):
            edge_noise = math.sin(x * .061 + y * .019) * 3.2 + math.sin(y * .087 - x * .014) * 2.2
            distance = min(x, y, SIZE - 1 - x, SIZE - 1 - y) + edge_noise
            alpha = round(142 * smooth(2, 25, distance))
            if alpha == 0:
                continue
            grain = math.sin(x * .17 + y * .071) * 4 + math.sin(y * .139 - x * .11) * 3
            soil_share = .38 + .14 * math.sin(x * .026 + y * .018) + .08 * math.sin(y * .041 - x * .013)
            soil_share = max(.16, min(.72, soil_share))
            color = tuple(round((83, 73, 61)[i] * soil_share + (157, 169, 126)[i] * (1 - soil_share) + grain) for i in range(3))
            pixels[x, y] = (*color, alpha)
    return ground


def extract_equipment(source: Image.Image) -> Image.Image:
    """Suppress the warm ground slab while retaining darker machinery and its contact shadows."""
    source = source.convert("RGBA")
    pixels = source.load()
    alpha = Image.new("L", (SIZE, SIZE), 0)
    mask = alpha.load()
    for y in range(SIZE):
        for x in range(SIZE):
            red, green, blue, opacity = pixels[x, y]
            if opacity == 0:
                continue
            luminance = red * .30 + green * .59 + blue * .11
            warm_ground = red - green > 10 and green - blue > 6 and luminance > 82
            # Rebuild the lower ground plane as a flat square instead of retaining the source's raised diamond plinth.
            slab_suppression = smooth(78, 184, y) if warm_ground else 0
            ground_contact_fade = smooth(226, 278, y)
            mask[x, y] = round(opacity * (1 - max(slab_suppression, ground_contact_fade)))
    alpha = alpha.filter(ImageFilter.GaussianBlur(.65))
    source.putalpha(Image.eval(alpha, lambda value: min(255, value)))
    return source


def make_base() -> tuple[Image.Image, Image.Image]:
    source = Image.open(SOURCE).convert("RGBA")
    source.thumbnail((SIZE - 16, SIZE - 24), Image.Resampling.LANCZOS)
    equipment = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    equipment.alpha_composite(source, ((SIZE - source.width) // 2, (SIZE - source.height) // 2 - 2))
    equipment = extract_equipment(equipment)

    # Isolate the walking beam and horsehead so each frame can rock around its real pivot.
    beam_mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(beam_mask).polygon(PUMPJACK_BEAM, fill=255)
    beam = equipment.copy()
    beam.putalpha(ImageChops.multiply(beam.getchannel("A"), beam_mask))
    static_equipment = equipment.copy()
    static_equipment.putalpha(ImageChops.multiply(static_equipment.getchannel("A"), ImageChops.invert(beam_mask)))

    base = square_ground()
    base.alpha_composite(static_equipment)

    # Unevenly feather the complete artwork so it merges into the map without a tile outline.
    edge = Image.new("L", (SIZE, SIZE), 0)
    edge_pixels = edge.load()
    for y in range(SIZE):
        for x in range(SIZE):
            variation = math.sin(x * .055 + 1.4) * 4 + math.sin(y * .073 - .8) * 3
            distance = min(x, y, SIZE - 1 - x, SIZE - 1 - y) + variation
            edge_pixels[x, y] = round(255 * smooth(1, 26, distance))
    edge = edge.filter(ImageFilter.GaussianBlur(1.5))
    base.putalpha(ImageChops.multiply(base.getchannel("A"), edge))
    beam.putalpha(ImageChops.multiply(beam.getchannel("A"), edge))
    return base, beam


def animate_pumpjack(base: Image.Image, beam: Image.Image, index: int) -> Image.Image:
    """Rock the pumpjack beam through a smooth loop around the fixed tower pivot."""
    phase = math.tau * index / FRAME_COUNT
    angle = math.sin(phase) * 6.0
    moving_beam = beam.rotate(-angle, resample=Image.Resampling.BICUBIC, center=PUMPJACK_PIVOT)
    frame = base.copy()
    frame.alpha_composite(moving_beam)
    return frame


def main() -> None:
    base, beam = make_base()
    for index in range(FRAME_COUNT):
        frame = animate_pumpjack(base, beam, index)
        frame.save(MAP / "wild-oil-frames" / f"frame_{index:02d}.png", optimize=True)
    base.save(MAP / "wild-oil-integrated-map-embedded.png", optimize=True)
    base.save(MAP / "wild-oil-map-embedded.png", optimize=True)
    base.save(MAP / "wild-oil-integrated.webp", "WEBP", quality=94, method=6)
    base.save(MAP / "wild-oil-animated.webp", "WEBP", quality=94, method=6)


if __name__ == "__main__":
    main()
