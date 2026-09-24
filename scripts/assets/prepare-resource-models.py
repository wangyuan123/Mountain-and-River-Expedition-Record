"""将资源微缩模型白底原稿裁成首页使用的透明 WebP。"""
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/resource-models-20260923/raw'
DEST = ROOT / 'frontend/img/resources/models'
PREVIEW = ROOT / 'output/imagegen/resource-models-20260923'
RESOURCES = [
    ('food', '粮食'),
    ('steel', '钢铁'),
    ('oil', '石油'),
    ('rare', '稀矿'),
    ('gold', '黄金'),
    ('pop', '平民'),
]


def prepare(path):
    original = Image.open(path).convert('RGB')
    original.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
    rgb = np.array(original)
    white_distance = 255 - rgb.min(axis=2).astype(float)
    mask = np.where(white_distance > 24, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[white_distance > 60] = cv2.GC_FGD
    mask[white_distance < 5] = cv2.GC_BGD
    mask[:3, :] = mask[-3:, :] = cv2.GC_BGD
    mask[:, :3] = mask[:, -3:] = cv2.GC_BGD
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 4, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')
    edge = cv2.dilate((alpha == 0).astype('uint8'), np.ones((3, 3), np.uint8)) > 0
    soft = np.clip((white_distance - 5) / 50, 0, 1) * 255
    alpha[edge] = np.minimum(alpha[edge], soft[edge])
    rgba = Image.fromarray(np.dstack([rgb, alpha]))
    bounds = rgba.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    if not bounds:
        raise ValueError(f'No model silhouette in {path}')
    rgba = rgba.crop(bounds)
    rgba.thumbnail((232, 232), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (256, 256))
    canvas.alpha_composite(rgba, ((256 - rgba.width) // 2, (256 - rgba.height) // 2))
    return canvas


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20)
    for key, _ in RESOURCES:
        path = SOURCE / f'{key}.png'
        prepare(path).save(DEST / f'{key}.webp', quality=92, method=6)
    for name, background, ink in [('light', '#ffffff', '#334155'), ('dark', '#172431', '#f8fafc')]:
        sheet = Image.new('RGB', (900, 420), background)
        draw = ImageDraw.Draw(sheet)
        for i, (key, label) in enumerate(RESOURCES):
            sprite = Image.open(DEST / f'{key}.webp').convert('RGBA')
            x = (i % 3) * 300
            y = (i // 3) * 210
            large = sprite.resize((112, 112), Image.Resampling.LANCZOS)
            sheet.paste(large, (x + 20, y + 16), large)
            draw.text((x + 20, y + 134), label, fill=ink, font=font)
            for size, offset in [(24, 180), (32, 224), (48, 264)]:
                small = sprite.resize((size, size), Image.Resampling.LANCZOS)
                sheet.paste(small, (x + offset, y + 78), small)
        sheet.save(PREVIEW / f'preview-{name}.jpg', quality=94)


if __name__ == '__main__':
    main()
