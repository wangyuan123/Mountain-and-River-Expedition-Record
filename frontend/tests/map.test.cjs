const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function context(game = {}) {
  const c=vm.createContext({console,Date,Map,Set,Promise,Math,setTimeout,clearTimeout,Game:game});c.window=c;return c;
}
function load(c,file){vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',file),'utf8'),c);}
const flush=()=>new Promise(setImmediate);
test('camera zoom keeps finger anchor and camera never moves game coordinates',()=>{
  const c=context();load(c,'map-camera.js');const cam=new c.Game.MapCamera(200,100,100,48);cam.width=390;cam.height=550;
  const before=cam.world(120,200);cam.zoom(1.8,120,200);const after=cam.world(120,200);
  assert.ok(Math.abs(before.x-after.x)<1e-9);assert.ok(Math.abs(before.y-after.y)<1e-9);
  cam.zoom(.8,120,200);const shrunk=cam.world(120,200);
  assert.ok(Math.abs(cam.scale-69.12)<1e-9);
  assert.ok(Math.abs(before.x-shrunk.x)<1e-9);assert.ok(Math.abs(before.y-shrunk.y)<1e-9);
  cam.zoom(.0001,120,200);assert.equal(cam.scale,24);
  const initial=cam.world(120,200);
  assert.ok(Math.abs(before.x-initial.x)<1e-9);assert.ok(Math.abs(before.y-initial.y)<1e-9);
  cam.pan(99999,99999);let b=cam.bounds();assert.ok(b.minX>=0&&b.minY>=0);
  const minimum={scale:cam.scale,x:cam.x,y:cam.y};
  cam.zoom(.0001,195,275);assert.deepEqual({scale:cam.scale,x:cam.x,y:cam.y},minimum);
  cam.zoom(100,195,275);assert.equal(cam.scale,88);
  cam.x=199;cam.y=199;cam.clamp();const chunks=cam.chunks();assert.ok(chunks.every(v=>v.cx<=12&&v.cy<=12));
  const lastVisible=chunks.findIndex(v=>!v.visible);assert.ok(chunks.slice(lastVisible).every(v=>!v.visible));
});
test('map starts slightly smaller and button or pinch can zoom out to half the initial size',()=>{
  const c=context();load(c,'map-camera.js');
  for(const factor of [1/1.3,0.5]){
    const cam=new c.Game.MapCamera(200,100,100);cam.width=1920;cam.height=1080;
    assert.equal(cam.scale,44);
    const anchor=cam.world(120,200);
    for(let step=0;step<4;step++)cam.zoom(factor,120,200);
    assert.equal(cam.scale,22);
    const zoomedAnchor=cam.world(120,200);
    assert.ok(Math.abs(zoomedAnchor.x-anchor.x)<1e-9);
    assert.ok(Math.abs(zoomedAnchor.y-anchor.y)<1e-9);
    cam.zoom(factor,120,200);
    assert.equal(cam.scale,22);
    cam.zoom(2,120,200);
    assert.equal(cam.scale,44);
  }
});
test('chunks deduplicate requests, limit concurrency, preserve cached regions on failure',async()=>{
  const c=context();load(c,'map-chunks.js');const resolvers=[];let calls=0;
  const store=new c.Game.MapChunks(()=>{calls++;return new Promise((resolve,reject)=>resolvers.push({resolve,reject}));},{concurrency:2});
  const cells=[{cx:0,cy:0},{cx:1,cy:0},{cx:2,cy:0}];store.request(cells);store.request(cells);await flush();assert.equal(calls,2);
  resolvers[0].resolve({targets:[{kind:'npc',id:1,x:1,y:1}]});await flush();assert.equal(calls,3);
  resolvers[1].resolve({targets:[]});resolvers[2].resolve({targets:[]});await flush();
  store.request(cells);await flush();assert.equal(calls,3);
  store.invalidate();store.request(cells);await flush();resolvers[3].reject(Error('offline'));resolvers[4].reject(Error('offline'));await flush();
  assert.equal(store.targets({minX:0,maxX:15,minY:0,maxY:15})[0].id,1);
  assert.ok(store.entries.get('0,0').retryAt>Date.now());
  resolvers[5].resolve({targets:[]});await flush();
});
test('cache isolates cities and stale responses cannot overwrite current map',async()=>{
  const c=context();load(c,'map-chunks.js');const resolves=[];
  const store=new c.Game.MapChunks(()=>new Promise(resolve=>resolves.push(resolve)),{concurrency:1});
  store.reset('account:city1');store.request([{cx:1,cy:1}]);await flush();
  store.reset('account:city2');store.request([{cx:2,cy:2}]);resolves[0]({targets:[{id:1}]});await flush();
  assert.equal(store.entries.has('1,1'),false);assert.equal(resolves.length,2);
  resolves[1]({targets:[{id:2}]});await flush();assert.equal(store.entries.get('2,2').data.targets[0].id,2);
});
test('region cache evicts old unneeded chunks',async()=>{
  const c=context();load(c,'map-chunks.js');const store=new c.Game.MapChunks(async()=>({targets:[]}),{limit:3});
  for(let x=0;x<10;x++){store.request([{cx:x,cy:0}]);await flush();}
  assert.equal(store.entries.size,3);assert.ok(store.entries.has('9,0'));assert.ok(!store.entries.has('0,0'));
});
test('map actions resolve fixed IDs after list replacement and preserve dispatch defaults',()=>{
  const state={world:{npcCities:[{id:9,name:'wrong'}],wildTiles:[]}};let route;
  const c=context({DATA:{},Core:{state,views:{}},go:r=>route=r,toast:()=>{}});load(c,'world.js');
  const fresh={kind:'npc',id:42,name:'right',x:10,y:20};c.Game.World.mapAction(fresh,'plunder');
  assert.equal(state.world._dispatchTarget.target.id,42);assert.equal(state.world._dispatchTarget.action,'plunder');assert.equal(route,'dispatch');
  state.world.npcCities=[{id:100}];assert.equal(state.world._dispatchTarget.target.id,42);
  c.Game.World.mapAction({kind:'wild',id:13,type:'forest',x:30,y:30},'conquer');assert.equal(state.world._dispatchTarget.target.id,13);
});
test('map requests abort on timeout so loading slots can recover',async()=>{
  let timeout;
  const c=context();c.AbortController=AbortController;c.location={port:'80',hostname:'localhost'};
  c.localStorage={getItem:()=>'',setItem(){},removeItem(){}};c.document={getElementById:()=>null};
  c.setTimeout=fn=>{timeout=fn;return 1;};c.clearTimeout=()=>{};
  c.fetch=(url,opts)=>new Promise((resolve,reject)=>opts.signal.addEventListener('abort',()=>{const e=Error('aborted');e.name='AbortError';reject(e);}));
  load(c,'api-client.js');const client=new c.Game.ApiClient('/api');
  const req=client.get('/game/world/map/chunk?cx=0&cy=0',{silent:true,timeout:10000});timeout();
  await assert.rejects(req,/加载超时/);
});
test('wild map dispatch renders safely and excludes appointed mayor and commander',()=>{
  const state={world:{pos:{x:100,y:100},wildTiles:[],_dispatchTarget:null},tech:{},resources:{},reports:[],army:{scout:687,infantry:20},officers:[{id:1,name:'first',military:330,star:1,level:1},{id:2,name:'second',military:330,star:1,level:1},{id:3,name:'mayor',role:'mayor',military:500},{id:4,name:'commander',role:'commander',military:900}]};
  const c=context({DATA:{wildTypes:{grainfield:{name:'粮田',res:'food',icon:'img/map/wild-grainfield.webp'}},resources:{food:{name:'粮食'}},units:{scout:{name:'侦察机'},infantry:{name:'步兵'}},starColor:{}},Core:{state,views:{},armyCap:()=>999999},go(){},toast(){},fmt:String});load(c,'world.js');
  c.Game.World.mapAction({kind:'wild',id:5,type:'grainfield',level:2,x:103,y:105},'conquer');
  const v={innerHTML:''};c.Game.World.renderDispatch(v);
  assert.match(v.innerHTML,/<button type="button" class="dispatch-stat-item dispatch-cap-action" onclick="Game.World.showArmyCapInfo\(\)"/);
  assert.match(v.innerHTML,/id="dqty_scout"[^>]*value="1"/);
  assert.match(v.innerHTML,/name="dpOfficer" value="1" checked/);
  assert.doesNotMatch(v.innerHTML,/name="dpOfficer" value="2" checked/);
  assert.doesNotMatch(v.innerHTML,/name="dpOfficer" value="3"/);
  assert.doesNotMatch(v.innerHTML,/name="dpOfficer" value="4"/);
});
test('dispatch cap card opens a dismissible breakdown for the selected city',()=>{
  const body={appendChild(mask){mask.parentNode=body;this.mask=mask;},removeChild(mask){mask.parentNode=null;this.mask=null;}};
  const closeButton={focus(){this.focused=true;}};
  const c=context({DATA:{},Core:{state:{player:{militaryRank:3}},views:{},buildingLevel:()=>10,getCommanderSkills:()=>({leadership:5}),skillBonus:id=>id==='leadership'?0.2:0,armyCap:()=>300000},fmt:String,getMilitaryRankTierInfo:()=>({name:'下士',baseCap:150000})});
  c.document={body,createElement:()=>({setAttribute(){},querySelector:()=>closeButton})};
  load(c,'world.js');
  c.Game.World.showArmyCapInfo();
  assert.match(body.mask.innerHTML,/下士/);
  assert.match(body.mask.innerHTML,/围墙 Lv\.10/);
  assert.match(body.mask.innerHTML,/三军统帅[^<]*当前 Lv\.5，加成 \+20%/);
  assert.match(body.mask.innerHTML,/150000 \+ 100000\) × \(1 \+ 20%\) = 300000/);
  assert.match(body.mask.innerHTML,/市政厅、参谋部和指挥官等级不直接增加上限/);
  assert.equal(closeButton.focused,true);
  closeButton.onclick();
  assert.equal(body.mask,null);
  c.Game.World.showArmyCapInfo();
  body.mask.onkeydown({key:'Escape'});
  assert.equal(body.mask,null);
});
test('only conquest and plunder preselect troops; owned wild dispatch starts empty',()=>{
  const state={world:{pos:{x:100,y:100},wildTiles:[]},tech:{},resources:{},reports:[],army:{scout:8,infantry:20},officers:[]};
  const messages=[];
  const c=context({DATA:{wildTypes:{oil:{name:'油田',res:'oil'}},resources:{oil:{name:'石油'}},units:{scout:{name:'侦察机',load:1},infantry:{name:'步兵',load:10}},starColor:{}},Core:{state,views:{},armyCap:()=>999999},go(){},toast:m=>messages.push(m),fmt:String});load(c,'world.js');
  const target={id:42,type:'oil',level:3,x:110,y:154,owned:true};
  for(const action of ['station','gather','scout','conquer','plunder']){
    state.world._dispatchTarget={kind:action==='gather'?'wild_gather':'wild',action,target};
    const v={innerHTML:''};c.Game.World.renderDispatch(v);
    const selected=action==='conquer'||action==='plunder';
    const inputs=[...v.innerHTML.matchAll(/id="dqty_([^"]+)"[^>]*value="([^"]*)"/g)];
    assert.equal(inputs.length,action==='scout'?1:2);
    for(const input of inputs) assert.equal(input[2],selected?'1':'',action);
    for(const slider of v.innerHTML.matchAll(/id="dslider_[^"]+"[^>]*value="([^"]*)"/g)) assert.equal(slider[1],selected?'1':'0',action);
  }
  state.world._dispatchTarget={kind:'wild',action:'station',target};
  c.document={getElementById:()=>({value:''}),querySelector:()=>null};
  c.Game.World.launchDispatch();
  assert.deepEqual(messages,['请至少选择一种兵种出征']);
});
test('wild scouting opens preparation and submits a scout march only after launch',async()=>{
  const state={world:{pos:{x:100,y:100},wildTiles:[]},tech:{},resources:{},reports:[],army:{scout:8,infantry:20},officers:[]};
  const routes=[],messages=[],requests=[];
  const c=context({DATA:{wildTypes:{oil:{name:'油田',res:'oil',icon:'img/map/wild-oil.webp'}},resources:{oil:{name:'石油'}},units:{scout:{name:'侦察机'},infantry:{name:'步兵'}},starColor:{}},Core:{state,views:{},armyCap:()=>999999},go:r=>routes.push(r),toast:m=>messages.push(m),fmt:String,API:{wildScout(){assert.fail('must not reveal intelligence instantly');},worldDispatch:r=>{requests.push(r);return Promise.resolve();}}});load(c,'world.js');
  const target={kind:'wild',id:42,type:'oil',level:3,x:110,y:154};
  c.Game.World.mapAction(target,'scout');
  assert.equal(routes.at(-1),'dispatch');
  assert.equal(state.world._dispatchTarget.action,'scout');
  assert.equal(requests.length,0);
  assert.equal(messages.length,0);
  assert.equal(target.scouted,undefined);
  const v={innerHTML:''};c.Game.World.renderDispatch(v);
  assert.match(v.innerHTML,/id="dqty_scout"[^>]*value=""/);
  assert.doesNotMatch(v.innerHTML,/id="dqty_infantry"|征服野地守军后/);
  assert.match(v.innerHTML,/抵达后进行侦查并生成情报报告/);
  state.world.wildTiles=[];
  c.document={getElementById:id=>id==='dqty_scout'?{value:'3'}:null,querySelector:()=>null};
  c.Game.World.launchDispatch();await flush();
  assert.equal(requests.length,1);
  assert.equal(requests[0].targetId,42);
  assert.equal(requests[0].targetKind,'wild');
  assert.equal(requests[0].action,'scout');
  assert.equal(requests[0].army.scout,3);
  assert.equal(routes.at(-1),'world');
  assert.deepEqual(messages,['部队出征']);
});
test('terrain detail is repeatable and adjacent tile pixels agree at their shared edge',()=>{
  const c=context();load(c,'map-terrain.js');
  c.document={createElement:()=>{
    const canvas={};
    const ctx={createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4)}),putImageData:p=>{canvas.pixels=p.data;},
      beginPath(){},moveTo(){},quadraticCurveTo(){},stroke(){}};
    canvas.getContext=()=>ctx;return canvas;
  }};
  const terrain=c.Game.MapTerrain;
  for(const [x,y] of [[8,24],[26,24],[39,24]]){
    const left=terrain.createTile(x,y,200),right=terrain.createTile(x+1,y,200);
    const side=left.width, interior=terrain.tileSpan*terrain.density;
    for(let row=0;row<side;row++){
      assert.deepEqual(left.pixels.slice((row*side+interior)*4,(row*side+side)*4),right.pixels.slice(row*side*4,(row*side+side-interior)*4));
    }
    assert.equal(left.pixels[3],255);
  }
  assert.deepEqual(terrain.createTile(25,25,200).pixels,terrain.createTile(25,25,200).pixels);
  for (const x of [0,20,100,190,199]) assert.match(terrain.region(x,100,200), /^(浅草地|黑土地)$/);
});

test('connected snowfield grades from thick center through medium to thin boundary',()=>{
  const c=context();load(c,'map-terrain.js');
  const cells=new Set();
  // Deliberately cross the 16-cell chunk boundary.
  for(let y=14;y<=18;y++)for(let x=14;x<=18;x++)cells.add(x+','+y);
  const variant=c.Game.MapTerrain.snowVariant;
  const counts={thick:0,medium:0,thin:0};
  for(let y=14;y<=18;y++)for(let x=14;x<=18;x++)counts[variant(x,y,cells)]++;
  assert.deepEqual(counts,{thick:1,medium:8,thin:16});
  assert.equal(variant(16,16,cells),'thick');
  cells.delete('17,16');
  assert.equal(variant(16,16,cells),'thin');
  assert.equal(variant(90,90,new Set(['90,90'])),'thin');
});

test('northern snow increases toward north with a snow-free central and southern world',()=>{
 const c=context();load(c,'map-terrain.js');const t=c.Game.MapTerrain;
 for(const size of [100,200])for(const x of [0,size*.25,size*.5,size-1]){
  assert.equal(t.northernSnow(x,0,size),1);
  assert.equal(t.northernSnow(x,size*.5,size),0);
  let last=1;
  for(let y=0;y<size;y++){const next=t.northernSnow(x,y,size);assert(next<=last);last=next;}
 }
 assert.match(t.region(100,5,200),/厚雪地/);
 assert.match(t.region(100,28,200),/中等雪地/);
 assert.equal(t.sample(100,100,200).snow,0);
});
