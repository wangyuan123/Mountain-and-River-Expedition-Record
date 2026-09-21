"""处理 gpt-image-2 兵种白底原稿，输出首页用透明 WebP 和深浅背景总览。"""
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/unit-models-20260921'
DEST = ROOT / 'frontend/img/units/models'


def prepare(path):
    """仅剔除白色摄影背景，保留银色机身的内部高光及完整装备轮廓。"""
    original = Image.open(path)
    # 兼容 API 部分返回图自带真实 alpha，直接保留，避免二次分割损伤机翼。
    if original.mode == 'RGBA' and original.getchannel('A').getextrema()[0] < 255:
        return normalize(original)
    im = original.convert('RGB')
    im.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
    rgb = np.array(im)
    h, w = rgb.shape[:2]
    white_distance = 255 - rgb.min(axis=2).astype(float)
    mask = np.where(white_distance > 26, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[white_distance > 65] = cv2.GC_FGD
    mask[(white_distance < 5)] = cv2.GC_BGD
    mask[:2, :] = mask[-2:, :] = cv2.GC_BGD
    mask[:, :2] = mask[:, -2:] = cv2.GC_BGD
    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 3, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')
    # 只在轮廓邻域去掉白底混色，不全局挖掉银色机翼与舰体高光。
    edge = cv2.dilate((alpha == 0).astype('uint8'), np.ones((3, 3), np.uint8)) > 0
    soft = np.clip((white_distance - 5) / 55, 0, 1)
    alpha[edge] = np.minimum(alpha[edge], soft[edge] * 255)
    a = alpha.astype(float) / 255
    clean = rgb.astype(float)
    partial = (a > 0) & (a < 1)
    clean[partial] = np.clip((clean[partial] - 255 * (1 - a[partial, None])) / a[partial, None], 0, 255)
    sprite = Image.fromarray(np.dstack([clean.astype('uint8'), alpha]))
    return normalize(sprite)


def normalize(sprite):
    """裁去外围留白并统一画幅，不拉伸舰艇和飞机。"""
    box = sprite.getchannel('A').point(lambda v: 255 if v > 20 else 0).getbbox()
    assert box, "模型轮廓为空"
    sprite = sprite.crop(box)
    sprite.thumbnail((368, 368), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384))
    canvas.alpha_composite(sprite, ((384 - sprite.width)//2, (384 - sprite.height)//2))
    return canvas


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    units = json.loads((SOURCE / 'units.json').read_text())
    metadata = []
    for u in units:
        path = SOURCE / 'raw' / (u['id'] + '.png')
        if u['id'] == 'destroyer':
            path = SOURCE / 'destroyer-v2.png'
        if not path.exists():
            continue
        target = DEST / (u['id'] + '.webp')
        if not target.exists() or target.stat().st_mtime < max(path.stat().st_mtime, Path(__file__).stat().st_mtime):
            prepare(path).save(target, quality=93, method=6)
        metadata.append({'id': u['id'], 'source': str(path.relative_to(SOURCE)), 'sourceSize': Image.open(path).size, 'outputSize': [384, 384]})
        print(u['id'], flush=True)
    (SOURCE / 'image-metadata.json').write_text(json.dumps(metadata, indent=2))
    font = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 17)
    for theme, bg, ink in [('light', '#f4f0e6', '#283b36'), ('dark', '#172431', '#eaf0f5')]:
        sheet = Image.new('RGB', (1440, 1000), bg)
        draw = ImageDraw.Draw(sheet)
        for i, u in enumerate(units):
            p = DEST / (u['id'] + '.webp')
            if not p.exists():
                continue
            sprite = Image.open(p)
            sprite.thumbnail((224, 205), Image.Resampling.LANCZOS)
            x, y = i % 6 * 240, i // 6 * 315
            sheet.paste(sprite, (x + (240-sprite.width)//2, y + 10), sprite)
            title = u['name'].split('-')[0]
            code = u['name'].split('（')[-1].rstrip('）')
            draw.text((x+12, y+225), title + ' · ' + code, font=font, fill=ink)
            # 同一张验收图包含首页 72px 和手机 56px 的实际画幅。
            for offset, size in [(12, 56), (88, 72)]:
                small = sprite.copy()
                small.thumbnail((size, 48), Image.Resampling.LANCZOS)
                sheet.paste(small, (x+offset, y+257), small)
        sheet.save(SOURCE / ('models-'+theme+'.jpg'), quality=94)
    print('Prepared', len(metadata), 'unit models.')


if __name__ == '__main__':
    main()
