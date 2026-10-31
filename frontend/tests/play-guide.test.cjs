const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

function setup() {
  let time = 1_800_000_000_000, root = null;
  const calls = [], nodes = {}, messages = [];
  const g = { Core: { state: { player: { id: 1, activeCityId: 11, cityName: '一城' },
    resources: { food: 500 }, items: { shield: 5, marchOrd: 5, basicMarchOrd: 5, advancedMarchOrd: 5 }, cityState: {}, officers: [] }, views: {}, render() { calls.push('render'); } },
    gameNow: () => time, escapeHtml: v => String(v).replaceAll('<', '&lt;').replaceAll('>', '&gt;'), fmt: String,
    formatDuration: seconds => seconds + '秒', toast: value => messages.push(value),
    API: { getToken: () => 'token', depotUse: async id => { calls.push(id); return { success: true }; } } };
  const document = {
    activeElement: { focus() {} }, getElementById: id => root && root.id === id ? root : null,
    body: { appendChild(node) { root = node; } },
    createElement() {
      return { innerHTML: '', querySelector(selector) { return nodes[selector] || (nodes[selector] = { focus() {}, disabled: false }); },
        querySelectorAll() { return []; }, remove() { root = null; } };
    }
  };
  const context = vm.createContext({ window: { Game: g }, document, console });
  for (const file of ['constants', 'data', 'play-guide']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/' + file + '.js'), 'utf8'), context);
  g.ServerRules.preview = async intent => { return g._quote; };
  g._quote = {end:time+28800000,added:28800000,text:'服务器护盾报价',key:'shield:0',blocked:false};
  g.gameNow = () => time;
  g.formatDuration = seconds => seconds + '秒';
  return { g, calls, messages, nodes, document, context, get root() { return root; }, advance(ms) { time += ms; }, get time() { return time; } };
}
const flush = () => new Promise(setImmediate);

test('timed preview returns backend quote unchanged despite forged local effects', async () => {
  const f=setup(), intents=[];
  f.g.Core.state.cityState={shieldUntil:f.time+999999999,marchBoostPercent:99};
  const quote={end:1800036000000,added:123456,text:'服务端限制',blocked:true,key:'authoritative'};
  f.g.ServerRules.preview=async intent=>{intents.push(intent);return quote;};
  assert.equal(await f.g.PlayGuide.timedPreview('shield'),quote);
  assert.deepEqual({...intents[0]},{action:'timedItem',id:'shield'});
  assert.equal(f.calls.length,0);
});

test('opening and cancelling timed items never consumes inventory; confirmation remains usable as clock advances', async () => {
  const f = setup();
  await f.g.PlayGuide.openTimedItem('shield');
  assert.match(f.root.innerHTML, /使用城市.*一城/);
  assert.equal(f.calls.length, 0);
  f.nodes['[data-guide-close]'].onclick();
  assert.equal(f.root, null);
  await f.g.PlayGuide.openTimedItem('shield');
  f.advance(15000);
  f.nodes['[data-guide-confirm]'].onclick();
  f.nodes['[data-guide-confirm]'].onclick();
  await flush();
  assert.deepEqual(f.calls, ['shield', 'render']);
  assert.equal(f.root, null);
});

test('timed item refuses higher-tier conflicts and stale city, account or effect snapshots', async () => {
  for (const change of [f => f.g.Core.state.player.activeCityId++, f => f.g.Core.state.player.id++, f => f.g._quote = {...f.g._quote,key:'changed'}]) {
    const f = setup(); await f.g.PlayGuide.openTimedItem('shield'); change(f);
    await f.nodes['[data-guide-confirm]'].onclick();
    assert.equal(f.calls.length, 0);
    assert.match(f.nodes['[data-guide-message]'].textContent, /已切换|已变化/);
  }
  const f = setup(); f.g.Core.state.cityState = { marchBoostUntil: f.time + 10000, marchBoostPercent: 50 };
  f.g._quote={...f.g._quote,blocked:true};
  await f.g.PlayGuide.openTimedItem('basicMarchOrd');
  assert.equal(f.nodes['[data-guide-confirm]'].disabled, true);
  f.nodes['[data-guide-confirm]'].onclick();
  assert.equal(f.calls.length, 0);
});

test('uncertain item request is not retried; failed modal stays closable and state is reloaded', async () => {
  const f = setup();
  f.g.API.depotUse = async () => { f.calls.push('attempt'); throw new Error('连接中断'); };
  f.g.API.getGameState = async () => { f.calls.push('reload'); return f.g.Core.state; };
  f.g.API.applyState = () => f.calls.push('apply');
  await await f.g.PlayGuide.openTimedItem('shield'); f.nodes['[data-guide-confirm]'].onclick(); await flush();
  f.nodes['[data-guide-confirm]'].onclick(); await flush();
  assert.deepEqual(f.calls, ['attempt', 'reload', 'apply']);
  assert.match(f.nodes['[data-guide-message]'].textContent, /避免重复消耗/);
  f.nodes['[data-guide-close]'].onclick(); assert.equal(f.root, null);
});

test('food forecast includes visiting garrisons and uses authoritative production, with no invented unlimited supply', () => {
  const f = setup();
  assert.match(f.g.PlayGuide.foodOutlook(), /等待粮账同步/);
  f.g.Core.state.foodSupply = { totalPerHour: 600, ownPerHour: 100, supportPerHour: 500, productionPerHour: 100, netPerHour:-500,estimatedDepletedAt:f.time+3600000 };
  assert.match(f.g.PlayGuide.foodOutlook(), /约 3600秒.*外来驻军耗粮 500/);
  f.g.Core.state.resources.food = 0; f.g.Core.state.foodSupply.depleted=true;
  assert.match(f.g.PlayGuide.foodOutlook(), /已缺粮/);
  Object.assign(f.g.Core.state.foodSupply,{productionPerHour:600,netPerHour:0,depleted:false,estimatedDepletedAt:null});
  assert.match(f.g.PlayGuide.foodOutlook(), /当前产量可覆盖耗粮/);
});

test('officer and combat previews render only server rows and frozen windows', () => {
  const f=setup();
  const officer={military:999999,skills:[],roleEffects:[['市长','科研速度 +15%','市长规则'],['随队','周期回合攻击 +279%','出征上限 20%'],['司令官','常驻攻击 +27.9%','所有技能均不生效']]};
  const html=f.g.PlayGuide.officerPreview(officer);
  assert.match(html,/科研速度 \+15%/);assert.match(html,/周期回合攻击 \+279%/);
  assert.match(html,/常驻攻击 \+27.9%/);assert.doesNotMatch(html,/999999/);
  assert.match(f.g.PlayGuide.officerPreview({military:100}),/等待服务器同步/);
  f.g.Battle={_activeTactical:{attackerStats:{infantry:{windowText:'第7回合窗口开启'}}}};
  assert.equal(f.g.PlayGuide.combatWindow(999,1),'第7回合窗口开启');
  assert.match(f.g.PlayGuide.combatConditions({counter:5,ranging:5}),/需受击后存活.*连续三回合/);
});

test('reward overview queries without claiming, counts only valid attachments and ignores late responses after city switch', async () => {
  const f = setup(); let resolveMail;
  f.g.MainQuest = { loadQuests: async () => {}, rewardSummary: () => ({ tasks: 2, checkin: { claimed: false } }) };
  f.g.API.listMail = () => new Promise(resolve => { resolveMail = resolve; });
  f.g.PlayGuide.openRewards(); await flush();
  resolveMail({ mails: [{ hasAttachment: true }, { hasAttachment: true, claimed: true }, { hasAttachment: true, attachmentExpired: true },
    { hasAttachment: true, attachmentExpiresAt: f.time - 1 }, { read: false }], hasMore: true }); await flush();
  const html = f.nodes['[data-guide-rewards]'].innerHTML;
  assert.match(html, /2项可领取/);
  assert.match(html, /最新一页中1封可领取/);
  assert.match(html, /还有后续邮件/);
  assert.equal(f.calls.length, 0);
  f.g.PlayGuide.openRewards(); await flush();
  const before = f.nodes['[data-guide-rewards]'].innerHTML;
  f.g.Core.state.player.activeCityId++;
  resolveMail({ mails: [{ hasAttachment: true }], hasMore: false }); await flush();
  assert.equal(f.nodes['[data-guide-rewards]'].innerHTML, before);
});
