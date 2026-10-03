const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function setup(){const c=vm.createContext({console,Math,Game:{}});c.window=c;for(const f of ['constants.js','map-ocean.js','map-terrain.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',f),'utf8'),c);return c;}
test('authoritative ocean mask preserves cell boundaries and rejects malformed data',()=>{
 const c=setup(),o=c.Game.MapOcean;assert.equal(o.ready(),false);o.configure({size:4,cells:'0011001100110011'});
 assert.equal(o.sea(1.99,2),false);assert.equal(o.sea(2,2),true);assert.equal(o.sea(-1,2),false);assert.equal(o.sea(4,2),false);
 assert.ok(o.sample(1.5,1.5)<0);assert.equal(o.sample(2,1.5),0);assert.ok(o.sample(2.5,1.5)>0);
 assert.throws(()=>o.configure({size:4,cells:'1'}));assert.throws(()=>o.configure({size:2,cells:'ab10'}));
 const candidate=o.nearestCoast(0,0);assert.deepEqual(JSON.parse(JSON.stringify(candidate)),{x:0,y:0});assert.equal(o.nearestCoast(0,0,()=>true),null);
 o.clear();assert.equal(o.ready(),false);
});
test('ocean and shoreline detail textures agree across chunk boundaries',()=>{
 const c=setup();c.Game.MapOcean.configure(JSON.parse(fs.readFileSync(path.join(__dirname,'ocean-fixture.json'),'utf8')));
 c.document={createElement:()=>{const canvas={};canvas.getContext=()=>({createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4)}),putImageData:p=>canvas.pixels=p.data,beginPath(){},moveTo(){},quadraticCurveTo(){},stroke(){}});return canvas;}};
 const t=c.Game.MapTerrain;
 for(const [x,y] of [[36,26],[43,35],[48,30]]){
  const a=t.createTile(x,y,200),b=t.createTile(x+1,y,200),n=a.width,interior=t.tileSpan*t.density;
  for(let row=0;row<n;row++)assert.deepEqual(a.pixels.slice((row*n+interior)*4,(row*n+n)*4),b.pixels.slice(row*n*4,(row*n+n-interior)*4));
 }
 assert.match(t.region(195,100,200),/海洋/);assert.match(t.region(20,100,200),/草地|土地/);
 const ocean=t.createTile(48,25,200).pixels;assert.ok(ocean[2]>ocean[0],'open sea uses blue, not a grass layer');
});
test('clouds stay over sea and keep their world positions across overlapping viewports',()=>{
 const c=setup(),o=c.Game.MapOcean,all={minX:0,minY:0,maxX:199,maxY:199};
 assert.equal(o.clouds(all,0).length,0);
 o.configure(JSON.parse(fs.readFileSync(path.join(__dirname,'ocean-fixture.json'),'utf8')));
 const before=o.clouds(all,0);assert.ok(before.length>0);
 assert.deepEqual(before,o.clouds(all,0));
 const coastal=o.clouds({minX:140,minY:90,maxX:165,maxY:120},0);
 assert.ok(coastal.length>0);
 for(const cloud of coastal)assert.deepEqual(cloud,before.find(other=>other.key===cloud.key));
 for(const seconds of [0,30,90,180,360])for(const cloud of o.clouds(all,seconds)){
  assert.equal(o.sea(cloud.x,cloud.y),true);assert.ok(o.sample(cloud.x,cloud.y)>2.5);
  assert.ok(cloud.alpha>0&&cloud.alpha<=.4,'thin clouds let the sea and ships show through');
  // 云体上缘包含抬升高度，最宽外形也要给海岸留出足够空间。
  for(const dx of [-cloud.width/2,0,cloud.width/2])for(const dy of [-cloud.width*.5-.65,cloud.width*.5-.65]){
   assert.equal(o.sea(cloud.x+dx,cloud.y+dy),true);
  }
 }
 const after=o.clouds(all,30),moving=before.find(cloud=>after.some(other=>other.key===cloud.key));
 assert.notEqual(moving.x,after.find(cloud=>cloud.key===moving.key).x);
 o.configure({size:20,cells:'0'.repeat(400)});assert.equal(o.clouds(all,0).length,0);
 o.clear();assert.equal(o.clouds(all,0).length,0);
});
test('open-sea clouds mix eight shapes and keep independent 60–90 percent transparency',()=>{
 const c=setup(),o=c.Game.MapOcean,bounds={minX:30,minY:30,maxX:160,maxY:160};
 o.configure({size:200,cells:'1'.repeat(200*200)});
 const clouds=o.clouds(bounds,0),later=new Map(o.clouds(bounds,90).map(cloud=>[cloud.key,cloud]));
 assert.equal(new Set(clouds.map(cloud=>cloud.variant)).size,8);
 assert.ok(new Set(clouds.map(cloud=>cloud.alpha.toFixed(3))).size>30,'opacity varies across clouds');
 assert.ok(Math.min(...clouds.map(cloud=>cloud.alpha))<.13);
 assert.ok(Math.max(...clouds.map(cloud=>cloud.alpha))>.37);
 for(const cloud of clouds){
  assert.ok(cloud.alpha>=.1&&cloud.alpha<=.4);
  assert.equal(later.get(cloud.key).alpha,cloud.alpha,'drift does not reroll opacity');
  assert.equal(later.get(cloud.key).variant,cloud.variant,'drift does not reroll shape');
 }
});
