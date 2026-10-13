const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const frontend = path.join(root, 'frontend');
const css = [...fs.readFileSync(path.join(frontend, 'index.html'), 'utf8').matchAll(/href="(css\/[^"?]+)(?:\?[^"]*)?"/g)].map(match => match[1]);
const checks = [], errors = [], baselineLayout = [];
const scripts = ['constants.js', 'data.js', 'icons.js', 'api-client.js', 'api.js', 'core.js', 'world-view.js', 'world.js',
  'dispatch-route.js', 'main-quest.js', 'map-camera.js', 'map-chunks.js', 'map-layout.js', 'world-map.js'];

// 只提供本地静态素材，游戏请求由页面中的模拟客户端处理。
const server = http.createServer((request, response) => {
  const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
  if (pathname === '/frontend/fixture') {
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end('<!DOCTYPE html><html lang="zh-CN" data-theme="paper"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
      css.map(file => '<link rel="stylesheet" href="' + file + '">').join('') +
      '</head><body><div id="screen"><div id="header"><div id="topbar">讨伐出征 · 本地验证</div><div id="navbar"></div></div><div id="view"></div></div><div id="toast"></div></body></html>');
    return;
  }
  const file = path.resolve(root, '.' + pathname);
  if (!file.startsWith(frontend + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404); response.end(); return;
  }
  const types = { '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png', '.webp': 'image/webp', '.svg': 'image/svg+xml' };
  response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
  fs.createReadStream(file).pipe(response);
});

(async () => {
  let browser;
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('http://127.0.0.1:' + server.address().port + '/frontend/fixture');
    for (const script of scripts) await page.addScriptTag({ path: path.join(frontend, 'js', script) });
    await page.evaluate(() => {
      const G = Game;
      window.fixture = { routes: [], requests: [], messages: [], held: [], hold: false, failDispatch: false, failRoute: false };
      const state = {
        player: { id: 1, activeCityId: 1, citySlot: 0, cityName: '河畔城', username: '本地验证', militaryRank: 'sergeant', prestige: 20000 },
        resources: { food: 10000, steel: 10000, oil: 10000, rare: 10000, gold: 10000 },
        army: { infantry: 1000, truck: 30 }, tech: {}, buildings: {}, reports: [],
        officers: [{ id: 7, name: '林峰', role: 'idle', military: 80, logistics: 60, knowledge: 40, star: 3, level: 10 },
          { id: 8, name: '周衡', role: 'idle', military: 70, logistics: 65, knowledge: 40, star: 3, level: 8 }],
        world: { cityPos: { x: 10, y: 10 }, pos: { x: 10, y: 10 }, marches: [], incoming: [],
          wildTiles: [{ id: 11, type: 'ironworks', level: 5, x: 12, y: 12, totalRes: 10000, mined: 0, occupied: false },
            { id: 12, type: 'mount', level: 2, x: 11, y: 12, totalRes: 0, mined: 0, occupied: false }],
          npcCities: [{ id: 22, name: '日军据点', level: 3, x: 13, y: 13 }], bandits: [],
          playerCities: [{ id: 33, name: '敌军城池', x: 14, y: 14, guildRelation: 'hostile' }] }
      };
      G.state = state; G.Core.state = state;
      // 使用真实导航和页面渲染；省略账号顶栏及地图画布的初始化。
      G.Core.renderTop = function () {};
      G.Core.armyCap = () => 25000;
      G.toast = message => fixture.messages.push(message);
      G.WorldMap.render = view => { view.textContent = '地图画布省略'; };
      G.API.client.getToken = () => 'local-fixture';
      G.API.getWorldView = () => Promise.resolve({ ...G.Core.state.world });
      G.API.client.get = () => Promise.resolve(null);
      G.API.client.post = (url, body) => {
        if (url === '/game/world/route') {
          fixture.routes.push(body);
          const route = { points: [[10, 10], [12, 12]], distance: 2.83, seconds: 30, mode: 'land', oil: 20, cargoLimit: 1000, unitEquipmentVersion: body.action + '-fixture' };
          if (fixture.hold) return new Promise((resolve, reject) => fixture.held.push({ resolve: () => resolve(route), reject }));
          if (fixture.failRoute) return Promise.reject(new Error('模拟路线失败'));
          return Promise.resolve(route);
        }
        if (url === '/game/world/dispatch') {
          fixture.requests.push(body);
          if (fixture.failDispatch) return Promise.reject(new Error('模拟出征失败'));
          const next = structuredClone(G.Core.state);
          Object.keys(next.world).filter(key => key.startsWith('_')).forEach(key => delete next.world[key]);
          return Promise.resolve({ state: next });
        }
        throw new Error('Unexpected fixture request: ' + url);
      };
      G.World.attackWild(0);
    });

    await page.locator('#dqty_infantry').fill('120');
    await page.locator('#dqty_infantry').press('Tab');
    await page.locator('#dqty_truck').fill('10');
    await page.locator('#dqty_truck').press('Tab');
    await page.locator('#dcarry_food').fill('70');
    await page.locator('[name="dpOfficer"][value="8"]').check();
    await page.locator('[name="dpBattleMode"][value="manual"]').check();
    await page.locator('[name="dpConquerMode"][value="occupy"]').check();
    await page.waitForFunction(() => !Game.World._dispatchRoutePending && Game.World._currentRoute);
    const inputs = () => page.evaluate(() => ({ troops: document.querySelector('#dqty_infantry').value,
      truck: document.querySelector('#dqty_truck').value, food: document.querySelector('#dcarry_food').value,
      officer: document.querySelector('[name="dpOfficer"]:checked').value,
      battle: document.querySelector('[name="dpBattleMode"]:checked').value }));
    const before = await inputs();
    assert.deepEqual(before, { troops: '120', truck: '10', food: '70', officer: '8', battle: 'manual' });

    for (const theme of ['paper', 'dark', 'blue-white', 'blue-white-classic']) {
      await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
      for (const width of [1280, 320]) {
        await page.setViewportSize({ width, height: 900 });
        await page.locator('#dpOccupyTarget').uncheck();
        assert.equal(await page.locator('#dpCampaignTroops').isVisible(), false);
        assert.deepEqual(await inputs(), before);
        assert.match(await page.locator('#dpCampaignSummary').innerText(), /仅夺取资源.*自动返城/);
        await page.waitForFunction(() => !Game.World._dispatchRoutePending && Game.World._currentRoute);
        assert.equal(await page.evaluate(() => fixture.routes.at(-1).action), 'plunder');
        if (theme === 'paper' && width === 320) await page.screenshot({ path: path.join(__dirname, 'mobile-resource-only.png') });
        await page.locator('#dpOccupyTarget').check();
        assert.equal(await page.locator('#dpCampaignTroops').isVisible(), true);
        assert.equal(await page.locator('[name="dpConquerMode"][value="occupy"]').isChecked(), true);
        assert.deepEqual(await inputs(), before);
        await page.waitForFunction(() => !Game.World._dispatchRoutePending && Game.World._currentRoute);
        const dimensions = await page.evaluate(() => {
          const field = document.querySelector('.dispatch-gather-modes'), toggle = document.querySelector('#dpOccupyTarget');
          const rect = field.getBoundingClientRect().toJSON(), toggleRect = toggle.getBoundingClientRect().toJSON();
          const scrollWidth = document.documentElement.scrollWidth;
          // 隐藏新增配置后复测，区分本次区域与原兵力输入区已有的窄屏溢出。
          field.hidden = true;
          const baselineScrollWidth = document.documentElement.scrollWidth;
          field.hidden = false;
          return { field: rect, toggle: toggleRect, scrollWidth, baselineScrollWidth,
            width: innerWidth, overflow: scrollWidth > innerWidth,
            wide: [...document.querySelectorAll('#view *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1)
              .map(el => ({ tag: el.tagName, id: el.id, className: el.className, right: el.getBoundingClientRect().right })) };
        });
        if (dimensions.overflow) baselineLayout.push({ theme, width, dimensions });
        assert.ok(dimensions.scrollWidth <= dimensions.baselineScrollWidth, theme + '/' + width);
        assert.ok(dimensions.field.left >= 0 && dimensions.field.right <= width, theme + '/' + width);
        assert.equal(dimensions.toggle.width, 40);
        assert.match(await page.locator('#dpCampaignSummary').innerText(), /尝试占领.*原地驻守/);
        checks.push({ scenario: 'toggle retains all fields, preview action and responsive bounds', theme, width, passed: true });
        if (theme === 'paper' && width === 1280) await page.screenshot({ path: path.join(__dirname, 'desktop-occupation.png') });
        if (theme === 'dark' && width === 320) await page.screenshot({ path: path.join(__dirname, 'mobile-dark-occupation.png') });
      }
    }

    await page.evaluate(() => { fixture.hold = true; });
    await page.locator('#dpOccupyTarget').uncheck();
    await page.waitForFunction(() => fixture.held.length === 1);
    await page.locator('#dpOccupyTarget').check();
    await page.waitForFunction(() => fixture.held.length === 2);
    await page.evaluate(() => { fixture.held[0].resolve(); Game.World.launchDispatch(); });
    assert.equal(await page.evaluate(() => fixture.requests.length), 0);
    assert.equal(await page.evaluate(() => Game.World._currentRoute), null);
    await page.evaluate(() => { fixture.held[1].resolve(); fixture.hold = false; });
    await page.waitForFunction(() => !Game.World._dispatchRoutePending && Game.World._currentRoute);
    assert.equal(await page.evaluate(() => Game.World._currentRoute.unitEquipmentVersion), 'conquer-fixture');
    checks.push({ scenario: 'stale route response rejected and submit waits for current mode', passed: true });

    await page.evaluate(() => { fixture.failDispatch = true; Game.World.launchDispatch(); });
    await page.waitForFunction(() => !Game.World._launching);
    assert.deepEqual(await inputs(), before);
    assert.equal(await page.evaluate(() => Game.Core.state.world._dispatchTarget.occupyTarget), true);
    await page.evaluate(() => { fixture.failDispatch = false; Game.World.launchDispatch(); });
    await page.waitForFunction(() => Game.Core.route === 'world' && !Game.World._launching);
    const launched = await page.evaluate(() => fixture.requests.at(-1));
    assert.equal(launched.action, 'conquer'); assert.equal(launched.conquerMode, 'occupy');
    assert.equal(launched.commanderId, 8); assert.equal(launched.carryRes.food, 70);
    assert.equal(launched.battleMode, 'manual'); assert.equal(launched.army.infantry, 120);
    assert.equal(await page.evaluate(() => Game.Core.state.world._dispatchTarget), undefined);
    checks.push({ scenario: 'failure preserves DOM and successful API state replacement clears draft', passed: true });

    await page.evaluate(() => {
      Game.MainQuest.state.chapters = [{ quests: [{ id: 'q2_4', status: 'in_progress' }, { id: 'q4_4', status: 'in_progress' }] }];
      Game.MainQuest.gotoCampaign('q2_4');
    });
    assert.equal(await page.evaluate(() => Game.WorldMap.isMap()), false);
    assert.equal(await page.evaluate(() => Game.Core.state.world._activeTab), 'wild');
    await page.locator('[data-target-key="wild:11"] button[aria-expanded]').click();
    await page.locator('[data-target-key="wild:11"]').locator('button', { hasText: /^讨伐$/ }).click();
    assert.equal(await page.locator('#dpOccupyTarget').isChecked(), false);
    await page.locator('#dqty_infantry').fill('50');
    await page.locator('#dqty_infantry').press('Tab');
    await page.waitForFunction(() => !Game.World._dispatchRoutePending && Game.World._currentRoute);
    await page.evaluate(() => Game.World.launchDispatch());
    await page.waitForFunction(() => Game.Core.route === 'world' && !Game.World._launching);
    assert.equal(await page.evaluate(() => fixture.requests.at(-1).action), 'plunder');
    assert.equal(await page.evaluate(() => fixture.requests.at(-1).conquerMode), 'withdraw');
    await page.evaluate(() => Game.MainQuest.gotoCampaign('q4_4'));
    assert.equal(await page.evaluate(() => Game.Core.state.world._activeTab), 'player');
    await page.locator('[data-player-id="33"] .tcard-mini-row').click();
    await page.locator('[data-player-id="33"]').locator('button', { hasText: /^讨伐$/ }).click();
    assert.equal(await page.locator('#dpOccupyTarget').isChecked(), true);
    assert.match(await page.locator('#dpCampaignDescription').innerText(), /战后民心为0/);
    checks.push({ scenario: 'actual Core/WorldMap quest navigation preserves tab and selects correct settlement mode', passed: true });

    await page.evaluate(() => Game.World.attackWild(1));
    assert.equal(await page.locator('#dpOccupyTarget').isChecked(), true);
    assert.equal(await page.locator('#dpOccupyTarget').isDisabled(), true);
    await page.evaluate(() => Game.World.attack('npc', 0));
    assert.match(await page.locator('#dpCampaignDescription').innerText(), /无法保留为我方领地/);
    assert.equal(await page.locator('[name="dpConquerMode"]').count(), 0);
    await page.locator('#dpOccupyTarget').uncheck();
    assert.match(await page.locator('#dpCampaignDescription').innerText(), /不接管城市，也不俘虏指挥官/);
    checks.push({ scenario: 'resource-less wild forces occupation and NPC explains no ownership', passed: true });
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(__dirname, 'checks.json'), JSON.stringify({ browser: await browser.version(), requestMode: 'fixture only', checks, errors, baselineLayout }, null, 2) + '\n');
    console.log(JSON.stringify({ checks: checks.length, errors, result: 'passed' }));
  } finally {
    if (browser) await browser.close();
    server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
