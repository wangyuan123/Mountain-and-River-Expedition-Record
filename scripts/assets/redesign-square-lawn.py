#!/usr/bin/env python3
"""
将菱形草坪重新设计为0度正交方格草坪（正方形地表轮廓），
边缘处理为不规则自然草叶与泥土轮廓，
草坪内部不均匀分布黑土地颜色（[83, 73, 61]）与地图背景色（[108, 108, 77]），
彻底消除菱形斜角和硬边，自然融入大地图。
"""

from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / 'output/imagegen/grass-redesign-20261015'
MAP_DIR = ROOT / 'frontend/img/map'
OUT_DIR.mkdir(parents=True, exist_ok=True)

C1_PATH = Path('/Users/chenjuan/.gemini/antigravity/brain/20ce01fc-7161-4686-a4ee-b8048647ef66/square_lawn_redesign_1792033498054.jpg')
C3_PATH = Path('/Users/chenjuan/.gemini/antigravity/brain/20ce01fc-7161-4686-a4ee-b8048647ef66/square_lawn_natural_1792033624797.jpg')
USER_ORIG_PATH = Path('/Users/chenjuan/.gemini/antigravity/brain/20ce01fc-7161-4686-a4ee-b8048647ef66/.user_uploaded/media_1792033015783_b4d8db9d.png')

# 游戏地图基础色相标准
MAP_BG_COLOR = np.array([107.6, 107.7, 76.8], dtype=np.float32)  # 地图原生橄榄绿平原背景色
BLACK_SOIL_COLOR = np.array([83.0, 73.0, 61.0], dtype=np.float32) # 游戏常量定义的黑土地基准色


def extract_and_refine_cutout(img_path: Path, bg_color: list, name: str) -> Image.Image:
    """精准分割摄影棚背景，消除边缘灰边，并进行柔和的不规则有机羽化"""
    orig = Image.open(img_path).convert('RGB')
    rgb = np.array(orig)
    h, w = rgb.shape[:2]
    
    # 色差距离检测
    diff = np.linalg.norm(rgb.astype(np.float32) - np.array(bg_color, dtype=np.float32), axis=2)
    
    mask = np.full((h, w), cv2.GC_PR_FGD, dtype=np.uint8)
    mask[diff < 20] = cv2.GC_BGD
    mask[diff > 45] = cv2.GC_FGD
    
    border_px = int(w * 0.035)
    mask[:border_px, :] = cv2.GC_BGD
    mask[-border_px:, :] = cv2.GC_BGD
    mask[:, :border_px] = cv2.GC_BGD
    mask[:, -border_px:] = cv2.GC_BGD
    
    bgd_model = np.zeros((1, 65), np.float64)
    fgd_model = np.zeros((1, 65), np.float64)
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, bgd_model, fgd_model, 5, cv2.GC_INIT_WITH_MASK)
    
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255.0, 0.0)
    
    # 边缘去雾/去灰边 (Defringe)：避免摄影棚淡灰色在羽化边缘发白
    # 过渡区基于diff平滑淡出
    transition = np.clip((diff - 20) / 25.0, 0.0, 1.0)
    alpha = alpha * transition
    
    # 清理孤立杂点
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats((alpha > 25).astype(np.uint8))
    for i in range(1, num_labels):
        if stats[i, cv2.CC_STAT_AREA] < 120:
            alpha[labels == i] = 0.0
            
    # 计算距离变换，对最外层 12-18px 进行平滑余弦/smoothstep羽化，使得边缘草尖和泥土自然消融
    dist = cv2.distanceTransform((alpha > 10).astype(np.uint8), cv2.DIST_L2, 5)
    feather_dist = 14.0
    feather_factor = np.clip(dist / feather_dist, 0.0, 1.0)
    smooth_feather = feather_factor * feather_factor * (3.0 - 2.0 * feather_factor)
    alpha = np.minimum(alpha, smooth_feather * 255.0)
    
    # 颜色校准：将微量的环境色向大地图色阶靠拢，使得黑土地与草地自然融入
    # 对偏亮灰白的边缘像素做底色融合
    edge_zone = (alpha > 0) & (alpha < 240)
    rgb_float = rgb.astype(np.float32)
    # 在半透明边缘将背景色替换为地图背景色，杜绝任何白边
    for c in range(3):
        rgb_float[edge_zone, c] = rgb_float[edge_zone, c] * 0.75 + MAP_BG_COLOR[c] * 0.25
        
    rgba = Image.fromarray(np.dstack([np.clip(rgb_float, 0, 255).astype(np.uint8), alpha.astype(np.uint8)]))
    
    # 裁切主体外接矩形
    bbox = rgba.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    if bbox:
        rgba = rgba.crop(bbox)
        
    rgba.save(OUT_DIR / f'{name}_cutout_feathered.png')
    return rgba


def build_384_tile(cropped_rgba: Image.Image, name: str) -> Image.Image:
    """生成游戏标准的 384x384 0度正交地图瓦片"""
    target_dim = 360  # 留出 24px 自然边缘呼吸空间
    w, h = cropped_rgba.size
    scale = min(target_dim / w, target_dim / h)
    new_w, new_h = int(round(w * scale)), int(round(h * scale))
    scaled = cropped_rgba.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((384 - new_w) // 2, (384 - new_h) // 2))
    
    canvas.save(OUT_DIR / f'{name}_tile_384.png')
    return canvas


def create_game_map_background(width: int, height: int) -> Image.Image:
    """生成真实的地图背景底图（包含温带橄榄绿平原与自然黑土地微起伏纹理）"""
    # 采样自 real_map_tile.png 并平铺/加微量噪声
    tile_path = OUT_DIR / 'real_map_tile.png'
    if tile_path.exists():
        tile = Image.open(tile_path).convert('RGB')
        bg = Image.new('RGB', (width, height))
        for y in range(0, height, tile.height):
            for x in range(0, width, tile.width):
                bg.paste(tile, (x, y))
        return bg
    else:
        # 兜底生成
        arr = np.zeros((height, width, 3), dtype=np.uint8)
        arr[:, :] = MAP_BG_COLOR.astype(np.uint8)
        return Image.fromarray(arr)


def create_showcase(optA_tile: Image.Image, optB_tile: Image.Image):
    """
    生成三列对比验收图：
    1. 左侧：用户原图（菱形草坪，硬斜边，无黑土地融入）
    2. 中间：重新设计的方案 A（正交方格地表，不规则羽化边缘，自然黑土地+背景底色斑块）
    3. 右侧：重新设计的方案 B（正交方格地表，密集立体草丛+深色黑土地湿润沃土）
    并在下方展示 2x2 连片拼合效果以及地图实景融合效果！
    """
    map_bg = create_game_map_background(1200, 960)
    
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 24)
    font_sub = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 18)

    # 1. 顶部排版三栏对比
    canvas = Image.new('RGB', (1200, 1100), '#18202a')
    draw = ImageDraw.Draw(canvas)
    
    draw.text((40, 24), '正交方格草坪重新设计与环境融入验收对比', fill='#f8fafc', font=font_title)
    draw.text((40, 62), '取消 45° 菱形斜角 ➔ 0° 正交方格正方形地表 | 不规则自然草尖边缘羽化 | 融合黑土地与地图背景底色', fill='#94a3b8', font=font_sub)
    
    # 3个预览卡片背景
    card_w, card_h = 360, 420
    xs = [40, 420, 800]
    y_card = 100
    
    # 卡片1：原图（菱形）
    draw.rounded_rectangle([(xs[0], y_card), (xs[0] + card_w, y_card + card_h)], radius=8, fill='#1e293b', outline='#ef4444', width=2)
    draw.text((xs[0] + 16, y_card + 14), '原始设计（用户反馈问题）', fill='#f87171', font=font_bold)
    draw.text((xs[0] + 16, y_card + 40), '• 45° 菱形设计，与当前正交方格地图冲突\n• 边界规则斜角，草坪边缘生硬\n• 内部为纯草皮，缺乏黑土地与地图底色过渡', fill='#cbd5e1', font=font_label)
    
    # 卡片2：方案 A（自然原野·黑土平原）
    draw.rounded_rectangle([(xs[1], y_card), (xs[1] + card_w, y_card + card_h)], radius=8, fill='#1e293b', outline='#10b981', width=2)
    draw.text((xs[1] + 16, y_card + 14), '方案 A：自然原野·生态融合（推荐）', fill='#34d399', font=font_bold)
    draw.text((xs[1] + 16, y_card + 40), '• 0° 正交方格水平前沿正方形地貌\n• 边缘完全不规则，草尖与泥土自然散落羽化\n• 丰富分布黑土地（[83,73,61]）与地图底色', fill='#cbd5e1', font=font_label)

    # 卡片3：方案 B（茂密草甸·湿润沃土）
    draw.rounded_rectangle([(xs[2], y_card), (xs[2] + card_w, y_card + card_h)], radius=8, fill='#1e293b', outline='#38bdf8', width=2)
    draw.text((xs[2] + 16, y_card + 14), '方案 B：立体草甸·沃土繁茂', fill='#38bdf8', font=font_bold)
    draw.text((xs[2] + 16, y_card + 40), '• 0° 正交方格水平正方形轮廓\n• 边缘不规则锯齿草簇与开裂泥土轮廓\n• 紧凑立体微缩草株，底衬深厚肥沃黑土壤', fill='#cbd5e1', font=font_label)

    # 在卡片内贴上实机底图
    sub_bg_size = (328, 280)
    for i in range(3):
        card_sub_bg = map_bg.crop((i * 328, 0, (i + 1) * 328, 280))
        canvas.paste(card_sub_bg, (xs[i] + 16, y_card + 120))
        
    # 贴上原图
    if USER_ORIG_PATH.exists():
        orig_img = Image.open(USER_ORIG_PATH).convert('RGBA')
        # 居中贴入卡片1底图
        ox = xs[0] + 16 + (328 - orig_img.width) // 2
        oy = y_card + 120 + (280 - orig_img.height) // 2
        canvas.paste(orig_img, (ox, oy), orig_img)
        
    # 贴上方案 A
    a_resized = optA_tile.resize((260, 260), Image.Resampling.LANCZOS)
    canvas.paste(a_resized, (xs[1] + 16 + (328 - 260) // 2, y_card + 120 + (280 - 260) // 2), a_resized)
    
    # 贴上方案 B
    b_resized = optB_tile.resize((260, 260), Image.Resampling.LANCZOS)
    canvas.paste(b_resized, (xs[2] + 16 + (328 - 260) // 2, y_card + 120 + (280 - 260) // 2), b_resized)
    
    # 下半区：大地图实机连片融入展示（4格连片无缝融合）
    draw.text((40, 545), '大地图实机无缝连片融合效果（4格多瓦片群落拼合，彻底消除地块方格感）', fill='#f8fafc', font=font_title)
    
    # 制作大地图背景展示板
    wide_map = map_bg.crop((0, 300, 1120, 780))
    # 在 wide_map 左侧拼合方案 A 的 2x2 连片群落
    # 2x2 放置，每个大小 220x220，微重叠
    a_small = optA_tile.resize((220, 220), Image.Resampling.LANCZOS)
    # 拼合方案A (4个点)
    wide_map.paste(a_small, (50, 40), a_small)
    wide_map.paste(a_small, (220, 40), a_small)
    wide_map.paste(a_small, (50, 210), a_small)
    wide_map.paste(a_small, (220, 210), a_small)
    
    # 在 wide_map 右侧拼合方案 B 的 2x2 连片群落
    b_small = optB_tile.resize((220, 220), Image.Resampling.LANCZOS)
    wide_map.paste(b_small, (620, 40), b_small)
    wide_map.paste(b_small, (790, 40), b_small)
    wide_map.paste(b_small, (620, 210), b_small)
    wide_map.paste(b_small, (790, 210), b_small)
    
    # 加标注
    draw_wide = ImageDraw.Draw(wide_map)
    draw_wide.text((70, 20), '方案 A（连片 4 地块融合，草木与黑土地自然交错）', fill='#ffffff', font=font_bold)
    draw_wide.text((640, 20), '方案 B（连片 4 地块融合，立体丰茂草皮）', fill='#ffffff', font=font_bold)
    
    canvas.paste(wide_map, (40, 585))
    
    canvas.save(OUT_DIR / 'square_lawn_redesign_showcase.jpg', quality=95)
    print('Saved showcase to square_lawn_redesign_showcase.jpg')


def main():
    print('Processing Candidate 3 (Option A: Natural Steppe Meadow)...')
    optA_cutout = extract_and_refine_cutout(C3_PATH, [224.6, 224.6, 224.4], 'optionA_natural')
    optA_tile = build_384_tile(optA_cutout, 'wild-grassland-optionA')
    
    print('Processing Candidate 1 (Option B: Lush Clump Meadow)...')
    optB_cutout = extract_and_refine_cutout(C1_PATH, [210.7, 206.7, 195.0], 'optionB_lush')
    optB_tile = build_384_tile(optB_cutout, 'wild-grassland-optionB')
    
    print('Generating comparison showcase...')
    create_showcase(optA_tile, optB_tile)
    
    # 将推荐的方案 A 应用于游戏前端地图资源
    print('Applying Option A to frontend/img/map...')
    # 1. wild-grassland 系列
    optA_tile.save(MAP_DIR / 'wild-grassland.webp', 'WEBP', quality=93, method=6)
    optA_tile.save(MAP_DIR / 'wild-grassland-map.webp', 'WEBP', quality=93, method=6)
    optA_tile.save(MAP_DIR / 'wild-grassland-map-embedded.png', 'PNG', optimize=True)
    
    # 2. wild-shrub-grass-patch 系列（320x320）
    optA_320 = optA_tile.resize((320, 320), Image.Resampling.LANCZOS)
    optA_320.save(MAP_DIR / 'wild-shrub-grass-patch.webp', 'WEBP', quality=93, method=6)
    
    # 3. grass-medium 系列
    optA_tile.save(MAP_DIR / 'grass-medium.webp', 'WEBP', quality=93, method=6)
    optA_tile.save(MAP_DIR / 'grass-medium-map.webp', 'WEBP', quality=93, method=6)
    optA_tile.save(MAP_DIR / 'grass-medium-map-embedded.png', 'PNG', optimize=True)

    # 4. 同步方案 B 为 grass-lush
    optB_tile.save(MAP_DIR / 'grass-lush.webp', 'WEBP', quality=93, method=6)
    optB_tile.save(MAP_DIR / 'grass-lush-map.webp', 'WEBP', quality=93, method=6)
    optB_tile.save(MAP_DIR / 'grass-lush-map-embedded.png', 'PNG', optimize=True)

    print('All assets processed and integrated successfully!')


if __name__ == '__main__':
    main()
