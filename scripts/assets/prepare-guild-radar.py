"""处理联防雷达（guild_radar）微缩模型原稿，输出 384×384 透明 WebP 及深浅背景多尺度对比验收图。"""
from pathlib import Path
import shutil
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/guild-radar-20261026'
RAW_DIR = SOURCE_DIR / 'raw'
OPTIONS_DIR = SOURCE_DIR / 'options'
PREVIEW_DIR = SOURCE_DIR / 'preview'
DEST_DIR = ROOT / 'frontend/img/buildings/garden'
GUILD_RADAR_TARGET = DEST_DIR / 'guild_radar.webp'

OPTIONS = [
    ('option-1-parabolic-dish', '方案 A · 弧形网面扫描阵', '旋转式金属抛物面网格雷达天线，外围环绕同心电磁脉冲光弧，沙袋通讯小屋'),
    ('option-2-surveillance-relay', '方案 B · 广域中继预警台', '钢架高塔承载曲面微波监控天线，清晰同心水平雷达波纹，带独立发电机房与粗线缆'),
    ('option-3-phased-array-bunker', '方案 C · 矩阵相控阵要塞', '重型钢架早期远程矩阵天线网，高明度同心脉冲电磁波弧环，战术地堡与柴油发电机组【用户选定实装】'),
]


def extract_model(img_path):
    orig = Image.open(img_path)
    if orig.mode == 'RGBA':
        alpha_arr = np.array(orig.getchannel('A'))
        if (alpha_arr == 0).mean() > 0.15:
            alpha_clean = np.where(alpha_arr < 10, 0, alpha_arr).astype('uint8')
            rgb = np.array(orig.convert('RGB'))
            rgba = np.dstack([rgb, alpha_clean])
            sprite = Image.fromarray(rgba, 'RGBA')
            bbox = sprite.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox() or orig.getbbox()
            cropped = sprite.crop(bbox)
            scale = min(362 / cropped.width, 362 / cropped.height)
            new_w, new_h = round(cropped.width * scale), round(cropped.height * scale)
            resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)
            canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
            canvas.alpha_composite(resized, ((384 - new_w) // 2, (384 - new_h) // 2))
            return canvas

    im = orig.convert('RGB')
    orig_rgb = np.array(im.resize((768, 768), Image.Resampling.LANCZOS))
    h, w = orig_rgb.shape[:2]
    yy, xx = np.mgrid[:h, :w]
    x, y = xx / w, yy / h
    border = (x < .025) | (x > .975) | (y < .025) | (y > .975)
    features = np.stack([np.ones_like(x), x, y, x*y, x*x, y*y], axis=-1)
    samples = border.copy()
    for _ in range(5):
        coefficients = np.linalg.lstsq(features[samples], orig_rgb[samples].astype(float), rcond=None)[0]
        distance = np.linalg.norm(orig_rgb - features @ coefficients, axis=2)
        samples = border & (distance < max(10, np.percentile(distance[border], 70)))

    mask = np.where(distance > 20, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    chroma = orig_rgb.max(axis=2).astype(float) - orig_rgb.min(axis=2)
    mask[(distance > 60) & (chroma > 20)] = cv2.GC_FGD
    mask[border & (distance < 30)] = cv2.GC_BGD
    cv2.setRNGSeed(0)
    cv2.grabCut(orig_rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 5, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')

    # 保留天线周围的电磁波纹/雷达脉冲光环 (空中区域 y < 0.45)
    sky = (y < 0.45) & (distance > 12)
    alpha[sky] = np.maximum(alpha[sky], np.clip((distance[sky] - 10) / 30.0 * 255, 0, 255).astype('uint8'))

    count, labels, stats, _ = cv2.connectedComponentsWithStats((alpha > 20).astype('uint8'))
    for k in range(1, count):
        if stats[k, cv2.CC_STAT_AREA] < 30:
            alpha[labels == k] = 0

    alpha = cv2.GaussianBlur(alpha, (3, 3), .6)
    rgba = np.dstack([orig_rgb, alpha])
    sprite = Image.fromarray(rgba, 'RGBA')
    bbox = sprite.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    if not bbox:
        raise ValueError(f"No silhouette found in {img_path}")
    cropped = sprite.crop(bbox)

    # 规范 362x362 主体置于 384x384 画布
    scale = min(362 / cropped.width, 362 / cropped.height)
    new_w, new_h = round(cropped.width * scale), round(cropped.height * scale)
    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((384 - new_w) // 2, (384 - new_h) // 2))
    return canvas


def main():
    OPTIONS_DIR.mkdir(parents=True, exist_ok=True)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    DEST_DIR.mkdir(parents=True, exist_ok=True)

    for opt_id, name, desc in OPTIONS:
        raw_file = RAW_DIR / f'{opt_id}.jpg'
        if not raw_file.exists():
            raw_file = RAW_DIR / f'{opt_id}.png'
        if not raw_file.exists():
            print(f'Warning: {raw_file} not found')
            continue
        opt_out = OPTIONS_DIR / f'{opt_id}.webp'
        sprite = extract_model(raw_file)
        sprite.save(opt_out, quality=94, method=6)
        print(f'Processed {opt_id} -> {opt_out}')

    # 用户选定方案 C (phased-array-bunker) 部署到游戏素材目录
    rec_file = OPTIONS_DIR / 'option-3-phased-array-bunker.webp'
    if rec_file.exists():
        shutil.copyfile(rec_file, GUILD_RADAR_TARGET)
        print(f'Deployed selected icon to {GUILD_RADAR_TARGET}')


if __name__ == '__main__':
    main()
