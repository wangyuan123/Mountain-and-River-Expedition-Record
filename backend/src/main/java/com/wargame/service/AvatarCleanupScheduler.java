package com.wargame.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/** 每小时清理一天前未被引用的本地头像，回收历史上传、异常退出及注销账号的遗留文件。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AvatarCleanupScheduler {
    private final AvatarStorageService avatars;

    @Scheduled(fixedDelayString = "${game.avatar.cleanup-interval-ms:3600000}", initialDelay = 60000)
    public void cleanup() {
        try {
            int removed = avatars.cleanupOrphans(Duration.ofDays(1));
            if (removed > 0) log.info("已清理 {} 个未被引用的头像文件", removed);
        } catch (IOException | RuntimeException e) {
            log.warn("头像目录清理失败，将在下次定时任务重试", e);
        }
    }
}
