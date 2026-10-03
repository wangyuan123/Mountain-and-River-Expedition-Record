/* global window */
(function (G) {
  'use strict';
  var cells = '', size = 200, depth = null, mainland = null;
  function configure(data) {
    if (!data || !Number.isInteger(data.size) || data.size < 1 || typeof data.cells !== 'string' || data.cells.length !== data.size * data.size || /[^01]/.test(data.cells)) throw Error('海陆地形数据无效，请刷新重试');
    cells = data.cells; size = data.size;
    var distance = new Float32Array(cells.length), queue = new Int32Array(cells.length), head = 0, tail = 0;
    distance.fill(1000);
    function adjacent(i, fn) { var x=i%size,y=Math.floor(i/size);if(x)fn(i-1);if(x<size-1)fn(i+1);if(y)fn(i-size);if(y<size-1)fn(i+size); }
    for (var i=0;i<cells.length;i++) {
      var edge=false;adjacent(i,function(n){if(cells[i]!==cells[n])edge=true;});
      if(edge){distance[i]=.5;queue[tail++]=i;}
    }
    while(head<tail){var p=queue[head++];adjacent(p,function(n){if(distance[n]>distance[p]+1){distance[n]=distance[p]+1;queue[tail++]=n;}});}
    depth=distance;for(var j=0;j<cells.length;j++)if(cells[j]==='0')depth[j]=-depth[j];
    mainland = new Uint8Array(cells.length); head = 0; tail = 0;
    for (var my = 0; my < size; my++) {
      var mIndex = my * size;
      if (cells[mIndex] === '0') { mainland[mIndex] = 1; queue[tail++] = mIndex; }
    }
    while (head < tail) {
      var mp = queue[head++];
      adjacent(mp, function (n) {
        if (!mainland[n] && cells[n] === '0') { mainland[n] = 1; queue[tail++] = n; }
      });
    }
  }
  function at(x,y) { x=Math.max(0,Math.min(size-1,x));y=Math.max(0,Math.min(size-1,y));return depth[y*size+x]; }
  function sample(x,y) {
    if(!depth)return -1000;
    var xx=x-.5,yy=y-.5,ix=Math.floor(xx),iy=Math.floor(yy),fx=xx-ix,fy=yy-iy;
    return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
  }
  function sea(x,y) { return x>=0&&y>=0&&x<size&&y<size&&cells[Math.floor(y)*size+Math.floor(x)]==='1'; }
  function isOcean(x,y) {
    if (!cells || !cells.length) return false;
    if (x >= 0 && y >= 0 && x < size && y < size) return cells[Math.floor(y)*size+Math.floor(x)]==='1';
    var bx = Math.max(0, Math.min(size - 1, Math.floor(x)));
    var by = Math.max(0, Math.min(size - 1, Math.floor(y)));
    return cells[by * size + bx] === '1';
  }
  function isIsland(x,y) {
    if (!cells || !cells.length || !mainland) return false;
    var bx = Math.max(0, Math.min(size - 1, Math.floor(x)));
    var by = Math.max(0, Math.min(size - 1, Math.floor(y)));
    var idx = by * size + bx;
    return cells[idx] === '0' && mainland[idx] === 0;
  }
  var sandRGB = [179,168,125], shallowRGB = [94,160,160], deepRGB = [34,77,108];
  function paint(channel, land, d, grain) {
    if(d<-.9)return land;
    if(d<0){var blend=Math.min(.86,(d+.9)/.9);return land+(sandRGB[channel]-land)*blend;}
    var t=Math.min(1,d/9), shallow=shallowRGB[channel], deep=deepRGB[channel];
    var water=shallow+(deep-shallow)*t+grain*2;
    if(d<.16)water+=22*(1-d/.16);return water;
  }
  function nearestCoast(x,y,blocked) {
    var best=null,distance=Infinity;
    for(var yy=0;yy<size-1;yy++)for(var xx=0;xx<size-1;xx++){
      if(sea(xx,yy)||sea(xx+1,yy)||sea(xx,yy+1)||sea(xx+1,yy+1))continue;
      if(![sea(xx-1,yy),sea(xx-1,yy+1),sea(xx+2,yy),sea(xx+2,yy+1),sea(xx,yy-1),sea(xx+1,yy-1),sea(xx,yy+2),sea(xx+1,yy+2)].some(Boolean))continue;
      if(blocked&&blocked(xx,yy))continue;
      var d=Math.abs(xx-x)+Math.abs(yy-y);if(d<distance){best={x:xx,y:yy};distance=d;}
    }return best;
  }
  // 每种云形使用独立轮廓，避免共用椭圆底座后全部看起来像同一朵积云。
  var cloudShapes = [
    // 高低错落的积云。
    [[68,99,43,27],[110,83,46,37],[153,61,47,49],[200,83,45,35],[248,104,43,24],[160,112,80,18]],
    // 细长的带状薄云。
    [[65,93,44,11],[114,84,64,15],[171,79,67,18],[228,86,56,13],[274,92,26,8]],
    // 中间留有空隙的双团云。
    [[68,99,42,25],[92,78,32,34],[119,101,35,21],[218,86,39,31],[247,104,43,23]],
    // 宽而低的层积云。
    [[65,102,42,20],[108,95,44,28],[157,99,46,24],[204,91,42,29],[253,106,42,18]],
    // 向右上方拉伸的卷云。
    [[57,113,30,9],[101,103,43,13],[147,89,48,16],[192,73,46,16],[233,57,35,11],[264,48,22,6]],
    // 紧凑、竖向较高的云团。
    [[126,111,44,23],[126,77,35,36],[155,50,30,32],[183,75,32,35],[191,113,42,24],[160,108,42,28]],
    // 分散的小云絮。
    [[61,66,28,17],[91,56,23,20],[135,101,32,21],[160,88,27,25],[214,59,28,16],[256,115,25,13]],
    // 前端饱满、尾部逐渐变细的拖尾云。
    [[76,90,43,32],[111,67,42,43],[146,93,47,30],[191,105,42,18],[232,115,34,12],[271,124,24,7]]
  ];
  /** 为每个云簇生成稳定的随机特征；不同盐值分别控制位置、云形和浓淡，避免逐帧闪变。 */
  function cloudRandom(x,y,salt) {
    var seed=Math.sin(x*127.1+y*311.7+salt*74.7)*43758.5453;
    return seed-Math.floor(seed);
  }
  /**
   * 按固定世界坐标生成稀疏海上云簇，平移镜头时保持位置、云形与基础透明度连续。
   * @param {Object} bounds - 镜头的世界坐标范围。
   * @param {number} seconds - 动画经过的秒数；传 0 时保持静止。
   * @returns {Object[]} 位于海洋上空的云簇位置、尺寸与透明度。
   */
  function clouds(bounds, seconds) {
    if (!depth) return [];
    var result = [];
    for (var gy = Math.floor((bounds.minY-4)/7); gy <= Math.floor((bounds.maxY+4)/7); gy++) {
      for (var gx = Math.floor((bounds.minX-4)/5); gx <= Math.floor((bounds.maxX+4)/5); gx++) {
        var random = cloudRandom(gx,gy,0);
        var phase = random*Math.PI*2;
        var x = gx*5+1+random*3+Math.sin(seconds*.035+phase)*.4;
        var y = gy*7+1+(random*7%1)*5+Math.sin(seconds*.025+phase)*.12;
        if (!sea(x,y)) continue;
        // 同时检查云体四角和地图边缘，给抬升高度与投影留出余量；靠岸逐渐淡出。
        var width = 2.2+random, radius = width/2;
        if (x-radius<0 || x+radius>=size || y-radius-.65<0 || y+radius+.3>=size) continue;
        var clearance = Math.min(sample(x-radius,y-radius-.65),sample(x+radius,y-radius-.65),
          sample(x-radius,y+radius+.3),sample(x+radius,y+radius+.3));
        // 透明度 60%–90% 对应 alpha 0.1–0.4；每朵独立取值，海岸淡出仍可进一步降低 alpha。
        var opacity = .1+cloudRandom(gx,gy,2)*.3;
        var alpha = Math.max(0,Math.min(1,(sample(x,y)-2.5)/2))*Math.max(0,Math.min(1,(clearance-.3)/1.5))*opacity;
        if (alpha <= 0) continue;
        result.push({key:gx+','+gy,x:x,y:y,width:width,alpha:alpha,variant:Math.floor(cloudRandom(gx,gy,1)*cloudShapes.length)});
      }
    }
    return result;
  }
  /**
   * 用柔边渐变生成透明白云纹理，复用纹理即可漂移，无需逐帧绘制画布。
   * @param {number} variant - 云体外形变体编号。
   * @returns {HTMLCanvasElement} 带柔和明暗和透明边缘的云体画布。
   */
  function createCloud(variant) {
    var canvas = document.createElement('canvas'); canvas.width=320; canvas.height=160;
    var ctx = canvas.getContext('2d');
    function puff(x,y,rx,ry,light) {
      ctx.save(); ctx.translate(x,y); ctx.scale(rx,ry);
      var gradient=ctx.createRadialGradient(-.15,-.28,.08,0,0,1);
      gradient.addColorStop(0,light?'rgba(255,255,250,.98)':'rgba(219,231,232,.78)');
      gradient.addColorStop(.48,light?'rgba(249,252,249,.95)':'rgba(209,225,229,.68)');
      gradient.addColorStop(.75,light?'rgba(233,242,242,.76)':'rgba(198,218,224,.4)');
      gradient.addColorStop(1,'rgba(235,245,245,0)');
      ctx.fillStyle=gradient;ctx.beginPath();ctx.arc(0,0,1,0,Math.PI*2);ctx.fill();ctx.restore();
    }
    var shape=cloudShapes[variant%cloudShapes.length];
    // 明暗都沿各自轮廓绘制，保留薄云的细长边缘和碎云之间的空隙。
    shape.forEach(function(p){puff(p[0],p[1]+p[3]*.16,p[2]*.95,p[3]*.8,false);});
    shape.forEach(function(p){puff(p[0],p[1],p[2],p[3],true);});
    return canvas;
  }
  G.MapOcean={nearestCoast:nearestCoast,configure:configure,sea:sea,isOcean:isOcean,isIsland:isIsland,sample:sample,paint:paint,clouds:clouds,createCloud:createCloud,ready:function(){return !!depth;},clear:function(){cells='';depth=null;mainland=null;}};
})(window.Game=window.Game||{});
