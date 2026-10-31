package com.wargame.service;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Resources;
import com.wargame.model.entity.ScoutReport;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.repository.ScoutReportRepository;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ResourceOverflowService {
    @org.springframework.beans.factory.annotation.Autowired private jakarta.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    public static final long STOCK_LIMIT = 1_000_000_000L;
    private static final Map<String, String> RESOURCE_NAMES = Map.of(
            "food", "粮食", "steel", "钢铁", "oil", "石油", "rare", "稀矿", "gold", "黄金");

    private final ResourcesRepository resources;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final ScoutReportRepository reports;
    private final WebSocketPushService websocket;
    private final JdbcTemplate jdbc;

    /**
     * 按已持久化的游戏周期至多执行一次全服库存巡检。
     * @param period 与野地刷新共用的游戏时间周期编号
     */
    @Transactional
    public void checkPeriod(long period) {
        List<Long> rows = jdbc.query("SELECT last_period FROM game_schedule WHERE task_key = 'resource-overflow' FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1));
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO game_schedule (task_key, last_period) VALUES ('resource-overflow', ?)", period);
            return;
        }
        long previous = rows.get(0);
        if (period <= previous) return;
        jdbc.update("UPDATE game_schedule SET last_period = ? WHERE task_key = 'resource-overflow'", period);
        if (previous == 0) return;
        inspectCities(period);
    }

    /**
     * 扫描所有玩家库存，仅处理主城和已完成建造的分城；钻石不属于可巡检资源。
     * @param period 当前已提交的游戏周期
     */
    private void inspectCities(long period) {
        long gameNow = GameClock.now();
        // 先筛候选，逐城按账号、城市、库存的顺序取得当前读，避免使用冻结前的快照。
        List<Resources> balances = resources.findAllForOverflowTreatment(STOCK_LIMIT);
        if (balances.isEmpty()) return;

        for (Resources balance : balances) {
            Player player = players.lockById(balance.getPlayerId()).orElse(null);
            if (player == null) continue;
            int slot = Objects.requireNonNullElse(balance.getCitySlot(), 0);
            PlayerCity city = cities.findByOwnerIdAndCitySlot(player.getId(), slot).orElse(null);
            if (city != null) em.refresh(city, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            // 冻结期间不扣库存，也不推进该资源的复查阶段。
            if (city != null && city.isSurrenderFrozen(gameNow)) continue;
            if (slot > 0 && (city == null || Objects.requireNonNullElse(city.getReadyAt(), 0L) > gameNow)) continue;
            em.refresh(balance, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            economy.beforeResourceChange(player.getId(), slot);
            balance = resources.findCityForTreatment(player.getId(), slot).orElseThrow();

            String cityName = slot == 0
                    ? firstNonBlank(player.getCityName(), city == null ? null : city.getName(), "主城")
                    : firstNonBlank(city.getName(), "分城");
            Integer x = slot == 0 && player.getCityPosX() != null ? player.getCityPosX() : city == null ? null : city.getX();
            Integer y = slot == 0 && player.getCityPosY() != null ? player.getCityPosY() : city == null ? null : city.getY();
            List<Map<String, Object>> losses = new ArrayList<>();
            inspectResource(balance, "food", period, losses);
            inspectResource(balance, "steel", period, losses);
            inspectResource(balance, "oil", period, losses);
            inspectResource(balance, "rare", period, losses);
            inspectResource(balance, "gold", period, losses);
            if (losses.isEmpty()) continue;

            resources.save(balance);
            createLossReport(player.getId(), slot, cityName, x, y, period, losses);
        }
    }

    /**
     * 按城市和资源独立处理阶段：首次损失超额的一半，仍超限的下一周期清除剩余超额。
     * @param balance 城市库存
     * @param type 资源字段名
     * @param period 当前游戏周期
     * @param losses 本城市本周期的战报损失明细
     */
    private void inspectResource(Resources balance, String type, long period, List<Map<String, Object>> losses) {
        Long stock = getStock(balance, type);
        long current = stock == null ? 0L : stock;
        int stage = getStage(balance.getId(), type);
        if (current <= STOCK_LIMIT) {
            if (stage > 0) clearStage(balance.getId(), type);
            return;
        }

        long excess = current - STOCK_LIMIT;
        // 首次仅损失超额的一半；同一资源下次检查仍超限时清除全部剩余超额。
        long loss = stage > 0 ? excess : excess / 2 + excess % 2;
        long after = current - loss;
        setStock(balance, type, after);
        if (stage > 0 || after <= STOCK_LIMIT) clearStage(balance.getId(), type);
        else jdbc.update("INSERT INTO resource_overflow_state (resource_id, resource_type, stage, last_period) VALUES (?, ?, 1, ?)",
                balance.getId(), type, period);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resourceType", type);
        item.put("resourceName", RESOURCE_NAMES.get(type));
        item.put("lossAmount", loss);
        item.put("beforeAmount", current);
        item.put("afterAmount", after);
        item.put("stage", stage > 0 ? "final" : "first");
        losses.add(item);
    }

    private int getStage(Long resourceId, String type) {
        List<Integer> values = jdbc.query("SELECT stage FROM resource_overflow_state WHERE resource_id = ? AND resource_type = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt(1), resourceId, type);
        return values.isEmpty() ? 0 : values.get(0);
    }

    private void clearStage(Long resourceId, String type) {
        jdbc.update("DELETE FROM resource_overflow_state WHERE resource_id = ? AND resource_type = ?", resourceId, type);
    }

    private void createLossReport(Long playerId, int slot, String cityName, Integer x, Integer y, long period,
                                  List<Map<String, Object>> losses) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", "资源损耗报告");
        data.put("cause", "仓库溢出");
        data.put("cityName", cityName);
        data.put("citySlot", slot);
        data.put("targetName", cityName);
        data.put("x", x);
        data.put("y", y);
        data.put("losses", losses);
        data.put("period", period);

        ScoutReport report = new ScoutReport();
        report.setPlayerId(playerId);
        report.setTargetX(x);
        report.setTargetY(y);
        report.setTargetName(cityName);
        report.setType("resource_loss");
        report.setCreatedAt(GameClock.now());
        report.setReadAt(0L);
        report.setData(JsonUtil.toJson(data));
        ScoutReport saved = reports.save(report);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", saved.getId());
        message.put("type", "resource_loss");
        message.put("time", saved.getCreatedAt());
        message.put("readAt", 0L);
        message.put("starred", false);
        message.put("detailsAvailable", true);
        message.put("data", data);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                websocket.pushScoutReport(playerId, message);
            }
        });
    }

    private Long getStock(Resources balance, String type) {
        return switch (type) {
            case "food" -> balance.getFood();
            case "steel" -> balance.getSteel();
            case "oil" -> balance.getOil();
            case "rare" -> balance.getRare();
            case "gold" -> balance.getGold();
            default -> throw new IllegalArgumentException("未知资源类型: " + type);
        };
    }

    private void setStock(Resources balance, String type, long value) {
        switch (type) {
            case "food" -> balance.setFood(value);
            case "steel" -> balance.setSteel(value);
            case "oil" -> balance.setOil(value);
            case "rare" -> balance.setRare(value);
            case "gold" -> balance.setGold(value);
            default -> throw new IllegalArgumentException("未知资源类型: " + type);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "城市";
    }

}
