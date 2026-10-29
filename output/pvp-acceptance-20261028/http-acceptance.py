"""仅连接本机 PvpLocalHarness；所有账号和兵力都存在独立 H2 内存库。"""
import json, pathlib, time, uuid, urllib.request, urllib.error
OUT=pathlib.Path(__file__).parent
BASE='http://127.0.0.1:8082/api/'
checks=[]
def check(name, condition):
    assert condition, name
    checks.append(name)
    print('PASS',name,flush=True)
def request(path, token=None, body=None, expected=200):
    headers={'Content-Type':'application/json','X-Game-Server':'jiangsu-1'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=15) as response: code=response.status; result=json.load(response)
    except urllib.error.HTTPError as response: code=response.code; result=json.load(response)
    assert code==expected,(path,code,result)
    return result
def post(path,t,**body): return request('game/pvp/'+path,t,dict(requestId=str(uuid.uuid4()),**body))
def get(path,t): return request('game/pvp/'+path,t)
def login(name): return request('auth/login',body={'username':name,'password':'PvpLocalTest123!','serverId':'jiangsu-1'})['token']
def wait(t,status,limit=25):
    deadline=time.monotonic()+limit
    while time.monotonic()<deadline:
        s=get('status',t)
        if s['status']==status: return s
        time.sleep(.25)
    raise AssertionError(('wait',status,s))
def enqueue(t):
    l=get('lobby',t)
    return post('queue',t,citySlot=0,battlefield='LAND_AIR',army={'infantry':1000},presetVersion=l['presetVersion'])
def matched(a,b):
    qa=enqueue(a); qb=enqueue(b)
    sa=wait(a,'ACCEPTING'); sb=wait(b,'ACCEPTING')
    assert sa['sessionId']==sb['sessionId']
    return sa,sb
def enter(a,b):
    sa,sb=matched(a,b); session=sa['sessionId']
    for t,s in [(a,sa),(b,sb)]: post('accept',t,ticketId=s['ticketId'],sessionId=session)
    for t in [a,b]: post('loaded',t,sessionId=session)
    wait(a,'BATTLING'); return session
# 只比较世界资产，排除登录时间等正常变动字段。
def assets(t):
    state=request('game/state',t)
    keys=['resources','army','officers','technologies','buildings','wounded','prisoners','items','equipment','marches','prestige','rankInfo','population']
    out = {k:state[k] for k in keys if k in state}
    if "rankInfo" in out: out["rankInfo"].pop("serverTime", None)
    return out
a,b=login('pvp_charlie'),login('pvp_delta')
locked=login('pvp_locked')
get('lobby',locked)
r=request('game/pvp/queue',locked,{'requestId':str(uuid.uuid4()),'citySlot':0,'battlefield':'LAND_AIR','army':{'infantry':1000},'presetVersion':0},expected=403)
check('低声望直接调用匹配接口被拒绝',r['code']=='PVP_PRESTIGE_REQUIRED')
for t in [a,b]:
    status=get('status',t)
    if status.get('ticketId'): post('queue/cancel',t,ticketId=status['ticketId'])
    while get('status',t).get('cooldownUntil',0)>get('status',t)['serverTime']:
        time.sleep(1)
before=[assets(t) for t in [a,b]]
q=enqueue(a); time.sleep(1)
check('只有一个账号排队时保持等待，无虚假对手',get('status',a)['status']=='QUEUED')
check('普通排队可取消',post('queue/cancel',a,ticketId=q['ticketId'])['status']=='IDLE')
sa,sb=matched(a,b); session=sa['sessionId']; original=sa['queuedAt']
check('相同阵容实力估值一致',sa['snapshot']['power']==sb['snapshot']['power'])
request('game/pvp/battle?sessionId='+session,a,expected=400)
post('accept',a,ticketId=sa['ticketId'],sessionId=session)
post('queue/cancel',b,ticketId=sb['ticketId'])
requeued=get('status',a)
check('对手拒绝后已接受者重新排队并保留等待起点',requeued['status']=='QUEUED' and requeued['queuedAt']==original)
post('queue/cancel',a,ticketId=requeued['ticketId'])
sa,sb=matched(a,b); session=sa['sessionId']
for t,s in [(a,sa),(b,sb)]: post('accept',t,ticketId=s['ticketId'],sessionId=session)
post('loaded',a,sessionId=session)
wait(a,'IDLE',30)
r=get('report?sessionId='+session,a)
check('单方未加载时作废且不计胜负',r['result']=='VOID' and r['reason']=='LOAD_TIMEOUT' and not r['rated'])
# 手动推进真实回合；不更改服务器时钟及截止时间。
session=enter(a,b)
battle=get('battle?sessionId='+session,a)
check('双方底线镜像正确',battle['myPositions']['infantry']==0 and battle['enemyPositions']['infantry']==battle['initialDistance'])
check('未结算对手默认指令不泄漏','defaults' not in battle['enemy'])
request('game/pvp/battle?sessionId='+session,locked,expected=400)
check('非参战账号不能访问战场',True)
round_no=battle['round']
draft=post('orders',a,sessionId=session,round=round_no,revision=1,orders={'infantry':{'action':'ADVANCE'}},lock=False)
check('草稿保存成功并递增版本',draft['revision']==1 and not draft['locked'])
body={'requestId':str(uuid.uuid4()),'sessionId':session,'round':round_no,'revision':2,'orders':{'infantry':{'action':'ADVANCE'}},'lock':True}
first=request('game/pvp/orders',a,body)
check('重试同一锁定请求幂等',first==request('game/pvp/orders',a,body))
check('单方锁定不推进回合',get('battle?sessionId='+session,b)['round']==round_no)
while True:
    ba=get('battle?sessionId='+session,a); bb=get('battle?sessionId='+session,b)
    if ba['status'] in ['FINISHED','VOID']: break
    for t,current in [(a,ba),(b,bb)]:
        if not current['locked']:
            post('orders',t,sessionId=session,round=current['round'],revision=current['revision']+1,orders={'infantry':{'action':'ADVANCE'}},lock=True)
ra=get('report?sessionId='+session,a); rb=get('report?sessionId='+session,b)
(OUT/'manual-report.json').write_text(json.dumps(ra,ensure_ascii=False,indent=2))
check('完整人工指挥对局正常结算',ra['reason']=='COMBAT' and ra['result'] in ['WIN','LOSS','DRAW'])
check('双方胜负一致',{'WIN':'LOSS','LOSS':'WIN','DRAW':'DRAW'}[ra['result']]==rb['result'])
check('每回合仅结算一次且有交火事件',len(ra['rounds'])==len({r['round'] for r in ra['rounds']}) and any(e['type']=='FIRE' for r in ra['rounds'] for e in r['events']))
after=[assets(t) for t in [a,b]]
(OUT/'asset-comparison.json').write_text(json.dumps({'before':before,'after':after},ensure_ascii=False,indent=2))
check('真实资产零消耗',before==after)
# 双方默认托管，等待真实调度完成；演习引擎每个回合照常运行。
session=enter(a,b)
for t in [a,b]:
    current=get('battle?sessionId='+session,t)
    post('auto',t,sessionId=session,expectedRound=current['round'],enabled=True)
wait(a,'IDLE',80)
auto=get('report?sessionId='+session,a)
(OUT/'auto-report.json').write_text(json.dumps(auto,ensure_ascii=False,indent=2))
check('双方默认托管可完整结算',auto['reason']=='COMBAT' and auto['actualLoss']==0)
check('托管后真实资产仍零消耗',before==[assets(t) for t in [a,b]])
# 认输和重复提交只产生一次结算。
session=enter(a,b)
body={'requestId':str(uuid.uuid4()),'sessionId':session}
request('game/pvp/surrender',a,body);request('game/pvp/surrender',a,body)
ra=get('report?sessionId='+session,a);rb=get('report?sessionId='+session,b)
check('认输与重复请求正确结算',ra['result']=='LOSS' and rb['result']=='WIN' and ra['reason']=='SURRENDER')
check('所有对局后真实资产不变',before==[assets(t) for t in [a,b]])
(OUT/'http-summary.json').write_text(json.dumps({'checks':checks,'passed':len(checks),'manualRounds':len(json.loads((OUT/'manual-report.json').read_text())['rounds']),'autoRounds':len(auto['rounds']),'records':[get('lobby',t)['record'] for t in [a,b]],'assetsCompared':list(before[0])},ensure_ascii=False,indent=2))
print('ALL PASS',len(checks),flush=True)
