import cv2
import numpy as np
from PIL import Image

def get_clean_mask(img_path):
    img = cv2.imread(str(img_path))
    h, w = img.shape[:2]
    
    # Background color is around [200, 204, 207] in BGR
    # Calculate color distance to studio background
    # Let's sample 4 corners
    corners = np.vstack([
        img[:30, :30].reshape(-1, 3),
        img[:30, -30:].reshape(-1, 3),
        img[-30:, :30].reshape(-1, 3),
        img[-30:, -30:].reshape(-1, 3)
    ])
    bg_mean = np.mean(corners, axis=0)
    
    # Distance to bg_mean
    diff = np.linalg.norm(img.astype(float) - bg_mean, axis=2)
    
    # The diorama itself has colors very distinct from bg_mean (green grass, yellow crops, brown wood, red brick, dark steel)
    # Background diff is < 25 (including soft shadow)
    # Let's floodfill from borders
    mask = np.zeros((h + 2, w + 2), np.uint8)
    
    # Create binary mask of background candidates
    # Any pixel with diff < 22 is potential background
    is_bg = (diff < 22).astype(np.uint8)
    
    # Floodfill from (0,0), (w-1,0), (0,h-1), (w-1,h-1)
    # using cv2.floodFill
    flooded = np.zeros((h, w), np.uint8)
    # Check borders
    for y in range(0, h, 20):
        for x in [0, w - 1]:
            if is_bg[y, x] and not flooded[y, x]:
                cv2.floodFill(is_bg, None, (x, y), 2)
    for x in range(0, w, 20):
        for y in [0, h - 1]:
            if is_bg[y, x] and not flooded[y, x]:
                cv2.floodFill(is_bg, None, (x, y), 2)
                
    bg_mask = (is_bg == 2)
    
    # Foreground mask is inverse
    fg_mask = (~bg_mask).astype(np.uint8) * 255
    
    # Smooth edges with slight blur
    fg_mask = cv2.GaussianBlur(fg_mask, (5, 5), 0)
    
    # Combine BGR with alpha
    b, g, r = cv2.split(img)
    rgba = cv2.merge([r, g, b, fg_mask])
    return Image.fromarray(rgba)

farm_clean = get_clean_mask('output/imagegen/realistic-buildings-20260917/c-industrial/farm.png')
farm_clean.save('output/imagegen/redesign-oil-grain-20261019/farm_clean_test.png')

oil_clean = get_clean_mask('output/imagegen/realistic-buildings-20260917/b-frontier/oilfield.png')
oil_clean.save('output/imagegen/redesign-oil-grain-20261019/oil_clean_test.png')

print('Clean cut done')
