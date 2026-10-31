const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup(storage = new Map()) {
  let now = 1000000;
  let nextTimer = 0;
  const intervals = new Map();
  const elements = new Map();
  const messages = [];
  function element() {
    return {
      hidden: false, innerHTML: '', textContent: '', controls: {},
      classList: { add() {}, remove() {} },
      setAttribute() {}, focus() {},
      querySelector(selector) {
        return this.controls[selector] || (this.controls[selector] = element());
      },
      remove() { if (this.parentNode) this.parentNode.removeChild(this); }
    };
  }
  const body = {
    children: [],
    appendChild(node) {
      node.parentNode = this;
      this.children.push(node);
      if (node.id) elements.set(node.id, node);
      for (const match of (node.innerHTML || '').matchAll(/<[^>]+id="([^"]+)"[^>]*>/g)) {
        const child = element();
        child.hidden = /\bhidden\b/.test(match[0]);
        child.owner = node;
        elements.set(match[1], child);
      }
    },
    removeChild(node) {
      node.parentNode = null;
      this.children.splice(this.children.indexOf(node), 1);
      for (const [id, child] of elements) if (child === node || child.owner === node) elements.delete(id);
    }
  };
  const document = {
    body, activeElement: null,
    getElementById: id => elements.get(id) || null,
    createElement: element, querySelector: () => null, querySelectorAll: () => []
  };
  const context = vm.createContext({
    console, document,
    Date: class extends Date { static now() { return now; } },
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) },
    requestAnimationFrame: callback => callback(),
    setInterval(callback, delay) {
      assert.equal(delay, 1000);
      intervals.set(++nextTimer, callback);
      return nextTimer;
    },
    clearInterval: id => intervals.delete(id),
    setTimeout: callback => { callback(); return 1; }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js', 'world.js', 'main-view.js', 'player-profile.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  }
  const G = context.Game;
  G.Main = G.PlayerProfile;
  G.WS = { statusHtml: () => '已连接' };
  G.toast = message => messages.push(message);
  G.Core.route = 'home';
  G.Core.state = {
    player: { id: 1, username: 'player1', militaryRank: 1, cityName: '主城' },
    cityState: { newbieProtectionUntil: now + 167 * 3600000 },
    world: { cityPos: { x: 1, y: 2 }, marches: [], incoming: [] },
    buildings: {}, resources: {}, tech: {}, army: {}, forts: {}, officers: [], tax: 30
  };
  for (const method of ['produceOf', 'foodPerHour', 'civilianPopulation', 'populationGrowthPerHour', 'popFree', 'resentment']) G.Core[method] = () => 0;
  G.Core.armyCap = () => 25000;
  G.Core.morale = () => 70;
  G.Core.populationCapacity = () => 100;
  G.Core.capacity = () => ({});
  const view = element();
  view.querySelector = () => null;
  elements.set('view', view);
  function renderHome() {
    G.Core.views.home(view);
    const notice = element();
    notice.hidden = /id="newbieProtectionNotice"[^>]*\bhidden\b/.test(view.innerHTML);
    elements.set('newbieProtectionNotice', notice);
    elements.set('newbieProtectionNoticeText', element());
    return view.innerHTML;
  }
  return {
    G, context, elements, storage, intervals, body, messages, renderHome,
    setTime: value => { now = value; },
    now: () => now,
    tick: () => [...intervals.values()].forEach(callback => callback())
  };
}

test('新手保护精确到秒，省略前导零单位且到期即失效', () => {
  const f = setup();
  for (const [remaining, expected] of [
    [167 * 3600000, '6天23小时0分钟0秒'], [48 * 3600000, '2天0小时0分钟0秒'],
    [2 * 86400000 + 3 * 3600000 + 4 * 60000, '2天3小时4分钟0秒'],
    [24 * 3600000 + 1, '1天0小时0分钟1秒'], [24 * 3600000, '1天0小时0分钟0秒'],
    [24 * 3600000 - 1, '1天0小时0分钟0秒'], [23 * 3600000, '23小时0分钟0秒'],
    [24 * 3600000 - 60000, '23小时59分钟0秒'],
    [3600001, '1小时0分钟1秒'], [3600000 - 1, '1小时0分钟0秒'],
    [59 * 60000, '59分钟0秒'], [60001, '1分钟1秒'], [60000, '1分钟0秒'], [1000, '1秒']
  ]) {
    f.G.Core.state.cityState.newbieProtectionUntil = f.now() + remaining;
    assert.equal(f.G.Core.newbieProtectionInfo().countdown, expected);
    assert.ok(f.G.Core.cityStatusText().detail.startsWith('还剩' + expected + '；'));
  }
  f.G.Core.state.cityState.newbieProtectionUntil = f.now();
  assert.equal(f.G.Core.newbieProtectionInfo().active, false);
  assert.equal(f.G.Core.newbieProtectionInfo().countdown, '0秒');
});

test('首页关闭需二次确认，取消不隐藏，确认后刷新、切城、重载均保持隐藏且不退出保护', () => {
  const f = setup();
  assert.match(f.renderHome(), /aria-label="永久关闭首页新手保护提示"/);
  const until = f.G.Core.state.cityState.newbieProtectionUntil;
  f.G.MainView.dismissNewbieProtectionNotice();
  let dialog = f.body.children.at(-1);
  assert.match(dialog.innerHTML, /永久关闭首页提示/);
  assert.match(dialog.innerHTML, /点击头像.*指挥官档案.*倒计时/);
  assert.match(dialog.innerHTML, /新手保护仍然生效/);
  dialog.querySelector('#confirmCancelBtn').onclick();
  assert.equal(f.storage.size, 0);
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, false);

  f.G.MainView.dismissNewbieProtectionNotice();
  dialog = f.body.children.at(-1);
  dialog.querySelector('#confirmOkBtn').onclick();
  assert.equal(f.storage.get('wargame_newbie_notice_hidden_1'), '1');
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
  assert.equal(f.G.Core.state.cityState.newbieProtectionUntil, until);
  assert.equal(f.G.Core.state.player.newbieProtectionEnded, undefined);
  f.G.MainView.silentUpdateHome({});
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
  f.G.Core.state.player.cityName = '分城';
  f.renderHome();
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
  const reloaded = setup(f.storage);
  reloaded.renderHome();
  assert.equal(reloaded.elements.get('newbieProtectionNotice').hidden, true);
  reloaded.G.Core.state.player.id = 2;
  reloaded.renderHome();
  assert.equal(reloaded.elements.get('newbieProtectionNotice').hidden, false);
});

test('确认期间切换账号不修改新账号偏好，存储不可用时仍关闭本次提示并明确说明', () => {
  const f = setup();
  f.renderHome();
  f.G.MainView.dismissNewbieProtectionNotice();
  const dialog = f.body.children.at(-1);
  f.G.Core.state.player.id = 2;
  dialog.querySelector('#confirmOkBtn').onclick();
  assert.equal(f.storage.size, 0);
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, false);
  f.context.localStorage.setItem = () => { throw Error('storage blocked'); };
  f.G.MainView.dismissNewbieProtectionNotice();
  f.body.children.at(-1).querySelector('#confirmOkBtn').onclick();
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
  assert.match(f.messages.at(-1), /无法保存设置/);
  f.G.MainView.silentUpdateHome({});
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
});

test('首页局部更新保留关闭按钮，保护到期或服务端明确归零时隐藏提示', () => {
  const f = setup();
  f.renderHome();
  const notice = f.elements.get('newbieProtectionNotice');
  const closeMarkup = '<button aria-label="关闭">✕</button>';
  notice.innerHTML = closeMarkup;
  f.G.MainView.silentUpdateHome({});
  assert.equal(notice.innerHTML, closeMarkup);
  assert.match(f.elements.get('newbieProtectionNoticeText').textContent, /还剩6天23小时0分钟0秒/);
  f.setTime(f.now() + 60000);
  f.G.MainView.silentUpdateHome({});
  assert.match(f.elements.get('newbieProtectionNoticeText').textContent, /还剩6天22小时59分钟0秒/);
  f.G.Core.state.player.newbieProtectionUntil = f.now() + 7 * 86400000;
  f.G.Core.state.cityState.newbieProtectionUntil = 0;
  f.G.MainView.silentUpdateHome({});
  assert.equal(notice.hidden, true);
  f.G.Core.state.cityState.newbieProtectionUntil = f.now() + 1000;
  f.G.MainView.silentUpdateHome({});
  assert.equal(notice.hidden, false);
  f.setTime(f.now() + 1000);
  f.G.MainView.silentUpdateHome({});
  assert.equal(notice.hidden, true);
});

test('首页隐藏后档案仍展示倒计时和规则，逐秒变化及服务端延期每秒校准', () => {
  const f = setup(new Map([['wargame_newbie_notice_hidden_1', '1']]));
  f.renderHome();
  assert.equal(f.elements.get('newbieProtectionNotice').hidden, true);
  f.G.Core.state.cityState.newbieProtectionUntil = f.now() + 86400000;
  f.G.Main.openPlayerDrawer();
  const section = f.elements.get('drawerNewbieProtection');
  const countdown = f.elements.get('drawerNewbieProtectionCountdown');
  assert.equal(section.hidden, false);
  assert.equal(countdown.textContent, '还剩1天0小时0分钟0秒');
  assert.match(f.elements.get('playerDrawerMask').innerHTML, /首次进入保护7天/);
  assert.equal(f.intervals.size, 1);
  f.setTime(f.now() + 1000);
  f.tick();
  assert.equal(countdown.textContent, '还剩23小时59分钟59秒');
  f.G.Core.state.cityState.newbieProtectionUntil = f.now() + 7 * 86400000;
  f.tick();
  assert.equal(countdown.textContent, '还剩7天0小时0分钟0秒');
  f.G.Core.state.cityState.newbieProtectionUntil = 0;
  f.tick();
  assert.equal(section.hidden, true);
  f.G.Main.closePlayerDrawer();
  assert.equal(f.intervals.size, 0);
});

test('档案重开不叠加计时器，退出账号和移除抽屉后清理', () => {
  const f = setup();
  f.G.Main.openPlayerDrawer();
  f.G.Main.openPlayerDrawer();
  assert.equal(f.intervals.size, 1);
  f.G.Core.state.player.id = 2;
  f.tick();
  assert.equal(f.intervals.size, 0);
  f.G.Main.openPlayerDrawer();
  f.elements.get('playerDrawerMask').remove();
  f.tick();
  assert.equal(f.intervals.size, 0);
  f.G.Main.openPlayerDrawer();
  f.G.Core.state = null;
  f.tick();
  assert.equal(f.intervals.size, 0);
});
