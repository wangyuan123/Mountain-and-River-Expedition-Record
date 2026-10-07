const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const elements = { loginUser: { value: 'commander' }, loginPass: { value: 'password' } };
  const context = vm.createContext({ console, document: {
    readyState: 'loading', addEventListener() {},
    getElementById: id => elements[id],
    createElement: () => ({ addEventListener() {}, querySelectorAll: () => [] }),
    body: { appendChild(mask) {
      elements.registrationAgreementModal = mask;
      elements.registrationAgreementCheck = { checked: true, focus() {} };
      elements.registrationAgreementSubmit = {};
      elements.registrationAgreementCancel = {};
    } }
  } });
  context.window = context;
  context.Game = { Core: {}, DATA: {}, Constants: {}, API: {
    login: async () => ({}), register: async () => ({}), createGuest: async () => ({})
  } };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main.js'), 'utf8'), context);
  const main = context.Game.Main;
  const welcomes = [];
  main.showLoginMsg = () => {};
  main.closeRegistrationAgreement = () => {};
  main.startGame = async () => {
    if (main._showWelcomeAfterStart) {
      main._showWelcomeAfterStart = false;
      welcomes.push(true);
    }
  };
  return { main, elements, welcomes };
}

test('existing account login clears pending welcome and never displays the new account introduction', async () => {
  const { main, welcomes } = setup();
  main._showWelcomeAfterStart = true;
  main.doLogin();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(welcomes.length, 0);
  assert.equal(main._showWelcomeAfterStart, false);
});

test('successful registration displays the introduction once, subsequent login does not', async () => {
  const { main, elements, welcomes } = setup();
  main.openRegistrationAgreement('commander', 'password');
  elements.registrationAgreementSubmit.onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(welcomes.length, 1);
  main.doLogin();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(welcomes.length, 1);
});

test('new guest creation displays the welcome introduction', async () => {
  const { main, welcomes } = setup();
  main.guestPlay();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(welcomes.length, 1);
});
