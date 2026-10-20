import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

# 1. Load base orthogonal diorama
base_bgr = cv2.imread('output/imagegen/terrain-orthogonal-20261003/raw/wild-grainfield.png')
h, w = base_bgr.shape[:2]

# 2. Extract farmers from grainfield_golden_waves
# In grainfield_golden_waves_1792391882826.jpg, let's find the farmers:
# Farmer A (bending down cutting wheat, white shirt/hat): x in [120..190], y in [530..610]
# Farmer B (carrying wheat bundle, blue pants/shirt): x in [700..780], y in [530..620]
# Farmer C (kneeling with bundle): x in [170..240], y in [860..950]
waves = cv2.imread('output/imagegen/redesign-oil-grain-20261019/grainfield_golden_waves_1792391882826.jpg')

# Let's crop them
farmer_a = waves[530:615, 120:195] # bending cutter
farmer_b = waves[530:625, 700:785] # carrying bundle
farmer_c = waves[860:955, 170:245] # bundle worker

# 3. Smooth the base wheat field completely:
# To eliminate the curved furrows/trenches and make "成片的麦子，不需要有垅沟壑间隔，调整稀疏一些":
# In base_bgr, the wheat field is from x=70..900, y=320..940.
# We can use directional texture inpainting and multiscale bilateral filtering:
wheat_mask = np.zeros((h, w), dtype=np.uint8)
pts = np.array([
    [90, 420], [200, 340], [350, 320], [520, 320], [780, 380], 
    [900, 480], [700, 560], [590, 710], [550, 940], 
    [80, 940], [80, 460]
], dtype=np.int32)
cv2.fillPoly(wheat_mask, [pts], 255)

# Detect the furrow shadows (gray value significantly lower than surrounding wheat)
gray = cv2.cvtColor(base_bgr, cv2.COLOR_BGR2GRAY)
# Local background average via large median blur
local_wheat = cv2.medianBlur(gray, 35)
# Dark grooves are where gray < local_wheat - 18
is_groove = (wheat_mask == 255) & (gray < (local_wheat - 15))

# Dilate groove mask slightly to cover edges
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
is_groove_dilated = cv2.dilate(is_groove.astype(np.uint8), kernel)

# Inpaint grooves using Navier-Stokes based inpainting
inpainted = cv2.inpaint(base_bgr, is_groove_dilated * 255, inpaintRadius=11, flags=cv2.INPAINT_NS)

# Further blend the wheat field to unify the texture into an even, continuous, natural wheat blanket
# A subtle gaussian blend on high-frequency ridges
wheat_smooth = cv2.bilateralFilter(inpainted, 15, 60, 60)

# Create a soft blend mask for the wheat field
mask_f = cv2.GaussianBlur(wheat_mask, (25, 25), 0).astype(float) / 255.0
mask_3d = np.dstack([mask_f, mask_f, mask_f])

# Mix: keep 65% smooth to eliminate ridges, 35% detailed texture
unified_wheat = (wheat_smooth * 0.65 + inpainted * 0.35).astype(np.uint8)
field_bgr = (unified_wheat * mask_3d + base_bgr * (1.0 - mask_3d)).astype(np.uint8)

# 4. Composite the miniature harvesting farmers into the field:
# Let's place Farmer A (bending cutter) at (x=380, y=720) in the wheat field
# Let's place Farmer B (carrying bundle) at (x=620, y=620) near the cart
# Let's place Farmer C (harvesting worker) at (x=240, y=550)

def paste_farmer(bg, farmer_crop, dest_x, dest_y, scale=1.1):
    fh, fw = farmer_crop.shape[:2]
    new_w, new_h = int(fw * scale), int(fh * scale)
    farmer_scaled = cv2.resize(farmer_crop, (new_w, new_h))
    
    # Extract alpha of farmer: farmer clothes are white/blue/straw, background is yellow wheat
    f_hsv = cv2.cvtColor(farmer_scaled, cv2.COLOR_BGR2HSV)
    # The farmer body has distinct saturation / lightness compared to wheat
    # Let's create an elliptical soft mask around farmer
    f_mask = np.zeros((new_h, new_w), dtype=float)
    cv2.ellipse(f_mask, (new_w//2, new_h//2), (new_w//2 - 2, new_h//2 - 2), 0, 0, 360, 1.0, -1)
    f_mask = cv2.GaussianBlur(f_mask, (5, 5), 0)
    
    # Place on bg
    for cy in range(new_h):
        for cx in range(new_w):
            by = dest_y + cy
            bx = dest_x + cx
            if 0 <= by < bg.shape[0] and 0 <= bx < bg.shape[1]:
                m = f_mask[cy, cx]
                bg[by, bx] = (farmer_scaled[cy, cx] * m + bg[by, bx] * (1 - m)).astype(np.uint8)

paste_farmer(field_bgr, farmer_a, 360, 710, scale=1.2)
paste_farmer(field_bgr, farmer_b, 610, 600, scale=1.15)
paste_farmer(field_bgr, farmer_c, 240, 530, scale=1.15)

cv2.imwrite('output/imagegen/redesign-oil-grain-20261019/test_farmers_integrated.png', field_bgr)
print("Farmers and unified wheat saved.")
