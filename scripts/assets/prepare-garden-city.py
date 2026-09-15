"""Prepare the selected garden city with a feathered moat and flat map footprint."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / 'output/imagegen/fortified-cities-20260914/c-garden-citadel-v2.png'
dest = ROOT / 'frontend/img/cities'
image = Image.open(source).convert('RGBA')
width, height = image.size
# Keep the moat and gate bridges, remove the surrounding rectangular landscape.
# Coordinates follow this selected artwork; the source remains unchanged.
outline = [(0.50, .018), (.996, .438), (.995, .52), (.54, .957),
           (.47, .974), (.004, .525), (.005, .437)]
mask = Image.new('L', image.size)
draw = ImageDraw.Draw(mask)
draw.polygon([(round(x*width), round(y*height)) for x,y in outline], fill=255)
# Short bridge approaches extend outside the moat on each side.
for a,b in [((.18,.22),(.25,.30)), ((.77,.29),(.86,.22)),
            ((.16,.76),(.24,.68)), ((.77,.68),(.85,.77))]:
    draw.line([(int(x*width),int(y*height)) for x,y in (a,b)], fill=255, width=34)
mask = mask.filter(ImageFilter.GaussianBlur(9))
image.putalpha(mask)
image.resize((512,512), Image.Resampling.LANCZOS).save(dest/'garden-citadel.webp', quality=94)
# Like NPC/harbor artwork, this miniature already contains its camera projection.
# Only the ground layer is projected by the renderer; compressing this sprite
# a second time flattens both its city walls and its vertical buildings.
sprite = image.resize((512,512), Image.Resampling.LANCZOS)
sprite.save(dest/'garden-citadel-map-embedded.png', optimize=True)
print('Prepared garden city detail and map assets.')
