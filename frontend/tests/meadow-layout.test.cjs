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

test('resources get bare clearings, while forest and grassland can share a meadow',()=>{
  const t=setup(), before=t.sample(100.5,100.5,200).grass;
  assert.ok(before>.8);
  const wild=type=>({kind:'wild',type,x:100,y:100});
  for(const type of ['grainfield','ironworks','oil','rarefactory','hill','swamp','plains','snow']) {
    t.updateChunk(6,6,[wild(type)]);
    for(let y=100;y<=101;y+=.25)for(let x=100;x<=101;x+=.25)assert.equal(t.sample(x,y,200).grass,0);
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
  assert.equal(t.sample(100.5,100.5,200).grass,0);
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
      if(image.url.includes('grass-'))grass++;else if(image.url.includes('wild-forest'))forest++;
      const x=(cx*t.tileSpan*t.density-t.padding+image.x)/t.density;
      const y=(cy*t.tileSpan*t.density-t.padding+image.y)/t.density;
      assert.ok(x+image.w/t.density<=target.x-.3 || x>=target.x+1.3 ||
        y+image.h/t.density<=target.y-.3 || y>=target.y+1.3,'vegetation must not overlap the resource clearing');
    }
  }
  assert.ok(grass>0,'retain connected meadow patches');
  assert.ok(forest>0,'mix woodland clusters into the meadow');
  const left=tiles.find(t=>t.cx===25&&t.cy===21).canvas;
  const right=tiles.find(t=>t.cx===26&&t.cy===21).canvas;
  const side=left.width,interior=t.tileSpan*t.density;
  for(let row=0;row<side;row++)assert.deepEqual(left.pixels.slice((row*side+interior)*4,(row*side+side)*4),right.pixels.slice(row*side*4,(row*side+side-interior)*4));
  const edgeImages=(canvas,cx)=>canvas.images.map(i=>({...i,x:i.x+cx*interior-t.padding})).filter(i=>i.x<26*interior+t.padding&&i.x+i.w>26*interior-t.padding);
  assert.deepEqual(edgeImages(left,25),edgeImages(right,26),'shared-edge grass and trees use identical world positions and draw order');
});
