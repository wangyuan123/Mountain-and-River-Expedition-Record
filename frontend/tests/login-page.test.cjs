const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function renderLogin(recovery) {
  const context = vm.createContext({ console, document: {} });
  context.window = context;
  context.Game = {
    DATA: {},
    Core: { views: {} },
    Account: {
      recovery: recovery ? { username: 'commander' } : null,
      loginPanel: () => '<div class="account-result">账号提示</div>',
      recoveryPanel: () => '<div id="recoveryPanel">恢复账号</div>'
    }
  };
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8'), context);
  const view = { innerHTML: '' };
  context.Game.Core.views.login(view);
  return view.innerHTML;
}

test('login page keeps credential fields, account notice and both auth actions', () => {
  const html = renderLogin(false);
  assert.match(html, /class="login-page"/);
  assert.match(html, /class="login-hero"/);
  assert.match(html, /class="login-hero-tank" src="img\/units\/models\/htank\.webp"/);
  assert.match(html, /class="login-hero-fighter login-hero-fighter-front" src="img\/units\/models\/fighter\.webp"/);
  assert.match(html, /class="login-hero-infantry login-hero-infantry-left" src="img\/units\/models\/infantry\.webp"/);
  assert.match(html, /class="login-hero-infantry login-hero-infantry-right" src="img\/units\/models\/infantry\.webp"/);
  assert.match(html, /for="loginUser"/);
  assert.match(html, /id="loginUser"[^>]*autocomplete="username"/);
  assert.match(html, /id="loginPass"[^>]*type="password"/);
  assert.match(html, /onsubmit="Game\.Main\.doLogin\(\); return false;"/);
  assert.match(html, /onclick="Game\.Main\.doRegister\(\)"/);
  assert.match(html, /id="loginMsg"[^>]*role="status"/);
  assert.match(html, /账号提示/);
});

test('account recovery uses the same login shell without showing password fields', () => {
  const html = renderLogin(true);
  assert.match(html, /class="login-page"/);
  assert.match(html, /id="recoveryPanel"/);
  assert.doesNotMatch(html, /id="loginPass"/);
});
