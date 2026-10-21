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

    /** 数据保留维护独立运行，不能占用游戏时钟或常规调度的单线程。 */
    @Bean(name = "retentionTaskScheduler")
    public ThreadPoolTaskScheduler retentionTaskScheduler() {
        return scheduler("data-retention-");
    }

    /** 容量盘点独立运行，避免大表统计拖延生命周期批次或限流器淘汰。 */
    @Bean(name = "retentionMetricsTaskScheduler")
    public ThreadPoolTaskScheduler retentionMetricsTaskScheduler() {
        return scheduler("retention-metrics-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
