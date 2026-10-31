package com.wargame.config;

import com.wargame.model.entity.AdminUser;
import com.wargame.repository.AdminUserRepository;
import com.wargame.security.AdminCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 显式初始化后台凭据；只创建新账号或替换旧迁移的固定密码，不重置已配置的管理员。 */
@Component
@Profile("!test")
public class AdminAccountBootstrap implements ApplicationRunner {
    private final AdminUserRepository admins;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public AdminAccountBootstrap(AdminUserRepository admins, PasswordEncoder passwords,
                                 @Value("${game.security.admin-bootstrap-username:admin}") String username,
                                 @Value("${game.security.admin-bootstrap-password:}") String password) {
        this.admins = admins;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.isEmpty()) return;
        AdminCredentials.validateNewPassword(password);
        if (!username.matches("[a-zA-Z0-9_.-]{3,50}")) throw new IllegalArgumentException("初始化管理员用户名需为 3—50 位字母、数字、下划线、点或连字符");
        AdminUser admin = admins.findByUsername(username).orElse(null);
        if (admin != null && !AdminCredentials.LEGACY_DEFAULT_HASH.equals(admin.getPasswordHash())) return;
        if (admin == null) {
            admin = new AdminUser();
            admin.setUsername(username);
            admin.setCreatedAt(System.currentTimeMillis());
        }
        admin.setPasswordHash(passwords.encode(password));
        admin.setRole("SUPER_ADMIN");
        admin.setDisabled(0);
        admin.setUpdatedAt(System.currentTimeMillis());
        admins.saveAndFlush(admin);
    }
}
