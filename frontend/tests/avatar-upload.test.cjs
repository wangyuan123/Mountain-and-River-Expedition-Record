const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(options = {}) {
  const requests = [], toasts = [], readers = [];
  const dataUrl = 'data:image/png;base64,iVBORw0KGgo=';
  const customAvatar = 'img/avatars/custom/42_uploaded.png';
  const file = { name: 'avatar.png', size: 1024, type: 'image/png' };
  const elements = {
    avatarPickerVipFile: { files: [file], disabled: false },
    avatarPickerVipUploadBtn: { disabled: false, textContent: '本地上传' },
    avatarPreviewImg: { src: 'old.png' },
    drawerAvatarImg: { src: 'old.png' },
    topbarAvatar: { src: 'old.png' }
  };
  let reply = { status: 200, data: { success: true, customAvatar } };
  let pickerRefreshes = 0, topRefreshes = 0;
  const context = {
    location: { port: '8081', hostname: 'localhost' },
    localStorage: { getItem: key => key === 'wargame_token' ? 'test-token' : 'jiangsu-1' },
    document: {
      getElementById: id => elements[id] || null,
      querySelector: selector => selector === '.topbar-avatar' ? elements.topbarAvatar : null
    },
    setTimeout: () => 1, clearTimeout() {},
    FileReader: class {
      constructor() { readers.push(this); }
      readAsDataURL(selected) {
        this.file = selected;
        if (options.readError) return this.onerror();
        if (options.readAbort) return this.onabort();
        this.onload({ target: { result: dataUrl } });
      }
    },
    fetch: async (url, init) => {
      requests.push({ path: new URL(url).pathname, method: init.method,
        body: JSON.parse(init.body), headers: init.headers });
      return { ok: reply.status < 400, status: reply.status,
        text: async () => JSON.stringify(reply.data) };
    },
    Game: {
      Core: { state: { player: { id: 42, vipLevel: 5, customAvatar: 'old.png' } },
        renderTop() { topRefreshes++; } },
      toast: message => toasts.push(message)
    }
  };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'api-client.js', 'api.js', 'player-profile.js', 'vip.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  }
  const game = context.Game;
  game.Main = Object.assign({}, game.PlayerProfile, { openAvatarPicker() { pickerRefreshes++; } });
  return { game, elements, file, dataUrl, customAvatar, requests, toasts, readers,
    setReply(status, data) { reply = { status, data }; },
    refreshes: () => ({ picker: pickerRefreshes, top: topRefreshes }) };
}

test('本地头像通过真实客户端上传，防止重复提交并立即同步档案及首页', async () => {
  const env = setup();
  const { game, elements, requests } = env;
  const uploading = game.Main.uploadCustomAvatarFile();
  assert.equal(elements.avatarPickerVipFile.disabled, true);
  assert.equal(elements.avatarPickerVipUploadBtn.textContent, '上传中…');
  game.Main.uploadCustomAvatarFile();
  await uploading;
  assert.equal(requests.length, 1);
  assert.equal(requests[0].path, '/api/game/vip/avatar/upload');
  assert.equal(requests[0].method, 'POST');
  assert.deepEqual(requests[0].body, { dataUrl: env.dataUrl });
  assert.equal(requests[0].headers.Authorization, 'Bearer test-token');
  assert.equal(requests[0].headers['Content-Type'], 'application/json');
  assert.equal(game.Core.state.player.customAvatar, env.customAvatar);
  for (const id of ['avatarPreviewImg', 'drawerAvatarImg', 'topbarAvatar']) {
    assert.equal(elements[id].src, env.customAvatar);
  }
  assert.deepEqual(env.refreshes(), { picker: 1, top: 1 });
  assert.equal(elements.avatarPickerVipFile.disabled, false);
  assert.equal(elements.avatarPickerVipUploadBtn.disabled, false);
  assert.equal(elements.avatarPickerVipUploadBtn.textContent, '本地上传');
  assert.ok(env.toasts.includes('自定义头像已上传并生效！'));
});

test('头像外链设置和恢复预设使用后端 url 字段与注册路由', async () => {
  const { game, requests } = setup();
  for (const url of ['https://example.com/commander.png', '']) {
    await game.API.vipSetCustomAvatarUrl(url);
    assert.equal(requests.at(-1).path, '/api/game/vip/avatar/url');
    assert.deepEqual(requests.at(-1).body, { url });
  }
});

test('上传被服务端拒绝时显示错误、保留旧头像并支持再次上传', async () => {
  const env = setup();
  env.setReply(400, { error: '仅支持 PNG、JPEG 或 WebP 格式的图片文件' });
  await env.game.Main.uploadCustomAvatarFile();
  assert.ok(env.toasts.includes('仅支持 PNG、JPEG 或 WebP 格式的图片文件'));
  assert.equal(env.game.Core.state.player.customAvatar, 'old.png');
  assert.equal(env.elements.avatarPreviewImg.src, 'old.png');
  assert.equal(env.elements.avatarPickerVipUploadBtn.disabled, false);
  env.setReply(200, { success: true, customAvatar: env.customAvatar });
  await env.game.Main.uploadCustomAvatarFile();
  assert.equal(env.requests.length, 2);
  assert.equal(env.game.Core.state.player.customAvatar, env.customAvatar);
});

for (const [option, message] of [['readError', '图片读取失败，请重新选择图片'], ['readAbort', '图片读取已取消，请重试']]) {
  test(`本地文件${option === 'readError' ? '读取失败' : '读取取消'}时明确提示并恢复按钮`, async () => {
    const env = setup({ [option]: true });
    await env.game.Main.uploadCustomAvatarFile();
    assert.deepEqual(env.toasts, [message]);
    assert.equal(env.requests.length, 0);
    assert.equal(env.elements.avatarPickerVipFile.disabled, false);
    assert.equal(env.elements.avatarPickerVipUploadBtn.disabled, false);
  });
}

test('上传响应缺少服务器头像地址时不能把临时 Base64 数据当作保存成功', async () => {
  const env = setup();
  env.setReply(200, { success: true });
  await env.game.Main.uploadCustomAvatarFile();
  assert.ok(env.toasts.includes('头像上传结果无效，请重试'));
  assert.equal(env.game.Core.state.player.customAvatar, 'old.png');
  assert.deepEqual(env.refreshes(), { picker: 0, top: 0 });
});

test('两处上传入口都在读取文件前拒绝超过后端 1MB 限制的图片', () => {
  for (const entry of ['profile', 'vip']) {
    const env = setup();
    env.file.size = 1024 * 1024 + 1;
    env.elements.vipCustomAvatarFileInput = env.elements.avatarPickerVipFile;
    if (entry === 'profile') env.game.Main.uploadCustomAvatarFile();
    else env.game.Vip.uploadAvatarFile();
    assert.deepEqual(env.toasts, ['图片大小不能超过 1MB']);
    assert.equal(env.readers.length, 0);
    assert.equal(env.requests.length, 0);
  }
});

test('恰好 1MB 的图片允许提交，未选择图片时提示选择文件', async () => {
  const env = setup();
  env.elements.avatarPickerVipFile.files = [];
  env.game.Main.uploadCustomAvatarFile();
  assert.deepEqual(env.toasts, ['请先选择要上传的本地图片']);
  env.file.size = 1024 * 1024;
  env.elements.avatarPickerVipFile.files = [env.file];
  await env.game.Main.uploadCustomAvatarFile();
  assert.equal(env.requests.length, 1);
});
