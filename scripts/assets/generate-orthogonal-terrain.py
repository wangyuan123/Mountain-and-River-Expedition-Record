"""批量生成与当前地图 0 度正交方格严格对齐的野地图标（森林、丘陵、沼泽、草地、岩石、资源点）。
采用 0 偏航角、俯视 30 度前视构图，底边为水平平直前沿，杜绝 45 度菱形斜边。
"""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import requests

# 规避内网 DNS 解析异常
orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/terrain-orthogonal-20261003/raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

ITEMS = [
    {
        'id': 'wild-forest-ridge',
        'name': '森林·连续林带',
        'out': 'wild-forest-ridge.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A continuous forest belt terrain tile. A continuous grove of mixed conifers and broadleaf trees extending across the shallow square terrain tile, varied tree heights, rich sage and forest green foliage. An earthy dirt trail alongside the tree line with wild shrubs, scattered fallen logs and rocks. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or vehicles.'''
    },
    {
        'id': 'wild-forest-edge',
        'name': '森林·低矮疏林',
        'out': 'wild-forest-edge.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A sparse woodland edge and clearing terrain tile. Lower-profile scattered trees with open airy clearings, young birch and oak trees, low flowering shrubs, sunny grass patches, weathered tree stumps, scattered stones, and fallen branches. Lower canopy height, natural transition to open meadow. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or vehicles.'''
    },
    {
        'id': 'wild-hill-peak',
        'name': '丘陵·主峰石脉',
        'out': 'wild-hill-peak.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A prominent high rocky ridge and primary peak hill terrain tile. A sharp, elevated limestone crag and dominant central crest rising significantly above the shallow terrain tile, with rugged layered gray rock strata, steep exposed stone faces, hardy alpine grass clinging to clefts, and a few wind-swept miniature pine trees. Bold mountain-hill peak silhouette with tactile rock textures. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or mining machinery.'''
    },
    {
        'id': 'wild-hill-ridge',
        'name': '丘陵·连绵缓坡',
        'out': 'wild-hill-ridge.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A rolling ridge terrain tile. Two smooth, undulating rounded grassy hills forming a harmonious saddle between them, covered in rich sage and olive meadow grass, gently stepped stone ledges, subtle footpaths winding between the knolls, and small wild bush clusters. Balanced medium elevation. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or vehicles.'''
    },
    {
        'id': 'wild-hill-foothill',
        'name': '丘陵·低矮残丘',
        'out': 'wild-hill-foothill.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A low foothill and rocky terrace terrain tile. Gentle, low-elevation sloping terrain with weathered rocky outcrops, flat stepped stone terraces, gravelly slopes, sparse dry sage scrub, scattered boulders, and wild grasses. Half the height of a mountain peak, soft natural descent. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or structures.'''
    },
    {
        'id': 'wild-swamp-deep',
        'name': '沼泽·深潭芦苇',
        'out': 'wild-swamp-deep.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A deep central wetland and dark pool terrain tile. A prominent still pond of deep dark teal-olive water in the center, reflective water surface with lily pads, ringed by tall dense clusters of cattails, golden-green reed beds, thick swamp moss, muddy peat banks, and an ancient mossy gnarled cypress tree with visible root knees. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings, toxic colors or fantasy elements.'''
    },
    {
        'id': 'wild-swamp-creek',
        'name': '沼泽·曲折水网',
        'out': 'wild-swamp-creek.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A meandering wetland creek and delta channel terrain tile. Two gentle winding muddy-banked streams weaving across the shallow tile, dividing the terrain into irregular mossy peat hummocks, lush golden-green marsh grasses, low sedge tufts, a half-submerged weathered log, and shallow clear water ripples over dark riverbed. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or pipes.'''
    },
    {
        'id': 'wild-swamp-marsh',
        'name': '沼泽·泥泞浅滩',
        'out': 'wild-swamp-marsh.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A flat muddy marshland and shallow fen edge terrain tile. Wide expanse of damp dark-earth mudflats, shallow puddle depressions with thin water sheen, low creeping moss, sparse tufts of wetland rush and short waterlogged grass, scattered river pebbles and silt deposits. Lowest vertical profile among swamp tiles. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or structures.'''
    },
    {
        'id': 'wild-rock',
        'name': '岩石·风化石林',
        'out': 'wild-rock.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A rugged rocky outcrop and boulder-strewn wasteland terrain tile. Weathered granite boulders, fractured stone slabs, gravelly dry earth, sparse desert scrub, earthy brown and stone gray tones. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or modern machinery.'''
    },
    {
        'id': 'wild-plains',
        'name': '平原·广袤原野',
        'out': 'wild-plains.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: An expansive open steppe and plains terrain tile. Golden-amber and dry sage-green steppe grasses blowing gently, low rolling turf, faint dirt wagon tracks, tiny wildflowers, wide open terrain. Perfectly flat square ground footprint with front edge strictly horizontal. No buildings or vehicles.'''
    },
    {
        'id': 'wild-grainfield',
        'name': '粮田·成熟麦浪',
        'out': 'wild-grainfield.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A lush, golden wheat farmland terrain tile for a 1930s-1940s agricultural supply field. Ripe golden wheat stalks in neat organic tractor rows, a rustic wooden hay wagon, a small stone well or weathered grain shed at the rear corner, rich dark tilled soil boundaries. Perfectly flat square ground footprint with front edge strictly horizontal.'''
    },
    {
        'id': 'wild-ironworks',
        'name': '炼铁厂·工业冶炼',
        'out': 'wild-ironworks.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A 1930s industrial iron smelting outpost terrain tile. Heavy brick furnace, tall brick smokestacks emitting subtle smoke, iron ore storage piles, metal gantry crane, rustic rail carts with raw iron scrap, corrugated metal workshop roof. Perfectly flat square ground footprint with front edge strictly horizontal.'''
    },
    {
        'id': 'wild-oil',
        'name': '油田·钻塔油井',
        'out': 'wild-oil.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A 1930s oil field extraction outpost terrain tile. A classic wooden oil derrick tower, a walking-beam pumpjack (nodding donkey pump), cylindrical riveted crude oil storage tanks, metal transfer pipes, oily dark soil patches. Perfectly flat square ground footprint with front edge strictly horizontal.'''
    },
    {
        'id': 'wild-rarefactory',
        'name': '稀矿厂·选矿提炼',
        'out': 'wild-rarefactory.png',
        'prompt': '''Use case: stylized-concept.
Asset type: a beautiful miniature diorama icon for a 1930s-1940s military strategy game world map, belonging to the same art family as a highly detailed European architectural model.
Style: premium semi-realistic 3D architectural tabletop miniature, tactile materials, convincing thickness and volumes, soft bevels, meticulous craft, realistic foliage, rich fine details.
Composition: ONE isolated subject on ONE shallow square terrain tile. Frontal elevated three-quarter view with camera looking down at a 30-degree pitch, zero yaw rotation: the front edge of the square terrain tile is strictly horizontal and parallel to the bottom of the image frame (orthogonal square grid orientation, NOT diamond, NOT 45-degree angled, square footprint matching a standard 2D orthogonal square grid cell with horizontal front edge and vertical/receding sides). Clear silhouette, no tilt-shift blur, completely inside frame with 10 percent margin.
Lighting and background: soft daylight from upper left, plain seamless pale warm-gray studio background.
Subject: A 1930s specialized rare minerals processing plant terrain tile. Industrial chemical leaching vats, conveyor belts carrying crystalline ores, a processing kiln with ventilation ducting, storage barrels, heavy machinery under an open-sided steel-truss canopy. Perfectly flat square ground footprint with front edge strictly horizontal.'''
    }
]


def generate_item(item):
    out_name = item['out']
    target_path = RAW_DIR / out_name
    if target_path.exists() and target_path.stat().st_size > 50000:
        print(f'{out_name} already exists ({target_path.stat().st_size} bytes), skipping.')
        return out_name

    print(f'Starting generation for {out_name} ({item["name"]})...')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': item.get('model', 'gpt-image-2'),
        'prompt': item['prompt'],
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'Failed to generate {out_name}: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response for {out_name}: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download {out_name}: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format for {out_name}: {img_data.keys()}')

    print(f'Successfully saved {out_name} ({target_path.stat().st_size} bytes)')
    return out_name


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    print(f'Starting generation for {len(ITEMS)} items with 2 workers...')
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(generate_item, ITEMS))
    print(f'All {len(results)} items generated: {results}')


if __name__ == '__main__':
    main()
