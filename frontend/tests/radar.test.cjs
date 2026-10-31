const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const notices = [], settings = [], flashes = [];
  const initial = { revision: 1, cities: [{ cityId: 11, level: 1, radius: 1, enabled: false, x: 100, y: 100 }], contacts: [], history: [], unread: 0 };
  const state = { player: { id: 1, activeCityId: 11 }, buildings: { radar: 10, guild_radar: 1 }, tech: { recon_radar: 0 }, world: { regionalRadar: initial, incoming: [] } };
  const game = {
    Core: { state, route: 'world', refreshTop() {} },
    Main: { renderNavBar() {} }, WorldMap: { refreshRadar(...args) { flashes.push(args); } },
    escapeHtml: value => String(value).replaceAll('<', '&lt;').replaceAll('>', '&gt;'),
    toast: text => notices.push(text),
    API: { getToken: () => 'token', setRadarEnabled: (...args) => { settings.push(args); return Promise.resolve(initial); } }
  };
  const context = vm.createContext({ window: { Game: game }, console, Date, Number, Promise, Map, Set, Math });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/radar.js'), 'utf8'), context);
  return { game, radar: game.Radar, state, initial, notices, settings, flashes };
}

function contact(id = 5, extra = {}) {
  return { id: 'radar-' + id, contactId: id, marchId: id + 100, reminderGroup: 'group', targetName: '团友城', targetX: 120, targetY: 120,
    threatenedPlayers: [{ playerId: 2, name: '团友A' }], sources: [{ cityId: 11, name: '监测城' }], threatKind: 'attack', status: 'active', read: false, ...extra };
}

test('radar switch shows the saved state without trailing level or radius information', () => {
  const { radar, initial } = setup();
  assert.match(radar.buttonHtml(), /联防雷达：已关闭/);
  assert.match(radar.buttonHtml(), /<span>雷达：关<\/span>/);
  assert.match(radar.buttonHtml(), /aria-label="联防雷达：已关闭"/);
  assert.match(radar.buttonHtml(), /aria-pressed="false"/);
  assert.doesNotMatch(radar.buttonHtml(), /data-radar-range|半径|Lv\./);
  assert.match(radar.buttonHtml(), /<\/button>$/);
  initial.cities[0].enabled = true;
  assert.match(radar.buttonHtml(), /联防雷达：已开启/);
  assert.match(radar.buttonHtml(), /<span>雷达：开<\/span>/);
  initial.cities[0].level = 0;
  assert.match(radar.buttonHtml(), /本城未建设联防雷达/);
  assert.match(radar.buttonHtml(), /<span>雷达：未建<\/span>/);
  assert.match(radar.buttonHtml(), /disabled/);
  assert.match(radar.buttonHtml(), /aria-pressed="false"/);
  assert.doesNotMatch(radar.buttonHtml(), /联防雷达Lv\./);
});

test('radar guidance explains all four upgrades and distinguishes absence of monitoring from absence of threats', () => {
  const { radar, initial } = setup();
  assert.match(radar.helpHtml(), /城防雷达.*联防雷达.*雷达预警科技.*联络中心/);
  assert.match(radar.helpHtml(), /暂无已建成且开启/);
  initial.cities[0].enabled = true; initial.joinedGuild = false;
  assert.match(radar.helpHtml(), /半径 1 格.*尚未加入军团/);
  initial.joinedGuild = true;
  assert.match(radar.helpHtml(), /暂无符合条件.*未发现预警不代表安全/);
});

test('city defense radar and recon technology cannot provide a missing regional sensor', async () => {
  const { radar, state, initial, settings } = setup();
  state.buildings.guild_radar = 0;
  state.tech.recon_radar = 10;
  for (const sensors of [[], [{ ...initial.cities[0], level: 0, radius: 0, enabled: false }]]) {
    initial.cities = sensors;
    assert.match(radar.buttonHtml(), /本城未建设联防雷达/);
    assert.match(radar.buttonHtml(), /disabled/);
    assert.doesNotMatch(radar.buttonHtml(), /联防雷达Lv\./);
    await radar.toggle();
  }
  assert.equal(settings.length, 0);
});

test('guild radar can be enabled without guild membership, city radar or recon research', async () => {
  const { radar, game, state, settings } = setup();
  state.buildings.radar = 0;
  game.API.setRadarEnabled = (...args) => {
    settings.push(args);
    return Promise.resolve({ ...radar.data(), revision: 2, cities: [{ ...radar.city(), enabled: true }] });
  };
  assert.doesNotMatch(radar.buttonHtml(), /disabled/);
  await radar.toggle();
  assert.deepEqual(settings, [[11, true]]);
  assert.match(radar.buttonHtml(), /联防雷达：已开启/);
  assert.equal(radar.city().radius, 1);
  assert.equal(radar.contacts().length, 0);
});

test('regional radius comes only from its server sensor, independent of city radar and recon research', () => {
  const { radar, state, initial } = setup();
  for (const level of [1, 5, 10]) {
    initial.cities[0].level = level;
    initial.cities[0].radius = level;
    state.buildings.guild_radar = level;
    for (const cityLevel of [0, 10]) {
      state.buildings.radar = cityLevel;
      state.tech.recon_radar = 10 - cityLevel;
      assert.equal(radar.city().radius, level);
      assert.doesNotMatch(radar.buttonHtml(), /data-radar-range|半径|Lv\./);
    }
  }
});

test('preview changes only guild radar coverage and keeps intelligence tied to recon research', async () => {
  const source = fs.readFileSync(path.join(__dirname, 'radar-preview.html'), 'utf8');
  const fixture = Array.from(source.matchAll(/<script>([\s\S]*?)<\/script>/g), match => match[1])
    .find(script => script.includes('G.RadarPreview ='));
  const context = vm.createContext({ window: { Game: {} }, document: { getElementById: () => ({ textContent: '' }) }, console });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fixture, context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/radar.js'), 'utf8'), context);
  const game = context.window.Game;
  assert.equal(game.state.buildings.radar, 10);
  assert.equal(game.state.buildings.guild_radar, 1);
  assert.equal(game.Radar.city().radius, 1);
  await game.Radar.toggle();
  for (const level of [1, 5, 10]) {
    game.RadarPreview.level(String(level));
    assert.equal(game.state.buildings.radar, 10);
    assert.equal(game.state.buildings.guild_radar, level);
    assert.equal(game.Radar.city().level, level);
    assert.equal(game.Radar.city().radius, level);
  }
  game.RadarPreview.threat();
  assert.equal(game.Radar.contacts()[0].radarLevel, game.state.tech.recon_radar);
  assert.equal(game.Radar.contacts()[0].radarLevel, 0);
});

test('switch is not optimistic and rejects duplicate submissions while pending', async () => {
  const { radar, game, settings } = setup();
  let resolve;
  game.API.setRadarEnabled = (...args) => { settings.push(args); return new Promise(done => { resolve = done; }); };
  const operation = radar.toggle();
  assert.equal(radar.city().enabled, false);
  assert.match(radar.buttonHtml(), /保存中/);
  await radar.toggle(); assert.equal(settings.length, 1);
  resolve({ ...radar.data(), revision: 2, cities: [{ ...radar.city(), enabled: true }] });
  await operation;
  assert.equal(radar.city().enabled, true);
  assert.match(radar.buttonHtml(), /aria-pressed="true"/);
});

test('failed switch keeps the last server state and reports the failure', async () => {
  const { radar, game, notices } = setup();
  game.API.setRadarEnabled = () => Promise.reject(new Error('保存失败'));
  await radar.toggle();
  assert.equal(radar.city().enabled, false);
  assert.equal(notices.at(-1), '保存失败');
  assert.doesNotMatch(radar.buttonHtml(), /保存中/);
});

test('late messages cannot restore contacts after a confirmed close', () => {
  const { radar, notices } = setup();
  const item = contact();
  radar.apply({ ...radar.data(), revision: 8, contacts: [], history: [{ ...item, status: 'monitoring_disabled' }] }, false);
  assert.equal(radar.apply({ ...radar.data(), revision: 7, contacts: [item], alerts: [item] }, true), false);
  assert.equal(radar.contacts().length, 0); assert.equal(radar.unread(), 0); assert.equal(notices.length, 0);
});

test('same task alerts once and own incoming takes precedence over regional intelligence', () => {
  const { radar, state, notices } = setup();
  const item = contact();
  radar.apply({ ...radar.data(), revision: 2, contacts: [item], alerts: [item] }, true);
  radar.apply({ ...radar.data(), revision: 3, contacts: [item], alerts: [item] }, true);
  assert.equal(notices.length, 1); assert.match(notices[0], /团友A.*遭到进攻/);
  state.world.incoming = [{ marchId: item.marchId }];
  assert.equal(radar.contacts().length, 0); assert.equal(radar.unread(), 0);
});

test('grouped squads retain separate cards and scout alerts have distinct wording', () => {
  const { radar, notices } = setup();
  const first = contact(1), second = contact(2), scout = contact(3, { reminderGroup: 'scout', threatKind: 'scout' });
  radar.apply({ ...radar.data(), revision: 2, contacts: [first, second, scout], alerts: [first, scout] }, true);
  assert.equal(radar.contacts().length, 3); assert.equal(radar.unread(), 3);
  assert.equal(notices.length, 2); assert.match(notices[0], /共2支来袭/); assert.match(notices[1], /遭到侦察/);
});

test('historical intelligence displays a frozen snapshot rather than a live countdown', () => {
  const { radar } = setup();
  radar.apply({ ...radar.data(), revision: 2, contacts: [], history: [contact(1, { status: 'monitoring_disabled', arriveAt: 5000, snapshotAt: 4000 })] }, false);
  const html = radar.cards(true);
  assert.match(html, /监测已关闭/); assert.match(html, /关闭／失效时预计抵达/); assert.match(html, /快照/);
  assert.doesNotMatch(html, /data-countdown|军情分享|增援/); assert.equal(radar.unread(), 0);
});

test('switching cities selects only its saved radar without changing any other city', () => {
  const { radar, state, initial, settings } = setup();
  initial.cities[0].enabled = true;
  initial.cities.push({ cityId: 12, enabled: false, level: 10, radius: 10, x: 300, y: 300 });
  state.player.activeCityId = 12;
  assert.equal(radar.city().cityId, 12); assert.equal(radar.city().enabled, false);
  assert.equal(radar.city().radius, 10); assert.equal(initial.cities[0].enabled, true); assert.equal(settings.length, 0);
});

test('coverage uses the server world center and radius and clips to world edges', () => {
  const { radar } = setup();
  const circle = radar.circle({ x: 100, y: 200 }, 80);
  assert.equal(circle[0].x, 180); assert.equal(circle[0].y, 200);
  circle.forEach(point => assert.ok(Math.abs(Math.hypot(point.x - 100, point.y - 200) - 80) < 1e-8));
  const clipped = radar.clippedCircle({ x: 5, y: 5 }, 100, 800);
  assert.ok(clipped.length > 0);
  clipped.forEach(point => { assert.ok(point.x >= 0 && point.x <= 800); assert.ok(point.y >= 0 && point.y <= 800); });
});

test('late switch response after changing accounts cannot mutate the new account', async () => {
  const { radar, game, state } = setup(); let resolve;
  game.API.setRadarEnabled = () => new Promise(done => { resolve = done; });
  const request = radar.toggle(); state.player.id = 99;
  const current = { revision: 1, cities: [], contacts: [], history: [] }; state.world.regionalRadar = current;
  resolve({ revision: 99, cities: [{ cityId: 11, enabled: true }], contacts: [], history: [] });
  await request; assert.equal(state.world.regionalRadar, current);
});

test('radar rendering is non-interactive and updates animation independently from terrain', () => {
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  assert.match(source, /this\.radarLayer\.eventMode = this\.radarWaves\.eventMode = 'none'/);
  const wave = source.slice(source.indexOf('MapView.prototype.drawRadarWaves'), source.indexOf('MapView.prototype.updateShieldBreath'));
  assert.match(wave, /prefers-reduced-motion/); assert.match(wave, /document\.hidden/); assert.match(wave, /time \/ 4000/);
  assert.doesNotMatch(wave, /drawGround|requestAnimationFrame|setInterval/);
  assert.match(source, /sensor\.radius, c\.size/); assert.match(source, /radarSensor\.radius, c\.size/);
});

function radarAnimation(options = {}) {
  const { radar, initial } = setup();
  const sensor = initial.cities[0];
  Object.assign(sensor, { enabled: true, x: 100, y: 100 }, options.sensor);
  const rays = [], polygons = [], fills = [];
  const graphics = {
    clear() { rays.length = 0; polygons.length = 0; fills.length = 0; return this; },
    lineStyle(width, color, alpha) { this.style = { width, color, alpha }; return this; },
    beginFill(color, alpha) { fills.push({ color, alpha }); return this; },
    endFill() { return this; },
    moveTo(coordinateX, coordinateY) { this.start = { x: coordinateX, y: coordinateY }; return this; },
    lineTo(coordinateX, coordinateY) { rays.push({ start: this.start, end: { x: coordinateX, y: coordinateY }, ...this.style }); return this; },
    drawPolygon(points) { polygons.push({ points, ...this.style }); return this; }
  };
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  function MapView() {}
  vm.runInNewContext(source.slice(source.indexOf('MapView.prototype.drawRadarWaves'), source.indexOf('MapView.prototype.updateShieldBreath')), {
    MapView, G: { Radar: radar }, Math,
    document: { hidden: !!options.hidden }, window: { matchMedia: () => ({ matches: !!options.reduced }) }
  });
  const view = new MapView();
  Object.assign(view, { radarVisible: options.visible !== false, radarWaves: graphics, radarColor: 0x168e91,
    camera: { size: 200, screen: (coordinateX, coordinateY) => ({ x: coordinateX, y: coordinateY }) } });
  return { view, sensor, rays, polygons, fills };
}

test('radar coverage, scan rays and ripples use visible contrast', () => {
  const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  assert.match(source, /theme === 'dark' \? 0x52fff1 : 0x00777d/);
  assert.match(source, /lineStyle\(2\.5, this\.radarColor, \.96\)\.beginFill\(this\.radarColor, \.16\)/);
  assert.match(source, /rgba\(0,119,125,\.2\).*#00777d/);
  const { view, rays, polygons } = radarAnimation();
  view.drawRadarWaves(0);
  assert.equal(rays.at(-1).width, 2.5);
  assert.ok(rays.at(-1).alpha >= 0.9);
  assert.ok(rays.at(-2).alpha >= 0.45);
  assert.ok(polygons[0].alpha >= 0.75);
});

test('radar scan rotates continuously around the city and completes a full turn', () => {
  const { view, sensor, rays } = radarAnimation();
  function direction(time) {
    view.drawRadarWaves(time);
    assert.ok(rays.length > 0);
    const ray = rays.at(-1);
    assert.deepEqual(ray.start, { x: sensor.x, y: sensor.y });
    return { x: ray.end.x - sensor.x, y: ray.end.y - sensor.y };
  }
  const start = direction(0), quarter = direction(6000), full = direction(24000);
  assert.ok(Math.abs(start.x * quarter.x + start.y * quarter.y) < 1e-8);
  assert.ok(Math.hypot(start.x - full.x, start.y - full.y) < 1e-8);
  assert.ok(Math.abs(Math.hypot(start.x, start.y) - sensor.radius) < 1e-8);
});

test('rotating scan lines stay within the true radar radius and world edges', () => {
  const { view, sensor, rays } = radarAnimation({ sensor: { x: 5, y: 5, radius: 100 } });
  for (let time = 0; time < 24000; time += 1500) {
    view.drawRadarWaves(time);
    assert.ok(rays.length > 0);
    rays.forEach(ray => {
      assert.ok(ray.end.x >= -1e-8 && ray.end.x <= 200 + 1e-8);
      assert.ok(ray.end.y >= -1e-8 && ray.end.y <= 200 + 1e-8);
      assert.ok(Math.hypot(ray.end.x - sensor.x, ray.end.y - sensor.y) <= sensor.radius + 1e-8);
    });
  }
});

test('radar scan stops when disabled, hidden, offscreen or reduced motion is requested', () => {
  for (const options of [{ sensor: { enabled: false } }, { hidden: true }, { visible: false }, { reduced: true }]) {
    const { view, sensor, rays, polygons } = radarAnimation(options);
    view.drawRadarWaves(2000);
    assert.equal(rays.length, 0);
    assert.equal(polygons.length, 0);
    assert.equal(sensor.enabled, options.sensor ? false : true);
  }
});
