package com.wargame.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** SMTP 慢连接不占用游戏 Tick 和数据保留任务线程。 */
@Configuration
public class AccountEmailSchedulingConfig {
    @Bean(name = "accountEmailTaskScheduler")
    public ThreadPoolTaskScheduler accountEmailTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("account-email-");
        return scheduler;
    }
}
