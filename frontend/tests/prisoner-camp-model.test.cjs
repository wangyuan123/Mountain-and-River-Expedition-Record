const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('战俘营（prisoner_camp）写实微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/buildings/garden/prisoner_camp.webp');
  assert.ok(fs.existsSync(webpPath), 'prisoner_camp.webp 必须存在于花园卫城建筑模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 15000, `prisoner_camp.webp 文件体积应大于 15KB，实际 ${stat.size} 字节`);

  // 验证深浅背景多尺度验收图存在
  const previewDark = path.resolve(__dirname, '../../output/imagegen/prisoner-camp-20261009/preview-dark.jpg');
  const previewLight = path.resolve(__dirname, '../../output/imagegen/prisoner-camp-20261009/preview-light.jpg');
  assert.ok(fs.existsSync(previewDark), '深色背景多尺度验收图必须存在');
  assert.ok(fs.existsSync(previewLight), '浅色背景多尺度验收图必须存在');
});

test('build.js 中 BUILD_ICON 包含 prisoner_camp 并指向写实透明 WebP', () => {
  const context = vm.createContext({
    window: {},
    document: { createElement: () => ({ style: {} }) }
  });
  context.window = context;

  const dataJs = fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8');
  vm.runInContext(dataJs, context);

  // Minimal Core stub for build.js
  context.Game.Core = { state: { buildings: {} }, buildMul: () => 1.0, views: {} };
  context.Game.Constants = { buildMaxConcurrent: 6 };

  const buildJs = fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8');
  vm.runInContext(buildJs, context);

  const G = context.Game;
  assert.ok(G.Build.BUILD_ICON, 'G.Build.BUILD_ICON 必须已定义');
  assert.equal(
    G.Build.BUILD_ICON.prisoner_camp,
    'img/buildings/garden/prisoner_camp.webp',
    'prisoner_camp 必须正确映射到花园卫城建筑模型文件'
  );

  const iconHtml = G.Build.renderBuildingIcon(G.Build.BUILD_ICON.prisoner_camp, '战俘营');
  assert.match(iconHtml, /src="img\/buildings\/garden\/prisoner_camp\.webp"/);
  assert.match(iconHtml, /alt="战俘营"/);
});
