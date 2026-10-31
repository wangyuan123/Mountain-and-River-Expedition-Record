const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup(chance, refreshAt = 0, quota = {}, list = []) {
  const state = { buildings: { academy: 10 }, resources: { gold: 1000 },
    academy: { list: list.map(o => ({ ...o, recruitCost: ({ 1: 80, 2: 160, 3: 240, 4: 320, 5: 400 })[o.star] })), fiveStarBatchChance: chance, refreshAt,
      refreshRoundLimit: 30, refreshDailyLimit: 100, ...quota } };
  const nodes = new Map();
  const document = {
    getElementById(id) { return nodes.get(id) || null; },
    createElement() {
      const close = { focus() { document.activeElement = close; } };
      return { innerHTML: '', querySelector() { return close; },
        addEventListener(type, handler) { this[type] = handler; } };
    },
    body: {
      appendChild(node) { nodes.set(node.id, node); node.parentNode = this; },
      removeChild(node) { nodes.delete(node.id); node.parentNode = null; }
    }
  };
  const context = vm.createContext({ Date, console, document, window: null, Game: {
    DATA: { starColor: { 1: '#fff', 2: '#4a90e2', 3: '#9013fe', 4: '#f5a623', 5: '#ffe14a' } }, Core: { state, views: {}, render() {} },
    API: {}, escapeHtml(value) { return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); },
    toast(message) { this.message = message; }
  } });
  context.Game.testDocument = document;
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);
  return context.Game;
}

test('军校概览保留等级、每批人数与服务端概率，详细说明收进问号入口', () => {
  for (const [chance, label] of [[0.003, '0.3%'], [0.03, '3%']]) {
    const G = setup(chance);
    const view = { innerHTML: '' };
    G.Officer.renderAcademyView(view);
    assert.ok(view.innerHTML.includes('军校 Lv.10 · 每批10人 · 五星名将概率 <b>' + label + '</b>'));
    assert.match(view.innerHTML, /class="link-act"[^>]*>？招募说明/);
    assert.match(view.innerHTML, /onclick="Game.Officer.showAcademyInfo\(\)"/);
    assert.match(view.innerHTML, /onkeydown=.*Enter.*preventDefault/);
    assert.doesNotMatch(view.innerHTML, /每批最多1名五星|冷却1小时|星级×80|满级合计|已生成名单/);
  }
});

test('军校说明弹窗包含全部招募、概率、费用与额度规则，并支持关闭和重新打开', () => {
  const G = setup(0.06, 0, { baseFiveStarBatchChance: 0.03, militaryTheoryLevel: 10, militaryTheoryBonus: 0.03 });
  const doc = G.testDocument;
  const trigger = { isConnected: true, focus() { doc.activeElement = trigger; } };
  doc.activeElement = trigger;
  G.Officer.showAcademyInfo();
  let modal = doc.getElementById('academyInfoModal');
  const html = modal.innerHTML;
  for (const rule of ['新建军校后才能招募军官', '消耗200黄金', '刷新10名候选人', '星级×80',
    '每批最多1名五星', '当前整批概率为 <b>6%</b>', '军校基础 3% ＋ 军事理论 Lv.10 加成 3个百分点',
    '已生成名单不随升级变化', '五星军官使用名将姓名', '两者满级合计6%', '征募令',
    '每轮最多30次', '冷却1小时', '每日最多100次', '北京时间0点重置', '账号各城市共享刷新额度', '跨日不会提前结束']) {
    assert.ok(html.includes(rule), rule);
  }
  assert.match(html, /role="dialog" aria-modal="true" aria-labelledby="academyInfoTitle"/);
  assert.equal(doc.activeElement, modal.querySelector('#academyInfoClose'));
  G.Officer.showAcademyInfo();
  assert.equal(doc.getElementById('academyInfoModal'), modal);
  modal.click({ target: modal.querySelector('#academyInfoClose') });
  assert.equal(doc.getElementById('academyInfoModal'), modal, '点击弹窗内容不关闭');
  modal.querySelector('#academyInfoClose').onclick();
  assert.equal(doc.getElementById('academyInfoModal'), null);
  assert.equal(doc.activeElement, trigger);
  for (const method of ['escape', 'backdrop']) {
    G.Officer.showAcademyInfo();
    modal = doc.getElementById('academyInfoModal');
    if (method === 'escape') modal.onkeydown({ key: 'Escape' });
    else modal.click({ target: modal });
    assert.equal(doc.getElementById('academyInfoModal'), null);
    assert.equal(doc.activeElement, trigger);
  }
});

test('未建军校仍可查看招募说明，缺少服务端概率时不编造数值', () => {
  const G = setup(undefined);
  G.Core.state.buildings.academy = 0;
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /尚未建造军校/);
  assert.match(view.innerHTML, /Game.Officer.showAcademyInfo/);
  assert.doesNotMatch(view.innerHTML, /Game.Officer.onRefreshClick|五星名将概率/);
  G.Officer.showAcademyInfo();
  const modal = G.testDocument.getElementById('academyInfoModal');
  assert.doesNotMatch(modal.innerHTML, /当前整批概率为|undefined|NaN/);
});

test('军校冷却中不提供立即刷新或自动刷新提示', () => {
  const G = setup(0.03, Date.now() + 3600000, { refreshRoundCount: 30, refreshDailyCount: 30 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /分钟\d+秒后可再次刷新/);
  assert.match(view.innerHTML, /本轮已用满30次/);
  assert.doesNotMatch(view.innerHTML, /onclick="Game\.Officer\.refreshAcademy|自动|立即刷新/);
});

test('一轮未满30次时可连续刷新，显示本轮与今日已用次数', () => {
  for (const count of [0, 1, 29]) {
    const G = setup(0.03, 0, { refreshRoundCount: count, refreshDailyCount: count });
    const view = { innerHTML: '' };
    G.Officer.renderAcademyView(view);
    assert.ok(view.innerHTML.includes('本轮已刷新 ' + count + '/30'));
    assert.ok(view.innerHTML.includes('今日已刷新 ' + count + '/100'));
    assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
    assert.doesNotMatch(view.innerHTML, /disabled|刷新休整中/);
  }
});

test('每日100次用满时禁用按钮，即使本轮尚未满30次', () => {
  const G = setup(0.03, 0, { refreshRoundCount: 10, refreshDailyCount: 100, refreshDailyResetAt: Date.now() + 3600000 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /今日刷新已达100次（游戏时间次日0点重置，停服顺延）/);
  assert.match(view.innerHTML, /disabled/);
  assert.doesNotMatch(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('冷却到期后开启下一轮，保留当日累计次数', () => {
  const G = setup(0.03, Date.now() - 1, { refreshRoundCount: 30, refreshDailyCount: 60 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('本轮已刷新 0/30'));
  assert.ok(view.innerHTML.includes('今日已刷新 60/100'));
  assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('跨日恢复日额度但不能提前跳过本轮冷却', () => {
  const G = setup(0.03, Date.now() + 3600000, { refreshRoundCount: 30, refreshDailyCount: 100, refreshDailyResetAt: Date.now() - 1 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('今日已刷新 0/100'));
  assert.match(view.innerHTML, /刷新休整中/);
  assert.doesNotMatch(view.innerHTML, /今日刷新已达|Game.Officer.onRefreshClick/);
});

test('跨日且未在冷却时可再次刷新', () => {
  const G = setup(0.03, 0, { refreshRoundCount: 10, refreshDailyCount: 100, refreshDailyResetAt: Date.now() - 1 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('今日已刷新 0/100'));
  assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('后端拒绝刷新时显示失败原因', async () => {
  const G = setup(0.03);
  G.API.refreshAcademy = async () => ({ success: false, message: '黄金不足(需200)' });
  await G.Officer.refreshAcademy();
  assert.equal(G.message, '黄金不足(需200)');
});

test('军校候选五星显示钻石边框、包角和属性稀有度，四星及以下为普通卡片不显示金色边框', () => {
  const G = setup(0.03, 0, {}, [
    { id: 'five', name: '<五星候选>', star: 5, military: 219, defense: 180, logistics: 160, knowledge: 150, skills: [] },
    { id: 'four', name: '四星候选', star: 4, military: 140, defense: 130, logistics: 120, knowledge: 110, skills: [] }
  ]);
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  const html = view.innerHTML;
  assert.match(html, /menu-item ok officer-card tier-diamond/);
  assert.match(html, /&lt;五星候选&gt;/);
  assert.doesNotMatch(html, /<五星候选>/);
  assert.match(html, /class="tier-ribbon rarity-ur">极度稀有（UR）<\/span>/);
  assert.match(html, /corner-tl/);
  assert.match(html, /menu-item ok officer-card tier-normal/);
  assert.doesNotMatch(html, /tier-gold/);
  assert.doesNotMatch(html, /荣耀黄金|永恒钻石/);
});

test('军校候选人卡片采用紧凑自适应多列网格与微型属性矩阵布局', () => {
  const G = setup(0.03, 0, {}, [
    { id: 'c1', name: '布鲁斯·李', star: 2, military: 55, defense: 54, logistics: 74, knowledge: 60, skills: [{ id: 'first_aid', lv: 2 }] }
  ]);
  G.DATA.officerSkills = { first_aid: { name: '战地急救' } };
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  const html = view.innerHTML;

  assert.match(html, /class="academy-candidates-grid menu">/);
  assert.match(html, /class="menu-item ok officer-card tier-normal academy-candidate-card"/);
  assert.match(html, /class="candidate-header-row"/);
  assert.match(html, /class="candidate-name n"[^>]*>布鲁斯·李<\/span>/);
  assert.match(html, /class="candidate-stars stars"/);
  assert.match(html, /class="candidate-star-pill star-2">2星<\/span>/);

  // 四维微型矩阵与最高属性高亮（后勤74最高）
  assert.match(html, /class="c-stat-pill pill-mil"[^>]*><span class="c-stat-lbl">军<\/span><span class="c-stat-val">55<\/span><\/div>/);
  assert.match(html, /class="c-stat-pill pill-def"[^>]*><span class="c-stat-lbl">防<\/span><span class="c-stat-val">54<\/span><\/div>/);
  assert.match(html, /class="c-stat-pill pill-log is-peak"[^>]*><span class="c-stat-lbl">后<\/span><span class="c-stat-val">74<\/span><\/div>/);
  assert.match(html, /class="c-stat-pill pill-kno"[^>]*><span class="c-stat-lbl">学<\/span><span class="c-stat-val">60<\/span><\/div>/);

  // 特长技能标签与招募按钮集成价格
  assert.match(html, /class="candidate-skill-tag"[^>]*>⚡ 战地急救 满级<\/span>/);
  assert.match(html, /class="link-act candidate-recruit-btn"/);
  assert.match(html, /<span class="btn-recruit-cost">🪙160<\/span>/);
  assert.match(html, /onclick="Game\.Officer\.recruit\(0\)"/);

  // 验证 CSS 中网格与卡片尺寸规范
  const css = fs.readFileSync(path.join(__dirname, '../css/officer.css'), 'utf8');
  assert.match(css, /\.academy-candidates-grid\s*\{[^}]*display:\s*grid;/);
  assert.match(css, /\.academy-candidate-card\s*\{[^}]*min-height:\s*82px;/);
  assert.match(css, /\.academy-candidate-card \.stars\s*\{[^}]*display:\s*inline-flex/);
});

test('资金不足时候选卡片标记锁定状态并禁用招募按钮', () => {
  const G = setup(0.03, 0, {}, [
    { id: 'c2', name: '休·约翰逊', star: 4, military: 81, defense: 86, logistics: 102, knowledge: 86, skills: [] }
  ]);
  G.Core.state.resources.gold = 100; // 4星需 320金
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  const html = view.innerHTML;

  assert.match(html, /class="menu-item lock officer-card tier-normal academy-candidate-card is-locked"/);
  assert.match(html, /class="link-act disabled candidate-recruit-btn is-disabled" disabled/);
  assert.match(html, /title="黄金不足 \(需 320 黄金\)"/);
  assert.match(html, /onclick="event\.stopPropagation\(\);Game\.toast\('黄金不足，无法招募'\);"/);
});

test('军校界面包含极速刷新勾选框，且支持状态读取与持久化切换', () => {
  const G = setup(0.03);
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /id="academy-fast-refresh-checkbox"/);
  assert.match(view.innerHTML, /Game\.Officer\.toggleFastRefresh\(this\.checked\)/);
  assert.match(view.innerHTML, /极速刷新/);
  assert.match(view.innerHTML, /id="academy-candidates-region"/);

  // 初始默认未勾选
  assert.equal(G.Officer.isFastRefresh(), false);

  // 切换为开启
  G.Officer.toggleFastRefresh(true);
  assert.equal(G.Officer.isFastRefresh(), true);

  // 开启后渲染时应当带 checked
  const viewChecked = { innerHTML: '' };
  G.Officer.renderAcademyView(viewChecked);
  assert.match(viewChecked.innerHTML, /id="academy-fast-refresh-checkbox"[^>]*checked/);

  // 切换为关闭
  G.Officer.toggleFastRefresh(false);
  assert.equal(G.Officer.isFastRefresh(), false);
});

test('CSS 包含军校机密调令公文袋、3D翻牌和5星悬停特写与光环样式规范', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/officer.css'), 'utf8');
  assert.match(css, /\.academy-dispatch-dossier\s*\{/);
  assert.match(css, /\.dossier-seal-stamp\s*\{/);
  assert.match(css, /\.academy-anim-cards-grid\s*\{[^}]*perspective:\s*1200px;/);
  assert.match(css, /\.card-flip-inner\s*\{[^}]*transform-style:\s*preserve-3d;/);
  assert.match(css, /\.card-face-back\s*\{[^}]*backface-visibility:\s*hidden;/);
  assert.match(css, /\.academy-anim-card-wrap\.has-five-star\.is-star5-spotlight/);
  assert.match(css, /\.star5-burst-glow\s*\{/);
});

