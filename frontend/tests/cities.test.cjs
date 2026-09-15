const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function runtime(extra = {}) {
  const ctx = { console, TypeError, location:{port:'80'},
    document:{getElementById(){return null;}, addEventListener(){},removeEventListener(){}},
    localStorage:{getItem(){return 'token';},setItem(){},removeItem(){}},
    setTimeout,clearTimeout,setInterval,clearInterval, ...extra };
  ctx.window=ctx;ctx.Game=ctx.Game||{};return vm.createContext(ctx);
}
function load(ctx,name){vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',name),'utf8'),ctx);}
function response(body){return {ok:true,status:200,text:async()=>JSON.stringify(body)};}

test('requests retain their originating city header and reject delayed responses after switching', async()=>{
  const sent=[];let resolve;
  const ctx=runtime({fetch(url,opts){sent.push(opts);return new Promise(r=>{resolve=r;});}});
  load(ctx,'api-client.js');const client=new ctx.Game.ApiClient('/api');client.cityId=11;
  const pending=client.get('/game/state',{silent:true});client.cityId=22;client.invalidateCityRequests();
  resolve(response({resources:{gold:1}}));
  await assert.rejects(pending,/已忽略旧页面响应/);
  assert.equal(sent[0].headers['X-City-Id'],'11');
});

test('switching clears local dispatch drafts while preserving shared reports',()=>{
  const ctx=runtime();load(ctx,'api-client.js');load(ctx,'api.js');
  const old={player:{id:1,activeCityId:11},reports:[{id:5}],_detailOfficerId:8,world:{_dispatchTarget:{id:3},_mapX:5}};
  ctx.Game.API.applyState(old);
  const next={player:{id:1,activeCityId:22},world:{}};ctx.Game.API.applyState(next);
  assert.equal(next._detailOfficerId,undefined);assert.equal(next.world._dispatchTarget,undefined);
  assert.equal(next.world._mapX,undefined);assert.equal(next.reports[0].id,5);
  assert.equal(ctx.Game.API.client.cityId,22);
});

test('background city ticks cannot overwrite visible city resources',()=>{
  const ctx=runtime({Game:{state:{player:{citySlot:1}}}});load(ctx,'ws-client.js');
  const events=[];ctx.Game.WS.on('tick',d=>events.push(d));
  ctx.Game.WS.handleMessage(JSON.stringify({type:'tick',citySlot:0,data:{gold:10}}));
  ctx.Game.WS.handleMessage(JSON.stringify({type:'tick',citySlot:1,data:{gold:20}}));
  assert.equal(events.length,1);assert.equal(events[0].gold,20);
});

test('navigation switch retains an escaped current-city tooltip and incoming warning',()=>{
  const ctx=runtime({Game:{state:{player:{cityName:'<北城>',mainCity:false},cityOverview:{cities:[{incoming:true}]}},
    escapeHtml(s){return s.replace(/</g,'&lt;').replace(/>/g,'&gt;');}}});
  load(ctx,'cities.js');const html=ctx.Game.Cities.nav();
  assert.match(html,/切换/);assert.match(html,/分城/);assert.match(html,/&lt;北城&gt;/);assert.match(html,/city-alert-dot/);
});
