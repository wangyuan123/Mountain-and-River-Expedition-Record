const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
  const content = { innerHTML: '' }, title = {}, mask = { remove() {} }, calls = [];
  const inputs = [{ dataset: { unit: 'infantry' }, value: '20' }];
  const cargo = [{ dataset: { resource: 'food' }, value: '0' }];
  const button = { disabled: false };
  const form = { handlers: {}, elements: { action: { value: 'reinforce' }, commander: { value: '0' } },
    addEventListener(event, callback) { this.handlers[event] = callback; },
    querySelector() { return button; }, querySelectorAll(selector) { return selector === '[data-unit]' ? inputs : cargo; } };
  const state = { player: { id: 1, cityName: '主城' }, army: { infantry: 20, truck: 2 }, resources: { food: 100 }, cityOverview: { cities: [] } };
  const context = { console, setTimeout, clearTimeout, clearInterval,
    document: { getElementById(id) { return { cityDialogMask: mask, cityDialogContent: content, cityDialogTitle: title, cityTransferForm: form }[id]; }, removeEventListener() {} },
    Game: { Core: { state, render() {} }, escapeHtml: String, toast() {},
      DATA: { units: { infantry: { name: '步兵', load: 1 }, truck: { name: '卡车', load: 50 } } },
      API: { applyState() {}, client: {
        get(url) { calls.push(url); return Promise.resolve([{ id: 22, ownerId: 2, name: '成员分城', x: 20, y: 10, readyAt: 0, acceptGarrison: true }]); },
        post(url, body) { calls.push({ url, body }); return Promise.resolve({ state }); }
      } } }
  };
  context.window = context; vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/cities.js'), 'utf8'), context);
  context.Game.Cities.open = () => {};
  return { context, content, title, form, calls, inputs, cargo, button };
}

test('member city selector preserves reinforce action and submits the selected destination', async () => {
  const f = fixture(), cities = f.context.Game.Cities;
  cities.openSupport(2, 'reinforce'); await new Promise(setImmediate);
  assert.match(f.content.innerHTML, /成员分城/);
  assert.match(f.content.innerHTML, /transferForm\(22,'reinforce'\)/);
  cities.transferForm(22, 'reinforce');
  assert.match(f.content.innerHTML, /name="action" value="reinforce"/);
  assert.match(f.content.innerHTML, /确认驻防/);
  assert.match(f.content.innerHTML, /data-unit="infantry"/);
  f.form.handlers.submit({ preventDefault() {}, target: f.form }); await new Promise(setImmediate);
  assert.equal(f.calls[1].url, '/game/world/dispatch');
  assert.equal(f.calls[1].body.targetId, 22); assert.equal(f.calls[1].body.action, 'reinforce');
});

test('map shortcut selects the exact city and transport offers logistics only', async () => {
  const f = fixture();
  f.context.Game.Cities.openSupport(2, 'transport', 22); await new Promise(setImmediate);
  assert.match(f.content.innerHTML, /name="action" value="transport"/);
  assert.match(f.content.innerHTML, /data-unit="truck"/);
  assert.doesNotMatch(f.content.innerHTML, /data-unit="infantry"/);
});

test('closed or replaced support dialogs ignore delayed member city responses', async () => {
  const f = fixture(); let resolve;
  f.context.Game.API.client.get = () => new Promise(callback => { resolve = callback; });
  f.context.Game.Cities.openSupport(2, 'reinforce');
  f.context.Game.Cities.close();
  const before = f.content.innerHTML;
  resolve([{ id: 22, name: '迟到结果', readyAt: 0 }]); await new Promise(setImmediate);
  assert.equal(f.content.innerHTML, before);
});

test('reinforcement waits for protection confirmation and cancel preserves the form', async () => {
  const f = fixture(); let confirm;
  f.context.Game.World = { confirmPlayerWarfare(needed, send) { assert.equal(needed, true); confirm = send; } };
  f.context.Game.Cities.openSupport(2, 'reinforce', 22); await new Promise(setImmediate);
  const formBefore = f.content.innerHTML;
  f.form.handlers.submit({ preventDefault() {}, target: f.form });
  assert.equal(f.calls.filter(call => typeof call === 'object').length, 0);
  assert.equal(f.button.disabled, false);
  assert.equal(f.content.innerHTML, formBefore);
  confirm(true); await new Promise(setImmediate);
  const dispatch = f.calls.find(call => typeof call === 'object');
  assert.equal(dispatch.body.action, 'reinforce');
  assert.equal(dispatch.body.abandonNewbieProtection, true);
});

test('peaceful support transport does not request protection exit', async () => {
  const f = fixture();
  f.context.Game.World = { confirmPlayerWarfare(needed, send) { assert.equal(needed, false); send(false); } };
  f.form.elements.action.value = 'transport';
  f.inputs[0].dataset.unit = 'truck'; f.inputs[0].value = '1'; f.cargo[0].value = '10';
  f.context.Game.Cities.openSupport(2, 'transport', 22); await new Promise(setImmediate);
  f.form.handlers.submit({ preventDefault() {}, target: f.form }); await new Promise(setImmediate);
  const dispatch = f.calls.find(call => typeof call === 'object');
  assert.equal(dispatch.body.action, 'transport');
  assert.equal(dispatch.body.abandonNewbieProtection, undefined);
});
