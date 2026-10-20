import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

# 1. Load raw wild-grainfield
raw_path = 'output/imagegen/terrain-orthogonal-20261003/raw/wild-grainfield.png'
img_bgr = cv2.imread(raw_path)
h, w = img_bgr.shape[:2]

# 2. Smooth out the furrows/ridges (消除垄沟壑间隔，变成成片金黄麦田)
# Let's inspect the wheat mask:
# Wheat has high yellow/amber content: Red > 130, Green > 100, Blue < 110
img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
r, g, b = img_rgb[:, :, 0], img_rgb[:, :, 1], img_rgb[:, :, 2]

# Wheat field bounding region: x in [70, 920], y in [310, 960]
wheat_region = np.zeros((h, w), dtype=bool)
# Polygon defining the wheat field inside the diorama
pts = np.array([
    [80, 390], [280, 310], [520, 320], [800, 380], 
    [930, 480], [700, 560], [600, 720], [560, 940], 
    [70, 940], [70, 400]
], dtype=np.int32)
cv2.fillPoly(wheat_region.astype(np.uint8), [pts], 1)
wheat_region = wheat_region.astype(bool)

# In the wheat region, identify the dark furrow shadow lines:
# Furrows are dark: lightness < 120 or (r < 130 and g < 110)
gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
is_furrow = wheat_region & (gray < 130)

# Morphological closing / inpainting on furrows to bridge the gaps with golden wheat
inpainted_wheat = cv2.inpaint(img_bgr, is_furrow.astype(np.uint8) * 255, inpaintRadius=9, flags=cv2.INPAINT_TELEA)

# Blend inpainted with golden texture:
# To make it "成片麦子，稀疏自然，不呈块状波浪"
# Add a fine golden grain texture to the wheat area
wheat_tex = cv2.bilateralFilter(inpainted_wheat, 9, 75, 75)
alpha_blend = np.zeros((h, w, 3), dtype=float)
alpha_blend[wheat_region] = 0.7
smoothed_bgr = (inpainted_wheat * (1 - alpha_blend) + wheat_tex * alpha_blend).astype(np.uint8)

# Enhance golden saturation in wheat region
hsv = cv2.cvtColor(smoothed_bgr, cv2.COLOR_BGR2HSV)
# Increase saturation and slightly brighten the wheat
hsv_wheat = hsv.copy()
hsv_wheat[:, :, 1] = np.where(wheat_region, np.clip(hsv[:, :, 1] * 1.25, 0, 255), hsv[:, :, 1])
hsv_wheat[:, :, 2] = np.where(wheat_region, np.clip(hsv[:, :, 2] * 1.08, 0, 255), hsv[:, :, 2])
golden_bgr = cv2.cvtColor(hsv_wheat, cv2.COLOR_HSV2BGR)

# Save intermediate result
cv2.imwrite('output/imagegen/redesign-oil-grain-20261019/test_wheat_smoothed.png', golden_bgr)
print("Smoothed wheat saved.")
