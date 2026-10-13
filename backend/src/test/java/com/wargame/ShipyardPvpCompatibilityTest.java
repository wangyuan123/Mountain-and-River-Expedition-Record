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
    void productionFacilityMetadataPreservesPublishedCombatCalibrationAndExistingSessionVersion() throws Exception {
        assertEquals("shipyard", UnitDef.UNITS.get("destroyer").build());
        assertEquals("airport", UnitDef.UNITS.get("scout").build());
        assertEquals("pvp-v1-8cc036d5a45e2da5c776e5c1", PvpSnapshotService.RULES);
        try (var stream = getClass().getResourceAsStream("/game/pvp-power-calibration-v1.json")) {
            assertNotNull(stream);
            for (JsonNode row : new ObjectMapper().readTree(stream)) {
                assertEquals(PvpPowerModel.VERSION, row.path("modelVersion").asText());
            }
        }
    }
}
