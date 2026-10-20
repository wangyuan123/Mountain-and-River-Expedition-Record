from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
MAP_DIR = ROOT / "frontend/img/map"

# 1. Grainfield
grain_img = Image.open(SRC_DIR / "wild-grainfield-v3-orthogonal-transparent.png")
# Save PNGs
grain_img.save(MAP_DIR / "wild-grainfield-transparent-c.png")
grain_img.save(MAP_DIR / "wild-grainfield-integrated-map-embedded.png")
grain_img.save(MAP_DIR / "wild-grainfield-orthogonal-map-embedded.png")
# Save WebPs
grain_img.save(MAP_DIR / "wild-grainfield-map-v2.webp", "WEBP", quality=95, method=6)
grain_img.save(MAP_DIR / "wild-grainfield-integrated.webp", "WEBP", quality=95, method=6)

# 2. Oilfield
oil_img = Image.open(SRC_DIR / "wild-oil-v3-orthogonal-transparent.png")
# Save PNGs
oil_img.save(MAP_DIR / "wild-oil-transparent-c.png")
oil_img.save(MAP_DIR / "wild-oil-integrated-map-embedded.png")
oil_img.save(MAP_DIR / "wild-oil-orthogonal-map-embedded.png")
# Save WebPs
oil_img.save(MAP_DIR / "wild-oil-integrated.webp", "WEBP", quality=95, method=6)

print("Successfully exported Grainfield and Oilfield assets to frontend/img/map/")
