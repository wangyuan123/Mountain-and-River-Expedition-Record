"""在现有民居模型上添加屋顶医疗标识，制作相同尺寸和透明留白的战地医院图标。"""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'frontend/img/buildings/garden/house.webp'
DESTINATION = ROOT / 'frontend/img/buildings/garden/hospital.webp'


def add_medical_panel(draw, origin, horizontal, vertical):
    """沿屋顶平面投影白底红十字，保持模型原有等距视角。"""
    def project(point):
        u, v = point
        return (origin[0] + u * horizontal[0] + v * vertical[0],
                origin[1] + u * horizontal[1] + v * vertical[1])

    draw.polygon([project(point) for point in [(0, 0), (1, 0), (1, 1), (0, 1)]],
                 fill=(235, 230, 213, 245))
    cross = [(0.38, 0.14), (0.62, 0.14), (0.62, 0.38), (0.86, 0.38),
             (0.86, 0.62), (0.62, 0.62), (0.62, 0.86), (0.38, 0.86),
             (0.38, 0.62), (0.14, 0.62), (0.14, 0.38), (0.38, 0.38)]
    draw.polygon([project(point) for point in cross], fill=(164, 45, 39, 255))


def prepare_hospital():
    """复用已有建筑的像素及透明通道，只增加两个清晰的屋顶医疗标识。"""
    scale = 4
    model = Image.open(SOURCE).convert('RGBA')
    overlay = Image.new('RGBA', (model.width * scale, model.height * scale))
    draw = ImageDraw.Draw(overlay)
    for origin, horizontal, vertical in [
        ((169, 64), (40, -20), (25, 13)),
        ((248, 135), (28, -14), (29, 16))
    ]:
        add_medical_panel(draw, tuple(value * scale for value in origin),
                          tuple(value * scale for value in horizontal),
                          tuple(value * scale for value in vertical))
    overlay = overlay.resize(model.size, Image.Resampling.LANCZOS)
    Image.alpha_composite(model, overlay).save(DESTINATION, 'WEBP', quality=95, method=6)
    print(DESTINATION)


if __name__ == '__main__':
    prepare_hospital()
