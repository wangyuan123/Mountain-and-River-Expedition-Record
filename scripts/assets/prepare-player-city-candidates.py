"""Create square, flat-footprint player-city candidates from existing renders."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output/imagegen/player-city-candidates-20261004"
OUT.mkdir(parents=True, exist_ok=True)

SOURCES = {
    "A-armored-command": ROOT / "output/imagegen/city-models/b-fortress-v2.png",
    "B-tech-command": ROOT / "output/imagegen/skyline-cities-20260915/b-command-capital.png",
    "C-industrial-command": ROOT / "output/imagegen/city-models/c-industry.png",
    "D-integrated-fortress": ROOT / "output/imagegen/fortified-cities-20260914/d-industrial-fortress.png",
}


def prepare(name, source):
    image = Image.open(source).convert("RGBA")
    width, height = image.size
    # Keep the complete city, but remove the studio/landscape border and the
    # thick front-facing slab that makes the source feel like a raised diorama.
    crop = image.crop((0, 0, width, round(height * .91)))
    mask = Image.new("L", crop.size, 255)
    draw = ImageDraw.Draw(mask)
    inset = round(crop.width * .025)
    draw.polygon([(inset, inset), (crop.width - inset, inset),
                  (crop.width - inset, crop.height - round(crop.height * .055)),
                  (round(crop.width * .93), crop.height - round(crop.height * .018)),
                  (round(crop.width * .07), crop.height - round(crop.height * .018)),
                  (inset, crop.height - round(crop.height * .055))], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(9))
    crop.putalpha(mask)
    crop = crop.resize((384, 384), Image.Resampling.LANCZOS)
    crop.save(OUT / f"{name}.png", optimize=True)
    crop.save(OUT / f"{name}.webp", "WEBP", quality=94, method=6)


for name, source in SOURCES.items():
    prepare(name, source)

print(f"Prepared {len(SOURCES)} player-city candidates in {OUT}")
