"""Remove miniature plinth sides and feather ground contact; retain original artwork.
Run with Pillow. Map-only PNGs preserve the full canvas and all world/click coordinates.
"""
from pathlib import Path
from PIL import Image
import math

ROOT = Path(__file__).resolve().parents[2]
files = sorted((ROOT / 'frontend/img/map').glob('*-map.webp'))
files += sorted((ROOT / 'frontend/img/cities').glob('*-map.webp'))
for source in files:
    original = Image.open(source).convert('RGBA')
    image = original.copy()
    width, height = image.size
    pixels = image.load()
    alpha = original.getchannel('A')
    bottom = []
    for x in range(width):
        rows = [y for y in range(height) if alpha.getpixel((x, y)) > 96]
        bottom.append(max(rows) if rows else -1)
    for x in range(width):
        # Follow the two sloped front edges, never a horizontal crop through the model.
        neighbors = sorted(v for v in bottom[max(0, x-2):min(width, x+3)] if v >= 0)
        if not neighbors:
            continue
        edge = neighbors[len(neighbors)//2]
        for y in range(max(int(height*.46), edge-42), height):
            # Strip approximately 10px of visible side wall. A softly irregular 22px
            # ground band blends soil into the real map rather than a flat square.
            variation = 1.8*math.sin(x*.27) + 1.2*math.sin(x*.71)
            distance = edge-y
            factor = max(0., min(1., (distance-10-variation)/22))
            factor = factor*factor*(3-2*factor)
            red, green, blue, opacity = pixels[x,y]
            opacity = round(opacity*factor)
            pixels[x,y] = (red,green,blue,opacity) if opacity else (0,0,0,0)
    image.save(source.with_name(source.stem+'-embedded.png'), optimize=True)
