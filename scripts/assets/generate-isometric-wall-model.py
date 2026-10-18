#!/usr/bin/env python3
"""
使用项目 gpt-image-2 官方管线生成真正带俯视 35° 等距视角的要塞城墙 3D 微缩模型原图。
"""
import base64
import os
from pathlib import Path
import socket
import requests

orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/tactical-wall-20261014/raw'
RAW_DIR.mkdir(parents=True, exist_ok=True)
RAW_FILE = RAW_DIR / 'isometric_wall_raw.png'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPT = """Use case: stylized-concept.
Asset type: one realistic 3D miniature architectural scale-model of a military defensive fortress city wall for the strategy game 山河远征录.

Primary request:
Create a complete, straight vertical fortress city wall defense line (完整战役要塞城墙防线微缩模型), viewed from an elevated 35-degree top-down isometric miniature diorama perspective (35度俯视微缩模型视角).

Framing & Layout:
- The entire defensive wall is oriented vertically from the top edge to the bottom edge of the frame.
- Camera angle: Elevated 35-degree oblique top-down perspective (俯视视角). From above, we clearly see the top stone walkway, crenellated battlements, watchtower roofs, and the front fortified wall facade.
- The front face of the fortress wall and its grand central gateway face directly towards the LEFT (facing incoming battlefield units). The rear/interior of the fortress is towards the RIGHT.
- Centered Grand Gatehouse (中央主要塞城门): Located at the exact vertical center (50% height) of the wall. Features an impressive arched gatehouse with heavy blast-resistant double steel doors, fortified rivets, and flanking semi-circular defensive watchtowers with red terracotta roofs.
- Symmetrical Upper & Lower Wall Wings: Symmetrically extending upwards and downwards from the central gatehouse to the top and bottom edges are straight, thick limestone and reinforced concrete ramparts with regular crenels (垛口), arrow/machine-gun slits, and top sentry walkways.
- Both top and bottom ends terminate cleanly with fortified pillbox corner bastions.
- Base defenses: Along the left front base on the ground are anti-tank steel Czech hedgehogs and sandbag revetments.

Style & Craftsmanship:
Realistic architectural scale model craftsmanship with tactile physical textures, crisp ambient occlusion, and subtle cast shadows: warm cream limestone masonry, weathered light-gray concrete, dark charcoal steel blast doors, muted terracotta roof tiles. Matching the game's premium Garden Citadel miniature asset collection.
Lighting:
Crisp, natural soft daylight from upper-left casting realistic depth and shadows across the multi-level ramparts.
Background:
Solid uniform seamless pure white background for clean transparency clipping. NO floating diorama dirt base, NO futuristic fantasy elements, NO text, watermarks, flags, or UI icons.
"""

def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    print('Starting 35-degree top-down isometric wall model generation...')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': 'gpt-image-2',
        'prompt': PROMPT.strip(),
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'API failed: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        r = requests.get(img_data['url'], timeout=60)
        RAW_FILE.write_bytes(r.content)
    elif img_data.get('b64_json'):
        RAW_FILE.write_bytes(base64.b64decode(img_data['b64_json']))
    print(f'Successfully saved 35-degree isometric wall model to {RAW_FILE} ({RAW_FILE.stat().st_size} bytes)')

if __name__ == '__main__':
    main()
