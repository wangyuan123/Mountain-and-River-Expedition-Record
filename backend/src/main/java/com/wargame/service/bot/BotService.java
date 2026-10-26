package com.wargame.service.bot;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.service.admin.AdminAuditLogService;
import com.wargame.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BotService {
    private final BotStore store;
    private final BotPlanner planner;
    private final AccountService accounts;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final WildTileRepository wilds;
    private final ResourcesRepository resources;
    private final CityScope scope;
    private final BuildService build;
    private final TechService tech;
    private final ArmyService army;
    private final MarchService marches;
    private final WorldService world;
    private final MarketService market;
    private final AdminAuditLogService audit;
    private final DepotService depot;
    private final GameStateService gameState;
    @PersistenceContext private EntityManager em;
    @Value("${game.bot.enabled:true}") private boolean globallyEnabled = true;

    public record Result(boolean success, String action, String reason, Map<String, Object> detail) {}

    @Transactional
    public BotStore.Profile configure(long playerId, BotStore.Settings settings, boolean convertToBot,
                                      String administrator, HttpServletRequest request) {
        Player player = accounts.lockPlayer(playerId);
        if (!Boolean.TRUE.equals(player.getIsBot()) && !convertToBot) throw new IllegalArgumentException("真实账号须显式确认 convertToBot 后才可交给 AI");
        if (!player.accountActive()) throw new IllegalArgumentException("只能配置有效且未封禁的账号");
        if (!player.isGameInitialized()) {
            if (!convertToBot) throw new IllegalArgumentException("账号尚未完成游戏初始化，须显式转换为 Bot");
            gameState.initializeNewPlayer(playerId);
        }
        PlayerCity city = cities.findByOwnerIdAndCitySlot(playerId, settings.citySlot()).orElseThrow(() -> new IllegalArgumentException("Bot 城市不存在"));
        if (city.getReadyAt() > GameClock.now() || city.isSurrenderFrozen(GameClock.now())) throw new IllegalArgumentException("该城市尚不可操作");
        if (convertToBot) { player.setIsBot(true); players.save(player); }
        store.configure(playerId, settings, GameClock.now());
        audit.record(administrator, "BOT_CONFIGURE", "PLAYER", Long.toString(playerId), JsonUtil.toJson(settings), request);
        return store.find(playerId, false).orElseThrow();
    }

    /** 玩家锁和 Bot 配置锁覆盖整个动作；并发调度必须锁后复核到期时间。 */
    @Transactional
    public Result run(long playerId, boolean force) {
        if (!globallyEnabled) return idle("全局 AI 开关已关闭");
        Player player = accounts.lockPlayer(playerId);
        BotStore.Profile profile = store.find(playerId, true).orElseThrow(() -> new IllegalArgumentException("未配置 Bot"));
        long now = GameClock.now();
        if (!profile.settings().enabled() || (!force && profile.nextActionAt() > now)) return idle("Bot 已暂停或尚未到执行时间");
        if (!Boolean.TRUE.equals(player.getIsBot()) || !player.accountActive() || !player.isGameInitialized()) {
            store.configure(playerId, new BotStore.Settings(false, profile.settings().pvpEnabled(), profile.settings().citySlot(),
                    profile.settings().intervalSeconds(), profile.settings().reservePercent(), profile.settings().maxMarches(), profile.settings().armyTarget()), now);
            return idle("账号不可用，已暂停 AI");
        }
        PlayerCity city = cities.findByOwnerIdAndCitySlot(playerId, profile.settings().citySlot()).orElse(null);
        if (city == null || city.getReadyAt() > now || city.isSurrenderFrozen(now)) return recordWait(profile, now, "城市尚未就绪或归降冻结中");
        try (var ignored = scope.enter(city)) {
            Resources wallet = resources.findByPlayerIdAndCitySlot(playerId, profile.settings().citySlot()).orElseThrow(() -> new IllegalArgumentException("缺少城市资源账户"));
            BotAction action = planner.plan(profile, player, city, wallet, now);
            Map<String, Object> outcome;
            try {
                outcome = execute(playerId, action);
            } catch (RuntimeException failure) {
                throw new ActionFailure(action, failure);
            }
            boolean success = !Boolean.FALSE.equals(outcome.get("success"));
            String reason = success ? action.reason() : String.valueOf(outcome.getOrDefault("message", "业务校验未通过"));
            store.complete(profile, action, success, reason, JsonUtil.toJson(Map.of("decision", action, "result", outcome)), now);
            return new Result(success, action.type(), reason, outcome);
        }
    }

    private Map<String, Object> execute(long playerId, BotAction action) {
        if ("player".equals(action.subject()) && action.targetId() != null) {
            PlayerCity target = cities.findById(action.targetId()).orElseThrow(() -> new IllegalArgumentException("目标城市已不存在"));
            Long ownerId = target.getOwnerId();
            if (ownerId == null) throw new IllegalArgumentException("目标城市已没有玩家城主");
            Player owner = accounts.lockPlayer(ownerId);
            em.refresh(target, LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(ownerId, target.getOwnerId()) || !Boolean.TRUE.equals(owner.getIsBot()) || !owner.accountActive()) {
                throw new IllegalArgumentException("AI 仅可向 Bot 城市侦查、宣战或进攻");
            }
        }
        if ("wild".equals(action.subject()) && Set.of("SCOUT", "ATTACK").contains(action.type())) {
            WildTile target = wilds.findById(action.targetId()).orElseThrow(() -> new IllegalArgumentException("目标野地已不存在"));
            em.refresh(target, LockModeType.PESSIMISTIC_WRITE);
            if (Boolean.TRUE.equals(target.getOccupied())) throw new IllegalArgumentException("目标野地已被占领，等待重新选择目标");
        }
        return switch (action.type()) {
            case "BUILD" -> build.upgrade(playerId, action.subject(), action.amount());
            case "USE_SUPPLY" -> depot.useItem(playerId, action.subject(), null, null);
            case "RESEARCH" -> tech.startResearch(playerId, action.subject());
            case "RECRUIT" -> army.recruit(playerId, action.subject(), action.amount());
            case "EXCHANGE" -> market.systemExchange(playerId, scope.slot(playerId), action.subject(), "food", action.amount());
            case "GATHER" -> marches.startWildGather(playerId, action.targetId(), "manual");
            case "HARVEST" -> marches.harvestWild(playerId, action.targetId());
            case "RECALL_WILD" -> marches.recallWild(playerId, action.targetId());
            case "ABANDON_WILD" -> world.abandonWild(playerId, action.targetId());
            case "RECALL_MARCH" -> marches.cancelMarch(playerId, action.targetId());
            case "STOP_GATHER" -> { marches.stopGatherMarch(playerId, action.targetId()); yield Map.of("success", true); }
            case "SCOUT" -> marchResult(marches.createDispatch(playerId, new DispatchRequest(action.subject(), action.targetId(),
                    "scout", action.army(), null, null, null, null, null, null, true)));
            case "STATION" -> marchResult(marches.createDispatch(playerId, new DispatchRequest("wild", action.targetId(), "station", action.army(), null, null)));
            case "DECLARE_WAR" -> world.declareWar(playerId, action.targetId(), true);
            case "ATTACK" -> marchResult(marches.createDispatch(playerId, new DispatchRequest(action.subject(), action.targetId(),
                    "player".equals(action.subject()) ? "plunder" : "conquer", action.army(), null, null, null,
                    "player".equals(action.subject()) ? null : "auto", "wild".equals(action.subject()) ? "occupy" : "withdraw", null, true)));
            case "WAIT" -> Map.of("success", true);
            default -> throw new IllegalArgumentException("未知 Bot 动作: " + action.type());
        };
    }

    private Map<String, Object> marchResult(March march) { return Map.of("success", true, "marchId", march.getId(), "arriveAt", march.getArriveAt()); }
    private Result idle(String reason) { return new Result(true, "WAIT", reason, Map.of()); }
    private Result recordWait(BotStore.Profile profile, long now, String reason) {
        store.complete(profile, BotAction.simple("WAIT", "", reason), true, reason, "{}", now);
        return idle(reason);
    }

    /** 失败动作先整体回滚，再单独记录退避，避免保留部分扣款或每秒重试。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result failed(long playerId, RuntimeException failure) {
        accounts.lockPlayer(playerId);
        var found = store.find(playerId, true);
        if (found.isEmpty() || !found.get().settings().enabled()) return idle("Bot 已暂停");
        BotAction action = failure instanceof ActionFailure wrapped ? wrapped.action : BotAction.simple("ERROR", "", "决策失败");
        Throwable cause = failure instanceof ActionFailure ? failure.getCause() : failure;
        String reason = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        store.complete(found.get(), action, false, reason, JsonUtil.toJson(action), GameClock.now());
        return new Result(false, action.type(), reason, Map.of());
    }

    private static class ActionFailure extends RuntimeException {
        private final BotAction action;
        ActionFailure(BotAction action, RuntimeException cause) { super(cause); this.action = action; }
    }
}
