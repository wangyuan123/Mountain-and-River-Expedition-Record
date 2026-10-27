const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('联防雷达（guild_radar）专属3D微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/buildings/garden/guild_radar.webp');
  const radarWebpPath = path.join(root, 'img/buildings/garden/radar.webp');
  assert.ok(fs.existsSync(webpPath), 'guild_radar.webp 必须存在于花园卫城建筑模型目录中');
  assert.ok(fs.existsSync(radarWebpPath), '城防雷达 radar.webp 也必须存在');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 20000, `guild_radar.webp 文件体积应大于 20KB，实际 ${stat.size} 字节`);

  // 联防雷达图标不得与城防雷达文件内容相同
  const guildBuf = fs.readFileSync(webpPath);
  const radarBuf = fs.readFileSync(radarWebpPath);
  assert.notEqual(guildBuf.compare(radarBuf), 0, '联防雷达与城防雷达必须使用不同的微缩模型图标');

  // 验证原稿与比选归档
  const rawPath = path.resolve(__dirname, '../../output/imagegen/guild-radar-20261026/raw/option-3-phased-array-bunker.jpg');
  const sheetPath = path.resolve(__dirname, '../../output/imagegen/guild-radar-20261026/preview/radar_options_showcase.png');
  assert.ok(fs.existsSync(rawPath), '方案原稿必须保留归档');
  assert.ok(fs.existsSync(sheetPath), '雷达对比比选总览图必须存在');
});

test('build.js 中联防雷达与城防雷达各自独立映射至专属模型', () => {
  const context = {
    window: {},
    document: {},
    Game: {
      DATA: {
        buildings: {
          radar: { name: '城防雷达' },
          guild_radar: { name: '联防雷达' }
        }
      },
      Core: {
        state: { buildings: {} },
        buildMul: () => 1,
        views: {}
      },
      Constants: {
        buildMaxConcurrent: 3
      }
    }
  };
  context.window = context;
  vm.createContext(context);

  const buildJs = fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8');
  vm.runInContext(buildJs, context);

  const G = context.Game;
  assert.ok(G.Build, 'Game.Build 必须已定义');
  assert.ok(G.Build.BUILD_ICON, 'G.Build.BUILD_ICON 必须已定义');

  assert.equal(G.Build.BUILD_ICON.radar, 'img/buildings/garden/radar.webp', '城防雷达保持映射到 radar.webp');
  assert.equal(G.Build.BUILD_ICON.guild_radar, 'img/buildings/garden/guild_radar.webp', '联防雷达必须映射到独立专属模型 guild_radar.webp');
  assert.notEqual(G.Build.BUILD_ICON.guild_radar, G.Build.BUILD_ICON.radar, '城防雷达与联防雷达素材路径必须区分开');
});
