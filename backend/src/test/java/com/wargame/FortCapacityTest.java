package com.wargame;

import com.wargame.model.constants.GameData;
import com.wargame.model.entity.Building;
import com.wargame.model.entity.Fortification;
import com.wargame.model.entity.Resources;
import com.wargame.service.CityScope;
import com.wargame.service.FortService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FortCapacityTest extends BaseServiceTest {
    @Autowired FortService forts;
    @Autowired CityScope cityScope;

    @Test
    void wallLevelsProvideThreeThousandCapacityEach() {
        Long playerId = createTestPlayer().getId();
        Building wall = createBuilding(playerId, "wall", 1);
        for (int level : new int[] {1, 5, 10}) {
            wall.setLevel(level);
            buildingRepository.save(wall);
            assertEquals(level * 3000, forts.fortCap(playerId));
        }
        assertEquals(0, forts.totalForts(playerId), "升级只扩容，不赠送工事");
    }

    @Test
    void buildingWithoutWallDoesNotChargeResources() {
        Long playerId = createTestPlayer().getId();
        assertEquals(0, forts.fortCap(playerId));
        assertEquals(false, forts.build(playerId, "bunker", 1).get("success"));
        assertEquals(1000, getResources(playerId).getSteel());
        assertEquals(0, forts.totalForts(playerId));
    }

    @Test
    void fourTypesShareCapacityAndRejectionDoesNotCharge() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "wall", 10);
        giveResources(playerId, 5000000, 5000000, 5000000, 5000000, 5000000);
        seedFort(playerId, 0, "bunker", 10000);
        seedFort(playerId, 0, "antitank", 8000);
        seedFort(playerId, 0, "howitzer", 7000);

        assertEquals(true, forts.build(playerId, "flak", 5000).get("success"));
        assertEquals(Map.of("bunker", 10000, "antitank", 8000, "howitzer", 7000, "flak", 5000),
                forts.getForts(playerId));
        Resources resources = getResources(playerId);
        assertEquals(5000000 - GameData.FORTS.get("flak").cost().get("steel") * 5000, resources.getSteel());
        int steel = resources.getSteel(), oil = resources.getOil(), rare = resources.getRare();

        for (String type : GameData.FORTS.keySet()) {
            assertEquals(false, forts.build(playerId, type, 1).get("success"));
        }
        // 超大数量也必须被容量校验挡住，不能溢出后进入费用计算。
        assertEquals(false, forts.build(playerId, "bunker", Integer.MAX_VALUE).get("success"));
        assertEquals(30000, forts.totalForts(playerId));
        assertEquals(steel, getResources(playerId).getSteel());
        assertEquals(oil, getResources(playerId).getOil());
        assertEquals(rare, getResources(playerId).getRare());
    }

    @Test
    void eachCityHasItsOwnSharedCapacity() {
        Long playerId = createTestPlayer().getId();
        createBuilding(playerId, "wall", 10);
        seedFort(playerId, 0, "bunker", 30000);
        Building secondWall = createBuilding(playerId, "wall", 1);
        secondWall.setCitySlot(1);
        buildingRepository.save(secondWall);
        Resources secondResources = new Resources();
        secondResources.setPlayerId(playerId);
        secondResources.setCitySlot(1);
        secondResources.setSteel(300000);
        secondResources.setOil(0);
        secondResources.setRare(0);
        resourcesRepository.save(secondResources);

        try (var ignored = cityScope.enter(playerId, 1)) {
            assertEquals(3000, forts.fortCap(playerId));
            assertEquals(0, forts.totalForts(playerId));
            assertEquals(true, forts.build(playerId, "bunker", 3000).get("success"));
            assertEquals(false, forts.build(playerId, "antitank", 1).get("success"));
            assertEquals(3000, forts.totalForts(playerId));
        }
        assertEquals(30000, forts.fortCap(playerId));
        assertEquals(30000, forts.totalForts(playerId));
    }

    private void seedFort(Long playerId, int citySlot, String type, int count) {
        Fortification fort = new Fortification();
        fort.setPlayerId(playerId);
        fort.setCitySlot(citySlot);
        fort.setType(type);
        fort.setCount(count);
        fortificationRepository.save(fort);
    }
}
