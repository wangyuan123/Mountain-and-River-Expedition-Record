const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('landscape navigation preserves its scroll and reveals the active entry', () => {
  const screen = { classList: { toggle() {} } };
  const previous = { clientWidth: 120, scrollLeft: 0, scrollTop: 120 };
  const active = { getBoundingClientRect: () => ({ top: 250, bottom: 280 }) };
  const viewport = {
    scrollTop: 0,
    querySelector: () => active,
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ top: 0, bottom: 240 })
  };
  let current = previous;
  const bar = {
    _navRoute: 'home',
    querySelector: selector => selector === '.nav-viewport' ? current : null,
    querySelectorAll: () => [],
    set innerHTML(value) { this.html = value; current = viewport; }
  };
  const context = {
    Game: {
      $: () => bar,
      Core: { route: 'world', bindKeys() {} },
      MainView: { navBar: () => '<nav>world</nav>' },
      PlayerProfile: {}
    },
    document: { readyState: 'loading', addEventListener() {}, getElementById: id => id === 'screen' ? screen : bar, querySelector: () => null },
    matchMedia: () => ({ matches: true })
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);

  context.Game.Main.renderNavBar();
  assert.equal(viewport.scrollTop, 160);
  assert.equal(bar._navRoute, 'world');
});

test('landscape sidebar toggle persists and updates its accessible label', () => {
  const classes = new Set();
  const screen = { classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } } };
  const label = { textContent: '' };
  const icon = { textContent: '' };
  const attributes = {};
  const button = {
    setAttribute(name, value) { attributes[name] = value; },
    querySelector(selector) { return selector === '.nav-collapse-label' ? label : icon; }
  };
  const storage = new Map();
  const context = {
    Game: { Core: {}, PlayerProfile: {} },
    document: {
      readyState: 'loading', addEventListener() {},
      getElementById: () => screen,
      querySelector: () => button
    },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
    matchMedia: () => ({ matches: true })
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);
  const main = context.Game.Main;
  main.toggleLandscapeNav();
  assert.ok(classes.has('nav-collapsed'));
  assert.equal(attributes['aria-expanded'], 'false');
  assert.equal(attributes['aria-label'], '展开导航');
  assert.equal(label.textContent, '展开导航');
  assert.equal(icon.textContent, '›');
  assert.equal(storage.get('wargame_landscape_nav_collapsed'), '1');
  main.toggleLandscapeNav();
  assert.ok(!classes.has('nav-collapsed'));
  assert.equal(attributes['aria-expanded'], 'true');
  assert.equal(attributes['aria-label'], '收起导航');
  assert.equal(storage.get('wargame_landscape_nav_collapsed'), '0');
});

test('game navigation renders an accessible landscape collapse control', () => {
  const context = { Game: {}, document: {} };
  context.window = context;
  vm.createContext(context);
  for (const file of ['data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Core.state = { world: { incoming: [] } };
  const html = context.Game.MainView.navBar();
  assert.match(html, /aria-controls="gameNavViewport" aria-expanded="true" aria-label="收起导航"/);
  assert.match(html, /id="gameNavViewport" class="nav-viewport"/);
});

test('changing or returning to a route resets the independently scrolling view', () => {
  const view = { scrollTop: 280 };
  const context = {
    Game: {},
    document: { readyState: 'loading', addEventListener() {}, getElementById: id => id === 'view' ? view : null }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  const core = context.Game.Core;
  core.route = 'home';
  core.render = () => {};
  core.go('world');
  assert.equal(view.scrollTop, 0);
  view.scrollTop = 190;
  core.back();
  assert.equal(view.scrollTop, 0);
});
