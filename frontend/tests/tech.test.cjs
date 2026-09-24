const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTestEnvironment() {
  const appendedElements = [];
  const nodes = {};
  const context = {
    console,
    setInterval: (fn, ms) => 1,
    clearInterval: (id) => {},
    setTimeout: (fn, ms) => 1,
    clearTimeout: (id) => {},
    Date: { now: () => 1000000 },
    document: {
      createElement: (tag) => {
        const el = {
          tagName: tag.toUpperCase(),
          className: '',
          innerHTML: '',
          style: {},
          childNodes: [],
          parentNode: null,
          appendChild: (c) => { el.childNodes.push(c); c.parentNode = el; },
          removeChild: (c) => {
            const idx = el.childNodes.indexOf(c);
            if (idx >= 0) el.childNodes.splice(idx, 1);
            c.parentNode = null;
          },
          querySelector: (sel) => {
            if (sel.startsWith('#')) {
              const id = sel.slice(1);
              // Simple search in innerHTML or children
              return { onclick: null, textContent: '', style: {} };
            }
            return null;
          },
          querySelectorAll: (sel) => [],
          addEventListener: () => {}
        };
        return el;
      },
      getElementById: (id) => nodes[id] || null,
      body: {
        appendChild: (el) => {
          appendedElements.push(el);
          el.parentNode = context.document.body;
        },
        removeChild: (el) => {
          const idx = appendedElements.indexOf(el);
          if (idx >= 0) appendedElements.splice(idx, 1);
          el.parentNode = null;
        }
      }
    },
    Game: {
      Core: {
        views: {},
        route: 'tech',
        state: {
          buildings: { lab: 2 },
          resources: { steel: 5000, food: 5000, oil: 1000, rare: 500, gold: 500 },
          tech: { attack_tech: 1, defense_tech: 0 },
          research: null,
          items: { speedUp10m: 3, speedUp1h: 1 }
        },
        costEnough: (cost) => true,
        render: () => {}
      },
      DATA: {
        techs: {
          attack_tech: { name: '攻击科技', branch: '军事', desc: '全军攻击 +10%/级', max: 10, labReq: 1, baseCost: { steel: 240, food: 120 }, growth: 1.7, affect: 'atk_all' },
          defense_tech: { name: '防御科技', branch: '军事', desc: '全军防御 +10%/级', max: 10, labReq: 2, baseCost: { steel: 240, food: 120 }, growth: 1.7, affect: 'def_all' }
        },
        resources: {
          steel: { name: '钢铁', icon: '🔩' },
          food: { name: '粮食', icon: '🌾' },
          oil: { name: '石油', icon: '🛢️' },
          rare: { name: '稀矿', icon: '💎' },
          gold: { name: '黄金', icon: '🪙' }
        },
        resEmoji: { steel: '🔩', food: '🌾' }
      },
      API: {
        techUpgrade: (tech) => Promise.resolve({ success: true, message: '已开始研发' }),
        techCancel: (queueId) => Promise.resolve({ success: true, message: '已取消' }),
        techSpeedUp: (itemId, queueId, count) => Promise.resolve({ success: true, message: '加速成功' })
      },
      fmt: (n) => String(n),
      escapeHtml: (s) => String(s || ''),
      toast: () => {}
    }
  };
  context.window = context;
  vm.createContext(context);
  const code = fs.readFileSync(path.join(__dirname, '../js/tech.js'), 'utf8');
  vm.runInContext(code, context);
  return { G: context.Game, appendedElements, context };
}

test('techDuration scales with level and is reduced by lab level', () => {
  const { G } = setupTestEnvironment();
  const d0 = G.techDuration('attack_tech', 0, 1);
  const d1 = G.techDuration('attack_tech', 1, 1);
  const d0Lab5 = G.techDuration('attack_tech', 0, 5);

  assert.equal(d0, 30);
  assert.ok(d1 > d0, 'Level 1 tech should take longer than Level 0');
  assert.ok(d0Lab5 < d0, 'Lab level 5 should reduce research duration');
  assert.equal(d0Lab5, 21);
});

test('confirmResearch creates a confirmation modal card instead of instantly upgrading', () => {
  const { G, appendedElements } = setupTestEnvironment();
  assert.equal(appendedElements.length, 0);

  G.Tech.confirmResearch('attack_tech');
  assert.equal(appendedElements.length, 1);

  const modal = appendedElements[0];
  assert.ok(modal.innerHTML.includes('科技研发确认'));
  assert.ok(modal.innerHTML.includes('开始研发'));
  assert.ok(modal.innerHTML.includes('加成效果'));
  assert.ok(modal.innerHTML.includes('研发工期'));
});

test('renderActiveResearchCard renders progress bar when active research exists', () => {
  const { G } = setupTestEnvironment();
  G.Core.state.research = {
    queueId: 10,
    techType: 'attack_tech',
    name: '攻击科技',
    targetLevel: 2,
    startedAt: 1000000,
    finishesAt: 1060000,
    durationSeconds: 60,
    remainingSeconds: 60
  };

  const container = { innerHTML: '' };
  G.Tech.renderView(container);
  assert.ok(container.innerHTML.includes('正在研发：【攻击科技 Lv.2】'));
  assert.ok(container.innerHTML.includes('加速研发'));
  assert.ok(container.innerHTML.includes('取消研发'));
});
