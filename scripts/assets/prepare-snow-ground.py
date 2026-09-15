"""Undo the authored diamond projection for snow textures used on the ground plane."""
from pathlib import Path
from PIL import Image
root=Path(__file__).resolve().parents[2]
for variant in ('thick','medium','thin'):
    source=root/'frontend/img/map'/f'snow-{variant}-map-embedded.png'
    image=Image.open(source).convert('RGBA')
    # Snow occupies x=0..384, y=96..288 of a square sprite. Map the
    # square terrain tile to that diamond, then the renderer reprojects it.
    ground=image.transform((384,384),Image.Transform.AFFINE,
                           (.5,-.5,192,.25,.25,96),Image.Resampling.BICUBIC)
    ground.save(source.with_name(f'snow-{variant}-ground.png'),optimize=True)
