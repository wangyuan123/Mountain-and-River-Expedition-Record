"""Prepare additive, repeatable SQL from a read-only map snapshot."""
from pathlib import Path
import random,json,collections
folder=Path(__file__).resolve().parent
lines=[line.split('\t') for line in (folder/'before.tsv').read_text().splitlines()]
_,world,size,mask=lines[0];world=int(world);size=int(size)
assert len(mask)==size*size and set(mask)<=set('01')
used=set()
for kind,w,x,y,span in lines[1:]:
 if int(w)!=world:continue
 span=int(span);x=min(int(x),size-span);y=min(int(y),size-span)
 for yy in range(y-1,y+span+1):
  for xx in range(x-1,x+span+1):used.add((xx,yy))
rng=random.Random(20260914)
coords=[(x,y) for y in range(size) for x in range(size) if mask[y*size+x]=='0' and (x,y) not in used]
near=[p for p in coords if abs(p[0]-101)<=8 and abs(p[1]-101)<=8]
rng.shuffle(near);rng.shuffle(coords)
chosen=[]
def add(pool,limit):
 for x,y in pool:
  if len(chosen)>=limit:break
  if (x,y) in used:continue
  chosen.append((x,y))
  for yy in range(y-1,y+2):
   for xx in range(x-1,x+2):used.add((xx,yy))
add(near,21);near_count=len(chosen);assert near_count==21
add(coords,700);assert len(chosen)==700
kinds=['forest','hill','swamp','grainfield','ironworks','oil','rarefactory']
rows=[]
for i,(x,y) in enumerate(chosen):
 kind=kinds[i%7];level=rng.randint(1,6);unit=rng.choice(['infantry','motor','armored','ltank'])
 rows.append(dict(world_id=world,type=kind,x=x,y=y,level=level,garrison=json.dumps({unit:5*level},separators=(',',':')),total_res=level*800 if kind in kinds[3:] else 0))
(folder/'proposed.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
values=',\n'.join(f"({r['world_id']},'{r['type']}',{r['x']},{r['y']},{r['level']},'{r['garrison']}',{r['total_res']})" for r in rows)
sql=f'''-- Additive map population; rerunning the same plan skips existing coordinates.
CREATE TEMPORARY TABLE proposed_wilds (world_id BIGINT, type VARCHAR(50), x INT, y INT, level INT, garrison TEXT, total_res INT, PRIMARY KEY(world_id,x,y));
INSERT INTO proposed_wilds VALUES
{values};
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SELECT id AS locked_world FROM world_map WHERE id={world} FOR UPDATE;
INSERT INTO wild_tiles (world_id,type,x,y,level,garrison,scouted,occupied,occupied_by,total_res,mined,version)
SELECT p.world_id,p.type,p.x,p.y,p.level,p.garrison,0,0,NULL,p.total_res,0,0
FROM proposed_wilds p JOIN world_map m ON m.id=p.world_id
WHERE SUBSTRING(m.terrain_data,p.y*m.size+p.x+1,1)='0'
AND NOT EXISTS (SELECT 1 FROM wild_tiles w WHERE w.world_id=p.world_id AND w.x=p.x AND w.y=p.y)
AND NOT EXISTS (SELECT 1 FROM npc_cities n WHERE n.world_id=p.world_id AND n.x=p.x AND n.y=p.y)
AND NOT EXISTS (SELECT 1 FROM bandits b WHERE b.world_id=p.world_id AND b.x=p.x AND b.y=p.y)
AND NOT EXISTS (SELECT 1 FROM player_cities c WHERE c.world_id=p.world_id
 AND p.x BETWEEN LEAST(c.x,m.size-IF(c.owner_id IS NULL,1,2)) AND LEAST(c.x,m.size-IF(c.owner_id IS NULL,1,2))+IF(c.owner_id IS NULL,0,1)
 AND p.y BETWEEN LEAST(c.y,m.size-IF(c.owner_id IS NULL,1,2)) AND LEAST(c.y,m.size-IF(c.owner_id IS NULL,1,2))+IF(c.owner_id IS NULL,0,1));
SELECT ROW_COUNT() AS added_wilds;
COMMIT;
SELECT w.type,COUNT(*) AS added_count,MIN(w.level) AS min_level,MAX(w.level) AS max_level
FROM wild_tiles w JOIN proposed_wilds p ON w.world_id=p.world_id AND w.x=p.x AND w.y=p.y GROUP BY w.type;
SELECT w.id,w.type,w.x,w.y,w.level,w.total_res,w.garrison
FROM wild_tiles w JOIN proposed_wilds p ON w.world_id=p.world_id AND w.x=p.x AND w.y=p.y ORDER BY w.id;
'''
(folder/'populate.sql').write_text(sql)
print(json.dumps({'world':world,'total':len(rows),'near_home':near_count,'types':dict(collections.Counter(r['type'] for r in rows))}))
