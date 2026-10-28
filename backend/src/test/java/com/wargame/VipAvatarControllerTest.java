package com.wargame;

import com.wargame.config.GlobalExceptionHandler;
import com.wargame.controller.VipController;
import com.wargame.model.entity.Player;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import com.wargame.service.VipService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VipAvatarControllerTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/x9sAAAAASUVORK5CYII=");
    private final AuthService auth = mock(AuthService.class);
    private final VipService vip = mock(VipService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        Player player = new Player();
        player.setId(42L);
        player.setVipLevel(5);
        when(auth.getCurrentPlayer()).thenReturn(player);
        var controller = new VipController(auth, vip, mock(GameStateService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void jsonUploadDelegatesToTransactionalServiceAndReturnsTheSavedAvatarUrl() throws Exception {
        String url = "img/avatars/custom/42_0123456789abcdef0123456789abcdef.png";
        when(vip.uploadCustomAvatar(eq(42L), any(byte[].class), eq("png")))
                .thenReturn(Map.of("success", true, "customAvatar", url));
        var response = mvc.perform(post("/api/game/vip/avatar/upload")
                .contentType("application/json").content(uploadBody(PNG)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.customAvatar").isString()).andReturn().getResponse();
        assertEquals(url, JsonUtil.parseObjMap(response.getContentAsString()).get("customAvatar"));
        verify(vip).uploadCustomAvatar(eq(42L), aryEq(PNG), eq("png"));
    }

    @Test
    void urlSettingAndResetBindTheSameUrlFieldUsedByTheClient() throws Exception {
        when(vip.setCustomAvatarUrl(anyLong(), anyString())).thenReturn(Map.of("success", true));
        for (String url : new String[] {"https://example.com/avatar.png", ""}) {
            mvc.perform(post("/api/game/vip/avatar/url").contentType("application/json")
                    .content(JsonUtil.toJson(Map.of("url", url))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            verify(vip).setCustomAvatarUrl(42L, url);
        }
    }

    @Test
    void oversizedAndNonImageUploadsDoNotWriteFilesOrUpdateThePlayer() throws Exception {
        mvc.perform(post("/api/game/vip/avatar/upload").contentType("application/json")
                .content(uploadBody(new byte[1024 * 1024 + 1])))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("头像大小不能超过 1MB"));
        mvc.perform(post("/api/game/vip/avatar/upload").contentType("application/json")
                .content(uploadBody(new byte[] {1, 2, 3, 4})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("仅支持 PNG、JPEG 或 WebP 格式的图片文件"));
        verifyNoInteractions(vip);
    }

    private String uploadBody(byte[] bytes) {
        return JsonUtil.toJson(Map.of("dataUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)));
    }
}
