import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# 1. Load base orthogonal diorama
base_img = cv2.imread('output/imagegen/terrain-orthogonal-20261003/raw/wild-grainfield.png')
h, w = base_img.shape[:2]

# 2. Load golden waves with fine wheat and farmers
waves_img = cv2.imread('output/imagegen/redesign-oil-grain-20261019/grainfield_golden_waves_1792391882826.jpg')
# Resize waves to fit the wheat area
waves_resized = cv2.resize(waves_img, (w, h))

# 3. Create polygon mask for the wheat field in base_img
# We want to replace the ribbed curved wheat with the contiguous fine wheat & farmers
# The wheat field is from x=70 to x=720, y=340 to y=940
mask = np.zeros((h, w), dtype=np.uint8)
pts = np.array([
    [90, 420], [180, 350], [280, 330], [480, 330], [600, 360], 
    [710, 420], [740, 520], [680, 600], [580, 720], [540, 940], 
    [80, 940], [80, 460]
], dtype=np.int32)
cv2.fillPoly(mask, [pts], 255)

# Soften mask edges for seamless blending
mask_blur = cv2.GaussianBlur(mask, (31, 31), 0)
mask_norm = mask_blur.astype(float) / 255.0
mask_3d = np.dstack([mask_norm, mask_norm, mask_norm])

# Crop texture from waves_img:
# Let's pick the central golden wheat with farmers:
# In waves_img, x in [100, 800], y in [200, 800] has beautiful continuous golden wheat and farmers!
wheat_crop = waves_img[180:880, 100:800]
# Resize wheat_crop to match the bounding box of the wheat mask:
x_min, y_min = 70, 320
x_max, y_max = 760, 950
crop_w = x_max - x_min
crop_h = y_max - y_min
wheat_patch = cv2.resize(wheat_crop, (crop_w, crop_h))

# Adjust color of wheat_patch to match base_img's warm golden tones
patch_hsv = cv2.cvtColor(wheat_patch, cv2.COLOR_BGR2HSV)
patch_hsv[:, :, 1] = np.clip(patch_hsv[:, :, 1] * 1.1, 0, 255) # vibrant gold
patch_bgr = cv2.cvtColor(patch_hsv, cv2.COLOR_HSV2BGR)

# Paste into an empty image matching base_img
patch_full = base_img.copy()
patch_full[y_min:y_max, x_min:x_max] = patch_bgr

# Seamless clone / alpha blend
blended = (patch_full * mask_3d + base_img * (1 - mask_3d)).astype(np.uint8)

cv2.imwrite('output/imagegen/redesign-oil-grain-20261019/test_composite_farmers.png', blended)
print("Composite saved to test_composite_farmers.png")
