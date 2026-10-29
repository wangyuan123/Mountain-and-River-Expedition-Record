const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(level = 1) {
  const nodes = {};
  const calls = [];
  const mask = {
    innerHTML: '',
    addEventListener() {},
    querySelector(selector) {
      if (!nodes[selector]) {
        nodes[selector] = {
          style: { setProperty() {} },
          innerHTML: '',
          querySelectorAll() { return []; }
        };
      }
      return nodes[selector];
    }
  };
  const context = vm.createContext({
    window: null,
    document: {
      body: { appendChild() {} },
      createElement() { return mask; }
    },
    Game: {
      Core: {
        state: {
          officers: [{ id: 1, name: '测试军官', level, exp: 0, star: 1 }],
          items: { expBookMax: 2, expBookAdv: 3 }
        },
        views: {},
        render() {}
      },
      API: {
        useExpBook(...args) {
          calls.push(args);
          return Promise.resolve({ success: true });
        }
      },
      escapeHtml(value) { return String(value); },
      toast() {}
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'save.js', 'shop.js', 'officer.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game: context.Game, nodes, calls };
}

test('shop and depot descriptions state direct max-level and 100000 experience per advanced book', () => {
  const { game } = setup();
  for (const catalog of [game.Shop.items(), Object.entries(game.DATA.items).map(([id, item]) => ({ id, ...item }))]) {
    assert.match(catalog.find(item => item.id === 'expBookMax').desc, /使用1本.*1～99级.*满级\(Lv\.100\)/);
    assert.match(catalog.find(item => item.id === 'expBookAdv').desc, /每本提供100000经验/);
  }
});

for (const level of [1, 50, 99]) {
  test(`max-level book previews the correct attribute points at level ${level} and submits one book`, () => {
    const { game, nodes, calls } = setup(level);
    game.Officer.openExpBookModal(1, 'expBookMax');
    assert.match(nodes['#expBookSummary'].innerHTML, new RegExp(`Lv\\.${level} → Lv\\.100`));
    assert.ok(nodes['#expBookSummary'].innerHTML.includes(`+${100 - level} 点可分配属性`));
    assert.equal(nodes['#expBookSliderRow'].style.display, 'none');
    assert.equal(nodes['#expBookConfirmBtn'].textContent, '确认使用 (1本)');
    nodes['#expBookBtnMax'].onclick();
    nodes['#expBookConfirmBtn'].onclick();
    assert.deepEqual(calls, [[1, 'expBookMax', 1]]);
  });
}

test('advanced book preview and submission use 100000 experience per book', () => {
  const { game, nodes, calls } = setup();
  game.Officer.openExpBookModal(1, 'expBookAdv');
  assert.match(nodes['#expBookCards'].innerHTML, /\+100000 经验\/本/);
  assert.match(nodes['#expBookSummary'].innerHTML, /\+100000 经验/);
  nodes['#expBookQtyInput'].value = '3';
  nodes['#expBookQtyInput'].oninput();
  assert.match(nodes['#expBookSummary'].innerHTML, /\+300000 经验/);
  nodes['#expBookConfirmBtn'].onclick();
  assert.deepEqual(calls, [[1, 'expBookAdv', 3]]);
});

test('four book prices follow the whole-book max-level discount formula', () => {
  const { game } = setup();
  const prices = { expBook: 10, expBookAdv: 100, expBookRapid: 500, expBookMax: 16400 };
  for (const [id, price] of Object.entries(prices)) {
    const product = game.Shop.items().find(item => item.id === id);
    assert.equal(product.price, price);
    assert.equal(product.name, game.DATA.items[id].name);
    assert.equal(product.desc, game.DATA.items[id].desc);
  }
  assert.match(game.DATA.items.expBookRapid.desc, /每本提供500000经验/);
  let experience = 0;
  for (let level = 1; level < game.OFFICER_MAX_LEVEL; level++) experience += game.expNeeded(level);
  assert.equal(experience, 20355750);
  assert.equal(Math.ceil(experience / 500000) * prices.expBookRapid * 0.8, prices.expBookMax);
});

test('rapid book previews and submits 500000 experience per book in batches', () => {
  const { game, nodes, calls } = setup();
  game.Core.state.items.expBookRapid = 3;
  game.Officer.openExpBookModal(1, 'expBookRapid');
  assert.match(nodes['#expBookCards'].innerHTML, /极速经验书/);
  assert.match(nodes['#expBookCards'].innerHTML, /\+500000 经验\/本/);
  assert.match(nodes['#expBookSummary'].innerHTML, /\+500000 经验/);
  nodes['#expBookQtyInput'].value = '3';
  nodes['#expBookQtyInput'].oninput();
  assert.match(nodes['#expBookSummary'].innerHTML, /\+1500000 经验/);
  nodes['#expBookConfirmBtn'].onclick();
  assert.deepEqual(calls, [[1, 'expBookRapid', 3]]);
});

test('rapid-only inventory opens the book dialog and is selected automatically', () => {
  const { game, nodes, calls } = setup();
  game.Core.state.items = { expBookRapid: 1 };
  game.Officer.openExpBookModal(1);
  assert.match(nodes['#expBookSummary'].innerHTML, /\+500000 经验/);
  nodes['#expBookConfirmBtn'].onclick();
  assert.deepEqual(calls, [[1, 'expBookRapid', 1]]);
});
