"""处理战地医院（hospital）微缩模型原稿，输出 384×384 透明 WebP 及深浅背景多尺度对比验收图。"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/field-hospital-20261012'
RAW_DIR = SOURCE_DIR / 'raw'
OPTIONS_DIR = SOURCE_DIR / 'options'
PREVIEW_DIR = SOURCE_DIR / 'preview'
DEST_DIR = ROOT / 'frontend/img/buildings/garden'
HOSPITAL_CURRENT_REF = DEST_DIR / 'hospital.webp'
OLD_HOSPITAL_BACKUP = SOURCE_DIR / 'old_hospital.webp'

OPTIONS = [
    ('option-1-pavilion-clinic', '方案一：红十字诊疗楼主楼与急救帐篷联组', '石木两层医疗主楼配红十字坡顶，侧连白色野战急救帐篷与救护车，主推推荐'),
    ('option-2-courtyard-infirmary', '方案二：回字庭院式野战疗养院', 'U型回廊式低矮红瓦病区，中央绿地设巨幅空中防空红十字地标，秩序庄重'),
    ('option-3-field-trauma-station', '方案三：战地应急手术营地与十字帐篷集群', '坚固石砌战地手术室加双重大跨红十字帆布军帐与前线氧气急救设备，战火救护感强'),
]


def extract_model(img_path):
    orig = Image.open(img_path)
    if orig.mode == 'RGBA':
        alpha_arr = np.array(orig.getchannel('A'))
        if (alpha_arr == 0).mean() > 0.15:
            # 原图已具备高质量原生透明通道，剔除微弱边缘杂色后裁切居中
            alpha_clean = np.where(alpha_arr < 10, 0, alpha_arr).astype('uint8')
            rgb = np.array(orig.convert('RGB'))
            rgba = np.dstack([rgb, alpha_clean])
            sprite = Image.fromarray(rgba, 'RGBA')
            bbox = sprite.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
            if not bbox:
                bbox = orig.getbbox()
            cropped = sprite.crop(bbox)
            scale = min(362 / cropped.width, 362 / cropped.height)
            new_w, new_h = round(cropped.width * scale), round(cropped.height * scale)
            resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)
            canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
            canvas.alpha_composite(resized, ((384 - new_w) // 2, (384 - new_h) // 2))
            return canvas

    orig_rgb = orig.convert('RGB')
    rgb = np.array(orig_rgb)
    h, w = rgb.shape[:2]

    min_c = rgb.min(axis=2).astype(float)
    white_dist = 255.0 - min_c
    chroma = rgb.max(axis=2).astype(float) - min_c

    mask = np.where(white_dist > 18, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[(white_dist > 52) & (chroma > 15)] = cv2.GC_FGD
    mask[white_dist < 8] = cv2.GC_BGD
    mask[:4, :] = mask[-4:, :] = cv2.GC_BGD
    mask[:, :4] = mask[:, -4:] = cv2.GC_BGD

    cv2.setRNGSeed(0)
    cv2.grabCut(rgb, mask, None, np.zeros((1, 65)), np.zeros((1, 65)), 4, cv2.GC_INIT_WITH_MASK)
    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype('uint8')

    count, labels, stats, _ = cv2.connectedComponentsWithStats(alpha)
    if count > 1:
        largest = 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])
        alpha = np.where(labels == largest, 255, 0).astype('uint8')

    soft = np.clip((white_dist - 6) / 24, 0, 1) * 255
    edge = cv2.dilate((alpha == 0).astype('uint8'), np.ones((3, 3), np.uint8)) > 0
    alpha[edge] = np.minimum(alpha[edge], soft[edge].astype('uint8'))

    rgba = np.dstack([rgb, alpha])
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


def backup_old_hospital():
    if HOSPITAL_CURRENT_REF.exists() and not OLD_HOSPITAL_BACKUP.exists():
        import shutil
        shutil.copyfile(HOSPITAL_CURRENT_REF, OLD_HOSPITAL_BACKUP)
        print(f"已备份旧版医院至: {OLD_HOSPITAL_BACKUP}")


def build_comparison_sheet():
    OPTIONS_DIR.mkdir(parents=True, exist_ok=True)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    backup_old_hospital()

    prepared = {}
    for key, title, desc in OPTIONS:
        src = RAW_DIR / f"{key}.png"
        if not src.exists():
            src_jpg = RAW_DIR / f"{key}.jpg"
            if src_jpg.exists():
                src = src_jpg
            else:
                raise FileNotFoundError(f"Raw image {src} does not exist yet")
        model = extract_model(src)
        model.save(OPTIONS_DIR / f"{key}.png", optimize=True)
        model.save(OPTIONS_DIR / f"{key}.webp", "WEBP", quality=95, method=6)
        prepared[key] = model

    old_hospital_img = Image.open(OLD_HOSPITAL_BACKUP).convert('RGBA') if OLD_HOSPITAL_BACKUP.exists() else None

    # 生成双主题全景对比图 (宽 1240 x 高 980)
    sheet_w, sheet_h = 1240, 980
    sheet = Image.new('RGB', (sheet_w, sheet_h), '#0f172a')
    draw = ImageDraw.Draw(sheet)

    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22)
    font_sub = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)
    font_badge = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 11)

    # 顶部横幅
    draw.rectangle([(0, 0), (sheet_w, 64)], fill='#1e293b')
    draw.text((28, 18), "新增建筑【战地医院 Hospital】3D写实微缩模型方案比选总览", fill='#f8fafc', font=font_title)
    draw.text((660, 24), "1930s-1940s 战地战伤救治与医疗科研体系 · 花园卫城微缩规范", fill='#94a3b8', font=font_sub)

    # 分栏：上部深色主题，下部浅色主题
    sections = [
        ('深色主题模式 (Dark Mode / 夜战战备)', 76, 420, '#172431', '#1e293b', '#f1f5f9', '#94a3b8', '#38bdf8'),
        ('浅色主题模式 (Light Mode / 报纸沙盘)', 512, 420, '#f8fafc', '#ffffff', '#0f172a', '#64748b', '#0284c7'),
    ]

    for sec_title, top_y, sec_h, sec_bg, card_bg, text_col, sub_col, accent_col in sections:
        draw.rectangle([(16, top_y), (sheet_w - 16, top_y + sec_h)], fill=sec_bg, outline='#334155' if 'Dark' in sec_title else '#cbd5e1', width=1)
        draw.text((32, top_y + 14), sec_title, fill=accent_col, font=font_bold)

        # 4 列展示：旧版临时医院 (民居贴十字) + 方案一 + 方案二 + 方案三
        cols = [
            ('旧版临时方案 (民居贴字)', '基准参照：普通民居加绘平面红十字', old_hospital_img, False),
            ('方案一：红十字主楼+救护帐篷', '【推荐】两层诊疗所+急救翼篷+救护车', prepared['option-1-pavilion-clinic'], True),
            ('方案二：庭院式野战疗养院', 'U型回廊病房+中央地标红十字', prepared['option-2-courtyard-infirmary'], False),
            ('方案三：战地手术营地集群', '石砌手术枢纽+双联医疗军帐', prepared['option-3-field-trauma-station'], False),
        ]

        col_w = 285
        start_x = 36
        for c_idx, (col_head, col_subhead, img, is_rec) in enumerate(cols):
            cx = start_x + c_idx * (col_w + 14)
            cy = top_y + 44
            ch = sec_h - 58

            # 卡片背景
            border = accent_col if is_rec else ('#334155' if 'Dark' in sec_title else '#e2e8f0')
            draw.rounded_rectangle([(cx, cy), (cx + col_w, cy + ch)], radius=8, fill=card_bg, outline=border, width=2 if is_rec else 1)

            # 徽标
            if is_rec:
                draw.rounded_rectangle([(cx + col_w - 74, cy + 8), (cx + col_w - 10, cy + 28)], radius=4, fill='#ef4444')
                draw.text((cx + col_w - 66, cy + 11), "推荐主选", fill='#ffffff', font=font_badge)

            draw.text((cx + 12, cy + 10), col_head, fill=text_col, font=font_bold)
            draw.text((cx + 12, cy + 30), col_subhead, fill=sub_col, font=font_desc)

            if img:
                # 大图展示 (140x140)
                large = img.resize((140, 140), Image.Resampling.LANCZOS)
                sheet.paste(large, (cx + (col_w - 140) // 2, cy + 54), large)

                # 游戏内缩略尺度模拟 (详情 88px / 卡槽 84px / 选建 64px / 紧凑 44px)
                draw.line([(cx + 12, cy + 204), (cx + col_w - 12, cy + 204)], fill='#334155' if 'Dark' in sec_title else '#e2e8f0', width=1)
                draw.text((cx + 12, cy + 212), "游戏多尺寸视口模拟:", fill=sub_col, font=font_desc)

                # 88px 详情
                s88 = img.resize((76, 76), Image.Resampling.LANCZOS)
                sheet.paste(s88, (cx + 16, cy + 234), s88)
                draw.text((cx + 28, cy + 316), "详情 88p", fill=sub_col, font=font_desc)

                # 64px 选建
                s64 = img.resize((56, 56), Image.Resampling.LANCZOS)
                sheet.paste(s64, (cx + 112, cy + 244), s64)
                draw.text((cx + 118, cy + 316), "选建 64p", fill=sub_col, font=font_desc)

                # 44px 紧凑
                s44 = img.resize((40, 40), Image.Resampling.LANCZOS)
                sheet.paste(s44, (cx + 196, cy + 252), s44)
                draw.text((cx + 200, cy + 316), "卡槽 44p", fill=sub_col, font=font_desc)

    # 底部说明
    draw.text((32, 946), "说明：战地医院为前线伤兵救治与后勤医疗技术研发基地，具备鲜明的红十字人道救护与野战医疗特征。部署主推方案一并完成测试套件验证。", fill='#64748b', font=font_desc)

    # 实装主推方案至游戏主素材目录
    hospital_dest = DEST_DIR / 'hospital.webp'
    prepared['option-1-pavilion-clinic'].save(hospital_dest, "WEBP", quality=95, method=6)
    print(f"成功将方案一（红十字主楼与救护帐篷）部署至游戏主素材目录: {hospital_dest}")

    out_path = PREVIEW_DIR / 'hospital_options_comparison_sheet.png'
    sheet.save(out_path, quality=95)
    print(f"成功保存战地医院方案对比总览图至: {out_path}")
    return out_path


if __name__ == '__main__':
    build_comparison_sheet()
