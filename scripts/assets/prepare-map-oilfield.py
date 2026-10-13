#!/usr/bin/env python3
"""Rebuild the map oilfield from the resource-tab oilfield artwork."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "frontend/img/buildings/garden/oilfield.webp"
MAP = ROOT / "frontend/img/map"
SIZE = 384
FRAME_COUNT = 12
PUMPJACK_PIVOT = (151, 106)
PUMPJACK_BEAM = [
    (60, 27), (96, 27), (105, 47), (190, 82), (213, 96),
    (211, 112), (187, 104), (101, 69), (99, 111), (84, 115),
    (78, 91), (69, 77), (61, 73),
]


# Baseline map terrain colors sampled from user references (图二草坪色、图三黑土色)
GRASS = (104, 103, 71)    # 大地图草坪基准色（橄榄金绿）
SOIL = (92, 81, 62)       # 大地图黑土基准色（深棕褐色）
DARK_SOIL = (84, 74, 56)  # 作业区与机械底盘油污深土色


def smooth(edge0: float, edge1: float, value: float) -> float:
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


def square_ground() -> Image.Image:
    """Create map-colored ground with a square-like footprint and feathered irregular edges."""
    ground = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pixels = ground.load()

    cx, cy = 192.0, 192.0
    half_size = 168.0
    corner_r = 28.0

    for y in range(SIZE):
        for x in range(SIZE):
            dx = abs(x - cx)
            dy = abs(y - cy)

            # 类似方形的底座骨架（带倒角圆润）
            corner_dx = max(0.0, dx - (half_size - corner_r))
            corner_dy = max(0.0, dy - (half_size - corner_r))
            corner_dist = math.sqrt(corner_dx * corner_dx + corner_dy * corner_dy)
            d_base = (half_size - max(dx, dy)) if (corner_dx == 0 or corner_dy == 0) else (corner_r - corner_dist)

            # 多频柏林/正弦噪声调制：生成自然草丛凹凸与泥舌延伸的不规则边缘
            n1 = math.sin(x * 0.038 + 1.2) * 11.0 + math.cos(y * 0.042 - 0.7) * 9.5
            n2 = math.sin(x * 0.085 - y * 0.048) * 6.5 + math.sin(y * 0.098 + x * 0.035) * 5.5
            n3 = math.sin(x * 0.19 + y * 0.13) * 3.0 + math.sin(x * 0.31 - y * 0.24) * 2.0
            edge_noise = n1 + n2 + n3
            d_effective = d_base + edge_noise

            # 边缘羽化平滑过渡
            alpha_factor = smooth(-6.0, 26.0, d_effective)
            if alpha_factor <= 0.0:
                continue

            alpha = round(255 * alpha_factor)

            # 颜色混合：
            # 1. 最外侧过渡带（羽化边缘区）：100% 锚定在大地图草坪色 (104, 103, 71)，使羽化半透明融入草坪时无任何色差与色边
            # 2. 向内部逐步过渡：草坪色与黑土色自然交融斑驳
            # 3. 中心及作业区：以黑土色和油污深土为主
            inward = smooth(4.0, 48.0, d_effective)
            dist_center = math.sqrt((x - cx) ** 2 + (y - (cy + 15)) ** 2)
            center_focus = 1.0 - smooth(50.0, 150.0, dist_center)

            patch_noise = math.sin(x * 0.045 + y * 0.028) * 0.18 + math.sin(y * 0.058 - x * 0.032) * 0.12
            soil_share = (0.50 * inward + 0.45 * center_focus * inward + patch_noise)
            soil_share = max(0.0, min(1.0, soil_share))

            # 地表高频微细杂色（grain），与大地图地表噪波完全契合
            grain = math.sin(x * 0.17 + y * 0.071) * 3.5 + math.sin(y * 0.139 - x * 0.11) * 2.5

            if soil_share > 0.65:
                deep_t = (soil_share - 0.65) / 0.35
                base_col = tuple(SOIL[i] * (1.0 - deep_t) + DARK_SOIL[i] * deep_t for i in range(3))
            else:
                t = soil_share / 0.65
                base_col = tuple(GRASS[i] * (1.0 - t) + SOIL[i] * t for i in range(3))

            color = tuple(max(0, min(255, round(base_col[i] + grain))) for i in range(3))
            pixels[x, y] = (*color, alpha)

    return ground


def extract_equipment(source: Image.Image) -> Image.Image:
    """Suppress the warm ground slab while retaining darker machinery, fences, and contact shadows."""
    source = source.convert("RGBA")
    pixels = source.load()
    alpha = Image.new("L", (SIZE, SIZE), 0)
    mask = alpha.load()
    for y in range(SIZE):
        for x in range(SIZE):
            red, green, blue, opacity = pixels[x, y]
            if opacity == 0:
                continue
            luminance = red * .30 + green * .59 + blue * .11
            warm_ground = red - green > 10 and green - blue > 6 and luminance > 82
            if warm_ground:
                # 剔除原画泛黄的凸起菱形底盘，改由不规则黑土草坪方盘承托
                suppression = smooth(65, 150, y)
            else:
                # 保留抽油机支架、管道、围栏与投影，仅在画布极底部做微量渐变
                suppression = smooth(320, 355, y)
            mask[x, y] = round(opacity * (1.0 - suppression))
    alpha = alpha.filter(ImageFilter.GaussianBlur(.65))
    source.putalpha(Image.eval(alpha, lambda value: min(255, value)))
    return source


def make_base() -> tuple[Image.Image, Image.Image]:
    source = Image.open(SOURCE).convert("RGBA")
    source.thumbnail((SIZE - 16, SIZE - 24), Image.Resampling.LANCZOS)
    equipment = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    equipment.alpha_composite(source, ((SIZE - source.width) // 2, (SIZE - source.height) // 2 - 2))
    equipment = extract_equipment(equipment)

    # Isolate the walking beam and horsehead so each frame can rock around its real pivot.
    beam_mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(beam_mask).polygon(PUMPJACK_BEAM, fill=255)
    beam = equipment.copy()
    beam.putalpha(ImageChops.multiply(beam.getchannel("A"), beam_mask))
    static_equipment = equipment.copy()
    static_equipment.putalpha(ImageChops.multiply(static_equipment.getchannel("A"), ImageChops.invert(beam_mask)))

    base = square_ground()
    base.alpha_composite(static_equipment)

    # Unevenly feather the outermost boundary of the complete artwork
    edge = Image.new("L", (SIZE, SIZE), 0)
    edge_pixels = edge.load()
    for y in range(SIZE):
        for x in range(SIZE):
            variation = math.sin(x * .055 + 1.4) * 4 + math.sin(y * .073 - .8) * 3
            distance = min(x, y, SIZE - 1 - x, SIZE - 1 - y) + variation
            edge_pixels[x, y] = round(255 * smooth(1, 22, distance))
    edge = edge.filter(ImageFilter.GaussianBlur(1.5))
    base.putalpha(ImageChops.multiply(base.getchannel("A"), edge))
    beam.putalpha(ImageChops.multiply(beam.getchannel("A"), edge))
    return base, beam


def animate_pumpjack(base: Image.Image, beam: Image.Image, index: int) -> Image.Image:
    """Rock the pumpjack beam through a smooth loop around the fixed tower pivot."""
    phase = math.tau * index / FRAME_COUNT
    angle = math.sin(phase) * 6.0
    moving_beam = beam.rotate(-angle, resample=Image.Resampling.BICUBIC, center=PUMPJACK_PIVOT)
    frame = base.copy()
    frame.alpha_composite(moving_beam)
    return frame


def main() -> None:
    base, beam = make_base()
    for index in range(FRAME_COUNT):
        frame = animate_pumpjack(base, beam, index)
        frame.save(MAP / "wild-oil-frames" / f"frame_{index:02d}.png", optimize=True)
    base.save(MAP / "wild-oil-integrated-map-embedded.png", optimize=True)
    base.save(MAP / "wild-oil-map-embedded.png", optimize=True)
    base.save(MAP / "wild-oil-integrated.webp", "WEBP", quality=94, method=6)
    base.save(MAP / "wild-oil-animated.webp", "WEBP", quality=94, method=6)


if __name__ == "__main__":
    main()
