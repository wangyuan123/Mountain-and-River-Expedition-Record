package com.wargame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** API 与 WebSocket 共用明确来源白名单，空值表示仅开放同源访问。 */
@Component
public class SecurityOrigins {
    private final List<String> origins;

    /** 拒绝通配符、用户信息和路径，防止部署配置意外放开任意网站。 */
    public SecurityOrigins(@Value("${game.security.allowed-origins:}") String configured) {
        origins = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || origin.contains("*") || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException("game.security.allowed-origins 必须是明确的 HTTP(S) 来源，不能含路径或通配符");
            }
        }
    }

    public List<String> values() {
        return origins;
    }
}
