package com.wargame.service.compliance;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 维护失败不放宽准入，HTTP 与 WebSocket 的绝对截止校验始终生效。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ComplianceMaintenance {
    private final AntiAddictionService protection;
    @Scheduled(fixedDelay = 60000)
    public void run() { protection.maintain(); }
}
