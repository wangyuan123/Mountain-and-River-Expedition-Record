const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function setupArmy() {
  const nodes = {};
  const modalNodes = {};
  const body = {
    appendChild(element) {
      element.parentNode = this;
      nodes[element.id] = element;
    },
    removeChild(element) {
      if (nodes[element.id] === element) delete nodes[element.id];
      element.parentNode = null;
    }
  };
  const G = {
    Core: {
      views: {},
      route: 'army',
      state: {
        army: { infantry: 100000, motor: 50000 },
        resources: { steel: 500000, oil: 500000, rare: 500000, food: 500000 },
        marches: [],
        woundedCount: 0
      },
      civilianPopulation: () => 99999,
      populationCapacity: () => 100000,
      popFree: () => 99999,
      populationGrowthPerHour: () => 1000,
      armyCap: () => 200000,
      foodPerHour: () => 9500000,
      getOfficerByRole: () => null,
      buildingLevels: () => [10, 10],
      trainMul: () => 1.5,
      render: () => {}
    },
    DATA: {
      units: {
        infantry: {
          name: '步兵-加兰德步枪兵（M1）',
          history: '美国｜装备M1加兰德半自动步枪的步兵，二战美军的代表性步兵装备。',
          cat: 'inf',
          atkGround: 6, atkAir: 1, atkSea: 1, atkFort: 2, def: 4, hp: 30, spd: 3, range: 100, food: 1, pop: 1,
          build: 'factory',
          cost: { steel: 20, oil: 0, rare: 0 }
        },
        motor: {
          name: '摩托兵-哈雷（WLA）',
          history: '美国｜哈雷WLA军用摩托。',
          cat: 'inf',
          atkGround: 12, atkAir: 1, atkSea: 1, atkFort: 6, def: 6, hp: 55, spd: 7, range: 140, food: 2, pop: 1,
          build: 'factory',
          cost: { steel: 40, oil: 10, rare: 0 }
        }
      },
      buildings: {
        factory: { name: '军工厂' }
      },
      combatRoles: {
        infantry: '廉价步兵；适合数量压制，惧怕摩托兵与装甲车',
        motor: '机动反步兵'
      },
      resources: {
        steel: { icon: '🔧' },
        oil: { icon: '🛢' },
        rare: { icon: '💠' }
      }
    },
    fmt: String,
    escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'),
    toast: () => {},
    API: {
      recruit: async () => ({ success: true }),
      cancelArmyQueue: async () => ({ success: true }),
      dismiss: async () => ({ success: true }),
      armyQueue: async () => ({ queue: [] }),
      getArmyQueue: async () => ({ queue: [] })
    }
  };

  const ctx = vm.createContext({
    Game: G,
    document: {
      activeElement: null,
      body,
      getElementById: id => nodes[id] || null,
      createElement: () => ({
        style: {},
        querySelector: selector => modalNodes[selector] || null
      })
    },
    setInterval: () => 1,
    clearInterval: () => {}
  });
  ctx.window = ctx;
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/army.js'), 'utf8'), ctx);

  return { G, nodes, modalNodes };
}

test('army cards hide prototype and production details by default, keeping header and recruit row visible', () => {
  const { G } = setupArmy();
  const container = { innerHTML: '' };
  G.Army.renderView(container);

  const html = container.innerHTML;

  // Header should be present
  assert.match(html, /unit-card-header/);
  assert.match(html, /unit-card-toggle/);
  assert.match(html, /详情 &#9662;/);

  // unit-card-details should be rendered with display:none by default
  assert.match(html, /class="unit-card-details"[^>]*style="display:none;"/);

  // Prototype history and production stats should be present in details area
  assert.match(html, /原型：美国｜装备M1加兰德半自动步枪的步兵/);
  assert.match(html, /生产: 基础 32秒\/个/);

  // Header should display [兵种名](编号) format
  assert.match(html, /<span class="n"[^>]*>步兵\(M1\)<\/span>/);
  assert.match(html, /<span class="n"[^>]*>摩托兵\(WLA\)<\/span>/);

  // Price and recruit row should be visible outside details
  assert.match(html, /<div class="cost">单价:/);
  assert.match(html, /class="recruit-row"/);
  assert.match(html, /class="btn recruit-btn"/);
  assert.match(html, />征召<\/button>/);
  assert.match(html, />解散<\/button>/);
  assert.doesNotMatch(html, /\[征召\]|\[解散\]/);
});

test('toggleUnitCard toggles expand state and updates toggle text and display style', () => {
  const { G, nodes } = setupArmy();

  assert.equal(G.Army.isUnitExpanded('infantry'), false);

  // Mock DOM elements for infantry card
  const detailsEl = { style: { display: 'none' } };
  const toggleEl = { innerHTML: '详情 &#9662;', classList: { add: () => {}, remove: () => {} } };
  const cardEl = {
    classList: { add: () => {}, remove: () => {} },
    querySelector: sel => {
      if (sel === '.unit-card-details') return detailsEl;
      if (sel === '.unit-card-toggle') return toggleEl;
      return null;
    }
  };
  nodes['unit-card-infantry'] = cardEl;

  // Toggle open
  G.Army.toggleUnitCard('infantry');
  assert.equal(G.Army.isUnitExpanded('infantry'), true);
  assert.equal(detailsEl.style.display, 'block');
  assert.equal(toggleEl.innerHTML, '收起 &#9652;');

  // Toggle close
  G.Army.toggleUnitCard('infantry');
  assert.equal(G.Army.isUnitExpanded('infantry'), false);
  assert.equal(detailsEl.style.display, 'none');
  assert.equal(toggleEl.innerHTML, '详情 &#9662;');
});

test('renderView preserves expanded state for already expanded unit cards', () => {
  const { G, nodes } = setupArmy();
  nodes['unit-card-infantry'] = {
    classList: { add: () => {}, remove: () => {} },
    querySelector: () => ({ style: {}, innerHTML: '', classList: { add: () => {}, remove: () => {} } })
  };

  G.Army.toggleUnitCard('infantry');
  assert.equal(G.Army.isUnitExpanded('infantry'), true);

  const container = { innerHTML: '' };
  G.Army.renderView(container);

  const html = container.innerHTML;

  // Infantry should be expanded
  assert.match(html, /id="unit-card-infantry"[^>]*class="[^"]*expanded[^"]*"/);
  assert.match(html, /id="unit-card-infantry"[\s\S]*?class="unit-card-details"[^>]*style="display:block;"/);
  assert.match(html, /id="unit-card-infantry"[\s\S]*?收起 &#9652;/);

  // Motor should remain collapsed
  assert.match(html, /id="unit-card-motor"[\s\S]*?class="unit-card-details"[^>]*style="display:none;"/);
  assert.match(html, /id="unit-card-motor"[\s\S]*?详情 &#9662;/);
});

test('disband opens a dedicated modal with its own quantity input and slider', async () => {
  const { G, nodes, modalNodes } = setupArmy();
  const dismissed = [];
  let renders = 0;
  G.Core.render = () => { renders += 1; };
  G.API.dismiss = async (unit, count) => {
    dismissed.push({ unit, count });
    return { success: true };
  };
  modalNodes['#armyDisbandQty'] = { value: '1', style: { setProperty: () => {} } };
  modalNodes['#armyDisbandSlider'] = { value: '1', style: { setProperty: () => {} } };
  modalNodes['#armyDisbandClose'] = {};
  modalNodes['#armyDisbandCancel'] = {};
  modalNodes['#armyDisbandConfirm'] = {};

  G.Army.openDisbandModal('infantry');

  const modal = nodes.armyDisbandModalMask;
  assert.ok(modal);
  assert.match(modal.innerHTML, /class="army-disband-head"/);
  assert.match(modal.innerHTML, /class="army-disband-body"/);
  assert.match(modal.innerHTML, /class="army-disband-quantity"/);
  assert.match(modal.innerHTML, /id="armyDisbandQty"[^>]*min="1" max="100000" value="1"/);
  assert.match(modal.innerHTML, /id="armyDisbandSlider"[^>]*min="1" max="100000" value="1"/);
  assert.doesNotMatch(modal.innerHTML, /id="qty_infantry"/);

  modalNodes['#armyDisbandSlider'].value = '325';
  modalNodes['#armyDisbandSlider'].oninput();
  assert.equal(modalNodes['#armyDisbandQty'].value, 325);

  await modalNodes['#armyDisbandConfirm'].onclick();
  assert.deepEqual(dismissed, [{ unit: 'infantry', count: 325 }]);
  assert.equal(nodes.armyDisbandModalMask, undefined);
  assert.equal(renders, 1);
});

test('unitDisplayName formats full names to [兵种名](编号) and home summary uses this format', () => {
  const coreCtx = vm.createContext({
    Game: {},
    document: { addEventListener: () => {} }
  });
  coreCtx.window = coreCtx;
  const coreSrc = fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8');
  require('./load-constants.cjs')(coreCtx);
  vm.runInContext(coreSrc, coreCtx);

  assert.equal(coreCtx.Game.unitDisplayName('装甲车-猎鹿犬防空型（T17E2）'), '装甲车(T17E2)');
  assert.equal(coreCtx.Game.unitDisplayName('重型坦克-斯大林（IS-2）'), '重型坦克(IS-2)');
  assert.equal(coreCtx.Game.unitDisplayName('步兵-加兰德步枪兵（M1）'), '步兵(M1)');
  assert.equal(coreCtx.Game.unitDisplayName('卡车-十轮大卡（CCKW-353）'), '卡车(CCKW-353)');
  assert.equal(coreCtx.Game.unitDisplayName('碉堡'), '碉堡');

  const { G } = setupArmy();
  G.unitDisplayName = coreCtx.Game.unitDisplayName;
  const ctx = vm.createContext({
    Game: G,
    document: { getElementById: () => null },
    setInterval: () => 1,
    clearInterval: () => {}
  });
  ctx.window = ctx;

  const mainViewSrc = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  require('./load-constants.cjs')(ctx);
  vm.runInContext(mainViewSrc, ctx);

  const html = G.MainView.renderArmySummaryList();

  // [兵种名](编号) should be displayed in the summary name span
  assert.match(html, /<span class="army-summary-name">步兵\(M1\)<\/span>/);
  assert.match(html, /<span class="army-summary-name">摩托兵\(WLA\)<\/span>/);

  // Full name should be retained in the title attribute for tooltip inspection
  assert.match(html, /title="步兵-加兰德步枪兵（M1） × 100000"/);
  assert.match(html, /title="摩托兵-哈雷（WLA） × 50000"/);
});

test('dispatch unit selection displays compact [兵种名](编号) format with title tooltip', () => {
  const c = vm.createContext({
    console, Math, Date, parseInt,
    document: { getElementById: () => null, createElement: () => ({ style: {}, setAttribute: () => {} }) },
    Game: {
      DATA: {
        units: {
          infantry: { name: '步兵-加兰德步枪兵（M1）', spd: 3, cat: 'inf' },
          armored: { name: '装甲车-猎鹿犬防空型（T17E2）', spd: 7, cat: 'arm' }
        },
        wildTypes: { plain: { name: '平原' } },
        combatRoles: {}, resources: {}, starColor: {}
      },
      fmt: String, escapeHtml: s => String(s), go: () => {},
      unitDisplayName: name => {
        var m = name.match(/[（(]([^）)]+)[）)]/);
        var code = m ? m[1].trim() : '';
        var base = name.indexOf('-') > 0 ? name.split('-')[0].trim() : name.replace(/[（(].*?[）)]/, '').trim();
        return code ? base + '(' + code + ')' : base;
      },
      Core: {
        views: {},
        state: { tech: {}, army: { infantry: 100, armored: 50 }, officers: [], resources: {}, world: { pos: { x: 10, y: 10 }, wildTiles: [] } },
        armyCap: () => 200,
        spdMul: () => 1
      }
    }
  });
  c.window = c;
  c.Core = c.Game.Core;
  require('./load-constants.cjs')(c);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), c);

  c.Game.World.mapAction({ kind: 'wild', id: 1, type: 'plain', name: '平原', x: 11, y: 11 }, 'conquer');
  const container = { innerHTML: '', querySelector: () => null, querySelectorAll: () => [] };
  c.Game.World.renderDispatch(container);

  const html = container.innerHTML;
  assert.match(html, /<span class="dispatch-unit-name" title="步兵-加兰德步枪兵（M1）">步兵\(M1\)/);
  assert.match(html, /<span class="dispatch-unit-name" title="装甲车-猎鹿犬防空型（T17E2）">装甲车\(T17E2\)/);
  assert.match(html, /class="qty recruit-qty"/);
  assert.match(html, /class="recruit-slider"/);
  assert.match(html, /出征兵力 \/ 带兵上限/);
  assert.match(html, /id="estDispatchTroops">2 \/ 200/);
});

test('compact css styles define 24px height controls and reduced margins', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  // 24px controls
  assert.match(css, /\.recruit-qty\s*\{[^}]*height:\s*24px/);
  assert.match(css, /\.recruit-slider\s*\{[^}]*height:\s*24px/);
  assert.match(css, /\.recruit-btn\s*\{[^}]*height:\s*24px/);
  // compact tabs
  assert.match(css, /\.army-tab\s*\{[^}]*min-height:\s*28px/);
  // compact unit cards
  assert.match(css, /\.unit-card\s*\{[^}]*padding:\s*5px 8px/);
});
