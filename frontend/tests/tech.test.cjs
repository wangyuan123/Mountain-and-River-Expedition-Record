const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTestEnvironment(runtime = false) {
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
          steel: { name: '钢铁', icon: 'img/resources/models/steel.webp' },
          food: { name: '粮食', icon: 'img/resources/models/food.webp' },
          oil: { name: '石油', icon: 'img/resources/models/oil.webp' },
          rare: { name: '稀矿', icon: 'img/resources/models/rare.webp' },
          gold: { name: '黄金', icon: 'img/resources/models/gold.webp' }
        },
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
  require('./load-constants.cjs')(context);
  if (runtime) {
    const state = context.Game.Core.state;
    for (const file of ['data.js', 'core.js']) {
      vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
    }
    context.Game.Core.state = Object.assign(state, { buildings: { lab: 10 }, officers: [], army: {} });
    context.Game.Core.costEnough = () => true;
    context.Game.Core.render = () => {};
  }
  vm.runInContext(code, context);
  return { G: context.Game, appendedElements, context };
}

test('recon onboarding focuses the speed-up item in the actual picker and clears it on close', () => {
  const { G, context } = setupTestEnvironment();
  const row = {};
  const cancel = {};
  const focused = [];
  let cleared = 0;
  const createElement = context.document.createElement;
  context.document.createElement = tag => {
    const el = createElement(tag);
    el.querySelector = selector => selector === '#spClose' ? cancel :
      selector === '.spicker-row[data-sid="speedUp1h"]' ? row : null;
    return el;
  };
  G.Core.state.research = { name: '侦察技术', targetLevel: 1, finishesAt: 1029000, queueId: 1 };
  G.DATA.items = { speedUp10m: { seconds: 600 }, speedUp1h: { seconds: 3600 } };
  G.Onboarding = { state: { guidedAction: { route: 'tech' } }, showSpotlight: target => focused.push(target), clearSpotlight: () => cleared++ };
  G.Tech.openSpeedUpPicker();
  assert.equal(focused[0], row, '加速弹窗必须把背景按钮的聚光灯切换到加速符');
  cancel.onclick();
  assert.equal(cleared, 1, '关闭加速弹窗后不能留下已移除选项的聚光灯');
});

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
  assert.ok(modal.innerHTML.includes('科技确认'));
  assert.ok(modal.innerHTML.includes('开始研发'));
  assert.ok(modal.innerHTML.includes('加成效果'));
  assert.ok(modal.innerHTML.includes('研发工期'));
  for (const key of ['steel', 'food']) {
    assert.ok(modal.innerHTML.includes(G.resourceIconHtml(key)));
  }
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

function currentTechnologyEffect(G, id) {
  G.Tech.setTab(G.DATA.techs[id].branch);
  const container = {};
  G.Tech.renderView(container);
  const card = container.innerHTML.split('data-tech="' + id + '"')[1].split('data-tech="')[0];
  return card.match(/<b style="color:var\(--ink\);">([^<]+)<\/b>/)[1];
}

test('recon research shows its permanent initiative and radar shows opening-only initiative', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  G.Core.state.tech = { recon_level: 4, recon_radar: 2 };
  G.Tech.setTab('侦察');
  const container = {};
  G.Tech.renderView(container);
  assert.match(container.innerHTML, /侦查交火先手\+20点/);
  assert.match(container.innerHTML, /当前首回合拦截先手值加成/);
  assert.match(G.DATA.techs.recon_level.desc, /攻守双方各按自身等级每级增加5点先手值/);
  assert.match(G.DATA.techs.recon_radar.desc, /首回合.*每级增加5点先手值，最高25点，后续回合不再提供/);
  assert.equal(currentTechnologyEffect(G, 'recon_radar'), '+10点');
  G.Tech.confirmResearch('recon_radar');
  assert.match(appendedElements.at(-1).innerHTML, /首回合拦截先手值/);
  assert.match(appendedElements.at(-1).innerHTML, /\+10点 → \+15点/);
});

test('fire density card shows independent action multiplier and reads frozen levels', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  for (const level of [0, 1, 5, 10]) {
    G.Core.state.tech = { attack_tech: 10, fire_density: level };
    assert.equal(G.Core.atkMul('inf'), 1.5);
    assert.equal(G.Core.firepowerMul(), 1 + level / 20);
    assert.equal(currentTechnologyEffect(G, 'fire_density'), '+' + level * 5 + '%（×' + (1 + level / 20).toFixed(2) + '）');
  }
  assert.equal(G.Core.firepowerMul({}), 1);
  assert.equal(G.Core.firepowerMul({ fire_density: 5 }), 1.25);
  G.Core.state.tech.fire_density = 5;
  G.Tech.confirmResearch('fire_density');
  const modal = appendedElements.at(-1).innerHTML;
  assert.match(modal, /行动火力/);
  assert.match(modal, /\+25%（×1\.25）/);
  assert.match(modal, /\+30%（×1\.30）/);
});

// 用实际运行的属性计算校验展示数值，避免文案与卡片同时使用错误倍率而互相通过。
test('technology card and upgrade preview percentages match runtime effects at every level', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  const multipliers = {
    attack_tech: () => G.Core.atkMul('inf'),
    defense_tech: () => G.Core.defMul(),
    weapon_range: () => G.Core.rangeMul(),
    cmd_hp: () => G.Core.hpMul('inf'),
    arm_engine: () => G.Core.spdMul('arm'),
    air_engine: () => G.Core.spdMul('air'),
    nav_engine: () => G.Core.spdMul('nav'),
    prod_food: () => G.Core.resBonusMul('food'),
    prod_steel: () => G.Core.resBonusMul('steel'),
    prod_oil: () => G.Core.resBonusMul('oil'),
    prod_rare: () => G.Core.resBonusMul('rare'),
    wall_reinforcement: () => G.Core.wallMul(),
    log_load: () => G.Core.loadMul(),
    log_train: () => G.Core.trainMul(),
    log_build: () => G.Core.buildMul()
  };
  function effectText(read) {
    const percent = Math.round((read() - 1) * 100);
    return (percent > 0 ? '+' : '') + percent + '%';
  }
  for (const [id, read] of Object.entries(multipliers)) {
    const max = G.DATA.techs[id].max;
    for (let lv = 0; lv <= max; lv++) {
      G.Core.state.tech = { [id]: lv };
      const current = effectText(read);
      assert.equal(currentTechnologyEffect(G, id), current, id + ' Lv.' + lv);
      if (lv < max) {
        G.Core.state.tech[id] = lv + 1;
        const next = effectText(read);
        G.Core.state.tech[id] = lv;
        G.Tech.confirmResearch(id);
        assert.ok(appendedElements.at(-1).innerHTML.includes(current + ' → ' + next), id + ' upgrade Lv.' + lv);
      }
    }
  }
});

test('equipment technology shows recipe access without inventing attribute bonuses', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  const effects = ['未解锁', 'Lv.1及以下配方', 'Lv.2及以下配方', 'Lv.3及以下配方', 'Lv.4及以下配方', 'Lv.5及以下配方'];
  for (let lv = 0; lv <= 5; lv++) {
    G.Core.state.tech = { unit_refit: lv };
    assert.equal(currentTechnologyEffect(G, 'unit_refit'), effects[lv]);
    if (lv < 5) {
      G.Tech.confirmResearch('unit_refit');
      const html = appendedElements.at(-1).innerHTML;
      assert.ok(html.includes(effects[lv] + ' → ' + effects[lv + 1]));
      assert.ok(html.includes('配方权限'));
      assert.doesNotMatch(html, /\+\d+%/);
    }
  }
});

test('load applies to all units and warehouse protects resources without changing storage capacity', () => {
  const { G } = setupTestEnvironment(true);
  G.Core.state.buildings.farm = [2, 3];
  G.Core.state.buildings.depot = [2, 3];
  const capacity = G.Core.capacity().food;
  for (let lv = 0; lv <= 5; lv++) {
    G.Core.state.tech = { log_load: lv * 2, log_warehouse: lv };
    assert.equal(currentTechnologyEffect(G, 'log_load'), (lv ? '+' : '') + lv * 10 + '%');
    assert.equal(G.Core.loadMul(), 1 + lv * .1);
    assert.equal(G.Core.protectCap(), Math.floor(5000 * (1 + lv * .1)));
    assert.equal(G.Core.capacity().food, capacity);
  }
});

test('capture and medical technologies show absolute rates with their own labels', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  for (const [id, rate, label] of [
    ['prisoner_education', lv => (lv / 10).toFixed(1) + '%', '俘获比例'],
    ['log_medical', () => Math.round(G.Core.medicalMul() * 100) + '%', '伤兵回收率']
  ]) {
    for (let lv = 0; lv <= 10; lv++) {
      G.Core.state.tech = { [id]: lv };
      const current = rate(lv);
      assert.equal(currentTechnologyEffect(G, id), current);
      if (lv < 10) {
        G.Core.state.tech[id] = lv + 1;
        const next = rate(lv + 1);
        G.Core.state.tech[id] = lv;
        G.Tech.confirmResearch(id);
        const html = appendedElements.at(-1).innerHTML;
        assert.ok(html.includes(current + ' → ' + next));
        assert.ok(html.includes(label));
      }
    }
  }
});
