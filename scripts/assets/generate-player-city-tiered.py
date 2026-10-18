"""生成 5-9 级与 10 级玩家城市微缩模型原图 (采用 gpt-image-2)。"""
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

PROMPT_LV5_9 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII-inspired strategy game 山河远征录, matching its existing A / garden citadel architectural collection.
Tier: Tier 2 (City Wall Level 5-9: Fortified Regional Stronghold / 城墙5-9级加固要塞主城).

Primary request:
Create an impressive, reinforced military fortress-city with higher defensive walls, more magnificent central command architecture, and significantly enhanced military presence. The fortress seamlessly combines taller stone-and-concrete defensive ramparts, a grand 4-5 story neoclassical military-civic headquarters, a high-mast radar station, an authentic prisoner-of-war (POW) camp compound, and an expanded military staging base with armored divisions and an active airfield.

Camera and Framing:
- Elevated three-quarter isometric perspective, approximately 35-40 degrees above the horizon, viewing from the south towards the north, showing front facade and right side.
- Strictly SQUARE ground footprint aligned to cardinal directions (NOT rotated 45 degrees into diamond, NOT a floating diorama slab). The southern defensive wall runs horizontally along the lower portion.
- Near-orthographic focal length, entire fortress fully contained within the square frame with a 6-8 percent clean margin. All-in-focus rendering from front gate to rear radar tower.

Architectural Zones and Upgraded Facilities:
1. Fortified Perimeter Wall (5-9级显著加高防御城墙):
   Conspicuously TALLER and thicker early-20th-century stone and reinforced concrete defensive rampart wall (measuring 1.8x the height of a basic town wall). Heavy angular corner bastion towers elevated significantly above ground level, each mounted with dual heavy anti-aircraft flak guns. A reinforced 2-story southern armored gatehouse with heavy steel portcullis doors, twin guard towers, searchlights, and anti-tank barricades (Czech hedgehogs). Wide elevated parapet walkway with sentry posts.
2. Grand Command Headquarters (更宏伟的4-5层统帅市政大本营):
   Commanding the north-central axis: a magnificent 4-5 story neoclassical military-civic headquarters with pale limestone facade, grand columned portico with classical pediment, wide stone approach steps, symmetrical wings, and a tall central clock tower with an oxidized bronze dome. A dignified paved command plaza with manicured formal hedges extends before it.
3. Air-Defense Radar Station (东北加固防空雷达站):
   Elevated on the fortified northeastern bastion: a tall steel-lattice antenna tower supporting an open wire-mesh parabolic radar dish, accompanied by a slender radio communication mast and a reinforced concrete transmitter bunker.
4. POW Camp Compound (西北战俘营封闭区):
   Walled-off compound in the northwestern quarter: two long single-story barracks with red terracotta tile roofs and pale stucco walls, enclosed by timber posts and wire-mesh fencing surrounding a packed-dirt muster yard, guarded by two elevated timber watchtowers at opposite corners.
5. Expanded Military Base Complex (东南部与正南部强化军事基地):
   - East sector airfield: smooth gray concrete runway and taxiway, twin curved corrugated-iron aircraft hangars, glass-cab flight control tower, and parked WWII propeller warplanes (P-51 Mustang style fighters and heavy bombers).
   - South sector armored parade & staging grounds: paved assembly yard displaying disciplined rows of olive-drab medium tanks, M8 Greyhound armored cars, and BM-13 Katyusha rocket launcher trucks with exposed rails.
   - West-central ordnance depot: red-brick armaments workshop with steel sawtooth glass roofs, supply crates, fuel drums, and transport trucks.

Materials, Color Palette and Craftsmanship:
Master scale-model craftsmanship with convincing physical textures. Harmonious palette matching the garden building collection: warm cream limestone and stucco, muted terracotta clay roof tiles, weathered dark timber, olive-drab military vehicles, neutral gray concrete paving, and manicured green grass accents.
Lighting:
Bright, natural morning daylight from upper-left casting crisp, realistic volumetric contact shadows that emphasize multi-level building relief, retaining clear interior court details.
Background & Constraints:
Solid uniform seamless pure white background (#ffffff) for clean transparency preparation. Flat thin ground surface with natural stone borders, NO floating dirt island, NO thick diorama pedestal block, NO water moat. NO modern skyscrapers, NO medieval fantasy spires, NO flying/airborne planes, NO text, flags, watermarks, or UI icons.
"""

PROMPT_LV10 = """Use case: stylized-concept.
Asset type: one complete realistic 3D miniature player-city fortress model asset for the WWII-inspired strategy game 山河远征录, matching its existing A / garden citadel architectural collection.
Tier: Tier 3 (City Wall Level 10 MAX: Supreme Command Citadel / 城墙10级满级最高统帅终极钢铁要塞大本营).

Primary request:
Create the ultimate monumental fortress-city and supreme military headquarters of the highest tier. The fortress features towering, massive monolithic citadel walls, an exceptionally grand 5-6 story supreme command palace with monumental colonnades, a state-of-the-art airbase, a massive heavy armor division staging ground, advanced radar towers, and elite fortress fortifications with unmatched military prestige and authority.

Camera and Framing:
- Elevated three-quarter isometric perspective, approximately 35-40 degrees above the horizon, viewing from the south towards the north, showing front facade and right side.
- Strictly SQUARE ground footprint aligned to cardinal directions (NOT rotated 45 degrees into diamond, NOT a floating diorama slab). The southern defensive wall runs horizontally along the lower portion.
- Near-orthographic focal length, entire fortress fully contained within the square frame with a 6-8 percent clean margin. All-in-focus rendering from front gate to rear radar tower.

Architectural Zones and Upgraded Facilities:
1. Massive Citadel Perimeter Ramparts (10级满级巨型复合要塞高墙与棱堡):
   Towering, massive early-20th-century monolithic fortress citadel walls (standing twice as tall as Tier 1, soaring with tremendous vertical presence). Constructed from heavy reinforced concrete armor slabs and cut granite blocks with sloped blast-resistant ramparts. Massive multi-faceted bastion fortresses at all corners, armed with heavy armored fortress artillery turrets and quad anti-aircraft flak batteries. Monumental 3-story southern barbican gate fortress with heavy blast-proof armored steel gates, stone watchtowers, fortress searchlights, and deep rows of concrete dragon's teeth anti-tank fortifications. Elevated parapets with active sentries and pillboxes.
2. Supreme Command Citadel Palace (至高宏伟的5-6层最高统帅大本营宫殿):
   Commanding the north-central axis with supreme dominance: an exceptionally grand 5-6 story neoclassical military-civic citadel palace with pale limestone facade, sweeping monumental columned wings, massive ceremonial marble staircase, high central portico, and a majestic soaring clock tower capped with an impressive neoclassical dome. A magnificent paved victory plaza extends in front, lined with formal sculpted gardens, ceremonial lanterns, and parade avenues.
3. Heavy Strategic Airbase Complex (东部重装战略机场基地):
   Expanded airfield featuring multiple reinforced blast-proof concrete aircraft hangars, advanced multi-story flight operations tower, extended concrete apron and runways with parked heavy strategic bombers (B-17 / B-29 style multi-engine bombers) and advanced fighter squadrons.
4. Heavy Armored Staging Ground & Armaments (正南至中央重装战备大操场):
   Expansive paved parade ground densely arrayed with elite heavy armor battalions: disciplined formations of heavy battle tanks with long rifled barrels, self-propelled artillery, Katyusha rocket launchers, half-track carriers, and armored command trucks. Western armaments complex includes multiple red-brick sawtooth workshops, industrial cranes, ammunition caches, and fuel depots.
5. Advanced Radar & Defense Array (东北角超级预警雷达中枢与西北警卫营):
   Northeastern elevated citadel bastion houses a massive high-gain rotating wire-mesh radar dish array and tall communications antenna masts. Northwestern sector contains reinforced barracks compound with stone-and-timber security perimeters, spotlight watchtowers, and communications bunker.

Materials, Color Palette and Craftsmanship:
Master scale-model craftsmanship with convincing physical textures. Harmonious palette matching the garden building collection: warm cream limestone and stucco, muted terracotta clay roof tiles, weathered dark timber, olive-drab military vehicles, neutral gray concrete paving, and manicured green grass accents.
Lighting:
Bright, natural morning daylight from upper-left casting crisp, realistic volumetric contact shadows that emphasize multi-level building relief, retaining clear interior court details.
Background & Constraints:
Solid uniform seamless pure white background (#ffffff) for clean transparency preparation. Flat thin ground surface with natural stone borders, NO floating dirt island, NO thick diorama pedestal block, NO water moat. NO modern skyscrapers, NO medieval fantasy spires, NO flying/airborne planes, NO text, watermarks, or UI icons.
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
    (OUTPUT_DIR / 'prompt_lv5_9.txt').write_text(PROMPT_LV5_9, encoding='utf-8')
    (OUTPUT_DIR / 'prompt_lv10.txt').write_text(PROMPT_LV10, encoding='utf-8')

    target_5_9 = RAW_DIR / 'city_lv5_9.png'
    target_10 = RAW_DIR / 'city_lv10.png'

    which = sys.argv[1] if len(sys.argv) > 1 else 'all'
    if which in ('5-9', 'lv5_9', 'all'):
        print('Generating Lv 5-9 City...')
        generate_image(PROMPT_LV5_9, target_5_9)
    if which in ('10', 'lv10', 'all'):
        print('Generating Lv 10 City...')
        generate_image(PROMPT_LV10, target_10)


if __name__ == '__main__':
    main()
