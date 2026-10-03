const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
function setup(extra={}) {
  const c=vm.createContext({console,Promise,Math,Game:{DATA:{},Core:{state:{},views:{}}},...extra});c.window=c;
  for(const file of ['constants.js','dispatch-route.js','world.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',file),'utf8'),c);
  return c;
}
test('preview preserves route turns, centres axis-aligned routes and labels direction',()=>{
  const {Game:g}=setup();
  for(const points of [[[20,30],[20,80]],[[20,30],[90,30]],[[0,0],[0,199],[199,199]],[[20,30]],[[20,30],[20,30]]]) {
    const l=g.DispatchRoute.layout(points);
    assert.equal(l.points.length,points.length);
    l.points.forEach((p,i)=>{
      assert.ok(p[0]>=274&&p[0]<=566&&p[1]>=50&&p[1]<=182);
      assert.ok(Math.abs((p[0]-420)/l.scale+l.cx-points[i][0])<1e-9);
      assert.ok(Math.abs((p[1]-116)/l.scale+l.cy-points[i][1])<1e-9);
    });
  }
  assert.equal(g.DispatchRoute.layout([[20,30],[20,80]]).points[0][0],420);
  assert.equal(g.DispatchRoute.layout([[20,30],[20,80]]).direction,'南方向');
  assert.equal(g.DispatchRoute.layout([[20,30],[10,20]]).direction,'西北方向');
  assert.equal(g.DispatchRoute.layout([[20,30]]).direction,'同一坐标');
  for(const p of [[],null,[[NaN,1]],[[1,Infinity]],[[1,'2']]])assert.equal(g.DispatchRoute.layout(p),null);
});
test('preview escapes names, shows server metrics and handles missing or coincident coordinates',()=>{
  const {Game:g}=setup(),el={};
  const route={mode:'air',points:[[126,139],[135,177]],distance:47,seconds:31,cargoLimit:0};
  g.DispatchRoute.render(el,route,{start:'<img onerror="bad">',end:'稀矿厂 & 基地'},null);
  assert.match(el.innerHTML,/&lt;img onerror=&quot;bad&quot;&gt;/);
  assert.match(el.innerHTML,/稀矿厂 &amp; 基地/);
  assert.match(el.innerHTML,/\(126, 139\)/);
  assert.match(el.innerHTML,/\(135, 177\)/);
  assert.match(el.innerHTML,/47/);assert.match(el.innerHTML,/31 秒/);
  assert.match(el.innerHTML,/stroke-dasharray="7 5"/);
  assert.match(el.innerHTML,/地形未加载/);
  g.DispatchRoute.render(el,{...route,points:[]});assert.match(el.innerHTML,/路线坐标暂不可用/);assert.doesNotMatch(el.innerHTML,/<svg/);
  g.DispatchRoute.render(el,{...route,points:[[20,30]]});assert.match(el.innerHTML,/起终/);assert.doesNotMatch(el.innerHTML,/NaN|Infinity/);
});
test('quantity changes keep the existing preview; unit changes reject stale previews',async()=>{
  const timers=[],requests=[];let onChange,resolveTerrain;
  const c=setup({setTimeout:fn=>(timers.push(fn),timers.length),clearTimeout(){}});
  c.Game.DispatchRoute.loadTerrain=()=>new Promise(resolve=>resolveTerrain=resolve);
  const rendered=[];c.Game.DispatchRoute.render=(hint,route)=>rendered.push(route.distance);
  c.Game.API={client:{post:()=>new Promise((resolve,reject)=>requests.push({resolve,reject}))}};
  const hint={isConnected:true,innerHTML:'',textContent:'',setAttribute(){},querySelector(){return null;}};
  let army={scout:1};
  c.Game.World.bindRoutePreview({addEventListener:(type,fn)=>{if(type==='change')onChange=fn;}},hint,()=>({army}),{start:'起点',end:'终点'});
  timers.pop()();
  army={scout:2};onChange();
  assert.equal(timers.length,0);
  assert.deepEqual(rendered,[]);
  army={scout:2,infantry:1};onChange();timers.pop()();
  resolveTerrain({sea(){return false;}});await new Promise(setImmediate);
  requests[1].resolve({distance:22});await new Promise(setImmediate);
  requests[0].resolve({distance:11});await new Promise(setImmediate);
  assert.deepEqual(rendered,[22]);
  army={scout:3,infantry:1};onChange();
  assert.equal(timers.length,0);
  assert.deepEqual(rendered,[22]);
  army={scout:2,infantry:1,transport:1};onChange();timers.pop()();
  requests[2].reject(Error('所需运力不足'));await new Promise(setImmediate);
  assert.match(hint.textContent,/所需运力不足/);
  army={scout:2,infantry:1,transport:2};onChange();timers.pop()();hint.isConnected=false;
  requests[3].resolve({distance:33});await new Promise(setImmediate);
  assert.deepEqual(rendered,[22]);
});

test('empty dispatch selections do not request an invalid route preview', () => {
  const timers = [];
  let requests = 0;
  const c = setup({
    setTimeout: fn => (timers.push(fn), timers.length),
    clearTimeout() {},
  });
  c.Game.DispatchRoute.loadTerrain = () => Promise.resolve(null);
  c.Game.API = { client: { post: () => (requests++, Promise.resolve({})) } };
  const hint = { isConnected: true, textContent: '', setAttribute() {}, querySelector() {} };
  c.Game.World.bindRoutePreview({ addEventListener() {} }, hint, () => ({ army: {} }), {});
  assert.equal(timers.length, 0);
  assert.equal(requests, 0);
  assert.equal(hint.textContent, '选择兵力后计算实际路线和时间');
});

test('dispatch with default selected units requests route preview immediately', () => {
  const timers = [];
  let requests = [];
  const c = setup({
    setTimeout: fn => (timers.push(fn), timers.length),
    clearTimeout() {},
  });
  c.Game.DispatchRoute.loadTerrain = () => Promise.resolve(null);
  c.Game.API = { client: { post: (url, payload) => (requests.push(payload), Promise.resolve({ distance: 10, seconds: 15 })) } };
  const hint = { isConnected: true, textContent: '', innerHTML: '', setAttribute() {}, querySelector() {} };
  c.Game.World.bindRoutePreview({ addEventListener() {} }, hint, () => ({ army: { scout: 1 } }), { start: '主城', end: '野地' });
  assert.equal(timers.length, 1, '应设置路线请求防抖计时器');
  timers.pop()();
  assert.equal(requests.length, 1, '应发出路线计算请求');
  assert.deepEqual(requests[0].army, { scout: 1 }, '应携带已选中的兵力');
});

test('onDispatchInputChange and slider change refresh route preview', () => {
  const timers = [];
  let refreshed = 0;
  const c = setup();
  c.Game.World._refreshDispatchRoute = () => { refreshed++; };
  c.document = {
    getElementById: (id) => {
      if (id.startsWith('dslider_')) return { max: '100', value: '0', style: { setProperty() {} } };
      if (id.startsWith('dqty_')) return { max: '100', value: '0' };
      return null;
    }
  };
  c.Game.World.onDispatchInputChange('scout', 1);
  assert.equal(refreshed, 1, '修改数量输入框应刷新行军路线');
  c.Game.World.onDispatchSliderChange('scout', 2);
  assert.equal(refreshed, 2, '滑动滑块应刷新行军路线');
});

