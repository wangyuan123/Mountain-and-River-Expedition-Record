const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const rules = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8'));

test('军事理论十级前置与设计配置一致，逐项检查军校、研发中心和参谋部', () => {
  const design = JSON.parse(fs.readFileSync(path.join(__dirname, '../../docs/balance/tech-building-prerequisites-design.json'), 'utf8'));
  assert.equal(rules.version, design.version);
  assert.deepEqual(rules.technologies.military_theory, design.technologies.military_theory);
  for (let level = 1; level <= 10; level++) {
    const buildings = { lab: Math.max(2, level), academy: level, staff: Math.max(0, level - 2) };
    assert.equal(environment(buildings).check('technologies', 'military_theory', level).missing.length, 0);
    for (const key of level < 3 ? ['lab', 'academy'] : ['lab', 'academy', 'staff']) {
      const below = { ...buildings, [key]: buildings[key] - 1 };
      const missing = environment(below).check('technologies', 'military_theory', level).missing;
      assert.deepEqual(Array.from(missing, item => item.id), [key]);
    }
  }
  const pending = environment({ lab: 10, academy: 9, staff: 8 }, [{ id: 'academy', targetLevel: 10 }]);
  assert.deepEqual(Array.from(pending.check('technologies', 'military_theory', 10).missing, item => item.id), ['academy']);
});

function environment(buildings, constructions, coastalPortLevel, unitUnlocks, equipment) {
  const calls = [];
  const context = {
    window: { Game: {
      Core: { state: { buildings, constructions: constructions || [], coastalPortLevel, unitUnlocks }, go: id => calls.push(['go', id]) },
      DATA: { buildings: {}, techs: { air_engine: { branch: 'air' } } },
      Tech: { setTab: id => calls.push(['tab', id]) },
      Cities: { open: () => calls.push(['cities']) }
    } },
    document: { getElementById: () => null }
  };
  context.window.window = context.window;
  context.window.Game.UnitEquipment = equipment;
  context.window.document = context.document;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  const api = context.window.Game.Prerequisites;
  api.setRules(rules);
  api.calls = calls;
  return api;
}

test('attack research reports every missing factory without adding duplicate building levels', () => {
  const api = environment({ lab: 7, lightfactory: 7, heavyfactory: 6,
    factory: [4, 2], staff: 5 });
  const missing = api.check('technologies', 'attack_tech', 7).missing;
  assert.deepEqual(Array.from(missing, item => [item.id, item.required, item.actual]),
    [['heavyfactory', 7, 6], ['factory', 5, 4]]);
});

test('demolition of a single building lowers the displayed available level', () => {
  const api = environment({ lab: 1, radar: 1 },
    [{ id: 'radar', slot: null, targetLevel: 0 }]);
  const missing = api.check('technologies', 'recon_level', 1).missing;
  assert.equal(missing.length, 1);
  assert.equal(missing[0].id, 'radar');
  assert.equal(missing[0].actual, 0);
});

test('laboratory level 10 no longer requires a coastal port', () => {
  const buildings = { command: 10, refinery: 9, depot: 5, staff: 7 };
  assert.equal(environment(buildings, [], 0).check('buildings', 'lab', 10)
    .missing.some(item => item.id === 'port'), false);
});

test('fire density research requires local firepower facilities and late staff levels', () => {
  assert.equal(environment({ lab: 3, factory: 1, academy: 1, raremine: 1 })
    .check('technologies', 'fire_density', 1).missing.length, 0);
  const missing = environment({ lab: 10, factory: 10, academy: 8, raremine: 8, staff: 7 })
    .check('technologies', 'fire_density', 10).missing;
  assert.deepEqual(Array.from(missing, m => [m.id, m.required, m.actual]), [['staff', 8, 7]]);
});

test('兵种门槛只采用服务端快照，待同步时保持锁定并导航到对应建设或研究页', () => {
  const snapshot = {
    unitId: 'rocket', ruleVersion: 'unit-unlock-20261020-v4', eligible: false,
    requirements: [
      { kind: 'building', id: 'factory', name: '军工厂', required: 9, current: 9, scope: 'city', met: true },
      { kind: 'technology', id: 'air_engine', name: '喷气推进', required: 7, current: 6, scope: 'empire', met: false },
      { kind: 'geography', id: 'coastal_or_legacy_naval', name: '沿海资格', required: 1, current: 0, scope: 'city', met: false }
    ],
    missing: []
  };
  const api = environment({}, [], 0, { rocket: snapshot });
  assert.equal(api.checkRecruitment('rocket'), snapshot);
  const html = api.recruitmentHtml('rocket');
  assert.match(html, /军工厂/);
  assert.match(html, /Lv\.9 \/ Lv\.9/);
  assert.match(html, /前往研究/);
  assert.match(html, /选择城市/);
  assert.deepEqual(api.calls, []);
  api.goToRequirement('technology', 'air_engine', 'empire', { target: { closest: () => null } });
  api.goToRequirement('building', 'factory', 'city');
  api.goToRequirement('geography', 'coastal_or_legacy_naval', 'city');
  assert.deepEqual(api.calls, [['go', 'tech'], ['tab', 'air'], ['go', 'buildArmy'], ['cities']]);
  assert.equal(environment({}).recruitmentHtml('rocket'), '<div class="desc">兵种条件待同步</div>');
});

test('从装备解锁条件跳转时关闭抽屉，存在草稿或请求时沿用原有保护', () => {
  let busy = false, unsaved = false; const closes = [];
  const api = environment({}, [], 0, {}, {
    isBusy: () => busy, hasUnsaved: () => unsaved, close: force => closes.push(force)
  });
  api.goToRequirement('technology', 'air_engine', 'empire');
  assert.deepEqual(closes, [true]);
  assert.deepEqual(api.calls, [['go', 'tech'], ['tab', 'air']]);
  busy = true; api.goToRequirement('building', 'factory', 'city');
  assert.equal(closes.length, 1); assert.equal(api.calls.length, 2);
  busy = false; unsaved = true; api.goToRequirement('building', 'factory', 'city');
  assert.deepEqual(closes, [true, false]); assert.equal(api.calls.length, 2);
});
