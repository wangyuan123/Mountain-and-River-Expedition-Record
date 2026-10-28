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

test('treatment completing after a city change cannot restore the previous city state', async()=>{
  const ctx=runtime({Game:{Core:{}}});load(ctx,'api-client.js');load(ctx,'api.js');
  const API=ctx.Game.API;
  const previous={player:{id:1,activeCityId:11},resources:{gold:1000}};
  const current={player:{id:1,activeCityId:22},resources:{gold:2000}};
  API.applyState(previous);
  let resolve;
  API.client.post=()=>new Promise(done=>{resolve=done;});
  const treatment=API.healWounded(1,5,'gold');
  API.applyState(current);
  resolve({success:true,state:previous});
  await treatment;
  assert.equal(ctx.Game.Core.state,current);
  assert.equal(API.client.cityId,22);
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

test('top rank describes its capacity without claiming unused city slots are full',()=>{
  const content={innerHTML:''};
  const overview={cities:[],count:3,cap:8,rankName:'上将'};
  const ctx=runtime({document:{getElementById(id){return id==='cityDialogContent'?content:null;}},
    Game:{Core:{state:{cityOverview:overview}},escapeHtml:String}});
  load(ctx,'cities.js');
  ctx.Game.Cities.back();
  assert.match(content.innerHTML,/当前军衔最多可拥有 8 座城市/);
  assert.match(content.innerHTML,/剩余 5 个名额/);
  assert.doesNotMatch(content.innerHTML,/已达到最高城市上限|城市数量已达上限/);
  overview.count=8;
  ctx.Game.Cities.back();
  assert.match(content.innerHTML,/城市数量已达上限/);
  assert.doesNotMatch(content.innerHTML,/建立新城/);
  overview.nextRankName='上将';overview.nextCap=9;
  ctx.Game.Cities.back();
  assert.match(content.innerHTML,/晋升上将后，可拥有 9 座城市/);
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

test('city list modal draws structured card layout with row-main, actions, garrison checkbox, and food stats', () => {
  const content = { innerHTML: '' };
  const overview = {
    cities: [
      { id: 1, name: '战火一$@焚天灭地城', current: true, level: 1, x: 20, y: 20, main: true, readyAt: 0, acceptGarrison: true, ownFoodPerHour: 120, supportFoodPerHour: 80, garrisonFoodUnmet: 0 },
      { id: 2, name: '分城二', current: false, level: 2, x: 25, y: 30, main: false, readyAt: 0, acceptGarrison: false, ownFoodPerHour: 50, supportFoodPerHour: 0, garrisonFoodUnmet: 30 }
    ],
    count: 2,
    cap: 5,
    rankName: '少将'
  };
  const ctx = runtime({
    document: { getElementById(id) { return id === 'cityDialogContent' ? content : null; } },
    Game: {
      Core: { state: { cityOverview: overview } },
      escapeHtml: String,
      fmt: n => Number(n).toLocaleString(),
      gameNow: () => 1000
    }
  });
  load(ctx, 'cities.js');
  ctx.Game.Cities.back();

  const html = content.innerHTML;
  // Card container structure
  assert.match(html, /class="city-list-row selected"/);
  assert.match(html, /class="city-row-main"/);
  assert.match(html, /class="city-choice"/);
  assert.match(html, /战火一\$@焚天灭地城/);

  // Current city has no transport / rebase actions
  assert.doesNotMatch(html.split('city-list-row')[1], /class="city-row-actions"/);

  // Non-current city has action buttons inside .city-row-actions as text buttons [运输][派遣]
  const nonCurrentRow = html.split('city-list-row')[2];
  assert.match(nonCurrentRow, /class="city-row-actions"/);
  assert.match(nonCurrentRow, /<button type="button" class="city-text-btn" onclick="Game.Cities.transferForm\(2,'transport'\)">\[运输\]<\/button>/);
  assert.match(nonCurrentRow, /<button type="button" class="city-text-btn" onclick="Game.Cities.transferForm\(2,'rebase'\)">\[派遣\]<\/button>/);

  // Extra section holds garrison checkbox and food stats on a separate row
  assert.match(html, /class="city-row-extra"/);
  assert.match(html, /class="city-garrison-check"/);
  assert.match(html, /允许接收军团驻军（本城承担粮耗）/);
  assert.match(html, /本城部队粮耗 120\/小时 · 外来驻军粮耗 80\/小时/);
  assert.match(html, /class="city-shortage-warn">曾发生补给短缺，未满足粮耗 30；此数为历史记录，不是欠款。/);

  // Verify CSS contains the required card styles and checkbox overrides
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.city-list-row\s*\{[^}]*flex-direction:\s*column/);
  assert.match(css, /\.city-row-main\s*\{[^}]*justify-content:\s*space-between/);
  assert.match(css, /\.city-row-extra\s*\{[^}]*border-top:\s*1px dashed/);
  assert.match(css, /\.city-dialog label\.city-garrison-check\s*\{[^}]*flex-direction:\s*row/);
  assert.match(css, /\.city-dialog label\.city-garrison-check input\[type="checkbox"\]/);
});
