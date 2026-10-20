from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "output/imagegen/redesign-oil-grain-20261019/refined_grain_opt2_farmers_waves.png"
MAP_DIR = ROOT / "frontend/img/map"

img = Image.open(SRC)

# Export PNGs
img.save(MAP_DIR / "wild-grainfield-square-c.png")
img.save(MAP_DIR / "wild-grainfield-transparent-c.png")
img.save(MAP_DIR / "wild-grainfield-integrated-map-embedded.png")
img.save(MAP_DIR / "wild-grainfield-map-embedded.png")
img.save(MAP_DIR / "wild-grainfield-orthogonal-map-embedded.png")

# Export WebPs (high quality 95)
img.save(MAP_DIR / "wild-grainfield-map-v2.webp", "WEBP", quality=95, method=6)
img.save(MAP_DIR / "wild-grainfield-integrated.webp", "WEBP", quality=95, method=6)
img.save(MAP_DIR / "wild-grainfield.webp", "WEBP", quality=95, method=6)

print("Successfully applied Grainfield Option B to frontend/img/map/ assets.")
