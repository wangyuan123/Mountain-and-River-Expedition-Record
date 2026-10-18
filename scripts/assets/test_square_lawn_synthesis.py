#!/usr/bin/env python3
"""
测试正交方格草坪的程序化有机融合生成：
- 正方形分布结构（填满4个象限，杜绝菱形）
- 边缘不规则锯齿与自然起伏轮廓
- 底面与边缘严格融合实机黑土色 [93.6, 88.4, 67.7] 与 现有草坪色 [103.6, 103.7, 72.6]
- 边缘草尖穿透与柔化羽化
"""

import math
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / 'output/imagegen/grass-redesign-20261015'
USER_SCREENSHOT_PATH = Path('/Users/chenjuan/.gemini/antigravity/brain/20ce01fc-7161-4686-a4ee-b8048647ef66/.user_uploaded/media_1792035557500_4ea393df.png')
ATLAS_PATH = ROOT / 'output/imagegen/grass-models/atlas.png'


def main():
    # 1. 读取用户截图并提取纯净的黑土与草坪基准色和真实纹理
    u2 = Image.open(USER_SCREENSHOT_PATH).convert('RGB')
    u2_arr = np.array(u2, dtype=np.float32)
    
    # 黑土区域 (左红框内): x: 50~120, y: 130~210
    soil_sample = u2_arr[130:210, 50:120]
    soil_mean = soil_sample.mean(axis=(0, 1))  # ~ [93.6, 88.4, 67.7]
    
    # 草坪区域 (右红框内): x: 440~540, y: 130~250
    grass_sample = u2_arr[130:250, 440:540]
    grass_mean = grass_sample.mean(axis=(0, 1)) # ~ [103.6, 103.7, 72.6]
    
    print(f"Soil Mean: {soil_mean}, Grass Mean: {grass_mean}")
    
    # 2. 从 atlas 提取纯净草丛切片
    atlas = Image.open(ATLAS_PATH).convert('RGBA')
    w_at, h_at = atlas.size
    q2 = atlas.crop((w_at // 2, 0, w_at, h_at // 2))
    q1 = atlas.crop((0, 0, w_at // 2, h_at // 2))
    
    # 清理 alpha
    def clean_quadrant(q):
        arr = np.array(q)
        alpha = arr[:, :, 3]
        clean_alpha = np.round(255 * np.clip((alpha.astype(float) - 105) / 140.0, 0, 1) ** 1.3).astype(np.uint8)
        res = q.copy()
        res.putalpha(Image.fromarray(clean_alpha))
        return res
        
    q2_clean = clean_quadrant(q2)
    q1_clean = clean_quadrant(q1)
    
    # 截取多个形态各异的高分辨率草簇（用于在正方形四个角、中心及边缘铺设）
    tuft_dict = {
        'center_dense': q2_clean.crop((320, 200, 450, 310)),
        'lush_peak': q1_clean.crop((280, 180, 420, 300)),
        'corner_tuft1': q2_clean.crop((220, 190, 330, 280)),
        'corner_tuft2': q2_clean.crop((430, 190, 540, 280)),
        'edge_blade_top': q2_clean.crop((340, 130, 430, 210)),
        'edge_blade_bot': q2_clean.crop((340, 300, 430, 380)),
        'small_scatter': q1_clean.crop((460, 220, 520, 280)),
    }
    
    # 3. 生成 384x384 画布
    size = 384
    cx, cy = 192.0, 192.0
    
    # 生成多倍频柏林噪声，用于黑土与草坪基底的有机斑块分布
    def make_noise(s, scale, octaves=3):
        noise = np.zeros((s, s), dtype=np.float32)
        amp = 1.0
        total = 0.0
        for i in range(octaves):
            gw = max(2, int(s / (scale / (2**i))))
            grid = np.random.uniform(0, 1, (gw, gw)).astype(np.float32)
            layer = cv2.resize(grid, (s, s), interpolation=cv2.INTER_CUBIC)
            noise += layer * amp
            total += amp
            amp *= 0.5
        return noise / total
        
    np.random.seed(888)
    broad_noise = make_noise(size, 85, 3)
    fine_noise = make_noise(size, 25, 3)
    grain = np.random.normal(0, 3.8, (size, size, 3)).astype(np.float32)
    
    # 底面权重：0 为现有草坪基底，1 为黑土地斑块
    # 斑块分布不均匀：左侧和局部形成斑驳深土，草坪间杂露出
    soil_weight = np.clip((broad_noise * 0.65 + fine_noise * 0.35 - 0.45) * 4.0 + 0.5, 0.0, 1.0)
    
    ground_rgb = np.zeros((size, size, 3), dtype=np.float32)
    for c in range(3):
        ground_rgb[:, :, c] = grass_mean[c] * (1.0 - soil_weight) + soil_mean[c] * soil_weight
    ground_rgb = np.clip(ground_rgb + grain, 0, 255)
    
    # 4. 正方形几何轮廓 + 不规则边缘遮罩
    # 半宽约 125px (正方形范围约 250x250)
    half_box = 125.0
    yy, xx = np.mgrid[:size, :size].astype(np.float32)
    u = (xx - cx) / half_box
    v = (yy - cy) / half_box
    
    # 正交方格外轮廓距离场：d_box = max(|u|, |v|)
    d_box = np.maximum(np.abs(u), np.abs(v))
    theta = np.arctan2(v, u)
    
    # 边缘不规则多频谐波扰动：使四边产生自然起伏与草尖凹凸
    edge_noise = make_noise(size, 32, 2)
    edge_perturb = (
        0.08 * np.sin(theta * 4.0 + 0.4) +
        0.06 * np.sin(theta * 8.0 - 1.1) +
        0.04 * np.cos(theta * 12.0 + 1.8) +
        0.09 * (edge_noise - 0.5)
    )
    
    d_effective = d_box - edge_perturb
    
    # 软羽化过渡带：在 0.72 ~ 1.05 之间平滑过渡
    feather_in = 0.70
    feather_out = 1.04
    t_f = np.clip((feather_out - d_effective) / (feather_out - feather_in), 0.0, 1.0)
    alpha_ground = t_f * t_f * (3.0 - 2.0 * t_f) * 255.0
    
    ground_rgba = Image.fromarray(np.dstack([ground_rgb.astype(np.uint8), alpha_ground.astype(np.uint8)]))
    
    # 5. 在正方形区域内，按正方形四象限排布草簇（彻底打破菱形，铺满正方形）
    lawn_canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    lawn_canvas.alpha_composite(ground_rgba)
    
    # 布设草簇点位（X, Y, tuft_key, scale, flip）
    # 中心、四角（左上、右上、左下、右下）、四边中点，形成饱满的正方形地块分布
    layout_points = [
        # 中心区
        (cx - 30, cy - 25, 'center_dense', 1.05, False),
        (cx + 15, cy - 10, 'lush_peak', 0.95, True),
        # 四个角（彻底填充正方格外形，杜绝菱形斜角收窄）
        (cx - 95, cy - 85, 'corner_tuft1', 0.88, False),   # 左上角
        (cx + 25, cy - 90, 'corner_tuft2', 0.85, True),    # 右上角
        (cx - 90, cy + 20, 'corner_tuft2', 0.85, False),   # 左下角
        (cx + 20, cy + 25, 'corner_tuft1', 0.90, True),    # 右下角
        # 四边中段（增强外轮廓不规则草尖）
        (cx - 35, cy - 105, 'edge_blade_top', 0.80, False), # 上边缘
        (cx - 30, cy + 50, 'edge_blade_bot', 0.82, True),   # 下边缘
        (cx - 105, cy - 30, 'corner_tuft1', 0.78, True),    # 左边缘
        (cx + 50, cy - 30, 'corner_tuft2', 0.78, False),    # 右边缘
        # 散落微草丛（丰富细节）
        (cx - 50, cy - 50, 'small_scatter', 0.9, False),
        (cx + 40, cy + 10, 'small_scatter', 1.0, True),
        (cx - 10, cy + 40, 'small_scatter', 0.85, False),
    ]
    
    vegetation_layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for px, py, key, scl, flip in layout_points:
        t = tuft_dict[key].copy()
        if flip:
            t = t.transpose(Image.FLIP_LEFT_RIGHT)
        if scl != 1.0:
            nw = int(round(t.width * scl))
            nh = int(round(t.height * scl))
            t = t.resize((nw, nh), Image.Resampling.LANCZOS)
        vegetation_layer.paste(t, (int(px), int(py)), t)
        
    # 对植物层边缘与整体羽化掩模做平滑限制，但允许草尖微探出
    veg_arr = np.array(vegetation_layer, dtype=np.float32)
    extended_alpha = np.clip(alpha_ground * 1.35 + 15.0, 0, 255)
    veg_arr[:, :, 3] = np.minimum(veg_arr[:, :, 3], extended_alpha)
    vegetation_layer = Image.fromarray(veg_arr.astype(np.uint8))
    
    # 复合到底面
    lawn_canvas.alpha_composite(vegetation_layer)
    
    lawn_canvas.save(OUT_DIR / 'test_organic_square_lawn.png')
    print("Generated test_organic_square_lawn.png successfully.")
    
    # 6. 在实机地图背景上测试融合效果
    map_tile_path = OUT_DIR / 'real_map_tile.png'
    map_tile = Image.open(map_tile_path).convert('RGB')
    
    preview_bg = Image.new('RGB', (600, 450))
    for my in range(0, 450, map_tile.height):
        for mx in range(0, 600, map_tile.width):
            preview_bg.paste(map_tile, (mx, my))
            
    # 居中放置新草坪
    test_lawn = lawn_canvas.resize((320, 320), Image.Resampling.LANCZOS)
    preview_bg.paste(test_lawn, ((600 - 320) // 2, (450 - 320) // 2), test_lawn)
    
    preview_bg.save(OUT_DIR / 'test_organic_on_map.jpg', quality=95)
    print("Saved test_organic_on_map.jpg successfully.")


if __name__ == '__main__':
    main()
