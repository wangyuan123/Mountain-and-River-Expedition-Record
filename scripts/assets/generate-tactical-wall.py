#!/usr/bin/env python3
"""
生成战术指挥面板全高城墙图案资产：
- tactical-city-wall-left.webp: 城门朝左，正面朝左（攻方视角，放置于指挥面板右侧敌军后方）
- tactical-city-wall-right.webp: 城门朝右，正面朝右（守方视角，放置于指挥面板左侧我军后方）
- player-city-wall-front.webp: 兼容性更新

基于官方 1254x1254 原画微缩模型真实纹理与现代军事要塞几何体系融合渲染。
"""

import os
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = ROOT / 'frontend/img/cities'
SOURCE_IMG = ROOT / 'output/imagegen/fortified-cities-20260914/c-garden-citadel-v2.png'
PLAYER_CITY_IMG = ROOT / 'output/imagegen/player-city-fortress-20261009/raw/player_city.png'

def remove_background(im, bg_color=(219, 211, 203), threshold=22, feather=4):
    """根据工作室背景色扣出带透明通道的物体"""
    arr = np.array(im.convert('RGB'))
    diff = np.linalg.norm(arr.astype(np.float32) - np.array(bg_color, dtype=np.float32), axis=2)
    alpha = np.clip((diff - threshold) / (threshold * 1.5) * 255, 0, 255).astype(np.uint8)
    # 形态学/平滑
    alpha_img = Image.fromarray(alpha).filter(ImageFilter.GaussianBlur(feather))
    result = im.convert('RGBA')
    result.putalpha(alpha_img)
    return result

def create_photoreal_tactical_wall(target_w=360, target_h=960):
    """
    融合官方高精度微缩模型原画材质，生成真实感极强的全高要塞城墙（朝左）。
    """
    # 最终画布
    final_canvas = Image.new('RGBA', (target_w, target_h), (0, 0, 0, 0))
    
    # 1. 尝试从原画中抽取城墙真实素材
    cg = Image.open(SOURCE_IMG) if SOURCE_IMG.exists() else None
    
    # 基础结构绘制
    draw = ImageDraw.Draw(final_canvas)

    c_limestone_lit = (226, 220, 204, 255)
    c_limestone_mid = (194, 186, 170, 255)
    c_limestone_dark = (142, 134, 118, 255)
    c_concrete_base = (162, 156, 142, 255)
    c_walkway = (128, 124, 114, 255)
    c_shadow = (25, 28, 32, 120)
    c_iron_gate = (54, 58, 64, 255)
    c_roof_tile = (165, 75, 62, 255)
    c_slit = (24, 22, 20, 255)
    
    # 填充城墙全高通长主体（X: 110 ~ 360，Y: 0 ~ 960）
    for y in range(target_h):
        # 墙面随高度自然起伏
        wave = int(np.sin(y / 60.0) * 3)
        # 基座缓坡（x: 80 ~ 140）
        for x in range(80 + wave, 140 + wave):
            t = (x - (80 + wave)) / 60.0
            r = int(c_concrete_base[0] * t + 115 * (1 - t))
            g = int(c_concrete_base[1] * t + 120 * (1 - t))
            b = int(c_concrete_base[2] * t + 105 * (1 - t))
            alpha = int(255 * min(1.0, t * 1.8))
            final_canvas.putpixel((x, y), (r, g, b, alpha))
            
        # 立面主体（x: 140 ~ 210）
        for x in range(140 + wave, 210 + wave):
            t = (x - (140 + wave)) / 70.0
            r = int(c_limestone_lit[0] * (1 - t*0.3))
            g = int(c_limestone_lit[1] * (1 - t*0.3))
            b = int(c_limestone_lit[2] * (1 - t*0.3))
            final_canvas.putpixel((x, y), (r, g, b, 255))
            
        # 顶部步道与内侧平台（x: 210 ~ target_w）
        for x in range(210 + wave, target_w):
            t = (x - (210 + wave)) / (target_w - 210.0)
            r = int(c_walkway[0] * (1 - t) + c_limestone_dark[0] * t)
            g = int(c_walkway[1] * (1 - t) + c_limestone_dark[1] * t)
            b = int(c_walkway[2] * (1 - t) + c_limestone_dark[2] * t)
            final_canvas.putpixel((x, y), (r, g, b, 255))

    # 如果有官方原画，采样真实高精花岗岩切片叠加
    if cg:
        try:
            # 提取西侧城墙的真实石块质感（180x800）
            raw_west = cg.crop((85, 230, 265, 1030))
            raw_west = raw_west.resize((140, target_h), Image.Resampling.LANCZOS)
            # 转换为灰度纹理作为质感贴图
            west_gray = raw_west.convert('L')
            west_arr = np.array(west_gray).astype(np.float32) / 128.0  # 均值约 1.0
            
            # 将纹理混合到主墙体立面
            c_arr = np.array(final_canvas)
            blend_x_start = 120
            blend_x_end = 260
            for i in range(blend_x_end - blend_x_start):
                tex_col = west_arr[:, min(i, 139)]
                for ch in range(3):
                    c_arr[:, blend_x_start + i, ch] = np.clip(
                        c_arr[:, blend_x_start + i, ch].astype(np.float32) * (0.55 + 0.45 * tex_col), 0, 255
                    ).astype(np.uint8)
            final_canvas = Image.fromarray(c_arr, 'RGBA')
            draw = ImageDraw.Draw(final_canvas)
        except Exception as e:
            print(f"Texture blend skipped: {e}")

    # 2. 绘制立体城垛（Crenellations）沿着正面纵向分布
    crenel_h = 20
    gap_h = 14
    step = crenel_h + gap_h
    for y_start in range(12, target_h - 12, step):
        # 避开中央大门楼 (Y: 380 ~ 580)
        if 375 <= y_start <= 585:
            continue
        y_end = min(y_start + crenel_h, target_h - 12)
        wave = int(np.sin(y_start / 60.0) * 3)
        # 城垛凸起方块（x: 104 ~ 142）
        box = [104 + wave, y_start, 142 + wave, y_end]
        draw.rectangle(box, fill=c_limestone_lit, outline=c_limestone_dark)
        # 上缘亮线与底部阴影
        draw.line([104 + wave, y_start, 142 + wave, y_start], fill=(255, 252, 245, 255), width=2)
        draw.line([104 + wave, y_end, 142 + wave, y_end], fill=c_limestone_dark, width=2)
        # 射击孔
        my = (y_start + y_end) // 2
        draw.rectangle([116 + wave, my - 2, 128 + wave, my + 2], fill=c_slit)

    # 3. 砌石水平接缝与混凝土防裂缝
    for y in range(0, target_h, 16):
        wave = int(np.sin(y / 60.0) * 3)
        draw.line([138 + wave, y, 210 + wave, y], fill=(135, 128, 115, 160), width=1)

    # 4. 中央宏伟大要塞主城门楼（Bastion Main Gatehouse, Y: 370 ~ 590）
    gate_top = 370
    gate_bottom = 590
    gate_mid_y = (gate_top + gate_bottom) // 2
    
    # 门楼大基座凸出（向左延伸至 x: 35）
    draw.polygon([
        (42, gate_mid_y - 85),
        (30, gate_mid_y),
        (42, gate_mid_y + 85),
        (135, gate_bottom),
        (240, gate_bottom),
        (240, gate_top),
        (135, gate_top)
    ], fill=c_concrete_base, outline=c_limestone_dark)

    # 门楼中央花岗岩主墙立面
    draw.rectangle([58, gate_top + 18, 225, gate_bottom - 18], fill=c_limestone_mid, outline=c_limestone_dark)
    # 受光面亮边
    draw.line([58, gate_top + 18, 225, gate_top + 18], fill=(255, 250, 240, 255), width=3)
    draw.line([58, gate_top + 18, 58, gate_bottom - 18], fill=(252, 246, 235, 255), width=3)

    # 双翼圆形防御塔楼（Flanking Turrets）
    turret_r = 40
    # 上塔楼
    draw.ellipse([64 - turret_r, gate_top + 38 - turret_r, 64 + turret_r, gate_top + 38 + turret_r],
                 fill=c_limestone_lit, outline=c_limestone_dark)
    draw.ellipse([64 - 28, gate_top + 38 - 28, 64 + 28, gate_top + 38 + 28],
                 fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([30, gate_top + 34, 42, gate_top + 42], fill=c_slit)
    
    # 下塔楼
    draw.ellipse([64 - turret_r, gate_bottom - 38 - turret_r, 64 + turret_r, gate_bottom - 38 + turret_r],
                 fill=c_limestone_lit, outline=c_limestone_dark)
    draw.ellipse([64 - 28, gate_bottom - 38 - 28, 64 + 28, gate_bottom - 38 + 28],
                 fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([30, gate_bottom - 42, 42, gate_bottom - 34], fill=c_slit)

    # 5. 核心重装城门洞与装甲铁门（朝向左方敌军！）
    d_left = 48
    d_right = 140
    d_top = gate_mid_y - 54
    d_bottom = gate_mid_y + 54
    
    # 石券拱门外边框
    draw.rectangle([d_left - 10, d_top - 12, d_right + 8, d_bottom + 12],
                   fill=c_limestone_dark, outline=(245, 240, 225, 255))
    
    # 门洞内部深黑空间
    draw.rectangle([d_left, d_top, d_right, d_bottom], fill=(18, 16, 15, 255))
    
    # 真实厚重双开暗钢装甲大门
    draw.rectangle([d_left + 8, d_top + 5, d_right - 4, d_bottom - 5], fill=c_iron_gate)
    
    # 铁门横向加固槽钢与粗大铆钉
    for dy in range(d_top + 12, d_bottom - 5, 20):
        draw.line([d_left + 8, dy, d_right - 4, dy], fill=(85, 92, 102, 255), width=4)
        draw.line([d_left + 8, dy + 2, d_right - 4, dy + 2], fill=(25, 28, 32, 255), width=1)
        for dx in range(d_left + 16, d_right - 8, 15):
            draw.ellipse([dx - 2, dy - 2, dx + 2, dy + 2], fill=(175, 182, 195, 255))
            
    # 中缝双开锁闭门缝
    draw.line([d_left + 8, gate_mid_y, d_right - 4, gate_mid_y], fill=(15, 16, 18, 255), width=3)

    # 城门上方战备指挥楼与机枪堡（x: 140 ~ 215, y: gate_mid_y - 40 ~ gate_mid_y + 40）
    draw.rectangle([140, gate_mid_y - 42, 215, gate_mid_y + 42], fill=c_limestone_lit, outline=c_limestone_dark)
    # 三联装防空观察窗
    for wy in [-25, -3, 19]:
        draw.rectangle([148, gate_mid_y + wy, 162, gate_mid_y + wy + 9], fill=(20, 24, 28, 255))

    # 6. 顶端与底端要塞封闭多边形角楼
    # 顶部封闭角楼（Y: 0 ~ 50）
    draw.polygon([(80, 0), (165, 0), (175, 50), (70, 50)], fill=c_limestone_lit, outline=c_limestone_dark)
    draw.rectangle([90, 8, 145, 32], fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([75, 22, 86, 28], fill=c_slit)
    
    # 底部封闭角楼（Y: 910 ~ 960）
    draw.polygon([(70, 910), (175, 910), (165, 960), (80, 960)], fill=c_limestone_lit, outline=c_limestone_dark)
    draw.rectangle([90, 928, 145, 952], fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([75, 932, 86, 938], fill=c_slit)

    # 7. 城墙前沿阵地反坦克三角拒马（Czech hedgehogs）与铁丝网工事
    hedgehogs = [
        (46, 110), (52, 190), (40, 270),
        (22, gate_mid_y - 70), (22, gate_mid_y + 70),
        (42, 690), (50, 770), (44, 860)
    ]
    for hx, hy in hedgehogs:
        s = 12
        draw.line([hx - s, hy - s, hx + s, hy + s], fill=(42, 45, 50, 255), width=3)
        draw.line([hx - s, hy + s, hx + s, hy - s], fill=(42, 45, 50, 255), width=3)
        draw.line([hx, hy - s - 2, hx, hy + s + 2], fill=(70, 76, 84, 255), width=3)
        draw.ellipse([hx - s - 2, hy + s - 1, hx + s + 4, hy + s + 4], fill=c_shadow)

    # 8. 边缘羽化与整体色调平衡
    smoothed = final_canvas.filter(ImageFilter.SMOOTH)
    wall_left = Image.blend(final_canvas, smoothed, 0.2)
    
    return wall_left

def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    
    print("Generating ultra-detailed Left-Facing Tactical Wall (360x960)...")
    wall_left = create_photoreal_tactical_wall(360, 960)
    
    path_left = OUTPUT_DIR / 'tactical-city-wall-left.webp'
    wall_left.save(path_left, format='WEBP', quality=95, method=6)
    print(f"-> Successfully saved: {path_left}")

    print("Generating mirrored Right-Facing Tactical Wall (360x960)...")
    wall_right = wall_left.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    path_right = OUTPUT_DIR / 'tactical-city-wall-right.webp'
    wall_right.save(path_right, format='WEBP', quality=95, method=6)
    print(f"-> Successfully saved: {path_right}")

    # 同步更新 player-city-wall-front.webp 保持兼容
    path_compat = OUTPUT_DIR / 'player-city-wall-front.webp'
    wall_left.save(path_compat, format='WEBP', quality=95, method=6)
    print(f"-> Updated compatibility asset: {path_compat}")

if __name__ == '__main__':
    main()
