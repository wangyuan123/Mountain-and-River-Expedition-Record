package com.wargame;

import com.wargame.config.GlobalExceptionHandler;
import com.wargame.controller.LeaderboardController;
import com.wargame.model.entity.Player;
import com.wargame.service.AuthService;
import com.wargame.service.LeaderboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LeaderboardControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final LeaderboardService leaderboards = mock(LeaderboardService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new LeaderboardController(auth, leaderboards))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648", "-2147483649", "1.5"})
    void malformedPageIsAClientErrorInsteadOfServerMaintenance(String page) throws Exception {
        mvc.perform(get("/api/game/leaderboard").param("page", page))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数 page 格式无效"))
                .andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(auth, leaderboards);
    }

    @Test
    void currentPlayerComesFromAuthenticationAndDefaultsRemainCompatible() throws Exception {
        Player player = new Player();
        player.setId(177L);
        when(auth.getCurrentPlayer()).thenReturn(player);
        when(leaderboards.get(177L, "players", "prestige", 1)).thenReturn(Map.of("total", 0));
        mvc.perform(get("/api/game/leaderboard").param("playerId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        verify(leaderboards).get(177L, "players", "prestige", 1);
    }
}
