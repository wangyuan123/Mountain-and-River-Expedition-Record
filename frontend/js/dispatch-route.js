/* global window, document */
(function (G) {
  'use strict';
  var modes = {land:'陆路行军',sea:'海上航线',sea_supply:'海路 · 含补给段',air:'空中航线',airlift:'跨海空运'};
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }
  // North-up, uniform scale: preserve every turn of the authoritative route.
  function layout(points) {
    if (!Array.isArray(points) || !points.length || points.some(function (p) {
      return !Array.isArray(p) || p.length < 2 || !Number.isFinite(p[0]) || !Number.isFinite(p[1]);
    })) return null;
    var xs=points.map(function(p){return p[0];}), ys=points.map(function(p){return p[1];});
    var minX=Math.min.apply(null,xs), maxX=Math.max.apply(null,xs), minY=Math.min.apply(null,ys), maxY=Math.max.apply(null,ys);
    var scale=Math.min(292/Math.max(4,maxX-minX),132/Math.max(4,maxY-minY));
    var cx=(minX+maxX)/2, cy=(minY+maxY)/2;
    function project(p){return [210+(p[0]-cx)*scale,116+(p[1]-cy)*scale];}
    var dx=points[points.length-1][0]-points[0][0], dy=points[points.length-1][1]-points[0][1];
    var direction=dx===0&&dy===0?'同一坐标':['东','东南','南','西南','西','西北','北','东北'][(Math.round(Math.atan2(dy,dx)/(Math.PI/4))+8)%8]+'方向';
    return {points:points.map(project),project:project,scale:scale,cx:cx,cy:cy,direction:direction};
  }
  function terrainImage(l, terrain) {
    if (!terrain) return '';
    var canvas=document.createElement('canvas');canvas.width=420;canvas.height=232;
    var ctx=canvas.getContext('2d');if(!ctx)return '';
    var pixels=ctx.createImageData(420,232);
    for(var y=0;y<232;y++)for(var x=0;x<420;x++) {
      var wx=l.cx+(x-210)/l.scale+.5, wy=l.cy+(y-116)/l.scale+.5;
      var d=terrain.sample?terrain.sample(wx,wy):(terrain.sea(wx,wy)?2:-2);
      var color=d>=0?(d<.65?[174,203,195]:[155,190,202]):(d>-.65?[211,211,180]:[220,228,205]);
      var i=(y*420+x)*4;
      pixels.data[i]=color[0];pixels.data[i+1]=color[1];pixels.data[i+2]=color[2];pixels.data[i+3]=255;
    }
    ctx.putImageData(pixels,0,0);return canvas.toDataURL();
  }
  function loadTerrain() {
    if(G.MapOcean&&G.MapOcean.ready())return Promise.resolve(G.MapOcean);
    if(!G.API||!G.API.client)return Promise.resolve(null);
    // Keep fallback terrain local; a response must not replace another city's map cache.
    return G.API.client.get('/game/world/map/terrain',{silent:true,timeout:10000}).then(function(data){
      if(!data||!Number.isInteger(data.size)||data.size<1||typeof data.cells!=='string'||data.cells.length!==data.size*data.size||/[^01]/.test(data.cells))return null;
      return {sea:function(x,y){return x>=0&&y>=0&&x<data.size&&y<data.size&&data.cells[Math.floor(y)*data.size+Math.floor(x)]==='1';}};
    }).catch(function(){return null;});
  }
  function render(el,route,labels,terrain) {
    labels=labels||{};
    var l=layout(route.points), seconds=Math.max(0,Math.round(Number(route.seconds)||0));
    var duration=seconds<60?seconds+' 秒':Math.floor(seconds/60)+' 分 '+seconds%60+' 秒';
    var h='<section class="dispatch-map-card"><header class="dispatch-map-header"><strong>行军路线预览</strong><span>'+esc(modes[route.mode]||'行军路线')+'</span></header>';
    h+='<div class="dispatch-map-stats"><span>行军距离 <b>'+esc(route.distance)+' <small>格</small></b></span><span>预计抵达 <b>'+duration+'</b></span></div>';
    if(l) {
      var start=route.points[0],end=route.points[route.points.length-1],sp=l.points[0],ep=l.points[l.points.length-1];
      var same=start[0]===end[0]&&start[1]===end[1];
      var color=route.mode==='land'?'#54734e':'#366f91';
      var description='从 '+(labels.start||'出发城市')+' ('+start.join(', ')+') 前往 '+(labels.end||'目标')+' ('+end.join(', ')+')，'+l.direction;
      h+='<div class="dispatch-map-surface"><svg viewBox="0 0 420 232" role="img" aria-label="'+esc(description)+'" xmlns="http://www.w3.org/2000/svg">';
      h+='<rect width="420" height="232" fill="#e4e9df"/>';
      var bg=terrainImage(l,terrain);if(bg)h+='<image href="'+bg+'" width="420" height="232"/>';
      var step=Math.pow(10,Math.floor(Math.log10(48/l.scale))), ratio=48/l.scale/step;
      step*=ratio>5?10:ratio>2?5:2;
      for(var gx=Math.ceil((l.cx-210/l.scale)/step)*step;gx<=l.cx+210/l.scale;gx+=step){
        var px=l.project([gx,l.cy])[0];h+='<path d="M '+px+' 0 V 232" stroke="#738675" stroke-opacity=".17"/><text x="'+(px+4)+'" y="226" class="dispatch-map-grid-label">'+gx+'</text>';
      }
      for(var gy=Math.ceil((l.cy-116/l.scale)/step)*step;gy<=l.cy+116/l.scale;gy+=step){
        var py=l.project([l.cx,gy])[1];h+='<path d="M 0 '+py+' H 420" stroke="#738675" stroke-opacity=".17"/><text x="5" y="'+(py-4)+'" class="dispatch-map-grid-label">'+gy+'</text>';
      }
      var path=l.points.map(function(p){return p.join(',');}).join(' ');
      h+='<polyline points="'+path+'" fill="none" stroke="#fff" stroke-opacity=".8" stroke-width="7" stroke-linejoin="round"/>';
      h+='<polyline points="'+path+'" fill="none" stroke="'+color+'" stroke-width="3" stroke-linejoin="round"'+(/air/.test(route.mode)?' stroke-dasharray="7 5"':'')+'/>';
      var lengths=[],total=0;
      for(var i=1;i<l.points.length;i++){var a=l.points[i-1],b=l.points[i];var len=Math.hypot(b[0]-a[0],b[1]-a[1]);lengths.push(len);total+=len;}
      if(total>40)[.3,.55,.8].forEach(function(f){
        var distance=total*f;
        for(var j=0;j<lengths.length;j++){
          if(distance<=lengths[j]&&lengths[j]>0){
            var a=l.points[j],b=l.points[j+1],t=distance/lengths[j],angle=Math.atan2(b[1]-a[1],b[0]-a[0])*180/Math.PI;
            h+='<path d="M -5 -5 L 2 0 L -5 5" transform="translate('+(a[0]+(b[0]-a[0])*t)+' '+(a[1]+(b[1]-a[1])*t)+') rotate('+angle+')" fill="none" stroke="'+color+'" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>';break;
          }distance-=lengths[j];
        }
      });
      function marker(p,word,fill){return '<circle cx="'+p[0]+'" cy="'+p[1]+'" r="20" fill="'+fill+'" fill-opacity=".13"/><circle cx="'+p[0]+'" cy="'+p[1]+'" r="12" fill="'+fill+'" stroke="white" stroke-width="2.5"/><text x="'+p[0]+'" y="'+(p[1]+4)+'" text-anchor="middle" fill="white" font-size="11" font-weight="700">'+word+'</text>';}
      h+=marker(sp,same?'起终':'起','#54734e');if(!same)h+=marker(ep,'终','#ac7445');
      function tag(p,coord,above){var y=p[1]+(above?-37:22);return '<g transform="translate('+(p[0]-48)+' '+y+')"><rect width="96" height="21" rx="5" fill="#fff" fill-opacity=".92"/><text x="48" y="14" text-anchor="middle" fill="#394e48" font-size="11">('+coord.join(', ')+')</text></g>';}
      h+=tag(sp,start,sp[1]<=ep[1]);if(!same)h+=tag(ep,end,ep[1]<sp[1]);
      h+='<g transform="translate(388 25)"><text x="0" y="-9" text-anchor="middle" fill="#50655d" font-size="10">北</text><path d="M 0 -3 L -5 11 L 0 8 L 5 11 Z" fill="#50655d"/></g>';
      h+='</svg><span class="dispatch-map-direction">'+esc(l.direction)+'</span><span class="dispatch-map-caption">'+(terrain?'海陆地形 · 北向上':'坐标示意 · 地形未加载')+'</span></div>';
      h+='<div class="dispatch-map-endpoints"><div><i class="dispatch-map-dot start"></i><span><small>起点 · 出发城市</small><strong>'+esc(labels.start||'当前城市')+'</strong><code>('+start.join(', ')+')</code></span></div><span class="dispatch-map-to" aria-hidden="true">→</span><div><i class="dispatch-map-dot end"></i><span><small>终点 · 行军目标</small><strong>'+esc(labels.end||'目标位置')+'</strong><code>('+end.join(', ')+')</code></span></div></div>';
    }else h+='<p class="dispatch-map-empty">路线坐标暂不可用，请重新选择部队后重试。</p>';
    h+='<footer class="dispatch-map-footer"><span>'+(route.mode==='air'?'直飞目标':route.mode==='airlift'?'运输机跨海投送':route.mode==='land'?'沿陆地通行':'沿可通航海域行进')+'</span><span>可携资源 <b>'+esc(route.cargoLimit)+'</b>'+(route.reservedLoad?' · 陆军占用运力 '+esc(route.reservedLoad):'')+'</span></footer></section>';
    el.innerHTML=h;
  }
  G.DispatchRoute={layout:layout,render:render,loadTerrain:loadTerrain};
})(window.Game=window.Game||{});
