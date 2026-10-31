const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

// 按服务端已授衔阶数渲染资料，验证新门槛进度及老账号展示，不模拟自动授衔。
function profile(tier, prestige) {
  let drawer;
  const context = vm.createContext({ console, requestAnimationFrame() {} });
  context.window = context;
  context.document = {
    createElement: () => ({ setAttribute() {}, querySelector() { return null; } }),
    getElementById: () => null,
    body: { appendChild(element) { drawer = element; } }
  };
  context.Game = {
    Core: { state: { player: { militaryRank: tier }, prestige, resources: {}, world: {} } },
    MainView: { getCurrentAvatar: () => 'avatar.svg' },
    WS: { statusHtml: () => '' },
    fmt: String, escapeHtml: value => value
  };
  for (const file of ['constants.js', 'data.js', 'player-profile.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.PlayerProfile.openPlayerDrawer();
  return { html: drawer.innerHTML, game: context.Game };
}

test('全部军衔声望及累计胜场门槛与设计、服务端和文档一致', () => {
  const expected = [
    ['列兵', 0, 0], ['上等兵', 10000, 10], ['下士', 100000, 50], ['中士', 300000, 110],
    ['上士', 500000, 200], ['军士长', 1000000, 300], ['准尉', 1500000, 450], ['少尉', 2000000, 650],
    ['中尉', 2600000, 900], ['上尉', 3300000, 1200], ['少校', 4100000, 1600], ['中校', 5000000, 2100],
    ['上校', 6000000, 2700], ['大校', 7000000, 3400], ['少将', 8000000, 4200], ['中将', 9000000, 5100],
    ['上将', 10000000, 6100]
  ];
  const { game } = profile(1, 0);
  const frontend = Array.from(game.DATA.militaryRanks, rank => [rank.name, rank.prestige, rank.npcVictories]);
  assert.deepEqual(frontend, expected);

  // 服务端负责授衔、前端负责进度展示；两份配置必须使用相同累计门槛。
  const backend = fs.readFileSync(path.join(__dirname,
    '../../backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java'), 'utf8');
  const serverRanks = Array.from(backend.matchAll(/new RankInfo\(\s*(\d+),\s*"([^"]+)",\s*(\d+),\s*\d+,\s*(\d+),/g),
    match => [Number(match[1]), match[2], Number(match[3]), Number(match[4])]);
  assert.deepEqual(serverRanks, expected.map(([name, prestige, victories], index) => [index + 1, name, prestige, victories]));

  const design = fs.readFileSync(path.join(__dirname, '../../docs/RANK_PROMOTION.md'), 'utf8');
  expected.slice(1).forEach(([name, prestige, victories], index) => {
    assert.ok(design.includes('| ' + (index + 2) + ' | ' + name + ' | '
      + prestige.toLocaleString('en-US') + ' | ' + victories.toLocaleString('en-US') + ' |'),
    name + '的设计门槛应与代码一致');
    assert.equal(game.getMilitaryRankTierInfo(index + 1).reqNpcVictories, victories);
  });
  assert.equal(game.getMilitaryRankTierInfo(17).reqNpcVictories, null);
});

test('各阶均按下一阶新门槛显示进度，声望达标不会自动授衔', () => {
  const { game } = profile(1, 0);
  for (const target of game.DATA.militaryRanks.slice(1)) {
    const currentTier = target.tier - 1;
    const below = profile(currentTier, target.prestige - 1);
    assert.match(below.html, /还需 1 声望/);
    assert.doesNotMatch(below.html, /声望已达标/);
    assert.equal(below.game.getMilitaryRankTierInfo(currentTier).nextPrestige, target.prestige);
    const reached = profile(currentTier, target.prestige);
    assert.match(reached.html, /声望已达标，查看晋升条件/);
    assert.equal(reached.game.Core.state.player.militaryRank, currentTier);
  }
});

test('上将保持最高军衔，旧账号声望不足也不显示下一阶', () => {
  for (const prestige of [2500000, 10000000]) {
    const { html, game } = profile(17, prestige);
    assert.match(html, /已达最高军衔/);
    assert.doesNotMatch(html, /下一军衔|还需 .* 声望/);
    assert.equal(game.Core.state.player.militaryRank, 17);
    assert.equal(game.getMilitaryRankTierInfo(17).nextPrestige, null);
  }
});

test('列兵按一万声望显示晋升进度，达标后提示查看其他晋升条件', () => {
  const below = profile(1, 9999);
  assert.match(below.html, /还需 1 声望/);
  assert.doesNotMatch(below.html, /声望已达标/);
  const reached = profile(1, 10000);
  assert.match(reached.html, /声望已达标，查看晋升条件/);
  assert.equal(reached.game.Core.state.player.militaryRank, 1);
  assert.equal(reached.game.getMilitaryRankTierInfo(1).nextPrestige, 10000);
});

test('旧少将保留军衔，按九百万门槛显示距中将剩余声望', () => {
  const { html, game } = profile(15, 1200000);
  assert.match(html, /少将/);
  assert.match(html, /下一军衔 · <b>中将/);
  assert.match(html, /还需 7800000 声望/);
  assert.equal(game.Core.state.player.militaryRank, 15);
  assert.equal(game.getMilitaryRankTierInfo(16).nextPrestige, 10000000);
});
