const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function setupArmyTestEnv() {
  const nodes = {};
  const appendedElements = [];
  const bodyListeners = {};
  const docListeners = {};

  const document = {
    activeElement: null,
    body: {
      appendChild(element) {
        element.parentNode = this;
        nodes[element.id] = element;
        appendedElements.push(element);
      },
      removeChild(element) {
        if (nodes[element.id] === element) delete nodes[element.id];
        const idx = appendedElements.indexOf(element);
        if (idx >= 0) appendedElements.splice(idx, 1);
        element.parentNode = null;
      },
      addEventListener(type, fn) {
        bodyListeners[type] = bodyListeners[type] || [];
        bodyListeners[type].push(fn);
      },
      removeEventListener(type, fn) {
        if (!bodyListeners[type]) return;
        bodyListeners[type] = bodyListeners[type].filter(cb => cb !== fn);
      }
    },
    getElementById: id => nodes[id] || null,
    createElement: tag => {
      const el = {
        tagName: tag.toUpperCase(),
        id: '',
        className: '',
        innerHTML: '',
        style: {},
        parentNode: null,
        children: [],
        listeners: {},
        addEventListener(type, fn) {
          this.listeners[type] = this.listeners[type] || [];
          this.listeners[type].push(fn);
        },
        removeEventListener(type, fn) {
          if (!this.listeners[type]) return;
          this.listeners[type] = this.listeners[type].filter(cb => cb !== fn);
        },
        querySelector(selector) {
          if (selector === '.army-rules-close') {
            return this.closeBtn || (this.closeBtn = {
              onclick: null,
              focus() {}
            });
          }
          if (selector === '.army-rules-confirm-btn') {
            return this.confirmBtn || (this.confirmBtn = {
              onclick: null,
              focus() {}
            });
          }
          return null;
        }
      };
      return el;
    },
    addEventListener(type, fn) {
      docListeners[type] = docListeners[type] || [];
      docListeners[type].push(fn);
    },
    removeEventListener(type, fn) {
      if (!docListeners[type]) return;
      docListeners[type] = docListeners[type].filter(cb => cb !== fn);
    }
  };

  const G = {
    Core: {
      views: {},
      route: 'army',
      state: {
        army: { infantry: 100000, truck: 50000 },
        resources: { steel: 500000, oil: 500000, rare: 500000, food: 500000 },
        marches: [],
        woundedCount: 0
      },
      civilianPopulation: () => 192000,
      populationCapacity: () => 192000,
      popFree: () => 192000,
      populationGrowthPerHour: () => 0,
      armyCap: () => 600000,
      foodPerHour: () => 2255452,
      getOfficerByRole: role => (role === 'commander' ? { name: '朱可夫' } : null),
      firepowerMul: () => 1,
      buildingLevels: () => [10, 10],
      trainMul: () => 1.5,
      render: () => {}
    },
    DATA: {
      units: {
        infantry: {
          name: '步兵-加兰德步枪兵（M1）',
          cat: 'inf',
          atkGround: 6, atkAir: 1, atkSea: 1, atkFort: 2, def: 4, hp: 30, spd: 3, range: 100, food: 1, pop: 1,
          build: 'factory',
          cost: { steel: 20, oil: 0, rare: 0 }
        }
      },
      buildings: {
        factory: { name: '军工厂' }
      },
      combatRoles: { infantry: '廉价步兵' },
      resources: { steel: { icon: 'img/steel.webp' } }
    },
    fmt: n => Number(n).toLocaleString(),
    escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'),
    toast: () => {},
    API: {
      armyQueue: async () => ({ queue: [] }),
      getArmyQueue: async () => ({ queue: [] })
    }
  };

  const ctx = vm.createContext({
    Game: G,
    document,
    setInterval: () => 1,
    clearInterval: () => {}
  });
  ctx.window = ctx;

  require('./load-constants.cjs')(ctx);
  G.Core.state.unitUnlocks = {
    infantry: { unitId: 'infantry', eligible: true, requirements: [], missing: [] }
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/army.js'), 'utf8'), ctx);

  return { G, document, nodes, appendedElements, docListeners };
}

test('军队Tab顶部重构为精简概览看板，并提供查看详情说明按钮', () => {
  const { G } = setupArmyTestEnv();
  const container = { innerHTML: '' };
  G.Army.renderView(container);
  const html = container.innerHTML;

  // 1. 验证包含了现代结构化概览卡片
  assert.match(html, /class="army-overview-card"/);
  assert.match(html, /class="army-ov-badge"[^>]*>战备概览<\/span>/);

  // 2. 验证核心数据（平民、现役部队、养兵耗粮、指挥官）
  assert.match(html, /平民人口/);
  assert.match(html, /192,000/);
  assert.match(html, /现役部队/);
  assert.match(html, /600,000/);
  assert.match(html, /养兵耗粮/);
  assert.match(html, /-2,255,452/);
  assert.match(html, /朱可夫/);

  // 3. 验证包含了查看详情说明按钮
  assert.match(html, /class="army-rules-btn"[^>]*onclick="Game\.Army\.showArmyDetailModal\(\)"/);
  assert.match(html, /查看详情说明/);

  // 4. 验证不再有原版挤在一起的杂乱长描述
  assert.doesNotMatch(html, /按目标使用对地、对空、对海或对工事攻击；所有兵种均有基础火力/);
  assert.doesNotMatch(html, /创建征兵队列立即扣除平民，取消或解散返还/);
});

test('点击查看详情说明或调用showArmyDetailModal弹出完备战备规则Modal', () => {
  const { G, appendedElements, docListeners } = setupArmyTestEnv();

  assert.equal(typeof G.Army.showArmyDetailModal, 'function');
  assert.equal(typeof G.Army.showArmyRulesModal, 'function');

  // 打开弹窗
  G.Army.showArmyDetailModal();
  assert.equal(appendedElements.length, 1);
  const modal = appendedElements[0];
  assert.equal(modal.id, 'armyDetailModal');

  // 验证弹窗内容涵盖平民返还、统帅加成、粮草消耗、四维克制、行军速度
  assert.match(modal.innerHTML, /兵种整编与战备规则说明/);
  assert.match(modal.innerHTML, /平民人口与征兵返还/);
  assert.match(modal.innerHTML, /全额返还/);
  assert.match(modal.innerHTML, /编制规模与军事指挥官/);
  assert.match(modal.innerHTML, /朱可夫/);
  assert.match(modal.innerHTML, /养兵粮饷与维持消耗/);
  assert.match(modal.innerHTML, /四维打击与协同克制/);
  assert.match(modal.innerHTML, /行军补给与速度限制/);

  // 测试点击“我知道了”关闭弹窗
  assert.ok(modal.confirmBtn && typeof modal.confirmBtn.onclick === 'function');
  modal.confirmBtn.onclick();
  assert.equal(appendedElements.length, 0);

  // 再次打开并测试 ESC 关闭
  G.Army.showArmyDetailModal();
  assert.equal(appendedElements.length, 1);
  const escHandler = docListeners.keydown && docListeners.keydown.at(-1);
  assert.ok(typeof escHandler === 'function');
  escHandler({ key: 'Escape' });
  assert.equal(appendedElements.length, 0);
});
