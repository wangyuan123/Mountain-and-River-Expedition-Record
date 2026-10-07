const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const c = vm.createContext({ console, Map, Set, Math });
c.window = c;
for (const name of ['map-layout.js', 'map-camera.js', 'map-chunks.js']) {
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', name), 'utf8'), c);
}
const layout = c.Game.MapLayout;
test('all four player-city cells select the same target without mutating its coordinates', () => {
  const city = Object.freeze({ kind:'player', id:1, x:100, y:100 });
  const b = layout.bounds(city, 200);
  assert.equal(b.span, 2);
  assert.equal(b.cx, 101);
  for (const [x,y] of [[100.01,100.01],[101.99,100.01],[100.01,101.99],[101.99,101.99]]) {
    assert.equal(layout.pick([city],x,y,200),city);
  }
  assert.equal(layout.pick([city],102,101,200),null);
  assert.equal(layout.pick([city],99.99,101,200),null);
  for (const kind of ['wild']) {
    const target={kind,id:2,x:100,y:100};
    assert.equal(layout.bounds(target,200).span,1);
    assert.equal(layout.pick([target],101.1,100.5,200),null);
  }
});
test('NPC and bandit footprints match player cities, including world edges', () => {
  for (const kind of ['npc','simulated_npc','bandit']) {
    for (const [x,y] of [[100,100],[199,199]]) {
      const target={kind,id:2,x,y}, b=layout.bounds(target,200);
      assert.equal(b.span,2);
      for (const [dx,dy] of [[.01,.01],[1.99,.01],[.01,1.99],[1.99,1.99]]) {
        assert.equal(layout.pick([target],b.x+dx,b.y+dy,200),target);
      }
      assert.equal(layout.pick([target],b.x+2,b.y+1,200),null);
    }
  }
});
test('edge cities keep all four visual cells inside the world', () => {
  for(const [x,y] of [[0,0],[199,0],[0,199],[199,199]]) {
    const city={kind:'player',id:1,x,y}, b=layout.bounds(city,200);
    assert.ok(b.x>=0 && b.y>=0 && b.x+b.span<=200 && b.y+b.span<=200);
    assert.equal(layout.pick([city],x+.5,y+.5,200),city);
    assert.equal(b.span,2);
  }
});
test('existing one-cell targets remain selectable in either drawing order', () => {
  const city={kind:'player',id:1,x:10,y:10};
  const resource={kind:'wild',id:2,x:11,y:11};
  for(const targets of [[city,resource],[resource,city]]) {
    assert.equal(layout.pick(targets,11.5,11.5,200),resource);
    assert.equal(layout.pick(targets,10.5,11.5,200),city);
  }
});
test('player selection covers the same four cells across map zoom levels', () => {
  const city={kind:'player',id:1,x:15,y:15};
  const camera=new c.Game.MapCamera(200,16,16,48);
  camera.width=390;camera.height=550;
  for(const scale of [48,64,88]) {
    camera.scale=scale;
    for(const [x,y] of [[15.1,15.1],[16.9,15.1],[15.1,16.9],[16.9,16.9]]) {
      const screen=camera.screen(x,y), world=camera.world(screen.x,screen.y);
      assert.equal(layout.pick([city],world.x,world.y,200),city);
    }
  }
});
test('expanded view finds a city anchored across a chunk boundary', () => {
  const city={kind:'player',id:1,x:15,y:15};
  const store=new c.Game.MapChunks(async()=>({targets:[]}));
  store.entries.set('0,0',{cx:0,cy:0,data:{targets:[city]}});
  const camera=new c.Game.MapCamera(200,17.2,17.2,88);
  camera.width=88;camera.height=88;
  const targets=store.targets(camera.bounds(2));
  assert.equal(layout.pick(targets,16.8,16.8,200),city);
  assert.ok(camera.chunks().some(chunk=>chunk.cx===0&&chunk.cy===0));
});
test('orthogonal square cells select their full displayed footprint', () => {
  const cam=new c.Game.MapCamera(200,100.5,100.5,48);cam.width=390;cam.height=550;
  const npc={kind:'npc',id:5,x:100,y:100};
  const points=cam.polygon(100,100,1);
  const expected=[171, 251, 219, 251, 219, 299, 171, 299];
  for(let i=0;i<points.length;i++) assert.ok(Math.abs(points[i]-expected[i])<1e-9);
  const empty=cam.world(170,250);
  assert.equal(layout.pick([npc],empty.x,empty.y,200),null);
  for(const [sx,sy] of [[195,275],[175,255],[215,255],[215,295],[175,295]]) {
    const p=cam.world(sx,sy);assert.equal(layout.pick([npc],p.x,p.y,200),npc);
  }
});
test('cardinal neighbors stay aligned along orthogonal axes at every zoom and map location',()=>{
 const cam=new c.Game.MapCamera(200,100,100,48);cam.width=390;cam.height=550;
 for(const scale of [48,64,88])for(const [x,y] of [[1,1],[100,100],[198,198]]){
  cam.scale=scale;
  const center=cam.screen(x,y),north=cam.screen(x,y-1),south=cam.screen(x,y+1),east=cam.screen(x+1,y),west=cam.screen(x-1,y);
  assert.ok(east.x>center.x && Math.abs(east.y-center.y)<1e-9);
  assert.ok(west.x<center.x && Math.abs(west.y-center.y)<1e-9);
  assert.ok(Math.abs(south.x-center.x)<1e-9 && south.y>center.y);
  assert.ok(Math.abs(north.x-center.x)<1e-9 && north.y<center.y);
  for(const [actual,expected] of [[north,[x,y-1]],[south,[x,y+1]],[east,[x+1,y]],[west,[x-1,y]]]){
   const world=cam.world(actual.x,actual.y);
   assert.ok(Math.abs(world.x-expected[0])<1e-9);
   assert.ok(Math.abs(world.y-expected[1])<1e-9);
  }
 }
});
test('drag follows the pointer and all viewport corners stay covered at world edges', () => {
  const cam=new c.Game.MapCamera(200,100,100,48);cam.width=920;cam.height=660;
  const before=cam.screen(101,102);cam.pan(70,-40);const after=cam.screen(101,102);
  assert.ok(Math.abs(after.x-before.x-70)<1e-9);assert.ok(Math.abs(after.y-before.y+40)<1e-9);
  const overflow = c.Game.MapCamera.overflow || 0;
  assert.equal(overflow, 6);
  for(const [x,y] of [[0,0],[200,0],[0,200],[200,200]]) {
    cam.x=x;cam.y=y;cam.clamp();const bounds=cam.bounds(2);
    for(const [sx,sy] of [[0,0],[920,0],[0,660],[920,660]]) {
      const p=cam.world(sx,sy);
      assert.ok(p.x>=-overflow-1e-9&&p.y>=-overflow-1e-9&&p.x<=200+overflow+1e-9&&p.y<=200+overflow+1e-9);
      assert.ok(Math.min(199,Math.max(0,Math.floor(p.x+1e-9)))>=bounds.minX&&Math.min(199,Math.max(0,Math.floor(p.x)))<=bounds.maxX);
      assert.ok(Math.min(199,Math.max(0,Math.floor(p.y+1e-9)))>=bounds.minY&&Math.min(199,Math.max(0,Math.floor(p.y)))<=bounds.maxY);
    }
  }
});
test('pointer coordinates invert perspective, scaling and portrait fullscreen rotation',()=>{
  const transforms=[
    (u,v)=>({x:30+390*u,y:130+550*v}),
    (u,v)=>({x:(30+480*u-40*v)/(1-.17*v),y:(130+580*v)/(1-.17*v)}),
    (u,v)=>({x:390-390*v,y:844*u})
  ];
  for(const project of transforms){
    const corners=[[0,0],[1,0],[1,1],[0,1]].map(([u,v])=>project(u,v));
    for(const [u,v] of [[0,0],[1,1],[.5,.5],[.13,.84],[.89,.16],[-.1,1.2]]){
      const p=project(u,v),actual=layout.unproject(corners,p.x,p.y);
      assert.ok(Math.abs(actual.x-u)<1e-9);assert.ok(Math.abs(actual.y-v)<1e-9);
    }
  }
  assert.equal(layout.unproject(Array(4).fill({x:1,y:1}),1,1),null);
});
