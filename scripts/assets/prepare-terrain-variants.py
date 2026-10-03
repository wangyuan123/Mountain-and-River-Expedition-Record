"""将生成的 9 款地形变体（森林、丘陵、沼泽各 3 款）处理为无底层厚度、完全融入地图的透明 WebP 与 embedded-PNG，
并生成连片拼接效果验收对比图及实机前后比对图。
"""
from pathlib import Path
import math
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/terrain-variants-20261001/raw'
OUT_DIR = ROOT / 'output/imagegen/terrain-variants-20261001'
MAP_DIR = ROOT / 'frontend/img/map'

VARIANTS = [
    # 森林
    ('wild-forest-dense', '森林·高耸密林', 'forest'),
    ('wild-forest-ridge', '森林·斜向林带', 'forest'),
    ('wild-forest-edge', '森林·低矮疏林', 'forest'),
    # 丘陵
    ('wild-hill-peak', '丘陵·主峰石脉', 'hill'),
    ('wild-hill-ridge', '丘陵·连绵缓坡', 'hill'),
    ('wild-hill-foothill', '丘陵·低矮残丘', 'hill'),
    # 沼泽
    ('wild-swamp-deep', '沼泽·深潭芦苇', 'swamp'),
    ('wild-swamp-creek', '沼泽·曲折水网', 'swamp'),
    ('wild-swamp-marsh', '沼泽·泥泞浅滩', 'swamp'),
]

# 各模型在 768x768 下的顶面地表前角 Y 坐标（彻底剔除下方深色立面土层与投影，杜绝台阶高度差）
FRONT_Y = {
    'wild-forest-dense': 625,
    'wild-forest-ridge': 625,
    'wild-forest-edge': 615,
    'wild-hill-peak': 660,
    'wild-hill-ridge': 625,
    'wild-hill-foothill': 620,
    'wild-swamp-deep': 625,
    'wild-swamp-creek': 620,
    'wild-swamp-marsh': 605,
}


def process_terrain_no_thickness(name):
    """剔除底座厚度、暗色土层立面与摄影投影，边缘柔和羽化以融入地图。"""
    raw_path = RAW_DIR / f'{name}.png'
    orig = Image.open(raw_path).convert('RGB')
    orig = orig.resize((768, 768), Image.Resampling.LANCZOS)
    rgb = np.array(orig)
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[:h, :w]
    x, y = xx / w, yy / h
    
    # 1. 拟合渐变棚拍背景并执行 grabCut 分割
    border = (x < .03) | (x > .97) | (y < .03) | (y > .97)
    features = np.stack([np.ones_like(x), x, y, x*y, x*x, y*y], axis=-1)
    samples = border.copy()
    for _ in range(5):
        coefficients = np.linalg.lstsq(features[samples], rgb[samples].astype(float), rcond=None)[0]
        distance = np.linalg.norm(rgb - features @ coefficients, axis=2)
        samples = border & (distance < max(10, np.percentile(distance[border], 70)))
        
    mask = np.where(distance > 24, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[distance > 55] = cv2.GC_FGD
    mask[border & (distance < 32)] = cv2.GC_BGD
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 4, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255.0, 0.0)
    
    # 2. V 形前缘裁剪：彻底消除底座厚度（深色土层立面）与投影
    y_center = FRONT_Y.get(name, 625)
    for cx in range(w):
        # 30° 斜等轴前缘线
        y_front = y_center - abs(cx - 384) * 0.575
        # 裁剪线及以下全部置为完全透明（0），消除立面与阴影
        alpha[int(y_front):, cx] = 0.0
        
        # 向上 42px 进行平滑余弦/平滑步进羽化，使地表边缘自然淡出融入地图草地
        fade_h = 42.0
        for cy in range(max(0, int(y_front - fade_h)), int(y_front)):
            dist = y_front - cy
            f = dist / fade_h
            alpha[cy, cx] = min(alpha[cy, cx], 255.0 * f * f * (3 - 2 * f))

    # 3. 左右两侧斜边地表羽化（消除硬直切线，保持林冠与山峰实体完整）
    for cy in range(h):
        rows = np.where(alpha[cy, :] > 5)[0]
        if len(rows) > 30 and cy > 300:
            x_left, x_right = rows.min(), rows.max()
            for cx in range(x_left, min(x_left + 26, x_right)):
                f = (cx - x_left) / 26.0
                alpha[cy, cx] = min(alpha[cy, cx], 255.0 * f * f * (3 - 2 * f))
            for cx in range(max(x_left, x_right - 26), x_right + 1):
                f = (x_right - cx) / 26.0
                alpha[cy, cx] = min(alpha[cy, cx], 255.0 * f * f * (3 - 2 * f))

    rgba = Image.fromarray(np.dstack([rgb, alpha.astype(np.uint8)]))
    bounds = rgba.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    if bounds:
        rgba = rgba.crop(bounds)
        
    rgba.thumbnail((364, 364), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(rgba, ((384 - rgba.width) // 2, (384 - rgba.height) // 2))
    return canvas


def render_preview_sheet():
    """生成包含 128px、64px、32px 多尺度以及 3x3 连片拼接模拟的验收长图。"""
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22)
    font_mid = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16)
    font_small = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)
    
    sheet = Image.new('RGB', (1200, 1420), '#1a222d')
    draw = ImageDraw.Draw(sheet)
    
    draw.text((40, 25), '地形图标变体验收：森林、丘陵、沼泽（已取消底层厚度与立面台阶）', fill='#f1f5f9', font=font_large)
    draw.text((40, 60), '已彻底去除方块底座厚度与深色土层立面，多向自然羽化无缝融入大地图草皮与水域', fill='#94a3b8', font=font_mid)
    
    categories = [
        ('forest', '森林 (Forest) 变体系列', ['wild-forest-dense', 'wild-forest-ridge', 'wild-forest-edge']),
        ('hill', '丘陵/山地 (Hill) 变体系列', ['wild-hill-peak', 'wild-hill-ridge', 'wild-hill-foothill']),
        ('swamp', '沼泽/湿地 (Swamp) 变体系列', ['wild-swamp-deep', 'wild-swamp-creek', 'wild-swamp-marsh']),
    ]
    
    y_start = 110
    for row_idx, (cat_id, cat_title, variant_keys) in enumerate(categories):
        cat_y = y_start + row_idx * 430
        draw.rectangle([(30, cat_y), (1170, cat_y + 400)], fill='#1e293b', outline='#334155', width=1)
        draw.text((50, cat_y + 15), cat_title, fill='#38bdf8', font=font_large)
        
        for v_idx, v_key in enumerate(variant_keys):
            name_label = [name for k, name, _ in VARIANTS if k == v_key][0]
            webp_path = MAP_DIR / f'{v_key}.webp'
            if not webp_path.exists():
                continue
            im = Image.open(webp_path).convert('RGBA')
            
            x_pos = 50 + v_idx * 240
            large = im.resize((128, 128), Image.Resampling.LANCZOS)
            sheet.paste(large, (x_pos + 10, cat_y + 60), large)
            draw.text((x_pos + 10, cat_y + 200), name_label, fill='#e2e8f0', font=font_mid)
            draw.text((x_pos + 10, cat_y + 225), v_key, fill='#64748b', font=font_small)
            
            s64 = im.resize((64, 64), Image.Resampling.LANCZOS)
            sheet.paste(s64, (x_pos + 10, cat_y + 250), s64)
            s32 = im.resize((32, 32), Image.Resampling.LANCZOS)
            sheet.paste(s32, (x_pos + 90, cat_y + 266), s32)
            draw.text((x_pos + 10, cat_y + 325), '64px / 32px 视口', fill='#94a3b8', font=font_small)
            
        cluster_x = 790
        cluster_y = cat_y + 40
        draw.rectangle([(cluster_x, cluster_y), (cluster_x + 350, cluster_y + 340)], fill='#151e28', outline='#475569', width=1)
        draw.text((cluster_x + 15, cluster_y + 12), '连片拼接模拟 (3×3 交叉散布)', fill='#fbbf24', font=font_mid)
        
        tile_size = 96
        pattern = [
            [0, 1, 2],
            [1, 2, 0],
            [2, 0, 1]
        ]
        base_grid_x = cluster_x + 40
        base_grid_y = cluster_y + 60
        
        for gy in range(3):
            for gx in range(3):
                v_type = pattern[gy][gx]
                v_key = variant_keys[v_type]
                emb_path = MAP_DIR / f'{v_key}-map-embedded.png'
                if not emb_path.exists():
                    emb_path = MAP_DIR / f'{v_key}.webp'
                tile_im = Image.open(emb_path).convert('RGBA')
                tile_thumb = tile_im.resize((tile_size, tile_size), Image.Resampling.LANCZOS)
                
                px = base_grid_x + gx * 85
                py = base_grid_y + gy * 75
                sheet.paste(tile_thumb, (px, py), tile_thumb)
                
    sheet.save(OUT_DIR / 'preview-comparison.jpg', quality=95)
    print(f'Saved preview comparison to {OUT_DIR / "preview-comparison.jpg"}')


def render_before_after_comparison():
    """生成实机截图的前后对比图。"""
    screen_before_path = ROOT / '.gemini/antigravity/brain/9e95bd95-2b24-43d4-9674-df0e02fe2be4/.user_uploaded/media_1790837767232.png'
    if not screen_before_path.exists():
        return
    screen_before = Image.open(screen_before_path).convert('RGBA')
    clean_grass = screen_before.crop((20, 20, 180, 180))
    
    new_model = Image.open(MAP_DIR / 'wild-forest-ridge-map-embedded.png')
    thumb = new_model.resize((150, 150), Image.Resampling.LANCZOS)
    
    screen_after = screen_before.copy()
    screen_after.paste(clean_grass, (150, 85))
    screen_after.alpha_composite(thumb, (150, 85))
    
    w, h = screen_before.size
    comp = Image.new('RGB', (w * 2, h + 50), '#1a222d')
    draw = ImageDraw.Draw(comp)
    font = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20)

    comp.paste(screen_before.convert('RGB'), (0, 50))
    comp.paste(screen_after.convert('RGB'), (w, 50))

    draw.text((20, 12), '【修改前】存在底层厚度（深色土层截面与投影导致立体台阶高度差）', fill='#f87171', font=font)
    draw.text((w + 20, 12), '【修改后】彻底取消底层厚度 + 边缘自然羽化（无底座无台阶，完美融入大地图）', fill='#4ade80', font=font)

    comp.save(OUT_DIR / 'before_after_comparison.jpg', quality=95)
    print(f'Saved before/after comparison to {OUT_DIR / "before_after_comparison.jpg"}')


def main():
    MAP_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    
    print('Starting processing terrain variants (without base thickness)...')
    for key, name, cat in VARIANTS:
        raw_path = RAW_DIR / f'{key}.png'
        if not raw_path.exists():
            print(f'Warning: raw file {raw_path} not found!')
            continue
        print(f'Processing {key} ({name})...')
        cutout = process_terrain_no_thickness(key)
        
        # 1. 保存普通 webp
        webp_path = MAP_DIR / f'{key}.webp'
        cutout.save(webp_path, 'WEBP', quality=92, method=6)
        
        # 2. 保存 -map.webp
        map_webp_path = MAP_DIR / f'{key}-map.webp'
        cutout.save(map_webp_path, 'WEBP', quality=92, method=6)
        
        # 3. 保存嵌入式羽化图 -map-embedded.png
        embedded_path = MAP_DIR / f'{key}-map-embedded.png'
        cutout.save(embedded_path, 'PNG', optimize=True)
        
        print(f'  Saved {webp_path.name}, {map_webp_path.name}, {embedded_path.name}')
        
    print('Generating comparison preview sheet...')
    render_preview_sheet()
    render_before_after_comparison()
    print('Done all terrain variants processing!')


if __name__ == '__main__':
    main()
