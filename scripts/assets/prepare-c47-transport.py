"""处理 C-47 运输机写实微缩模型，精准扣除纯白背景（保留机身与机翼白色黑白斑马纹），输出 384x384 透明 WebP 和深浅背景多尺度验收图。"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/c47-transport-20261010'
RAW_IMAGE = SOURCE_DIR / 'raw/c47_transport.png'
DEST_DIR = ROOT / 'frontend/img/units/models'
DEST_FILE = DEST_DIR / 'transport.webp'
BOMBER_REF = DEST_DIR / 'bomber.webp'
OLD_TRANSPORT = SOURCE_DIR / 'old_transport.webp'


def cutout_plane(path):
    orig = Image.open(path).convert('RGB')
    rgb = np.array(orig)
    h, w = rgb.shape[:2]

    # 背景为近似纯白 (#fcfcfc - #ffffff)
    white_dist = 255.0 - rgb.min(axis=2).astype(float)

    # 运输机身上有诺曼底空投特有的白色黑白斑马纹（255, 255, 255），
    # 不能简单基于单像素阈值挖白，必须结合 GrabCut 区域语义分割与外轮廓孔洞闭合。
    mask = np.where(white_dist > 25, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[white_dist > 60] = cv2.GC_FGD
    # 四周边缘属于必定背景
    mask[:4, :] = cv2.GC_BGD
    mask[-4:, :] = cv2.GC_BGD
    mask[:, :4] = cv2.GC_BGD
    mask[:, -4:] = cv2.GC_BGD

    cv2.setRNGSeed(0)
    bgd_model = np.zeros((1, 65), np.float64)
    fgd_model = np.zeros((1, 65), np.float64)
    cv2.grabCut(rgb, mask, None, bgd_model, fgd_model, 5, cv2.GC_INIT_WITH_MASK)

    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')

    # 填充飞机内部因白色反光或白色斑马纹可能产生的微小孔洞
    contours, _ = cv2.findContours(alpha, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    filled_mask = np.zeros_like(alpha)
    for c in contours:
        if cv2.contourArea(c) > 1000:
            cv2.drawContours(filled_mask, [c], -1, 255, thickness=cv2.FILLED)

    # 边缘平滑与抗锯齿处理（de-matting）
    edge = cv2.dilate((filled_mask == 0).astype('uint8'), np.ones((3, 3), np.uint8)) > 0
    soft = np.clip((white_dist - 4) / 45.0, 0, 1)
    final_alpha = filled_mask.copy().astype(float)
    final_alpha[edge] = np.minimum(final_alpha[edge], soft[edge] * 255.0)

    # 边缘去白杂色（消除深色背景下的白边）
    a = (final_alpha / 255.0)[:, :, None]
    clean_rgb = rgb.astype(float)
    partial = (final_alpha > 0) & (final_alpha < 255)
    clean_rgb[partial] = np.clip((clean_rgb[partial] - 255.0 * (1.0 - a[partial])) / np.maximum(a[partial], 0.05), 0, 255)

    sprite = Image.fromarray(np.dstack([clean_rgb.astype('uint8'), final_alpha.astype('uint8')]))
    return normalize(sprite)


def normalize(sprite):
    box = sprite.getchannel('A').point(lambda v: 255 if v > 15 else 0).getbbox()
    if not box:
        raise ValueError("模型轮廓为空")
    cropped = sprite.crop(box)

    # 翼展与轰炸机/战斗机统一规范为 368px，居中放置在 384x384 画布中
    target_span = 368
    ratio = min(target_span / cropped.width, target_span / cropped.height)
    new_w = round(cropped.width * ratio)
    new_h = round(cropped.height * ratio)
    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)

    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    offset = ((384 - new_w) // 2, (384 - new_h) // 2)
    canvas.alpha_composite(resized, offset)
    return canvas


def render_preview_sheets():
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)

    model_c47 = Image.open(DEST_FILE).convert('RGBA')
    model_bomber = Image.open(BOMBER_REF).convert('RGBA') if BOMBER_REF.exists() else None
    model_old = Image.open(OLD_TRANSPORT).convert('RGBA') if OLD_TRANSPORT.exists() else None

    for theme, bg_color, card_bg, ink, sub_ink in [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b'),
    ]:
        sheet = Image.new('RGB', (1200, 680), bg_color)
        draw = ImageDraw.Draw(sheet)

        # 头部标题
        draw.text((30, 20), 'C-47 运输机写实微缩模型验收 · ' + ('深色主题' if theme == 'dark' else '浅色主题'), font=font_title, fill=ink)
        draw.text((30, 52), '专属视觉特征：D-Day 黑白侵略条纹（斑马纹）、侧方跳伞货舱门、钝圆货运机身、双发螺旋桨，彻底杜绝与轰炸机撞脸', font=font_desc, fill=sub_ink)

        # 左侧卡片：新 C-47 运输机模型
        draw.rectangle([(30, 85), (400, 645)], fill=card_bg, outline='#334155' if theme == 'dark' else '#e2e8f0', width=1)
        draw.text((45, 105), '【全新】C-47 空中列车运输机', font=font_label, fill='#38bdf8' if theme == 'dark' else '#0284c7')
        draw.text((45, 130), '黑白斑马纹 · 侧开跳伞货舱门 · 双发螺旋桨', font=font_desc, fill=sub_ink)

        large_c47 = model_c47.resize((240, 240), Image.Resampling.LANCZOS)
        sheet.paste(large_c47, (65, 165), large_c47)

        draw.text((45, 435), '游戏实际视口规格检查：', font=font_desc, fill=sub_ink)
        s72 = model_c47.resize((72, 72), Image.Resampling.LANCZOS)
        sheet.paste(s72, (45, 465), s72)
        draw.text((45, 550), '72px 详情/桌面', font=font_desc, fill=ink)

        s56 = model_c47.resize((56, 56), Image.Resampling.LANCZOS)
        sheet.paste(s56, (165, 473), s56)
        draw.text((165, 550), '56px 列表视口', font=font_desc, fill=ink)

        s48 = model_c47.resize((48, 48), Image.Resampling.LANCZOS)
        sheet.paste(s48, (275, 477), s48)
        draw.text((275, 550), '48px 密集表格', font=font_desc, fill=ink)

        # 中间卡片：B-17G 轰炸机（对比项，防止撞脸）
        if model_bomber:
            draw.rectangle([(425, 85), (795, 645)], fill=card_bg, outline='#334155' if theme == 'dark' else '#e2e8f0', width=1)
            draw.text((440, 105), '【对比】B-17G 空中堡垒轰炸机', font=font_label, fill='#f59e0b' if theme == 'dark' else '#d97706')
            draw.text((440, 130), '四发重轰 · 纯军绿机身 · 防御自卫炮塔', font=font_desc, fill=sub_ink)

            large_b17 = model_bomber.resize((240, 240), Image.Resampling.LANCZOS)
            sheet.paste(large_b17, (460, 165), large_b17)

            draw.text((440, 435), '轰炸机视口尺寸对照：', font=font_desc, fill=sub_ink)
            b72 = model_bomber.resize((72, 72), Image.Resampling.LANCZOS)
            sheet.paste(b72, (440, 465), b72)
            draw.text((440, 550), '72px 轰炸机', font=font_desc, fill=ink)

            b56 = model_bomber.resize((56, 56), Image.Resampling.LANCZOS)
            sheet.paste(b56, (560, 473), b56)
            draw.text((560, 550), '56px 轰炸机', font=font_desc, fill=ink)

            b48 = model_bomber.resize((48, 48), Image.Resampling.LANCZOS)
            sheet.paste(b48, (670, 477), b48)
            draw.text((670, 550), '48px 轰炸机', font=font_desc, fill=ink)

        # 右侧卡片：旧版运输机（已被替换）
        if model_old:
            draw.rectangle([(820, 85), (1170, 645)], fill=card_bg, outline='#334155' if theme == 'dark' else '#e2e8f0', width=1)
            draw.text((835, 105), '【旧版】原运输机（已废弃）', font=font_label, fill='#ef4444' if theme == 'dark' else '#dc2626')
            draw.text((835, 130), '纯暗绿涂装 · 轮廓与轰炸机雷同 · 辨识度差', font=font_desc, fill=sub_ink)

            large_old = model_old.resize((240, 240), Image.Resampling.LANCZOS)
            sheet.paste(large_old, (855, 165), large_old)

            draw.text((835, 435), '旧版视口对照：', font=font_desc, fill=sub_ink)
            o72 = model_old.resize((72, 72), Image.Resampling.LANCZOS)
            sheet.paste(o72, (835, 465), o72)
            draw.text((835, 550), '72px 旧运输机', font=font_desc, fill=sub_ink)

            o56 = model_old.resize((56, 56), Image.Resampling.LANCZOS)
            sheet.paste(o56, (955, 473), o56)
            draw.text((955, 550), '56px 旧运输机', font=font_desc, fill=sub_ink)

            o48 = model_old.resize((48, 48), Image.Resampling.LANCZOS)
            sheet.paste(o48, (1065, 477), o48)
            draw.text((1065, 550), '48px 旧运输机', font=font_desc, fill=sub_ink)

        sheet.save(SOURCE_DIR / f'preview-{theme}.jpg', quality=95)
        print(f'Saved preview-{theme}.jpg')


def render_table_comparison():
    user_img_path = Path.home() / '.gemini/antigravity/brain/9e95bd95-2b24-43d4-9674-df0e02fe2be4/.user_uploaded/media_1791596425778_2fc4bd08.png'
    if not user_img_path.exists():
        return
    user_img = Image.open(user_img_path).convert('RGB')
    uw, uh = user_img.size

    c47_model = Image.open(DEST_FILE).convert('RGBA')
    c47_icon = c47_model.resize((70, 70), Image.Resampling.LANCZOS)

    # 生成修改后表格截图模拟
    after_img = user_img.copy()
    # 清空旧图标区域
    for y in range(165, 245):
        for x in range(655, 745):
            after_img.putpixel((x, y), (255, 255, 255))
    after_img.paste(c47_icon, (660, 172), c47_icon)

    # 制作左右对比大图
    comp = Image.new('RGB', (uw * 2 + 20, uh + 60), '#0f172a')
    draw = ImageDraw.Draw(comp)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 18)
    font_sub = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13)

    comp.paste(user_img, (0, 60))
    comp.paste(after_img, (uw + 20, 60))

    draw.text((20, 12), '【修改前】轰炸机与运输机均为橄榄绿机体，远看轮廓与颜色几乎完全雷同', fill='#f87171', font=font_title)
    draw.text((20, 38), '辨识痛点：小尺寸（48-64px）下玩家极易混淆空军兵种', fill='#94a3b8', font=font_sub)

    draw.text((uw + 40, 12), '【修改后】C-47 运输机专属诺曼底黑白斑马纹 + 侧面跳伞货舱门', fill='#4ade80', font=font_title)
    draw.text((uw + 40, 38), '视觉效果：即使缩小到 48px，黑白高对比横条纹也一眼可知是空投部队运输机！', fill='#38bdf8', font=font_sub)

    comp_dest = SOURCE_DIR / 'transport_vs_bomber_comparison.png'
    comp.save(comp_dest)
    print(f'Saved {comp_dest}')

    # 同步复制到 artifacts 目录便于展示
    artifact_copy = Path.home() / '.gemini/antigravity/brain/9e95bd95-2b24-43d4-9674-df0e02fe2be4/transport_vs_bomber_comparison.png'
    comp.save(artifact_copy)
    print(f'Copied to artifact: {artifact_copy}')


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    if not RAW_IMAGE.exists():
        raise FileNotFoundError(f'{RAW_IMAGE} 不存在，请先保存原图')

    print('Processing C-47 Transport aircraft model cutout...')
    sprite = cutout_plane(RAW_IMAGE)
    sprite.save(DEST_FILE, 'WEBP', quality=94, method=6)
    print(f'Successfully saved transparent model to {DEST_FILE}')

    print('Rendering preview sheets...')
    render_preview_sheets()
    render_table_comparison()
    print('Done all C-47 transport plane processing!')


if __name__ == '__main__':
    main()
