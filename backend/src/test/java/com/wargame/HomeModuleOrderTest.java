package com.wargame;

import com.wargame.model.entity.Player;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HomeModuleOrderTest extends BaseServiceTest {
    @Autowired private EntityManager entityManager;

    @Test
    void savesOrderForOnePlayerAndReturnsItAfterReload() {
        Player first = createTestPlayer("home-first", 30);
        Player second = createTestPlayer("home-second", 30);
        List<String> order = List.of("chat", "resources", "officers", "army");

        assertEquals(order, gameStateService.setHomeModuleOrder(first.getId(), order));
        entityManager.flush();
        entityManager.clear();

        assertEquals(order, ((Map<?, ?>) gameStateService.getGameState(first.getId()).get("player")).get("homeModuleOrder"));
        assertNull(((Map<?, ?>) gameStateService.getGameState(second.getId()).get("player")).get("homeModuleOrder"));
    }

    @Test
    void rejectsIncompleteDuplicateAndUnknownOrders() {
        Player player = createTestPlayer("home-invalid", 30);
        for (List<String> order : List.of(
                List.of("chat", "resources", "officers"),
                List.of("chat", "chat", "officers", "army"),
                List.of("chat", "resources", "officers", "unknown"))) {
            assertThrows(IllegalArgumentException.class, () -> gameStateService.setHomeModuleOrder(player.getId(), order));
        }
        assertThrows(IllegalArgumentException.class, () -> gameStateService.setHomeModuleOrder(player.getId(), null));
        assertNull(playerRepository.findById(player.getId()).orElseThrow().getHomeModuleOrder());
    }
}
