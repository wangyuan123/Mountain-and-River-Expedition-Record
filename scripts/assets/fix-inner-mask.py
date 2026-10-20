import cv2
import numpy as np
from PIL import Image

def clean_cut_perfect(img_path):
    img = cv2.imread(str(img_path))
    h, w = img.shape[:2]
    
    # Background color is around [200, 204, 207] in BGR
    corners = np.vstack([
        img[:30, :30].reshape(-1, 3),
        img[:30, -30:].reshape(-1, 3),
        img[-30:, :30].reshape(-1, 3),
        img[-30:, -30:].reshape(-1, 3)
    ])
    bg_mean = np.mean(corners, axis=0)
    diff = np.linalg.norm(img.astype(float) - bg_mean, axis=2)
    
    # 1. Outer floodfill
    is_bg = (diff < 22).astype(np.uint8)
    for y in range(0, h, 20):
        for x in [0, w - 1]:
            if is_bg[y, x] == 1:
                cv2.floodFill(is_bg, None, (x, y), 2)
    for x in range(0, w, 20):
        for y in [0, h - 1]:
            if is_bg[y, x] == 1:
                cv2.floodFill(is_bg, None, (x, y), 2)
    
    # 2. Also catch enclosed background pockets (e.g., between pumpjack beam and tower)
    # where diff is very low (< 16) and color is near neutral
    # Check saturation
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    is_inner_bg = (diff < 18) & (sat < 20)
    
    final_bg = (is_bg == 2) | is_inner_bg
    fg_mask = (~final_bg).astype(np.uint8) * 255
    fg_mask = cv2.GaussianBlur(fg_mask, (3, 3), 0)
    
    b, g, r = cv2.split(img)
    rgba = cv2.merge([r, g, b, fg_mask])
    return Image.fromarray(rgba)

oil_clean = clean_cut_perfect('output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png')
oil_clean.save('output/imagegen/redesign-oil-grain-20261019/oil_clean_perfect.png')
print("Inner mask fixed")
