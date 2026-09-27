package com.wargame;

import com.wargame.controller.GameController;
import com.wargame.model.constants.AvatarDef;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Player;
import com.wargame.repository.ScoutReportRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GameControllerAvatarTest {
    private final AuthService authService = mock(AuthService.class);
    private final GameStateService gameStateService = mock(GameStateService.class);
    private final GameController controller = new GameController(authService, gameStateService, mock(ScoutReportRepository.class));

    @Test
    void rejectsExternalAndNonPresetAvatars() {
        for (String avatar : new String[] { null, "https://example.com/avatar.png", "img/avatars/other.svg", "", "img/avatars/commander-8.svg",
                "img/avatars/historical/rank-marshal-v1.webp", "img/avatars/historical/../commander-1.svg" }) {
            assertThrows(IllegalArgumentException.class, () -> controller.setAvatar(new GameDtos.AvatarRequest(avatar)));
        }
        verifyNoInteractions(authService, gameStateService);
    }

    @Test
    void savesPresetAvatar() {
        Player player = new Player();
        player.setId(42L);
        when(authService.getCurrentPlayer()).thenReturn(player);
        when(gameStateService.getGameState(42L)).thenReturn(Map.of());

        for (String avatar : AvatarDef.PRESETS) {
            var result = controller.setAvatar(new GameDtos.AvatarRequest("  " + avatar + "  "));
            assertEquals(avatar, result.getBody().get("avatar"));
            verify(gameStateService).setAvatar(42L, avatar);
        }
    }
}
