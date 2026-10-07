const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(setImmediate);
function fixture({native=false,portrait=true,rejectLock=false}={}) {
  const events={}, classes=new Set(), button={setAttribute(k,v){this[k]=v;},focus(){this.focused=true;}}, close={focus(){this.focused=true;}};
  const document={documentElement:{style:{overflow:'auto'}},body:{style:{overflow:'scroll'}},fullscreenElement:null};
  let locks=0,unlocks=0,exits=0;
  const c={console,document,Date,Map,Set,Promise,Math,screen:{orientation:{lock(){locks++;return rejectLock?Promise.reject(Error('unsupported')):Promise.resolve();},unlock(){unlocks++;}}},matchMedia:()=>({matches:portrait}),Game:{MapChunks:function(){}}};c.window=c;
  const source=fs.readFileSync(require('node:path').join(__dirname,'../js/world-map.js'),'utf8').replace('  G.WorldMap={','  G.TestMapView=MapView;\n  G.WorldMap={');
  vm.createContext(c);
  require('./load-constants.cjs')(c);
  vm.runInNewContext(source,c);
  const view=Object.create(c.Game.TestMapView.prototype);
  view.shell={classList:{add:v=>classes.add(v),remove:v=>classes.delete(v)},querySelector:s=>s.includes('exit-full')?close:button};
  view.pointers=new Map([[1,{}]]);view.camera={width:844,height:390};view.closeDetail=()=>{};view.resize=()=>{};
  view.on=(node,event,fn)=>{events[event]=fn;};
  if(native)view.shell.requestFullscreen=()=>{document.fullscreenElement=view.shell;return Promise.resolve();};
  document.exitFullscreen=()=>{exits++;document.fullscreenElement=null;return Promise.resolve();};
  return {c,view,document,events,classes,button,close,counts:()=>({locks,unlocks,exits})};
}
test('world map opens in map mode after returning from the list',()=>{
  const {c}=fixture();
  c.Game.Core={state:{world:{}},render(){}};
  assert.equal(c.Game.WorldMap.isMap(),true);
  c.Game.WorldMap.setMode('list');
  assert.equal(c.Game.WorldMap.isMap(),false);
  c.Game.WorldMap.prepareEntry();
  assert.equal(c.Game.WorldMap.isMap(),true);
});
test('map toolbar keeps search and all actions together, without a title',()=>{
  const {c}=fixture();
  c.Game.Core={state:{world:{cityPos:{x:100,y:100}}}};
  c.Game.DATA={world:{size:200}};
  c.Game.MapLayout={bounds:()=>({cx:101,cy:101,span:2})};
  c.Game.MapCamera=function(){};
  const stop=new Error('markup captured');
  const container={innerHTML:'',querySelector(){throw stop;}};
  assert.throws(()=>new c.Game.TestMapView(container),error=>error===stop);
  const toolbar=container.innerHTML.match(/<div class="world-map-toolbar page-backbar">(.*?)<\/div>/);
  assert.ok(toolbar);
  assert.match(toolbar[1], /data-map="back"[^>]*><span>‹ 返回上一步<\/span><\/button>/);
  assert.doesNotMatch(toolbar[1], /data-map="back"[^>]*><span>\[‹ 返回上一步\]<\/span>/);
  assert.match(toolbar[1],/data-map="back".*<form class="world-map-search">.*class="page-back-button" type="submit"><span>\[定位\]<\/span><\/button>.*class="page-back-button" data-map="refresh"><span>\[刷新\]<\/span><\/button><\/form>.*class="page-back-button" data-map="list"><span>查看列表地图&gt;<\/span><\/button>.*data-map="full"/);
  assert.doesNotMatch(toolbar[1],/战略地图/);
  assert.equal(container.innerHTML.match(/class="world-map-search"/g).length,1);
  assert.match(container.innerHTML,/<aside class="world-map-minimap collapsed"><button class="minimap-toggle" type="button" aria-expanded="false" aria-label="展开世界缩略图"><span>世界缩略图<\/span><span class="minimap-toggle-icon">\+<\/span><\/button>/);
  const expand=toolbar[1].match(/<button[^>]*data-map="full"[^>]*>(.*?)<\/button>/);
  const collapse=container.innerHTML.match(/<button[^>]*data-map="exit-full"[^>]*>(.*?)<\/button>/);
  assert.match(expand[0],/aria-label="全屏显示地图" title="全屏显示地图"/);
  assert.match(collapse[0],/aria-label="收起全屏地图" title="收起全屏地图"/);
  assert.match(expand[1],/<svg[^>]*aria-hidden="true"[^>]*><path d="[^"]+"\/><\/svg>/);
  assert.match(collapse[1],/<svg[^>]*aria-hidden="true"[^>]*><path d="[^"]+"\/><\/svg>/);
  assert.equal(expand[1].replace(/<[^>]+>/g,''),'');
  assert.equal(collapse[1].replace(/<[^>]+>/g,''),'');
  assert.notEqual(expand[1],collapse[1]);
});
test('map toolbar back and view buttons retain their actions',()=>{
  const {c,view,events}=fixture();
  let backs=0,lists=0,fulls=0,exits=0;
  c.Game.Core={back(){backs++;}};
  c.Game.WorldMap.setMode=mode=>{if(mode==='list')lists++;};
  view.app={view:{}};
  view.shell.querySelector=()=>({});
  view.enterFullscreen=()=>{fulls++;};
  view.exitFullscreen=()=>{exits++;};
  view.bind();
  for(const action of ['back','list','full','exit-full']){
    events.click({target:{closest:selector=>selector==='[data-map]'?{dataset:{map:action}}:null}});
  }
  assert.deepEqual([backs,lists,fulls,exits],[1,1,1,1]);
});
test('landscape map toolbar moves between the commander row and map without losing actions',()=>{
  const {c,view}=fixture();
  let landscape=true,backs=0,lists=0,fulls=0;
  const right={},listeners=new Map(),classes=new Set();
  const row={querySelector:()=>right,insertBefore(node,before){assert.equal(before,right);node.parentNode=this;}};
  const shell=view.shell;
  shell.firstChild={};
  shell.insertBefore=(node,before)=>{assert.equal(before,shell.firstChild);node.parentNode=shell;};
  shell.classList.toggle=(name,enabled)=>{if(enabled)classes.add(name);else classes.delete(name);};
  shell.querySelector=()=>({});
  const toolbar={parentNode:shell,querySelector:()=>({})};
  view.toolbar=toolbar;view.app={view:{}};view.destroyed=false;
  view.on=(node,event,fn)=>{if(!listeners.has(node))listeners.set(node,{});listeners.get(node)[event]=fn;};
  c.document.querySelector=()=>row;
  c.matchMedia=()=>({matches:landscape});
  c.Game.Core={back(){backs++;}};
  c.Game.WorldMap.setMode=()=>{lists++;};
  view.enterFullscreen=()=>{fulls++;};
  view.bind();
  view.syncToolbar();
  assert.equal(toolbar.parentNode,row);
  assert.ok(classes.has('map-toolbar-in-topbar'));
  const click=action=>listeners.get(toolbar).click({target:{closest:selector=>selector==='[data-map]'?{dataset:{map:action}}:null}});
  click('back');click('list');click('full');
  assert.deepEqual([backs,lists,fulls],[1,1,1]);
  landscape=false;view.syncToolbar();
  assert.equal(toolbar.parentNode,shell);
  assert.ok(!classes.has('map-toolbar-in-topbar'));
});
test('unsupported fullscreen still opens landscape overlay and restores scroll/focus on exit',()=>{
  const f=fixture();f.view.enterFullscreen();
  assert.equal(f.view.fullscreen,true);assert.ok(f.classes.has('world-map-full'));assert.equal(f.close.focused,true);
  assert.equal(f.document.body.style.overflow,'hidden');assert.equal(f.view.pointers.size,0);
  f.view.exitFullscreen();f.view.exitFullscreen();
  assert.equal(f.view.fullscreen,false);assert.equal(f.classes.size,0);assert.equal(f.button['aria-expanded'],'false');
  assert.equal(f.document.body.style.overflow,'scroll');assert.equal(f.document.documentElement.style.overflow,'auto');assert.equal(f.button.focused,true);
});
test('native fullscreen locks landscape and releases only its own fullscreen on exit',async()=>{
  const f=fixture({native:true});f.view.enterFullscreen();await flush();
  assert.deepEqual(f.counts(),{locks:1,unlocks:0,exits:0});f.view.exitFullscreen();await flush();
  assert.deepEqual(f.counts(),{locks:1,unlocks:1,exits:1});
});
test('rejected fullscreen and orientation requests leave the closeable fallback active',async()=>{
  const f=fixture({native:true,rejectLock:true});f.view.enterFullscreen();await flush();assert.equal(f.view.fullscreen,true);f.view.exitFullscreen();
  const g=fixture();g.view.shell.requestFullscreen=()=>Promise.reject(Error('denied'));g.view.enterFullscreen();await flush();assert.equal(g.view.fullscreen,true);g.view.exitFullscreen();
  assert.equal(g.document.body.style.overflow,'scroll');
});
test('closing while fullscreen request is pending also exits a late native entry',async()=>{
  const f=fixture();let resolve;
  f.view.shell.requestFullscreen=()=>new Promise(r=>{resolve=r;});f.view.enterFullscreen();f.view.exitFullscreen();
  f.document.fullscreenElement=f.view.shell;resolve();await flush();assert.equal(f.counts().exits,1);assert.equal(f.counts().locks,0);
});
test('rotated canvas taps and drags map to landscape pixels; minimap uses same inverse rotation',()=>{
  const f=fixture();f.view.fullscreen=true;
  f.view.app={view:{getBoundingClientRect:()=>({left:0,top:0,right:390,width:390,height:844})}};
  const a=f.view.local({clientX:390,clientY:0}),b=f.view.local({clientX:0,clientY:844});
  assert.equal(a.x,0);assert.equal(a.y,0);assert.equal(b.x,844);assert.equal(b.y,390);
  const center=f.view.local({clientX:195,clientY:422});assert.equal(center.x,422);assert.equal(center.y,195);
  const drag=f.view.local({clientX:175,clientY:452});assert.equal(drag.x-center.x,30);assert.ok(Math.abs(drag.y-center.y-20)<1e-9);
  const mini={getBoundingClientRect:()=>({left:200,top:20,right:300,width:100,height:100})};
  const point=f.view.elementPoint(mini,{clientX:275,clientY:95});assert.equal(point.x,.75);assert.equal(point.y,.25);
});
test('physical landscape fullscreen keeps ordinary canvas coordinates',()=>{
  const f=fixture({portrait:false});f.view.fullscreen=true;f.view.app={view:{getBoundingClientRect:()=>({left:0,top:0,right:844,width:844,height:390})}};
  const p=f.view.local({clientX:200,clientY:150});assert.equal(p.x,200);assert.equal(p.y,150);
});
test('minimap click and captured drag update camera coordinates and release cleanly',()=>{
  const {view}=fixture({portrait:false}), handlers={};let captured=null,requests=0,wakes=0;
  const canvas={getContext:()=>({}),getBoundingClientRect:()=>({left:10,top:20,width:100,height:100}),
    setPointerCapture:id=>{captured=id;},hasPointerCapture:id=>captured===id,
    releasePointerCapture:()=>{captured=null;},focus(){}};
  const panel={querySelector:s=>s==='canvas'?canvas:{}};
  view.shell.querySelector=()=>panel;
  view.on=(node,event,fn)=>{if(node===canvas)handlers[event]=fn;};
  view.buildMinimapGround=()=>{};view.wake=()=>{wakes++;};view.requestChunks=()=>{requests++;};
  view.camera={size:200,x:100,y:100,clamp(){}};
  view.initMinimap();
  const event=(x,y,id=1)=>({clientX:x,clientY:y,pointerId:id,pointerType:'mouse',button:0,preventDefault(){}});
  handlers.pointerdown(event(35,95));
  assert.equal(view.camera.x,50);assert.equal(view.camera.y,150);assert.equal(captured,1);
  handlers.pointermove(event(90,40,2));assert.equal(view.camera.x,50);
  handlers.pointermove(event(90,40));assert.equal(view.camera.x,160);assert.equal(view.camera.y,40);
  handlers.pointermove(event(150,-20));assert.equal(view.camera.x,200);assert.equal(view.camera.y,0);
  handlers.pointerup(event(150,-20));assert.equal(captured,null);assert.equal(view.minimapPointer,null);assert.equal(requests,1);
  handlers.pointermove(event(35,95));assert.equal(view.camera.x,200);assert.equal(wakes,3);
});
