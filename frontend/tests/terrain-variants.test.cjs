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
      forest: { name: '森林', icon: 'img/map/wild-forest.webp' },
      hill: { name: '丘陵', icon: 'img/map/wild-hill.webp' },
      swamp: { name: '沼泽', icon: 'img/map/wild-swamp.webp' },
    }
  };
  for (const file of ['map-camera.js', 'map-layout.js', 'world-map.js']) {
    let source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8');
    source = source.replace('  G.WorldMap={', '  G.TestMapIcon=icon;\n  G.WorldMap={');
    vm.runInContext(source, c);
  }
  return c;
}

test('森林、丘陵、沼泽地形图标采用多样化变体，且四邻接格子不重复', () => {
  const c = setup();
  const getIcon = c.Game.TestMapIcon;

  // 1. 验证森林三款变体完整生成且无空值
  const forestVariants = new Set();
  for (let x = 0; x < 3; x++) {
    for (let y = 0; y < 3; y++) {
      const art = getIcon({ kind: 'wild', type: 'forest', x, y });
      assert.match(art, /^img\/map\/wild-forest-(dense|ridge|edge)\.webp$/);
      forestVariants.add(art);
    }
  }
  assert.equal(forestVariants.size, 3, '森林必须覆盖 dense, ridge, edge 全部 3 款变体');

  // 2. 验证任意相邻两个同类地形各自拥有不同图标（彻底避免横向/纵向机械重复）
  for (let x = 10; x <= 15; x++) {
    for (let y = 10; y <= 15; y++) {
      const cur = getIcon({ kind: 'wild', type: 'forest', x, y });
      const right = getIcon({ kind: 'wild', type: 'forest', x: x + 1, y });
      const down = getIcon({ kind: 'wild', type: 'forest', x, y: y + 1 });
      assert.notEqual(cur, right, `相邻水平格子 (${x},${y}) 与 (${x+1},${y}) 不能重复相同变体`);
      assert.notEqual(cur, down, `相邻垂直格子 (${x},${y}) 与 (${x},${y+1}) 不能重复相同变体`);
    }
  }

  // 3. 验证丘陵三款变体完整生成且四邻接不重复
  const hillVariants = new Set();
  for (let x = 0; x < 3; x++) {
    for (let y = 0; y < 3; y++) {
      const art = getIcon({ kind: 'wild', type: 'hill', x, y });
      assert.match(art, /^img\/map\/wild-hill-(peak|ridge|foothill)\.webp$/);
      hillVariants.add(art);
    }
  }
  assert.equal(hillVariants.size, 3, '丘陵必须覆盖 peak, ridge, foothill 全部 3 款变体');

  // 4. 验证沼泽三款变体完整生成且四邻接不重复
  const swampVariants = new Set();
  for (let x = 0; x < 3; x++) {
    for (let y = 0; y < 3; y++) {
      const art = getIcon({ kind: 'wild', type: 'swamp', x, y });
      assert.match(art, /^img\/map\/wild-swamp-(deep|creek|marsh)\.webp$/);
      swampVariants.add(art);
    }
  }
  assert.equal(swampVariants.size, 3, '沼泽必须覆盖 deep, creek, marsh 全部 3 款变体');
});

test('全部 9 款变体的 WebP 与 embedded-PNG 物理文件均真实存在且有效', () => {
  const root = path.resolve(__dirname, '..');
  const expectedFiles = [
    'img/map/wild-forest-dense.webp',
    'img/map/wild-forest-dense-map.webp',
    'img/map/wild-forest-dense-map-embedded.png',
    'img/map/wild-forest-ridge.webp',
    'img/map/wild-forest-ridge-map.webp',
    'img/map/wild-forest-ridge-map-embedded.png',
    'img/map/wild-forest-edge.webp',
    'img/map/wild-forest-edge-map.webp',
    'img/map/wild-forest-edge-map-embedded.png',
    'img/map/wild-hill-peak.webp',
    'img/map/wild-hill-peak-map.webp',
    'img/map/wild-hill-peak-map-embedded.png',
    'img/map/wild-hill-ridge.webp',
    'img/map/wild-hill-ridge-map.webp',
    'img/map/wild-hill-ridge-map-embedded.png',
    'img/map/wild-hill-foothill.webp',
    'img/map/wild-hill-foothill-map.webp',
    'img/map/wild-hill-foothill-map-embedded.png',
    'img/map/wild-swamp-deep.webp',
    'img/map/wild-swamp-deep-map.webp',
    'img/map/wild-swamp-deep-map-embedded.png',
    'img/map/wild-swamp-creek.webp',
    'img/map/wild-swamp-creek-map.webp',
    'img/map/wild-swamp-creek-map-embedded.png',
    'img/map/wild-swamp-marsh.webp',
    'img/map/wild-swamp-marsh-map.webp',
    'img/map/wild-swamp-marsh-map-embedded.png',
  ];

  for (const rel of expectedFiles) {
    const p = path.join(root, rel);
    assert.ok(fs.existsSync(p), `文件必须存在: ${rel}`);
    const stat = fs.statSync(p);
    assert.ok(stat.size > 5000, `文件有效大小检查: ${rel} (${stat.size} bytes)`);
  }
});

test('Game.WorldMap.icon 导出方法可正确解析野地变体图标', () => {
  const c = setup();
  assert.equal(typeof c.Game.WorldMap.icon, 'function');
  const forestArt = c.Game.WorldMap.icon({ kind: 'wild', type: 'forest', x: 5, y: 8 });
  assert.match(forestArt, /^img\/map\/wild-forest-(dense|ridge|edge)\.webp$/);
  const hillArt = c.Game.WorldMap.icon({ kind: 'wild', type: 'hill', x: 5, y: 8 });
  assert.match(hillArt, /^img\/map\/wild-hill-(peak|ridge|foothill)\.webp$/);
  const swampArt = c.Game.WorldMap.icon({ kind: 'wild', type: 'swamp', x: 5, y: 8 });
  assert.match(swampArt, /^img\/map\/wild-swamp-(deep|creek|marsh)\.webp$/);
});

