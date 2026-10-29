package com.wargame;

import com.wargame.config.GlobalExceptionHandler;
import com.wargame.controller.ShopController;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RechargeDisabledControllerTest {

    @ParameterizedTest
    @ValueSource(strings = {"{\"pkgId\":\"p6\"}", "{\"pkgId\":\"p10000\",\"diamond\":999999,\"rmb\":0}", "{}", "null", ""})
    void directHttpRechargeIsForbiddenWithoutTouchingAccountOrResources(String body) throws Exception {
        var auth = mock(AuthService.class);
        var resources = mock(ResourcesRepository.class);
        var items = mock(PlayerItemRepository.class);
        var state = mock(GameStateService.class);
        var controller = new ShopController(auth, resources, items, state);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/game/shop/recharge").contentType("application/json").content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYMENT_DISABLED"))
                .andExpect(jsonPath("$.error").value("暂未开放充值，敬请期待"));
        verifyNoInteractions(auth, resources, items, state);
    }
}
