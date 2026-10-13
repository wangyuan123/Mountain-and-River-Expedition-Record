const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('M8 灰狗写实微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/units/models/recon_car.webp');
  assert.ok(fs.existsSync(webpPath), 'recon_car.webp 必须存在于兵种模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 15000, `recon_car.webp 文件体积应大于 15KB，实际 ${stat.size} 字节`);

  // 验证预览图生成
  const previewDark = path.resolve(__dirname, '../../output/imagegen/recon-car-20261009/preview-dark.jpg');
  const previewLight = path.resolve(__dirname, '../../output/imagegen/recon-car-20261009/preview-light.jpg');
  assert.ok(fs.existsSync(previewDark), '深色主题多尺度验收图必须存在');
  assert.ok(fs.existsSync(previewLight), '浅色主题多尺度验收图必须存在');
});

test('data.js 中兵种模型映射包含 recon_car 并指向写实 WebP', () => {
  const context = vm.createContext({ window: {} });
  context.window = context;
  const dataJs = fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8');
  vm.runInContext(dataJs, context);

  const G = context.window.Game;
  assert.ok(G.UNIT_MODEL, 'Game.UNIT_MODEL 必须已定义');
  assert.equal(G.UNIT_MODEL.recon_car, 'img/units/models/recon_car.webp', 'recon_car 必须正确映射到写实微缩模型文件');
});
