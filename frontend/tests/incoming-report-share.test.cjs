const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const token = 'dc123456-1234-1234-1234-123456789abc';

function setup(level = 10) {
  const toasts = [];
  const nodes = new Map();
  const im = {
    id: 'march-37', marchId: 37, radarLevel: level, action: 'plunder',
    attackerName: '来袭者', targetX: 0, targetY: 2, targetName: '守方分城',
    fromX: 10, fromY: 20, branches: ['land', 'air'], strengthBand: '101—500',
    unitTypes: ['infantry', 'fighter'], army: { infantry: 150, fighter: 10 }, totalTroops: 160,
    commander: '统帅甲', commanderStar: 3, commanderLevel: 8,
    combatTech: { attack_tech: 10 }, unitEquipment: { '@ue:infantry:item:infantry_helmet': 1 },
    intelligenceSource: '装备为出征快照',
    commandBonus: { name: '司令甲', military: 50, defense: 40, logistics: 30, knowledge: 20 },
    expanded: true, arriveAt: Date.now() + 60000
  };
  const state = {
    player: { id: 2, cityName: '接收者城市', guild: { id: 88 } },
    unitEquipment: { catalog: { infantry_helmet: { name: '钢盔' } } },
    world: { incoming: [im], marches: [], reinforcements: [], cityPos: { x: 99, y: 99 } }
  };
  const game = {
    Core: { state, views: {}, route: 'chat' },
    DATA: { units: { infantry: { name: '步兵' }, fighter: { name: '战斗机' } }, techs: { attack_tech: { name: '攻击科技' } } },
    escapeHtml: value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]),
    fmt: String, gameNow: () => Date.now(), toast: text => toasts.push(text),
    API: { sharedIncomingReport: async requested => {
      assert.equal(requested, token);
      return { incoming: im, sharedAt: Date.now() - 1000, ownerName: '守城者' };
    } }
  };
  const focused = { isConnected: true, focus() { this.restored = true; } };
  const document = {
    activeElement: focused,
    getElementById: id => nodes.get(id) || null,
    querySelectorAll: () => [],
    createElement() {
      const button = { focus() { this.focused = true; } };
      return {
        innerHTML: '', button, links: [], setAttribute(name, value) { this[name] = value; },
        querySelector: selector => selector === '[data-incoming-close]' ? button : null,
        querySelectorAll(selector) {
          if (selector !== '[data-intel-coordinate]') return [];
          this.links = Array.from(this.innerHTML.matchAll(/<a [^>]*data-intel-coordinate[^>]*onclick="([^"]+)"/g), match => ({
            handler: match[1], addEventListener(type, listener) { this[type] = listener; }
          }));
          return this.links;
        },
        remove() { nodes.delete(this.id); }
      };
    },
    body: { appendChild(node) { nodes.set(node.id, node); } }
  };
  const context = vm.createContext({ Game: game, document, Date, console, localStorage: { getItem: () => null } });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  return { game, im, nodes, toasts, focused, context };
}

test('每级雷达的军团弹窗与来袭详情共用全部情报字段，不按接收者雷达升级', async () => {
  for (const action of ['plunder', 'scout']) {
    for (let level = 0; level <= 10; level++) {
      const { game, im, nodes } = setup(level);
      im.action = action;
      const view = {};
      game.World.renderAlerts(view);
      const detail = game.World.renderIncomingIntelligence(im);
      assert.ok(view.innerHTML.includes(detail));
      await game.World.viewSharedIncomingReport(token);
      const modal = nodes.get('sharedIncomingReportModal');
      assert.ok(modal, action + ' Lv.' + level);
      assert.ok(modal.innerHTML.includes(detail));
      const labels = ['来袭玩家:', '出发地:', '军种:', '兵力区间:', '兵种:', '总兵力:', '步兵 ×150', '统帅:', '战斗科技:', '司令官:'];
      labels.forEach((label, index) => assert.equal(modal.innerHTML.includes(label), level >= index + 1, action + ' Lv.' + level + ' ' + label));
      assert.match(modal.innerHTML, /目的地: <a [^>]*>\(0,2\)<\/a> 守方分城/);
      assert.match(modal.innerHTML, /分享时间:.*情报以分享时为准/);
      assert.ok(modal.innerHTML.includes('到达倒计时（分享时）:'));
      assert.doesNotMatch(modal.innerHTML, /接收者城市|startIncomingBattle|requestGuildAid|openScoutInterception|undefined/);
      assert.equal(game.Core.route, 'chat');
    }
  }
});

test('军团分享军情的坐标点击会定位分享中的目标并关闭弹窗', async () => {
  for (const [index, x, y] of [[0, 10, 20], [1, 0, 2]]) {
    const { game, nodes, context } = setup();
    const locations = [];
    game.World.locateTarget = (x, y) => locations.push({ x, y });
    await game.World.viewSharedIncomingReport(token);
    const modal = nodes.get('sharedIncomingReportModal');
    const link = modal.links[index];
    vm.runInContext('(function (event) { ' + link.handler + ' })', context)({
      preventDefault() {}, stopPropagation() {}
    });
    link.click();
    assert.deepEqual(locations, [{ x, y }]);
    assert.equal(nodes.size, 0);
  }
});

test('军情弹窗转义玩家文字，显示预计到达和历史快照状态', async () => {
  const { game, im, nodes } = setup();
  im.attackerName = '<img src=x onerror=alert(1)>';
  im.targetName = '<script>alert(1)</script>';
  im.arriveAt = Date.now() - 1000;
  im.waitingForBattle = true;
  await game.World.viewSharedIncomingReport(token);
  const html = nodes.get('sharedIncomingReportModal').innerHTML;
  assert.match(html, /&lt;img/);
  assert.match(html, /&lt;script/);
  assert.doesNotMatch(html, /<img|<script/);
  assert.match(html, /分享时状态: 候战中/);
  assert.match(html, /已过，请确认最新战况/);
});

test('关闭按钮、Escape、遮罩均能关闭弹窗，重新点击只保留一个弹窗', async () => {
  const { game, nodes, focused } = setup();
  await game.World.viewSharedIncomingReport(token);
  let modal = nodes.get('sharedIncomingReportModal');
  assert.equal(modal.button.focused, true);
  modal.onclick({ target: {} });
  assert.equal(nodes.size, 1);
  await game.World.viewSharedIncomingReport(token);
  assert.equal(nodes.size, 1);
  assert.notEqual(nodes.get('sharedIncomingReportModal'), modal);
  nodes.get('sharedIncomingReportModal').button.onclick();
  assert.equal(nodes.size, 0);
  assert.equal(focused.restored, true);
  await game.World.viewSharedIncomingReport(token);
  nodes.get('sharedIncomingReportModal').onkeydown({ key: 'Escape' });
  assert.equal(nodes.size, 0);
  await game.World.viewSharedIncomingReport(token);
  modal = nodes.get('sharedIncomingReportModal');
  modal.onclick({ target: modal });
  assert.equal(nodes.size, 0);
});

test('越权、失效和网络错误保留服务端提示，不打开空白弹窗', async () => {
  for (const message of ['仅该军团成员可查看此军情', '分享军情不存在', '网络连接失败']) {
    const { game, nodes, toasts } = setup();
    game.API.sharedIncomingReport = async () => { throw new Error(message); };
    await game.World.viewSharedIncomingReport(token);
    assert.equal(nodes.size, 0);
    assert.deepEqual(toasts, [message]);
  }
});

test('军团历史和聊天页识别严格 UUID 求援战报，伪造内容按普通文本转义', () => {
  const { game, context } = setup();
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/chat.js'), 'utf8'), context);
  const message = { id: 1, playerId: 1, username: '守城者', ts: Date.now(), content: '[军情:' + token + ']' };
  for (const render of [game.Chat.renderGuildMessageHtml, game.Chat.renderMessageHtml]) {
    assert.match(render(message), /Game.World.viewSharedIncomingReport/);
    assert.match(render(message), /求援战报 · 查看来袭军情/);
    assert.doesNotMatch(render({ ...message, content: '[军情:abc]' }), /onclick="Game.World.viewSharedIncomingReport/);
    assert.doesNotMatch(render({ ...message, content: '[军情:' + token + ']"><script>alert(1)</script>' }), /<script>|onclick="Game.World.viewSharedIncomingReport/);
  }
});
