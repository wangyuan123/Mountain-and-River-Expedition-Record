package com.wargame;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class IncomingReportShareIntegrationTest extends BaseServiceTest {
    @Autowired IncomingReportShareService shares;
    @Autowired SharedIncomingReportRepository snapshots;
    @Autowired WorldViewService views;
    @Autowired GuildChatService chat;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired ObjectMapper mapper;
    private Player defender;
    private Player ally;

    @BeforeEach
    void setup() {
        defender = createTestPlayer("share-defender", 30);
        ally = createTestPlayer("share-ally", 30);
        var guild = new Guild();
        guild.setName("军情测试团");
        guild.setLeaderPlayerId(defender.getId());
        guild.setCreatedAt(System.currentTimeMillis());
        guilds.save(guild);
        members.save(new GuildMember(null, guild.getId(), defender.getId(), "leader", 1L));
        members.save(new GuildMember(null, guild.getId(), ally.getId(), "member", 1L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void persistedGuildReportMatchesEveryRadarLevelAndSurvivesIncomingDeletion(int radar) throws Exception {
        createTechnology(defender.getId(), "recon_radar", radar);
        // 接收方满级雷达也只能读取分享者当时探明的内容。
        createTechnology(ally.getId(), "recon_radar", 10);
        var incoming = new IncomingMarch();
        incoming.setTargetPlayerId(defender.getId());
        incoming.setFromName("袭击者");
        incoming.setFromX(20);
        incoming.setFromY(30);
        incoming.setAction(radar % 2 == 0 ? "scout" : "plunder");
        incoming.setArmy("{\"infantry\":150,\"fighter\":10}");
        incoming.setArriveAt(GameClock.now() + 60000);
        incomingMarchRepository.save(incoming);
        var expected = mapper.readValue(mapper.writeValueAsString(views.getIncoming(defender).get(0)),
                new TypeReference<Map<String, Object>>() {});

        var message = shares.share(defender.getId(), incoming.getId().toString());
        assertTrue(message.content().matches("\\[军情:[0-9a-f-]{36}\\]"));
        assertEquals(message.content(), chat.history(ally.getId()).get(0).content());
        String token = message.content().substring(4, message.content().length() - 1);
        assertTrue(snapshots.existsById(token));
        assertEquals(expected, shares.read(ally.getId(), token).get("incoming"));
        assertTrue(scoutReportRepository.findByPlayerId(defender.getId()).isEmpty());

        incomingMarchRepository.delete(incoming);
        assertTrue(views.getIncoming(defender).isEmpty());
        assertEquals(expected, shares.read(ally.getId(), token).get("incoming"));
        members.deleteByPlayerId(ally.getId());
        assertThrows(IllegalArgumentException.class, () -> shares.read(ally.getId(), token));
    }
}
