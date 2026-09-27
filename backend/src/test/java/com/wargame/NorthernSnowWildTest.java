package com.wargame;

import com.wargame.model.entity.WildTile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NorthernSnowWildTest extends BaseServiceTest {
    @Test
    void generatedSnowWildsFormOneNorthernBlock() {
        long worldId = createTestWorld().getId();
        gameStateService.genWorld(worldId);

        List<WildTile> wilds = wildTileRepository.findByWorldId(worldId);
        List<WildTile> snow = wilds.stream().filter(w -> "snow".equals(w.getType())).toList();
        assertEquals(9, snow.size());

        int minX = snow.stream().mapToInt(WildTile::getX).min().orElseThrow();
        int minY = snow.stream().mapToInt(WildTile::getY).min().orElseThrow();
        assertTrue(minY >= 12 && minY <= 24);
        Set<String> cells = snow.stream().map(w -> w.getX() + "," + w.getY()).collect(Collectors.toSet());
        for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) {
            assertTrue(cells.contains((minX + dx) + "," + (minY + dy)));
        }
    }
}
