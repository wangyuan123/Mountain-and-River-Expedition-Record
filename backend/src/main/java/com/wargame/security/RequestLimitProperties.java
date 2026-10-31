package com.wargame.security;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 单实例共享配额；普通轮询与写操作分别设置预算，不能以切换 URL 绕过。 */
@Data
@Component
@ConfigurationProperties(prefix = "game.security.rate-limit")
public class RequestLimitProperties {
    private int ipPerMinute = 1200;
    private int ipWritesPerMinute = 300;
    private int accountPerMinute = 600;
    private int accountWritesPerMinute = 120;
    private int websocketPerIpPerMinute = 60;
    private int adminLoginPerIpPerMinute = 20;
    private int adminLoginPerAccountPerQuarterHour = 10;

    @PostConstruct
    void validate() {
        if (ipPerMinute < 1 || ipWritesPerMinute < 1 || accountPerMinute < 1 || accountWritesPerMinute < 1
                || websocketPerIpPerMinute < 1 || adminLoginPerIpPerMinute < 1 || adminLoginPerAccountPerQuarterHour < 1)
            throw new IllegalArgumentException("所有请求限流配额必须大于零");
    }
}
