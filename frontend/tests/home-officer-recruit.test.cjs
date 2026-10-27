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

test('首页军官将领栏目标题包含【去招募>】按钮，点击跳转至军校招募，且标题名称旁边无多余图标', () => {
  const officers = [
    { name: '古德里安', level: 10, star: 5, military: 90, logistics: 80, knowledge: 70, role: 'idle' }
  ];
  const G = setupTest(officers);
  const html = G.MainView.renderOfficerSummaryCard();

  // 验证军官将领名称旁边取消了图标，保持清爽纯文本标题
  assert.match(html, /<span class="zone-title">军官将领<\/span>/);
  assert.doesNotMatch(html, /<span class="zone-title"><img/);
  assert.doesNotMatch(html, /rank-major-v1\.webp/);

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
  assert.match(html, /去军校招募 &gt;<\/span>/);
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

test('首页军官卡片紧凑排版：闲置去噪、属性精简为单字、满级与在职突出高亮', () => {
  const G = setupTest([
    { id: 101, name: '巴顿', level: 100, star: 5, role: 'idle', military: 344, logistics: 95, knowledge: 95 },
    { id: 102, name: '曼施坦因', level: 85, star: 5, role: 'commander', military: 120, logistics: 140, knowledge: 225 }
  ]);

  const html = G.MainView.renderOfficerSummaryCard();

  // 1. 闲置军官不打标，消除视觉杂音
  assert.doesNotMatch(html, /role-idle/);
  assert.doesNotMatch(html, />闲置</);

  // 2. 指挥官/市长在职军官正确打标
  assert.match(html, /<span class="home-officer-role role-commander">指挥官<\/span>/);

  // 3. 属性精简为单字标签并带有详细 title 提示
  assert.match(html, /<span class="home-officer-stat" title="军事 344"><span class="stat-lbl mil">军<\/span><b class="stat-val mil">344<\/b><\/span>/);
  assert.match(html, /<span class="home-officer-stat" title="后勤 95"><span class="stat-lbl log">勤<\/span><b class="stat-val log">95<\/b><\/span>/);
  assert.match(html, /<span class="home-officer-stat" title="学识 95"><span class="stat-lbl kno">学<\/span><b class="stat-val kno">95<\/b><\/span>/);

  // 4. 满级显示 is-max 样式
  assert.match(html, /home-officer-level is-max">Lv\.100<small class="max-badge">\(满\)<\/small><\/span>/);

  // 5. CSS 规则校验紧凑内边距与技能更宽的宽度
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.home-officer-item\s*\{[^}]*padding:\s*4px 7px;/s);
  assert.match(css, /\.home-officer-skill\s*\{[^}]*max-width:\s*60%;/s);

  const classicCss = fs.readFileSync(path.join(__dirname, '../css/classic-home.css'), 'utf8');
  assert.match(classicCss, /\[data-theme="blue-white-classic"\]\s+\.home-officer-item\s*\{[^}]*padding:\s*4px 0\s*!important;/s);
});
