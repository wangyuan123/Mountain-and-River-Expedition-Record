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

test('首页军官将领栏目标题包含【去招募>】按钮，点击跳转至陆军讲武堂招募', () => {
  const officers = [
    { name: '古德里安', level: 10, star: 5, military: 90, logistics: 80, knowledge: 70, role: 'idle' }
  ];
  const G = setupTest(officers);
  const html = G.MainView.renderOfficerSummaryCard();

  // 验证包含 去招募 按钮及样式类
  assert.match(html, /<span class="home-officer-go zone-head-action"[^>]*>去招募 &gt;<\/span>/);
  // 验证点击事件跳转到 academy (讲武堂)
  assert.match(html, /onclick="event\.stopPropagation\(\);Game\.go\('academy'\)"/);
  // 验证带有 title 提示
  assert.match(html, /title="点击前往陆军讲武堂 · 招募将领"/);
});

test('首页在无军官时标题栏依然展示【去招募>】按钮', () => {
  const G = setupTest([]);
  const html = G.MainView.renderOfficerSummaryCard();

  assert.match(html, /<span class="home-officer-go zone-head-action"[^>]*>去招募 &gt;<\/span>/);
  assert.match(html, /Game\.go\('academy'\)/);
  assert.match(html, /去讲武堂招募 &gt;<\/span>/);
  assert.match(html, /暂未招募将领/);
});

test('首页军官列表完整展示，并由样式控制为三排横向滚动', () => {
  const officers = Array.from({ length: 7 }, (_, index) => ({
    id: index + 1,
    name: '将领' + (index + 1),
    level: 10 - index,
    star: 5,
    military: 90,
    logistics: 80,
    knowledge: 70,
    role: 'idle'
  }));
  const G = setupTest(officers);
  const html = G.MainView.renderOfficerSummaryCard();

  assert.equal((html.match(/class="home-officer-item home-officer-item-action"/g) || []).length, 7);
  assert.match(html, /共 <b>7<\/b> 名将领/);
  assert.match(html, /class="home-officer-list"/);

  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.home-officer-list\s*\{[^}]*grid-auto-flow:\s*column;[^}]*grid-template-rows:\s*repeat\(3,/s);
  assert.match(css, /\.home-officer-list\s*\{[^}]*overflow-x:\s*auto;/s);
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

test('首页军官技能显示各自图标，未知技能使用默认符号', () => {
  const G = setupTest([
    { id: 1, name: '市长', level: 10, star: 5, role: 'mayor', skills: [{ id: 'finance', lv: 5 }] },
    { id: 2, name: '指挥官', level: 10, star: 5, role: 'commander', skills: [{ id: 'leadership', lv: 5 }] },
    { id: 3, name: '侦察官', level: 10, star: 5, role: 'idle', skills: [{ id: 'blitz', lv: 5 }] },
    { id: 4, name: '旧技能', level: 10, star: 5, role: 'idle', skills: ['legacy'] }
  ]);
  G.DATA.officerSkills = {
    finance: { name: '精明理财', icon: '🪙' },
    leadership: { name: '三军统帅', icon: '🎖️' },
    blitz: { name: '闪电突击', icon: '⚡' }
  };
  G.Core.formatSkills = skills => skills.map(skill => {
    const id = typeof skill === 'string' ? skill : skill.id;
    return (G.DATA.officerSkills[id] || { name: id }).name;
  }).join('/');

  const html = G.MainView.renderOfficerSummaryCard();
  assert.match(html, /title="精明理财"><span aria-hidden="true">🪙<\/span> 精明理财/);
  assert.match(html, /title="三军统帅"><span aria-hidden="true">🎖️<\/span> 三军统帅/);
  assert.match(html, /title="闪电突击"><span aria-hidden="true">⚡<\/span> 闪电突击/);
  assert.match(html, /title="legacy"><span aria-hidden="true">✦<\/span> legacy/);
});
