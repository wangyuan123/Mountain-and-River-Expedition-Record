const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(player = {}) {
  const elements = {};
  const document = { getElementById: id => elements[id] || null };
  const trigger = { isConnected: true, focus() { document.activeElement = this; } };
  document.activeElement = trigger;
  document.createElement = () => {
    const close = { focus() { document.activeElement = this; } };
    const done = { focus() { document.activeElement = this; } };
    return {
      attributes: {}, classList: { add() {}, remove() {} },
      setAttribute(key, value) { this.attributes[key] = value; },
      querySelector(selector) {
        return selector === '.rank-cap-close' ? close : selector === '.rank-cap-done' ? done : null;
      }
    };
  };
  document.body = {
    classList: { add() {}, remove() {} },
    appendChild(element) { elements[element.id] = element; element.parentNode = this; },
    removeChild(element) { delete elements[element.id]; element.parentNode = null; }
  };
  const context = vm.createContext({ document, requestAnimationFrame() {}, console });
  context.window = context;
  context.Game = {
    Core: { state: { player: { militaryRank: 1, ...player }, resources: {}, world: {} }, armyCap: () => 600000 },
    MainView: { getCurrentAvatar: () => 'avatar.svg' }, WS: { statusHtml: () => '' },
    fmt: value => String(Math.floor(value)),
    escapeHtml: value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
  };
  for (const file of ['constants.js', 'data.js', 'player-profile.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  }
  context.Game.Main = context.Game.PlayerProfile;
  return { context, G: context.Game, document, elements, trigger };
}

test('两项战力与带兵上限使用同一强调色，按钮打开对应用途说明且保留1/1000显示', () => {
  const { context, G, elements } = setup({ formationPower: 1239000000, overallPower: 4173000000 });
  G.PlayerProfile.openPlayerDrawer();
  const drawer = elements.playerDrawerMask;
  for (const [metric, name, formatted] of [
    ['formationPower', '编队战力', '123.9万'],
    ['overallPower', '综合实力', '417.3万']
  ]) {
    const button = [...drawer.innerHTML.matchAll(/<button\b[^>]*>[\s\S]*?<\/button>/g)]
      .map(match => match[0]).find(html => html.includes('showPowerInfo(\'' + metric + '\')'));
    assert.ok(button);
    assert.ok(button.includes('type="button"'));
    assert.ok(button.includes('aria-label="查看' + name + '用途说明"'));
    assert.ok(button.includes('aria-haspopup="dialog"'));
    assert.ok(button.includes('class="stat-v highlight"'));
    assert.ok(button.includes(formatted));
    vm.runInContext(button.match(/onclick="([^"]+)"/)[1], context);
    const mask = elements.playerPowerInfoMask;
    assert.ok(mask.innerHTML.includes(name + '用途说明'));
    assert.ok(mask.innerHTML.includes(name + '榜'));
    assert.doesNotMatch(mask.innerHTML, /计算逻辑|计算公式|当前换算|服务端原始估值|÷|√/);
    assert.equal(mask.attributes.role, 'dialog');
    assert.equal(mask.attributes['aria-modal'], 'true');
    assert.equal(mask.attributes['aria-labelledby'], 'playerPowerInfoTitle');
    assert.ok(elements.playerDrawerMask);
    mask.querySelector('.rank-cap-done').onclick();
  }
  assert.equal(G.Core.state.player.formationPower, 1239000000);
  assert.equal(G.Core.state.player.overallPower, 4173000000);
});

test('弹窗仅展示各自实力参考、排行榜与战争保护用途，不展示计算逻辑', () => {
  const { G, elements } = setup({ formationPower: 100000, overallPower: 200000 });
  G.PlayerProfile.showPowerInfo('formationPower');
  let mask = elements.playerPowerInfoMask;
  for (const text of ['最强单支现有编队', '出征前参考', '编队战力榜', '玩家城市战争保护', '不会直接增加攻击']) {
    assert.ok(mask.innerHTML.includes(text), text);
  }
  assert.doesNotMatch(mask.innerHTML, /综合实力榜|计算逻辑|单兵价值|建筑每级|技能等级|当前换算|原始估值|1000|√|÷/);
  mask.querySelector('.rank-cap-done').onclick();
  G.PlayerProfile.showPowerInfo('overallPower');
  mask = elements.playerPowerInfoMask;
  for (const text of ['全账号', '总体发展', '账号成长', '综合实力榜', '玩家城市战争保护', '不代表实际胜率']) {
    assert.ok(mask.innerHTML.includes(text), text);
  }
  assert.doesNotMatch(mask.innerHTML, /编队战力榜|计算逻辑|单兵价值|建筑每级|科技每级|技能等级|当前换算|原始估值|1000|√|÷/);
});

test('说明弹窗阻止重复打开、限制键盘焦点，支持Escape、遮罩及按钮关闭并恢复焦点', () => {
  const { G, document, elements, trigger } = setup();
  G.PlayerProfile.showPowerInfo('formationPower');
  const mask = elements.playerPowerInfoMask;
  const close = mask.querySelector('.rank-cap-close');
  const done = mask.querySelector('.rank-cap-done');
  assert.equal(document.activeElement, close);
  G.PlayerProfile.showPowerInfo('overallPower');
  assert.equal(elements.playerPowerInfoMask, mask);
  let prevented = 0;
  mask.onkeydown({ key: 'Tab', preventDefault() { prevented++; } });
  assert.equal(document.activeElement, done);
  mask.onkeydown({ key: 'Tab', shiftKey: true, preventDefault() { prevented++; } });
  assert.equal(document.activeElement, close);
  assert.equal(prevented, 2);
  mask.onclick({ target: close });
  assert.equal(elements.playerPowerInfoMask, mask);
  let stopped = false;
  mask.onkeydown({ key: 'Escape', stopPropagation() { stopped = true; } });
  assert.ok(stopped);
  assert.equal(elements.playerPowerInfoMask, undefined);
  assert.equal(document.activeElement, trigger);
  for (const method of ['close', 'done', 'backdrop']) {
    G.PlayerProfile.showPowerInfo('formationPower');
    const current = elements.playerPowerInfoMask;
    if (method === 'backdrop') current.onclick({ target: current });
    else current.querySelector(method === 'close' ? '.rank-cap-close' : '.rank-cap-done').onclick();
    assert.equal(elements.playerPowerInfoMask, undefined);
    assert.equal(document.activeElement, trigger);
  }
});

test('无战力或零战力仍可查看用途，未知属性不创建弹窗', () => {
  const { G, elements } = setup();
  G.PlayerProfile.showPowerInfo('prestige');
  assert.equal(elements.playerPowerInfoMask, undefined);
  G.PlayerProfile.showPowerInfo('formationPower');
  let mask = elements.playerPowerInfoMask;
  const usage = mask.innerHTML;
  assert.match(usage, /编队战力用途说明/);
  assert.doesNotMatch(mask.innerHTML, /NaN|undefined/);
  mask.querySelector('.rank-cap-done').onclick();
  G.Core.state.player.formationPower = 0;
  G.PlayerProfile.showPowerInfo('formationPower');
  mask = elements.playerPowerInfoMask;
  assert.equal(mask.innerHTML, usage);
});
