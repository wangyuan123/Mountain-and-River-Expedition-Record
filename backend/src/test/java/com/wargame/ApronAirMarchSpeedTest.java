package com.wargame;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Technology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("战备起飞场空军全图航速加成测试")
class ApronAirMarchSpeedTest extends BaseServiceTest {

    private Long playerId;

    @BeforeEach
    void setUp() {
        Player player = createTestPlayer("pilot_commander", 30);
        playerId = player.getId();
    }

    @Test
    @DisplayName("战备起飞场定义校验: airSpdBonus 为 3，移除 airCap")
    void testBuildingDefProperties() {
        BuildingDef apronDef = BuildingDef.BUILDINGS.get("apron");
        assertNotNull(apronDef);
        assertEquals(3, apronDef.airSpdBonus());
        assertTrue(apronDef.desc().contains("航速"));
    }

    @Test
    @DisplayName("战备起飞场航速加成: Lv.0 -> 1.0, Lv.5 -> 1.15, Lv.10 -> 1.30")
    void testApronSpeedMultiplier() {
        // 无起飞场
        assertEquals(1.0, marchService.getUnitTechSpdMul(playerId, "air"), 1e-4);

        // 建造 Lv.5 战备起飞场
        Building apron = createBuilding(playerId, "apron", 5);
        assertEquals(1.15, marchService.getUnitTechSpdMul(playerId, "air"), 1e-4);

        // 升级到 Lv.10 战备起飞场
        apron.setLevel(10);
        buildingRepository.save(apron);
        assertEquals(1.30, marchService.getUnitTechSpdMul(playerId, "air"), 1e-4);

        // 增加喷气推进科技 Lv.4 (加成 4 * 5% = 20%)，总加成 = 1.0 + 0.20 + 0.30 = 1.50
        Technology tech = new Technology();
        tech.setPlayerId(playerId);
        tech.setType("air_engine");
        tech.setLevel(4);
        technologyRepository.save(tech);
        assertEquals(1.50, marchService.getUnitTechSpdMul(playerId, "air"), 1e-4);
    }

    @Test
    @DisplayName("战备起飞场仅影响空军，不影响步兵、装甲车或舰船的大地图航速")
    void testNonAirUnitsUnaffectedByApron() {
        createBuilding(playerId, "apron", 10);

        // inf, arm, nav 均不受 apron 影响
        assertEquals(1.0, marchService.getUnitTechSpdMul(playerId, "inf"), 1e-4);
        assertEquals(1.0, marchService.getUnitTechSpdMul(playerId, "arm"), 1e-4);
        assertEquals(1.0, marchService.getUnitTechSpdMul(playerId, "nav"), 1e-4);
    }
}
