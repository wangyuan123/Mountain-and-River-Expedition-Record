package com.wargame;

import com.wargame.controller.PrivateChatController;
import com.wargame.model.entity.Player;
import com.wargame.service.AuthService;
import com.wargame.service.PrivateChatService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivateChatControllerTest {
    @Test void conversationsRouteUsesAuthenticatedPlayer() throws Exception {
        var chat = mock(PrivateChatService.class);
        var auth = mock(AuthService.class);
        var player = new Player(); player.setId(7L);
        when(auth.getCurrentPlayer()).thenReturn(player);
        when(chat.conversations(7L)).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new PrivateChatController(chat, auth)).build();
        mvc.perform(get("/api/game/chat/private/conversations"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(chat).conversations(7L);
    }
}
