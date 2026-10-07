const test=require('node:test'), assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function fixture(){
 const c={console,Date,Map,Set,WeakMap,Uint8Array,Game:{MapChunks:function(){}},document:{createElement:()=>{
   const ctx={drawImage(source){this.source=source;},getImageData(){return {data:this.source};}};
   return {getContext:()=>ctx};
 }}};c.window=c;vm.createContext(c);
 require('./load-constants.cjs')(c);
 for(const file of ['map-camera.js','map-layout.js','world-map.js']){
  let source=fs.readFileSync(path.join(__dirname,'../js',file),'utf8');
  source=source.replace('  function marchHeading(', '  G.TestMarchHeading=marchHeading;\n  function marchHeading(');
  source=source.replace('  var cache = new G.MapChunks','  G.TestGatherCountdown=gatherCountdown; var cache = new G.MapChunks');
  source=source.replace('  G.WorldMap={','  G.TestMapView=MapView; G.TestMapIcon=icon; G.TestMarkerHeight=markerHeight; G.TestOwnershipCaption=ownershipCaption; G.TestDrawOwnership=drawOwnership; G.TestMarchUnitIcon=marchUnitIcon; G.TestMarchFormation=marchFormation; G.TestPrimaryMarchUnit=primaryMarchUnit; G.TestMarchMarkerIconSize=marchMarkerIconSize;\n  G.WorldMap={');vm.runInContext(source,c);
 }
 const v=Object.create(c.Game.TestMapView.prototype);v.camera=new c.Game.MapCamera(200,100.5,100.5,48);v.camera.width=390;v.camera.height=550;
 v.markerLayer={children:[]};v.visible=[];v.loadDetail=t=>{v.result={target:t};};v.loadSite=(x,y)=>{v.result={site:[x,y]};};
 function marker(target,alpha){
  const rgba=new Uint8Array(16*4);alpha.forEach((a,i)=>rgba[i*4+3]=a);
  const m={x:195,y:275,target,sprite:{width:96,height:96,texture:{orig:{width:4,height:4},baseTexture:{valid:true,resource:{source:rgba}}}}};
  v.markerLayer.children.push(m);v.visible.push(target);return m;
 }
 return {v,marker,c};
}
test('gathering countdown, progress and map caption advance without a server response',()=>{
 const {c,v}=fixture();let now=100000,wakes=0;
 c.Date={now:()=>now};c.Game.fmt=String;c.Game.DATA={wildTypes:{ironworks:{name:'炼铁厂',res:'steel'}}};
 const target={kind:'wild',id:1,type:'ironworks',occupied:true,level:1,gathering:true,gatherStartAt:100000,gatherEndAt:180000,gatherLoad:800};
 const time={},progress={style:{}},amount={};
 const nodes={'[data-gather-time]':time,'[data-gather-progress]':progress,'[data-gather-amount]':amount};
 const panel={querySelector:key=>nodes[key]};
 v.selected=target;v.visible=[target];v.detail={hidden:false,querySelector:()=>panel};v.wake=()=>wakes++;
 v.updateGathering();assert.equal(time.textContent,'采集中，剩余 80 秒');assert.equal(progress.style.width,'0%');
 assert.equal(c.Game.TestOwnershipCaption(target),'我的 · 1级 · 采集中');
 now+=20000;v.updateGathering();assert.equal(time.textContent,'采集中，剩余 60 秒');
 assert.equal(progress.style.width,'25%');assert.equal(amount.textContent,'已开采：200 / 800');
 now+=90000;v.updateGathering();assert.equal(time.textContent,'已采满，请收获');
 assert.equal(progress.style.width,'100%');assert.equal(amount.textContent,'已开采：800 / 800');
 assert.equal(c.Game.TestOwnershipCaption(target),'我的 · 1级 · 待收获');
 v.selected=null;v.detail.hidden=true;v.updateGathering();assert.equal(wakes,4);
 target.gathering=false;v.updateGathering();assert.equal(wakes,4);
 assert.equal(c.Game.TestOwnershipCaption(target),'我的 · 1级');
 target.gathering=true;target.occupied=false;target.claimed=true;
 assert.doesNotMatch(c.Game.TestOwnershipCaption(target),/采集中|待收获/);
 target.occupied=true;c.document.hidden=true;v.updateGathering();assert.equal(wakes,4);
 c.document.hidden=false;v.destroyed=true;v.updateGathering();assert.equal(wakes,4);
});
test('a dispatched gathering army shows a live model-top countdown only after arrival',()=>{
 const {c,v}=fixture();let now=100000,wakes=0;
 c.Date={now:()=>now};
 const tile={kind:'wild',id:7,type:'grainfield',occupied:true};
 const march={targetKind:'wild_gather',targetId:'7',gathering:false,gatherEndAt:180000,gatherMode:'auto'};
 c.Game.Core={state:{world:{marches:[march]}}};
 v.selected=null;v.visible=[tile];v.detail={hidden:true};v.wake=()=>wakes++;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'');
 v.updateGathering();assert.equal(wakes,0);
 march.gathering=true;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'采集剩余 01:20');
 v.updateGathering();assert.equal(wakes,1);
 now+=1000;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'采集剩余 01:19');
 v.updateGathering();assert.equal(wakes,2,'未打开详情也应逐秒重绘地图倒计时');
 now=180000;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'采集完成 · 待自动返城');
 march.gatherMode='manual';
 assert.equal(c.Game.TestGatherCountdown(tile,now),'采集完成 · 待收获');
 march.returning=true;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'');
 v.updateGathering();assert.equal(wakes,2);
 march.returning=false;march.gatherStopped=true;
 assert.equal(c.Game.TestGatherCountdown(tile,now),'');
});

test('model-top countdown combines the correct gathering teams and excludes unrelated or private tasks',()=>{
 const {c}=fixture(),now=100000;
 const tile={kind:'wild',id:7,type:'grainfield',occupied:true};
 const base={targetKind:'wild_gather',targetId:7,gathering:true,gatherMode:'auto',gatherEndAt:180000};
 c.Game.Core={state:{world:{marches:[base,{...base,gatherEndAt:220000},
  {...base,targetId:8,gatherEndAt:999999},{...base,targetKind:'player',gatherEndAt:999999},
  {...base,returning:true},{...base,gatherStopped:true},{...base,gathering:false},
  {...base,gatherEndAt:null}]}}};
 assert.equal(c.Game.TestGatherCountdown(tile,now),'2队采集 · 最晚剩余 02:00');
 assert.equal(c.Game.TestGatherCountdown({...tile,occupied:false,claimed:true},now),'');
 assert.equal(c.Game.TestGatherCountdown({...tile,kind:'player'},now),'');
 c.Game.Core.state.world.marches=[];
 const stationed={...tile,gathering:true,gatherMode:'auto',gatherEndAt:180000};
 assert.equal(c.Game.TestGatherCountdown(stationed,now),'采集剩余 01:20');
 assert.equal(c.Game.TestGatherCountdown(stationed,180000),'采集完成 · 待自动返城');
 assert.equal(c.Game.TestGatherCountdown({...stationed,gatherMode:undefined},180000),'采集完成 · 待收获');
 assert.equal(c.Game.TestGatherCountdown({...stationed,gathering:false},now),'');
});

test('own city detail enters the selected city instead of routing directly home',()=>{
 const {c,v}=fixture();const buttons=[],entered=[];
 c.Game.escapeHtml=String;c.Game.Core={state:{player:{activeCityId:11},world:{cityPos:{x:4,y:105}}}};
 c.Game.DATA={};c.Game.Cities={enter:id=>entered.push(id)};
 c.Game.go=()=>assert.fail('must switch to the selected city before opening its home');
 c.document.createElement=()=>({});
 v.detail={innerHTML:'',querySelector:()=>({appendChild:button=>buttons.push(button)})};
 for(const id of [22,33,11]){
  buttons.length=0;
  const target={kind:'player',id,selfCity:true,name:'我的城市',x:5,y:108,readyAt:0};
  v.selected=target;v.renderDetail(target);
  assert.deepEqual(buttons.map(button=>button.textContent), id === 11 ? ['进入城市'] : ['进入城市', '运输', '派遣']);
  assert.equal(buttons[0].disabled,false);
  buttons[0].onclick();
  assert.equal(entered.at(-1),id);
 }
 assert.deepEqual(entered,[22,33,11]);
});

test('unfinished or non-owned cities cannot invoke city entry',()=>{
 const {c,v}=fixture();const buttons=[];
 c.Game.escapeHtml=String;c.Game.Core={state:{world:{cityPos:{x:4,y:105}}}};c.Game.DATA={};
 c.Game.Cities={enter:()=>assert.fail('unavailable city must not be entered')};
 c.document.createElement=()=>({});
 v.detail={innerHTML:'',querySelector:()=>({appendChild:button=>buttons.push(button)})};
 v.selected={kind:'player',id:22,selfCity:true,name:'建设中的分城',x:5,y:108,readyAt:Date.now()+60000};
 v.renderDetail(v.selected);
 assert.equal(buttons[0].textContent,'城市建设中');
 assert.equal(buttons[0].disabled,true);
 v.act('enterCity',buttons[0]);
 v.selected={...v.selected,selfCity:false,readyAt:0};
 v.act('enterCity',{});
});

test('stationed gathering details expose only the controls appropriate to the mode',()=>{
 const {c,v}=fixture();const buttons=[],panels=[];
 c.Game.fmt=String;c.Game.escapeHtml=String;c.Game.Core={state:{world:{pos:{x:10,y:10},marches:[]}}};
 c.Game.World={renderGatherMarches:()=>''};
 c.Game.DATA={wildTypes:{ironworks:{name:'炼铁厂',res:'steel',icon:'iron.svg'}},units:{truck:{name:'卡车',load:50}}};
 c.document.createElement=()=>({});
 v.detail={innerHTML:'',querySelector:key=>key==='.world-map-actions'?{appendChild:button=>buttons.push(button)}:{insertAdjacentHTML:(position,html)=>panels.push(html)}};
 const tile={kind:'wild',id:1,type:'ironworks',occupied:true,x:15,y:15,level:1,totalRes:1000,mined:0,garrison:{truck:2}};
 function render(extra){buttons.length=0;panels.length=0;v.renderDetail({...tile,...extra});return buttons.map(button=>button.textContent);}
 assert.ok(render({}).includes('采集'));
 for(const mode of ['manual',undefined]){
  const labels=render({gathering:true,gatherMode:mode,gatherStartAt:Date.now()-60000,gatherEndAt:Date.now()-1,gatherLoad:100});
  assert.ok(labels.includes('收获'));assert.ok(!labels.includes('部队回城'));
  assert.match(panels.join(''),/采集全手动/);
 }
 const automatic=render({gathering:true,gatherMode:'auto',gatherStartAt:Date.now()-60000,gatherEndAt:Date.now()-1,gatherLoad:100});
 assert.ok(!automatic.includes('收获'));assert.ok(!automatic.includes('撤回'));
 assert.match(panels.join(''),/采集全自动/);assert.match(panels.join(''),/等待自动收获返城/);
 for(const amount of [0,80]){
  const stopped=render({gathering:false,gatherHarvested:amount,gatherRes:'steel'});
  assert.ok(stopped.includes('部队回城'));assert.ok(!stopped.includes('采集'));assert.ok(!stopped.includes('收获'));
  assert.match(panels.join(''),/等待回城命令/);
 }
 assert.match(c.Game.TestOwnershipCaption({...tile,gathering:true,gatherMode:'auto',gatherEndAt:Date.now()-1}),/待自动返城/);
 assert.match(c.Game.TestOwnershipCaption({...tile,gatherHarvested:0}),/待回城/);
});
test('player art follows actual coast status for own and other cities, including legacy inland naval cities',()=>{
 const {c}=fixture(),icon=c.Game.TestMapIcon;
 for(const selfCity of [true,false]){
  assert.equal(icon({kind:'player',selfCity,coastal:true}),'img/cities/player-city-preview-map.png');
  assert.equal(icon({kind:'player',selfCity,coastal:false,legacyNaval:true}),'img/cities/player-city-preview-map.png');
  assert.equal(icon({kind:'player',selfCity}),'img/cities/player-city-preview-map.png');
 }
 assert.equal(icon({kind:'npc',coastal:true}),'img/map/npc-fortress-orthogonal.webp');
 assert.equal(icon({kind:'bandit',sea:false}),'img/map/npc-fortress-orthogonal.webp');
 for(const [name,vessel] of [
  ['日寇第1舰队','battleship'],['日寇第4航母编队','carrier'],
  ['日寇第5潜艇支队','sub'],['日寇第6驱逐舰队','destroyer']
 ]){
  const image=icon({kind:'bandit',sea:true,name});
  assert.equal(image,'img/npc/japanese-navy/'+vessel+'.webp');
  assert.ok(fs.existsSync(path.join(__dirname,'..',image)));
 }
});
test('player cities extend their ground depth by 28% at every map zoom',()=>{
 const {c}=fixture(),height=c.Game.TestMarkerHeight;
 for(const width of [96,176,352]){
  assert.equal(height({kind:'wild'},width),width);
  assert.ok(Math.abs(height({kind:'player',coastal:false},width)*.90/width-.64)<1e-9);
  assert.ok(Math.abs(height({kind:'player',coastal:true},width)*.60/width-.64)<1e-9);
  assert.equal(height({kind:'npc'},width),width);
  assert.equal(height({kind:'bandit',sea:false},width),width);
  assert.equal(height({kind:'simulated_npc'},width),width);
 }
});
test('march marker uses the home-page unit model for its largest represented unit',()=>{
 const {c}=fixture();c.Game.UNIT_ICON={infantry:'img/units/infantry.svg',ltank:'img/units/ltank.svg'};c.Game.UNIT_MODEL={infantry:'img/units/models/infantry.webp'};
 const primary=c.Game.TestPrimaryMarchUnit;
 assert.equal(primary({army:{infantry:120,ltank:80}}),'infantry');
 assert.equal(primary({army:{unknown:999,ltank:80}}),'ltank');
 assert.equal(primary({army:{unknown:999,ltank:0}}),null);
 assert.equal(c.Game.TestMarchUnitIcon('infantry'),'img/units/models/infantry.webp');
 assert.equal(c.Game.TestMarchUnitIcon('ltank'),'img/units/ltank.svg');
});
test('march marker represents a mixed formation with a main model, two companions and a remaining-type count',()=>{
 const {c}=fixture();c.Game.UNIT_MODEL={infantry:'infantry.webp',ltank:'ltank.webp',rocket:'rocket.webp',fighter:'fighter.webp'};
 const formation=c.Game.TestMarchFormation({army:{infantry:120,ltank:100,rocket:80,fighter:60,unknown:40}});
 assert.equal(formation.primary.id,'infantry');
 assert.deepEqual(Array.from(formation.companions,unit=>unit.id),['ltank','rocket']);
 assert.equal(formation.total,400);
 assert.equal(formation.extraTypes,2);
});
test('march marker renders an enlarged transparent unit model without a circular backdrop',()=>{
 const {c}=fixture();
 assert.equal(c.Game.TestMarchMarkerIconSize(48),48);
 assert.equal(c.Game.TestMarchMarkerIconSize(80),72);
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 assert.doesNotMatch(source,/marker\.backdrop/);
});
test('march routes render above cities, clouds and captions while shadows stay on the sea',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 const layers=source.match(/this\.app\.stage\.addChild\(([^;]+)\);/);
 assert.ok(layers);
 assert.deepEqual(layers[1].split(',').map(layer=>layer.trim()),[
  'this.mapShadow','this.ground','this.groundQ2','this.groundQ3','this.groundQ4','this.groundDetails','this.terrain','this.mapBorder','this.cloudShadowLayer',
  'this.markerLayer','this.marchLayer','this.cloudLayer','this.selectionOutline','this.captionLayer','this.routes'
 ]);
 assert.match(source,/this\.routes\.addChild\(this\.routeFlow\);/,'路线流动效果应随路线保持在最上层');
});
test('city name is shown beside city status instead of below the city artwork',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 assert.doesNotMatch(source,/marker\.label/);
 assert.match(source,/info = caption \+ '·' \+ status \+ '\\n' \+ coordinates;/);
});
test('ground and naval models remain upright and face left or right based on route direction',()=>{
 const {c}=fixture();
 const units=['truck','motor','armored','ltank','htank','assault','rocket','destroyer','sub','battleship','carrier','infantry','special'];
 for(const unit of units) {
  for(const [dx,dy] of [[1,0],[1,1],[0,1],[-1,1],[-1,0],[-1,-1],[0,-1],[1,-1]]) {
   const heading=c.Game.TestMarchHeading([{x:0,y:0},{x:dx,y:dy}],0,unit);
   assert.equal(heading.rotation, 0, unit+' must remain upright without 2D plane tilt');
   const expectedScaleX = unit === 'special' ? (dx > 0 ? 1 : -1) : (dx > 0 ? -1 : 1);
   assert.equal(heading.scaleX, expectedScaleX, unit+' horizontal facing on '+dx+','+dy);
  }
 }
});

test('aircraft noses follow route direction and reverse on the return route',()=>{
 const {c}=fixture();
 const vectors={fighter:[-265,145],bomber:[-255,105],scout:[-145,145],transport:[-260,110]};
 for(const [unit,[fx,fy]] of Object.entries(vectors)) {
  for(const [dx,dy] of [[1,0],[1,1],[0,1],[-1,1],[-1,0],[-1,-1],[0,-1],[1,-1]]) {
   const heading=c.Game.TestMarchHeading([{x:0,y:0},{x:dx,y:dy}],0,unit);
   const angle=Math.atan2(fy,fx)+heading.rotation;
   assert.ok(Math.abs(Math.cos(angle)-dx/Math.hypot(dx,dy))<1e-9,unit);
   assert.ok(Math.abs(Math.sin(angle)-dy/Math.hypot(dx,dy))<1e-9,unit);
   assert.equal(heading.scaleX,1);
  }
 }
});

test('truck headings follow route segments and reverse with the return route',()=>{
 const {v,c}=fixture();let now=100;
 c.Date={now:()=>now};c.Game.UNIT_MODEL={truck:'truck.webp',rocket:'rocket.webp'};
 c.Game.DATA={world:{size:200}};
 c.Game.Core={state:{world:{marches:[]}}};
 c.Game.MapChunks.prototype.targets=()=>[];
 const position=()=>({x:0,y:0,set(x,y){this.x=x;this.y=y;}});
 class Sprite {
  constructor(texture){this.texture=texture;this.anchor={set(){}};this.position=position();this.scale={x:1,y:1};this.rotation=0;}
  set width(value){this.scale.x=value/(this.texture.orig.width||1);}
  set height(value){this.scale.y=value/(this.texture.orig.height||1);}
 }
 class Container {
  constructor(){this.children=[];this.position=position();this.scale={x:1,y:1};this.rotation=0;}
  addChild(child){this.children.push(child);}
  removeChild(child){this.children=this.children.filter(item=>item!==child);}
 }
 c.PIXI={Container,Sprite,Text:class extends Sprite {constructor(text,style){super(c.PIXI.Texture.EMPTY);this.text=text;this.style=style;}},
  Texture:{EMPTY:{orig:{width:1,height:1}},from:()=>({orig:{width:100,height:100},baseTexture:{once(){}}})}};
 v.routes={clear(){},lineStyle(){return this;},moveTo(){return this;},lineTo(){return this;}};
 v.marchLayer=new Container();v.marchMarkers=new Map();v.wake=()=>{};
 const march={id:1,fromX:100,fromY:100,targetX:104,targetY:102,targetKind:'player',
  route:[[100,100],[102,100],[102,102],[104,102]],army:{truck:10,rocket:5},startAt:0,arriveAt:1000};
 c.Game.Core.state.world.marches=[march];
 function assertHeading(marker,expectedScaleX){
  assert.equal(marker.models.rotation, 0, 'truck heading should remain upright on every route segment');
  assert.equal(marker.models.scale.x, expectedScaleX, 'model should face left/right by horizontal scale');
  assert.ok(marker.icon.scale.x>0 && marker.companions[0].scale.x>0,'models keep their own positive scale');
  assert.ok(marker.countText.scale.x>0,'count badge remains readable');
 }
 v.drawRoutes();let marker=v.marchMarkers.get('1');
 assertHeading(marker, -1);
 now=500;v.drawRoutes();assertHeading(marker, -1);
 now=900;v.drawRoutes();assertHeading(marker, -1);
 march.route[3]=[101,102];march.targetX=101;now=900;v.drawRoutes();
 assertHeading(marker, 1);
 march.route.reverse();march.fromX=101;march.fromY=102;march.targetX=100;march.targetY=100;
 march.returning=true;now=100;v.drawRoutes();assertHeading(marker, -1);
 now=500;v.drawRoutes();assertHeading(marker, -1);
 now=900;v.drawRoutes();assertHeading(marker, 1);
 Object.assign(march,{returning:false,fromX:100,fromY:100,targetX:102,targetY:100,route:[[100,100],[102,100]]});
 now=500;v.drawRoutes();assertHeading(marker, -1);
 assert.equal(marker.models.rotation, 0, 'rightward truck remains upright');
 march.route.reverse();Object.assign(march,{returning:true,fromX:102,fromY:100,targetX:100,targetY:100});
 v.drawRoutes();assertHeading(marker, 1);
 assert.equal(marker.models.rotation, 0, 'returning truck remains upright');
 march.returning=false;march.fromX=100;march.fromY=100;
 march.route=[[100,100],[100,100],[100,98]];march.targetX=100;now=0;
 v.drawRoutes();assertHeading(marker, 1);
});
test('humanoid units follow route heading while retaining their standing artwork',()=>{
 const {v,c}=fixture();let now=100;
 c.Date={now:()=>now};c.Game.UNIT_MODEL={infantry:'infantry.webp',special:'special.webp',truck:'truck.webp'};
 c.Game.DATA={world:{size:200}};
 c.Game.Core={state:{world:{marches:[]}}};
 c.Game.MapChunks.prototype.targets=()=>[];
 const position=()=>({x:0,y:0,set(x,y){this.x=x;this.y=y;}});
 class Sprite {
  constructor(texture){this.texture=texture;this.anchor={set(){}};this.position=position();this.scale={x:1,y:1};this.rotation=0;}
  set width(value){this.scale.x=value/(this.texture.orig.width||1);}
  set height(value){this.scale.y=value/(this.texture.orig.height||1);}
 }
 class Container {
  constructor(){this.children=[];this.position=position();this.scale={x:1,y:1};this.rotation=0;}
  addChild(child){this.children.push(child);}
  removeChild(child){this.children=this.children.filter(item=>item!==child);}
 }
 c.PIXI={Container,Sprite,Text:class extends Sprite {constructor(text,style){super(c.PIXI.Texture.EMPTY);this.text=text;this.style=style;}},
  Texture:{EMPTY:{orig:{width:1,height:1}},from:()=>({orig:{width:100,height:100},baseTexture:{once(){}}})}};
 v.routes={clear(){},lineStyle(){return this;},moveTo(){return this;},lineTo(){return this;}};
 v.marchLayer=new Container();v.marchMarkers=new Map();v.wake=()=>{};
 const march={id:2,fromX:100,fromY:100,targetX:104,targetY:102,targetKind:'player',
  route:[[100,100],[104,100],[104,102]],army:{infantry:50},startAt:0,arriveAt:1000};
 c.Game.Core.state.world.marches=[march];
 now=100;v.drawRoutes();
 let marker=v.marchMarkers.get('2');
 assert.equal(marker.models.rotation, 0, 'infantry remains vertically upright');
 assert.equal(marker.models.scale.x, -1, 'infantry should mirror horizontally when marching right');

 now=700;v.drawRoutes();
 assert.equal(marker.models.rotation, 0, 'infantry remains vertically upright on vertical routes');
 assert.equal(marker.models.scale.x, -1, 'infantry keeps facing right when turning down');

 march.route=[[104,100],[100,100]];march.fromX=104;march.targetX=100;march.targetY=100;
 now=100;v.drawRoutes();
 assert.equal(marker.models.rotation, 0, 'infantry remains vertically upright on return');
 assert.equal(marker.models.scale.x, 1, 'infantry should face left when marching left');

 march.army={special:50};v.drawRoutes();
 assert.equal(marker.models.rotation, 0, 'special remains vertically upright');
 assert.equal(marker.models.scale.x, -1, 'right-facing special artwork must mirror for leftward travel');
 march.route=[[100,100],[104,100]];v.drawRoutes();
 assert.equal(marker.models.scale.x, 1, 'special should use its original right-facing artwork for rightward travel');
 march.army={infantry:50,special:10};v.drawRoutes();
 assert.equal(marker.models.scale.x, -1);
 assert.ok(marker.companions[0].scale.x<0, 'special companion cancels the left-facing infantry parent mirror');
 assert.ok(Number.isFinite(marker.companions[0].rotation));

 march.army={truck:20,infantry:10};
 march.route=[[100,100],[100,104]];
 assert.equal(marker.models.rotation, 0, 'truck remains upright on a vertical route');
 assert.ok(Number.isFinite(marker.companions[0].rotation), 'infantry companion heading remains finite alongside truck');
});
test('a visible resource rooftop outside its ground cell opens the resource instead of building a city',()=>{
 const {v,marker}=fixture();const t={kind:'wild',id:1,type:'ironworks',x:100,y:100};
 marker(t,[0,255,255,0,255,255,255,255,255,255,255,255,0,255,255,0]);
 v.pick({x:195,y:235});assert.equal(v.result.target,t);
});
test('transparent corners remain empty ground and overlapping artwork follows painting order',()=>{
 const {v,marker}=fixture();const back={kind:'wild',id:1,x:100,y:100},front={kind:'wild',id:2,x:101,y:100};
 marker(back,Array(16).fill(255));marker(front,[0,255,255,0,255,255,255,255,255,255,255,255,0,255,255,0]);
 v.pick({x:195,y:235});assert.equal(v.result.target,front);
 v.pick({x:151,y:231});assert.equal(v.result.target,back);
 v.markerLayer.children.shift();v.visible.shift();v.pick({x:151,y:231});assert.ok(v.result.site);
});
test('ground cell fallback still works and CSS-projected events share the same picking coordinates',()=>{
 const {v,marker}=fixture(),t={kind:'wild',id:1,x:100,y:100};marker(t,Array(16).fill(0));
 v.inputCorners=[{x:30,y:100},{x:498,y:100},{x:585,y:800},{x:25,y:800}].map(p=>({getBoundingClientRect:()=>({left:p.x,top:p.y})}));
 const q=v.local({clientX:270,y:0,clientY:350});assert.ok(Number.isFinite(q.x)&&Number.isFinite(q.y));
 v.pick({x:195,y:275});assert.equal(v.result.target,t);
 v.pick({x:20,y:20});assert.ok(v.result.site);
});

test('selection outline is shown for empty land, all wild terrain, npc and player cities',()=>{
 const {v,c}=fixture();
 c.Game.DATA={wildTypes:{forest:{res:null},snow:{res:null},oil:{res:'oil'},grainfield:{res:'food'}}};
 c.Game.MapOcean={sea:(x,y)=>x===199};
 for(const target of [
  {kind:'site'},
  {kind:'wild',type:'forest'},
  {kind:'wild',type:'snow'},
  {kind:'wild',type:'oil'},
  {kind:'wild',type:'grainfield'},
  {kind:'wild',type:'unknown'},
  {kind:'npc'},
  {kind:'player'}
 ]){
  v.selected={...target,x:10,y:10};assert.equal(v.showSelectionOutline(),true);
  v.selected.x=199;assert.equal(v.showSelectionOutline(),false);
 }
 v.selected=null;assert.equal(v.showSelectionOutline(),false);
});

test('map labels distinguish own, other and unclaimed territory without treating claimed as mine',()=>{
 const {c}=fixture();c.Game.DATA={wildTypes:{oil:{name:'油田',res:'oil'},forest:{res:null}}};
 const caption=c.Game.TestOwnershipCaption;
 assert.equal(caption({kind:'player',selfCity:true}),'我的城市');
 assert.equal(caption({kind:'player',selfCity:false,ownerName:'远山'}),'');
 const wild={kind:'wild',type:'oil',level:1};
 assert.equal(caption({...wild,occupied:true,claimed:true}),'我的 · 1级');
 assert.equal(caption({...wild,occupied:false,claimed:true,ownerName:'远山'}),'油田 · 1级');
 assert.equal(caption({...wild,occupied:false,claimed:false}),'油田 · 1级');
 assert.equal(caption({kind:'npc'}),'');
 assert.equal(caption({kind:'wild',type:'forest'}),'');
 assert.equal(caption({kind:'wild',type:'forest',occupied:true,level:3}),'我的 · 3级');
});

test('reused badges update on capture and release, and clicking a badge opens its target',()=>{
 const {v,c,marker}=fixture();c.Game.DATA={wildTypes:{oil:{name:'油田',res:'oil'},forest:{res:null}}};
 const target={kind:'wild',type:'oil',id:1,x:100,y:100,level:1,ownerName:'远山'};
 const m=marker(target,Array(16).fill(0));
 const g={};for(const key of ['clear','lineStyle','beginFill','drawRoundedRect','endFill','drawPolygon','moveTo','lineTo'])g[key]=()=>g;
 m.ownershipPlate=g;m.ownershipText={text:'',width:50,style:{},position:{set(){}}};
 v.captionLayer={children:[{mapMarker:m}]};
 for(const [occupied,claimed,expected] of [[false,false,'油田 · 1级'],[true,true,'我的 · 1级'],[false,true,'油田 · 1级'],[false,false,'油田 · 1级']]){
  Object.assign(target,{occupied,claimed});c.Game.TestDrawOwnership(m,target,-90);
  assert.equal(m.ownershipText.text,expected);assert.equal(g.visible,true);
  v.pick({x:m.x,y:m.y-80});assert.equal(v.result.target,target);
 }
 target.type='forest';c.Game.TestDrawOwnership(m,target,-90);
 assert.equal(g.visible,false);assert.equal(m.ownershipHit,null);
});
