const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const c = {
    console, Date, Map, Set, WeakMap, Uint8Array,
    Game: {
      escapeHtml: s => String(s == null ? '' : s),
      MapChunks: function () {},
      Core: { state: { player: { activeCityId: 1 } } },
      API: { client: {}, getToken: () => 'token' }
    },
    document: {
      createElement: () => {
        const ctx = { drawImage() {}, getImageData() { return { data: [] }; } };
        return { getContext: () => ctx };
      }
    }
  };
  c.window = c;
  vm.createContext(c);
  require('./load-constants.cjs')(c);
  for (const file of ['map-camera.js', 'map-layout.js', 'world-map.js']) {
    let source = fs.readFileSync(path.join(__dirname, '../js', file), 'utf8');
    source = source.replace('  var instance = null, camera = null, owner = \'\', mode = \'list\'', '  var instance = null, camera = null, owner = \'token:1\', mode = \'map\'');
    source = source.replace('  G.WorldMap={', '  G.TestMapView=MapView;\n  G.WorldMap={');
    vm.runInContext(source, c);
  }
  const v = Object.create(c.Game.TestMapView.prototype);
  v.camera = new c.Game.MapCamera(200, 100, 100, 48);
  v.detailSeq = 0;
  v.detail = { hidden: true, innerHTML: '', querySelectorAll: () => [], querySelector: () => null };
  v.wake = () => {};
  return { c, v };
}

test('loadSite displays streamlined site info and condition when cannot build', async () => {
  const { c, v } = setup();
  const elements = {};
  v.detail = {
    hidden: true,
    _html: '',
    set innerHTML(val) {
      this._html = val;
    },
    get innerHTML() {
      return this._html;
    },
    querySelector: (sel) => {
      if (sel === '[data-map="found-city"]') return null;
      if (sel === 'form') return null;
      return null;
    }
  };

  c.Game.API.client.get = async (url) => {
    assert.equal(url, '/game/cities/site?x=137&y=100');
    return {
      x: 137,
      y: 100,
      coastal: true,
      cityType: '海岸',
      valid: false,
      reason: '城市数量已达军衔上限'
    };
  };

  v.loadSite(137, 100);
  await new Promise(resolve => setTimeout(resolve, 10));

  const html = v.detail.innerHTML;
  assert.match(html, /海岸 · \(137, 100\)/);
  assert.match(html, /当前不可建城：城市数量已达军衔上限/);
  assert.match(html, /建城判断条件：以当前格为左上角的 2×2 四格均为未占用陆地，且城市名额未满；内陆也可建城。/);
  assert.doesNotMatch(html, /data-map="found-city"/);
  assert.doesNotMatch(html, /粮 5,000 · 钢 10,000/);
});

test('loadSite displays blue [建立城市] button and toggles form when valid', async () => {
  const { c, v } = setup();
  let foundBtnObj = null;
  let formObj = null;
  let posted = null;

  c.Game.API.applyState = () => {};
  c.Game.Core.refreshTop = () => {};
  c.Game.toast = () => {};
  c.Game.API.client.post = async (url, data) => {
    posted = { url, data };
    return { state: {}, message: '城市建立成功' };
  };

  v.closeDetail = () => {};
  v.requestChunks = () => {};

  v.detail = {
    hidden: true,
    _html: '',
    set innerHTML(val) {
      this._html = val;
      if (val.includes('[建立城市]')) {
        foundBtnObj = {
          dataset: { map: 'found-city' },
          onclick: null
        };
        const inputObj = { value: '胜利新城', focus() { this.focused = true; } };
        formObj = {
          style: { display: 'none' },
          elements: { name: inputObj },
          querySelector: (sel) => {
            if (sel === 'input[name="name"]') return inputObj;
            if (sel === 'button[type="submit"]') return { disabled: false };
            return null;
          },
          onsubmit: null
        };
      }
    },
    get innerHTML() {
      return this._html;
    },
    querySelector: (sel) => {
      if (sel === '[data-map="found-city"]') return foundBtnObj;
      if (sel === 'form') return formObj;
      return null;
    }
  };

  c.Game.API.client.get = async (url) => {
    return {
      x: 50,
      y: 60,
      coastal: false,
      cityType: '平原',
      valid: true,
      reason: ''
    };
  };

  v.loadSite(50, 60);
  await new Promise(resolve => setTimeout(resolve, 10));

  const html = v.detail.innerHTML;
  assert.match(html, /平原 · \(50, 60\)/);
  assert.match(html, /\[建立城市\]/);
  assert.match(html, /建城判断条件：以当前格为左上角的 2×2 四格均为未占用陆地，且城市名额未满；内陆也可建城。/);
  assert.doesNotMatch(html, /当前不可建城/);

  // Test click [建立城市] toggles form
  assert.equal(formObj.style.display, 'none');
  foundBtnObj.onclick({ preventDefault() {} });
  assert.equal(formObj.style.display, 'block');
  assert.equal(formObj.elements.name.focused, true);

  // Test form submit
  formObj.onsubmit({ preventDefault() {} });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(posted.url, '/game/cities');
  assert.equal(posted.data.x, 50);
  assert.equal(posted.data.y, 60);
  assert.equal(posted.data.name, '胜利新城');
});
