const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const nodes = {};
  for (const key of ['[data-close]', '[data-shop]', '[data-coordinates]', '[data-preview]', '[data-status]', '[data-confirm]', '[data-readiness]', '[data-recheck]']) {
    nodes[key] = { style: {}, disabled: false, focus() {}, querySelectorAll() { return []; } };
  }
  const form = { elements: {}, handlers: {}, addEventListener(name, handler) { this.handlers[name] = handler; } };
  for (const name of ['itemId', 'battlefield', 'x', 'y']) form.elements[name] = { value: '', disabled: false };
  nodes.form = form;
  const document = {
    activeElement: { isConnected: true, focus() {} },
    body: { appendChild(mask) { this.mask = mask; } },
    createElement() { return {
      innerHTML: '', addEventListener() {},
      querySelector(selector) { return nodes[selector]; },
      remove() { document.body.mask = null; }
    }; },
    getElementById() { return this.body.mask; }, addEventListener() {}, removeEventListener() {}
  };
  const requests = [], events = [];
  const G = {
    escapeHtml: String, Core: { state: { player: { id: 1, activeCityId: 11 }, items: { teleportNormal: 2, teleportAdvanced: 3 },
      cityOverview: { cities: [{ id: 11, name: '东南城', x: 700, y: 600 }] } } },
    API: { getToken: () => 'token', client: {
      async get(url) { if (url.endsWith('/readiness')) return { ready: true, checks: [] }; requests.push({ url }); return { valid: true }; },
      async post(url, body, options) { requests.push({ url, body, options }); return { success: true, x: 60, y: 70, state: G.Core.state, message: '传送成功' }; },
      invalidateCityRequests() { events.push('invalidate'); }
    }, applyState(state) { G.Core.state = state; events.push('state'); } },
    World: { _dispatchTarget: {} }, WorldView: { invalidate() {} },
    WorldMap: { invalidate() {}, focusCoordinate(x, y) { events.push([x, y]); }, setMode() {} },
    go(route) { events.push(route); }, toast(message) { events.push(message); }
  };
  const context = vm.createContext({ window: { Game: G }, document, console });
  for (const name of ['constants', 'data', 'city-teleport']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + name + '.js'), 'utf8'), context);
  }
  return { G, form, nodes, document, requests, events };
}
async function settle() { await new Promise(resolve => setImmediate(resolve)); }
function submit(form) { form.onsubmit({ preventDefault() {} }); }

test('normal teleport sends selected battlefield without coordinates and consumes only after submit', async () => {
  const { G, form, nodes, requests, document, events } = setup();
  G.CityTeleport.open('teleportNormal'); await settle();
  assert.equal(form.elements.battlefield.value, 'southeast');
  form.elements.battlefield.value = 'northeast'; form.elements.battlefield.onchange();
  assert.equal(nodes['[data-coordinates]'].hidden, true);
  assert.equal(requests.length, 0);
  assert.equal(nodes['[data-confirm]'].disabled, false);
  submit(form);
  assert.equal(G.CityTeleport.isBusy(), true);
  G.CityTeleport.close(); assert.ok(document.body.mask);
  await settle();
  assert.deepEqual(JSON.parse(JSON.stringify(requests)), [{ url: '/game/cities/teleport',
    body: { itemId: 'teleportNormal', battlefield: 'northeast' }, options: { retry: 0 } }]);
  assert.equal(document.body.mask, null);
  assert.equal(G.World._dispatchTarget, null);
  assert.deepEqual(events, ['invalidate', 'state', [60, 70], 'world', '传送成功']);
});

test('advanced teleport validates all four battlefield ranges and rejects midline footprints', async () => {
  for (const [field, minX, minY] of [['northwest', 0, 0], ['northeast', 400, 0], ['southwest', 0, 400], ['southeast', 400, 400]]) {
    const { G, form, nodes, requests } = setup();
    G.CityTeleport.open('teleportAdvanced');
    form.elements.battlefield.value = field; form.elements.battlefield.onchange();
    assert.equal(form.elements.x.min, minX); assert.equal(form.elements.x.max, minX + 398);
    assert.equal(form.elements.y.min, minY); assert.equal(form.elements.y.max, minY + 398);
    form.elements.x.value = String(minX + 399); form.elements.y.value = String(minY + 60);
    nodes['[data-preview]'].onclick(); await settle();
    assert.equal(requests.length, 0); assert.equal(nodes['[data-confirm]'].disabled, true);
    form.elements.x.value = String(minX + 50);
    nodes['[data-preview]'].onclick(); await settle();
    assert.equal(requests[0].url, '/game/cities/teleport/site?battlefield=' + field + '&x=' + (minX + 50) + '&y=' + (minY + 60));
    assert.equal(nodes['[data-confirm]'].disabled, false);
    submit(form); await settle();
    assert.deepEqual(JSON.parse(JSON.stringify(requests[1].body)), { itemId: 'teleportAdvanced', battlefield: field, x: minX + 50, y: minY + 60 });
  }
});

test('changing battlefield invalidates a pending preview and requires checking the new destination', async () => {
  const { G, form, nodes, requests } = setup(); let finish;
  G.API.client.get = url => url.endsWith('/readiness') ? Promise.resolve({ ready: true, checks: [] }) : new Promise(resolve => { finish = resolve; });
  G.CityTeleport.open('teleportAdvanced', 700, 600);
  form.elements.battlefield.value = 'northwest'; form.elements.battlefield.onchange();
  finish({ valid: true }); await settle();
  assert.equal(nodes['[data-confirm]'].disabled, true);
  submit(form); assert.equal(requests.length, 0);
  form.elements.x.value = '20.5'; form.elements.y.value = '30';
  nodes['[data-preview]'].onclick();
  assert.match(nodes['[data-status]'].textContent, /整数坐标/);
});

test('failed teleport shows server reason and advanced mode needs a fresh preview', async () => {
  const { G, form, nodes, document } = setup();
  G.API.client.post = async () => { throw new Error('该范围内有野地，请选择空地'); };
  G.CityTeleport.open('teleportAdvanced', 700, 600); await settle();
  submit(form); await settle();
  assert.equal(G.CityTeleport.isBusy(), false); assert.ok(document.body.mask);
  assert.equal(nodes['[data-confirm]'].disabled, true);
  assert.equal(form.elements.x.disabled, false);
  assert.match(nodes['[data-status]'].textContent, /该范围内有野地/);
});

test('insufficient inventory disables teleport without sending a request', () => {
  const { G, form, nodes, requests } = setup();
  G.Core.state.items.teleportNormal = 0;
  G.CityTeleport.open('teleportNormal');
  assert.equal(nodes['[data-confirm]'].disabled, true);
  submit(form); assert.equal(requests.length, 0);
  assert.match(nodes['[data-status]'].textContent, /数量不足/);
});

test('readiness checklist lists simultaneous blockers and enables normal teleport only after a successful recheck', async () => {
  const { G, nodes, requests, form } = setup();
  let ready = false;
  G.API.client.get = async url => {
    assert.equal(url, '/game/cities/teleport/readiness');
    return { ready, requiresCoast: true, checks: [
      { key: 'departures', passed: ready, message: '本城仍有外派部队', route: 'alerts' },
      { key: 'battle', passed: ready, message: '城市正在交战', route: 'alerts' }
    ] };
  };
  G.CityTeleport.open('teleportNormal');
  submit(form); assert.equal(requests.length, 0);
  await settle();
  assert.match(nodes['[data-readiness]'].innerHTML, /仍有外派部队.*城市正在交战.*沿海或海岛/);
  assert.equal(nodes['[data-confirm]'].disabled, true);
  ready = true; nodes['[data-recheck]'].onclick();
  assert.equal(nodes['[data-confirm]'].disabled, true);
  await settle();
  assert.equal(nodes['[data-confirm]'].disabled, false);
  assert.match(nodes['[data-readiness]'].innerHTML, /已通过：没有本城外派部队/);
  submit(form); await settle();
  assert.equal(requests.length, 1);
});

test('failed readiness query blocks both teleport types without consuming a tool', async () => {
  const { G, nodes, requests, form } = setup();
  G.API.client.get = async () => { throw new Error('查询中断'); };
  G.CityTeleport.open('teleportNormal'); await settle();
  assert.equal(nodes['[data-confirm]'].disabled, true);
  assert.match(nodes['[data-readiness]'].textContent, /查询中断/);
  submit(form); assert.equal(requests.length, 0);
});
