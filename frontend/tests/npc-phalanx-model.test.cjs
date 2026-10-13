const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('NPC 步坦协同方阵写实微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/map/npc-fortress-orthogonal.webp');
  assert.ok(fs.existsSync(webpPath), 'npc-fortress-orthogonal.webp 必须存在于地图模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 20000, `npc-fortress-orthogonal.webp 文件体积应大于 20KB，实际 ${stat.size} 字节`);

  const embeddedPng = path.join(root, 'img/map/npc-fortress-orthogonal-map-embedded.png');
  assert.ok(fs.existsSync(embeddedPng), 'npc-fortress-orthogonal-map-embedded.png 必须存在');

  const legacyEmbeddedPng = path.join(root, 'img/map/npc-fortress-map-embedded.png');
  assert.ok(fs.existsSync(legacyEmbeddedPng), 'npc-fortress-map-embedded.png 必须存在并更新');
  assert.ok(fs.statSync(legacyEmbeddedPng).size > 20000, 'npc-fortress-map-embedded.png 体积需大于20KB');

  const fortressWebp = path.join(root, 'img/map/npc-fortress.webp');
  assert.ok(fs.existsSync(fortressWebp), 'npc-fortress.webp 必须存在并更新');

  // 验证备份与候选方案归档
  const previewOptions = path.resolve(__dirname, '../../output/imagegen/npc-phalanx-20261011/preview-options.png');
  const comparisonSheet = path.resolve(__dirname, '../../output/imagegen/npc-phalanx-20261011/comparison-old-vs-new.png');
  assert.ok(fs.existsSync(previewOptions), '3 款方案比选总览表必须存在');
  assert.ok(fs.existsSync(comparisonSheet), '新旧大地图实装对比图必须存在');
});

test('world-map.js 中 NPC/流寇目标正确映射到新步坦方阵模型', () => {
  const context = {
    console,
    Date,
    Map,
    Set,
    WeakMap,
    Uint8Array,
    Game: {
      MapChunks: function () {},
      DATA: {
        wildTypes: {}
      }
    },
    document: {
      createElement: () => ({
        getContext: () => ({
          drawImage() {},
          getImageData: () => ({ data: [] })
        })
      })
    }
  };
  context.window = context;
  vm.createContext(context);

  require('./load-constants.cjs')(context);

  const worldMapJs = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  vm.runInContext(worldMapJs, context);

  const G = context.Game;
  assert.ok(G.WorldMap, 'Game.WorldMap 必须已定义');
  assert.equal(typeof G.WorldMap.icon, 'function', 'WorldMap.icon 必须是函数');

  const npcIcon = G.WorldMap.icon({ kind: 'npc', x: 10, y: 10 });
  assert.equal(npcIcon, 'img/map/npc-fortress-orthogonal.webp', 'NPC 流寇据点必须正确映射到 npc-fortress-orthogonal.webp');

  const banditIcon = G.WorldMap.icon({ kind: 'bandit', x: 20, y: 20 });
  assert.equal(banditIcon, 'img/map/npc-fortress-orthogonal.webp', '日寇流寇必须正确映射到 npc-fortress-orthogonal.webp');

  assert.ok(worldMapJs.includes('v=5.6-npc-square'), 'world-map.js 纹理请求必须包含 v=5.6-npc-square 防缓存版本号');

  const indexHtml = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  assert.ok(indexHtml.includes('world-map.js?v=5.21-npc-square'), 'index.html 中 world-map.js 必须引入最新版本防缓存参数');
});
