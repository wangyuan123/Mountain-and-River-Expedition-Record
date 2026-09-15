const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function setup(){const c=vm.createContext({console,Math,Game:{}});c.window=c;for(const f of ['map-ocean.js','map-terrain.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',f),'utf8'),c);return c;}
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
