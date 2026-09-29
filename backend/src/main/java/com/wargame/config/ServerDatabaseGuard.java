package com.wargame.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 在世界初始化和定时任务使用数据库前，确保该库只属于当前大区。 */
@Component
@Profile("!test")
public class ServerDatabaseGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    private final GameServerIdentity server;
    private final TransactionTemplate transactions;

    public ServerDatabaseGuard(JdbcTemplate jdbc, GameServerIdentity server, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.server = server;
        this.transactions = transactions;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 在 ContextRefreshedEvent 启动定时任务、ApplicationRunner 初始化世界之前完成绑定。
        transactions.executeWithoutResult(status -> {
            String owner = jdbc.queryForObject(
                    "SELECT server_id FROM game_server_identity WHERE singleton_id = 1 FOR UPDATE", String.class);
            if (owner == null) {
                jdbc.update("UPDATE game_server_identity SET server_id = ? WHERE singleton_id = 1", server.id());
            } else if (!server.id().equals(owner)) {
                throw new IllegalStateException("当前数据库属于大区 " + owner + "，不能由 " + server.id() + " 启动");
            }
        });
    }
}
