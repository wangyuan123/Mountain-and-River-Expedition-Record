#!/usr/bin/env python3
"""
将 gpt-image-2 生成的 3D 微缩要塞城墙原图处理为高质量带透明通道的 WebP 资产：
- tactical-city-wall-left.webp (城门朝左，立于右侧，攻方视角)
- tactical-city-wall-right.webp (城门朝右，立于左侧，守方视角)
- player-city-wall-front.webp (兼容更新)
"""

from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
RAW_PATH = ROOT / 'output/imagegen/tactical-wall-20261014/raw/tactical_wall_raw.png'
DEST_DIR = ROOT / 'frontend/img/cities'

def process_wall_model():
    if not RAW_PATH.exists():
        raise FileNotFoundError(f"Raw image not found: {RAW_PATH}")
    
    im_raw = Image.open(RAW_PATH).convert('RGB')
    rgb = np.array(im_raw)
    
    # 纯白背景精准剔除
    diff_white = 255.0 - rgb.astype(np.float32)
    dist = np.linalg.norm(diff_white, axis=2)
    # 柔和 Alpha 阈值
    alpha = np.clip((dist - 14.0) / 22.0 * 255.0, 0, 255).astype(np.uint8)
    alpha = cv2.GaussianBlur(alpha, (3, 3), 0.7)
    
    rgba = np.dstack([rgb, alpha])
    im_rgba = Image.fromarray(rgba, 'RGBA')
    
    # 找到有效主体边界
    bbox = im_rgba.getbbox()
    print("Detected subject bbox:", bbox)
    cropped = im_rgba.crop(bbox)
    print("Cropped model size:", cropped.size)
    
    # 调整为符合指挥面板高度比例的高精竖直长条 (360 x 960)
    # 保持垂直高度铺满，纵横比合适
    target_w, target_h = 360, 960
    # 先等比缩放高度到 960
    scale = target_h / cropped.height
    new_w = int(cropped.width * scale)
    scaled = cropped.resize((new_w, target_h), Image.Resampling.LANCZOS)
    
    # 放置在 360x960 画布上
    # 朝左城墙：城墙右侧平贴右边缘（城内侧），左侧向战场内部展开（正面朝左）
    wall_left = Image.new('RGBA', (target_w, target_h), (0, 0, 0, 0))
    if new_w >= target_w:
        # 取左侧和中央主体部分
        paste_x = target_w - new_w
        wall_left.paste(scaled, (paste_x, 0), scaled)
    else:
        # 靠右贴齐
        paste_x = target_w - new_w
        wall_left.paste(scaled, (paste_x, 0), scaled)
        
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    
    # 保存左向版本（城门朝左，立于指挥面板右侧，攻方视角）
    left_path = DEST_DIR / 'tactical-city-wall-left.webp'
    wall_left.save(left_path, format='WEBP', quality=95, method=6)
    print(f"Saved Left-facing 3D Model: {left_path} ({left_path.stat().st_size} bytes)")
    
    # 水平镜像生成右向版本（城门朝右，立于指挥面板左侧，守方视角）
    wall_right = wall_left.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    right_path = DEST_DIR / 'tactical-city-wall-right.webp'
    wall_right.save(right_path, format='WEBP', quality=95, method=6)
    print(f"Saved Right-facing 3D Model: {right_path} ({right_path.stat().st_size} bytes)")
    
    # 兼容原路径
    compat_path = DEST_DIR / 'player-city-wall-front.webp'
    wall_left.save(compat_path, format='WEBP', quality=95, method=6)
    print(f"Updated Compatibility asset: {compat_path} ({compat_path.stat().st_size} bytes)")

if __name__ == '__main__':
    process_wall_model()
