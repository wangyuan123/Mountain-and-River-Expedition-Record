const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function render(intel) {
  const game = {
    DATA: { units: { infantry: { name: '步兵' }, fighter: { name: '战斗机' } }, techs: { attack_tech: { name: '攻击科技' } }, officerSkills: {} },
    Core: { views: {}, state: { player: {}, unitEquipment: { catalog: { infantry_helmet: { name: '钢盔' } } }, world: {
      marches: [], reinforcements: [], cityPos: { x: 1, y: 2 }, incoming: [{ id: 'march-1', marchId: 1, arriveAt: 1, targetName: '本城', expanded: true, ...intel }]
    } } },
    MapChunks: function () {}, fmt: String, escapeHtml: value => String(value).replace(/</g, '&lt;'),
    API: {}, WorldView: {}
  };
  const context = vm.createContext({ Game: game, Date, localStorage: { getItem: () => null } });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  const view = {};
  game.World.renderAlerts(view);
  return view.innerHTML;
}

test('零级雷达保持未知来源、目标与迎战入口，缺少字段不显示undefined', () => {
  const html = render({ radarLevel: 0 });
  assert.match(html, /未知敌军 来袭/);
  assert.match(html, /本城/);
  assert.match(html, /进入战斗/);
  assert.doesNotMatch(html, /undefined|总兵力:|统帅:|战斗科技:/);
});

test('三级雷达展示兵种与总数，不展示各兵种编成和高级情报', () => {
  const html = render({ radarLevel: 3, fromName: '敌方', branches: ['land', 'air'], strengthBand: '101—500',
    unitTypes: ['infantry', 'fighter'], totalTroops: 200 });
  assert.match(html, /步兵、战斗机/);
  assert.match(html, /总兵力: 200/);
  assert.doesNotMatch(html, /步兵 ×|统帅:|战斗科技:|已配装装备:/);
});

test('五级雷达按服务端对象目录解析装备并显示本队快照', () => {
  const html = render({ radarLevel: 5, army: { infantry: 150 }, commander: '统帅甲', commanderStar: 3, commanderLevel: 8,
    combatTech: { attack_tech: 10 }, unitEquipment: { '@ue:infantry:item:infantry_helmet': 1 },
    commanderAttributes: { military: 80, defense: 70 }, skills: {},
    commandBonus: { name: '司令甲', military: 50, defense: 40, logistics: 30, knowledge: 20 },
    intelligenceSource: '战斗属性为开战快照' });
  assert.match(html, /步兵 ×150/);
  assert.match(html, /统帅甲 3星 Lv.8/);
  assert.match(html, /攻击科技 Lv.10/);
  assert.match(html, /已配装装备: 钢盔/);
  assert.match(html, /军事 80 \/ 防御 70/);
  assert.match(html, /战斗属性为开战快照/);
  assert.doesNotMatch(html, /undefined/);
});
