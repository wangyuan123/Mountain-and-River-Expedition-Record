const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
  const c = vm.createContext({console, Math, Map, Promise, Game:{}});
  c.window = c;
  c.Image = class { set src(value) { this.url=value; this.onload(); } };
  c.document = {createElement() {
    const canvas={images:[]};let matrix=[1,0,0,1,0,0];
    canvas.getContext=()=>({
      createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4)}),
      putImageData:p=>{canvas.pixels=p.data;},
      setTransform:(...m)=>{matrix=m;},
      drawImage:(image,x,y,w,h)=>{
        const [a,b,c,d,e,f]=matrix;
        const corners=[[x,y],[x+w,y],[x,y+h],[x+w,y+h]].map(([px,py])=>[a*px+c*py+e,b*px+d*py+f]);
        const xs=corners.map(p=>p[0]),ys=corners.map(p=>p[1]);
        canvas.images.push({url:image.url,x:Math.min(...xs),y:Math.min(...ys),w:Math.max(...xs)-Math.min(...xs),h:Math.max(...ys)-Math.min(...ys)});
      },
      beginPath(){},moveTo(){},quadraticCurveTo(){},stroke(){},fillRect(){},lineTo(){}
    });
    return canvas;
  }};
  for(const file of ['constants.js','map-camera.js','map-terrain.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',file),'utf8'),c);
  return c.Game.MapTerrain;
}

test('cell edges softly mix meadow with soil on all four sides, leaving interiors untouched',()=>{
  const t=setup(), canvas=t.createTile(25,25,200), side=canvas.width;
  const start=100*t.density-t.padding;
  function hash(x,y) {
    let n=Math.imul(x,374761393)+Math.imul(y,668265263);
    n=Math.imul(n^(n>>>13),1274126177);
    return ((n^(n>>>16))>>>0)/4294967295;
  }
  function noise(x,y) {
    const ix=Math.floor(x),iy=Math.floor(y);let fx=x-ix,fy=y-iy;
    fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);
    return (hash(ix,iy)*(1-fx)+hash(ix+1,iy)*fx)*(1-fy)+
      (hash(ix,iy+1)*(1-fx)+hash(ix+1,iy+1)*fx)*fy;
  }
  function originalGreen(px,py) {
    const wx=start+px,wy=start+py;
    const patch=Math.fround(noise(wx/54,wy/54)*.7+noise(wx/11+37,wy/11+71)*.3);
    const gx=Math.floor(px/16)*16,gy=Math.floor(py/16)*16,tx=(px-gx)/16,ty=(py-gy)/16;
    const s=(x,y)=>t.sample((start+x)/64,(start+y)/64,200).grass;
    const grass=(s(gx,gy)*(1-tx)+s(gx+16,gy)*tx)*(1-ty)+(s(gx,gy+16)*(1-tx)+s(gx+16,gy+16)*tx)*ty;
    return Math.round(81+Math.max(0,Math.min(1,grass*.52+(patch-.45)*.12))*51+(patch-.5)*22+(hash(wx,wy)-.5)*14);
  }
  for(const [x,y] of [[2,34],[66,34],[34,2],[34,66]]) {
    const green=canvas.pixels[(y*side+x)*4+1];
    assert.ok(originalGreen(x,y)-green>=1,'each edge must retain a faint soil blend');
  }
  const fade=[2,5,8,12].map(x=>originalGreen(x,34)-canvas.pixels[(34*side+x)*4+1]);
  assert.ok(fade[0]>=fade[1] && fade[1]>=fade[2] && fade[2]>=fade[3] && fade[0]>fade[3], 'edge wear must gradually fade into grass');
  assert.equal(canvas.pixels[(34*side+34)*4+1],originalGreen(34,34),'cell center retains its original grass color');
});

test('resource footprints do not remove the base grass cover',()=>{
  const t=setup(), before=t.sample(100.5,100.5,200).grass;
  assert.ok(before>.8);
  const wild=type=>({kind:'wild',type,x:100,y:100});
  for(const type of ['grainfield','ironworks','oil','rarefactory','hill','swamp','plains','snow']) {
    t.updateChunk(6,6,[wild(type)]);
    for(let y=100;y<=101;y+=.25)for(let x=100;x<=101;x+=.25)assert.ok(t.sample(x,y,200).grass>.8);
  }
  t.updateChunk(6,6,[wild('forest'),wild('grassland')]);
  assert.equal(t.sample(100.5,100.5,200).grass,before);
});

test('constraints survive unrelated chunk updates and ignore ownership changes',()=>{
  const t=setup(), oil={kind:'wild',type:'oil',x:100,y:100};
  t.updateChunk(6,6,[oil]);const revision=t.revision();
  assert.equal(t.updateChunk(6,6,[{...oil,occupied:true,level:9}]),false);
  assert.equal(t.revision(),revision);
  t.updateChunk(0,0,[{...oil,x:1,y:1}]);
  assert.ok(t.sample(100.5,100.5,200).grass>.8);
  t.updateChunk(6,6,[]);
  assert.ok(t.sample(100.5,100.5,200).grass>.8);
  t.updateChunk(6,6,[oil]);t.clearTargets();
  assert.ok(t.sample(100.5,100.5,200).grass>.8);
});

test('meadow and woodland artwork avoids resource footprints, including tile-edge halos',async()=>{
  const t=setup();await t.loadMeadows();
  const target={kind:'wild',type:'oil',x:103,y:87};
  t.updateChunk(6,5,[target]);
  let grass=0,forest=0;
  const tiles=[];
  for(let cy=20;cy<=23;cy++)for(let cx=24;cx<=27;cx++) {
    const canvas=t.createTile(cx,cy,200);tiles.push({cx,cy,canvas});
    for(const image of canvas.images) {
      assert.ok(!image.url.includes('grass-lush'),'lush grass must not be rendered on the map');
      if(image.url.includes('grass-'))grass++;else if(image.url.includes('wild-forest'))forest++;
      const x=(cx*t.tileSpan*t.density-t.padding+image.x)/t.density;
      const y=(cy*t.tileSpan*t.density-t.padding+image.y)/t.density;
      assert.ok(x+image.w/t.density<=target.x-.3 || x>=target.x+1.3 ||
        y+image.h/t.density<=target.y-.3 || y>=target.y+1.3,'vegetation must not overlap the resource clearing');
    }
  }
  assert.equal(grass,0,'patchy meadow grass decals are cancelled from the map');
  assert.ok(forest>0,'mix woodland clusters into the meadow');
  const left=tiles.find(t=>t.cx===25&&t.cy===21).canvas;
  const right=tiles.find(t=>t.cx===26&&t.cy===21).canvas;
  const side=left.width,interior=t.tileSpan*t.density;
  for(let row=0;row<side;row++)assert.deepEqual(left.pixels.slice((row*side+interior)*4,(row*side+side)*4),right.pixels.slice(row*side*4,(row*side+side-interior)*4));
  const round=v=>Math.round(v*1e4)/1e4;
  const edgeImages=(canvas,cx)=>canvas.images.map(i=>({url:i.url,x:round(i.x+cx*interior-t.padding),y:round(i.y),w:round(i.w),h:round(i.h)})).filter(i=>i.x<26*interior+t.padding&&i.x+i.w>26*interior-t.padding);
  assert.deepEqual(edgeImages(left,25),edgeImages(right,26),'shared-edge grass and trees use identical world positions and draw order');
});

test('全图不规则黑土地覆盖约30%，草坪覆盖约70%', () => {
  const t = setup();
  let total = 0, grassSamples = 0, soilSamples = 0;
  // 采样 400x400 地图的大规模陆地网格点
  for (let y = 0.5; y < 400; y += 2) {
    for (let x = 0.5; x < 400; x += 2) {
      total++;
      const s = t.sample(x, y, 400);
      if (s.grass >= 0.5) grassSamples++;
      else soilSamples++;
    }
  }
  const grassRatio = grassSamples / total;
  // 与地图地貌标签使用相同的草量阈值。
  assert.ok(grassRatio >= 0.67 && grassRatio <= 0.73, `全图草坪覆盖率预期约70%，实际为 ${(grassRatio * 100).toFixed(1)}%`);
  // 裸土随机覆盖约三成，其余保持浅色草坪。
  assert.ok(soilSamples > 0, '土地必须保留部分自然散布空间');
  const soilRatio = soilSamples / total;
  assert.ok(soilRatio >= 0.27 && soilRatio <= 0.33, `土地覆盖率预期约30%，实际为 ${(soilRatio * 100).toFixed(1)}%`);
});
