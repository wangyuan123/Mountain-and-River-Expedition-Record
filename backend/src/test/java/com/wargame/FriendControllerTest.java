package com.wargame;

import com.wargame.controller.FriendController;
import com.wargame.model.dto.FriendDtos;
import com.wargame.model.entity.Player;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FriendControllerTest {
    @Test void everyMutationUsesAuthenticatedOwnerAndLiteralRoutesResolveCorrectly() throws Exception {
        var friends = mock(FriendService.class); var auth = mock(AuthService.class);
        var player = new Player(); player.setId(7L); when(auth.getCurrentPlayer()).thenReturn(player);
        var mvc = MockMvcBuilders.standaloneSetup(new FriendController(friends, auth)).build();
        when(friends.list(7L)).thenReturn(new FriendDtos.ListResponse(List.of(new FriendDtos.Group(0L, "我的好友")), List.of(), List.of(), new FriendDtos.Settings("APPROVAL", false)));
        mvc.perform(get("/api/game/friends")).andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].name").value("我的好友"));
        mvc.perform(get("/api/game/friends/search").param("query", "bob")).andExpect(status().isOk());
        verify(friends).search(7L, "bob");
        mvc.perform(post("/api/game/friends/2").contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":99,\"groupId\":0}")).andExpect(status().isOk());
        verify(friends).add(7L, 2L, 0L);
        mvc.perform(put("/api/game/friends/settings").contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":99,\"friendRequestPolicy\":\"NONE\",\"blockStrangerMessages\":true}")).andExpect(status().isOk());
        verify(friends).updateSettings(7L, new FriendDtos.Settings("NONE", true));
        mvc.perform(post("/api/game/friends/requests/10/accept")).andExpect(status().isOk()); verify(friends).respond(7L, 10L, true);
        mvc.perform(delete("/api/game/friends/requests/10")).andExpect(status().isOk()); verify(friends).respond(7L, 10L, false);
        mvc.perform(post("/api/game/friends/2/block")).andExpect(status().isOk()); verify(friends).block(7L, 2L, true);
        mvc.perform(delete("/api/game/friends/2/block")).andExpect(status().isOk()); verify(friends).block(7L, 2L, false);
        mvc.perform(delete("/api/game/friends/groups/3")).andExpect(status().isOk()); verify(friends).deleteGroup(7L, 3L);
    }
}
