import java.util.*;
import com.wargame.service.BattleService;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
public class BattleFocusAnalysis {
 static Map<String,Integer> tech(int attack, int defense, int hp){return new HashMap<>(Map.of("attack_tech",attack,"defense_tech",defense,"cmd_hp",hp,"weapon_range",10,"arm_engine",10,"air_engine",10,"nav_engine",10));}
 static int sum(Map<String,Integer> a){return a.values().stream().mapToInt(Integer::intValue).sum();}
 static Map<String,BattleService.UnitOrder> orders(int battle,int round, boolean manual){
  Map<String,BattleService.UnitOrder> o=new LinkedHashMap<>();
  if(!manual)return o;
  if(battle==2 && round==9)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,null));
  if(battle==2 && round==10)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,null));
  if(battle==2 && round==11)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,null));
  if(battle==2 && round==13)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,null));
  if(battle==2 && round==14)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,null));
  if(battle==2 && round==15)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==16)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==17)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==18)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==19)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==20)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==21)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==22)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"armored"));
  if(battle==2 && round==23)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"htank"));
  if(battle==2 && round==24)o.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.HOLD,"htank"));
  return o;
 }
 public static void main(String[] args){
 Map<String,Integer> a1=new LinkedHashMap<>(Map.ofEntries(Map.entry("htank",340000),Map.entry("rocket",500000)));Map<String,Integer>d1=new LinkedHashMap<>(Map.ofEntries(Map.entry("armored",48331),Map.entry("assault",64366),Map.entry("battleship",64367),Map.entry("bomber",64356),Map.entry("carrier",64367),Map.entry("destroyer",64367),Map.entry("fighter",64154),Map.entry("htank",64366),Map.entry("infantry",64366),Map.entry("ltank",64366),Map.entry("rocket",64365),Map.entry("scout",50767),Map.entry("special",64365),Map.entry("sub",64366),Map.entry("transport",64366),Map.entry("truck",64365)));
 Map<String,Integer> a2=new LinkedHashMap<>(Map.ofEntries(Map.entry("armored",230000),Map.entry("htank",300000),Map.entry("rocket",300000)));Map<String,Integer>d2=new LinkedHashMap<>(Map.ofEntries(Map.entry("armored",46142),Map.entry("assault",65679),Map.entry("battleship",65679),Map.entry("bomber",61441),Map.entry("carrier",65679),Map.entry("destroyer",63617),Map.entry("fighter",61248),Map.entry("htank",63194),Map.entry("infantry",65678),Map.entry("ltank",65678),Map.entry("rocket",61450),Map.entry("scout",51803),Map.entry("special",65678),Map.entry("sub",65678),Map.entry("transport",65678),Map.entry("truck",65678)));
  for(int b=1;b<=2;b++){
   Map<String,Integer> a0=b==1?a1:a2,d0=b==1?d1:d2;
   int am=b==1?219:100,ad=b==1?75:100,dm=b==1?160:0,dd=b==1?205:0;
   Map<String,Integer> as=b==1?Map.of("frenzy",5,"borrow_armor",5,"suppress",2):Map.of("ration",5);
   Map<String,Integer> ds=b==1?Map.of("bulwark",5,"counter",5,"medic",5):Map.of();
   for(String scenario:List.of("original","rocket-focus-armor","rocket-focus-ship")){
    long totalsA=0,totalsD=0,win=0;int min=Integer.MAX_VALUE,max=0;double roundAvg=0;Map<String,Integer> example=null;Map<String,Integer>apos24=null,dpos24=null;String log24="";int distance=0;
    for(long seed=0;seed<32;seed++){
     BattleService engine=new BattleService(seed);Map<String,Integer>a=new LinkedHashMap<>(a0),d=new LinkedHashMap<>(d0),ap=new LinkedHashMap<>(),dp=new LinkedHashMap<>();
     int atk=switch(scenario){case "damage-1.5"->20;case "damage-2"->30;case "damage-3"->50;default->10;};
     Map<String,Integer> at=tech(atk,scenario.equals("defense-tech-0")?0:10,scenario.equals("hp-tech-0")?0:10),dt=tech(atk,scenario.equals("defense-tech-0")?0:10,scenario.equals("hp-tech-0")?0:10);
     distance=scenario.equals("current-distance")?engine.calcInitialDistance(a,d,new BattleService.TechCtx(at,as,am,ad),new BattleService.TechCtx(dt,ds,dm,dd)):(b==1?6725:6950);
     for(String id:a.keySet())ap.put(id,0);for(String id:d.keySet())dp.put(id,distance);
     int first=0;
     for(int r=1;r<=24;r++){
      Map<String,BattleService.UnitOrder> chosen=orders(b,r,true);
      if(scenario.equals("rocket-focus-armor"))chosen.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE,d.getOrDefault("rocket",0)>0?"rocket":d.getOrDefault("htank",0)>0?"htank":d.getOrDefault("armored",0)>0?"armored":"ltank"));
      if(scenario.equals("rocket-focus-ship"))chosen.put("rocket",new BattleService.UnitOrder(BattleService.CommandAction.ADVANCE,"battleship"));
      var s=engine.resolveWorldRound(a,d,ap,dp,distance,at,dt,as,ds,am,ad,dm,dd,10,10,r,first,chosen,Map.of());
      a=new LinkedHashMap<>(s.attackerArmy());d=new LinkedHashMap<>(s.defenderArmy());ap=new LinkedHashMap<>(s.attackerPositions());dp=new LinkedHashMap<>(s.defenderPositions());first=s.firstCombatRound();
      if(sum(a)==0||sum(d)==0||r==24){totalsA+=sum(a);totalsD+=sum(d);roundAvg+=r;min=Math.min(min,r);max=Math.max(max,r);if(sum(d)==0)win++;if(seed==0){example=d;apos24=ap;dpos24=dp;log24=s.log();}break;}
     }
    }
    System.out.printf(Locale.ROOT,"battle=%d scenario=%s distance=%d survivorA=%.1f survivorD=%.1f wins=%d/32 round=%.2f range=%d-%d%n",b,scenario,distance,totalsA/32.0,totalsD/32.0,win,roundAvg/32,min,max);
    if(scenario.equals("original")){System.out.println("defender0="+example);System.out.println("apos="+apos24+" dpos="+dpos24);}
   }
  }
 }
}
