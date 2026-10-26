const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(document = { getElementById() { return null; } }) {
  const context = { document };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Core.state = {
    buildings: { wall: 5 },
    forts: { bunker: 10, howitzer: 20, antitank: 0, flak: 5 }
  };
  return context.Game;
}

function createModalDocument() {
  const listeners = new Map();
  const previousFocus = { focus() { document.activeElement = this; } };
  const body = {
    children: [],
    appendChild(element) { element.parentNode = this; this.children.push(element); },
    removeChild(element) { this.children.splice(this.children.indexOf(element), 1); element.parentNode = null; }
  };
  const document = {
    activeElement: previousFocus,
    body,
    querySelector(selector) { return body.children.find(element => element.className.split(' ').includes(selector.slice(1))) || null; },
    createElement() {
      const closeButton = { focus() { document.activeElement = this; } };
      return {
        closeButton,
        querySelector() { return closeButton; },
        addEventListener(type, handler) { this[type] = handler; }
      };
    },
    addEventListener(type, handler) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(handler);
    },
    removeEventListener(type, handler) { if (listeners.has(type)) listeners.get(type).delete(handler); }
  };
  return {
    document, body, previousFocus,
    keydown(key) { for (const handler of Array.from(listeners.get('keydown') || [])) handler({ key }); },
    listenerCount() { return (listeners.get('keydown') || new Set()).size; }
  };
}

test('首页城防总览展示四类工事、共享容量和墙体属性', () => {
  const G = setup();
  const html = G.MainView.renderFortSummaryList();
  for (let i = 0; i < 4; i++) {
    const id = ['bunker', 'howitzer', 'antitank', 'flak'][i];
    assert.match(html, new RegExp('--fort-summary-column:' + (i + 1) + ';--fort-summary-row:1'));
    assert.match(html, new RegExp(G.DATA.forts[id].name));
    assert.match(html, new RegExp('class="fort-summary-icon-img"[^>]*src="img/units/models/' + id + '\\.webp"'));
    assert.equal(G.UNIT_MODEL[id], 'img/units/models/' + id + '.webp');
  }
  assert.match(html, /fort-summary-cnt">×10/);
  assert.match(html, /fort-summary-cnt">×0/);

  const home = { innerHTML: '' };
  G.Core.state.player = { cityName: '测试城', homeModuleOrder: ['army'] };
  G.Core.state.resources = {};
  G.Core.state.officers = [];
  G.Core.state.army = {};
  G.Core.state.world = { cityPos: { x: 1, y: 2 }, marches: [], incoming: [] };
  G.Core.state.reports = [];
  G.Core.cityStatusText = () => ({ status: 'peace', text: '和平' });
  G.Core.getOfficerByRole = () => null;
  G.Core.armyCap = () => 100;
  G.Core.produceOf = () => 0;
  G.Core.foodPerHour = () => 0;
  G.Core.civilianPopulation = () => 0;
  G.Core.capacity = () => ({ food: 100, steel: 100, oil: 100, rare: 100 });
  G.Core.morale = () => 100;
  G.Core.resentment = () => 0;
  G.Core.populationCapacity = () => 100;
  G.Core.populationGrowthPerHour = () => 0;
  G.Core.popFree = () => 0;
  G.Core.views.home(home);
  assert.match(home.innerHTML, /城防总览<\/span>/);
  assert.match(home.innerHTML, /单排横向排列，左右滑动/);
  assert.match(home.innerHTML, /工事 35\/150/);
  assert.match(home.innerHTML, /墙体 <b>1250000<\/b> 生命 · <b>100<\/b> 防御/);
  assert.match(home.innerHTML, /Game\.MainView\.showFortSummaryFullscreen\(\)/);
  assert.match(home.innerHTML, /<span class="zone-head-actions"><button[^>]*class="fort-summary-expand"[^>]*>.*?<\/button><span class="fort-summary-go zone-head-action"[^>]*>去城防 &gt;<\/span><\/span>/s);
  assert.match(home.innerHTML, /<span class="zone-head-actions"><button[^>]*class="army-summary-expand"[^>]*>.*?<\/button><span class="army-dispatch-go zone-head-action"[^>]*>去出征 &gt;<\/span><\/span>/s);
  assert.doesNotMatch(home.innerHTML, /class="fort-go"/);
  G.Core.state.tech = { wall_reinforcement: 10 };
  G.Core.views.home(home);
  assert.match(home.innerHTML, /墙体 <b>1875000<\/b> 生命 · <b>150<\/b> 防御/);
  assert.match(home.innerHTML, /工事 35\/150/);
  assert.doesNotMatch(home.innerHTML, /class="fort-go"/);
});

test('城防总览全屏入口可用 Escape 和关闭按钮退出', () => {
  const listeners = {};
  const previousFocus = { focus() { this.restored = true; } };
  const closeButton = { focus() { this.focused = true; } };
  const body = {
    appendChild(element) { element.parentNode = this; this.modal = element; },
    removeChild(element) { element.parentNode = null; this.modal = null; }
  };
  const document = {
    activeElement: previousFocus,
    body,
    createElement() {
      return {
        querySelector() { return closeButton; },
        addEventListener(type, handler) { this[type] = handler; }
      };
    },
    addEventListener(type, handler) { listeners[type] = handler; },
    removeEventListener(type, handler) { if (listeners[type] === handler) delete listeners[type]; }
  };
  const G = setup(document);

  G.MainView.showFortSummaryFullscreen();
  assert.match(body.modal.innerHTML, /fort-summary-expanded/);
  assert.equal((body.modal.innerHTML.match(/class="fort-summary-item/g) || []).length, 4);
  assert.equal(closeButton.focused, true);
  listeners.keydown({ key: 'Escape' });
  assert.equal(body.modal, null);
  assert.equal(listeners.keydown, undefined);
  assert.equal(previousFocus.restored, true);
});

test('四类城防卡片支持点击和键盘查看详情，零库存工事仍可打开', () => {
  const G = setup();
  const html = G.MainView.renderFortSummaryList();
  const cards = [...html.matchAll(/<div class="fort-summary-item[^"]*"[^>]*>/g)].map(match => match[0]);
  const calls = [];
  G.MainView.showUnitDetailModal = (id, event) => calls.push({ id, event });

  for (const [index, id] of ['bunker', 'howitzer', 'antitank', 'flak'].entries()) {
    const card = cards[index];
    assert.match(card, /role="button" tabindex="0"/);
    assert.ok(card.includes(`aria-label="查看${G.DATA.forts[id].name}详情"`));
    const click = new Function('Game', 'event', card.match(/onclick="([^"]+)"/)[1]);
    const keydown = new Function('Game', 'event', card.match(/onkeydown="([^"]+)"/)[1]);
    const event = { preventDefault() { this.prevented = true; } };
    click(G, event);
    assert.deepEqual(calls.pop(), { id, event });
    for (const key of ['Enter', ' ']) {
      event.key = key;
      event.prevented = false;
      keydown(G, event);
      assert.deepEqual(calls.pop(), { id, event });
      assert.equal(event.prevented, true);
    }
    keydown(G, { key: 'ArrowRight' });
    assert.equal(calls.length, 0);
  }
  assert.match(cards[2], /is-empty/);
});

test('城防详情展示历史、定位、当前城市库存和八项基础属性，不展示行军消耗', () => {
  const harness = createModalDocument();
  const G = setup(harness.document);
  G.Core.state.army = { bunker: 99999, howitzer: 99999, antitank: 99999, flak: 99999 };
  G.Core.state.tech = { wall_reinforcement: 10, attack: 10, defense: 10 };
  const originalState = JSON.stringify(G.Core.state);

  for (const [id, fort] of Object.entries(G.DATA.forts)) {
    const event = { stopPropagation() { this.stopped = true; } };
    G.MainView.showUnitDetailModal(id, event);
    const modal = harness.body.children[0];
    assert.equal(event.stopped, true);
    assert.match(modal.innerHTML, /城防详情/);
    assert.ok(modal.innerHTML.includes(`<h2>${G.escapeHtml(fort.name)}</h2>`));
    assert.ok(modal.innerHTML.includes(`src="img/units/models/${id}.webp"`));
    assert.ok(modal.innerHTML.includes(`当前部署：<b>${G.fmt(G.Core.state.forts[id])}</b>`));
    assert.ok(fort.history, `${id} 必须配置历史介绍`);
    assert.ok(G.DATA.combatRoles[id], `${id} 必须配置战斗定位`);
    assert.ok(modal.innerHTML.includes(`<h3>历史信息</h3><p>${G.escapeHtml(fort.history)}</p>`));
    assert.ok(modal.innerHTML.includes(`<h3>战斗定位</h3><p>${G.escapeHtml(G.DATA.combatRoles[id])}</p>`));
    assert.match(modal.innerHTML, /属性信息/);
    for (const [label, value] of [
      ['对地攻击', fort.atkGround], ['对空攻击', fort.atkAir],
      ['对海攻击', fort.atkSea], ['对工事攻击', fort.atkFort],
      ['防御', fort.def], ['生命', fort.hp], ['移速', 0], ['射程', fort.range]
    ]) {
      assert.ok(modal.innerHTML.includes(`<span>${label}</span><b>${value}</b>`), `${id} 的${label}应为基础值`);
    }
    assert.equal((modal.innerHTML.match(/class="unit-detail-stat"/g) || []).length, 8);
    assert.match(modal.innerHTML, /固定城防工事，不进行战场机动或大地图行军/);
    assert.doesNotMatch(modal.innerHTML, /当前兵力|常驻耗粮|行军油耗|行军粮耗|人口占用|秒[/]格|undefined|NaN/);
    assert.equal(harness.document.activeElement, modal.closeButton);
    harness.keydown('Escape');
    assert.equal(harness.body.children.length, 0);
    assert.equal(harness.listenerCount(), 0);
    assert.equal(harness.document.activeElement, harness.previousFocus);
  }
  assert.equal(JSON.stringify(G.Core.state), originalState);
});

test('城防详情支持关闭按钮及遮罩关闭，正文点击不会关闭且清理键盘监听', () => {
  const harness = createModalDocument();
  const G = setup(harness.document);

  G.MainView.showUnitDetailModal('bunker');
  let modal = harness.body.children[0];
  modal.click({ target: modal.closeButton });
  harness.keydown('Enter');
  assert.equal(harness.body.children.length, 1);
  modal.closeButton.onclick();
  assert.equal(harness.body.children.length, 0);
  assert.equal(harness.listenerCount(), 0);
  assert.equal(harness.document.activeElement, harness.previousFocus);

  G.MainView.showUnitDetailModal('flak');
  modal = harness.body.children[0];
  modal.click({ target: modal });
  assert.equal(harness.body.children.length, 0);
  assert.equal(harness.listenerCount(), 0);
  assert.equal(harness.document.activeElement, harness.previousFocus);
});

test('全屏城防总览内打开详情，Escape 逐层关闭并恢复焦点', () => {
  const harness = createModalDocument();
  const G = setup(harness.document);

  G.MainView.showFortSummaryFullscreen();
  const summary = harness.body.children[0];
  assert.equal((summary.innerHTML.match(/onclick="Game.MainView.showUnitDetailModal/g) || []).length, 4);
  G.MainView.showUnitDetailModal('howitzer');
  assert.equal(harness.body.children.length, 2);
  assert.equal(harness.listenerCount(), 2);
  harness.keydown('Escape');
  assert.deepEqual(harness.body.children, [summary]);
  assert.equal(harness.listenerCount(), 1);
  assert.equal(harness.document.activeElement, summary.closeButton);
  harness.keydown('Escape');
  assert.equal(harness.body.children.length, 0);
  assert.equal(harness.listenerCount(), 0);
  assert.equal(harness.document.activeElement, harness.previousFocus);
});

test('城防详情转义介绍文本，无效类型不创建弹窗', () => {
  const harness = createModalDocument();
  const G = setup(harness.document);
  G.MainView.showUnitDetailModal('unknown_fort');
  assert.equal(harness.body.children.length, 0);
  assert.equal(harness.listenerCount(), 0);

  G.DATA.forts.bunker.history = '<script>history</script>';
  G.DATA.combatRoles.bunker = '<b>role</b>';
  G.MainView.showUnitDetailModal('bunker');
  const html = harness.body.children[0].innerHTML;
  assert.match(html, /&lt;script&gt;history&lt;[/]script&gt;/);
  assert.match(html, /&lt;b&gt;role&lt;[/]b&gt;/);
  assert.doesNotMatch(html, /<script>|<b>role<[/]b>/);
  harness.keydown('Escape');
});
