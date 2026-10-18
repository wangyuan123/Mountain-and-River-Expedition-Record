package com.wargame;

import com.wargame.controller.WorldController;
import com.wargame.model.entity.Player;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.MarchService;
import com.wargame.service.WorldService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NewbieProtectionControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "null"})
    void declarationForwardsExplicitConsentUsingAuthenticatedPlayer(String consent) throws Exception {
        var auth = mock(AuthService.class);
        var world = mock(WorldService.class);
        var state = mock(GameStateService.class);
        var player = new Player();
        player.setId(7L);
        when(auth.getCurrentPlayer()).thenReturn(player);
        Boolean confirmed = "null".equals(consent) ? null : Boolean.valueOf(consent);
        when(world.declareWar(7L, 22L, confirmed)).thenReturn(new LinkedHashMap<>(Map.of("success", true)));
        when(state.getGameState(7L)).thenReturn(Map.of());
        var mvc = MockMvcBuilders.standaloneSetup(new WorldController(auth, world, mock(MarchService.class), state)).build();
        String confirmationField = confirmed == null ? "" : ",\"abandonNewbieProtection\":" + consent;
        mvc.perform(post("/api/game/world/declare-war").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":99,\"targetCityId\":22" + confirmationField + "}"))
                .andExpect(status().isOk());
        verify(world).declareWar(7L, 22L, confirmed);
        verify(world, never()).declareWar(anyLong(), anyLong());
    }
}
