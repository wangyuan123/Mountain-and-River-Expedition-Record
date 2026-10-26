const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupGame(response, error) {
  const events = [];
  const requests = [];
  const elements = {
    'dismiss-row': { innerHTML: '' },
    'dismiss-row-7': { innerHTML: '' }
  };
  const initialState = { player: { id: 1 }, officers: [{ id: 7 }], _detailOfficerId: 7 };
  const game = {
    DATA: {},
    Core: {
      state: initialState,
      views: {},
      render() { events.push({ action: 'render' }); }
    },
    ApiClient: function () {
      this.post = function (url, body) {
        requests.push({ url, body });
        return error ? Promise.reject(error) : Promise.resolve(response);
      };
    },
    toast(message, type) { events.push({ action: 'toast', message, type }); },
    go(route) { events.push({ action: 'go', route }); }
  };
  const context = vm.createContext({
    Game: game,
    document: { getElementById(id) { return elements[id] || null; } }
  });
  context.window = context;
  for (const file of ['api.js', 'officer.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  game.Officer._detailOfficerId = 7;
  return { game, events, requests, elements, initialState };
}

for (const entry of ['dismiss', 'dismissList']) {
  test(`${entry}: successful dismissal refreshes the view before showing success`, async () => {
    const state = { player: { id: 1 }, officers: [] };
    const { game, events, requests } = setupGame({ success: true, state });

    await game.Officer[entry](7, 'confirm');

    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/game/officer/dismiss');
    assert.equal(requests[0].body.officerId, 7);
    assert.equal(game.Core.state, state);
    assert.equal(game.state, state);
    assert.deepEqual(events, [
      entry === 'dismiss' ? { action: 'go', route: 'officer' } : { action: 'render' },
      { action: 'toast', message: '解雇成功', type: 'success' }
    ]);
    if (entry === 'dismiss') {
      assert.equal(game.Officer._detailOfficerId, null);
      assert.equal(state._detailOfficerId, null);
    }
  });

  test(`${entry}: business failure shows the server message without success or navigation`, async () => {
    const { game, events } = setupGame({ success: false, message: '军官不存在', state: { officers: [] } });

    await game.Officer[entry](7, 'confirm');

    assert.deepEqual(events, [{ action: 'toast', message: '军官不存在', type: 'error' }]);
    assert.equal(game.Officer._detailOfficerId, 7);
  });

  test(`${entry}: business failure without a message shows the fallback`, async () => {
    const { game, events } = setupGame({ success: false });

    await game.Officer[entry](7, 'confirm');

    assert.deepEqual(events, [{ action: 'toast', message: '解雇失败', type: 'error' }]);
  });

  test(`${entry}: request failure preserves the view and shows the error`, async () => {
    const { game, events, initialState } = setupGame(null, new Error('网络异常'));

    await game.Officer[entry](7, 'confirm');

    assert.equal(game.Core.state, initialState);
    assert.deepEqual(events, [{ action: 'toast', message: '网络异常', type: 'error' }]);
  });

  test(`${entry}: asking and cancelling never sends a dismissal request or shows success`, () => {
    const { game, events, requests, elements } = setupGame({ success: true });
    const wrapper = elements[entry === 'dismiss' ? 'dismiss-row' : 'dismiss-row-7'];

    game.Officer[entry](7, 'ask');
    assert.match(wrapper.innerHTML, /确认解雇/);
    game.Officer[entry](7, 'cancel');
    assert.doesNotMatch(wrapper.innerHTML, /确认解雇/);
    assert.deepEqual(requests, []);
    assert.deepEqual(events, []);
  });
}

test('dismissal API preserves the operation result while applying the returned state', async () => {
  const response = { success: true, message: '已解雇 军官', refund: 20, state: { officers: [] } };
  const { game } = setupGame(response);

  assert.equal(await game.API.dismissOfficer(7), response);
  assert.equal(game.Core.state, response.state);
});
