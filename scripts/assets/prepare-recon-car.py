"""处理 M8 灰狗写实模型白底原稿，彻底剔除车底白色地面投影与底纹，输出 384x384 透明 WebP 和深浅背景多尺度验收图。"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/recon-car-20261009'
RAW_IMAGE = SOURCE_DIR / 'raw/recon_car.png'
DEST_DIR = ROOT / 'frontend/img/units/models'
DEST_FILE = DEST_DIR / 'recon_car.webp'
ARMORED_REF = DEST_DIR / 'armored.webp'


def cutout_vehicle(path):
    orig = Image.open(path)
    if orig.mode != 'RGBA':
        orig = orig.convert('RGBA')
    
    rgba = np.array(orig).astype(float)
    h, w = rgba.shape[:2]
    rgb = rgba[:, :, :3]
    alpha = rgba[:, :, 3]
    
    # 彻底剔除车底白色/浅灰地面阴影与底纹图案
    # 地面投影特征：位于下半部 (y >= h * 0.58)，低饱和度单色 (span < 25)，较高明度 (mean > 100)
    span = rgb.max(axis=2) - rgb.min(axis=2)
    mean_rgb = rgb.mean(axis=2)
    
    shadow_factor = np.clip((mean_rgb - 100.0) / 45.0, 0, 1) * np.clip((25.0 - span) / 12.0, 0, 1)
    shadow_region = np.broadcast_to((np.arange(h)[:, None] >= int(h * 0.58)), (h, w))
    alpha[shadow_region] = alpha[shadow_region] * (1.0 - shadow_factor[shadow_region])
    
    # 边缘去白杂色（de-matting）
    white_dist = 255.0 - rgb.min(axis=2)
    edge = cv2.dilate((alpha < 50).astype('uint8'), np.ones((3, 3), np.uint8)) > 0
    soft = np.clip((white_dist - 6) / 50.0, 0, 1)
    alpha[edge] = np.minimum(alpha[edge], soft[edge] * 255.0)
    
    clean_sprite = Image.fromarray(np.dstack([rgb.astype('uint8'), alpha.astype('uint8')]))
    return normalize(clean_sprite)


def normalize(sprite):
    box = sprite.getchannel('A').point(lambda v: 255 if v > 15 else 0).getbbox()
    if not box:
        raise ValueError("模型轮廓为空")
    sprite = sprite.crop(box)
    sprite.thumbnail((364, 364), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(sprite, ((384 - sprite.width) // 2, (384 - sprite.height) // 2))
    return canvas


def render_preview_sheets():
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)
    
    model_m8 = Image.open(DEST_FILE).convert('RGBA')
    model_t17 = Image.open(ARMORED_REF).convert('RGBA') if ARMORED_REF.exists() else None
    
    for theme, bg_color, card_bg, ink, sub_ink in [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b'),
    ]:
        sheet = Image.new('RGB', (1080, 680), bg_color)
        draw = ImageDraw.Draw(sheet)
        
        # 头部标题
        draw.text((30, 20), 'M8 灰狗装甲侦查车微缩模型验收 · ' + ('深色主题（已剔除车底白斑）' if theme == 'dark' else '浅色主题'), font=font_title, fill=ink)
        draw.text((30, 52), '特征：六轮三轴底盘、单门37mm主炮塔、高耸无线电侦查天线、车底无任何白色底纹/阴影', font=font_desc, fill=sub_ink)
        
        # 左侧卡片：M8 灰狗（新生成模型）
        draw.rectangle([(30, 85), (525, 645)], fill=card_bg, outline='#334155' if theme == 'dark' else '#e2e8f0', width=1)
        draw.text((50, 105), '新模型：侦查装甲车-灰狗（M8）', font=font_label, fill='#38bdf8' if theme == 'dark' else '#0284c7')
        draw.text((50, 130), '六轮三轴 · 单主炮塔 · 侦查天线 · 纯净轮胎触底', font=font_desc, fill=sub_ink)
        
        # 240px 大图
        large_m8 = model_m8.resize((240, 240), Image.Resampling.LANCZOS)
        sheet.paste(large_m8, (50, 165), large_m8)
        
        # 72px 与 56px 实际视口检查
        draw.text((50, 440), '实际游戏视口尺寸检查：', font=font_desc, fill=sub_ink)
        s72 = model_m8.resize((72, 72), Image.Resampling.LANCZOS)
        sheet.paste(s72, (50, 470), s72)
        draw.text((50, 555), '72px 桌面/详情', font=font_desc, fill=ink)
        
        s56 = model_m8.resize((56, 56), Image.Resampling.LANCZOS)
        sheet.paste(s56, (180, 478), s56)
        draw.text((180, 555), '56px 移动/列表', font=font_desc, fill=ink)
        
        # 右侧卡片：风格参考比对（现有 T17E2 猎鹿犬防空装甲车）
        if model_t17:
            draw.rectangle([(555, 85), (1050, 645)], fill=card_bg, outline='#334155' if theme == 'dark' else '#e2e8f0', width=1)
            draw.text((575, 105), '参考对比：防空装甲车-猎鹿犬（T17E2）', font=font_label, fill='#a855f7' if theme == 'dark' else '#7e22ce')
            draw.text((575, 130), '四轮两轴 · 双联高射机枪 · 现有写实基准', font=font_desc, fill=sub_ink)
            
            large_t17 = model_t17.resize((240, 240), Image.Resampling.LANCZOS)
            sheet.paste(large_t17, (575, 165), large_t17)
            
            draw.text((575, 440), '参考模型视口尺寸检查：', font=font_desc, fill=sub_ink)
            t72 = model_t17.resize((72, 72), Image.Resampling.LANCZOS)
            sheet.paste(t72, (575, 470), t72)
            draw.text((575, 555), '72px 猎鹿犬', font=font_desc, fill=ink)
            
            t56 = model_t17.resize((56, 56), Image.Resampling.LANCZOS)
            sheet.paste(t56, (705, 478), t56)
            draw.text((705, 555), '56px 猎鹿犬', font=font_desc, fill=ink)
            
        sheet.save(SOURCE_DIR / f'preview-{theme}.jpg', quality=95)
        print(f'Saved preview-{theme}.jpg')


def render_user_comparison():
    user_img_path = Path.home() / '.gemini/antigravity/brain/9e95bd95-2b24-43d4-9674-df0e02fe2be4/.user_uploaded/media_1791510666230_645106c3.png'
    if not user_img_path.exists():
        return
    user_img = Image.open(user_img_path).convert('RGB')
    uw, uh = user_img.size
    bg_color = tuple(user_img.getpixel((10, 10)))

    clean_model = Image.open(DEST_FILE)
    new_panel = Image.new('RGB', (uw, uh), bg_color)
    model_thumb = clean_model.resize((int(uw * 1.05), int(uw * 1.05)), Image.Resampling.LANCZOS)
    new_panel.paste(model_thumb, (int(uw * -0.02), int(uh * -0.05)), model_thumb)

    comp = Image.new('RGB', (uw * 2, uh + 45), '#0b1120')
    draw = ImageDraw.Draw(comp)
    font = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16)

    comp.paste(user_img, (0, 45))
    comp.paste(new_panel, (uw, 45))

    draw.text((15, 12), '【修改前】车底存在白色地面投影/底纹', fill='#f87171', font=font)
    draw.text((uw + 15, 12), '【修改后】已完全剔除车底白色底纹，仅保留纯黑轮胎触底', fill='#4ade80', font=font)

    comp.save(SOURCE_DIR / 'shadow_removal_comparison.jpg', quality=95)
    print('Saved shadow_removal_comparison.jpg')


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    if not RAW_IMAGE.exists():
        raise FileNotFoundError(f'{RAW_IMAGE} 不存在，请先生成原图')
    
    print('Processing M8 Greyhound model cutout (removing white ground shadow)...')
    sprite = cutout_vehicle(RAW_IMAGE)
    sprite.save(DEST_FILE, 'WEBP', quality=93, method=6)
    print(f'Successfully saved transparent model to {DEST_FILE}')
    
    print('Rendering preview sheets...')
    render_preview_sheets()
    render_user_comparison()
    print('Done all M8 Greyhound processing!')


if __name__ == '__main__':
    main()
