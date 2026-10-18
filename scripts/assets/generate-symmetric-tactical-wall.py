#!/usr/bin/env python3
"""
生成严格以中央城门为对称轴、两侧城墙完全对称、占满指挥面板高度的完整要塞城墙微缩模型图：
- tactical-city-wall-left.webp: 城门朝左，立于指挥面板右侧（攻方视角）
- tactical-city-wall-right.webp: 城门朝右，立于指挥面板左侧（守方视角）
- player-city-wall-front.webp: 兼容性更新

核心设计满足：
1. 完整城墙模型：自顶端棱堡碉堡到底端棱堡碉堡，全封闭无截断。
2. 宽度（面宽）占满高度：高度 100% 贯穿战术面板（自 Y=0 到 Y=960）。
3. 城门严格位于指挥面板垂直正中（Y = 480px，即 50%）。
4. 城门两侧（上方和下方）的城墙以 Y=480 为中心像素级绝对对称。
5. 采用 3D 微缩要塞模型真实质感（花岗岩条石、钢筋混凝土、暗钢防爆装甲大门、红陶瓦顶、三角拒马）。
"""

from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
DEST_DIR = ROOT / 'frontend/img/cities'
SOURCE_IMG = ROOT / 'output/imagegen/fortified-cities-20260914/c-garden-citadel-v2.png'

def generate_symmetric_wall_asset(width=360, height=960):
    mid_y = height // 2  # 480
    gate_half_h = 110    # 门楼半高：Y 从 370 到 480
    
    # 调色板
    c_limestone_lit = (228, 222, 206, 255)
    c_limestone_mid = (196, 188, 172, 255)
    c_limestone_dark = (140, 132, 116, 255)
    c_concrete_base = (162, 156, 142, 255)
    c_walkway = (126, 122, 112, 255)
    c_iron_gate = (50, 54, 60, 255)
    c_iron_beam = (82, 88, 98, 255)
    c_roof_tile = (165, 72, 58, 255)
    c_slit = (24, 22, 20, 255)
    c_hedgehog = (40, 44, 48, 255)

    # 1. 创建上半部分画布 (width x mid_y)
    half_canvas = Image.new('RGBA', (width, mid_y), (0, 0, 0, 0))

    # 绘制上半部的城墙通长基底与主立面
    for y in range(mid_y):
        # 斜面缓坡基脚 (x: 80 ~ 140)
        for x in range(80, 140):
            t = (x - 80) / 60.0
            r = int(c_concrete_base[0] * t + 115 * (1 - t))
            g = int(c_concrete_base[1] * t + 120 * (1 - t))
            b = int(c_concrete_base[2] * t + 105 * (1 - t))
            alpha = int(255 * min(1.0, t * 1.8))
            half_canvas.putpixel((x, y), (r, g, b, alpha))

        # 主墙立面 (x: 140 ~ 215)
        for x in range(140, 215):
            t = (x - 140) / 75.0
            r = int(c_limestone_lit[0] * (1 - t * 0.25))
            g = int(c_limestone_lit[1] * (1 - t * 0.25))
            b = int(c_limestone_lit[2] * (1 - t * 0.25))
            half_canvas.putpixel((x, y), (r, g, b, 255))

        # 巡逻马道与内壁平台 (x: 215 ~ width)
        for x in range(215, width):
            t = (x - 215) / (width - 215.0)
            r = int(c_walkway[0] * (1 - t) + c_limestone_dark[0] * t)
            g = int(c_walkway[1] * (1 - t) + c_limestone_dark[1] * t)
            b = int(c_walkway[2] * (1 - t) + c_limestone_dark[2] * t)
            half_canvas.putpixel((x, y), (r, g, b, 255))

    # 融合真实微缩模型花岗岩材质纹理到上半部分
    if SOURCE_IMG.exists():
        try:
            cg = Image.open(SOURCE_IMG)
            tex_crop = cg.crop((120, 400, 240, 760)).resize((120, mid_y), Image.Resampling.LANCZOS)
            tex_np = np.array(tex_crop.convert('L')).astype(np.float32) / 128.0
            half_arr = np.array(half_canvas)
            for i in range(110):
                col_tex = tex_np[:, min(i, 119)]
                for ch in range(3):
                    half_arr[:, 125 + i, ch] = np.clip(
                        half_arr[:, 125 + i, ch].astype(np.float32) * (0.68 + 0.32 * col_tex), 0, 255
                    ).astype(np.uint8)
            half_canvas = Image.fromarray(half_arr, 'RGBA')
        except Exception as e:
            print(f"Texture blend skipped: {e}")

    draw = ImageDraw.Draw(half_canvas)

    # 2. 上半部城垛（Crenellations / Merlons, Y: 15 ~ 365）
    crenel_h = 18
    gap_h = 12
    step = crenel_h + gap_h
    for y_start in range(16, mid_y - gate_half_h - 10, step):
        y_end = min(y_start + crenel_h, mid_y - gate_half_h - 10)
        box = [104, y_start, 142, y_end]
        draw.rectangle(box, fill=c_limestone_lit, outline=c_limestone_dark)
        draw.line([104, y_start, 142, y_start], fill=(255, 252, 245, 255), width=2)
        draw.line([104, y_end, 142, y_end], fill=c_limestone_dark, width=2)
        my = (y_start + y_end) // 2
        draw.rectangle([116, my - 2, 128, my + 2], fill=c_slit)

    # 3. 砌石水平接缝与分格纹理
    for y in range(0, mid_y, 16):
        draw.line([138, y, 215, y], fill=(135, 128, 115, 160), width=1)
    for y in range(8, mid_y, 32):
        draw.line([215, y, width, y], fill=(108, 102, 94, 150), width=1)

    # 4. 顶端棱堡封闭角楼碉堡（Corner Bastion Turret, Y: 0 ~ 48）
    draw.polygon([(78, 0), (165, 0), (175, 48), (68, 48)], fill=c_limestone_lit, outline=c_limestone_dark)
    draw.rectangle([88, 8, 145, 30], fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([74, 20, 85, 26], fill=c_slit)

    # 5. 上半部反坦克三角拒马阵地（Czech hedgehogs）
    for hy in [110, 190, 275]:
        hx = 50
        s = 12
        draw.line([hx - s, hy - s, hx + s, hy + s], fill=c_hedgehog, width=3)
        draw.line([hx - s, hy + s, hx + s, hy - s], fill=c_hedgehog, width=3)
        draw.line([hx, hy - s - 2, hx, hy + s + 2], fill=(70, 75, 82, 255), width=3)
        draw.ellipse([hx - s - 2, hy + s - 1, hx + s + 4, hy + s + 4], fill=(20, 24, 28, 110))

    # 6. 上半部门楼半身（Y: 370 ~ 480）
    gate_start_y = mid_y - gate_half_h  # 370
    draw.polygon([
        (42, gate_start_y + 25),
        (28, mid_y),
        (240, mid_y),
        (240, gate_start_y),
        (135, gate_start_y)
    ], fill=c_concrete_base, outline=c_limestone_dark)

    draw.rectangle([58, gate_start_y + 15, 225, mid_y], fill=c_limestone_mid, outline=c_limestone_dark)
    draw.line([58, gate_start_y + 15, 225, gate_start_y + 15], fill=(255, 250, 240, 255), width=3)
    draw.line([58, gate_start_y + 15, 58, mid_y], fill=(252, 246, 235, 255), width=3)

    # 门楼上方圆形防御塔楼（中心在 Y: 405）
    turret_r = 38
    turret_cy = gate_start_y + 35  # 405
    draw.ellipse([64 - turret_r, turret_cy - turret_r, 64 + turret_r, turret_cy + turret_r],
                 fill=c_limestone_lit, outline=c_limestone_dark)
    draw.ellipse([64 - 26, turret_cy - 26, 64 + 26, turret_cy + 26],
                 fill=c_roof_tile, outline=(110, 45, 35, 255))
    draw.rectangle([30, turret_cy - 4, 42, turret_cy + 4], fill=c_slit)

    # 门洞与暗钢装甲大门（拱门中心严格对准 mid_y 480）
    door_left = 46
    door_right = 142
    door_top = mid_y - 52
    draw.rectangle([door_left - 10, door_top - 10, door_right + 8, mid_y],
                   fill=c_limestone_dark, outline=(245, 240, 225, 255))
    draw.rectangle([door_left, door_top, door_right, mid_y], fill=(16, 15, 14, 255))
    draw.rectangle([door_left + 8, door_top + 4, door_right - 4, mid_y], fill=c_iron_gate)
    
    # 门扇加强槽钢与铆钉
    for dy in range(door_top + 12, mid_y, 18):
        draw.line([door_left + 8, dy, door_right - 4, dy], fill=c_iron_beam, width=4)
        draw.line([door_left + 8, dy + 2, door_right - 4, dy + 2], fill=(24, 26, 30, 255), width=1)
        for dx in range(door_left + 16, door_right - 8, 15):
            draw.ellipse([dx - 2, dy - 2, dx + 2, dy + 2], fill=(180, 186, 198, 255))

    # 指挥楼观察窗
    draw.rectangle([140, mid_y - 38, 215, mid_y], fill=c_limestone_lit, outline=c_limestone_dark)
    draw.rectangle([148, mid_y - 25, 162, mid_y - 15], fill=(20, 24, 28, 255))
    draw.rectangle([175, mid_y - 25, 189, mid_y - 15], fill=(20, 24, 28, 255))

    # 门前拒马（距 mid_y 70px）
    hx, hy = 22, mid_y - 70
    s = 12
    draw.line([hx - s, hy - s, hx + s, hy + s], fill=c_hedgehog, width=3)
    draw.line([hx - s, hy + s, hx + s, hy - s], fill=c_hedgehog, width=3)
    draw.line([hx, hy - s - 2, hx, hy + s + 2], fill=(70, 75, 82, 255), width=3)

    # 消除边缘轻微杂边
    half_canvas = half_canvas.filter(ImageFilter.SMOOTH)

    # 7. 由上半部严格垂直翻转生成下半部（确保城门位于 Y=480 绝对正中，城门两侧城墙严格对称！）
    lower_canvas = half_canvas.transpose(Image.Transpose.FLIP_TOP_BOTTOM)

    # 8. 拼合成完整全高 360 x 960 画布
    full_wall_left = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    full_wall_left.paste(half_canvas, (0, 0))
    full_wall_left.paste(lower_canvas, (0, mid_y))

    # 9. 在中心线 Y=mid_y 上绘制对称的闭合门缝与中心锁闭装置
    draw_full = ImageDraw.Draw(full_wall_left)
    draw_full.line([door_left + 8, mid_y, door_right - 4, mid_y], fill=(12, 14, 16, 255), width=3)
    draw_full.rectangle([door_left + 24, mid_y - 5, door_left + 40, mid_y + 5], fill=(75, 82, 92, 255), outline=(140, 148, 160, 255))

    return full_wall_left

def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    
    print("Generating strictly symmetrical tactical fortress wall (360x960)...")
    wall_left = generate_symmetric_wall_asset(360, 960)
    
    # 采用 lossless=True 保证无压缩抖动的高保真对称
    left_path = DEST_DIR / 'tactical-city-wall-left.webp'
    wall_left.save(left_path, format='WEBP', lossless=True)
    print(f"-> Saved: {left_path} ({left_path.stat().st_size} bytes)")

    wall_right = wall_left.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    right_path = DEST_DIR / 'tactical-city-wall-right.webp'
    wall_right.save(right_path, format='WEBP', lossless=True)
    print(f"-> Saved: {right_path} ({right_path.stat().st_size} bytes)")

    compat_path = DEST_DIR / 'player-city-wall-front.webp'
    wall_left.save(compat_path, format='WEBP', lossless=True)
    print(f"-> Updated: {compat_path} ({compat_path.stat().st_size} bytes)")

if __name__ == '__main__':
    main()
