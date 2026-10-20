package com.wargame.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 时钟使用独立线程，不能因一次批量玩家结算耗时而误判为停服。 */
@Configuration
public class GameSchedulingConfig {
    @Bean(name = "gameClockTaskScheduler")
    public ThreadPoolTaskScheduler gameClockTaskScheduler() {
        return scheduler("game-clock-");
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("game-tasks-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
