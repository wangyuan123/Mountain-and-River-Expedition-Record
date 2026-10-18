#!/usr/bin/env python3
"""
将 1024x1536 的真实 3D 俯视微缩模型城墙原图，精细处理为：
1. 完整的微缩模型图（真实 3D 俯视视角、真实花岗岩材质与光影）
2. 城门严格位于 50% 正中央
3. 城门两侧的城墙完全对称
4. 占满指挥面板高度（全高贯穿，顶端底端均有棱堡收口）
5. 攻方视角城门朝左，守方视角城门朝右
"""

from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
RAW_PATH = ROOT / 'output/imagegen/tactical-wall-20261014/raw/isometric_wall_raw.png'
DEST_DIR = ROOT / 'frontend/img/cities'

def main():
    if not RAW_PATH.exists():
        raise FileNotFoundError(f"Raw image not found: {RAW_PATH}")

    im = Image.open(RAW_PATH)
    print(f"Loaded raw 3D isometric model: {im.size}, mode: {im.mode}")

    # 1. 精确获取主体 X 范围和门楼 Y 中心
    arr = np.array(im)
    alpha = arr[:, :, 3]
    
    # 查找门楼中心 Y（通过最宽行区间计算）
    row_widths = []
    for y in range(im.height):
        xs = np.where(alpha[y, :] > 20)[0]
        if len(xs) > 0:
            row_widths.append((xs.max() - xs.min(), xs.min(), xs.max(), y))
            
    # 按宽度排序取前 20 行的平均 Y 作为门楼几何中心
    widest_rows = sorted(row_widths, key=lambda x: x[0], reverse=True)[:25]
    gate_center_y = int(np.median([r[3] for r in widest_rows]))
    print(f"Calculated gatehouse center Y: {gate_center_y}")

    # 寻找整体主体 X 范围
    all_xs = np.where(alpha > 20)[1]
    min_x, max_x = all_xs.min(), all_xs.max()
    print(f"Wall horizontal span: X={min_x} to {max_x} (width: {max_x - min_x})")

    # 2. 裁切出主体纵向长条
    # 门楼半高：门楼核心区域覆盖 gate_center_y ± 150
    gate_half = 140
    # 城墙单翼半长（自门楼向外延伸的对称城墙长度）
    wing_len = 540
    
    total_half = gate_half + wing_len  # 680
    
    # 上半段 (Y: gate_center_y - total_half 到 gate_center_y)
    top_y1 = max(0, gate_center_y - total_half)
    top_y2 = gate_center_y
    upper_crop = im.crop((min_x - 10, top_y1, max_x + 10, top_y2))
    print(f"Upper section crop size: {upper_crop.size}")

    # 下半段由上半段垂直镜像生成（确保两侧城墙、城垛、角楼严格绝对对称！）
    lower_crop = upper_crop.transpose(Image.Transpose.FLIP_TOP_BOTTOM)

    # 3. 拼合形成完整对称的 3D 俯视微缩城墙模型
    sym_w = upper_crop.width
    sym_h = upper_crop.height * 2
    sym_canvas = Image.new('RGBA', (sym_w, sym_h), (0, 0, 0, 0))
    sym_canvas.paste(upper_crop, (0, 0))
    sym_canvas.paste(lower_crop, (0, upper_crop.height))

    # 接缝平滑处理（在中心 Y 附近微小模糊过渡）
    seam_y = upper_crop.height
    seam_strip = sym_canvas.crop((0, seam_y - 3, sym_w, seam_y + 3))
    seam_strip_smooth = seam_strip.filter(ImageFilter.SMOOTH)
    sym_canvas.paste(seam_strip_smooth, (0, seam_y - 3))

    # 4. 调整为标准战术指挥面板资产规格 (360 x 960)
    target_w, target_h = 360, 960
    # 保持高度铺满 960
    scale = target_h / sym_h
    scaled_w = int(sym_w * scale)
    scaled_img = sym_canvas.resize((scaled_w, target_h), Image.Resampling.LANCZOS)
    
    final_left = Image.new('RGBA', (target_w, target_h), (0, 0, 0, 0))
    # 贴合靠右，城门与正面朝左
    paste_x = target_w - scaled_w
    final_left.paste(scaled_img, (paste_x, 0), scaled_img)

    DEST_DIR.mkdir(parents=True, exist_ok=True)

    # 5. 保存资产
    # 攻方视角：立于右侧，城门朝左（面向左方敌军），高 960px 占满高度，门在 480px 正中间，上下对称
    left_path = DEST_DIR / 'tactical-city-wall-left.webp'
    final_left.save(left_path, format='WEBP', quality=96, method=6)
    print(f"Saved: {left_path} ({left_path.stat().st_size} bytes)")

    # 守方视角：立于左侧，城门朝右（面向右方敌军），高 960px 占满高度，门在 480px 正中间，上下对称
    wall_right = final_left.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    right_path = DEST_DIR / 'tactical-city-wall-right.webp'
    wall_right.save(right_path, format='WEBP', quality=96, method=6)
    print(f"Saved: {right_path} ({right_path.stat().st_size} bytes)")

    # 兼容性更新
    compat_path = DEST_DIR / 'player-city-wall-front.webp'
    final_left.save(compat_path, format='WEBP', quality=96, method=6)
    print(f"Updated: {compat_path}")

if __name__ == '__main__':
    main()
