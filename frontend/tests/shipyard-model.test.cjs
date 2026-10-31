const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('造船厂（shipyard）专属3D微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/buildings/garden/shipyard.webp');
  assert.ok(fs.existsSync(webpPath), 'shipyard.webp 必须存在于花园卫城建筑模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 20000, `shipyard.webp 文件体积应大于 20KB，实际 ${stat.size} 字节`);

  // 验证原稿与比选归档
  const rawPath = path.resolve(__dirname, '../../output/imagegen/shipyard-20261012/raw/option-2-slipway-crane.jpg');
  const sheetPath = path.resolve(__dirname, '../../output/imagegen/shipyard-20261012/preview/shipyard_options_comparison_sheet.png');
  assert.ok(fs.existsSync(rawPath), '方案二原稿必须保留归档');
  assert.ok(fs.existsSync(sheetPath), '造船厂比选总览图必须存在');
});

test('build.js 中造船厂正确映射至独立专属模型，不再复用军港', () => {
  const context = {
    window: {},
    document: {},
    Game: {
      DATA: {
        buildings: {
          shipyard: { name: '造船厂' },
          port: { name: '军港' }
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

  assert.equal(G.Build.BUILD_ICON.shipyard, 'img/buildings/garden/shipyard.webp', '造船厂必须映射到独立专属模型 shipyard.webp');
  assert.equal(G.Build.BUILD_ICON.port, 'img/buildings/garden/port.webp', '军港保留原本独立的 port.webp');
  assert.notEqual(G.Build.BUILD_ICON.shipyard, G.Build.BUILD_ICON.port, '造船厂与军港不得再共用同一套图标');

  const indexHtml = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  assert.ok(/build\.js\?v=20261031-server-authority/.test(indexHtml), 'index.html 需引用最新防缓存版本号');
});
