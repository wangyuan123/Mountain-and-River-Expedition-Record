const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');

function fixture(){
 let now=500;
 const targets=[],marches=[],point=()=>({x:0,y:0,set(x,y=x){this.x=x;this.y=y;}});
 class Container {
  constructor(){this.children=[];this.position=point();this.scale={x:1,y:1};}
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
  lineStyle(width,color){this.width=width;this.color=color;return this;}
  moveTo(){return this;}
  lineTo(x,y){this.lines.push({x,y});return this;}
  beginFill(){return this;}
  drawRoundedRect(){return this;}
  endFill(){return this;}
 }
 const texture={orig:{width:100,height:100},baseTexture:{once(){}}};
 const c={console,Date:{now:()=>now},Map,Set,WeakMap,Uint8Array,document:{hidden:false},Game:{MapChunks:function(){this.targets=()=>targets;}}};
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
 view.routes=new Graphics();view.routeFlow=new Graphics();view.marchLayer=new Container();view.marchMarkers=new Map();view.wake=()=>{};
 const march={id:1,fromX:100,fromY:100,targetX:104,targetY:102,targetKind:'wild_gather',targetId:7,action:'gather',
  route:[[100,100],[104,100],[104,102]],army:{truck:1},startAt:0,arriveAt:1000};
 const tile={kind:'wild',id:7,x:104,y:102,occupied:true,hasGarrison:false};
 const status=()=>view.marchMarkers.get('status:104:102');
 return {view,march,marches,targets,tile,status,setNow:value=>{now=value;}};
}

test('arrival replaces the moving model and route with gathering, then return restores the route until home',()=>{
 const f=fixture(),{view,march,marches,status}=f;marches.push(march);
 view.drawRoutes();const moving=view.marchMarkers.get('1');
 assert.equal(view.routes.lines.length,23);assert.equal(view.routes.color,0x467da5);assert.ok(moving.icon);assert.equal(status(),undefined);
 assert.equal(view.animatingMarches,true,'在途行军应开启连续帧动画');
 f.setNow(1000);view.drawRoutes();
 assert.equal(view.routes.lines.length,0);assert.equal(moving.destroyed,true);assert.equal(view.marchMarkers.has('1'),false);
 assert.equal(view.animatingMarches,false,'行军抵达后应停止连续帧动画');
 assert.equal(status().statusText.text,'采集');assert.equal(status().icon,undefined);
 march.gathering=true;f.setNow(2000);view.drawRoutes();assert.equal(status().statusText.text,'采集');
 march.gatherStopped=true;view.drawRoutes();assert.equal(status().statusText.text,'驻扎');
 const arrived=status();
 Object.assign(march,{returning:true,fromX:104,fromY:102,targetX:100,targetY:100,startAt:2000,arriveAt:3000});
 march.route.reverse();f.setNow(2500);view.drawRoutes();
 assert.equal(arrived.destroyed,true);assert.equal(status(),undefined);assert.ok(view.marchMarkers.get('1').icon);
 assert.equal(view.routes.lines.length,24);assert.equal(view.routes.color,0x578657);
 f.setNow(3000);view.drawRoutes();
 assert.equal(view.routes.lines.length,0);assert.equal(view.marchMarkers.size,0);assert.equal(view.marchLayer.children.length,0);
});

test('animation frames move the march without redrawing the map and stop at arrival',()=>{
 const f=fixture(),{view,march,marches}=f;marches.push(march);view.drawRoutes();
 const initialX=view.marchMarkers.get('1').position.x;
 let fullDraws=0,renders=0,scheduled=0;
 view.dirty=false;view.pointers=new Map();view.vx=view.vy=0;view.lastLoad=0;
 view.app={stage:{},renderer:{render(){renders++;}}};
 view.draw=function(){fullDraws++;this.drawRoutes();};view.requestChunks=()=>{};
 view.scheduleFrame=()=>{scheduled++;};
 f.setNow(600);view.frame(16);
 assert.equal(fullDraws,0);assert.equal(renders,1);
 assert.ok(view.marchMarkers.get('1').position.x>initialX);
 assert.equal(view.dirty,false);
 assert.equal(scheduled,1);
 f.setNow(1000);view.frame(32);
 assert.equal(fullDraws,0);assert.equal(renders,2);assert.equal(view.animatingMarches,false);
 assert.equal(view.marchMarkers.has('1'),false);
 assert.equal(scheduled,1);
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
 assert.equal(status(),undefined);assert.equal(view.routes.lines.length,23,'返程必须覆盖旧的战斗状态');
 marches.length=0;view.drawRoutes();assert.equal(view.marchMarkers.size,0);assert.equal(view.routes.lines.length,0);
});

test('authoritative stationary states override a future arrival clock without changing ordinary in-flight marches',()=>{
 for(const [extra,label] of [[{gathering:true},'采集'],[{gatherStopped:true},'驻扎'],[{inBattle:true},'交战'],[{waitingForBattle:true},'待战']]){
  const f=fixture();f.marches.push(Object.assign(f.march,extra));f.view.drawRoutes();
  assert.equal(f.view.routes.lines.length,0);assert.equal(f.status().statusText.text,label);
 }
 const f=fixture();f.marches.push(f.march);
 for(const arriveAt of [null,undefined,'invalid',1000]){
  f.march.arriveAt=arriveAt;f.view.drawRoutes();assert.equal(f.status(),undefined);assert.equal(f.view.routes.lines.length,23);
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
 assert.ok(view.marchMarkers.get('4').icon);assert.equal(view.routes.lines.length,23);
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

test('march routes render 2px dashed lines, offensive marches are red, and transport marches are green', () => {
 const f = fixture(), { view, marches } = f;
 const offensive = { id: 10, fromX: 100, fromY: 100, targetX: 54, targetY: 99, targetKind: 'bandit', action: 'conquer',
   route: [[100, 100], [54, 99]], army: { bomber: 200 }, startAt: 0, arriveAt: 1000 };
 const transport = { id: 11, fromX: 100, fromY: 100, targetX: 120, targetY: 120, targetKind: 'player', action: 'transport',
   route: [[100, 100], [120, 120]], army: { transport: 10 }, startAt: 0, arriveAt: 1000 };

 // 1. 进攻路线为红色且粗细为 2px 虚线
 marches.push(offensive);
 view.drawRoutes();
 assert.equal(view.routes.color, 0xd9383a);
 assert.equal(view.routes.width, 2);
 assert.ok(view.routes.lines.length > 2, '行军路线应使用虚线线段替代连续实线');

 // 2. 进攻路线未胜利返城（撤退或失败）仍为红色
 Object.assign(offensive, { returning: true, fromX: 54, fromY: 99, targetX: 100, targetY: 100 });
 view.drawRoutes();
 assert.equal(view.routes.color, 0xd9383a);

 // 3. 进攻路线若战斗胜利返回，航线变为绿色
 Object.assign(offensive, { win: true });
 view.drawRoutes();
 assert.equal(view.routes.color, 0x578657);

 // 4. 携带战利品资源的返城同样判定为胜利并显示为绿色
 delete offensive.win;
 offensive.carryRes = { steel: 500 };
 view.drawRoutes();
 assert.equal(view.routes.color, 0x578657);

 // 5. 运输路线为绿色且粗细为 2px 虚线
 marches.length = 0;
 marches.push(transport);
 view.drawRoutes();
 assert.equal(view.routes.color, 0x578657);
 assert.equal(view.routes.width, 2);
 assert.ok(view.routes.lines.length > 2, '运输路线也以 2px 虚线线段呈现');
});

test('march routes render flowing dashed segments and directional arrows indicating travel heading', () => {
 const f = fixture(), { view, marches } = f;
 view.routeFlow = new (view.routes.constructor)();
 const march = { id: 12, fromX: 100, fromY: 100, targetX: 120, targetY: 100, targetKind: 'bandit', action: 'conquer',
   route: [[100, 100], [120, 100]], army: { fighter: 50 }, startAt: 0, arriveAt: 2000 };
 marches.push(march);
 view.drawRoutes();

 // 验证 routeFlow 成功绘制了高亮箭头和虚线段
 assert.ok(view.routeFlow.lines.length > 0);
 assert.equal(view.routeFlow.color, 0xffffff);

 // 验证时间流逝后流动相位产生位移（箭头坐标变化）
 const firstLineX1 = view.routeFlow.lines[0].x;
 f.setNow(200);
 view.drawRoutes();
 const secondLineX1 = view.routeFlow.lines[0].x;
 assert.notEqual(firstLineX1, secondLineX1, '随时间推进流动虚线与箭头坐标应产生流动位移');
});

