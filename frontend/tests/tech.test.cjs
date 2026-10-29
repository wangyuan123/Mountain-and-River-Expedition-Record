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

test('all 240 research gold prices match the approved design and preserve other resource costs', () => {
  const { G } = setupTestEnvironment(true);
  const design = JSON.parse(fs.readFileSync(path.join(__dirname, '../../docs/balance/tech-research-gold-design-20261028.json'), 'utf8'));
  const covered = new Set();
  let total = 0;
  for (const group of design.groups) {
    for (const tech of group.technologies) {
      assert.ok(!covered.has(tech.key));
      covered.add(tech.key);
      const definition = G.DATA.techs[tech.key];
      assert.equal(G.Constants.researchGold.techGroups[tech.key], group.key);
      assert.equal(definition.baseCost.gold, undefined);
      let previous = 0;
      let cumulative = 0;
      for (const row of group.levels) {
        const cost = G.techCost(tech.key, row.fromLevel);
        assert.equal(cost.gold, row.gold, tech.key + ' Lv.' + row.targetLevel);
        assert.ok(cost.gold > previous);
        for (const [key, value] of Object.entries(definition.baseCost)) {
          assert.equal(cost[key], Math.floor(value * Math.pow(definition.growth, row.fromLevel)));
        }
        assert.equal(Object.keys(cost).length, Object.keys(definition.baseCost).length + 1);
        cumulative += cost.gold;
        assert.equal(cumulative, row.cumulativeGold);
        total += cost.gold;
        previous = cost.gold;
      }
    }
  }
  assert.deepEqual([...covered].sort(), Object.keys(G.DATA.techs).sort());
  assert.deepEqual(Object.keys(G.Constants.researchGold.techGroups).sort(), [...covered].sort());
  assert.equal(total, 2019962000);
});

test('gold shortage disables research confirmation and the card displays the same required gold', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  G.Core.state.tech.attack_tech = 0;
  G.Core.state.resources = { steel: 1000, food: 1000, gold: 5999 };
  G.Core.costEnough = cost => Object.entries(cost).every(([key, value]) => (G.Core.state.resources[key] || 0) >= value);
  G.Tech.confirmResearch('attack_tech');
  assert.match(appendedElements.at(-1).innerHTML, /需 6000/);
  assert.match(appendedElements.at(-1).innerHTML, /id="cuOk" disabled/);
  const container = {};
  G.Tech.renderView(container);
  assert.match(container.innerHTML, /6000/);
  G.Core.state.resources.gold = 6000;
  G.Tech.confirmResearch('attack_tech');
  assert.match(appendedElements.at(-1).innerHTML, /id="cuOk">开始研发/);
  G.Tech.showResearchInfo();
  assert.match(appendedElements.at(-1).innerHTML, /资源产量科技2000万、普通科技3000万、燃烧引擎5000万、喷气推进6500万、舰船动力8000万/);
});

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

test('techDuration scales with level and ignores lab level', () => {
  const { G } = setupTestEnvironment();
  const d0 = G.techDuration('attack_tech', 0, 1);
  const d1 = G.techDuration('attack_tech', 1, 1);
  const d0Lab5 = G.techDuration('attack_tech', 0, 5);

  assert.equal(d0, 180);
  assert.ok(d1 > d0, 'Level 1 tech should take longer than Level 0');
  assert.equal(d0Lab5, d0, 'Lab level only controls research access');
  assert.equal(d0Lab5, 180);
  assert.equal(G.techDuration('attack_tech', 5, 0), 85320);
  assert.equal(G.techDuration('attack_tech', 5, 1), 85320);
  assert.equal(G.techDuration('attack_tech', 5, 5), 85320);
  assert.equal(G.techDuration('attack_tech', 5, 10), 85320);
  assert.equal(G.techDuration('attack_tech', 5, 20), 85320, 'Lab must not speed up research');
});

test('研发中心逐级开放科技种类且各项目标等级始终受本城中心限制', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '../../backend/src/main/resources/game/tech-building-prerequisites.json')));
  G.Prerequisites = { check: () => ({ missing: [] }), preview: () => {}, missingText: () => '' };
  for (let level = 0; level <= 10; level++) {
    const expectedCount = [0, 6, 16, 22, 23, 24, 24, 24, 24, 24, 24][level];
    const access = G.researchAccess(level);
    assert.equal(access.unlocked.length, expectedCount);
    assert.equal(access.level, level);
    for (const [id, tech] of Object.entries(G.DATA.techs)) {
      assert.equal(tech.labReq, rules.technologies[id].labFloor, id);
      for (let target = 1; target <= 10; target++) {
        const requiredLab = rules.technologies[id].levels[target - 1].requires.find(req => req.building === 'lab');
        assert.equal(requiredLab.level, Math.max(tech.labReq, target), id + ' target ' + target);
      }
      G.Core.state.buildings.lab = level;
      G.Core.state.tech = { [id]: level };
      G.Core.state.research = null;
      const before = appendedElements.length;
      G.Tech.confirmResearch(id);
      assert.equal(appendedElements.length, before, id + ' must not exceed lab ' + level);
    }
  }
  G.Core.state.buildings.lab = 4;
  G.Core.state.tech = {};
  G.Tech.confirmResearch('air_engine');
  assert.equal(appendedElements.length, 1, '新开放的喷气推进从1级开始研究');
  assert.match(appendedElements.at(-1).innerHTML, /Lv\.0 → Lv\.1/);
  assert.doesNotMatch(appendedElements.at(-1).innerHTML, /中心提速/);

});

test('科研工期设计测算与实际公式一致，中心准入表与服务端配置一致', () => {
  const { G } = setupTestEnvironment(true);
  const design = JSON.parse(fs.readFileSync(path.join(__dirname, '../../docs/balance/research-queue-duration-design-20261019.json')));
  const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '../../backend/src/main/resources/game/tech-building-prerequisites.json')));
  assert.equal(design.labAccess.speedBonus, false);
  assert.equal(design.timing.labSpeedPerLevel, undefined);
  for (const row of design.labAccess.unlocksByLevel) {
    const access = G.researchAccess(row.labLevel);
    assert.equal(access.unlocked.length, row.cumulativeTechnologyCount);
    assert.equal(access.level, row.maximumTargetLevel);
    const expected = Object.entries(rules.technologies).filter(([, tech]) => tech.labFloor === row.labLevel).map(([id]) => id).sort();
    assert.deepEqual(row.newTechnologyKeys.slice().sort(), expected);
  }
  for (const profile of design.analysis.profiles) {
    G.Core.state.officers = [{ role: 'mayor', knowledge: profile.knowledge, skills: [{ id: 'research', lv: profile.researchSkillLevel }] }];
    assert.equal(profile.speedMultiplier, Number(((1 + profile.knowledge * 0.001) * (1 + profile.researchSkillLevel * 0.04)).toFixed(8)));
    for (const [key, values] of Object.entries(profile.durationSecondsByTimingProfile)) {
      const techId = key === 'normal' ? 'attack_tech' : key === 'resource' ? 'prod_food' : key;
      values.forEach((seconds, index) => {
        for (const labLevel of [0, 1, 5, 10]) assert.equal(G.techDuration(techId, index, labLevel), seconds, profile.key + ' ' + techId);
      });
    }
  }
});

test('research uses the current mayor knowledge continuously and multiplies it with the research skill', () => {
  const { G } = setupTestEnvironment(true);
  const mayor = { id: 1, role: 'mayor', knowledge: 0, skills: [] };
  G.Core.state.officers = [mayor];
  for (const [knowledge, seconds] of [[0, 85320], [50, 81257], [100, 77564], [200, 71100]]) {
    mayor.knowledge = knowledge;
    assert.equal(G.techDuration('attack_tech', 5, 10), seconds, 'Mayor knowledge ' + knowledge);
  }
  mayor.knowledge = 100;
  mayor.skills = [{ id: 'research', lv: 5 }];
  assert.equal(G.techDuration('attack_tech', 5, 10), 64636);
  mayor.role = 'commander';
  assert.equal(G.techDuration('attack_tech', 5, 10), 85320, 'Only the appointed mayor accelerates research');
  G.Core.state.officers = [];
  assert.equal(G.techDuration('attack_tech', 5, 10), 85320);
  assert.equal(G.techDuration('prod_food', 0, 10), 120);
  mayor.role = 'mayor';
  G.Core.state.officers = [mayor];
  assert.equal(G.techDuration('prod_food', 0, 10), 91, 'Stacked bonuses apply to resource technology');
  assert.equal(G.techDuration('attack_tech', 9, 10), 981818, 'Late research exceeds 24 hours');
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

test('tech page keeps the overview compact and opens ordered research guidance', () => {
  const { G, appendedElements } = setupTestEnvironment();
  const container = { innerHTML: '' };
  G.Tech.renderView(container);

  assert.match(container.innerHTML, /中心升级不提速，研发速度仅受市长学识和格物致知影响/);
  assert.match(container.innerHTML, /class="link-act"[^>]*>查看研发说明/);
  assert.doesNotMatch(container.innerHTML, /基础工期为 7\.5 秒/);

  G.Tech.showResearchInfo();
  const modal = appendedElements.at(-1);
  assert.match(modal.innerHTML, /role="dialog"/);
  assert.match(modal.innerHTML, /<ol/);
  assert.match(modal.innerHTML, /基础工期/);
  assert.match(modal.innerHTML, /研发中心/);
  assert.match(modal.innerHTML, /市长学识/);
  assert.match(modal.innerHTML, /格物致知/);
  assert.match(modal.innerHTML, /知道了/);
});

function currentTechnologyEffect(G, id) {
  G.Tech.setTab(G.DATA.techs[id].branch);
  const container = {};
  G.Tech.renderView(container);
  const card = container.innerHTML.split('data-tech="' + id + '"')[1].split('data-tech="')[0];
  return card.match(/<b style="color:var\(--ink\);">([^<]+)<\/b>/)[1];
}

test('military theory displays absolute probability points at every level and in upgrade previews', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  assert.equal(G.DATA.techs.military_theory.max, 10);
  for (let level = 0; level <= 10; level++) {
    G.Core.state.tech = { military_theory: level };
    const current = '+' + (level * 3 / 10).toFixed(1) + '个百分点';
    assert.equal(currentTechnologyEffect(G, 'military_theory'), current);
    if (level < 10) {
      G.Tech.confirmResearch('military_theory');
      const next = '+' + ((level + 1) * 3 / 10).toFixed(1) + '个百分点';
      assert.ok(appendedElements.at(-1).innerHTML.includes(current + ' → ' + next));
    }
  }
});

test('recon research shows its permanent initiative and radar shows opening-only initiative', () => {
  const { G, appendedElements } = setupTestEnvironment(true);
  G.Core.state.tech = { recon_level: 4, recon_radar: 2 };
  G.Tech.setTab('侦察');
  const container = {};
  G.Tech.renderView(container);
  assert.match(container.innerHTML, /侦查交火先手\+20点/);
  assert.match(container.innerHTML, /当前首回合拦截先手值加成/);
  assert.match(G.DATA.techs.recon_level.desc, /双方各按自身等级每级增加5点先手值，最高50点/);
  assert.match(G.DATA.techs.recon_radar.desc, /首回合.*每级增加2点先手值，最高20点/);
  assert.equal(currentTechnologyEffect(G, 'recon_radar'), '+4点');
  G.Tech.confirmResearch('recon_radar');
  assert.match(appendedElements.at(-1).innerHTML, /首回合拦截先手值/);
  assert.match(appendedElements.at(-1).innerHTML, /\+4点 → \+6点/);
  G.Core.state.tech.recon_radar = 10;
  assert.equal(currentTechnologyEffect(G, 'recon_radar'), '+20点');
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
  const effects = Array.from({ length: 11 }, (_, lv) => lv ? 'Lv.' + lv + '及以下配方' : '未解锁');
  for (let lv = 0; lv <= 10; lv++) {
    G.Core.state.tech = { unit_refit: lv };
    assert.equal(currentTechnologyEffect(G, 'unit_refit'), effects[lv]);
    if (lv < 10) {
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
  for (let lv = 0; lv <= 10; lv++) {
    G.Core.state.tech = { log_load: lv, log_warehouse: lv };
    assert.equal(currentTechnologyEffect(G, 'log_load'), (lv ? '+' : '') + lv * 5 + '%');
    assert.equal(G.Core.loadMul(), 1 + lv * .05);
    assert.equal(G.Core.protectCap(), Math.floor(5000 * (1 + lv * .05)));
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


test('all target-level duration profiles and expanded prerequisites match the reviewed design', () => {
  const { G } = setupTestEnvironment(true);
  const design = JSON.parse(fs.readFileSync(path.join(__dirname, '../../docs/balance/research-queue-duration-design-20261019.json')));
  const expected = {
    attack_tech: [180,600,1800,10800,43200,85320,168420,332460,656400,1296000],
    prod_food: [120,480,1500,5400,21600,46860,101640,220380,478020,1036800],
    arm_engine: [300,900,2700,10800,43200,90360,188940,395100,826260,1728000],
    nav_engine: [300,900,2700,10800,43200,96660,216180,483480,1081500,2419200],
    air_engine: [300,900,2700,10800,43200,96660,216180,483480,1081500,2419200]
  };
  for (const [id, values] of Object.entries(expected)) values.forEach((seconds, index) => assert.equal(G.techDuration(id, index, 0), seconds, id + ' level ' + (index + 1)));
  for (const id of design.tenLevelTechnologyExpansion.technologyKeys) {
    assert.equal(G.DATA.techs[id].max, 10);
    expected.attack_tech.forEach((seconds, index) => assert.equal(G.techDuration(id, index, 0), seconds));
  }
  const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../../backend/src/main/resources/game/tech-building-prerequisites.json')));
  for (const [id, override] of Object.entries(design.tenLevelTechnologyExpansion.prerequisiteOverrides)) {
    assert.deepEqual(config.technologies[id].levels, override.levels);
  }
  G.Core.state.officers = [{ role: 'mayor', knowledge: 344, skills: [{ id: 'research', lv: 5 }] }];
  assert.equal(G.techDuration('attack_tech', 9, 10), 803571);
  assert.equal(G.techDuration('nav_engine', 9, 10), 1500000);
  assert.equal(expected.nav_engine.at(-1), design.timing.endgameBaseSeconds);
});

test('科技卡片在前置不满足时展开全部要求，已满足时仍可折叠', () => {
  const { G } = setupTestEnvironment(true);
  G.Prerequisites = {
    check: () => ({ missing: [{ id: 'lab', required: 2, actual: 1, scope: 'current_city' }] }),
    missingText: () => '需本城军工科技研发中心 Lv.2 (当前 Lv.1)',
    html: () => '<div class="prereq-list">前置要求</div>',
    preview: () => {}
  };
  G.Core.state.buildings.lab = 1; // 军工科技研发中心 Lv.1，军事理论需 Lv.2，因此处于锁定状态
  G.Core.state.tech = { military_theory: 0 };
  const container = { innerHTML: '' };
  G.Tech.renderView(container);
  const html = container.innerHTML;

  // 1. 卡片拥有专用类名与紧凑容器
  assert.match(html, /class="menu-item tech-card lock is-locked" data-tech="military_theory"/);
  assert.match(html, /class="tech-card-header"/);
  assert.match(html, /class="tech-name n">军事理论<\/span>/);
  assert.match(html, /class="tech-level lv">Lv\.0\/10<\/span>/);

  // 2. 描述与效果信息结构紧凑并列
  assert.match(html, /class="tech-desc d"/);
  assert.match(html, /class="tech-info-row"/);
  assert.match(html, /class="tech-effect-tag"><span class="tech-effect-lbl">当前五星名将整批概率加成: <\/span><b style="color:var\(--ink\);">\+0\.0个百分点<\/b><\/span>/);
  assert.match(html, /class="tech-lock-pill cost">需.*军工科技研发中心 Lv\.2.*<\/span>/);

  // 未满足的要求直接展开，玩家可一次看到所有缺项和建设入口。
  assert.match(html, /<details class="prereq-details" open onclick="event\.stopPropagation\(\)"><summary class="prereq-summary"><span>Lv\.1 建筑要求<\/span><\/summary>/);
  assert.match(html, /class="prereq-preview-foot"><button type="button" class="link-act prereq-preview-btn"[^>]*>查看各级前置要求 &gt;<\/button><\/div><\/details>/);
  G.Prerequisites.check = () => ({ missing: [] });
  G.Core.state.buildings.lab = 10;
  G.Tech.renderView(container);
  assert.doesNotMatch(container.innerHTML, /<details class="prereq-details" open/);

  // 4. 验证 CSS 样式表包含紧凑卡片定义
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.tech-card\s*\{[^}]*padding:\s*7px 10px;/);
  assert.match(css, /\.tech-card-header\s*\{[^}]*display:\s*flex;/);
  assert.match(css, /\.tech-info-row\s*\{[^}]*display:\s*flex;/);
});

test('正在研发的科技卡片拥有专用激活高亮和倒计时徽章', () => {
  const { G } = setupTestEnvironment(true);
  G.Core.state.buildings.lab = 5;
  G.Core.state.research = {
    techType: 'military_theory',
    name: '军事理论',
    targetLevel: 1,
    startedAt: 1000000,
    finishesAt: 1060000
  };
  const container = { innerHTML: '' };
  G.Tech.renderView(container);
  const html = container.innerHTML;

  assert.match(html, /class="menu-item tech-card active is-researching" data-tech="military_theory"/);
  assert.match(html, /class="quest-badge warn tech-timer-badge" id="tech-badge-military_theory">⏳ 研发中 1分钟0秒<\/span>/);
  assert.match(html, /class="btn sm ok tech-btn-action"[^>]*onclick="event\.stopPropagation\(\);Game\.Tech\.openSpeedUpPicker\(\)">⚡ 加速<\/button>/);
});

