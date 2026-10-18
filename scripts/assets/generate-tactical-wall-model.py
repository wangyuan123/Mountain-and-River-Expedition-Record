#!/usr/bin/env python3
"""使用项目 gpt-image-2 官方管线生成战斗指挥面板全高城墙 3D 微缩模型原图。"""
import base64
import os
from pathlib import Path
import socket
import requests

# 规避内网 DNS 解析到离线内网 IP 的问题，直接路由到外网网关公网 IP
orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/tactical-wall-20261014/raw'
PROMPT_FILE = ROOT / 'output/imagegen/tactical-wall-20261014/prompt.txt'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPT = """Use case: stylized-concept.
Asset type: one realistic 3D miniature fortress city wall model asset for the tactical battle command panel in the WWII-inspired strategy game 山河远征录, matching its existing A / garden citadel architectural collection.

Primary request:
Create an impressive, highly detailed, vertical military defensive fortress rampart wall (战役战术要塞防御城墙防线). 
The fortress wall stands vertically as a continuous fortified defensive barrier spanning vertically from the very top of the frame to the bottom. 
The FRONT FACADE and the MAIN FORTRESS GATE are explicitly facing towards the LEFT (towards incoming enemy forces on the battlefield).

Camera and Framing:
- Elevated three-quarter isometric perspective, approximately 35-40 degrees above the horizon, viewing from the side-front so that the wall stretches vertically from top to bottom of the frame.
- The front vertical fortified wall face and gate open towards the LEFT. The flat interior fortress walkway is on the RIGHT side.
- Long vertical defensive line occupying the frame from top to bottom, near-orthographic lens, all elements crisply in-focus.

Key Architectural Elements:
1. Central Grand Bastion Gatehouse (中央主要塞城门楼):
   Located at the vertical center of the rampart, protruding outward towards the LEFT. Built with warm cream limestone and reinforced concrete. Features a monumental stone gate arch with heavy blast-resistant double steel doors, fortified iron rivets, and locking crossbeams facing left. Flanked by two rounded defensive watchtowers with observation slits, small terracotta tiled roofs, and machine gun cupolas.
2. Continuous Upper & Lower Ramparts (上下两段要塞城墙防线):
   Extending seamlessly above and below the central gate to the frame edges. Thick, battered stone foundation, crenellated parapets with regular crenels (垛口) facing left, sentry catwalk with guardrails on the right, and embedded rifle/machine-gun firing slits.
3. Top and Bottom Bastions:
   Hexagonal concrete pillbox bunkers terminating the wall at the upper and lower ends.
4. Battlefield Obstacles:
   Along the left exterior ground base facing incoming enemies: steel Czech hedgehog anti-tank obstacles, barbed-wire revetments, and sandbag firing positions firmly on the ground.

Materials & Craftsmanship:
Master scale-model miniature diorama craftsmanship with convincing physical textures: warm cream limestone and weathered stucco, desaturated terracotta roof caps, dark charcoal steel blast doors and obstacles, clean neutral concrete base.
Lighting:
Crisp natural morning daylight from upper-left casting sharp, soft ambient occlusion contact shadows to highlight the three-dimensional depth and wall thickness.
Background & Constraints:
Solid uniform seamless pure white background for clean transparency extraction. NO floating dirt diorama slab, NO modern skyscrapers, NO futuristic tech, NO text, watermarks, flags, or UI icons.
"""

def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    PROMPT_FILE.write_text(PROMPT.strip(), encoding='utf-8')
    target_path = RAW_DIR / 'tactical_wall_raw.png'

    print('Starting Tactical Wall Model generation via gpt-image-2...')
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
        raise RuntimeError(f'Failed to generate Tactical Wall: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download image: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format: {img_data.keys()}')

    print(f'Successfully saved Tactical Wall Model raw image to {target_path} ({target_path.stat().st_size} bytes)')

if __name__ == '__main__':
    main()
