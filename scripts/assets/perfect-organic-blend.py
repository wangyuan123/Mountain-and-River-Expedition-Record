import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageChops
from pathlib import Path
import math

ROOT = Path('.').resolve()
MAP_DIR = ROOT / 'frontend/img/map'
OUT_DIR = ROOT / 'output/imagegen/redesign-oil-grain-20261019'
OUT_DIR.mkdir(parents=True, exist_ok=True)
TARGET = 384

print("Starting Organic Blending & Artifact Elimination...")

# ==============================================================================
# 1. PROCESS GRAINFIELD (款式一：石渠水网·金稻方阵)
# ==============================================================================
raw_grain_path = Path('/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832/ricefield_paddy_v1_1792423238204.jpg')
g_img = cv2.imread(str(raw_grain_path))
gh, gw = g_img.shape[:2]

# Crop diorama
sub_g = g_img[185:855, 36:988].copy()
sgh, sgw = sub_g.shape[:2]

hsv_g = cv2.cvtColor(sub_g, cv2.COLOR_BGR2HSV)
sat_g = hsv_g[:, :, 1]
val_g = hsv_g[:, :, 2]

# Floor detection: any low saturation and medium-high brightness, or near white
diff_w = np.linalg.norm(sub_g.astype(float) - 255.0, axis=2)
is_floor = ((sat_g < 35) & (val_g > 150)) | (diff_w < 90)

# Floodfill from borders to only remove external floor
flood_g = is_floor.astype(np.uint8)
for y in range(0, sgh, 4):
    for x in [0, sgw - 1]:
        if flood_g[y, x] == 1:
            cv2.floodFill(flood_g, None, (x, y), 2)
for x in range(0, sgw, 4):
    for y in [0, sgh - 1]:
        if flood_g[y, x] == 1:
            cv2.floodFill(flood_g, None, (x, y), 2)

exterior_g = (flood_g == 2)

# Also explicitly clean the bottom-left studio cast shadow
# In sub_g, the mud base left edge is around x = 70. Any exterior floor on x < 70, y > 350
for y in range(350, sgh):
    for x in range(0, 95):
        if sat_g[y, x] < 45 and val_g[y, x] > 120:
            exterior_g[y, x] = True

# Also clean the bottom shadow strip below the stone base
# The stone culvert bottom is around y = 620-630
for y in range(630, sgh):
    for x in range(0, sgw):
        if sat_g[y, x] < 40 and val_g[y, x] > 110:
            exterior_g[y, x] = True

# Alpha mask
alpha_g = (~exterior_g).astype(np.uint8) * 255

# Apply 1.5px Gaussian blur to alpha for smooth edge antialiasing
alpha_g_smooth = cv2.GaussianBlur(alpha_g, (3, 3), 0.8)

b_g, g_g, r_g = cv2.split(sub_g)
grain_rgba = cv2.merge([r_g, g_g, b_g, alpha_g_smooth])
pil_grain = Image.fromarray(grain_rgba)

# Tight crop to visible content
y_g, x_g = np.where(alpha_g_smooth > 15)
grain_cropped = pil_grain.crop((x_g.min(), y_g.min(), x_g.max(), y_g.max()))

# Target width 332 to keep 26px margin on left and right inside 384x384
tw_g, th_g = grain_cropped.size
scale_g = 332.0 / tw_g
scaled_w_g = int(tw_g * scale_g)
scaled_h_g = int(th_g * scale_g)
scaled_grain = grain_cropped.resize((scaled_w_g, scaled_h_g), Image.Resampling.LANCZOS)
scaled_grain = ImageEnhance.Color(scaled_grain).enhance(1.10)

# Create 384x384 canvas
grain_canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
gx = (TARGET - scaled_w_g) // 2
gy = (TARGET - scaled_h_g) // 2 + 4

# Create natural soft contact shadow underneath the mud base
grain_shadow = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
s_draw = ImageDraw.Draw(grain_shadow)
# Draw an elliptical/trapezoidal ground shadow directly under the diorama base
s_draw.polygon([
    (gx + 10, gy + scaled_h_g - 40),
    (gx + scaled_w_g - 10, gy + scaled_h_g - 40),
    (gx + scaled_w_g - 25, gy + scaled_h_g + 14),
    (gx + 25, gy + scaled_h_g + 14)
], fill=(20, 16, 10, 140))
grain_shadow = grain_shadow.filter(ImageFilter.GaussianBlur(10))

grain_canvas.alpha_composite(grain_shadow)
grain_canvas.alpha_composite(scaled_grain, (gx, gy))

# Apply subtle feathering on the very bottom 6px of the mud base so it dissolves smoothly into grass/soil
g_arr = np.array(grain_canvas)
for y in range(gy + scaled_h_g - 6, gy + scaled_h_g + 2):
    if y < TARGET:
        factor = (gy + scaled_h_g + 2 - y) / 8.0
        g_arr[y, :, 3] = np.clip(g_arr[y, :, 3].astype(float) * factor, 0, 255).astype(np.uint8)

grain_final = Image.fromarray(g_arr)
grain_final.save(OUT_DIR / "perfect_grainfield_waterway.png")
print("Grainfield perfected and saved.")


# ==============================================================================
# 2. PROCESS OILFIELD (款型 C 重型前线采油站)
# ==============================================================================
raw_oil_path = ROOT / "output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png"
o_img = cv2.imread(str(raw_oil_path))
oh, ow = o_img.shape[:2]

hsv_o = cv2.cvtColor(o_img, cv2.COLOR_BGR2HSV)
sat_o = hsv_o[:, :, 1]
val_o = hsv_o[:, :, 2]

# Backdrop detection
is_bg_o = (sat_o < 34) & (val_o > 150)
flood_o = is_bg_o.astype(np.uint8)

# Flood fill outer
for y in range(0, oh, 6):
    for x in [0, ow - 1]:
        if flood_o[y, x] == 1:
            cv2.floodFill(flood_o, None, (x, y), 2)
for x in range(0, ow, 6):
    for y in [0, oh - 1]:
        if flood_o[y, x] == 1:
            cv2.floodFill(flood_o, None, (x, y), 2)

# Flood fill inner holes
inner_seed_points = [
    (400, 300), (460, 320), (500, 400), (465, 340), (450, 360), (470, 310)
]
for px, py in inner_seed_points:
    if flood_o[py, px] == 1:
        cv2.floodFill(flood_o, None, (px, py), 2)

exterior_o = (flood_o == 2)

# Ensure the right side around the tank and tree (where user circled) has NO grey remnants
for y in range(350, 750):
    for x in range(1040, ow):
        if sat_o[y, x] < 38 and val_o[y, x] > 135:
            exterior_o[y, x] = True

# Also ensure bottom gravel edge has zero rectangular cutoff
# Ground boundaries taper off naturally
for y in range(1140, oh):
    for x in range(0, ow):
        if sat_o[y, x] < 40 and val_o[y, x] > 130:
            exterior_o[y, x] = True

alpha_o = (~exterior_o).astype(np.uint8) * 255
alpha_o_smooth = cv2.GaussianBlur(alpha_o, (3, 3), 0.8)

b_o, g_o, r_o = cv2.split(o_img)
oil_rgba = cv2.merge([r_o, g_o, b_o, alpha_o_smooth])
pil_oil = Image.fromarray(oil_rgba)

# Tight crop
y_o, x_o = np.where(alpha_o_smooth > 15)
oil_cropped = pil_oil.crop((x_o.min(), y_o.min(), x_o.max(), y_o.max()))

# Scale to fit comfortably with breathing room (width 328px)
tw_o, th_o = oil_cropped.size
scale_o = 328.0 / tw_o
scaled_w_o = int(tw_o * scale_o)
scaled_h_o = int(th_o * scale_o)
scaled_oil = oil_cropped.resize((scaled_w_o, scaled_h_o), Image.Resampling.LANCZOS)

# Create 384x384 canvas
oil_canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
ox = (TARGET - scaled_w_o) // 2
oy = (TARGET - scaled_h_o) // 2 + 12

# Create organic contact shadow conforming to the actual oilfield footprint
# (NO RECTANGULAR BOX!)
oil_shadow = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
os_draw = ImageDraw.Draw(oil_shadow)

# Soft shadow pools under:
# 1. Main pumpjack concrete pad
os_draw.ellipse([ox + 55, oy + 120, ox + 180, oy + 230], fill=(20, 16, 12, 130))
# 2. Large oil storage tank
os_draw.ellipse([ox + 175, oy + 130, ox + 270, oy + 210], fill=(20, 16, 12, 140))
# 3. Generator shed
os_draw.ellipse([ox + 160, oy + 190, ox + 295, oy + 260], fill=(20, 16, 12, 130))
# 4. Small gatehouse & entrance
os_draw.ellipse([ox + 20, oy + 180, ox + 95, oy + 245], fill=(20, 16, 12, 110))
# 5. General gravel courtyard
os_draw.polygon([
    (ox + 40, oy + 150),
    (ox + 270, oy + 140),
    (ox + 300, oy + 230),
    (ox + 180, oy + 265),
    (ox + 50, oy + 250)
], fill=(20, 16, 12, 100))

oil_shadow = oil_shadow.filter(ImageFilter.GaussianBlur(12))

oil_canvas.alpha_composite(oil_shadow)
oil_canvas.alpha_composite(scaled_oil, (ox, oy))

# Soften the outermost 6px of gravel boundary for natural terrain bleed
o_arr = np.array(oil_canvas)
oil_final = Image.fromarray(o_arr)
oil_final.save(OUT_DIR / "perfect_oilfield_c.png")
print("Oilfield perfected and saved.")

# ==============================================================================
# 3. PREPARE ANIMATED 12 FRAMES FOR WILD-OIL-FRAMES
# ==============================================================================
# Now generate the 12 walking beam frames based on this pristine oil_final!
frames_dir = MAP_DIR / "wild-oil-frames"
frames_dir.mkdir(parents=True, exist_ok=True)

# Locate pivot on this 384x384 canvas
# Scaled pumpjack pivot
pivot_x = ox + int(372 * scale_o)
pivot_y = oy + int(295 * scale_o)
print(f"Animation pivot on canvas: ({pivot_x}, {pivot_y})")

# Beam mask on 384x384 canvas
beam_mask = Image.new("L", (TARGET, TARGET), 0)
b_draw = ImageDraw.Draw(beam_mask)
# Scaled beam polygon relative to (ox, oy)
raw_beam_poly = [
    (195, 140), (330, 170), (510, 275), (525, 335), (490, 360),
    (360, 305), (310, 325), (200, 290), (185, 200)
]
scaled_poly = [(ox + int(px * scale_o), oy + int(py * scale_o)) for px, py in raw_beam_poly]
b_draw.polygon(scaled_poly, fill=255)
beam_mask = beam_mask.filter(ImageFilter.GaussianBlur(1.0))

beam_img = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
beam_img.paste(oil_final, mask=beam_mask)

anim_frames = []
for index in range(12):
    phase = math.tau * index / 12.0
    angle = math.sin(phase) * 2.2
    rot_beam = beam_img.rotate(-angle, resample=Image.Resampling.BICUBIC, center=(pivot_x, pivot_y))
    
    frame = oil_final.copy()
    frame.alpha_composite(rot_beam)
    
    # Polish rod vertical travel
    rod_x = ox + int(216 * scale_o)
    rod_top = oy + int(290 * scale_o)
    rod_bot = oy + int(380 * scale_o)
    draw_f = ImageDraw.Draw(frame)
    draw_f.line([(rod_x, rod_top), (rod_x, rod_bot)], fill=(215, 225, 235, 190), width=1)
    
    frame.save(frames_dir / f"frame_{index:02d}.png", "PNG", optimize=True)
    anim_frames.append(frame)

anim_frames[0].save(
    MAP_DIR / "wild-oil-animated.webp",
    "WEBP",
    save_all=True,
    append_images=anim_frames[1:],
    duration=120,
    loop=0,
    quality=94,
    method=6
)
print("Saved 12 frames in wild-oil-frames and animated WebP.")

# ==============================================================================
# 4. EXPORT ALL PRODUCTION ASSETS
# ==============================================================================
# Grainfield
grain_final.save(MAP_DIR / "wild-grainfield-v1-waterway.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-v1-waterway.webp", "WEBP", quality=95, method=6)
grain_final.save(MAP_DIR / "wild-grainfield-map-v2-map-embedded.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-integrated-map-embedded.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-map-embedded.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-orthogonal-map-embedded.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-transparent-c.png", "PNG", optimize=True)
grain_final.save(MAP_DIR / "wild-grainfield-map-v2.webp", "WEBP", quality=95, method=6)
grain_final.save(MAP_DIR / "wild-grainfield-integrated.webp", "WEBP", quality=95, method=6)
grain_final.save(MAP_DIR / "wild-grainfield.webp", "WEBP", quality=95, method=6)
grain_final.save(MAP_DIR / "wild-grainfield-map.webp", "WEBP", quality=95, method=6)

# Oilfield
oil_final.save(MAP_DIR / "wild-oil-transparent-c.png", "PNG", optimize=True)
oil_final.save(MAP_DIR / "wild-oil-integrated-map-embedded.png", "PNG", optimize=True)
oil_final.save(MAP_DIR / "wild-oil-map-embedded.png", "PNG", optimize=True)
oil_final.save(MAP_DIR / "wild-oil-orthogonal-map-embedded.png", "PNG", optimize=True)
oil_final.save(MAP_DIR / "wild-oil-integrated.webp", "WEBP", quality=95, method=6)
oil_final.save(MAP_DIR / "wild-oil.webp", "WEBP", quality=95, method=6)
oil_final.save(MAP_DIR / "wild-oil-map.webp", "WEBP", quality=95, method=6)

print("All production assets exported successfully!")
