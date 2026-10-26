package com.wargame.service.bot;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class BotSchedulingConfig {
    @Bean(name = "botTaskScheduler")
    public ThreadPoolTaskScheduler botTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("bot-decisions-");
        return scheduler;
    }
}
