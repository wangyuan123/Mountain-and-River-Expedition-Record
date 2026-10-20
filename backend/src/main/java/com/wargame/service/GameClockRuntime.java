package com.wargame.service;

import com.wargame.util.GameClock;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.LongSupplier;
import java.util.UUID;

/** 重启从最后提交的检查点继续，停机、数据库故障和长时间进程挂起均不补算。 */
@Component
@ConditionalOnProperty(name = "game.clock.enabled", havingValue = "true", matchIfMissing = true)
public class GameClockRuntime implements SmartInitializingSingleton, DisposableBean {
    private static final long MAX_PULSE_GAP_MS = 5_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LongSupplier monotonic;
    private final LongSupplier wall;
    private final String ownerToken = UUID.randomUUID().toString();
    private volatile long current;
    private long anchor;
    private AutoCloseable registration;

    @org.springframework.beans.factory.annotation.Autowired
    public GameClockRuntime(JdbcTemplate jdbc, TransactionTemplate transactions, EntityManagerFactory schema) {
        this(jdbc, transactions, System::nanoTime, System::currentTimeMillis);
    }

    GameClockRuntime(JdbcTemplate jdbc, TransactionTemplate transactions, LongSupplier monotonic, LongSupplier wall) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.monotonic = monotonic;
        this.wall = wall;
    }

    /** 在业务调度之前安装；首次升级从最后一次经济结算恢复，兼容现有绝对时间戳。 */
    @Override
    public synchronized void afterSingletonsInstantiated() {
        current = transactions.execute(status -> {
            var rows = jdbc.queryForList("SELECT game_time FROM game_clock WHERE singleton_id = 1 FOR UPDATE", Long.class);
            if (rows.isEmpty()) {
                long initial = initialTime();
                jdbc.update("INSERT INTO game_clock (singleton_id, game_time, heartbeat_at) VALUES (1, ?, 0)", initial);
                return initial;
            }
            if (rows.get(0) == 0) {
                long initial = initialTime();
                jdbc.update("UPDATE game_clock SET game_time = ? WHERE singleton_id = 1", initial);
                return initial;
            }
            return rows.get(0);
        });
        transactions.executeWithoutResult(status -> {
            var row = jdbc.queryForMap("SELECT owner_token, heartbeat_at FROM game_clock WHERE singleton_id = 1 FOR UPDATE");
            if (row.get("owner_token") == null || wall.getAsLong() - ((Number) row.get("heartbeat_at")).longValue() > MAX_PULSE_GAP_MS) {
                jdbc.update("UPDATE game_clock SET owner_token = ?, heartbeat_at = ? WHERE singleton_id = 1", ownerToken, wall.getAsLong());
            }
        });
        anchor = monotonic.getAsLong();
        registration = GameClock.use(() -> current);
    }

    /** 旧版没有全服心跳，最后一次玩家结算是首次部署能够恢复的最近运行点。 */
    private long initialTime() {
        Long lastTick = jdbc.queryForObject("SELECT MAX(last_tick) FROM players", Long.class);
        long realNow = wall.getAsLong();
        return lastTick == null || lastTick <= 0 ? realNow : Math.min(lastTick, realNow);
    }

    /** 服务就绪才建立运行锚点，数据库迁移和世界修复的耗时不参与活动结算。 */
    public synchronized void resume() { anchor = monotonic.getAsLong(); }

    /** 先提交再对业务可见；租约持有者独占推进，其他实例只同步，不能叠加运行时长。 */
    public synchronized void advance() {
        long sample = monotonic.getAsLong();
        long elapsed = Math.max(0, (sample - anchor) / 1_000_000L);
        boolean committed = false;
        try {
            current = transactions.execute(status -> {
                var row = jdbc.queryForMap("SELECT game_time, owner_token, heartbeat_at FROM game_clock WHERE singleton_id = 1 FOR UPDATE");
                long stored = ((Number) row.get("game_time")).longValue();
                long realNow = wall.getAsLong();
                if (!ownerToken.equals(row.get("owner_token"))) {
                    if (row.get("owner_token") == null || realNow - ((Number) row.get("heartbeat_at")).longValue() > MAX_PULSE_GAP_MS) {
                        // 接管只建立新锚点，租约失联期间绝不按真实时间追赶。
                        jdbc.update("UPDATE game_clock SET owner_token = ?, heartbeat_at = ? WHERE singleton_id = 1", ownerToken, realNow);
                    }
                    return stored;
                }
                long next = stored + (elapsed <= MAX_PULSE_GAP_MS ? elapsed : 0);
                jdbc.update("UPDATE game_clock SET game_time = ?, heartbeat_at = ? WHERE singleton_id = 1", next, realNow);
                return next;
            });
            committed = true;
        } finally {
            // 失败查询本身也可能阻塞数秒，必须排除整段失败事务耗时，不能在下次成功时补算。
            anchor = committed ? sample : monotonic.getAsLong();
        }
    }

    @Override public void destroy() throws Exception {
        try {
            // 正常关闭主动交接；强杀则由租约过期接管，均不结算未提交的零头。
            transactions.executeWithoutResult(status -> jdbc.update("UPDATE game_clock SET owner_token = NULL WHERE singleton_id = 1 AND owner_token = ?", ownerToken));
        } finally {
            if (registration != null) registration.close();
        }
    }
}
