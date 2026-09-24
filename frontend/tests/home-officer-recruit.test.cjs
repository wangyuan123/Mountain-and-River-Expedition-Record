const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTest(officers = []) {
  const state = {
    officers: officers,
    army: {},
    tax: 10,
    buildings: { academy: 1 }
  };

  const context = vm.createContext({
    console,
    Math,
    Date,
    parseInt,
    document: {
      getElementById: () => null,
      createElement: () => ({ style: {}, setAttribute: () => {} })
    },
    Game: {
      DATA: {
        units: {},
        starColor: { 1: '#fff', 2: '#4a90e2', 3: '#9013fe', 4: '#f5a623', 5: '#ffe14a' }
      },
      fmt: (n) => String(n),
      escapeHtml: (str) => str || '',
      go: () => {},
      Core: {
        state,
        views: {},
        armyCap: () => 10000,
        civilianPopulation: () => 1000,
        populationCapacity: () => 2000,
        populationGrowthPerHour: () => 100,
        morale: () => 100,
        resentment: () => 0,
        capacity: () => ({ food: 10000, steel: 10000, oil: 10000, rare: 10000 }),
        getOfficerByRole: () => null,
        formatSkills: () => ''
      }
    }
  });
  context.window = context;

  const mainViewSrc = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  require('./load-constants.cjs')(context);
  vm.runInContext(mainViewSrc, context);
  return context.Game;
}

test('首页军官将领栏目标题包含【去招募>】按钮，点击跳转至军校招募', () => {
  const officers = [
    { name: '古德里安', level: 10, star: 5, military: 90, logistics: 80, knowledge: 70, role: 'idle' }
  ];
  const G = setupTest(officers);
  const html = G.MainView.renderOfficerSummaryCard();

  // 验证包含 去招募 按钮及样式类
  assert.match(html, /<span class="home-officer-go zone-head-action"[^>]*>去招募 &gt;<\/span>/);
  // 验证点击事件跳转到 academy (军校)
  assert.match(html, /onclick="event\.stopPropagation\(\);Game\.go\('academy'\)"/);
  // 验证带有 title 提示
  assert.match(html, /title="点击前往军校 · 招募将领"/);
});

test('首页在无军官时标题栏依然展示【去招募>】按钮', () => {
  const G = setupTest([]);
  const html = G.MainView.renderOfficerSummaryCard();

  assert.match(html, /<span class="home-officer-go zone-head-action"[^>]*>去招募 &gt;<\/span>/);
  assert.match(html, /Game\.go\('academy'\)/);
});

test('首页点击将领进入详情，只有底部参谋部入口跳转至军官管理', () => {
  const G = setupTest([
    { id: 42, name: '古德里安', level: 10, star: 5, military: 90, logistics: 80, knowledge: 70, role: 'idle' }
  ]);
  const html = G.MainView.renderOfficerSummaryCard();

  assert.match(html, /class="home-officer-card">/);
  assert.doesNotMatch(html, /home-officer-card" onclick="Game\.go\('officer'\)/);
  assert.match(html, /class="home-officer-item home-officer-item-action"[^>]*onclick="Game\.Officer\.showDetail\('42'\)"/);
  assert.match(html, /参谋部 &gt;<\/span>/);
  assert.match(html, /onclick="Game\.go\('officer'\)"[^>]*title="前往参谋部 · 军官管理"/);
});
