const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('landscape navigation preserves its scroll and reveals the active entry', () => {
  const screen = { classList: { toggle() {} } };
  const previous = { clientWidth: 120, scrollLeft: 0, querySelectorAll: () => [{ scrollTop: 120 }] };
  const active = { getBoundingClientRect: () => ({ top: 250, bottom: 280 }) };
  const navPage = {
    scrollTop: 0,
    querySelector: () => active,
    getBoundingClientRect: () => ({ top: 0, bottom: 240 })
  };
  const viewport = {
    clientWidth: 120, scrollLeft: 0,
    querySelectorAll: () => [navPage],
    addEventListener() {}
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
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);

  context.Game.Main.renderNavBar();
  assert.equal(navPage.scrollTop, 160);
  assert.equal(bar._navRoute, 'world');
});

test('landscape sidebar toggle persists and updates its accessible label', () => {
  const classes = new Set();
  const screen = { classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } } };
  const icon = { textContent: '' };
  const attributes = {};
  const button = {
    setAttribute(name, value) { attributes[name] = value; },
    querySelector(selector) { return selector === '.nav-collapse-icon' ? icon : null; }
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
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);
  const main = context.Game.Main;
  main.toggleLandscapeNav();
  assert.ok(classes.has('nav-collapsed'));
  assert.equal(attributes['aria-expanded'], 'false');
  assert.equal(attributes['aria-label'], '展开导航');
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
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  context.Game.Core.state = { world: { incoming: [] } };
  const html = context.Game.MainView.navBar();
  assert.match(html, /aria-controls="gameNavViewport" aria-expanded="true" aria-label="收起导航"/);
  assert.match(html, /class="nav-collapse-icon" aria-hidden="true">‹<\/span><\/button>/);
  assert.doesNotMatch(html, /nav-collapse-label/);
  assert.match(html, /id="gameNavViewport" class="nav-viewport"/);
});

test('map list return shares the page backbar without duplicating it', () => {
  const context = { Game: {}, document: {
    createElement(tag) {
      return { tag, children: [], appendChild(node) { this.children.push(node); }, setAttribute() {}, addEventListener() {} };
    }
  } };
  context.window = context;
  vm.createContext(context);
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  const mapSwitch = { className: 'world-map-list-switch' };
  let bar;
  const view = { firstChild: mapSwitch,
    querySelector(selector) { return selector === '.page-backbar' ? bar : selector === '.world-map-list-switch' ? mapSwitch : null; },
    insertBefore(node) { bar = node; }
  };
  context.Game.Core.renderBackButton(view);
  assert.equal(bar.className, 'page-backbar');
  assert.equal(bar.children[0].className, 'page-back-button');
  assert.equal(bar.children[1], mapSwitch);
  context.Game.Core.renderBackButton(view);
  assert.equal(bar.children.length, 2);
});

test('portaled map toolbar does not create a second return button in the view', () => {
  const context = { Game: {}, document: {} };
  context.window = context;
  vm.createContext(context);
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  const view = {
    querySelector(selector) { return selector === '.world-map-shell' ? {} : null; },
    insertBefore() { assert.fail('map already owns its return button'); }
  };
  context.Game.Core.renderBackButton(view);
});

test('changing or returning to a route resets the independently scrolling view', () => {
  const view = { scrollTop: 280 };
  const context = {
    Game: {},
    document: { readyState: 'loading', addEventListener() {}, getElementById: id => id === 'view' ? view : null }
  };
  context.window = context;
  vm.createContext(context);
  require('./load-constants.cjs')(context);
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
