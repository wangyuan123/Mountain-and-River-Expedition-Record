import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
TARGET = 384

# Load the wheat field with farmers (refined_grain_opt2_farmers_waves.png)
base_img = cv2.imread(str(OUT_DIR / "refined_grain_opt2_farmers_waves.png"))
h, w = base_img.shape[:2]

# Let's inspect: in base_img, it's 384x384 full bleed.
# If placed directly on grass, the 4 edges are hard razor cuts.
# To eliminate 截断感 (clipping/truncation) while keeping horizontal/vertical alignment:
# 1. Create a safe inset mask (e.g. padding of 18px from edges)
# 2. Modulate the edge with high-frequency natural organic grass tufts & soil noise
# 3. Softly feather the alpha from 1.0 down to 0.0 over 12-18px
# 4. Add realistic ambient contact shadow beneath the footprint

# Create organic perimeter alpha mask
alpha = np.zeros((h, w), dtype=np.float32)

cx, cy = w / 2.0, h / 2.0
# Box bounds: inset by 18px so model is completely inside canvas with zero truncation
margin = 20.0
box_w = (w - margin * 2) / 2.0
box_h = (h - margin * 2) / 2.0

for y in range(h):
    for x in range(w):
        # Distance to box boundary
        dx = abs(x - cx)
        dy = abs(y - cy)
        
        # Chebyshev distance to square boundary
        dist_x = box_w - dx
        dist_y = box_h - dy
        dist_to_edge = min(dist_x, dist_y)
        
        # Organic edge noise (simulating natural wheat stalk fringes & soil irregularity)
        n1 = np.sin(x * 0.08) * 4.5 + np.cos(y * 0.09) * 4.5
        n2 = np.sin(x * 0.22 + y * 0.15) * 2.5
        edge_dist = dist_to_edge + n1 + n2
        
        # Smooth falloff: edge_dist > 14 is 1.0 (fully opaque), edge_dist < -4 is 0.0
        if edge_dist >= 14:
            alpha[y, x] = 1.0
        elif edge_dist <= -4:
            alpha[y, x] = 0.0
        else:
            t = (edge_dist + 4) / 18.0
            # smoothstep
            alpha[y, x] = t * t * (3 - 2 * t)

alpha_uint8 = (alpha * 255).astype(np.uint8)
# Soft blur on alpha
alpha_uint8 = cv2.GaussianBlur(alpha_uint8, (5, 5), 0)

# Build RGBA
b, g, r = cv2.split(base_img)
rgba = cv2.merge([r, g, b, alpha_uint8])
pil_fg = Image.fromarray(rgba)

# Ground contact shadow
shadow = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
s_draw = ImageDraw.Draw(shadow)
# Draw rounded rectangle / polygon for contact shadow
s_draw.rectangle([margin - 8, margin - 4, TARGET - margin + 8, TARGET - margin + 8], fill=(16, 12, 8, 140))
# Tighter darker shadow along bottom
s_draw.rectangle([margin - 4, TARGET - margin - 12, TARGET - margin + 4, TARGET - margin + 6], fill=(10, 8, 5, 190))
shadow = shadow.filter(ImageFilter.GaussianBlur(10))

# Composite final
final_model = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
final_model.alpha_composite(shadow)
final_model.alpha_composite(pil_fg)
final_model.save(OUT_DIR / "test_no_clip_model.png")

# Now test embedding onto Grassland and Black soil!
GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

test_canvas = Image.new("RGB", (TARGET * 2 + 30, TARGET + 60), (22, 26, 32))
t_draw = ImageDraw.Draw(test_canvas)

# Left: on grass
bg_grass = Image.new("RGBA", (TARGET, TARGET), (*GRASS, 255))
bg_grass.alpha_composite(final_model)
test_canvas.paste(bg_grass.convert("RGB"), (10, 40))

# Right: on black soil
bg_soil = Image.new("RGBA", (TARGET, TARGET), (*SOIL, 255))
bg_soil.alpha_composite(final_model)
test_canvas.paste(bg_soil.convert("RGB"), (TARGET + 20, 40))

# Borders
t_draw.rectangle([10, 40, 10 + TARGET, 40 + TARGET], outline=(255, 215, 0), width=2)
t_draw.rectangle([TARGET + 20, 40, TARGET + 20 + TARGET, 40 + TARGET], outline=(70, 160, 255), width=2)

font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
t_draw.text((10, 12), "左: 草坪环境嵌入（自然草木过渡 · 零硬边截断）", fill=(240, 245, 250), font=font)
t_draw.text((TARGET + 20, 12), "右: 黑土环境嵌入（软阴影自然咬合 · 零硬边截断）", fill=(240, 245, 250), font=font)

test_canvas.save(OUT_DIR / "test_no_clip_comparison.png")
print("Saved test_no_clip_comparison.png")
