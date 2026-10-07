"""Prepare the existing square-view NPC fortress for a single map cell."""

from pathlib import Path

from PIL import Image, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "output/imagegen/cities-orthogonal-20261003/raw/npc-fortress.png"
DEST = ROOT / "frontend/img/map"
BACKGROUND = (217, 214, 208)


def main():
    artwork = Image.open(SOURCE).convert("RGB").crop((0, 45, 1254, 1095))
    mask = Image.new("L", artwork.size)
    source_pixels = artwork.load()
    mask_pixels = mask.load()

    # The source has a nearly uniform studio backdrop. Preserve fine antennae
    # while dissolving its pale perimeter and contact shadow into the map.
    for y in range(artwork.height):
        for x in range(artwork.width):
            color = source_pixels[x, y]
            distance = max(abs(color[c] - BACKGROUND[c]) for c in range(3))
            opacity = max(0, min(255, round((distance - 8) / 24 * 255)))
            opacity = round(opacity * min(1, x / 32, (artwork.width - 1 - x) / 32))
            if y > 905:
                opacity = round(opacity * max(0, min(1, (1010 - y) / 105)))
            mask_pixels[x, y] = opacity

    mask = mask.filter(ImageFilter.GaussianBlur(1.5))
    artwork = artwork.resize((384, 384), Image.Resampling.LANCZOS)
    mask = mask.resize((384, 384), Image.Resampling.LANCZOS)
    artwork.putalpha(mask)
    artwork.save(DEST / "npc-fortress-orthogonal-map-embedded.png", optimize=True)
    artwork.save(DEST / "npc-fortress-orthogonal.webp", "WEBP", quality=94, method=6)


if __name__ == "__main__":
    main()
