const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

/** 直接加载生产适配层，测试中不注入自动快照或替代业务计算器。 */
function setup() {
  const messages = [], pending = [];
  let token = 'account-a';
  const G = {
    Core: { state: { gameplay: { armyCap: 123, buildings: { farm: { levels: [{ cost: { steel: 71 } }] } } } } },
    API: { getToken: () => token, client: { cityId: 1, serverId: 'server-a', post(url, intent) {
      assert.equal(url, '/game/preview');
      return new Promise((resolve, reject) => pending.push({ intent, resolve, reject }));
    } } },
    toast: message => messages.push(message)
  };
  const context = vm.createContext({ window: { Game: G } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/gameplay-rules.js'), 'utf8'), context);
  return { G, pending, messages, setToken: value => { token = value; } };
}
const flush = () => new Promise(setImmediate);

test('读取服务器报价，篡改本地等级和属性不会触发前端重算，缺快照时等待同步', () => {
  const { G } = setup();
  G.Core.state.buildings = { farm: 10, command: 10 };
  G.Core.state.tech = { log_build: 10 };
  assert.equal(G.ServerRules.value('armyCap'), 123);
  assert.equal(G.ServerRules.level('buildings', 'farm', 0).cost.steel, 71);
  delete G.Core.state.gameplay;
  assert.equal(G.ServerRules.value('armyCap'), undefined);
  assert.equal(G.ServerRules.level('buildings', 'farm', 0).cost, undefined);
});

test('同一报价合并请求并通知每个使用者；资源推送后丢弃在途旧报价', async () => {
  const { G, pending } = setup();
  const intent = { action: 'recruit', id: 'infantry', count: 2 };
  const received = [];
  assert.equal(G.ServerRules.cachedPreview(intent, value => received.push(value.cost)), null);
  G.ServerRules.cachedPreview(intent, value => received.push(value.cost));
  assert.equal(pending.length, 1);
  pending[0].resolve({ cost: 91 }); await flush();
  assert.deepEqual(received, [91, 91]);
  assert.equal(G.ServerRules.cachedPreview(intent).cost, 91);
  G.ServerRules.invalidate();
  G.ServerRules.cachedPreview(intent, () => assert.fail('过期报价不能更新画面'));
  G.ServerRules.invalidate();
  pending[1].resolve({ cost: 1 }); await flush();
  assert.equal(G.ServerRules.cachedPreview(intent), null);
  assert.equal(pending.length, 3);
  pending[2].resolve({ cost: 99 }); await flush();
  assert.equal(G.ServerRules.cachedPreview(intent).cost, 99);
});

test('切城、切服和换号后拒绝迟到预览，不将其他身份报价应用到当前页面', async () => {
  for (const switchIdentity of [f => { f.G.API.client.cityId = 2; }, f => { f.G.API.client.serverId = 'server-b'; }, f => f.setToken('account-b')]) {
    const f = setup();
    const response = f.G.ServerRules.preview({ action: 'heal', objectId: 1, count: 1 });
    switchIdentity(f); f.pending[0].resolve({ gold: 1 });
    await assert.rejects(response, /账号或城市已切换/);
  }
});

test('预览错误不缓存伪造的零费用，再次请求可恢复', async () => {
  const { G, pending, messages } = setup();
  const intent = { action: 'shop', id: 'skillBook', count: 1 };
  G.ServerRules.cachedPreview(intent);
  pending[0].reject(new Error('同步失败')); await flush();
  assert.deepEqual(messages, ['同步失败']);
  assert.equal(G.ServerRules.cachedPreview(intent), null);
  assert.equal(pending.length, 2);
  pending[1].resolve({ cost: 81 }); await flush();
  assert.equal(G.ServerRules.cachedPreview(intent).cost, 81);
});

test('完整状态替换后即使页面尚未请求新报价，旧响应也不能更新页面', async () => {
  const { G, pending } = setup();
  const intent = { action: 'experience', objectId: 1, id: 'expBook', count: 1 };
  G.ServerRules.cachedPreview(intent, () => assert.fail('旧状态报价不能回填'));
  G.Core.state = { gameplay: { armyCap: 200 } };
  pending[0].resolve({ gain: 1000 }); await flush();
  assert.equal(G.ServerRules.cachedPreview(intent), null);
  assert.equal(pending.length, 2);
  pending[1].resolve({ gain: 2000 }); await flush();
  assert.equal(G.ServerRules.cachedPreview(intent).gain, 2000);
});
