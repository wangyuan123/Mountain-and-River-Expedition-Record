const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ids = ['officers', 'army', 'resources', 'chat'];

function setup(storage = new Map(), username = 'alice', serverOrder = null, save = null) {
  const listeners = {};
  const requests = [];
  let hit = null;
  const sections = ids.map(id => ({
    id,
    getAttribute(name) { return name === 'data-home-module' ? id : null; },
    hasAttribute(name) { return name === 'data-home-module'; },
    closest(selector) { return selector === '[data-home-module]' ? this : null; },
    getBoundingClientRect() { return { top: ids.indexOf(id) * 100, height: 100 }; },
    classList: { add() {}, remove() {} }
  }));
  const status = { textContent: '' };
  const root = {
    nodes: [...sections, status],
    events: {},
    addEventListener(type, handler) { this.events[type] = handler; },
    querySelectorAll() { return this.nodes.filter(node => node.id); },
    querySelector(selector) {
      if (selector === '.home-module-status') return status;
      const match = selector.match(/^\[data-home-module="(\w+)"\]$/);
      return match ? sections.find(node => node.id === match[1]) : null;
    },
    contains(node) { return this.nodes.includes(node); },
    insertBefore(node, before) {
      this.nodes.splice(this.nodes.indexOf(node), 1);
      const index = before == null ? this.nodes.length : this.nodes.indexOf(before);
      this.nodes.splice(index, 0, node);
    }
  };
  for (const section of sections) {
    Object.defineProperties(section, {
      previousElementSibling: { get() { return root.nodes[root.nodes.indexOf(section) - 1] || null; } },
      nextElementSibling: { get() { return root.nodes[root.nodes.indexOf(section) + 1] || null; } },
      nextSibling: { get() { return root.nodes[root.nodes.indexOf(section) + 1] || null; } }
    });
  }
  const handles = Object.fromEntries(sections.map(section => [section.id, {
    closest(selector) { return selector === '.home-module-handle' ? this : section; },
    focus() {}
  }]));
  const view = { innerHTML: '', querySelector() { return root; } };
  const state = {
    player: { id: username, cityName: '测试城', homeModuleOrder: serverOrder }, resources: {}, officers: [], army: {}, tax: 10,
    world: { cityPos: { x: 0, y: 0 }, marches: [], incoming: [] }
  };
  const core = {
    state, views: {}, cityStatusText: () => ({ status: 'peace', text: '和平' }),
    getOfficerByRole: () => null, armyCap: () => 100,
    produceOf: () => 0, foodPerHour: () => 0, civilianPopulation: () => 10,
    capacity: () => ({ food: 100, steel: 100, oil: 100, rare: 100 }),
    morale: () => 100, resentment: () => 0, populationCapacity: () => 100,
    populationGrowthPerHour: () => 0, popFree: () => 10
  };
  const context = vm.createContext({
    Math, JSON, document: {
      getElementById: () => null,
      elementFromPoint: () => hit,
      addEventListener(type, handler) { listeners[type] = handler; },
      removeEventListener(type) { delete listeners[type]; }
    },
    localStorage: {
      getItem: key => storage.get(key) || null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: key => storage.delete(key)
    },
    Game: {
      Core: core, API: {
        getUsername: () => username,
        setHomeModuleOrder(order) {
          requests.push([...order]);
          return save ? save(order) : Promise.resolve({ homeModuleOrder: order });
        }
      },
      DATA: { resources: Object.fromEntries(['food', 'steel', 'oil', 'rare', 'gold'].map(id => [id, { name: id, icon: 'x' }])) },
      Constants: { resourceKeysWithGold: ['food', 'steel', 'oil', 'rare', 'gold'] },
      fmt: String, escapeHtml: String
    }
  });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8'), context);
  const render = () => context.Game.Core.views.home(view);
  const order = () => root.querySelectorAll().map(section => section.id);
  return { render, order, root, handles, listeners, view, state, requests, setHit: node => { hit = node; } };
}

test('服务端顺序优先于旧本地顺序，且按玩家隔离', async () => {
  const storage = new Map([['wargame_home_modules_alice', JSON.stringify(['chat', 'chat', 'unknown', 'resources'])]]);
  const alice = setup(storage, 'alice', ['army', 'chat', 'officers', 'resources']);
  alice.render();
  assert.deepEqual([...alice.view.innerHTML.matchAll(/data-home-module="(\w+)"/g)].map(match => match[1]), ['army', 'chat', 'officers', 'resources']);
  assert.equal((alice.view.innerHTML.match(/class="home-module-handle"/g) || []).length, 4);
  await new Promise(setImmediate);
  assert.equal(alice.requests.length, 0);
  const bob = setup(storage, 'bob');
  bob.render();
  assert.deepEqual([...bob.view.innerHTML.matchAll(/data-home-module="(\w+)"/g)].map(match => match[1]), ids);
});

test('旧本地顺序仅在服务端未配置时迁移一次', async () => {
  const storage = new Map([['wargame_home_modules_alice', JSON.stringify(['chat', 'chat', 'unknown', 'resources'])]]);
  const page = setup(storage);
  page.render();
  assert.deepEqual([...page.view.innerHTML.matchAll(/data-home-module="(\w+)"/g)].map(match => match[1]), ['chat', 'resources', 'officers', 'army']);
  await new Promise(setImmediate);
  assert.deepEqual(page.requests, [['chat', 'resources', 'officers', 'army']]);
  assert.equal(storage.has('wargame_home_modules_alice'), false);
  page.render();
  assert.equal(page.requests.length, 1);
});

test('方向键和指针拖拽按操作顺序写入服务端', async () => {
  const storage = new Map();
  const page = setup(storage);
  page.render();
  let prevented = false;
  page.root.events.keydown({
    target: page.handles.resources, key: 'ArrowUp',
    preventDefault() { prevented = true; }
  });
  assert.equal(prevented, true);
  assert.deepEqual(page.order(), ['officers', 'resources', 'army', 'chat']);

  page.root.events.pointerdown({
    target: page.handles.officers, pointerType: 'touch', pointerId: 1,
    clientX: 20, clientY: 20, preventDefault() {}
  });
  page.setHit(page.root.querySelector('[data-home-module="chat"]'));
  page.listeners.pointermove({ pointerId: 1, clientX: 20, clientY: 350, preventDefault() {} });
  page.listeners.pointerup({ pointerId: 1, type: 'pointerup' });
  assert.deepEqual(page.order(), ['resources', 'army', 'chat', 'officers']);
  await new Promise(setImmediate);
  assert.deepEqual(page.requests, [
    ['officers', 'resources', 'army', 'chat'],
    ['resources', 'army', 'chat', 'officers']
  ]);
  assert.deepEqual(Array.from(page.state.player.homeModuleOrder), ['resources', 'army', 'chat', 'officers']);
  assert.equal(storage.size, 0);
});

test('连续调整等待上一次写入完成，最终保存最后一次顺序', async () => {
  const resolves = [];
  const page = setup(new Map(), 'alice', ids, () => new Promise(resolve => resolves.push(resolve)));
  page.render();
  const moveUp = id => page.root.events.keydown({
    target: page.handles[id], key: 'ArrowUp', preventDefault() {}
  });

  moveUp('resources');
  await new Promise(setImmediate);
  assert.deepEqual(page.requests, [['officers', 'resources', 'army', 'chat']]);
  moveUp('chat');
  await new Promise(setImmediate);
  assert.equal(page.requests.length, 1);

  resolves[0]({ homeModuleOrder: page.requests[0] });
  await new Promise(setImmediate);
  assert.deepEqual(page.requests[1], ['officers', 'resources', 'chat', 'army']);
  resolves[1]({ homeModuleOrder: page.requests[1] });
  await new Promise(setImmediate);
  assert.deepEqual(Array.from(page.state.player.homeModuleOrder), page.requests[1]);
});
