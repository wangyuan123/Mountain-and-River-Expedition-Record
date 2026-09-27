const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(modules = []) {
  const messages = [];
  const context = vm.createContext({
    console, Date,
    document: { getElementById() { return null; } }
  });
  context.window = context;
  function load(file) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  ['constants.js', 'data.js', 'icons.js'].forEach(load);
  const G = context.Game;
  G.Core = {
    state: { resources: { diamond: 10000 }, items: { goldBox: 1, steelPack: 1, supplyPack: 1 } },
    views: {}, render() {}
  };
  G.toast = message => messages.push(message);
  G.API = { shopBuy: () => Promise.resolve({ success: true }) };
  modules.forEach(load);
  return { G, messages };
}

test('五种资源始终使用首页配置的图片，所有素材均存在', () => {
  const { G } = setup();
  for (const key of ['food', 'steel', 'oil', 'rare', 'gold']) {
    const resource = G.DATA.resources[key];
    const html = G.resourceIconHtml(key);
    assert.ok(html.includes('src="' + resource.icon + '"'));
    assert.ok(html.includes('alt="' + resource.name + '"'));
    assert.ok(fs.existsSync(path.join(__dirname, '..', resource.icon)));
  }
  G.DATA.resources.steel.icon = G.DATA.resources.gold.icon;
  assert.ok(G.resourceIconHtml('steel').includes('src="' + G.DATA.resources.gold.icon + '"'));
});

test('图片属性和未知资源名称转义，无图片的钻石保留首页现有图标', () => {
  const { G } = setup();
  G.DATA.resources.steel.name = '钢铁"<script>';
  assert.ok(G.resourceIconHtml('steel').includes('alt="钢铁&quot;&lt;script&gt;"'));
  assert.equal(G.resourceIconHtml('<unknown>'), '&lt;unknown&gt;');
  assert.equal(G.resourceIconHtml('diamond'), G.DATA.resources.diamond.icon);
});

test('商城与仓库的资源礼包渲染图片，正文不泄露图片路径', () => {
  const { G } = setup(['shop.js', 'depot.js']);
  const view = { innerHTML: '' };
  G.Shop.setCat('resource');
  G.Shop.renderView(view);
  const shopHtml = view.innerHTML;
  G.Depot.setTab('resource');
  G.Depot.renderView(view);
  for (const html of [shopHtml, view.innerHTML]) {
    for (const key of ['food', 'steel', 'gold']) {
      assert.ok(html.includes('src="' + G.DATA.resources[key].icon + '"'));
    }
    assert.ok(!html.replace(/<[^>]*>/g, '').includes('img/resources/'));
  }
});

test('购买资源礼包的纯文本提示只显示商品名称和数量', async () => {
  const { G, messages } = setup(['shop.js']);
  G.Shop.buy('goldBox');
  await Promise.resolve();
  assert.deepEqual(messages, ['已购买 黄金箱 ×1']);
});

test('邮件附件读取同一套资源图片并保留数量与领取入口', async () => {
  const { G } = setup(['mail.js']);
  const keys = ['food', 'steel', 'oil', 'rare', 'gold'];
  G.API.listMail = () => Promise.resolve({ mails: [{
    id: 1, system: true, subject: '资源补给', body: '', ts: 0,
    attach: keys.map(type => ({ type, qty: 100 }))
  }] });
  G.API.markMailRead = () => Promise.resolve({});
  G.API.mailUnread = () => Promise.resolve({ unread: 0 });
  const view = { innerHTML: '' };
  G.Mail.renderView(view);
  await Promise.resolve();
  G.Mail.open(1);
  for (const key of keys) assert.ok(view.innerHTML.includes(G.resourceIconHtml(key)));
  assert.ok(view.innerHTML.includes('×100'));
  assert.ok(view.innerHTML.includes('领取附件'));
});
