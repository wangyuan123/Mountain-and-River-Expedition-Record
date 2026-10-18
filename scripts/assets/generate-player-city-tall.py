"""生成建筑与城墙高度再提高 50% 的三款玩家城市微缩模型原图 (采用 gpt-image-2)。"""
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

PROMPT_TALL_LV1_4 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII strategy game 山河远征录, matching its A / garden citadel architectural collection.
Tier: Tier 1 (City Wall Level 1-4: Fortified Base City / 城墙1-4级高墙基准主城).

Crucial Vertical Elevation Requirement (+50% Taller Walls and Buildings):
- The defensive perimeter walls are conspicuously TALLER and THICKER (increased by 50% in vertical height compared to standard low walls, standing tall with prominent cut-granite stone masonry, deep crenellated battlements, elevated patrol walkway, and high corner watchtower bastions).
- Central headquarters is a TALL, imposing 4-story neoclassical civic-military command building with high classical columned portico, wide stone approach steps, and a prominent tall central clock tower with an oxidized-bronze dome reaching high into the sky.
- Elevated southern gatehouse with a tall arched stone portal, heavy reinforced steel gates, and flanking guard posts with anti-tank barricades.
- Corner bastions are elevated tall stone towers mounted with dual anti-aircraft flak guns.

Camera and Framing:
- Elevated three-quarter isometric perspective, 35-40 degrees above horizon, viewing from south towards north.
- Strictly SQUARE ground footprint aligned to cardinal directions, flat ground base with stone border, solid seamless pure white background (#ffffff). All-in-focus miniature model.

Internal Facilities:
- East airfield with runway, curved hangar, flight tower, parked P-51 fighters and bomber.
- Central parade yard with olive-drab medium tanks, scout cars, and trucks.
- West red-brick armaments workshop with sawtooth glass roof and northwest timber POW camp.

Palette & Materials:
Warm cream limestone and stucco, muted terracotta clay roof tiles, weathered dark timber, olive-drab vehicles, neutral gray concrete paving, and manicured green grass accents. Bright morning light from upper-left casting rich structural shadows. Flat thin ground surface, NO floating island, NO water moat, NO modern skyscrapers, NO text.
"""

PROMPT_TALL_LV5_9 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII strategy game 山河远征录, matching its A / garden citadel architectural collection.
Tier: Tier 2 (City Wall Level 5-9: Fortified Regional Stronghold / 城墙5-9级加固高耸要塞重镇).

Crucial Vertical Elevation Requirement (+50% Taller Walls and Buildings):
- The defensive perimeter ramparts are exceptionally TALL and HEAVY (increased by 50% in vertical height over Tier 1, towering with massive stepped stone buttresses, thick reinforced concrete armor slabs, deep crenellated battlements, and double-height patrol catwalks).
- The southern entrance gatehouse is a soaring, massive 2-3 story fortified stone-and-steel gate fortress with heavy blast-proof portcullis doors, tall twin crenellated guard towers, searchlights, and Czech hedgehog anti-tank obstacles.
- Corner bastions are massive, tall multi-level octagonal fortress towers rising high above the ground, each armed with dual heavy anti-aircraft flak cannons and armored pillboxes.
- Central command headquarters is an exceptionally grand, tall 5-story neoclassical military-civic palace with towering classical columns, monumental ceremonial stairs, expansive symmetrical wings, and a towering central clock dome that rises majestically into the skyline.

Camera and Framing:
- Elevated three-quarter isometric perspective, 35-40 degrees above horizon, viewing from south towards north.
- Strictly SQUARE ground footprint aligned to cardinal directions, flat ground base with stone border, solid seamless pure white background (#ffffff). All-in-focus miniature scale model.

Expanded Military Facilities:
- East airfield: dual curved corrugated hangars, multi-story flight tower, parked heavy bombers and fighter squadrons.
- Paved assembly grounds: disciplined rows of olive-drab medium/heavy tanks, Katyusha rocket launchers, and armored scout cars.
- West sector: expanded red-brick munitions factory with high administrative tower, fuel drums, and transport fleet.
- Elevated northeast bastion with tall steel-lattice radar dish and communication mast; northwest fortified POW camp.

Palette & Materials:
Warm cream limestone, muted terracotta clay roof tiles, weathered dark timber, olive-drab armor, gray paving, manicured grass. Bright morning daylight from upper-left with crisp contact shadows. Flat base, NO floating island, NO moat, NO modern skyscrapers, NO text.
"""

PROMPT_TALL_LV10 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII strategy game 山河远征录, matching its A / garden citadel architectural collection.
Tier: Tier 3 (City Wall Level 10 MAX: Supreme Command Citadel / 城墙10级满级至尊钢铁要塞大本营).

Crucial Vertical Elevation Requirement (+50% Taller Walls and Buildings):
- Colossal, towering monolithic citadel fortress walls (standing at the maximum limit of fortress height, with walls 50% taller than Tier 2, soaring like an impregnable steel-and-stone mountain rampart). Multi-tiered sloped concrete armor plates, deep embrasures, and heavy fortress gun emplacements.
- Massive 3-4 story monolithic citadel barbican gatehouse with towering stone fortress bastions, giant armored blast doors, eagle crest relief, heavy searchlights, and deep rows of concrete dragon's teeth anti-tank barricades extending in front.
- Colossal multi-faceted bastion fortresses at all four corners, towering high above the terrain, armed with heavy armored rotating fortress artillery turrets and quad anti-aircraft batteries.
- Supreme Command Citadel Palace: an awe-inspiring 6-7 story monumental neoclassical command palace reaching high into the sky, featuring a magnificent central dome and soaring clock tower reaching the pinnacle of the composition, sweeping symmetrical colonnade wings, grand ceremonial marble stairs, and a grand victory plaza with a monumental bronze winged statue.

Camera and Framing:
- Elevated three-quarter isometric perspective, 35-40 degrees above horizon, viewing from south towards north.
- Strictly SQUARE ground footprint aligned to cardinal directions, flat ground base with stone border, solid seamless pure white background (#ffffff). All-in-focus miniature model.

Elite Military Facilities:
- Heavy strategic airbase: multiple reinforced blast-proof aircraft bunkers, advanced flight tower, parked heavy strategic bombers and escort fighters.
- Immense concrete parade grounds: packed with disciplined heavy battle tanks, self-propelled artillery, and Katyusha rocket trucks.
- Giant high-gain rotating wire-mesh radar dish array and tall communications masts on the elevated rear bastion.
- Industrial armaments complex: multi-building brick workshops, heavy gantry cranes, and ammunition depots.

Palette & Materials:
Warm cream limestone, muted terracotta clay roofs, olive-drab heavy armor, neutral gray concrete, manicured lawns. Bright morning daylight from upper-left, sharp architectural relief. Flat base, NO floating island, NO moat, NO modern skyscrapers, NO text.
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
    target_1_4 = RAW_DIR / 'city_tall_lv1_4.png'
    target_5_9 = RAW_DIR / 'city_tall_lv5_9.png'
    target_10 = RAW_DIR / 'city_tall_lv10.png'

    which = sys.argv[1] if len(sys.argv) > 1 else 'all'
    if which in ('1-4', 'lv1_4', 'all'):
        print('Generating Lv 1-4 Tall City...')
        generate_image(PROMPT_TALL_LV1_4, target_1_4)
    if which in ('5-9', 'lv5_9', 'all'):
        print('Generating Lv 5-9 Tall City...')
        generate_image(PROMPT_TALL_LV5_9, target_5_9)
    if which in ('10', 'lv10', 'all'):
        print('Generating Lv 10 Tall City...')
        generate_image(PROMPT_TALL_LV10, target_10)


if __name__ == '__main__':
    main()
