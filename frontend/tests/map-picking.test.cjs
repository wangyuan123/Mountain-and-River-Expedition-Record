const test=require('node:test'), assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function fixture(){
 const c={console,Date,Map,Set,WeakMap,Uint8Array,Game:{MapChunks:function(){}},document:{createElement:()=>{
   const ctx={drawImage(source){this.source=source;},getImageData(){return {data:this.source};}};
   return {getContext:()=>ctx};
 }}};c.window=c;vm.createContext(c);
 for(const file of ['map-camera.js','map-layout.js','world-map.js']){
  let source=fs.readFileSync(path.join(__dirname,'../js',file),'utf8');
  source=source.replace('  G.WorldMap={','  G.TestMapView=MapView; G.TestMapIcon=icon; G.TestOwnershipCaption=ownershipCaption; G.TestDrawOwnership=drawOwnership; G.TestMarchUnitIcon=marchUnitIcon; G.TestMarchFormation=marchFormation; G.TestPrimaryMarchUnit=primaryMarchUnit; G.TestMarchMarkerIconSize=marchMarkerIconSize;\n  G.WorldMap={');vm.runInContext(source,c);
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
test('player art follows actual coast status for own and other cities, including legacy inland naval cities',()=>{
 const {c}=fixture(),icon=c.Game.TestMapIcon;
 for(const selfCity of [true,false]){
  assert.equal(icon({kind:'player',selfCity,coastal:true}),'img/cities/harbor.webp');
  assert.equal(icon({kind:'player',selfCity,coastal:false,legacyNaval:true}),'img/cities/garden-citadel.webp');
  assert.equal(icon({kind:'player',selfCity}),'img/cities/garden-citadel.webp');
 }
 assert.equal(icon({kind:'npc',coastal:true}),'img/map/npc-fortress.webp');
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
 assert.equal(c.Game.TestMarchMarkerIconSize(48),36);
 assert.equal(c.Game.TestMarchMarkerIconSize(80),56);
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 assert.doesNotMatch(source,/marker\.backdrop/);
});
test('marching models render above city artwork while routes and captions keep their layers',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 const layers=source.match(/this\.app\.stage\.addChild\(([^;]+)\);/);
 assert.ok(layers);
 assert.deepEqual(layers[1].split(',').map(layer=>layer.trim()),[
  'this.ground','this.groundDetails','this.terrain','this.routes',
  'this.markerLayer','this.marchLayer','this.selectionOutline','this.captionLayer'
 ]);
});
test('city name is shown beside city status instead of below the city artwork',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../js/world-map.js'),'utf8');
 assert.doesNotMatch(source,/marker\.label/);
 assert.match(source,/info = caption \+ '·' \+ status \+ '\\n' \+ coordinates;/);
});
test('marching models face the current route direction, including turns and returning marches',()=>{
 const {v,c}=fixture();let now=500;
 c.Date={now:()=>now};c.Game.UNIT_MODEL={truck:'truck.webp',rocket:'rocket.webp'};
 c.Game.DATA={world:{size:200}};
 c.Game.Core={state:{world:{marches:[]}}};
 c.Game.MapChunks.prototype.targets=()=>[];
 const position=()=>({x:0,y:0,set(x,y){this.x=x;this.y=y;}});
 class Sprite {
  constructor(texture){this.texture=texture;this.anchor={set(){}};this.position=position();this.scale={x:1,y:1};}
  set width(value){this.scale.x=value/(this.texture.orig.width||1);}
  set height(value){this.scale.y=value/(this.texture.orig.height||1);}
 }
 class Container {
  constructor(){this.children=[];this.position=position();}
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
 v.drawRoutes();let marker=v.marchMarkers.get('1');
 assert.ok(marker.icon.scale.x<0);assert.ok(marker.companions[0].scale.x<0);
 now=750;v.drawRoutes();
 assert.ok(marker.icon.scale.x<0,'north-south segment retains its previous horizontal direction');
 now=900;v.drawRoutes();assert.ok(marker.icon.scale.x<0);
 march.route[3]=[101,102];march.targetX=101;now=900;v.drawRoutes();
 assert.ok(marker.icon.scale.x>0,'a leftward turn updates the facing before returning');
 march.route.reverse();march.fromX=101;march.fromY=102;march.targetX=100;march.targetY=100;
 march.returning=true;now=500;v.drawRoutes();
 assert.ok(marker.icon.scale.x<0);assert.ok(marker.companions[0].scale.x<0);
 now=900;v.drawRoutes();
 assert.ok(marker.icon.scale.x>0);assert.ok(marker.companions[0].scale.x>0);
 assert.ok(marker.countText.scale.x>0,'count badge must not be mirrored');
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

test('selection outline is limited to empty land and non-resource wild terrain',()=>{
 const {v,c}=fixture();
 c.Game.DATA={wildTypes:{forest:{res:null},snow:{res:null},oil:{res:'oil'},grainfield:{res:'food'}}};
 c.Game.MapOcean={sea:(x,y)=>x===199};
 for(const target of [{kind:'site'}, {kind:'wild',type:'forest'}, {kind:'wild',type:'snow'}]){
  v.selected={...target,x:10,y:10};assert.equal(v.showSelectionOutline(),true);
  v.selected.x=199;assert.equal(v.showSelectionOutline(),false);
 }
 for(const target of [null,{kind:'npc'}, {kind:'player'}, {kind:'wild',type:'oil'}, {kind:'wild',type:'grainfield'}, {kind:'wild',type:'unknown'}]){
  v.selected=target;assert.equal(v.showSelectionOutline(),false);
 }
});

test('map labels distinguish own, other and unclaimed territory without treating claimed as mine',()=>{
 const {c}=fixture();c.Game.DATA={wildTypes:{oil:{name:'油田',res:'oil'},forest:{res:null}}};
 const caption=c.Game.TestOwnershipCaption;
 assert.equal(caption({kind:'player',selfCity:true}),'我的城市');
 assert.equal(caption({kind:'player',selfCity:false,ownerName:'远山'}),'远山');
 const wild={kind:'wild',type:'oil',level:1};
 assert.equal(caption({...wild,occupied:true,claimed:true}),'我的 · 1级');
 assert.equal(caption({...wild,occupied:false,claimed:true,ownerName:'远山'}),'远山 · 1级');
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
 for(const [occupied,claimed,expected] of [[false,false,'油田 · 1级'],[true,true,'我的 · 1级'],[false,true,'远山 · 1级'],[false,false,'油田 · 1级']]){
  Object.assign(target,{occupied,claimed});c.Game.TestDrawOwnership(m,target,-90);
  assert.equal(m.ownershipText.text,expected);assert.equal(g.visible,true);
  v.pick({x:m.x,y:m.y-80});assert.equal(v.result.target,target);
 }
 target.type='forest';c.Game.TestDrawOwnership(m,target,-90);
 assert.equal(g.visible,false);assert.equal(m.ownershipHit,null);
});
