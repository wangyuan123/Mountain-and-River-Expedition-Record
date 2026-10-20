from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"

# Load v3-orthogonal (derrick + twin tanks + pumpjack)
raw_v3 = Image.open(ROOT / "output/imagegen/map-icons/wild-oil-v3-orthogonal.png").convert("RGBA")

# Let's create two terrain backgrounds to test:
# 1) Grassland (104, 103, 71)
# 2) Black soil (92, 81, 62)

for name, color in [("grass", (104, 103, 71)), ("blacksoil", (92, 81, 62))]:
    bg = Image.new("RGBA", (384, 384), (*color, 255))
    
    # 1. Soft contact shadow on ground
    shadow = Image.new("RGBA", (384, 384), (0, 0, 0, 0))
    s_draw = ImageDraw.Draw(shadow)
    # Contact shadow for derrick tower, tanks, and pumpjack base
    s_draw.ellipse([50, 200, 200, 310], fill=(20, 15, 10, 160))
    s_draw.ellipse([180, 220, 370, 340], fill=(20, 15, 10, 180))
    s_draw.ellipse([80, 260, 320, 360], fill=(20, 15, 10, 190))
    shadow = shadow.filter(ImageFilter.GaussianBlur(8))
    
    bg.alpha_composite(shadow)
    bg.alpha_composite(raw_v3)
    bg.save(OUT_DIR / f"test_oil_v3_on_{name}.png")

print("Generated test_oil_v3_on_grass and test_oil_v3_on_blacksoil")
