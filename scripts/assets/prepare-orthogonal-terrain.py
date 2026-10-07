"""将生成的正交方格野地图标（森林、丘陵、沼泽、草地、岩石、资源点）处理为无底座厚度、
底边水平平直、宽度 100% 满幅无缝拼接的透明 WebP 与 embedded-PNG，彻底消除白条和拼接缝隙。
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/terrain-orthogonal-20261003/raw'
MAP_DIR = ROOT / 'frontend/img/map'

ITEMS = [
    # 森林
    ('wild-forest-dense', '森林·高耸密林', 645),
    ('wild-forest-ridge', '森林·连续林带', 635),
    ('wild-forest-edge', '森林·低矮疏林', 625),
    # 丘陵
    ('wild-hill-peak', '丘陵·主峰石脉', 650),
    ('wild-hill-ridge', '丘陵·连绵缓坡', 630),
    ('wild-hill-foothill', '丘陵·低矮残丘', 625),
    # 沼泽
    ('wild-swamp-deep', '沼泽·深潭芦苇', 630),
    ('wild-swamp-creek', '沼泽·曲折水网', 620),
    ('wild-swamp-marsh', '沼泽·泥泞浅滩', 615),
    # 地表
    # ('wild-grassland', '地表·温带草甸', 610) -> 已删除草原独立地图模型，保留地图原生平原地表
    ('wild-rock', '岩石·风化石林', 635),
    ('wild-plains', '平原·广袤原野', 610),
    # 资源点
    ('wild-grainfield', '粮田·成熟麦浪', 620),
    ('wild-ironworks', '炼铁厂·工业冶炼', 645),
    ('wild-oil', '油田·钻塔油井', 650),
    ('wild-rarefactory', '稀矿厂·选矿提炼', 645),
]


def process_orthogonal_tile(name, y_cutoff):
    raw_path = RAW_DIR / f'{name}.png'
    if not raw_path.exists():
        print(f'Warning: {raw_path} does not exist!')
        return None
    orig = Image.open(raw_path).convert('RGB')
    orig = orig.resize((768, 768), Image.Resampling.LANCZOS)
    rgb = np.array(orig)
    h, w = rgb.shape[:2]
    
    # 1. 摄影棚背景与阴影消除
    # 摄影棚地面与阴影具有平滑低纹理梯度 (mag < 12) 与低饱和度 (saturation < 22)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    grad_x = cv2.Sobel(blurred, cv2.CV_32F, 1, 0, ksize=3)
    grad_y = cv2.Sobel(blurred, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(grad_x, grad_y)
    
    is_studio = (mag < 12) & (hsv[:, :, 1] < 22)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    is_studio = cv2.morphologyEx(is_studio.astype(np.uint8), cv2.MORPH_CLOSE, kernel)
    
    # 连通域：只将与外部四周边缘连通的平滑区域判为摄影棚背景
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(is_studio)
    bg_mask = np.zeros_like(gray, dtype=bool)
    for label in range(1, num_labels):
        ys, xs = np.where(labels == label)
        if ys.min() < 5 or xs.min() < 5 or xs.max() > 762:
            bg_mask[labels == label] = True
            
    # 正方格对称左右边界裁切：x_left = 46, x_right = 722 (宽度 676)
    x_left = 46
    x_right = w - 46
    
    mask = np.full((h, w), cv2.GC_PR_FGD, dtype=np.uint8)
    mask[:, :x_left] = cv2.GC_BGD
    mask[:, x_right:] = cv2.GC_BGD
    mask[bg_mask] = cv2.GC_BGD
    mask[:10, :] = cv2.GC_BGD
    
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 4, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255.0, 0.0)
    alpha[:, :x_left] = 0.0
    alpha[:, x_right:] = 0.0
    alpha[bg_mask] = 0.0
    alpha[int(y_cutoff):, :] = 0.0
    
    # 消除漂浮的孤立微小杂斑
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats((alpha > 10).astype(np.uint8))
    for i in range(1, num_labels):
        if stats[i, cv2.CC_STAT_AREA] < 200:
            alpha[labels == i] = 0.0
            
    rgba = Image.fromarray(np.dstack([rgb, alpha.astype(np.uint8)]))
    top_y = np.where(alpha.max(axis=1) > 10)[0]
    y_min = top_y.min() if len(top_y) > 0 else 0
    cropped = rgba.crop((x_left, y_min, x_right, int(y_cutoff)))
    
    # 缩放到宽度严格等于 384px (100% 满幅贴满地图网格，消除相邻地块拼接透光缝隙)
    target_w = 384
    scale = target_w / cropped.width
    target_h = int(round(cropped.height * scale))
    scaled = cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)
    
    # 画布为 384x384，内容严格靠底边对齐 (0, 384 - target_h)
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, (0, 384 - target_h))
    return canvas


def main():
    MAP_DIR.mkdir(parents=True, exist_ok=True)
    print('Processing orthogonal terrain tiles...')
    for key, name, y_cut in ITEMS:
        print(f'Processing {key} ({name})...')
        cutout = process_orthogonal_tile(key, y_cut)
        if cutout is None:
            continue
            
        # 1. 保存 .webp
        cutout.save(MAP_DIR / f'{key}.webp', 'WEBP', quality=92, method=6)
        # 2. 保存 -map.webp
        cutout.save(MAP_DIR / f'{key}-map.webp', 'WEBP', quality=92, method=6)
        # 3. 保存 -map-embedded.png
        cutout.save(MAP_DIR / f'{key}-map-embedded.png', 'PNG', optimize=True)
        print(f'  Saved {key} webp and png')
        
    # 兜底别名同步：wild-forest, wild-hill, wild-swamp 等
    for src, dst in [
        ('wild-forest-dense', 'wild-forest'),
        ('wild-hill-ridge', 'wild-hill'),
        ('wild-swamp-deep', 'wild-swamp'),
        ('wild-plains', 'grass-plain'),
    ]:
        src_png = MAP_DIR / f'{src}-map-embedded.png'
        if src_png.exists():
            im = Image.open(src_png)
            im.save(MAP_DIR / f'{dst}-map-embedded.png', 'PNG', optimize=True)
            im.save(MAP_DIR / f'{dst}.webp', 'WEBP', quality=92, method=6)
            im.save(MAP_DIR / f'{dst}-map.webp', 'WEBP', quality=92, method=6)
            print(f'  Synced alias {dst} from {src}')

    # 草原已取消独立地图模型，保持透明占位
    transparent = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    for g_name in ['wild-grassland', 'grass-lush', 'grass-medium', 'grass-sparse']:
        transparent.save(MAP_DIR / f'{g_name}-map-embedded.png', 'PNG', optimize=True)
        transparent.save(MAP_DIR / f'{g_name}.webp', 'WEBP', quality=92)
        transparent.save(MAP_DIR / f'{g_name}-map.webp', 'WEBP', quality=92)
            
    print('All orthogonal terrain tiles processed successfully!')


if __name__ == '__main__':
    main()
