import com.wargame.service.BattleService;
import com.wargame.service.pvp.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.pvp.PvpSnapshot;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import java.nio.file.*;

/** 独立种子、未用于校准的混编及中间科技/配装水平检验；结果不代表真人胜率。 */
public class PvpHoldoutTool {
    static final long[] SEEDS = {83011, 83012, 83013};
    static List<Map<String,Integer>> forms(String field) {
        return field.equals("LAND_AIR") ? List.of(Map.of("infantry",5,"special",1),Map.of("ltank",2,"assault",1,"recon_car",1),
                Map.of("rocket",1,"armored",2,"fighter",1),Map.of("bomber",1,"htank",1,"infantry",2))
                : List.of(Map.of("sub",2,"destroyer",1),Map.of("battleship",2,"fighter",1),Map.of("carrier",1,"bomber",2),Map.of("destroyer",3,"fighter",2));
    }
    static BattleService.TechCtx context(Map<String,Integer> army,Map<String,Integer> skills) {
        Map<String,Integer> tech=new TreeMap<>();
        for(String id:List.of("attack_tech","defense_tech","cmd_hp","weapon_range","arm_engine","air_engine","nav_engine"))tech.put(id,3);
        army.keySet().forEach(id->{for(String key:List.of("ground","air","sea","defense","hp","speed"))tech.put(UnitEquipmentEffects.key(id,key),5);});
        return new BattleService.TechCtx(tech,skills,60,60,CommandBonus.NONE);
    }
    static Map<String,Integer> equalPower(Map<String,Integer> form,Map<String,Integer> skills,String field,double factor) {
        var ctx=context(form,skills);Map<String,Map<String,Double>> stats=new TreeMap<>();
        var engine=new BattleService(1,false); form.keySet().forEach(id->stats.put(id,engine.practiceStats(id,ctx)));
        var snap=new PvpSnapshot("","",0,field,form,ctx.tech(),skills,null,"",60,60,0,0,List.of(),CommandBonus.NONE,Map.of(),Map.of(),stats,PvpSnapshotService.RULES,PvpPowerModel.VERSION,0,factor);
        double base=PvpPowerModel.basePower(snap)*factor;
        Map<String,Integer> army=new TreeMap<>();form.forEach((id,n)->army.put(id,Math.max(1,(int)Math.round(n*200000/base))));return army;
    }
    static double[] simulate(String field,Map<String,Integer> a,Map<String,Integer> b,BattleService.TechCtx ac,BattleService.TechCtx bc,long seed) {
        var engine=new BattleService(seed,false);var initialA=a;var initialB=b;
        int distance=engine.calcInitialDistance(a,b,ac,bc),first=0;Map<String,Integer> ap=new TreeMap<>(),bp=new TreeMap<>();
        a.keySet().forEach(id->ap.put(id,0));b.keySet().forEach(id->bp.put(id,distance));
        for(int round=1;round<=24;round++){
            var r=engine.resolvePracticeRound(a,b,ap,bp,distance,ac,bc,round,first,Map.of(),Map.of());
            a=r.attackerArmy();b=r.defenderArmy();ap.clear();ap.putAll(r.attackerPositions());bp.clear();bp.putAll(r.defenderPositions());first=r.firstCombatRound();if(r.finished())break;
        }
        double own=PvpPowerModel.loss(initialA,a,field),foe=PvpPowerModel.loss(initialB,b,field);
        return new double[]{foe-own,Math.abs(foe-own)<=.05?.5:foe>own?1:0};
    }
    public static void main(String[] args)throws Exception{
        List<Map<String,Object>> rows=PvpJson.read(Files.readString(Path.of(args[0])),new TypeReference<>(){});List<Map<String,Object>> output=new ArrayList<>();int sideAWins=0,sideBWins=0,draws=0;
        for(var row:rows){
            Map<String,Integer> skills=PvpJson.read(PvpJson.write(row.get("skills")),new TypeReference<>(){});
            if(skills.size()==1&&!List.of(1,3,5).contains(skills.values().iterator().next()))continue;
            String field=(String)row.get("field");double factor=((Number)row.get("factor")).doubleValue(),sum=0,score=0;int samples=0;
            for(var own:forms(field))for(var foe:forms(field))for(long seed:SEEDS)for(boolean mirror:new boolean[]{false,true}){
                var a=equalPower(own,skills,field,factor);var b=equalPower(foe,Map.of(),field,1);var ac=context(a,skills);var bc=context(b,Map.of());
                var result=simulate(field,mirror?b:a,mirror?a:b,mirror?bc:ac,mirror?ac:bc,seed);
                sum+=mirror?-result[0]:result[0];score+=mirror?1-result[1]:result[1];samples++;
                if(result[1]==1)sideAWins++;else if(result[1]==0)sideBWins++;else draws++;
            }
            output.add(Map.of("field",field,"skills",skills,"samples",samples,"meanLossDifference",sum/samples,"meanResultScore",score/samples));
        }
        int total=sideAWins+sideBWins+draws;
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("modelVersion",PvpPowerModel.VERSION);evidence.put("seeds",SEEDS);evidence.put("targetPower",200000);evidence.put("techLevel",3);evidence.put("equipmentPercent",5);
        evidence.put("samples",total);evidence.put("results",output);evidence.put("sideAWinRate",sideAWins/(double)total);evidence.put("sideBWinRate",sideBWins/(double)total);evidence.put("drawRate",draws/(double)total);
        evidence.put("limitation","synthetic unseen mixed formations; paired mirrored seeds; no human population or command behavior guarantee");
        Files.writeString(Path.of(args[1]),PvpJson.write(evidence));System.out.println("holdout configurations="+output.size()+" samples="+evidence.get("samples"));
    }
}
