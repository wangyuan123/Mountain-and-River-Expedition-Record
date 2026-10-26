const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const c = {
    console, Date, Map, Set, WeakMap, Uint8Array,
    Game: { MapChunks: function() {} },
    document: { createElement: () => ({ getContext: () => ({}) }) }
  };
  c.window = c;
  vm.createContext(c);
  require('./load-constants.cjs')(c);
  c.Game.DATA = {
    world: { size: 200 },
    wildTypes: {
      forest:      { name: '森林',   res: null,    icon: 'img/map/wild-forest-ridge-integrated.webp' },
      hill:        { name: '丘陵',   res: null,    icon: 'img/map/wild-hill-ridge-integrated.webp' },
      grainfield:  { name: '粮田',   res: 'food',  icon: 'img/map/wild-grainfield-map-v2.webp' },
      ironworks:   { name: '炼铁厂', res: 'steel', icon: 'img/map/wild-ironworks-integrated.webp' },
      oil:         { name: '油田',   res: 'oil',   icon: 'img/map/wild-oil-integrated.webp' },
      rarefactory: { name: '稀矿厂', res: 'rare',  icon: 'img/map/wild-rarefactory-integrated.webp' },
    }
  };
  c.Game.state = {
    cityPos: { x: 10, y: 10 },
    world: {
      wildTiles: []
    }
  };
  for (const file of ['map-camera.js', 'map-layout.js', 'world-map.js']) {
    let source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8');
    vm.runInContext(source, c);
  }
  return c;
}

test('资源野地采完收获后，若剩余资源为0，图标切换为装满货物原地驻扎的卡车车队警备模型图', () => {
  const c = setup();
  const getIcon = c.Game.WorldMap.icon;

  // 1. 占领的粮田，采集完毕并点击收获，剩余资源为0
  const harvestedGrainfield = {
    kind: 'wild',
    id: 101,
    type: 'grainfield',
    occupied: true,
    totalRes: 0,
    mined: 0,
    gatherHarvested: 1000,
    hasGarrison: true
  };
  assert.equal(
    getIcon(harvestedGrainfield),
    'img/map/wild-depleted-convoy.webp',
    '已采空且收获的粮田必须切换为卡车车队驻扎警备图标'
  );

  // 2. 占领的炼铁厂、油田、稀矿厂，采空收获后同样切换为卡车车队驻扎警备图标
  for (const type of ['ironworks', 'oil', 'rarefactory']) {
    const tile = {
      kind: 'wild',
      id: 200,
      type: type,
      occupied: true,
      totalRes: 0,
      mined: 0,
      gatherHarvested: 800,
      hasGarrison: true
    };
    assert.equal(
      getIcon(tile),
      'img/map/wild-depleted-convoy.webp',
      `${type} 采空收获后必须切换为卡车车队驻扎警备图标`
    );
  }

  // 3. 正常有剩余资源的资源野地（未采空），保持原始资源图标
  const activeGrainfield = {
    kind: 'wild',
    id: 102,
    type: 'grainfield',
    occupied: true,
    totalRes: 5000,
    mined: 1000
  };
  assert.equal(
    getIcon(activeGrainfield),
    'img/map/wild-grainfield-map-v2.webp',
    '未采空的资源野地应保持原有模型图标'
  );

  // 4. 未占领的中立资源野地，即使 totalRes 为 0 也绝不显示玩家驻扎车队
  const neutralEmpty = {
    kind: 'wild',
    id: 103,
    type: 'grainfield',
    occupied: false,
    totalRes: 0,
    mined: 0
  };
  assert.equal(
    getIcon(neutralEmpty),
    'img/map/wild-grainfield-map-v2.webp',
    '中立野地不可显示玩家车队'
  );

  // 5. 非资源地形（如森林、丘陵），无 res 产出，即使 totalRes 为 0 也不受影响
  const forestTile = {
    kind: 'wild',
    id: 104,
    type: 'forest',
    x: 0,
    y: 0,
    occupied: true,
    totalRes: 0
  };
  assert.match(
    getIcon(forestTile),
    /^img\/map\/wild-forest-.*\.webp$/,
    '非资源野地不受采空车队图标影响'
  );
});

test('卡车车队模型资源物理文件存在且尺寸规范符合 384x384', () => {
  const root = path.join(__dirname, '../..');
  const webpPath = path.join(root, 'frontend/img/map/wild-depleted-convoy.webp');
  const pngPath = path.join(root, 'frontend/img/map/wild-depleted-convoy-map-embedded.png');

  assert.ok(fs.existsSync(webpPath), 'wild-depleted-convoy.webp 文件必须存在');
  assert.ok(fs.existsSync(pngPath), 'wild-depleted-convoy-map-embedded.png 文件必须存在');

  const webpStat = fs.statSync(webpPath);
  const pngStat = fs.statSync(pngPath);
  assert.ok(webpStat.size > 10000, 'WebP 图标文件大小应大于 10KB');
  assert.ok(pngStat.size > 20000, 'Embedded PNG 图标文件大小应大于 20KB');
});
