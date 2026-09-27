const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');

function fixture(){
 let now=500;
 const targets=[],marches=[],point=()=>({x:0,y:0,set(x,y=x){this.x=x;this.y=y;}});
 class Container {
  constructor(){this.children=[];this.position=point();}
  addChild(child){this.children.push(child);}
  removeChild(child){this.children=this.children.filter(item=>item!==child);}
  destroy(){this.destroyed=true;this.children=[];}
 }
 class Sprite {
  constructor(texture){this.texture=texture;this.anchor=point();this.position=point();this.scale=point();}
  set width(value){this.scale.x=value/this.texture.orig.width;}
  set height(value){this.scale.y=value/this.texture.orig.height;}
 }
 class Graphics {
  constructor(){this.lines=[];}
  clear(){this.lines=[];return this;}
  lineStyle(width,color){this.color=color;return this;}
  moveTo(){return this;}
  lineTo(x,y){this.lines.push({x,y});return this;}
  beginFill(){return this;}
  drawRoundedRect(){return this;}
  endFill(){return this;}
 }
 const texture={orig:{width:100,height:100},baseTexture:{once(){}}};
 const c={console,Date:{now:()=>now},Map,Set,WeakMap,Uint8Array,Game:{MapChunks:function(){this.targets=()=>targets;}}};
 c.PIXI={Container,Sprite,Graphics,Texture:{EMPTY:texture,from:()=>texture},Text:class extends Sprite {
  constructor(text,style){super(texture);this.text=text;this.style=style;}
  get width(){return this.text.length*11;}
 }};
 c.window=c;vm.createContext(c);require('./load-constants.cjs')(c);
 for(const file of ['map-camera.js','map-layout.js','world-map.js']){
  const source=fs.readFileSync(path.join(__dirname,'../js',file),'utf8').replace('  G.WorldMap={','  G.TestMapView=MapView; G.WorldMap={');
  vm.runInContext(source,c);
 }
 c.Game.Core={state:{world:{marches}}};c.Game.DATA={world:{size:200}};c.Game.UNIT_MODEL={truck:'truck.webp'};
 const view=Object.create(c.Game.TestMapView.prototype);
 view.camera=new c.Game.MapCamera(200,100.5,100.5,48);view.camera.width=600;view.camera.height=400;
 view.routes=new Graphics();view.marchLayer=new Container();view.marchMarkers=new Map();view.wake=()=>{};
 const march={id:1,fromX:100,fromY:100,targetX:104,targetY:102,targetKind:'wild_gather',targetId:7,action:'gather',
  route:[[100,100],[104,100],[104,102]],army:{truck:1},startAt:0,arriveAt:1000};
 const tile={kind:'wild',id:7,x:104,y:102,occupied:true,hasGarrison:false};
 const status=()=>view.marchMarkers.get('status:104:102');
 return {view,march,marches,targets,tile,status,setNow:value=>{now=value;}};
}

test('arrival replaces the moving model and route with gathering, then return restores the route until home',()=>{
 const f=fixture(),{view,march,marches,status}=f;marches.push(march);
 view.drawRoutes();const moving=view.marchMarkers.get('1');
 assert.equal(view.routes.lines.length,2);assert.equal(view.routes.color,0x467da5);assert.ok(moving.icon);assert.equal(status(),undefined);
 f.setNow(1000);view.drawRoutes();
 assert.equal(view.routes.lines.length,0);assert.equal(moving.destroyed,true);assert.equal(view.marchMarkers.has('1'),false);
 assert.equal(status().statusText.text,'采集');assert.equal(status().icon,undefined);
 march.gathering=true;f.setNow(2000);view.drawRoutes();assert.equal(status().statusText.text,'采集');
 march.gatherStopped=true;view.drawRoutes();assert.equal(status().statusText.text,'驻扎');
 const arrived=status();
 Object.assign(march,{returning:true,fromX:104,fromY:102,targetX:100,targetY:100,startAt:2000,arriveAt:3000});
 march.route.reverse();f.setNow(2500);view.drawRoutes();
 assert.equal(arrived.destroyed,true);assert.equal(status(),undefined);assert.ok(view.marchMarkers.get('1').icon);
 assert.equal(view.routes.lines.length,2);assert.equal(view.routes.color,0x578657);
 f.setNow(3000);view.drawRoutes();
 assert.equal(view.routes.lines.length,0);assert.equal(view.marchMarkers.size,0);assert.equal(view.marchLayer.children.length,0);
});

test('arrival labels reflect stationing, combat, waiting and scouting instead of leaving a route behind',()=>{
 const f=fixture(),{view,march,marches,status}=f;marches.push(march);f.setNow(1000);march.targetKind='wild';
 for(const [action,label] of [['station','驻扎'],['conquer','交战'],['plunder','交战'],['scout','侦查'],['transport','抵达']]){
  march.action=action;view.drawRoutes();assert.equal(view.routes.lines.length,0);assert.equal(status().statusText.text,label);
 }
 march.targetKind='player';march.action='conquer';march.waitingForBattle=true;view.drawRoutes();
 assert.equal(status().statusText.text,'待战');
 march.waitingForBattle=false;march.battleId=99;view.drawRoutes();assert.equal(status().statusText.text,'交战');
 Object.assign(march,{returning:true,startAt:1000,arriveAt:2000});f.setNow(1500);view.drawRoutes();
 assert.equal(status(),undefined);assert.equal(view.routes.lines.length,2,'返程必须覆盖旧的战斗状态');
 marches.length=0;view.drawRoutes();assert.equal(view.marchMarkers.size,0);assert.equal(view.routes.lines.length,0);
});

test('authoritative stationary states override a future arrival clock without changing ordinary in-flight marches',()=>{
 for(const [extra,label] of [[{gathering:true},'采集'],[{gatherStopped:true},'驻扎'],[{inBattle:true},'交战'],[{waitingForBattle:true},'待战']]){
  const f=fixture();f.marches.push(Object.assign(f.march,extra));f.view.drawRoutes();
  assert.equal(f.view.routes.lines.length,0);assert.equal(f.status().statusText.text,label);
 }
 const f=fixture();f.marches.push(f.march);
 for(const arriveAt of [null,undefined,'invalid',1000]){
  f.march.arriveAt=arriveAt;f.view.drawRoutes();assert.equal(f.status(),undefined);assert.equal(f.view.routes.lines.length,2);
 }
});

test('garrison status survives removal of its march and follows gathering and recall updates',()=>{
 const f=fixture(),{view,march,marches,targets,tile,status}=f;
 targets.push(tile);march.targetKind='wild';march.action='station';marches.push(march);f.setNow(1000);
 view.drawRoutes();const stationed=status();assert.equal(stationed.statusText.text,'驻扎');
 marches.length=0;tile.hasGarrison=true;view.drawRoutes();
 assert.equal(status(),stationed);assert.equal(view.routes.lines.length,0);
 const center=view.camera.screen(104.5,102.5);
  assert.equal(status().position.x,center.x);assert.ok(status().position.y>center.y,'状态放在目标下方');
 view.loadDetail=target=>{view.picked=target;};
 view.pick(status().position);assert.equal(view.picked,tile,'点击状态标记应打开野地详情');
 tile.gathering=true;view.drawRoutes();assert.equal(status().statusText.text,'采集');
 tile.gathering=false;tile.gatherHarvested=10;view.drawRoutes();assert.equal(status().statusText.text,'驻扎');
 tile.hasGarrison=false;view.drawRoutes();assert.equal(status(),undefined);assert.equal(stationed.destroyed,true);
});

test('multiple teams at the same target share one status plate and unrelated traveling teams keep moving',()=>{
 const f=fixture(),{view,march,marches,targets,tile,status}=f;f.setNow(1000);
 targets.push({...tile,hasGarrison:true});
 marches.push(march,{...march,id:2},{...march,id:3,action:'conquer',targetKind:'wild'},
  {...march,id:4,targetX:106,arriveAt:2000});
 view.drawRoutes();
 assert.equal(status().statusText.text,'交战 · 采集 · 驻扎');assert.equal(view.marchMarkers.size,2);
 assert.ok(view.marchMarkers.get('4').icon);assert.equal(view.routes.lines.length,2);
 marches.splice(0,3);view.drawRoutes();assert.equal(status().statusText.text,'驻扎');
});

test('only owned wilds with troops expose a garrison marker',()=>{
 const f=fixture(),{view,targets,tile,status}=f;
 targets.push({...tile,occupied:false,claimed:true,hasGarrison:true,gathering:true,garrison:{truck:10}});
 view.drawRoutes();assert.equal(view.marchMarkers.size,0);
 targets[0]={...tile,garrison:{truck:0}};view.drawRoutes();assert.equal(status(),undefined);
 targets[0].garrison.truck=2;view.drawRoutes();assert.equal(status().statusText.text,'驻扎');
 targets[0].occupied=false;view.drawRoutes();assert.equal(status(),undefined);
});
