package com.wargame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 未配置投递凭据时仍可登录旧账号，但不能降级为免邮箱注册。 */
@Data
@Component
@ConfigurationProperties(prefix = "game.email")
public class AccountEmailProperties {
    private boolean enabled = true;
    private String host = "smtp.qq.com";
    private int port = 465;
    private boolean ssl = true;
    private String username = "";
    private String password = "";
    private String from = "";
    private String dataKey = "";
    private String hmacKey = "";
    private int hourlyLimit = 200;
    private int dailyLimit = 1000;
    private int queueLimit = 1000;
    private String supportUrl = "";

    public boolean available() {
        return enabled && !host.isBlank() && !username.isBlank() && !password.isBlank()
                && !from.isBlank() && !dataKey.isBlank() && !hmacKey.isBlank();
    }
}
