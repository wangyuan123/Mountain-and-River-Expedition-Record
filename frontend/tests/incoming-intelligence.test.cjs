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

test('六级雷达展示兵种与总数，不展示各兵种编成和高级情报', () => {
  const html = render({ radarLevel: 6, fromName: '敌方', branches: ['land', 'air'], strengthBand: '101—500',
    unitTypes: ['infantry', 'fighter'], totalTroops: 200 });
  assert.match(html, /步兵、战斗机/);
  assert.match(html, /总兵力: 200/);
  assert.doesNotMatch(html, /步兵 ×|统帅:|战斗科技:|已配装装备:/);
});

test('野外遭遇拦截在零级与满级雷达下均只展示粗略情报，无城防操作', () => {
  for (const radarLevel of [0, 10]) {
    for (const arriveAt of [1, Date.now() + 60000]) {
      const html = render({ intelligenceType: 'field_interception', radarLevel, interceptedMarchId: 21,
        fromName: '<截击玩家>', sourceCommanderName: '<敌方统帅>', fromX: 100, fromY: 101, targetX: 50, targetY: 51, arriveAt,
        unitTypes: ['fighter', 'infantry'], strengthBand: '101—500',
        commander: '<截击将领>', commanderStar: 3, commanderLevel: 8,
        commandChief: '截击统帅', commandChiefStar: 4, commandChiefLevel: 10,
        army: { fighter: 125 }, totalTroops: 125, combatTech: { attack_tech: 10 },
        unitEquipment: {}, commandBonus: { name: '机密司令', military: 99 } });
      assert.ok(html.includes('[遭遇拦截]'));
      assert.match(html, /行军 #21/);
      assert.match(html, /野外目视与前线急报（无雷达支援）/);
      assert.match(html, /战斗机、步兵/);
      assert.match(html, /兵力区间: 101—500（目视估计）/);
      assert.match(html, /带兵将领: &lt;截击将领> 3星 Lv.8/);
      assert.match(html, /敌方统帅: &lt;敌方统帅>/);
      assert.match(html, /司令官: 截击统帅 4星 Lv.10/);
      assert.ok(html.includes('Game.World.locateTarget(100,101)'));
      assert.ok(html.includes('Game.World.locateTarget(50,51)'));
      assert.match(html, /突围获胜后沿原路线继续行进/);
      assert.doesNotMatch(html, /雷达情报等级|总兵力:|×125|兵力: 125|战斗科技:|已配装装备:|军事 99|机密司令/);
      const card = html.slice(html.indexOf('<div class="term-alert-list">'), html.indexOf('<div class="term-footer">'));
      assert.doesNotMatch(card, /startIncomingBattle|activateCityShield|openScoutInterception|requestGuildAid|开罩|进入战斗|undefined/);
    }
  }
});

test('野外急报未任命将领和零坐标可正常展示', () => {
  const html = render({ intelligenceType: 'field_interception', interceptedMarchId: 1, fromX: 0, fromY: 0,
    targetX: 0, targetY: 1, commander: '未任命', commandChief: '未任命' });
  assert.ok(html.includes('Game.World.locateTarget(0,0)'));
  assert.ok(html.includes('Game.World.locateTarget(0,1)'));
  assert.match(html, /带兵将领: 未任命/);
  assert.match(html, /司令官: 未任命/);
  assert.doesNotMatch(html, /undefined|×/);
});

test('野外急报交火坐标缺失时保持未知，不使用本城坐标替代', () => {
  const html = render({ intelligenceType: 'field_interception', interceptedMarchId: 1, targetX: null, targetY: null });
  assert.ok(html.includes('拦截交火位置: (?,?)'));
  assert.ok(!html.includes('Game.World.locateTarget(1,2)'));
});

test('野外急报推送立即加入军情，重复消息不增卡，撤回与交火结束清除对应卡', () => {
  const handlers = {};
  const toasts = [];
  const game = { state: { world: { incoming: [{ id: 'march-9' }] } },
    WS: { on: (event, handler) => { handlers[event] = handler; } }, toast: message => toasts.push(message) };
  const context = vm.createContext({ Game: game });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-handlers.js'), 'utf8'), context);
  const intel = { id: 'interception-2', intelligenceType: 'field_interception', interceptedMarchId: 7, event: 'started' };
  handlers.incoming(intel);
  assert.equal(game.state.world.incoming.length, 2);
  assert.equal(game.state.world.incoming[1].expanded, true);
  assert.match(toasts[0], /行军 #7 遭遇敌军拦截/);
  handlers.incoming(intel);
  assert.equal(game.state.world.incoming.length, 2);
  game.state.world.incoming[1].expanded = false;
  handlers.incoming({ ...intel, event: 'accelerated', arriveAt: 12345 });
  assert.equal(game.state.world.incoming[1].arriveAt, 12345);
  assert.equal(game.state.world.incoming[1].expanded, false);
  handlers.incoming({ ...intel, event: 'resolved' });
  assert.equal(game.state.world.incoming.length, 1);
  assert.equal(game.state.world.incoming[0].id, 'march-9');
  handlers.incoming(intel);
  handlers.incoming({ ...intel, event: 'cancelled' });
  assert.equal(game.state.world.incoming.length, 1);
  assert.match(toasts.at(-1), /拦截已取消/);
});

test('军情缺失或无效坐标保持文本，目的地缺省时仍可定位当前城市', () => {
  for (const fromX of [undefined, null, '', ' ', '?', '5);alert(1)', Infinity]) {
    const html = render({ radarLevel: 2, fromX, fromY: 387 });
    assert.doesNotMatch(html, /出发地: <a/);
    assert.match(html, /目的地: <a [^>]*Game.World.locateTarget\(1,2\)/);
  }
  const html = render({ radarLevel: 2, fromX: 5, targetX: '?', targetY: 106 });
  assert.match(html, /出发地: \(5,\?\)/);
  assert.match(html, /目的地: \(\?,106\)/);
  assert.doesNotMatch(html, /data-intel-coordinate/);
});

test('十级雷达显示本队科技快照并忽略历史兵种装备', () => {
  const html = render({ radarLevel: 10, army: { infantry: 150 }, commander: '统帅甲', commanderStar: 3, commanderLevel: 8,
    combatTech: { attack_tech: 10 }, unitEquipment: { '@ue:infantry:item:infantry_helmet': 1 },
    commanderAttributes: { military: 80, defense: 70 }, skills: { blitz: 5 },
    commandBonus: { name: '司令甲', military: 50, defense: 40, logistics: 30, knowledge: 20 },
    intelligenceSource: '战斗属性为开战快照' });
  assert.match(html, /步兵 ×150/);
  assert.match(html, /统帅甲 3星 Lv.8/);
  assert.match(html, /攻击科技 Lv.10/);
  assert.doesNotMatch(html, /已配装装备|钢盔/);
  assert.match(html, /司令官: 司令甲（军事 50 \/ 防御 40 \/ 后勤 30 \/ 学识 20）/);
  assert.match(html, /战斗属性为开战快照/);
  assert.doesNotMatch(html, /undefined|将领有效属性:|将领技能:|军事 80 \/ 防御 70|blitz Lv\.5/);
});


test('来袭攻击与侦查情报按雷达逐级开放，旧载荷的将领属性和技能始终不展示', () => {
  const fields = { fromName: '敌方', fromX: 9, fromY: 8, action: 'plunder', branches: ['land'], strengthBand: '101—500',
    unitTypes: ['infantry'], totalTroops: 150, army: { infantry: 150 }, commander: '统帅甲',
    combatTech: { attack_tech: 10 }, unitEquipment: {}, commanderAttributes: { military: 80, defense: 70 }, skills: { blitz: 5 }, commandBonus: {} };
  const labels = ['来袭玩家:', '出发地:', '军种:', '兵力区间:', '兵种:', '总兵力:', '步兵 ×150', '统帅:', '战斗科技:', '司令官:'];
  for (const action of ['plunder', 'scout']) {
    for (let level = 0; level <= 10; level++) {
      const html = render({ ...fields, action, radarLevel: level });
      labels.forEach((label, index) => assert.equal(html.includes(label), level >= index + 1, action + ' Lv.' + level + ' ' + label));
      assert.doesNotMatch(html, /将领有效属性:|将领技能:|军事 80 \/ 防御 70|blitz Lv\.5/);
    }
  }
});
