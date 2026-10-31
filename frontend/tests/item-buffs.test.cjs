const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

// 轻量 DOM 替身只提供节点、属性和焦点；效果计算与异步行为全部执行真实模块。
function setup() {
  let mono = 0, game = 2000000000000, answer, reject = false, resolveLater;
  const timers = new Map(), listeners = new Map(), ws = new Map(), requests = [];
  let timerId = 0, nodes = [], writes = 0;
  const document = { hidden: false, activeElement: null,
    getElementById: () => drawer,
    addEventListener(name, fn) { listeners.set(name, fn); },
    removeEventListener(name) { listeners.delete(name); }
  };
  const root = {
    onclick: null, html: '',
    set innerHTML(html) {
      writes++;this.html = html;nodes = [];
      for (const match of html.matchAll(/<[^>]+\s(data-buff-[^>]+)>/g)) {
        const attrs = {};
        for (const attr of match[0].matchAll(/(data-buff-[\w-]+)="([^"]*)"/g)) attrs[attr[1]] = attr[2];
        nodes.push({ attrs, open: false, textContent: '',
          getAttribute(name) { return this.attrs[name] ?? null; },
          focus() { document.activeElement = this; }
        });
      }
    },
    get innerHTML() { return this.html; },
    querySelectorAll(selector) { return nodes.filter(n => n.attrs[selector.slice(1, -1)] != null); },
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  };
  const body = { scrollTop: 50 };
  const drawer = { querySelector(selector) { return selector === '#drawerItemBuffs' ? root : body; } };
  const G = { Core: { state: { player: { id: 1, activeCityId: 11 }, cityState: {}, cityOverview: {} } },
    gameNow: () => game,
    GameTime: { sync(time) { game = Math.max(game, time); }, toWallTime(until) { return until; } },
    escapeHtml: text => String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]),
    API: { getToken: () => 'token', client: { serverId: 'test', get(url) {
      requests.push(url);
      if (answer === 'pending') return new Promise(resolve => { resolveLater = resolve; });
      return reject ? Promise.reject(new Error('offline')) : Promise.resolve(answer);
    } } },
    WS: { on(name, fn) { ws.set(name, fn); }, off(name) { ws.delete(name); } },
    Main: { closePlayerDrawer() { G.ItemBuffs.unmount(); } }, go(route) { G.route = route; }
  };
  const window = { Game: G, performance: { now: () => mono },
    addEventListener(name, fn) { listeners.set(name, fn); },
    removeEventListener(name) { listeners.delete(name); }
  };
  const context = vm.createContext({ window, document, console,
    setInterval(fn) { timers.set(++timerId, fn);return timerId; }, clearInterval(id) { timers.delete(id); }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/item-buffs.js'), 'utf8'), context);
  function snapshot(effects = []) {
    return { playerId: 1, serverTime: game, cities: [
      { id: 11, name: '江宁城', x: 50, y: 60 }, { id: 12, name: '姑苏城', x: 70, y: 80 }
    ], effects };
  }
  answer = snapshot();
  return { G, root, document, drawer, timers, listeners, ws, requests, snapshot,
    node(attr, value) { return nodes.find(n => n.attrs[attr] === value); },
    setAnswer(value) { answer = value; }, fail() { reject = true; },
    resolve(value) { resolveLater(value); }, writes: () => writes,
    tick(elapsed = 1000, advanceGame = true) {
      mono += elapsed;if (advanceGame) game += elapsed;
      [...timers.values()].forEach(fn => fn());
    },
    click(action) { root.onclick({ target: { closest: () => ({ getAttribute: () => action }) } }); }
  };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
const until = 2000000000000;
function effect(id, kind, cityId, percent, duration) {
  return { id, kind, cityId, percent, until: until + duration, name: kind === 'shield' ? '免战护盾' : kind,
    icon: 'img/items/march-ord.svg', scope: cityId ? 'city' : 'account', status: 'active' };
}

test('lists account, current and other city buffs, counting covered tiers separately', async () => {
  const h = setup();
  h.setAnswer(h.snapshot([effect('high', 'mobilization', null, 50, 1000), Object.assign(effect('low', 'mobilization', null, 25, 90000000), {status:'covered'}),
    effect('shield', 'shield', 11, 0, 600000), effect('march', 'march', 12, 10, 3600000)]));
  h.G.ItemBuffs.mount(h.drawer);await settle();
  assert.match(h.root.innerHTML, /生效中 3 项 · 被覆盖 1 项/);
  assert.match(h.root.innerHTML, /当前城市 · 江宁城（50, 60）/);
  assert.match(h.root.innerHTML, /其他城市（1座）/);
  assert.match(h.root.innerHTML, /新发起行军速度 \+10%/);
  assert.equal(h.node('data-buff-status', 'low').textContent, '被覆盖');
  assert.equal(h.node('data-buff-status', 'shield').textContent, '即将结束');
  assert.equal(h.node('data-buff-remaining', 'low').textContent, '1天 01:00:00');
  assert.match(h.node('data-buff-end', 'low').textContent, /北京时间/);
});

test('highest tier expiry promotes lower tier immediately, even while offline', async () => {
  const h = setup();h.setAnswer(h.snapshot([effect('high','mobilization',null,50,1000), effect('low','mobilization',null,10,3000)]));
  h.G.ItemBuffs.mount(h.drawer);await settle();h.fail();h.ws.get('disconnected')();h.tick();
  assert.match(h.root.innerHTML, /生效中 1 项/);
  assert.equal(h.node('data-buff-status','low').textContent,'即将结束');
  assert.equal(h.node('data-buff-status','high'),undefined);
  h.tick(2000);assert.match(h.root.innerHTML,/暂无生效中的道具增益/);
});

test('second ticks update text in place and preserve fold, focus and scroll', async () => {
  const h = setup();h.setAnswer(h.snapshot([effect('shield','shield',12,0,3600000)]));
  h.G.ItemBuffs.mount(h.drawer);await settle();
  const fold=h.node('data-buff-fold','cities');fold.open=true;
  const button=h.node('data-buff-key','depot');button.focus();
  const writes=h.writes();h.tick();
  assert.equal(h.writes(),writes);assert.equal(fold.open,true);assert.equal(h.document.activeElement,button);
  assert.equal(h.node('data-buff-remaining','shield').textContent,'00:59:59');
  h.fail();h.ws.get('disconnected')();
  assert.equal(h.node('data-buff-fold','cities').open,true);
  assert.equal(h.document.activeElement,h.node('data-buff-key','depot'));
});

test('paused server samples freeze countdown and hide estimated wall deadline', async () => {
  const h = setup();h.setAnswer(h.snapshot([effect('shield','shield',11,0,3600000)]));
  h.G.ItemBuffs.mount(h.drawer);await settle();h.tick(6000,false);
  assert.equal(h.node('data-buff-remaining','shield').textContent,'01:00:00');
  assert.match(h.root.querySelector('[data-buff-clock]').textContent,/游戏计时已暂停/);
  assert.equal(h.node('data-buff-end','shield').textContent,'暂停期间暂无法确定结束时间');
  h.tick();assert.equal(h.root.querySelector('[data-buff-clock]').textContent,'');
});

test('loading errors differ from empty results, and refresh failures preserve known buffs', async () => {
  const h = setup();h.fail();h.G.ItemBuffs.mount(h.drawer);await settle();
  assert.match(h.root.innerHTML,/增益信息暂不可用/);assert.doesNotMatch(h.root.innerHTML,/暂无生效中的道具增益/);
  const cached=setup();cached.setAnswer(cached.snapshot([effect('shield','shield',11,0,3600000)]));
  cached.G.ItemBuffs.mount(cached.drawer);await settle();cached.fail();cached.click('retry');await settle();
  assert.match(cached.root.innerHTML,/状态待同步/);assert.ok(cached.node('data-buff-remaining','shield'));
});

test('polls at thirty seconds, and unmount clears timers, listeners and pending responses', async () => {
  const h=setup();h.G.ItemBuffs.mount(h.drawer);await settle();
  for(let n=0;n<29;n++)h.tick();assert.equal(h.requests.length,1);
  h.tick();await settle();assert.equal(h.requests.length,2);
  h.setAnswer('pending');h.click('retry');h.G.ItemBuffs.unmount();h.resolve(h.snapshot());await settle();
  assert.equal(h.timers.size,0);assert.equal(h.listeners.size,0);assert.equal(h.ws.size,0);assert.equal(h.root.innerHTML,'');
});

test('city switch discards old response and queues fresh account snapshot', async () => {
  const h=setup();h.setAnswer('pending');h.G.ItemBuffs.mount(h.drawer);
  h.G.Core.state.player.activeCityId=12;h.G.ItemBuffs.stateChanged();
  h.setAnswer(h.snapshot([effect('shield','shield',12,0,3600000)]));
  h.resolve(h.snapshot());await settle();await settle();
  assert.equal(h.requests.length,2);assert.match(h.root.innerHTML,/当前城市 · 姑苏城（70, 80）/);
});

test('account switch clears old effects immediately and ignores late responses', async () => {
  const h=setup();h.setAnswer('pending');h.G.ItemBuffs.mount(h.drawer);
  h.G.Core.state.player.id=2;h.G.ItemBuffs.stateChanged();h.resolve(h.snapshot());await settle();
  assert.equal(h.root.innerHTML,'');assert.equal(h.timers.size,0);
});

test('warehouse action closes the drawer and navigates without consuming or switching cities', async () => {
  const h=setup();h.G.ItemBuffs.mount(h.drawer);await settle();h.click('depot');
  assert.equal(h.G.route,'depot');assert.equal(h.G.Core.state.player.activeCityId,11);
  assert.deepEqual(h.requests,['/game/item-buffs']);assert.equal(h.timers.size,0);
});

test('escapes city names and refreshes on external buff changes, focus and reconnect', async () => {
  const h=setup();const data=h.snapshot();data.cities[0].name='<script>alert(1)</script>';
  h.setAnswer(data);h.G.ItemBuffs.mount(h.drawer);await settle();
  assert.doesNotMatch(h.root.innerHTML,/<script>/);assert.match(h.root.innerHTML,/&lt;script&gt;/);
  h.G.Core.state.cityState={shieldUntil:until+5000};h.G.ItemBuffs.stateChanged();await settle();
  assert.equal(h.requests.length,2);
  h.listeners.get('focus')();await settle();h.ws.get('connected')();await settle();assert.equal(h.requests.length,4);
});
