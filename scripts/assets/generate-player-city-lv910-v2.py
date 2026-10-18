"""重新生成 9-10 级全新玩家城市微缩模型原图 (采用 gpt-image-2)。
严格要求：
1. 真正原生完整的一体化微缩模型，绝无拼接拼凑感；
2. 比 7-8 级更高级、更宏伟，城墙与统帅宫殿更高耸拔地而起；
3. 城门前方严格保持平直横向矩形地基与规整平行排列反坦克龙齿，绝无三角形突起；
4. 全景光照通透，石灰岩与混凝土立面质感清晰，杜绝死黑阴影。
"""
import base64
import os
from pathlib import Path
import socket
import sys
import requests

orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = ROOT / 'output/imagegen/player-city-tiered-20261017'
RAW_DIR = OUTPUT_DIR / 'raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPT_LV9_10_REDESIGN = """Use case: stylized-concept.
Asset type: one single complete realistic 3D miniature player-city mega-fortress model asset for the WWII strategy game 山河远征录, matching its existing A / garden citadel architectural collection.
Tier: Tier 5 (City Wall Level 9-10 MAX: Supreme Imperial Citadel Metropolis / 城墙9-10级终极巨构钢铁要塞帝国之都).

CRITICAL ARCHITECTURAL UPGRADES (Much more monumental and advanced than Level 7-8):
- Extreme Vertical Elevation: The fortress perimeter walls and central imperial command palace are substantially TALLER and MORE MONUMENTAL than Level 7-8 (+80% greater vertical height), creating an awe-inspiring, colossal fortress metropolis that pierces high into the sky.
- Monumental Perimeter Walls: Massive, soaring monolithic multi-tiered citadel ramparts built with cut cream granite blocks and sloped reinforced concrete armor plates, deep artillery embrasures, massive stepped buttresses, and double-height enclosed patrol catwalks.
- Supreme Command Imperial Palace: Dominating the central-north skyline, an immensely grand 7-8 story neoclassical imperial citadel palace with pale limestone facade, sweeping colonnaded wings, grand ceremonial marble stairs, and an immense soaring clock tower capped with a magnificent golden-bronze dome reaching high into the sky. A vast paved victory plaza extends in front with a central monumental bronze winged victory statue and fountains.
- Corner Mega-Bastions: Colossal multi-level star-shaped bastion towers rising to immense heights at all corners, each mounted with a heavy armored rotating twin-barrel fortress artillery turret and quad heavy anti-aircraft flak batteries.
- Heavy Strategic Airbase (East): Multiple reinforced blast-proof aircraft bunkers with clamshell blast doors, modern flight operations tower, extended concrete apron with parked 4-engine heavy strategic bombers and escort fighter squadrons.
- Massive Armored Parade Ground (Center/South): Densely packed with disciplined battalions of heavy battle tanks with long rifled barrels, self-propelled heavy artillery, and Katyusha rocket launcher trucks.
- Advanced Strategic Radar (Northeast): High-gain rotating wire-mesh radar dish array and tall communications masts on the elevated rear bastion.

STRICT FRONT GATE & GROUND BASE REQUIREMENTS (NO TRIANGLE, NO PATCHWORK):
- The entire southern defensive wall and the ground base perimeter run strictly STRAIGHT and HORIZONTAL along the lower edge of the fortress.
- Front Entrance Gate: A monumental 4-story monolithic citadel barbican gate fortress with twin soaring crenellated bastion towers, giant blast-proof armored steel gates with golden eagle crest, and searchlights.
- In front of the gate: A wide, formal, perfectly RECTANGULAR cobblestone avenue and flat horizontal paved defensive apron featuring neat, disciplined parallel horizontal rows of concrete dragon's teeth (tetrahedral anti-tank pyramids) and steel Czech hedgehogs.
- ABSOLUTELY NO TRIANGULAR FORMS JUTTING OUT, NO POINTED ARROWHEAD RAMPS, NO ANGLED CHEVRON APPROACH. The front retaining wall is completely flat and horizontal from left to right.

LIGHTING & MATERIAL VISIBILITY (NO BLACK VOIDS):
- Bright, natural morning sunlight from upper-left (10 o'clock) complemented by soft secondary ambient fill light.
- Every exterior wall surface—including the south-facing gate bastions and east-facing right curtain walls—is brightly and evenly illuminated with rich, clear cut-stone and concrete texture, distinct masonry seams, active parapets, and clearly visible embrasures.
- ABSOLUTELY NO PITCH-BLACK VOIDS, NO OPAQUE BLACK SHADOW HOLES, NO MISSING WALL TEXTURES.

CAMERA, FRAMING & PALETTE:
- Elevated three-quarter isometric perspective (35-40 degrees above horizon, viewing from south towards north).
- Strictly SQUARE ground footprint aligned to cardinal directions, flat thin ground base with stone border, solid seamless uniform pure white background (#ffffff) for clean transparency isolation. All-in-focus miniature model.
- Warm cream limestone, muted terracotta clay roof tiles, weathered timber, olive-drab armor, neutral gray concrete paving, manicured green grass accents.
- NO floating island, NO water moat, NO modern glass skyscrapers, NO text or UI overlays.
"""


def generate_image(prompt: str, target_path: Path):
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': 'gpt-image-2',
        'prompt': prompt.strip(),
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    print(f'Sending request to {API_URL} for {target_path.name}...')
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'API error {resp.status_code}: {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response: {res_json}')
    img_data = res_json['data'][0]
    if img_data.get('url'):
        r = requests.get(img_data['url'], timeout=60)
        target_path.write_bytes(r.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image format: {img_data.keys()}')
    print(f'Successfully saved {target_path} ({target_path.stat().st_size} bytes)')


def main():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    # 生成两张候选图，确保万无一失
    candidate_1 = RAW_DIR / 'city_tall_lv9_10_redesign_c1.png'
    candidate_2 = RAW_DIR / 'city_tall_lv9_10_redesign_c2.png'

    print('Generating Candidate 1...')
    generate_image(PROMPT_LV9_10_REDESIGN, candidate_1)
    print('Generating Candidate 2...')
    generate_image(PROMPT_LV9_10_REDESIGN, candidate_2)


if __name__ == '__main__':
    main()
