package com.wargame;

import com.wargame.service.GameClockScheduler;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 启用生产时钟配置，覆盖建表、启动安装、心跳和跨域前端同步的真实装配。 */
@SpringBootTest(properties = {
        "game.clock.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:game-clock-startup;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "game.security.allowed-origins=http://localhost:3000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameClockStartupTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired GameClockScheduler scheduler;
    @Autowired MockMvc mvc;

    @Test void readyApplicationUsesPersistedClockAndExposesItToBrowser() throws Exception {
        long initial = GameClock.now();
        assertTrue(initial > 0);
        assertNotNull(jdbc.queryForObject("SELECT owner_token FROM game_clock WHERE singleton_id=1", String.class));
        scheduler.pulse();
        long after = GameClock.now();
        assertTrue(after >= initial);
        assertEquals(after, jdbc.queryForObject("SELECT game_time FROM game_clock WHERE singleton_id=1", Long.class));
        var response = mvc.perform(get("/api/auth/server").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Game-Time"))
                .andExpect(header().exists("X-Game-Time"))
                .andReturn().getResponse();
        assertTrue(Long.parseLong(response.getHeader("X-Game-Time")) >= after);
    }
}
