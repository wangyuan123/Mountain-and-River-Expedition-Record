package com.wargame;

import com.wargame.controller.GameController;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Player;
import com.wargame.repository.ScoutReportRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameControllerHomeModuleOrderTest {
    @Test
    void savesOrderForAuthenticatedPlayer() {
        AuthService auth = mock(AuthService.class);
        GameStateService state = mock(GameStateService.class);
        Player player = new Player();
        player.setId(42L);
        when(auth.getCurrentPlayer()).thenReturn(player);
        List<String> order = List.of("chat", "resources", "officers", "army");
        when(state.setHomeModuleOrder(42L, order)).thenReturn(order);

        var controller = new GameController(auth, state, mock(ScoutReportRepository.class));
        var response = controller.setHomeModuleOrder(new GameDtos.HomeModuleOrderRequest(order));

        assertEquals(true, response.getBody().get("success"));
        assertEquals(order, response.getBody().get("homeModuleOrder"));
        verify(state).setHomeModuleOrder(42L, order);
    }
}
