import com.wargame.service.pvp.*;
import java.util.*;
import java.nio.file.*;
/** 使用当前真实引擎生成演习技能模型校准证据；输出为配置和验收记录。 */
public class PvpCalibrationTool {
    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> evidence = new ArrayList<>();
        if (Files.exists(Path.of(args[0]))) evidence.addAll(PvpJson.read(Files.readString(Path.of(args[0])), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {}));
        // 断点续算只保留当前规则的实测值，不能把旧系数换标签后跳过重新测量。
        evidence.removeIf(row -> !PvpPowerModel.VERSION.equals(row.get("modelVersion"))
                || !PvpJson.write(PvpPowerModel.CALIBRATION_SEEDS).equals(PvpJson.write(row.get("seeds"))));
        for (String field : List.of("LAND_AIR","SEA_AIR")) {
            for (String skill : new TreeSet<>(PvpPowerModel.COMBAT_SKILLS)) for (int level : new int[]{1,2,3,4,5}) {
                if (evidence.stream().anyMatch(e -> field.equals(e.get("field")) && Map.of(skill,level).equals(e.get("skills")))) continue;
                long start=System.nanoTime(); var result=new LinkedHashMap<>(PvpPowerModel.calibrate(field,Map.of(skill,level)));
                result.put("field",field);result.put("skills",Map.of(skill,level));result.put("elapsedMs",(System.nanoTime()-start)/1000000);
                evidence.add(result);System.out.println(field+" "+skill+" "+level+" factor="+result.get("factor")+" residual="+result.get("residual"));
                Files.writeString(Path.of(args[0]),PvpJson.write(evidence));
            }
            for (var skills : List.of(Map.of("frenzy",5,"pierce",5,"suppress",5),Map.of("learn",5,"borrow_armor",5,"counter",5),Map.of("blitz",3,"bulwark",3,"counter",3))) {
                if (evidence.stream().anyMatch(e -> field.equals(e.get("field")) && skills.equals(e.get("skills")))) continue;
                var result=new LinkedHashMap<>(PvpPowerModel.calibrate(field,skills));result.put("field",field);result.put("skills",skills);evidence.add(result);
                Files.writeString(Path.of(args[0]),PvpJson.write(evidence));System.out.println(field+" combo "+skills+" factor="+result.get("factor"));
            }
        }
    }
}
