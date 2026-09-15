package com.wargame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.GameWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 推送服务 - 向指定玩家推送实时游戏消息。
 * <p>
 * 消息格式 (JSON):
 * <pre>
 * {
 *   "type": "tick" | "march" | "battle" | "incoming" | "ping" | "pong",
 *   "data": { ... },
 *   "timestamp": 1234567890
 * }
 * </pre>
 * <p>
 * 离线玩家（无活跃会话）的消息直接丢弃，不做排队。
 */
@Service
public class WebSocketPushService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPushService.class);

    @org.springframework.beans.factory.annotation.Autowired(required = false) private CityScope cityScope;
    private final GameWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    public WebSocketPushService(GameWebSocketHandler handler) {
        this.handler = handler;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 向指定玩家推送消息。
     *
     * @param playerId 玩家 ID
     * @param type     消息类型 (tick/march/battle/incoming/ping/pong)
     * @param data     消息数据
     */
    public void pushToPlayer(Long playerId, String type, Object data) {
        if (!handler.isPlayerOnline(playerId)) return;
        send(type, data, playerId);
    }

    public void broadcast(String type, Object data) {
        send(type, data, null);
    }

    private void send(String type, Object data, Long playerId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("data", data);
        message.put("timestamp", System.currentTimeMillis());
        if (playerId != null && cityScope != null && java.util.Set.of("tick", "march", "army", "build", "resources").contains(type)) {
            message.put("citySlot", cityScope.slot(playerId));
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            // Freeze the payload now, but never announce a rolled-back battle or purchase.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deliver(playerId, json);
                    }
                });
            } else {
                deliver(playerId, json);
            }
        } catch (Exception e) {
            log.warn("推送消息失败: playerId={}, type={}", playerId, type, e);
        }
    }

    private void deliver(Long playerId, String json) {
        try {
            if (playerId == null) handler.broadcast(json);
            else handler.sendToPlayer(playerId, json);
        } catch (Exception e) {
            log.warn("提交后推送失败: playerId={}", playerId, e);
        }
    }

    /**
     * 推送 tick 更新 - 包含变化的资源、行军进度、建筑进度。
     *
     * @param playerId     玩家 ID
     * @param stateChanges 变化的状态数据
     */
    public void pushTickUpdate(Long playerId, Map<String, Object> stateChanges) {
        pushToPlayer(playerId, "tick", stateChanges);
    }

    /**
     * 推送行军更新 - 行军到达、采集完成、返程到达等事件。
     *
     * @param playerId 玩家 ID
     * @param marchInfo 行军信息 (event: arrived/gatherComplete/returned)
     */
    public void pushMarchUpdate(Long playerId, Map<String, Object> marchInfo) {
        pushToPlayer(playerId, "march", marchInfo);
    }

    /**
     * 推送战报 - 战斗解析结果。
     *
     * @param playerId 玩家 ID
     * @param report   战报数据
     */
    public void pushBattleReport(Long playerId, Map<String, Object> report) {
        pushToPlayer(playerId, "battle", report);
    }

    /**
     * 推送侦查报告 - 让前端战报列表能即时显示新完成的侦查。
     * data 字段为完整侦查报告 (含 result/showCityInfo/部队对比 等)。
     */
    public void pushScoutReport(Long playerId, Map<String, Object> report) {
        pushToPlayer(playerId, "scoutReport", report);
    }

    /**
     * 推送来袭警报 - 敌军来袭通知。
     *
     * @param playerId  玩家 ID
     * @param attackInfo 来袭信息
     */
    public void pushIncomingAttack(Long playerId, Map<String, Object> attackInfo) {
        pushToPlayer(playerId, "incoming", attackInfo);
    }
}
