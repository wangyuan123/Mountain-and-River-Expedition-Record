"""生成所有大地图 寇/NPC据点微缩模型图标（共 5 款写实微缩模型图）。
全新规范：
- 【展示完整图标】：所有单位（士兵、指挥官、步枪刺刀、战车、机枪、火炮、战机、风向袋、工事）100% 完整保留；
- 【边缘不需要模糊处理】：四周边框均为 0° 直角锐利方正切边，彻底去除羽化衰减与半透明过渡；
- 【图标为正方形】：1:1 严格规范正方形（384x384）；
- 【放到地图中占满地图空格】：100% 不透明实心内容（Alpha=255），无透明外边距，在地图单个格子（1x1 cell）中严丝合缝占满空格。

分级映射：
1. 寇·步兵方阵 (npc-phalanx-infantry, Lv.1~5)
2. 寇·轻装甲方阵 (npc-phalanx-light-armor, Lv.6~8)
3. 寇·中坦突击方阵 (npc-fortress-orthogonal, Lv.9~10)
4. 寇·战机航空哨所 (npc-phalanx-aircraft, Lv.11~20)
5. 寇·步坦重装合成战团 (npc-phalanx-mixed, Lv.21~30)
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011/raw'
DEST_DIR = ROOT / 'frontend/img/map'
OUTPUT_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011'

# 大地图标准地形色系（与 frontend/js/constants.js terrainPalette 完全一致）
GRASS = np.array([157.0, 169.0, 126.0])  # 大地图草坪基准色
SIZE = 384


def warp_model(src_path: Path, pts_src: np.ndarray, bg_color: np.ndarray = GRASS) -> Image.Image:
    """对微缩战阵模型进行 0° 正交正方形透视重构，完整展示模型并占满 384x384 正方形网格。"""
    src = cv2.imread(str(src_path))
    if src is None:
        raise FileNotFoundError(f"无法读取原图: {src_path}")
    h, w = src.shape[:2]
    rgb = cv2.cvtColor(src, cv2.COLOR_BGR2RGB).astype(float)
    gray = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
    is_white = (gray > 220) | (rgb.min(axis=2) > 218)

    # 摄影棚白色底板替换为大地图基准草坪色
    composite = np.where(is_white[:, :, None], bg_color, rgb)

    pts_dst = np.array([
        [0, 0],
        [SIZE, 0],
        [SIZE, SIZE],
        [0, SIZE]
    ], dtype=np.float32)

    M = cv2.getPerspectiveTransform(pts_src.astype(np.float32), pts_dst)
    warped = cv2.warpPerspective(composite, M, (SIZE, SIZE), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)
    
    # 转换为 100% 不透明实心 RGBA 格式（Alpha=255，无边缘羽化/模糊）
    im_rgb = Image.fromarray(np.clip(warped, 0, 255).astype(np.uint8), 'RGB')
    im_rgba = im_rgb.convert('RGBA')
    return im_rgba


def process_infantry() -> Image.Image:
    """1. 寇·步兵方阵 (Lv.1~5)：3 排步兵列阵、指挥官军刀、掷弹筒组、机枪卧射组、战壕泥地充满正方形。"""
    src = RAW_DIR / 'infantry_phalanx.jpg'
    pts_inf = np.array([[5, 360], [520, 80], [1015, 340], [450, 920]])
    return warp_model(src, pts_inf)


def process_light_armor() -> Image.Image:
    """2. 寇·轻装甲方阵 (Lv.6~8)：九五式轻坦车长观瞻、边三轮摩托、装甲巡逻车、电线杆与碎石车道充满正方形。"""
    src = RAW_DIR / 'light_armor_phalanx.jpg'
    pts_la = np.array([[220, 25], [830, 35], [920, 740], [200, 750]])
    return warp_model(src, pts_la)


def process_orthogonal() -> Image.Image:
    """3. 寇·中坦突击方阵 (Lv.9~10)：123/124 号九七式中战车双车突击、警戒步兵班、车辙战地充满正方形。"""
    src = RAW_DIR / 'assault_column.jpg'
    pts_as = np.array([[10, 330], [520, 90], [1005, 550], [450, 900]])
    return warp_model(src, pts_as)


def process_aircraft() -> Image.Image:
    """4. 寇·战机航空哨所 (Lv.11~20)：零战与双发攻击机整备、沙袋指挥所、风向袋、野战机场充满正方形。"""
    src = RAW_DIR / 'aircraft_phalanx.jpg'
    pts_air = np.array([[50, 220], [540, 50], [980, 470], [450, 710]])
    return warp_model(src, pts_air)


def process_mixed() -> Image.Image:
    """5. 寇·步坦重装合成战团 (Lv.21~30)：71 号重坦、装甲战车群、冲锋步兵散兵线协同突击充满正方形。"""
    src = RAW_DIR / 'mixed_formation.jpg'
    pts_mix = np.array([[25, 340], [520, 140], [1005, 510], [480, 855]])
    return warp_model(src, pts_mix)


def build_showcase_sheet(models: dict):
    """生成 5 款全新正方形实心微缩模型全家福展示总览长图。"""
    tile_w, tile_h = 240, 240
    margin = 30
    gap = 20
    header_h = 75
    footer_h = 55
    total_w = margin * 2 + tile_w * len(models) + (len(models) - 1) * gap
    total_h = header_h + tile_h + footer_h

    sheet = Image.new('RGB', (total_w, total_h), (26, 28, 26))
    draw = ImageDraw.Draw(sheet)

    font_title = None
    font_sub = None
    for font_path in [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
        '/Library/Fonts/Arial Unicode.ttf'
    ]:
        try:
            font_title = ImageFont.truetype(font_path, 20)
            font_sub = ImageFont.truetype(font_path, 13)
            break
        except Exception:
            continue
    if not font_title:
        font_title = font_sub = ImageFont.load_default()

    draw.text((margin, 20), "全新 0° 正交正方形微缩模型寇图标（完整展示 · 无菱形 · 边缘无模糊 · 100% 占满地图空格）",
              fill=(245, 245, 240), font=font_title)

    for i, (key, (title, img)) in enumerate(models.items()):
        x = margin + i * (tile_w + gap)
        y = header_h
        draw.text((x + 8, y - 26), title, fill=(235, 215, 145), font=font_sub)

        # 模拟地图单元格边框（金色网格线）
        draw.rectangle([x - 2, y - 2, x + tile_w + 2, y + tile_h + 2], outline=(240, 200, 80), width=2)
        thumb = img.resize((tile_w, tile_h), Image.Resampling.LANCZOS)
        sheet.paste(thumb, (x, y))
        draw.text((x + 6, y + tile_w - 22), '正方形充满网格', fill=(220, 255, 200), font=font_sub)

    draw.text((margin, total_h - 35),
              "实装规格：384x384 规范正方形 · 100% 不透明实心内容 · 边缘锐利无羽化虚化 · 严格占满地图单个单元格",
              fill=(180, 190, 180), font=font_sub)

    showcase_path = OUTPUT_DIR / 'npc_all_phalanxes_showcase.png'
    sheet.save(showcase_path, quality=95)
    print(f"展示长图已保存: {showcase_path} ({showcase_path.stat().st_size} bytes)")


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print(">>> 开始生成 5 款全新正方形寇微缩模型图（完整展示 · 无菱形 · 边缘无模糊 · 占满空格）...")
    models = {
        'npc-phalanx-infantry': ('Lv.1~5 步兵方阵', process_infantry()),
        'npc-phalanx-light-armor': ('Lv.6~8 轻装甲方阵', process_light_armor()),
        'npc-fortress-orthogonal': ('Lv.9~10 中坦突击', process_orthogonal()),
        'npc-phalanx-aircraft': ('Lv.11~20 战机哨所', process_aircraft()),
        'npc-phalanx-mixed': ('Lv.21~30 重装合成', process_mixed())
    }

    for name, (title, img) in models.items():
        webp_path = DEST_DIR / f"{name}.webp"
        png_path = DEST_DIR / f"{name}-map-embedded.png"

        # WebP 保存（q=95 高画质）
        img.save(webp_path, 'WEBP', quality=95, method=6)
        # 地图嵌入 PNG（全尺寸 100% 实心无模糊）
        img.save(png_path, 'PNG', optimize=True)
        print(f"✓ 已实装 {title}: {webp_path.name} ({webp_path.stat().st_size // 1024}KB), {png_path.name} ({png_path.stat().st_size // 1024}KB)")

    # 兼容老版城堡别名
    fortress_img = models['npc-fortress-orthogonal'][1]
    fortress_img.save(DEST_DIR / 'npc-fortress.webp', 'WEBP', quality=95, method=6)
    fortress_img.save(DEST_DIR / 'npc-fortress-map.webp', 'WEBP', quality=95, method=6)
    fortress_img.save(DEST_DIR / 'npc-fortress-map-embedded.png', 'PNG', optimize=True)
    print("✓ 已同步兼容老资产别名 npc-fortress.*")

    # 生成展示长图
    build_showcase_sheet(models)
    print(">>> 所有寇正方形微缩模型图标已全部实装完毕！")


if __name__ == '__main__':
    main()
