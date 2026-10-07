"""Blend existing wild artwork into the map's grass and soil."""

import math
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / "frontend/img/map"
GRASS = (157, 169, 126)
SOIL = (83, 73, 61)


def smooth(edge0, edge1, value):
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


WILD_GROUND = {
    "forest-dense": .68, "forest-ridge": .68, "forest-edge": .72,
    "hill-peak": .30, "hill-ridge": .43, "hill-foothill": .40,
    "swamp-deep": .44, "swamp-creek": .48, "swamp-marsh": .43,
    "rock": .22, "grainfield": .58, "oil": .48, "plains": .75,
}


def redesign(kind, grass_bias=.50):
    source = Image.open(MAP / f"wild-{kind}-map-embedded.png").convert("RGBA")
    target = Image.new("RGBA", source.size)
    source_px = source.load()
    target_px = target.load()

    for y in range(source.height):
        for x in range(source.width):
            red, green, blue, alpha = source_px[x, y]
            if not alpha:
                continue

            # Cut the old ground plate back on an uneven contour and feather it
            # into the map while keeping the tall subject above the plate intact.
            fringe = math.sin(x * .082) * 5 + math.sin(x * .173 + 1.8) * 3
            bottom = 363 - 23 * abs((x - 192) / 192) ** 1.4 + fringe
            edge_alpha = 1 - smooth(bottom - 19, bottom + 3, y)
            if y > 235:
                inset = smooth(235, 330, y)
                left = inset * (16 + math.sin(y * .106) * 7 + math.sin(y * .237) * 3)
                right = 384 - inset * (16 - math.sin(y * .092 + 2) * 7)
                edge_alpha *= smooth(left - 3, left + 12, x)
                edge_alpha *= 1 - smooth(right - 12, right + 3, x)
            if edge_alpha <= 0:
                continue

            # The map palette enters only at ground level; biome-specific grass
            # bias keeps stone, marsh, crops, and forest visually distinct.
            ground = smooth(252, 345, y)
            grass_share = grass_bias + .15 * math.sin(x * .049 + y * .025)
            terrain = tuple(round(SOIL[c] * (1 - grass_share) + GRASS[c] * grass_share) for c in range(3))
            detail = 1 - smooth(270, 350, y) * .82
            mix = ground * (1 - detail)
            color = tuple(round(original * (1 - mix) + terrain[c] * mix)
                          for c, original in enumerate((red, green, blue)))
            target_px[x, y] = (*color, round(alpha * edge_alpha))

    target.save(MAP / f"wild-{kind}-integrated-map-embedded.png", optimize=True)
    target.save(MAP / f"wild-{kind}-integrated.webp", "WEBP", quality=94, method=6)


if __name__ == "__main__":
    redesign("ironworks")
    redesign("rarefactory")
    for kind, grass_bias in WILD_GROUND.items():
        redesign(kind, grass_bias)
