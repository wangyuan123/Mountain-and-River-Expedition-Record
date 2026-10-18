package com.wargame;

import com.wargame.repository.AcademyRepository;
import com.wargame.service.ImportService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 旧存档中的废弃键可以继续导入，但不能覆盖现行账号额度或丢失有效状态。 */
class LegacySaveImportTest extends BaseServiceTest {
    @Autowired ImportService imports;
    @Autowired AcademyRepository academies;

    @Test
    void ignoresRetiredKeysAndKeepsAccountCooldownWarProtectionAndStableMarchTarget() {
        createTestWorld();
        var player = createTestPlayer();
        player.setAcademyRefreshAt(12345L);
        player.setWarAt(23456L);
        playerRepository.saveAndFlush(player);

        imports.importFromJson(player.getId(), """
                {"academy":{"refreshAt":9999999999999,"list":[{"name":"候选军官","star":3}]},
                 "cityState":{"status":"war","warTarget":999,"warAt":888,
                   "warUntil":34567,"shieldUntil":45678,"peaceUntil":56789},
                 "world":{"marches":[{"targetIdx":99,"targetId":"123","targetKind":"bandit",
                   "targetName":"寇 Lv.1","targetX":18,"targetY":18,"action":"scout",
                   "army":{"scout":1},"startAt":100,"arriveAt":200}]}}
                """);

        var account = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(12345L, account.getAcademyRefreshAt());
        assertEquals(23456L, account.getWarAt());
        var academy = academies.findByPlayerId(player.getId()).orElseThrow();
        assertEquals("候选军官", JsonUtil.parseList(academy.getOfficers()).get(0).get("name"));
        var city = cityStateRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals("war", city.getStatus());
        assertEquals(34567L, city.getWarEndAt());
        assertEquals(45678L, city.getShieldUntil());
        assertEquals(56789L, city.getPeaceUntil());
        var march = marchRepository.findByPlayerId(player.getId()).get(0);
        assertEquals("123", march.getTargetId());
        assertEquals("bandit", march.getTargetKind());
        assertEquals(Map.of("scout", 1), JsonUtil.parseIntMap(march.getArmy()));
        assertEquals(200L, march.getArriveAt());
    }
}
