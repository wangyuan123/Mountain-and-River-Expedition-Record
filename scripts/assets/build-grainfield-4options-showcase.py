import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")

# Load 4 options
opt1 = Image.open(OUT_DIR / "refined_grain_opt1_paddy_grid.png")
opt2 = Image.open(OUT_DIR / "refined_grain_opt2_farmers_waves.png")
opt3 = Image.open(OUT_DIR / "refined_grain_opt3_tractor_harvester.png")
opt4 = Image.open(OUT_DIR / "refined_grain_opt4_granary_homestead.png")

options = [
    ("款型 A：【规整水网·金稻水田】", opt1, "正向2.5D正交俯视 · 纵横田埂水渠 · 矩形金稻方阵", "100%满格严整咬合网格 · 清澈水渠分割 · 金黄垂穗稻谷"),
    ("款型 B：【丰收麦浪·割麦农民】", opt2, "连绵金黄麦海 · 无垄沟壑 · 田间多名微缩割麦农夫", "成片通透麦穗 · 弯腰执镰割麦与肩挑麦捆 · 烟火田园生机"),
    ("款型 C：【战时机械化·拖拉机麦田】", opt3, "1930s战备农业基地 · 红色微缩拖拉机 · 平直割麦道", "机械化收割开辟麦道 · 规整割晒麦茬 · 后勤货车与粮箱"),
    ("款型 D：【田园庄园·红顶谷仓麦海】", opt4, "欧陆乡村庄园 · 微缩红顶木质谷仓 · 麦垛与金麦田", "大片麦田环绕 · 饱满圆锥草垛 · 农耕庄园立体层次丰富")
]

CELL = 260
PAD = 24
TOP_H = 105
ROW1_H = CELL + 70
ROW2_H = CELL + 50
TOTAL_W = PAD * 5 + CELL * 4
TOTAL_H = TOP_H + ROW1_H + ROW2_H + PAD + 10

canvas = Image.new("RGB", (TOTAL_W, TOTAL_H), (18, 22, 28))
draw = ImageDraw.Draw(canvas)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 26)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
font_card_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
font_desc1 = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 13)
font_desc2 = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 12)
font_sec = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 17)

# Header
draw.text((PAD, 18), "全新设计大地图粮田微缩模型：4款设计方案对比评选", fill=(245, 248, 252), font=font_title)
draw.text((PAD, 58), "【严格硬性指标：模型图边框与地图方格水平垂直严格对齐 · 彻底摒弃45°菱形与旧版构图 · 四角精准对齐】", fill=(255, 215, 0), font=font_sub)

# Section 1: 4 款设计原图与网格对齐校验
sec1_y = TOP_H
draw.text((PAD, sec1_y - 2), "【第一部分：4款全新设计模型原图 · 地图方格网格线（金黄色）水平对齐校验】", fill=(100, 220, 255), font=font_sec)

for i, (title, img, desc1, desc2) in enumerate(options):
    x = PAD + i * (CELL + PAD)
    y = sec1_y + 26
    
    # Model preview
    scaled = img.resize((CELL, CELL), Image.Resampling.LANCZOS)
    canvas.paste(scaled, (x, y))
    
    # Grid overlay in Gold / Cyan
    draw.rectangle([x, y, x + CELL, y + CELL], outline=(255, 215, 0), width=2)
    # Corner alignment crosshairs
    tlen = 14
    draw.line([x, y, x + tlen, y], fill=(255, 255, 255), width=3)
    draw.line([x, y, x, y + tlen], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y, x + CELL - tlen, y], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y, x + CELL, y + tlen], fill=(255, 255, 255), width=3)
    draw.line([x, y + CELL, x + tlen, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x, y + CELL, x, y + CELL - tlen], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y + CELL, x + CELL - tlen, y + CELL], fill=(255, 255, 255), width=3)
    draw.line([x + CELL, y + CELL, x + CELL, y + CELL - tlen], fill=(255, 255, 255), width=3)
    
    # Title & description below card
    draw.text((x + 2, y + CELL + 8), title, fill=(245, 248, 250), font=font_card_title)
    draw.text((x + 2, y + CELL + 30), desc1, fill=(210, 225, 240), font=font_desc1)
    draw.text((x + 2, y + CELL + 48), desc2, fill=(160, 180, 200), font=font_desc2)

# Section 2: 大地图草坪与黑土地实机嵌入效果
sec2_y = sec1_y + ROW1_H + 20
draw.text((PAD, sec2_y - 2), "【第二部分：大地图实机模拟 · 分别嵌入草坪（左半格）与黑土地（右半格）真实环境】", fill=(100, 220, 255), font=font_sec)

GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

for i, (title, img, desc1, desc2) in enumerate(options):
    x = PAD + i * (CELL + PAD)
    y = sec2_y + 26
    
    # Background: Split into Left=Grassland, Right=Black soil
    cell_env = Image.new("RGB", (CELL, CELL), (0, 0, 0))
    env_draw = ImageDraw.Draw(cell_env)
    env_draw.rectangle([0, 0, CELL // 2, CELL], fill=GRASS)
    env_draw.rectangle([CELL // 2, 0, CELL, CELL], fill=SOIL)
    
    # Add subtle terrain noise
    arr = np.array(cell_env)
    np.random.seed(200 + i)
    noise = np.random.randint(-7, 7, arr.shape)
    arr = np.clip(arr + noise, 0, 255).astype(np.uint8)
    cell_env = Image.fromarray(arr)
    
    # Paste model
    scaled = img.resize((CELL - 12, CELL - 12), Image.Resampling.LANCZOS)
    cell_env.paste(scaled, (6, 6))
    
    canvas.paste(cell_env, (x, y))
    
    # Map grid border
    draw.rectangle([x, y, x + CELL, y + CELL], outline=(50, 180, 230), width=2)
    # Inner model boundary line (cyan)
    draw.rectangle([x + 6, y + 6, x + CELL - 6, y + CELL - 6], outline=(255, 215, 0), width=1)
    
    draw.text((x + 4, y + CELL + 8), f"方案 {i+1} 地图嵌入效果", fill=(230, 240, 250), font=font_card_title)
    draw.text((x + 4, y + CELL + 28), "左:草坪环境 | 右:黑土地环境 · 水平对齐", fill=(170, 190, 210), font=font_desc2)

showcase_path = OUT_DIR / "grainfield_redesign_4_options_showcase.png"
canvas.save(showcase_path)
canvas.save(ARTIFACT_DIR / "grainfield_redesign_4_options_showcase.png")
print("Saved grainfield 4 options showcase:", showcase_path)
