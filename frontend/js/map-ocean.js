/* global window */
(function (G) {
  'use strict';
  var cells = '', size = 200, depth = null;
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
  }
  function at(x,y) { x=Math.max(0,Math.min(size-1,x));y=Math.max(0,Math.min(size-1,y));return depth[y*size+x]; }
  function sample(x,y) {
    if(!depth)return -1000;
    var xx=x-.5,yy=y-.5,ix=Math.floor(xx),iy=Math.floor(yy),fx=xx-ix,fy=yy-iy;
    return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
  }
  function sea(x,y) { return x>=0&&y>=0&&x<size&&y<size&&cells[Math.floor(y)*size+Math.floor(x)]==='1'; }
  function paint(channel, land, d, grain) {
    if(d<-.9)return land;
    if(d<0){var sand=[179,168,125][channel];var blend=Math.min(.86,(d+.9)/.9);return land+(sand-land)*blend;}
    var shallow=[94,160,160],deep=[34,77,108],t=Math.min(1,d/9);
    var water=shallow[channel]+(deep[channel]-shallow[channel])*t+grain*2;
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
  G.MapOcean={nearestCoast:nearestCoast,configure:configure,sea:sea,sample:sample,paint:paint,ready:function(){return !!depth;},clear:function(){cells='';depth=null;}};
})(window.Game=window.Game||{});
