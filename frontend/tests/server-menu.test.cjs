const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('大区菜单贴着输入框展开，并支持键盘选择与关闭', () => {
  const listeners = new Map();
  const document = {
    readyState: 'loading', activeElement: null,
    addEventListener(name, handler) { listeners.set(name, handler); },
    removeEventListener(name) { listeners.delete(name); },
    getElementById() { return null; }
  };
  const context = vm.createContext({ console, document, Math, Array });
  context.window = context;
  context.innerHeight = 930;
  context.addEventListener = (name, handler) => listeners.set('window-' + name, handler);
  context.removeEventListener = name => listeners.delete('window-' + name);
  context.localStorage = { getItem: () => null };
  let selected = 'jiangsu-1';
  context.Game = {
    Constants: { landscapeNavStorageKey: 'nav' }, DATA: {}, PlayerProfile: {},
    Core: { bindKeys() {}, render() {} }, API: { isLoggedIn: () => false },
    Servers: { select(id) { selected = id; } }
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);

  const classes = new Set();
  const picker = {
    classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, remove(name) { classes.delete(name); } },
    closest() { return { getBoundingClientRect: () => ({ top: 300, bottom: 680 }) }; },
    contains(target) { return target === trigger || options.includes(target); },
    querySelector(selector) { return selector === '.login-server-options' ? menu : selector === '#loginServerValue' ? value : trigger; },
    querySelectorAll: () => options
  };
  const value = { textContent: '江苏一区' };
  const trigger = {
    closest: () => picker,
    getBoundingClientRect: () => ({ top: 600, bottom: 649 }),
    querySelector: () => value,
    setAttribute(name, content) { this[name] = content; },
    focus() { document.activeElement = this; }
  };
  const options = ['江苏一区', '江苏二区'].map((name, index) => ({
    textContent: name, dataset: { serverId: 'jiangsu-' + (index + 1) },
    selected: index === 0,
    closest: () => picker,
    focus() { document.activeElement = this; },
    setAttribute(key, value) { if (key === 'aria-selected') this.selected = value === 'true'; }
  }));
  const menu = {
    hidden: true, offsetHeight: 84, style: {}, firstElementChild: options[0],
    querySelector: () => options.find(option => option.selected)
  };
  const main = context.Game.Main;

  main.toggleServerMenu(trigger);
  assert.equal(menu.hidden, false);
  assert.equal(trigger['aria-expanded'], 'true');
  assert.equal(classes.has('opens-above'), true);
  assert.equal(document.activeElement, options[0]);
  main.handleServerKey({ key: 'ArrowDown', currentTarget: picker, preventDefault() {} });
  assert.equal(document.activeElement, options[1]);
  main.chooseServer(options[1]);
  assert.equal(selected, 'jiangsu-2');
  assert.equal(value.textContent, '江苏二区');
  assert.equal(menu.hidden, true);
  assert.equal(document.activeElement, trigger);

  main.toggleServerMenu(trigger);
  main.dismissServerMenu({ type: 'pointerdown', target: {} });
  assert.equal(menu.hidden, true);
  assert.equal(listeners.has('pointerdown'), false);
});
