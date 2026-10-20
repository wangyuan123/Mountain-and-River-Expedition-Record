import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output/imagegen/redesign-oil-grain-20261019"
ARTIFACT_DIR = Path("/Users/chenjuan/.gemini/antigravity/brain/9815c554-ee6f-48a0-8dd0-1c769cc2e832")
MAP_DIR = ROOT / "frontend/img/map"
TARGET = 384

# Load the two transparent master models
v1_rgba = Image.open(OUT_DIR / "rice_model_v1_waterway.png")
v2_rgba = Image.open(OUT_DIR / "rice_model_v2_cart.png")

# 1. Export WebPs and PNGs
v1_rgba.save(OUT_DIR / "wild-grainfield-v1-waterway.png")
v1_rgba.save(OUT_DIR / "wild-grainfield-v1-waterway.webp", "WEBP", quality=95, method=6)

v2_rgba.save(OUT_DIR / "wild-grainfield-v2-cart.png")
v2_rgba.save(OUT_DIR / "wild-grainfield-v2-cart.webp", "WEBP", quality=95, method=6)

# Also save copies into frontend/img/map/ for live in-game preview
v1_rgba.save(MAP_DIR / "wild-grainfield-v1-waterway.webp", "WEBP", quality=95, method=6)
v1_rgba.save(MAP_DIR / "wild-grainfield-v1-waterway.png")
v2_rgba.save(MAP_DIR / "wild-grainfield-v2-cart.webp", "WEBP", quality=95, method=6)
v2_rgba.save(MAP_DIR / "wild-grainfield-v2-cart.png")

print("Exported WebP and PNG assets for both Variant 1 and Variant 2.")

# ==============================================================================
# 2. BUILD COMPREHENSIVE IN-MAP WORLD CHUNK SIMULATION
# ==============================================================================
# We simulate a 3x2 world map grid for each variant:
# Row 1: Grassland terrain with Grainfield in center, flanked by Forest / Plains
# Row 2: Black Soil terrain with Grainfield in center, flanked by Oilfield / Hills

GRASS = (104, 103, 71)
SOIL = (92, 81, 62)

# Load other map models for contextual reference
oil_model = Image.open(MAP_DIR / "wild-oil-transparent-c.png").resize((200, 200), Image.Resampling.LANCZOS)
city_model = Image.open(ROOT / "frontend/img/cities/player-city-lv10.png").resize((210, 210), Image.Resampling.LANCZOS)

CELL_S = 210 # size of each map cell in simulation
PAD = 20
CHUNK_COLS = 3
CHUNK_ROWS = 2

CHUNK_W = CELL_S * CHUNK_COLS
CHUNK_H = CELL_S * CHUNK_ROWS

def render_map_chunk(grain_model, label_text):
    chunk = Image.new("RGBA", (CHUNK_W, CHUNK_H), (0, 0, 0, 0))
    c_draw = ImageDraw.Draw(chunk)
    
    # 6 cells:
    # (0,0): Grassland (player city)
    # (1,0): Grassland with Grainfield (CENTERPIECE)
    # (2,0): Grassland meadow
    # (0,1): Black Soil with Oilfield
    # (1,1): Black Soil with Grainfield (CENTERPIECE)
    # (2,1): Black Soil hill
    
    grid_layout = [
        # (col, row, bg_color, model_to_paste, cell_name)
        (0, 0, GRASS, city_model, "玩家主城 (0度对标)"),
        (1, 0, GRASS, grain_model.resize((CELL_S, CELL_S), Image.Resampling.LANCZOS), "粮田 · 草坪嵌入"),
        (2, 0, GRASS, None, "自然草地"),
        (0, 1, SOIL, oil_model, "油田款型C (0度对标)"),
        (1, 1, SOIL, grain_model.resize((CELL_S, CELL_S), Image.Resampling.LANCZOS), "粮田 · 黑土嵌入"),
        (2, 1, SOIL, None, "荒原黑土"),
    ]
    
    for c, r, bg, mdl, name in grid_layout:
        cx = c * CELL_S
        cy = r * CELL_S
        # Base terrain tile
        tile = Image.new("RGBA", (CELL_S, CELL_S), (*bg, 255))
        t_arr = np.array(tile)
        np.random.seed(c * 17 + r * 23 + 99)
        t_arr[:, :, :3] = np.clip(t_arr[:, :, :3] + np.random.randint(-6, 6, t_arr[:, :, :3].shape), 0, 255)
        tile = Image.fromarray(t_arr.astype(np.uint8))
        
        # Paste model
        if mdl is not None:
            # Center in cell
            mx = (CELL_S - mdl.width) // 2
            my = (CELL_S - mdl.height) // 2
            tile.alpha_composite(mdl, (mx, my))
            
        chunk.paste(tile, (cx, cy))
        
        # Grid lines (subtle cyan/yellow)
        c_draw.rectangle([cx, cy, cx + CELL_S, cy + CELL_S], outline=(180, 200, 220, 80), width=1)
        
        # Cell highlight for grainfield
        if (c, r) in [(1, 0), (1, 1)]:
            c_draw.rectangle([cx, cy, cx + CELL_S, cy + CELL_S], outline=(255, 215, 0), width=2)
            # Corner crosshairs
            tlen = 10
            c_draw.line([cx, cy, cx + tlen, cy], fill=(255, 255, 255), width=2)
            c_draw.line([cx, cy, cx, cy + tlen], fill=(255, 255, 255), width=2)
            c_draw.line([cx + CELL_S, cy, cx + CELL_S - tlen, cy], fill=(255, 255, 255), width=2)
            c_draw.line([cx + CELL_S, cy, cx + CELL_S, cy + tlen], fill=(255, 255, 255), width=2)
            c_draw.line([cx, cy + CELL_S, cx + tlen, cy + CELL_S], fill=(255, 255, 255), width=2)
            c_draw.line([cx, cy + CELL_S, cx, cy + CELL_S - tlen], fill=(255, 255, 255), width=2)
            c_draw.line([cx + CELL_S, cy + CELL_S, cx + CELL_S - tlen, cy + CELL_S], fill=(255, 255, 255), width=2)
            c_draw.line([cx + CELL_S, cy + CELL_S, cx + CELL_S, cy + CELL_S - tlen], fill=(255, 255, 255), width=2)
            
    return chunk

chunk_v1 = render_map_chunk(v1_rgba, "款式一：石渠水网·金稻方阵")
chunk_v2 = render_map_chunk(v2_rgba, "款式二：丰收木车·金稻平原")

# ==============================================================================
# 3. COMPOSE MASTER COMPARISON CANVAS
# ==============================================================================
# Left Column: Variant 1 (Single tile + 3x2 map chunk)
# Right Column: Variant 2 (Single tile + 3x2 map chunk)

BOARD_W = CHUNK_W * 2 + PAD * 3
BOARD_H = 110 + CHUNK_H + 90 + PAD

board = Image.new("RGB", (BOARD_W, BOARD_H), (16, 20, 26))
b_draw = ImageDraw.Draw(board)

font_title = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 25)
font_sub = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)
font_head = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 18)
font_desc1 = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 13)
font_desc2 = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 12)

# Title
b_draw.text((PAD, 18), "大地图水稻田微缩模型：【款式一】与【款式二】实机大地图多格嵌入效果对比", fill=(245, 248, 252), font=font_title)
b_draw.text((PAD, 56), "均为连成一片金黄水稻 · 底座纯透明落地 · 四周保留26px安全留白零截断 · 观察在多格世界地貌中的融合效果", fill=(255, 215, 0), font=font_sub)

# Col 1: Variant 1
col1_x = PAD
col1_y = 95
b_draw.text((col1_x, col1_y), "【款式一：石渠水网·金稻方阵】大地图实机模拟 (3×2分块)", fill=(255, 220, 100), font=font_head)
board.paste(chunk_v1.convert("RGB"), (col1_x, col1_y + 28))
b_draw.rectangle([col1_x, col1_y + 28, col1_x + CHUNK_W, col1_y + 28 + CHUNK_H], outline=(255, 215, 0), width=2)

desc1_y = col1_y + 28 + CHUNK_H + 8
b_draw.text((col1_x, desc1_y), "★ 构图特征：青石横向引水渠与木质水闸 · 规整水乡稻田方阵 · 2名戴斗笠农夫挥镰割稻与运粮", fill=(235, 245, 250), font=font_desc1)
b_draw.text((col1_x, desc1_y + 22), "★ 嵌入表现：四周保留27px间隔空间，水稻完全收拢在方格内，底座透明软阴影与草坪/黑土无缝咬合，零截断", fill=(170, 195, 215), font=font_desc2)

# Col 2: Variant 2
col2_x = PAD * 2 + CHUNK_W
col2_y = 95
b_draw.text((col2_x, col2_y), "【款式二：丰收木车·金稻平原】大地图实机模拟 (3×2分块)", fill=(100, 220, 255), font=font_head)
board.paste(chunk_v2.convert("RGB"), (col2_x, col2_y + 28))
b_draw.rectangle([col2_x, col2_y + 28, col2_x + CHUNK_W, col2_y + 28 + CHUNK_H], outline=(70, 180, 240), width=2)

b_draw.text((col2_x, desc1_y), "★ 构图特征：平整田埂小道与细流泥沟 · 停靠满载粮袋的古典双轮木车 · 农夫弯腰割稻捆扎 · 金稻成片", fill=(235, 245, 250), font=font_desc1)
b_draw.text((col2_x, desc1_y + 22), "★ 嵌入表现：四周保留26px间隔空间，木车与稻海完整展现，底座透明软阴影与草坪/黑土无缝咬合，零截断", fill=(170, 195, 215), font=font_desc2)

showcase_file = OUT_DIR / "both_rice_variants_inmap_showcase.png"
board.save(showcase_file)
board.save(ARTIFACT_DIR / "both_rice_variants_inmap_showcase.png")
print(f"Saved master in-map showcase: {showcase_file}")

