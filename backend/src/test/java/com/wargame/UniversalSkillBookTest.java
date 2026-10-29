package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.controller.DepotController;
import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.DepotService;
import com.wargame.service.OfficerService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UniversalSkillBookTest extends BaseServiceTest {
    @Autowired private ShopController shop;
    @Autowired private DepotController depot;
    @Autowired private DepotService depotService;
    @Autowired private OfficerService officers;
    @Autowired private PlayerItemRepository items;
    @Autowired private ObjectMapper mapper;
    private Player player;
    private Officer officer;

    @BeforeEach
    void setUp() {
        player = createTestPlayer("universal-books", 30);
        officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setStar(5);
        officer.setSkills("[]");
        officerRepository.saveAndFlush(officer);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UserPrincipal.from(player), null, UserPrincipal.from(player).getAuthorities()));
        var resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(2000);
        resourcesRepository.saveAndFlush(resources);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void give(String key, int count) {
        items.saveAndFlush(new PlayerItem(null, player.getId(), key, count, 0L));
    }

    private int count(String key) {
        return items.findByPlayerIdAndItemKey(player.getId(), key).orElseThrow().getCount();
    }

    private Map<String, Object> use(String skillId) {
        return depot.useItem(new GameDtos.DepotUseRequest("universalSkillBook", officer.getId(), null, skillId)).getBody();
    }

    private void skills(String... skillIds) {
        var current = officerRepository.findById(officer.getId()).orElseThrow();
        current.setSkills(JsonUtil.toJson(java.util.Arrays.stream(skillIds).map(id -> Map.of("id", id, "lv", 1)).toList()));
        officerRepository.saveAndFlush(current);
    }

    @Test
    void buyAndChooseFixedLevelOneAndReplayDoesNotConsumeAgain() {
        var buy = shop.buy(new GameDtos.ShopBuyRequest("universalSkillBook")).getBody();
        assertEquals(true, buy.get("success"));
        assertEquals(800, buy.get("price"));
        assertEquals(1200, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals("[]", officerRepository.findById(officer.getId()).orElseThrow().getSkills());
        shop.buy(new GameDtos.ShopBuyRequest("universalSkillBook"));
        var learned = use("frenzy");
        assertEquals(true, learned.get("success"));
        assertEquals("frenzy", learned.get("skillId"));
        assertEquals(1, learned.get("level"));
        assertEquals(1, count("universalSkillBook"));
        assertNotNull(learned.get("state"));
        assertEquals(false, use("frenzy").get("success"));
        assertEquals(1, count("universalSkillBook"));
        assertEquals(List.of(Map.of("id", "frenzy", "lv", 1)), JsonUtil.parseList(
                officerRepository.findById(officer.getId()).orElseThrow().getSkills()));
    }

    @Test
    void rejectsInvalidTargetsAliasesOtherPlayersAndMissingStock() {
        give("universalSkillBook", 1);
        for (String invalid : new String[] {null, "", "supply", "not-a-skill"}) {
            assertEquals(false, use(invalid).get("success"));
            assertEquals(1, count("universalSkillBook"));
        }
        var other = createTestPlayer("book-other", 30);
        var foreign = createOfficer(other.getId(), "idle", 30, 30, 30);
        for (Long invalidOfficer : new Long[] {null, Long.MAX_VALUE, foreign.getId()}) {
            assertEquals(false, depotService.useItem(player.getId(), "universalSkillBook", invalidOfficer, null, "frenzy").get("success"));
            assertEquals(1, count("universalSkillBook"));
        }
        items.tryConsume(player.getId(), "universalSkillBook", 1, 0L);
        assertEquals(false, use("frenzy").get("success"));
        assertEquals("[]", officerRepository.findById(officer.getId()).orElseThrow().getSkills());
    }

    @Test
    void rejectsBothConflictDirectionsFullSlotsAndLegacyDuplicates() {
        give("universalSkillBook", 3);
        skills("bulwark");
        assertEquals(false, use("borrow_armor").get("success"));
        skills("borrow_armor");
        assertEquals(false, use("bulwark").get("success"));
        skills("supply");
        assertEquals(false, use("leadership").get("success"));
        skills("frenzy", "pierce", "blitz");
        assertEquals(false, use("medic").get("success"));
        assertEquals(3, count("universalSkillBook"));
    }

    @Test
    void acceptsEveryFormalSkillAndNeverTreatsUniversalBookAsUpgradeMaterial() {
        give("universalSkillBook", 20);
        for (String skillId : OfficerSkillDef.OFFICER_SKILLS.keySet()) {
            skills();
            assertEquals(true, use(skillId).get("success"), skillId);
            assertEquals(false, officers.upgradeSkill(player.getId(), officer.getId(), 0, "universalSkillBook").get("success"));
        }
        assertEquals(5, count("universalSkillBook"));
    }

    @Test
    void oldRandomBookAndGiftKeepTheirInventoryKeysAndOptionalFieldIsCompatible() throws Exception {
        var oldRequest = mapper.readValue("{\"itemId\":\"skillBook\",\"officerId\":" + officer.getId() + "}", GameDtos.DepotUseRequest.class);
        assertNull(oldRequest.skillId());
        var newRequest = mapper.readValue("{\"itemId\":\"universalSkillBook\",\"officerId\":" + officer.getId() + ",\"skillId\":\"frenzy\"}", GameDtos.DepotUseRequest.class);
        assertEquals("frenzy", newRequest.skillId());
        var buy = shop.buy(new GameDtos.ShopBuyRequest("skillBook")).getBody();
        assertEquals("已购买 普通技能书", buy.get("message"));
        assertEquals(80, buy.get("price"));
        assertEquals(true, depotService.useItem(player.getId(), "skillBook", officer.getId(), null, "not-a-skill").get("success"));
        assertEquals(0, count("skillBook"));
        assertTrue(ItemDef.ITEMS.get("stage_gift_2").desc().contains("普通技能书×2"));
        give("stage_gift_2", 1);
        assertEquals(true, depotService.useItem(player.getId(), "stage_gift_2", null, null).get("success"));
        assertEquals(2, count("skillBook"));
        assertTrue(items.findByPlayerIdAndItemKey(player.getId(), "universalSkillBook").isEmpty());
    }
}
