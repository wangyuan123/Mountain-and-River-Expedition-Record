package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wargame.controller.ArmyController;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.AuthService;
import com.wargame.service.BattleActionPreferences;
import com.wargame.service.GameStateService;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.PlayerCityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 覆盖完整 JSON 表单到控制器、持久化再回读，防止新增迎战字段被旧接口字段白名单拒绝。 */
class BattleDefaultsControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Player player = new Player();
        player.setId(7L);
        PlayerRepository players = mock(PlayerRepository.class);
        when(players.findById(7L)).thenReturn(Optional.of(player));
        AuthService auth = mock(AuthService.class);
        when(auth.getCurrentPlayer()).thenReturn(player);
        ArmyService army = mock(ArmyService.class);
        when(army.sortieCap(7L)).thenReturn(37500);
        BattleActionPreferences preferences = new BattleActionPreferences(players, army,
                mock(MarchRepository.class), mock(BattleSessionRepository.class), mock(PlayerCityRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(new ArmyController(auth, army,
                mock(GameStateService.class), preferences)).build();
    }

    private ObjectNode readForm() throws Exception {
        return (ObjectNode) json.readTree(mvc.perform(get("/api/game/army/battle-defaults"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ObjectNode saveForm(ObjectNode form) throws Exception {
        form.remove("sortieCap");
        form.remove("locked");
        return (ObjectNode) json.readTree(mvc.perform(post("/api/game/army/battle-defaults")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(form)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void savesCurrentActionsAndManualCountsTogetherAndReloadsThem() throws Exception {
        ObjectNode form = readForm();
        ((ObjectNode) form.get("outgoing")).put("infantry", "RETREAT");
        ((ObjectNode) form.get("defending")).put("infantry", "HOLD");
        form.putObject("sortieArmy").put("infantry", 50).put("rocket", 0);
        ObjectNode saved = saveForm(form);
        assertEquals(form.get("outgoing"), saved.get("outgoing"));
        assertEquals(form.get("defending"), saved.get("defending"));
        assertEquals(form.get("sortieArmy"), saved.get("sortieArmy"));
        assertEquals(saved, readForm());
    }

    @Test
    void savesAutomaticAndZeroArmyAsDistinctConfigurations() throws Exception {
        ObjectNode form = readForm();
        form.putObject("sortieArmy").put("infantry", 0);
        assertEquals(0, saveForm(form).get("sortieArmy").get("infantry").intValue());
        form.putNull("sortieArmy");
        assertTrue(saveForm(form).get("sortieArmy").isNull());
        assertTrue(readForm().get("sortieArmy").isNull());
    }

    @Test
    void acceptsUnchangedFormWithoutEditingEveryUnit() throws Exception {
        ObjectNode form = readForm();
        ObjectNode saved = saveForm(form);
        assertEquals(form.get("outgoing"), saved.get("outgoing"));
        assertEquals(form.get("defending"), saved.get("defending"));
        assertTrue(saved.get("sortieArmy").isNull());
    }
}
