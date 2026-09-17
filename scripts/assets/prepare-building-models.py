"""将选定的 A 套原图制成透明建筑模型。依赖 Pillow、numpy、opencv-python-headless。"""
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/realistic-buildings-20260917'
DEST = ROOT / 'frontend/img/buildings/garden'
QA = SOURCE / 'integration'

# 这些轮廓只约束原图右下方的地台边界，避免把摄影棚投影当成实心建筑。
# 坐标按 A 套选定原图归一化；上半部仍由分割保留树冠、天线和烟囱。
GROUND_EDGES = {
    'academy': [(1, .55), (.780, .872), (.651, .899)],
    'farm': [(1, .395), (.987, .455), (.936, .510), (.958, .555), (.899, .705), (.630, .941)],
    'house': [(1, .560), (.917, .739), (.643, .960)],
    'lab': [(1, .485), (.936, .656), (.769, .888), (.601, .929)],
    'lightfactory': [(1, .536), (.812, .703), (.809, .730), (.508, .937)],
    'radar': [(1, .606), (.995, .628), (.650, .967)],
    'factory': [(1, .574), (.969, .635), (.963, .668), (.701, .930)],
    'exchange': [(1, .478), (.988, .521), (.975, .602), (.919, .724), (.808, .871), (.703, .927)],
}


def cutout(path):
    rgb = np.array(Image.open(path).convert('RGB').resize((768, 768), Image.Resampling.LANCZOS))
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[:h, :w]
    x, y = xx / w, yy / h
    border = (x < .025) | (x > .975) | (y < .025) | (y > .975)
    features = np.stack([np.ones_like(x), x, y, x*y, x*x, y*y], axis=-1)
    samples = border.copy()
    # 鲁棒拟合渐变背景，排除触及画框的建筑，不能用统一灰色直接抠奶油石墙。
    for _ in range(5):
        coefficients = np.linalg.lstsq(features[samples], rgb[samples].astype(float), rcond=None)[0]
        distance = np.linalg.norm(rgb - features @ coefficients, axis=2)
        samples = border & (distance < max(10, np.percentile(distance[border], 70)))
    mask = np.where(distance > 24, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    chroma = rgb.max(axis=2).astype(float) - rgb.min(axis=2)
    mask[(distance > 65) & (chroma > 28)] = cv2.GC_FGD
    mask[border & (distance < 35)] = cv2.GC_BGD
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 5, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')
    count, labels, stats, _ = cv2.connectedComponentsWithStats(alpha)
    for k in range(1, count):
        if stats[k, cv2.CC_STAT_AREA] < 25:
            alpha[labels == k] = 0
    if path.stem == 'radar':
        # 栅格天线内部也要透出页面背景；只处理空中网面，不影响混凝土屋顶。
        mesh = (x > .23) & (x < .61) & (y > .02) & (y < .266)
        alpha[mesh] = np.minimum(alpha[mesh], np.clip((distance[mesh] - 18) / 24, 0, 1) * 255)
    alpha = cv2.GaussianBlur(alpha, (3, 3), .6)
    if path.stem in GROUND_EDGES:
        outline = [(0, 0), (1, 0)] + GROUND_EDGES[path.stem] + [(0, 1)]
        ground = Image.new('L', (w, h))
        ImageDraw.Draw(ground).polygon([(round(a*w), round(b*h)) for a, b in outline], fill=255)
        alpha = np.minimum(alpha, np.array(ground.filter(ImageFilter.GaussianBlur(.7))))
    rgba = Image.fromarray(np.dstack([rgb, alpha]))
    bounds = rgba.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    rgba = rgba.crop(bounds)
    rgba.thumbnail((368, 368), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384))
    canvas.alpha_composite(rgba, ((384 - rgba.width)//2, (384 - rgba.height)//2))
    return canvas


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    QA.mkdir(parents=True, exist_ok=True)
    buildings = json.loads((SOURCE / 'manifest.json').read_text())['buildings']
    for b in buildings:
        sprite = cutout(SOURCE / 'a-garden' / (b['id'] + '.png'))
        sprite.save(DEST / (b['id'] + '.webp'), quality=92, method=6)
        print(b['id'], flush=True)
    for name, color, ink in [('dark', '#172431', '#ffffff'), ('light', '#f5ecd7', '#302e24')]:
        sheet = Image.new('RGB', (1400, 720), color)
        draw = ImageDraw.Draw(sheet)
        for i, b in enumerate(buildings):
            sprite = Image.open(DEST / (b['id'] + '.webp')).convert('RGBA')
            sprite.thumbnail((194, 210), Image.Resampling.LANCZOS)
            x, y = i % 7 * 200, i // 7 * 240
            sheet.paste(sprite, (x + 3, y), sprite)
            draw.text((x + 8, y + 212), b['id'], fill=ink)
        sheet.save(QA / ('transparent-' + name + '.jpg'), quality=94)
    print('Prepared 21 transparent models and light/dark contact sheets.')


if __name__ == '__main__':
    main()
