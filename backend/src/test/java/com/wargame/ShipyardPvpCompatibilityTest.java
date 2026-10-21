package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.UnitDef;
import com.wargame.service.pvp.PvpPowerModel;
import com.wargame.service.pvp.PvpSnapshotService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipyardPvpCompatibilityTest {
    @Test
    void productionFacilityMetadataKeepsCurrentMobilityCalibrationVersion() throws Exception {
        assertEquals("shipyard", UnitDef.UNITS.get("destroyer").build());
        assertEquals("airport", UnitDef.UNITS.get("scout").build());
        // 生产设施元数据仍归一化；机动与场景距离改变战斗契约，校准版本随之更新。
        assertEquals("pvp-v1-7d0aa90b2879baa174c11814", PvpSnapshotService.RULES);
        try (var stream = getClass().getResourceAsStream("/game/pvp-power-calibration-v1.json")) {
            assertNotNull(stream);
            for (JsonNode row : new ObjectMapper().readTree(stream)) {
                assertEquals(PvpPowerModel.VERSION, row.path("modelVersion").asText());
            }
        }
    }
}
