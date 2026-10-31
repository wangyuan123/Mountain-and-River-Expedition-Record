const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function setupWoundedEnv() {
  const nodes = {};
  const appendedElements = [];
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
      }
    },
    getElementById: id => nodes[id] || null,
    createElement: tag => {
      return {
        tagName: tag.toUpperCase(),
        id: '',
        className: '',
        innerHTML: '',
        style: {},
        parentNode: null,
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
          if (selector === '.wounded-rules-close') {
            return this.closeBtn || (this.closeBtn = { onclick: null, focus() {} });
          }
          if (selector === '.wounded-rules-confirm-btn') {
            return this.confirmBtn || (this.confirmBtn = { onclick: null, focus() {} });
          }
          return null;
        }
      };
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
      route: 'wounded',
      views: {},
      state: { player: { cityName: '战神殿_弑神门', activeCityId: 1 }, resources: { gold: 252065, diamond: 890 } },
      refreshTop: () => {}
    },
    DATA: {
      units: {
        assault_gun: { name: '突击炮-自行加榴炮（ISU-152）' }
      }
    },
    fmt: n => Number(n).toLocaleString(),
    escapeHtml: s => String(s).replace(/</g, '&lt;').replace(/>/g, '&gt;'),
    toast: () => {},
    API: {
      getToken: () => 'token',
      getWounded: async () => ({ batches: [], total: 0, medicalLevel: 10 }),
      healWounded: async () => ({ success: true })
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

  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/wounded.js'), 'utf8'), ctx);

  return { G, document, nodes, appendedElements, docListeners };
}

test('伤兵营顶部提示重构为紧凑概览卡片，并提供详细说明按钮', () => {
  const { G } = setupWoundedEnv();
  const data = {
    cityName: '战神殿_弑神门',
    cityX: 1,
    cityY: 106,
    medicalLevel: 10,
    serverTime: 1000000,
    batches: [
      { id: 1, unit: 'assault_gun', count: 30235, goldPerUnit: 32, recoveryPercent: 50, expiresAt: 2000000 }
    ],
    resources: { gold: 252065, diamond: 890 }
  };
  G.Wounded.data = data;

  const html = G.Wounded.renderContent(data);

  // 1. 验证包含结构化概览卡片
  assert.match(html, /class="wounded-notice-card"/);
  assert.match(html, /class="wounded-notice-badge"[^>]*>救治须知<\/span>/);
  assert.match(html, /class="wounded-notice-digest"/);
  assert.match(html, /伤兵留存 7 天/);
  assert.match(html, /最高 65% 回收/);
  assert.match(html, /每 1000 基础黄金折算 1 颗/);

  // 2. 验证包含详细说明操作按钮
  assert.match(html, /class="wounded-rules-btn"[^>]*onclick="Game\.Wounded\.showWoundedRulesModal\(\)"/);
  assert.match(html, /详细说明/);

  // 3. 验证资金余额行保留且排版优雅
  assert.match(html, /id="wounded-balance"/);
  assert.match(html, /本城可用黄金 252,065 · 账号钻石 890/);

  // 4. 验证移除了原先占用半屏的大黄框长文
  assert.doesNotMatch(html, /<div class="wounded-notice"><b>请在受伤后 7 天内治疗<\/b>/);
});

test('点击详细说明按钮或调用showWoundedRulesModal展示规则弹窗并支持关闭', () => {
  const { G, appendedElements, docListeners } = setupWoundedEnv();
  G.Wounded.data = { medicalLevel: 10 };

  assert.equal(typeof G.Wounded.showWoundedRulesModal, 'function');
  assert.equal(typeof G.Wounded.showRulesModal, 'function');

  // 打开弹窗
  G.Wounded.showWoundedRulesModal();
  assert.equal(appendedElements.length, 1);
  const modal = appendedElements[0];
  assert.equal(modal.id, 'woundedRulesModal');

  // 验证弹窗各板块规则完备性
  assert.match(modal.innerHTML, /伤兵营救治与医疗规则说明/);
  assert.match(modal.innerHTML, /7 天时限与倒计时机制/);
  assert.match(modal.innerHTML, /超过 7 天未救治的伤兵将伤重阵亡全部消失/);
  assert.match(modal.innerHTML, /医疗技术与军官急救技能/);
  assert.match(modal.innerHTML, /50%/);
  assert.match(modal.innerHTML, /治疗费用与货币折算/);
  assert.match(modal.innerHTML, /1000 未折扣的基础黄金折算 1 颗钻石/);
  assert.match(modal.innerHTML, /额外提供 15% 回收率/);
  assert.match(modal.innerHTML, /学习即满级，无需升级/);
  assert.match(modal.innerHTML, /合计最高 65%/);
  assert.match(modal.innerHTML, /归降冻结期间暂停/);
  assert.match(modal.innerHTML, /快捷选择与批量救治/);
  assert.match(modal.innerHTML, /一键 MAX/);
  assert.match(modal.innerHTML, /超额置灰/);

  // 点击“我知道了”关闭弹窗
  assert.ok(modal.confirmBtn && typeof modal.confirmBtn.onclick === 'function');
  modal.confirmBtn.onclick();
  assert.equal(appendedElements.length, 0);

  // 再次打开并测试 ESC 关闭
  G.Wounded.showWoundedRulesModal();
  assert.equal(appendedElements.length, 1);
  const escHandler = docListeners.keydown && docListeners.keydown.at(-1);
  assert.ok(typeof escHandler === 'function');
  escHandler({ key: 'Escape' });
  assert.equal(appendedElements.length, 0);
});
