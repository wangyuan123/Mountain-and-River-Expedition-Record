const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setupTestEnvironment() {
  const documentMock = {
    body: {
      appendChild: function (node) {
        this.children = this.children || [];
        this.children.push(node);
        node.parentNode = this;
      },
      removeChild: function (node) {
        this.children = (this.children || []).filter(c => c !== node);
        node.parentNode = null;
      }
    },
    createElement: function (tag) {
      const el = {
        tagName: tag.toUpperCase(),
        id: '',
        className: '',
        innerHTML: '',
        parentNode: null,
        children: [],
        querySelector: function (selector) {
          if (selector === '.fort-rules-close') return this._closeBtn;
          if (selector === '.fort-rules-confirm-btn') return this._confirmBtn;
          return null;
        },
        addEventListener: function (evt, handler) {
          this._listeners = this._listeners || {};
          this._listeners[evt] = handler;
        },
        removeEventListener: function () {}
      };
      el._closeBtn = { onclick: null };
      el._confirmBtn = { onclick: null, focus: () => {} };
      return el;
    },
    getElementById: function (id) {
      if (documentMock.body.children) {
        return documentMock.body.children.find(c => c.id === id) || null;
      }
      return null;
    },
    addEventListener: function (evt, handler) {
      this._docListeners = this._docListeners || {};
      this._docListeners[evt] = handler;
    },
    removeEventListener: function () {}
  };

  const state = {
    buildings: { wall: 10 },
    forts: { bunker: 5000, howitzer: 5000, antitank: 5000, flak: 5000 },
    tech: { wall_reinforcement: 5 }
  };

  const context = vm.createContext({
    window: {
      document: documentMock,
      Game: {
        Core: {
          state,
          views: {},
          wallMul: () => 1 + 0.05 * ((state.tech || {}).wall_reinforcement || 0)
        },
        DATA: {
          buildings: {
            wall: { fortCapPerLevel: 3000, wallHpPerLevel: 250000, wallDefensePerLevel: 20 }
          },
          forts: {
            bunker: { name: '碉堡', desc: '坚固掩体,反步兵,近程高血防', atkGround: 8, atkAir: 1, atkSea: 1, atkFort: 1, def: 24, hp: 260, range: 1050, cost: { steel: 80, oil: 0, rare: 0 } },
            howitzer: { name: '榴弹炮', desc: '远程压制,反步兵与建筑', atkGround: 30, atkAir: 1, atkSea: 28, atkFort: 30, def: 6, hp: 80, range: 1850, cost: { steel: 60, oil: 10, rare: 5 } },
            antitank: { name: '反坦克炮', desc: '穿甲火力,反装甲', atkGround: 42, atkAir: 1, atkSea: 36, atkFort: 15, def: 8, hp: 80, range: 1850, cost: { steel: 70, oil: 10, rare: 10 } },
            flak: { name: '防空炮', desc: '对空火力,反空军', atkGround: 12, atkAir: 63, atkSea: 10, atkFort: 1, def: 8, hp: 80, range: 1850, cost: { steel: 55, oil: 15, rare: 15 } }
          },
          combatRoles: {
            bunker: '固定地面防御；高生命与近程火力阻滞接近部队，对空与对海火力极弱',
            howitzer: '远程地面压制与岸防；1850射程，对空火力极弱',
            antitank: '远程地面与岸防火力；1850射程、对地42，对空火力极弱',
            flak: '固定防空火力，对地和对海较弱，攻坚能力极弱'
          }
        },
        resourceIconHtml: id => '[' + id + ']',
        fmt: n => Number(n).toLocaleString(),
        escapeHtml: t => String(t || '')
      }
    },
    document: documentMock
  });

  const fortJs = fs.readFileSync(path.join(__dirname, '..', 'js', 'fort.js'), 'utf8');
  vm.runInContext(fortJs, context, { filename: 'fort.js' });

  Object.assign(state.gameplay, {fortCap:30000, wall:{maxHp:2500000, defense:200}});
  return { G: context.window.Game, documentMock, state };
}

test('城防工事页面重构为高空间利用率紧凑看板与工事网格', () => {
  const { G, state } = setupTestEnvironment();
  const view = { innerHTML: '' };
  G.Fort.renderView(view);

  // 1. 验证顶部概览看板
  assert.match(view.innerHTML, /class="fort-overview-card"/);
  assert.match(view.innerHTML, /class="fort-ov-badge"[^>]*>战备概览</);
  assert.match(view.innerHTML, /城防 <b>Lv\.10<\/b>/);
  assert.match(view.innerHTML, /class="fort-rules-btn"[^>]*onclick="Game\.Fort\.showFortRulesModal\(\)"/);

  // 2. 验证三列网格数据
  assert.match(view.innerHTML, /共享容量/);
  assert.match(view.innerHTML, /20,000/);
  assert.match(view.innerHTML, /30,000/);
  assert.match(view.innerHTML, /墙体生命/);
  assert.match(view.innerHTML, /墙体防御/);

  // 3. 验证紧凑网格与4类工事卡片
  assert.match(view.innerHTML, /class="fort-grid"/);
  for (const type of ['bunker', 'howitzer', 'antitank', 'flak']) {
    assert.match(view.innerHTML, new RegExp('id="fort-card-' + type + '"'));
    assert.match(view.innerHTML, new RegExp(G.DATA.forts[type].name));
  }

  // 4. 验证打击四维与属性标签
  assert.match(view.innerHTML, /class="fort-pill atk"[^>]*title="对地攻击"/);
  assert.match(view.innerHTML, /class="fort-pill atk"[^>]*title="对空攻击"/);
  assert.match(view.innerHTML, /class="fort-pill def"/);
  assert.match(view.innerHTML, /class="fort-pill hp"/);
  assert.match(view.innerHTML, /class="fort-pill range"/);

  // 5. 验证单价与修筑操作行（紧凑左右对齐）
  assert.match(view.innerHTML, /class="fort-action-row"/);
  assert.match(view.innerHTML, /fort-qty-input/);
  assert.match(view.innerHTML, /fort-act-btn/);
  assert.match(view.innerHTML, /class="btn ok sm fort-act-btn"/);
  assert.match(view.innerHTML, /class="btn warn sm fort-act-btn"/);
});

test('城防工事规则说明弹窗展示完整规则并支持关闭', () => {
  const { G, documentMock } = setupTestEnvironment();

  // 调用弹窗展示方法
  G.Fort.showFortRulesModal();

  const modal = documentMock.getElementById('fortRulesModal');
  assert.ok(modal, '弹窗应当已挂载至 DOM');
  assert.match(modal.innerHTML, /🛡️ 城防工事与守城规则说明/);
  assert.match(modal.innerHTML, /📦 工事容量与城防等级/);
  assert.match(modal.innerHTML, /🧱 墙体生命与征服判定/);
  assert.match(modal.innerHTML, /⚔️ 守城交火与自动参战/);
  assert.match(modal.innerHTML, /🔨 战备修筑与拆除回收/);

  // 触发关闭
  modal.querySelector('.fort-rules-confirm-btn').onclick();
  assert.equal(documentMock.getElementById('fortRulesModal'), null, '点击确认后弹窗应当关闭并移除');
});
