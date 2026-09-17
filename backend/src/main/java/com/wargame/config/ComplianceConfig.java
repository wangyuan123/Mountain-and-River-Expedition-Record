package com.wargame.config;

import com.wargame.service.compliance.*;
import com.wargame.security.GameAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;

@Configuration
public class ComplianceConfig {
    @Bean @ConditionalOnMissingBean(Clock.class)
    public Clock complianceClock() { return Clock.systemUTC(); }

    /** 防沉迷暂时关闭；测试身份仍仅限显式 local/test 环境。 */
    @Bean
    public org.springframework.beans.factory.InitializingBean complianceStartupCheck(ComplianceProperties config,
            Environment environment, IdentityVault vault) {
        return () -> {
            var profiles = Arrays.asList(environment.getActiveProfiles());
            boolean prod = profiles.contains("prod");
            // TODO：产品完善并恢复默认启用后，取消下面两行注释。
            // if (!config.isEnabled() && (prod || !profiles.contains("test")))
            //     throw new IllegalStateException("正式运行不能关闭防沉迷控制");
            if (config.isLocalFixtures() && (prod || !(profiles.contains("local") || profiles.contains("test"))))
                throw new IllegalStateException("测试身份仅允许在 local/test 环境启用");
            if (config.isLocalFixtures() && config.getDataKey().isBlank())
                config.setDataKey(java.util.Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!config.getDataKey().isBlank()) vault.validate();
            if (!config.getSupportUrl().isBlank() && !config.getSupportUrl().startsWith("https://"))
                throw new IllegalStateException("客服地址必须使用 HTTPS");
            if (config.getPolicyVersion().isBlank() || config.getPolicyVersion().length() > 80)
                throw new IllegalStateException("防沉迷策略版本必须为1至80字符");
            if (!config.getCalendarFrom().isBlank() || !config.getCalendarThrough().isBlank() || !config.getCalendarSource().isBlank()) {
                if (config.getCalendarSource().isBlank() || LocalDate.parse(config.getCalendarFrom()).isAfter(LocalDate.parse(config.getCalendarThrough())))
                    throw new IllegalStateException("开放日历须包含审核来源与有效日期范围");
            }
            for (String date : config.getExtraOpenDates()) LocalDate.parse(date);
            for (String date : config.getClosedDates()) LocalDate.parse(date);
        };
    }

    /** 未取得正式技术规范时拒绝核验，不能把格式校验或测试结果当成实名通过。 */
    @Bean @ConditionalOnMissingBean(RealNameProvider.class)
    public RealNameProvider realNameProvider(ComplianceProperties config, Clock clock) {
        return new RealNameProvider() {
            public boolean available() { return config.isLocalFixtures(); }
            public VerifiedIdentity verify(String proof, Long playerId) {
                if (!config.isLocalFixtures()) throw new GameAccessException("IDENTITY_UNAVAILABLE", "实名服务尚未接通，请通过帮助入口联系运营方", 503);
                // 固定命名的虚构身份，不接受姓名、证件号或用户自报的出生日期。
                return switch (proof == null ? "" : proof) {
                    case "DEMO-ADULT" -> new VerifiedIdentity("demo-adult", LocalDate.of(1990, 1, 1), clock.millis() + 86_400_000L, null, null);
                    case "DEMO-PARENT" -> new VerifiedIdentity("demo-parent", LocalDate.of(1980, 1, 1), clock.millis() + 86_400_000L, null, null);
                    case "DEMO-TEEN" -> new VerifiedIdentity("demo-teen", LocalDate.of(2010, 1, 1), clock.millis() + 86_400_000L, "demo-parent", "demo-consent-v1");
                    case "DEMO-CHILD" -> new VerifiedIdentity("demo-child", LocalDate.of(2016, 1, 1), clock.millis() + 86_400_000L, "demo-parent", "demo-consent-v1");
                    default -> throw new GameAccessException("IDENTITY_REJECTED", "测试凭据无效", 400);
                };
            }
        };
    }
}
