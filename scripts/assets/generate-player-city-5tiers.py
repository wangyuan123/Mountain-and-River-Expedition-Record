"""生成 5-6 级与 9-10 级两款全新玩家城市微缩模型原图 (采用 gpt-image-2)。"""
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

PROMPT_LV5_6 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII strategy game 山河远征录, matching its A / garden citadel architectural collection.
Tier: Tier 3 (City Wall Level 5-6: Fortified Strategic Bastion / 城墙5-6级强化高耸要塞重镇).

Crucial Vertical Elevation Requirement:
- Wall and architecture height is intermediate between Tier 2 (Level 3-4) and Tier 4 (Level 7-8).
- The defensive perimeter ramparts are markedly TALL, thick, and formidable (standing significantly higher than Level 3-4, but slightly below Level 7-8, with stepped stone and concrete buttresses, deep crenellated battlements, and double-height patrol catwalks).
- The southern entrance gatehouse is a soaring, reinforced 2.5-story stone-and-steel barbican gate fortress with heavy blast-proof portcullis doors, twin octagonal crenellated guard towers, powerful searchlights, and anti-tank barricades (Czech hedgehogs).
- Corner bastions are elevated, robust multi-level octagonal fortress towers rising high above the ground, each armed with dual heavy 88mm anti-aircraft flak cannons and armored pillboxes.
- Central command headquarters is a grand, tall 5-story neoclassical military-civic headquarters with towering classical columns, monumental ceremonial stairs, expansive symmetrical wings, and a tall central clock tower with a patinated green-bronze dome rising into the skyline.

Camera and Framing:
- Elevated three-quarter isometric perspective, 35-40 degrees above horizon, viewing from south towards north.
- Strictly SQUARE ground footprint aligned to cardinal directions, flat ground base with stone border, solid seamless pure white background (#ffffff). All-in-focus miniature scale model.

Expanded Military Facilities:
- East airfield: dual curved corrugated hangars, concrete runway and taxiway, flight tower, parked twin-engine bombers and fighter squadrons.
- Paved assembly grounds: disciplined rows of olive-drab medium and heavy battle tanks, Katyusha rocket launchers, and armored scout cars.
- West sector: expanded red-brick munitions factory with high administrative tower, fuel storage drums, and transport fleet.
- Elevated northeast bastion with tall steel-lattice radar dish and communication mast; northwest fortified timber POW camp.

Palette & Materials:
Warm cream limestone and stucco, muted terracotta clay roof tiles, weathered dark timber, olive-drab armor, gray concrete paving, manicured grass accents. Bright morning daylight from upper-left with crisp contact shadows. Flat base, NO floating island, NO moat, NO modern skyscrapers, NO text.
"""

PROMPT_LV9_10 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII strategy game 山河远征录, matching its A / garden citadel architectural collection.
Tier: Tier 5 (City Wall Level 9-10 MAX: Supreme Imperial Citadel Metropolis / 城墙9-10级终极巨构钢铁要塞帝国之都).

Crucial Extreme Vertical Elevation Requirement (+80% TALLER than Level 7-8):
- In comparison to Level 7-8, the defensive perimeter walls and central command architecture are increased in vertical height by an ASTONISHING 80%, creating an awe-inspiring, colossal mountain-like fortress rampart that pierces high into the sky!
- Colossal, multi-tiered monolithic citadel walls soaring to maximum vertical limits (massive sheer granite and sloped reinforced concrete armor plates, deep artillery embrasures, towering stepped buttresses, double-decked enclosed patrol walkways).
- Massive, monumental 4-story monolithic citadel barbican gatehouse with colossal twin fortress bastions, giant blast-proof armored steel gates with eagle relief, heavy searchlight arrays, and 3 dense rows of concrete dragon's teeth anti-tank barricades extending far forward into the approach.
- Colossal star-shaped mega-bastions at all corners, towering to extreme heights, each mounted with an immense armored rotating double-barrel heavy fortress artillery turret and quad heavy anti-aircraft batteries.
- Supreme Command Imperial Palace: A breathtaking, monumental 8-story neoclassical imperial citadel palace reaching the pinnacle of the sky, featuring an immense golden-bronze dome and soaring clock spire that dominates the entire composition, grand ceremonial marble stairs, expansive colonnade wings, and a vast central victory plaza with a monumental bronze winged victory statue and fountains.

Camera and Framing:
- Elevated three-quarter isometric perspective, 35-40 degrees above horizon, viewing from south towards north.
- Strictly SQUARE ground footprint aligned to cardinal directions, flat ground base with stone border, solid seamless pure white background (#ffffff). All-in-focus miniature model.

Elite Strategic Military Facilities:
- Heavy strategic airbase: multiple reinforced blast-proof aircraft bunkers with clamshell blast doors, multi-story flight operations tower, parked four-engine heavy strategic bombers and escort fighters.
- Immense concrete parade grounds: densely packed with disciplined battalions of heavy battle tanks, self-propelled heavy artillery, and Katyusha rocket launcher trucks.
- Giant high-gain rotating wire-mesh radar dish array and tall communications masts on the elevated rear bastion.
- Industrial armaments megacomplex: multi-building red-brick workshops, heavy gantry cranes, and ammunition depots.

Palette & Materials:
Warm cream limestone, muted terracotta clay roofs, olive-drab heavy armor, neutral gray concrete, manicured lawns. Bright morning daylight from upper-left, sharp architectural relief with deep majestic shadows. Flat base, NO floating island, NO moat, NO modern skyscrapers, NO text.
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
    target_5_6 = RAW_DIR / 'city_tall_lv5_6.png'
    target_9_10 = RAW_DIR / 'city_tall_lv9_10.png'

    which = sys.argv[1] if len(sys.argv) > 1 else 'all'
    if which in ('5-6', 'lv5_6', 'all'):
        print('Generating Lv 5-6 Tall City...')
        generate_image(PROMPT_LV5_6, target_5_6)
    if which in ('9-10', 'lv9_10', 'all'):
        print('Generating Lv 9-10 Mega Tall City (+80% on top of 7-8)...')
        generate_image(PROMPT_LV9_10, target_9_10)


if __name__ == '__main__':
    main()
