package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.pvp.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.concurrent.*;

/** 手工验收专用内存库：与运行中的游戏数据库隔离，只启用演习调度。 */
public class PvpLocalHarness {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WargameApplication.class);
        app.setAdditionalProfiles("test");
        ConfigurableApplicationContext context = app.run(args);
        var players = context.getBean(PlayerRepository.class); var password = context.getBean(PasswordEncoder.class);
        var game = context.getBean(GameStateService.class); var troops = context.getBean(ArmyUnitRepository.class);
        WorldMap world = new WorldMap(); world.setSize(200); world.setScanRadius(3); world.setPosX(0); world.setPosY(0);
        context.getBean(WorldMapRepository.class).save(world);
        for (String username : new String[]{"pvp_alpha", "pvp_bravo", "pvp_charlie", "pvp_delta", "pvp_locked"}) {
            Player p = new Player(); p.setUsername(username); p.setPasswordHash(password.encode("PvpLocalTest123!")); p.setFaction("allies");
            p.setDisplayName("演习·" + username); p = players.save(p); game.initializeNewPlayer(p.getId());
            // 与正式页面的准入门槛一致；另留低声望账号验证接口不能绕过限制。
            p = players.findById(p.getId()).orElseThrow();
            p.setPrestige(username.equals("pvp_locked") ? 0 : 10000); players.save(p);
            ArmyUnit u = new ArmyUnit(); u.setPlayerId(p.getId()); u.setCitySlot(0); u.setType("infantry"); u.setCount(1000); troops.save(u);
        }
        var practice = context.getBean(PvpPracticeService.class); var matches = context.getBean(PvpMatchService.class);
        var sessions = context.getBean(PvpPracticeSessionRepository.class); var tickets = context.getBean(PvpMatchTicketRepository.class);
        var timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                for (String ticket : tickets.expired(now, now - PvpPracticeService.SEARCH_MS, PageRequest.of(0, 100))) practice.expireTicket(ticket);
                for (String session : sessions.due(now, PageRequest.of(0, 100))) practice.advance(session);
                matches.match();
            } catch (Exception e) { e.printStackTrace(); }
        }, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> timer.shutdownNow()));
        System.out.println("PVP_TEST_READY: isolated H2; pvp_alpha / pvp_bravo / pvp_charlie / pvp_delta / pvp_locked; password=PvpLocalTest123!");
    }
}
