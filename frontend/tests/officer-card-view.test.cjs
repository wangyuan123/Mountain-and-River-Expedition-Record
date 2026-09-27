const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTestEnvironment(officers = []) {
  const elements = {};
  const document = {
    activeElement: null,
    getElementById(id) {
      return elements[id] || null;
    },
    createElement(tag) {
      return {
        tagName: tag.toUpperCase(),
        style: {},
        innerHTML: '',
        parentNode: null
      };
    }
  };

  const context = vm.createContext({
    Date,
    console,
    Math,
    document,
    location: { port: '80', protocol: 'http:', hostname: 'localhost' },
    window: null,
    Game: {
      DATA: {},
      Core: {},
      API: {},
      fmt: (n) => String(n),
      expNeeded: () => 1000,
      toast: () => {},
      escapeHtml: (s) => String(s == null ? '' : s),
      go: () => {}
    }
  });
  context.window = context;
  context.G = context.Game;

  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api-client.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);

  context.Game.Core.state = {
    officers: officers,
    items: {},
    resources: { gold: 500 }
  };

  return context;
}

test('1~3星军官渲染普通无边框卡片 (tier-normal)，无段位金属边框与包角', () => {
  const officers = [
    { id: 'o1', name: '步兵少尉', star: 1, level: 10, exp: 100, role: 'idle', military: 30, defense: 20, logistics: 25, knowledge: 10, skills: [] },
    { id: 'o2', name: '骑兵中尉', star: 2, level: 20, exp: 200, role: 'idle', military: 50, defense: 40, logistics: 45, knowledge: 20, skills: [] },
    { id: 'o3', name: '装甲上尉', star: 3, level: 40, exp: 500, role: 'idle', military: 80, defense: 70, logistics: 75, knowledge: 40, skills: [] }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /class="officer-card tier-normal"/);
  assert.equal((html.match(/tier-normal/g) || []).length, 3);
  assert.doesNotMatch(html, /tier-gold/);
  assert.doesNotMatch(html, /tier-diamond/);
  assert.doesNotMatch(html, /card-corner/);
  assert.doesNotMatch(html, /tier-ribbon/);
});

test('4星军官取消金色边框与包角，渲染普通卡片 (tier-normal)', () => {
  const officers = [
    { id: 'o4', name: '巴顿', star: 4, level: 60, exp: 800, role: 'idle', military: 219, defense: 110, logistics: 120, knowledge: 80, skills: [{ id: 'assault', lv: 3 }] }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /class="officer-card tier-normal"/);
  assert.doesNotMatch(html, /tier-gold/);
  assert.doesNotMatch(html, /card-corner/);
  assert.doesNotMatch(html, /tier-ribbon|荣耀黄金|极度稀有/);
  assert.doesNotMatch(html, /tier-diamond/);
  assert.match(html, /class="officer-gem-stars" role="img" aria-label="4星军官"/);
  assert.equal((html.match(/class="officer-gem-star"/g) || []).length, 4);
  assert.equal((html.match(/class="officer-gem-star is-empty"/g) || []).length, 1);
});

test('5星军事满值显示 UR 并保留钻石边框和包角', () => {
  const officers = [
    { id: 'o5', name: '朱可夫', star: 5, level: 100, exp: 10000, role: 'commander', military: 219, defense: 190, logistics: 160, knowledge: 160, skills: [{ id: 'assault', lv: 5 }, { id: 'marshal', lv: 5 }] }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /class="officer-card tier-diamond"/);
  assert.match(html, /class="tier-ribbon rarity-ur">极度稀有（UR）<\/span>/);
  assert.doesNotMatch(html, /永恒钻石/);
  assert.match(html, /card-corner corner-tl/);
  assert.match(html, /card-corner corner-tr/);
  assert.match(html, /card-corner corner-bl/);
  assert.match(html, /card-corner corner-br/);
  assert.doesNotMatch(html, /tier-gold/);
  assert.match(html, /class="officer-gem-stars" role="img" aria-label="5星军官"/);
  assert.equal((html.match(/class="officer-gem-star"/g) || []).length, 10);
  assert.doesNotMatch(html, /class="officer-gem-star is-empty"/);
});

test('五星稀有度按最高属性区间判定，军事和防御满值优先', () => {
  const cases = [
    { attr: { defense: 219, logistics: 219 }, expected: 'rarity-ur">极度稀有（UR）' },
    { attr: { logistics: 219, military: 218 }, expected: 'rarity-ssr">超稀有（SSR）' },
    { attr: { knowledge: 219, defense: 218 }, expected: 'rarity-ssr">超稀有（SSR）' },
    { attr: { military: 210 }, expected: 'rarity-sr">稀有（SR）' },
    { attr: { defense: 218 }, expected: 'rarity-sr">稀有（SR）' },
    { attr: { knowledge: 209 }, expected: null }
  ];
  for (const { attr, expected } of cases) {
    const officer = { id: 'o5', name: '测试将领', star: 5, level: 100, role: 'idle',
      military: 100, defense: 100, logistics: 100, knowledge: 100, ...attr };
    const context = setupTestEnvironment([officer]);
    const container = { innerHTML: '' };
    context.Game.Officer.renderView(container);
    if (expected) assert.ok(container.innerHTML.includes(expected), JSON.stringify(attr));
    else assert.doesNotMatch(container.innerHTML, /tier-ribbon/);
  }
});

test('军官卡片四维属性矩阵与技能徽章完整展示', () => {
  const officers = [
    { id: 'o5', name: '隆美尔', star: 5, level: 100, exp: 10000, role: 'idle', military: 219, defense: 165, logistics: 175, knowledge: 155, loyalty: 100, salary: 62, skills: [{ id: 'assault', lv: 5 }] }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /pill-mil"><span class="stat-pill-label">军事<\/span><span class="stat-pill-value">219<\/span>/);
  assert.match(html, /pill-def"><span class="stat-pill-label">防御<\/span><span class="stat-pill-value">165<\/span>/);
  assert.match(html, /pill-log"><span class="stat-pill-label">后勤<\/span><span class="stat-pill-value">175<\/span>/);
  assert.match(html, /pill-kno"><span class="stat-pill-label">学识<\/span><span class="stat-pill-value">155<\/span>/);
  assert.match(html, /❤️ 忠诚 <b>100<\/b>/);
  assert.match(html, /🪙 俸禄 <b>62<\/b> 金\/h/);
  assert.match(html, /class="card-skill-tag">/);
  assert.match(html, /已达最高等级上限/);
});

test('参谋部看板正确渲染市长与指挥官的双司令台', () => {
  const officers = [
    { id: 'm1', name: '艾森豪威尔', star: 5, level: 100, role: 'mayor', logistics: 219, knowledge: 210 },
    { id: 'c1', name: '宫本武藏', star: 4, level: 100, role: 'commander', military: 160 }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /slot-mayor/);
  assert.match(html, /👑 执政市长/);
  assert.match(html, /艾森豪威尔/);
  assert.match(html, /后勤 <b>219<\/b> · 学识 <b>210<\/b>/);

  assert.match(html, /slot-commander/);
  assert.match(html, /⚔️ 作战指挥官/);
  assert.match(html, /宫本武藏/);
  assert.match(html, /军事 <b>160<\/b>/);
});

test('军官卡片点击与任命按钮事件绑定正常', () => {
  const officers = [
    { id: 'o1', name: '测试军官', star: 4, level: 20, role: 'idle' }
  ];
  const context = setupTestEnvironment(officers);
  const container = { innerHTML: '' };
  context.Game.Officer.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /onclick="Game\.Officer\.showDetail\('o1'\)"/);
  assert.match(html, /onclick="event\.stopPropagation\(\);Game\.Officer\.appoint\('o1','mayor'\)"/);
  assert.match(html, /onclick="event\.stopPropagation\(\);Game\.Officer\.appoint\('o1','commander'\)"/);
});

test('军官能力属性与数值使用主题色变量，去除黑色字体', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/officer.css'), 'utf8');
  assert.match(css, /\.officer-attr-main\s*>\s*span:first-child\s*\{[^}]*color:\s*var\(--od-accent\)/);
  assert.match(css, /\.officer-attr-main\s*strong\s*\{[^}]*color:\s*var\(--od-accent\)/);
  assert.match(css, /\[data-theme\|="blue-white"\]\s*\.officer-detail\s*\{[^}]*--od-accent:\s*#2b5f9e/);
  assert.match(css, /\[data-theme\|="blue-white"\]\s*\.officer-detail\s*\{[^}]*--od-ink:\s*#1e3a5f/);
});
