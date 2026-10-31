const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

const rules = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8'));

function environment(buildings, constructions, coastalPortLevel, unitUnlocks) {
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
  context.window.document = context.document;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  const api = context.window.Game.Prerequisites;
  api.setRules(rules);
  api.calls = calls;
  return api;
}

test('building and technology eligibility reads evaluated server requirements only', () => {
  const source = fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8');
  const missing = { id: 'hospital', name: '战地医院', required: 8, actual: 7, scope: 'city' };
  const quote = { requirements: [missing], missing: [missing] };
  const state = { buildings: { hospital: 10 }, constructions: [], gameplay: { prerequisites: { buildings: {}, technologies: { log_medical: [quote] } } } };
  const c = { window: { Game: { Core: { state } } } };
  vm.runInNewContext(source, c);
  const api = c.window.Game.Prerequisites;
  assert.equal(api.check('technologies', 'log_medical', 1), quote);
  assert.equal(api.firstMissing('technologies', 'log_medical', 1), missing);
  state.buildings.hospital = 0;
  assert.equal(api.check('technologies', 'log_medical', 1), quote);
  state.gameplay = {};
  assert.equal(api.check('technologies', 'log_medical', 1), null);
});

test('all evaluated levels remain accessible without rebuilding prerequisite curves', () => {
  const snapshot = require('./server-gameplay.cjs').fixture.gameplay.prerequisites;
  const c = { window: { Game: { Core: { state: { gameplay: { prerequisites: snapshot } } } } } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), c);
  for (const kind of ['buildings', 'technologies']) for (const [id, levels] of Object.entries(snapshot[kind])) {
    levels.forEach((quote, level) => assert.equal(c.window.Game.Prerequisites.check(kind, id, level + 1), quote));
  }
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
