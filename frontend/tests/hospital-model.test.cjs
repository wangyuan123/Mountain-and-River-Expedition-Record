const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('战地医院（hospital）专属3D微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const webpPath = path.join(root, 'img/buildings/garden/hospital.webp');
  assert.ok(fs.existsSync(webpPath), 'hospital.webp 必须存在于花园卫城建筑模型目录中');

  const stat = fs.statSync(webpPath);
  assert.ok(stat.size > 20000, `hospital.webp 文件体积应大于 20KB，实际 ${stat.size} 字节`);

  // 验证原稿与比选归档
  const rawPath = path.resolve(__dirname, '../../output/imagegen/field-hospital-20261012/raw/option-1-pavilion-clinic.png');
  const sheetPath = path.resolve(__dirname, '../../output/imagegen/field-hospital-20261012/preview/hospital_options_comparison_sheet.png');
  assert.ok(fs.existsSync(rawPath), '方案一原稿必须保留归档');
  assert.ok(fs.existsSync(sheetPath), '战地医院比选总览图必须存在');
});

test('build.js 中战地医院正确映射至独立专属模型', () => {
  const context = {
    window: {},
    document: {},
    Game: {
      DATA: {
        buildings: {
          hospital: { name: '战地医院' },
          house: { name: '民居' }
        }
      },
      Core: {
        state: { buildings: {} },
        buildMul: () => 1,
        views: {}
      },
      Constants: {
        buildMaxConcurrent: 6
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

  assert.equal(G.Build.BUILD_ICON.hospital, 'img/buildings/garden/hospital.webp', '战地医院必须映射到专属模型 hospital.webp');
  assert.equal(G.Build.BUILD_ICON.house, 'img/buildings/garden/house.webp', '民居保留原本独立的 house.webp');
});
