const test=require('node:test'), assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function fixture(){
 const c={console,Date,Map,Set,WeakMap,Uint8Array,Game:{MapChunks:function(){}},document:{createElement:()=>{
   const ctx={drawImage(source){this.source=source;},getImageData(){return {data:this.source};}};
   return {getContext:()=>ctx};
 }}};c.window=c;vm.createContext(c);
 for(const file of ['map-camera.js','map-layout.js','world-map.js']){
  let source=fs.readFileSync(path.join(__dirname,'../js',file),'utf8');
  source=source.replace('  G.WorldMap={','  G.TestMapView=MapView; G.TestMapIcon=icon; G.TestOwnershipCaption=ownershipCaption; G.TestDrawOwnership=drawOwnership;\n  G.WorldMap={');vm.runInContext(source,c);
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
test('player art follows actual coast status for own and other cities, including legacy inland naval cities',()=>{
 const {c}=fixture(),icon=c.Game.TestMapIcon;
 for(const selfCity of [true,false]){
  assert.equal(icon({kind:'player',selfCity,coastal:true}),'img/cities/harbor.webp');
  assert.equal(icon({kind:'player',selfCity,coastal:false,legacyNaval:true}),'img/cities/garden-citadel.webp');
  assert.equal(icon({kind:'player',selfCity}),'img/cities/garden-citadel.webp');
 }
 assert.equal(icon({kind:'npc',coastal:true}),'img/map/npc-fortress.webp');
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
