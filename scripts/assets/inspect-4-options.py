import cv2
import numpy as np
from PIL import Image

# 1. Opt 1: grainfield_option_one
im1 = Image.open('output/imagegen/redesign-oil-grain-20261019/grainfield_option_one_1792391934850.jpg')
print("Opt 1 size:", im1.size)

# 2. Opt 2: golden waves
im2 = Image.open('output/imagegen/redesign-oil-grain-20261019/grainfield_golden_waves_1792391882826.jpg')
print("Opt 2 size:", im2.size)

# 3. Opt 3: option two (tractor harvester)
im3 = Image.open('output/imagegen/redesign-oil-grain-20261019/grainfield_option_two_1792391963423.jpg')
print("Opt 3 size:", im3.size)

# 4. Opt 4: garden / frontier farm
im4 = Image.open('output/imagegen/realistic-buildings-20260917/a-garden/farm.png')
print("Opt 4 size:", im4.size)

