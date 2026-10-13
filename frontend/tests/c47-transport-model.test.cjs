const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('C-47 运输机写实微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/units/models/transport.webp');
  assert.ok(fs.existsSync(webpPath), 'transport.webp 必须存在于兵种模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 15000, `transport.webp 文件体积应大于 15KB，实际 ${stat.size} 字节`);

  // 验证与轰炸机模型不是同一文件
  const bomberPath = path.join(root, 'img/units/models/bomber.webp');
  assert.ok(fs.existsSync(bomberPath), 'bomber.webp 必须存在');
  const transportBuf = fs.readFileSync(webpPath);
  const bomberBuf = fs.readFileSync(bomberPath);
  assert.notDeepEqual(transportBuf, bomberBuf, '运输机与轰炸机不能使用相同资源');

  // 验证预览图生成
  const previewDark = path.resolve(__dirname, '../../output/imagegen/c47-transport-20261010/preview-dark.jpg');
  const previewLight = path.resolve(__dirname, '../../output/imagegen/c47-transport-20261010/preview-light.jpg');
  const comp = path.resolve(__dirname, '../../output/imagegen/c47-transport-20261010/transport_vs_bomber_comparison.png');
  assert.ok(fs.existsSync(previewDark), '深色主题多尺度验收图必须存在');
  assert.ok(fs.existsSync(previewLight), '浅色主题多尺度验收图必须存在');
  assert.ok(fs.existsSync(comp), '部队表格同屏对比图必须存在');
});

test('data.js 中兵种模型映射包含 transport 并指向正确的 WebP', () => {
  const context = vm.createContext({ window: {} });
  context.window = context;
  const dataJs = fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8');
  vm.runInContext(dataJs, context);

  const G = context.window.Game;
  assert.ok(G.UNIT_MODEL, 'Game.UNIT_MODEL 必须已定义');
  assert.equal(G.UNIT_MODEL.transport, 'img/units/models/transport.webp', 'transport 必须正确映射到 C-47 运输机微缩模型文件');
});
