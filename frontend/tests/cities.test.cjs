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
function load(ctx,name){require('./load-constants.cjs')(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../js',name),'utf8'),ctx);}
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

test('city transfer offers logistics for transport and every available unit for dispatch',()=>{
  const content={innerHTML:''};
  const number={dataset:{resource:'gold'},value:'0',max:'50'};
  const progress={};
  const range={dataset:{resourceRange:'gold'},value:'0',style:{setProperty(key,value){progress[key]=value;}}};
  const remaining={textContent:'50'};
  const truckNumber={dataset:{unit:'truck'},value:'0',max:'2'};
  const truckRange={dataset:{unitRange:'truck'},value:'0',style:{setProperty(key,value){progress['unit-'+key]=value;}}};
  const truckRemaining={textContent:'2'};
  const hint={textContent:'',style:{}};
  const form={elements:{action:{value:'transport'}},handlers:{},addEventListener(type,handler){this.handlers[type]=handler;},
    querySelector(selector){return selector.includes('unit-remaining')?truckRemaining:selector.includes('unit-range')?truckRange:selector.includes('data-unit=')?truckNumber:selector.includes('resource-remaining')?remaining:selector.includes('resource-range')?range:number;},
    querySelectorAll(selector){return selector==='[data-resource]'?[number]:selector==='[data-unit]'?[truckNumber]:[];}};
  const document={getElementById(id){return id==='cityDialogContent'?content:id==='cityTransferForm'?form:id==='cityTransferLoad'?hint:null;}};
  const ctx=runtime({document,Game:{
    Core:{state:{player:{cityName:'主城'},army:{truck:2,transport:1,infantry:10},resources:{gold:50},
      cityOverview:{cities:[{id:1,current:true},{id:2,name:'分城',current:false,readyAt:0}]}}},
    DATA:{units:{truck:{name:'卡车',load:50},transport:{name:'运输机'},infantry:{name:'步兵'}}},escapeHtml:String
  }});
  load(ctx,'cities.js');
  ctx.Game.Cities.transferForm(2,'transport');
  assert.match(content.innerHTML,/data-unit="truck"/);
  assert.match(content.innerHTML,/data-unit="transport"/);
  assert.match(content.innerHTML,/data-unit-range="truck"[^>]*max="2"/);
  assert.match(content.innerHTML,/data-unit-range="transport"[^>]*max="1"/);
  assert.doesNotMatch(content.innerHTML,/data-unit="infantry"/);
  assert.match(content.innerHTML,/id="cityTransferLoad"/);
  assert.match(content.innerHTML,/name="commander"/);
  assert.match(content.innerHTML,/城内 50 · 剩余 <span data-resource-remaining="gold">50<\/span>/);
  assert.match(content.innerHTML,/data-resource="gold"[^>]*max="50"/);
  assert.match(content.innerHTML,/data-resource-range="gold"[^>]*max="50"/);
  assert.match(content.innerHTML,/class="recruit-slider-wrap"><input class="recruit-slider"/);
  range.value='37';form.handlers.input.call(form,{target:range});
  assert.equal(number.value,'37');assert.equal(remaining.textContent,'13');assert.equal(progress['--p'],'74.0%');assert.match(hint.textContent,/携带资源 37/);
  number.value='99';form.handlers.input.call(form,{target:number});
  assert.equal(number.value,'50');assert.equal(range.value,'50');assert.equal(remaining.textContent,'0');
  assert.equal(number.max,'50');
  truckRange.value='1';form.handlers.input.call(form,{target:truckRange});
  assert.equal(truckNumber.value,'1');assert.equal(truckRemaining.textContent,'1');assert.equal(progress['unit---p'],'50.0%');
  assert.match(hint.textContent,/部队负重 50/);
  truckNumber.value='8';form.handlers.input.call(form,{target:truckNumber});
  assert.equal(truckNumber.value,'2');assert.equal(truckRange.value,'2');assert.equal(truckRemaining.textContent,'0');
  ctx.Game.Cities.transferForm(2,'rebase');
  assert.match(content.innerHTML,/data-unit="infantry"/);
  assert.match(content.innerHTML,/确认派遣/);
});

test('entering an owned city applies its state before opening home and blocks repeated switches',async()=>{
  const requests=[],renders=[],toasts=[];let resolveSwitch;
  const ctx=runtime({Game:{
    Core:{route:'world',render(){renders.push({route:this.route,cityId:this.state.player.activeCityId,gold:this.state.resources.gold});}},
    World:{_dispatchTarget:{id:99}},WorldView:{invalidate(){}},
    escapeHtml:String,toast:message=>toasts.push(message),go:()=>assert.fail('a different city must switch first')
  }});
  load(ctx,'api-client.js');load(ctx,'api.js');load(ctx,'cities.js');
  const G=ctx.Game;
  function state(cityId){return {player:{id:1,activeCityId:cityId},resources:{gold:cityId*100},world:{},
    cityOverview:{cities:[11,22,33].map(id=>({id,name:'城市'+id,current:id===cityId,readyAt:0}))}};}
  G.API.applyState(state(11));
  G.API.client.post=(url,body)=>{requests.push({url,cityId:body.cityId});return new Promise(resolve=>{resolveSwitch=resolve;});};
  for(const cityId of [22,33,11]){
    const previous=G.Core.state.player.activeCityId;
    G.Core.route='world';
    const count=requests.length;
    G.Cities.enter(cityId);G.Cities.enter(cityId);
    assert.equal(requests.length,count+1);
    assert.deepEqual(requests.at(-1),{url:'/game/cities/switch',cityId});
    assert.equal(G.Core.route,'world');
    assert.equal(G.Core.state.player.activeCityId,previous);
    assert.equal(G.Cities.switching,true);
    resolveSwitch({state:state(cityId)});
    await new Promise(resolve=>setImmediate(resolve));
    assert.deepEqual(renders.at(-1),{route:'home',cityId,gold:cityId*100});
    assert.equal(G.API.client.cityId,cityId);
    assert.equal(G.Cities.switching,false);
    assert.equal(G.World._dispatchTarget,null);
  }
  assert.equal(toasts.length,3);
});

test('entering the current city avoids a switch; a failed switch preserves the original city and route',async()=>{
  const routes=[],messages=[],requests=[];
  const ctx=runtime({Game:{Core:{route:'world',state:{player:{activeCityId:11},cityOverview:{cities:[
    {id:11,current:true,name:'主城',readyAt:0},{id:22,current:false,name:'分城',readyAt:0}
  ]}}},escapeHtml:String,toast:message=>messages.push(message),go:route=>routes.push(route),
    API:{client:{invalidateCityRequests(){},post(url,body){requests.push(body);return Promise.reject(new Error('切换失败'));}}}}});
  load(ctx,'cities.js');
  ctx.Game.Cities.enter(11);
  assert.deepEqual(routes,['home']);assert.equal(requests.length,0);
  ctx.Game.Cities.enter(22);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(ctx.Game.Core.state.player.activeCityId,11);
  assert.equal(ctx.Game.Core.route,'world');
  assert.equal(ctx.Game.Cities.switching,false);
  assert.deepEqual(routes,['home']);assert.deepEqual(messages,['切换失败']);
});
