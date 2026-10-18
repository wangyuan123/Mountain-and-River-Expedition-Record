#!/usr/bin/env python3
"""
测试根据实机基准色对 Candidate 3 进行精确色相映射与宽幅有机双色不规则边缘羽化
"""

from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / 'output/imagegen/grass-redesign-20261015'
C3_PATH = Path('/Users/chenjuan/.gemini/antigravity/brain/20ce01fc-7161-4686-a4ee-b8048647ef66/square_lawn_natural_1792033624797.jpg')
REAL_MAP_TILE = OUT_DIR / 'real_map_tile.png'

TARGET_SOIL = np.array([90.5, 85.9, 64.8], dtype=np.float32)   # 实机黑土基准色
TARGET_GRASS = np.array([104.7, 104.9, 73.6], dtype=np.float32) # 实机现有草坪基准色


def main():
    c3 = Image.open(C3_PATH).convert('RGB')
    rgb = np.array(c3, dtype=np.float32)
    h, w = rgb.shape[:2]
    
    # 1. 分割摄影棚灰白背景 (背景均值 [224.6, 224.6, 224.4])
    bg_color = np.array([224.6, 224.6, 224.4], dtype=np.float32)
    diff = np.linalg.norm(rgb - bg_color, axis=2)
    
    # 获取前景区域
    fg_mask = (diff > 18.0).astype(np.float32)
    
    # 2. 裁切主体 bounding box
    bbox_ys, bbox_xs = np.where(fg_mask > 0.5)
    min_x, max_x = bbox_xs.min(), bbox_xs.max()
    min_y, max_y = bbox_ys.min(), bbox_ys.max()
    
    cropped_rgb = rgb[min_y:max_y+1, min_x:max_x+1]
    cropped_diff = diff[min_y:max_y+1, min_x:max_x+1]
    
    # 缩放到标准 384x384 (留出 40px 呼吸羽化空间，主体约 304x304)
    target_side = 304
    resized_rgb = cv2.resize(cropped_rgb, (target_side, target_side), interpolation=cv2.INTER_LANCZOS4)
    resized_diff = cv2.resize(cropped_diff, (target_side, target_side), interpolation=cv2.INTER_LANCZOS4)
    
    # 3. 精确色阶映射 (Color Remapping & Calibration)
    # 原图黑土过黑 ([10, 15, 10])，草地过绿
    # 提取原图亮度 L
    gray = cv2.cvtColor(resized_rgb.astype(np.uint8), cv2.COLOR_RGB2GRAY).astype(np.float32) / 255.0
    
    # 将极暗部（原图黑泥 L < 0.25）提升并映射到实机黑土色 TARGET_SOIL [90.5, 85.9, 64.8]
    # 将中等亮度（原图浅地表 0.25 < L < 0.45）平滑映射到 TARGET_GRASS [104.7, 104.9, 73.6]
    # 将高光草尖（L > 0.45）映射为自然的微透光草叶绿相 [124, 130, 82]
    
    calibrated_rgb = np.zeros_like(resized_rgb)
    for c in range(3):
        soil_c = TARGET_SOIL[c]
        grass_c = TARGET_GRASS[c]
        highlight_c = grass_c + 20.0  # 微亮草尖
        
        # 3 段曲线插值
        t_soil = np.clip(gray / 0.28, 0.0, 1.0)
        t_grass = np.clip((gray - 0.28) / 0.30, 0.0, 1.0)
        
        # 基础地表基准
        base = soil_c * (1.0 - t_soil) + grass_c * t_soil
        full = base * (1.0 - t_grass) + highlight_c * t_grass
        
        # 融合原画真实高频微细节（纹理波动）
        detail = (resized_rgb[:, :, c] - np.mean(resized_rgb[:, :, c])) * 0.45
        calibrated_rgb[:, :, c] = np.clip(full + detail, 0, 255)
        
    # 4. 生成 384x384 正交画布，居中嵌入
    full_rgb = np.zeros((384, 384, 3), dtype=np.float32)
    full_alpha = np.zeros((384, 384), dtype=np.float32)
    
    offset_x = (384 - target_side) // 2
    offset_y = (384 - target_side) // 2
    
    # 5. 边缘不规则处理与双色有机交替
    # 中心坐标
    cx, cy = 192.0, 192.0
    yy, xx = np.mgrid[:384, :384].astype(np.float32)
    
    # 归一化正方格外形坐标 (边长 ~ 280)
    half_box = 138.0
    u = (xx - cx) / half_box
    v = (yy - cy) / half_box
    d_box = np.maximum(np.abs(u), np.abs(v))
    theta = np.arctan2(v, u)
    
    # 有机谐波扰动：让四边产生大起伏与细锯齿
    perturb = (
        0.09 * np.sin(theta * 4.0 + 0.3) +
        0.06 * np.sin(theta * 7.0 - 0.9) +
        0.04 * np.cos(theta * 11.0 + 1.7)
    )
    
    d_eff = d_box - perturb
    
    # 软羽化带：d_eff 从 0.65 到 1.02，过渡宽度约 45px，保证极度柔和
    feather_in, feather_out = 0.68, 1.02
    t_fade = np.clip((feather_out - d_eff) / (feather_out - feather_in), 0.0, 1.0)
    alpha_mask = t_fade * t_fade * (3.0 - 2.0 * t_fade)
    
    # 将主体区域填入
    full_rgb[offset_y:offset_y+target_side, offset_x:offset_x+target_side] = calibrated_rgb
    
    # 在边缘羽化区（alpha 从 0.0 到 0.9），强制将地表颜色平滑过渡到【黑土与现有草坪的双色有机组合】
    # 生成局部双色混合噪声（模拟实机地图中黑土与草坪的交替）
    edge_mix_noise = (np.sin(xx / 18.0) * np.cos(yy / 18.0) + 1.0) / 2.0
    edge_blend_color = np.zeros((384, 384, 3), dtype=np.float32)
    for c in range(3):
        edge_blend_color[:, :, c] = TARGET_GRASS[c] * (1.0 - edge_mix_noise) + TARGET_SOIL[c] * edge_mix_noise
        
    # 边缘颜色与主体平滑混合
    edge_weight = np.clip((1.0 - alpha_mask) * 1.5, 0.0, 1.0)
    for c in range(3):
        full_rgb[:, :, c] = full_rgb[:, :, c] * (1.0 - edge_weight) + edge_blend_color[:, :, c] * edge_weight
        
    full_alpha = alpha_mask * 255.0
    
    # 组合为 RGBA
    result_rgba = Image.fromarray(np.dstack([
        np.clip(full_rgb, 0, 255).astype(np.uint8),
        np.clip(full_alpha, 0, 255).astype(np.uint8)
    ]))
    
    result_rgba.save(OUT_DIR / 'calibrated_dual_color_lawn.png')
    print("Saved calibrated_dual_color_lawn.png")
    
    # 在实机地图上测试单地块和连片地块
    map_tile = Image.open(REAL_MAP_TILE).convert('RGB')
    test_map = Image.new('RGB', (800, 600))
    for my in range(0, 600, map_tile.height):
        for mx in range(0, 800, map_tile.width):
            test_map.paste(map_tile, (mx, my))
            
    # 左侧放单片
    scaled_tile = result_rgba.resize((300, 300), Image.Resampling.LANCZOS)
    test_map.paste(scaled_tile, (80, 150), scaled_tile)
    
    # 右侧放 2x2 连片拼合
    small_tile = result_rgba.resize((190, 190), Image.Resampling.LANCZOS)
    test_map.paste(small_tile, (430, 90), small_tile)
    test_map.paste(small_tile, (585, 90), small_tile)
    test_map.paste(small_tile, (430, 245), small_tile)
    test_map.paste(small_tile, (585, 245), small_tile)
    
    test_map.save(OUT_DIR / 'calibrated_on_real_map.jpg', quality=95)
    print("Saved calibrated_on_real_map.jpg")


if __name__ == '__main__':
    main()
