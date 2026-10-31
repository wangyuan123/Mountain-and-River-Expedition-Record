package com.wargame;
import com.wargame.service.pvp.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 防止发布缺级、过期或全为占位系数的校准配置。 */
class PvpCalibrationTest {
    @Test void shippedCalibrationCoversEveryCombatSkillLevelAndHasMeasuredMirroredEvidence() throws Exception {
        try(var stream=getClass().getResourceAsStream("/game/pvp-power-calibration-v1.json")){
            assertNotNull(stream);List<Map<String,Object>> rows=PvpJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),new TypeReference<>(){});
            assertEquals(2 * (PvpPowerModel.COMBAT_SKILLS.size() * 5 + 3),rows.size());
            for(String field:List.of("LAND_AIR","SEA_AIR"))for(String skill:PvpPowerModel.COMBAT_SKILLS)for(int level=1;level<=5;level++){
                int expected=level;assertTrue(rows.stream().anyMatch(r->field.equals(r.get("field"))&&Map.of(skill,expected).equals(r.get("skills"))),field+" "+skill+" "+level);
            }
            for(var row:rows){
                assertEquals(PvpPowerModel.VERSION,row.get("modelVersion"));assertTrue(((Number)row.get("samples")).intValue()>=5000);
                double factor=((Number)row.get("factor")).doubleValue();
                assertTrue(Double.isFinite(factor) && factor>0);
                assertEquals(1, factor * ((Number)row.get("quantityScale")).doubleValue(), 1e-12);
                assertTrue(Math.abs(((Number)row.get("residual")).doubleValue())<.03, row.get("field")+" "+row.get("skills"));
                assertTrue(row.get("method").toString().contains("mirrored A/B"));
            }
            assertTrue(rows.stream().anyMatch(row -> ((Number)row.get("factor")).doubleValue()>1));
            // 烟幕等条件技能未改变参考阵容战果时，应允许实测系数为1，不伪造正收益。
            for(var row:rows)if(Math.abs(((Number)row.get("skillScore")).doubleValue()-((Number)row.get("baseline")).doubleValue())<1e-12){
                assertEquals(1,((Number)row.get("factor")).doubleValue(),1e-12);
            }
        }
    }
}
