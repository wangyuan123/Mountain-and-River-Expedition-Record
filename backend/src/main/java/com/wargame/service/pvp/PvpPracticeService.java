package com.wargame.service.pvp;

import com.wargame.util.GameClock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.wargame.config.GameServerIdentity;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.pvp.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.BattleService;
import com.wargame.service.WebSocketPushService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.function.Supplier;

/**
 * 双人演习状态机。所有修改先按玩家ID升序锁演习状态，再锁票据/会话。
 * 无真实世界写依赖；终局、评分、战报与占用释放在同一事务提交。
 */
@Service
@RequiredArgsConstructor
public class PvpPracticeService {
    public static final long ACCEPT_MS = 15000, LOAD_MS = 20000, COUNTDOWN_MS = 3000, ROUND_MS = 15000, LEASE_MS = 30000, SEARCH_MS = 300000;
    private static final TypeReference<Map<String, BattleService.UnitOrder>> ORDER_TYPE = new TypeReference<>() {};
    private final PvpPlayerStateRepository states;
    private final PvpMatchTicketRepository tickets;
    private final PvpPracticeSessionRepository sessions;
    private final PvpRoundOrderRepository orders;
    private final PvpRoundResultRepository rounds;
    private final PvpPracticeReportRepository reports;
    private final PvpRequestRepository requests;
    private final PlayerRepository players;
    private final GameServerIdentity region;
    private final WebSocketPushService push;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PvpWaitEstimate waitEstimate;
    private final com.wargame.service.compliance.AntiAddictionService protection;

    /** 演习写事务内部再次校验当前账号和游戏许可，越过截止时间时整体回滚。 */
    private void requireRequestAccess() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof com.wargame.model.UserPrincipal user)) return;
        var p = players.findById(user.getPlayerId()).orElseThrow(); em.refresh(p);
        if (!p.accountActive() || p.getAuthVersion() != user.getAuthVersion())
            throw new com.wargame.security.AccountException("ACCOUNT_UNAVAILABLE", "登录状态已失效，请重新登录");
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web ? web.getRequest() : null;
        protection.requireOperationAccess(p, request == null ? null : request.getHeader("X-Play-Session"), request == null ? "" : request.getRequestURI());
    }

    private long now() { return GameClock.now(); }
    private String uuid() { return UUID.randomUUID().toString(); }
    private boolean terminal(PvpPracticeSession s) { return "FINISHED".equals(s.getStatus()) || "VOID".equals(s.getStatus()); }
    private PvpSnapshot snapshot(String text) { return PvpJson.read(text, PvpSnapshot.class); }
    private PvpBattleData data(PvpPracticeSession s) { return PvpJson.read(s.getBattleData(), PvpBattleData.class); }

    private Map<Long, PvpPlayerState> lockPlayers(Collection<Long> ids) {
        Map<Long, PvpPlayerState> out = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(ids)) {
            // 创建竞争由主键和INSERT IGNORE解决，只锁演习状态，绝不占用真实驻军。
            jdbc.update("INSERT IGNORE INTO pvp_player_states(player_id,preset_version,rating,games,wins,losses,draws,confirmation_window,confirmation_exits,cooldown_until,sequence_no,version) VALUES (?,0,1000,0,0,0,0,0,0,0,0,0)", id);
            PvpPlayerState p = states.lock(id).orElseThrow(); em.refresh(p);
            out.put(id, p);
        }
        return out;
    }
    private PvpPracticeSession lockSession(String id, Long participant) {
        PvpPracticeSession s = sessions.findById(id).orElseThrow(() -> new IllegalArgumentException("演习不存在"));
        if (participant != null && !Objects.equals(participant, s.getPlayerA()) && !Objects.equals(participant, s.getPlayerB()))
            throw new IllegalArgumentException("仅参战双方可以访问演习");
        lockPlayers(List.of(s.getPlayerA(), s.getPlayerB()));
        em.refresh(s, LockModeType.PESSIMISTIC_WRITE); return s;
    }
    private PvpMatchTicket lockTicket(String id, Long owner) {
        var t = tickets.findById(id).orElseThrow(() -> new IllegalArgumentException("票据已失效"));
        if (!Objects.equals(t.getPlayerId(), owner)) throw new IllegalArgumentException("票据不属于你");
        em.refresh(t, LockModeType.PESSIMISTIC_WRITE); return t;
    }
    private PvpPlayerState state(Long id) { return states.findById(id).orElseThrow(); }
    private void changed(Long player, String event) {
        var p = state(player); p.setSequence(p.getSequence() + 1);
        push.pushToPlayer(player, event, statusView(p));
    }
    private void changedBoth(PvpPracticeSession s, String event) { changed(s.getPlayerA(), event); changed(s.getPlayerB(), event); }

    /** 请求ID绑定操作和内容；重复写返回原响应，复用同ID改变内容被拒绝。 */
    private Map<String, Object> write(Long player, String requestId, Object body, Supplier<Map<String, Object>> action) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{8,64}")) throw new IllegalArgumentException("requestId无效");
        String hash = PvpJson.hash(PvpJson.write(body));
        var prior = requests.findByPlayerIdAndRequestId(player, requestId);
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getHash())) throw new IllegalArgumentException("requestId不能复用于其他操作");
            return PvpJson.read(prior.get().getResponse(), new TypeReference<>() {});
        }
        requireRequestAccess();
        Map<String, Object> result = action.get();
        requireRequestAccess();
        PvpRequest r = new PvpRequest(); r.setId(uuid()); r.setPlayerId(player); r.setRequestId(requestId);
        r.setHash(hash); r.setResponse(PvpJson.write(result)); r.setCreatedAt(now()); requests.save(r); return result;
    }
    @Transactional(readOnly = true)
    public Map<String, Object> prior(Long player, String requestId, Object body) {
        var r = requests.findByPlayerIdAndRequestId(player, requestId);
        if (r.isEmpty()) return null;
        if (!r.get().getHash().equals(PvpJson.hash(PvpJson.write(body)))) throw new IllegalArgumentException("requestId内容冲突");
        return PvpJson.read(r.get().getResponse(), new TypeReference<>() {});
    }

    @Transactional
    public Map<String, Object> status(Long player) {
        var p = lockPlayers(List.of(player)).get(player);
        return statusView(p);
    }
    private Map<String, Object> statusView(PvpPlayerState p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "IDLE"); out.put("version", p.getSequence()); out.put("serverTime", now());
        out.put("region", region.id()); out.put("cooldownUntil", p.getCooldownUntil());
        if (p.getActiveTicketId() != null) {
            var t = tickets.findById(p.getActiveTicketId()).orElseThrow();
            out.put("status", t.getStatus()); out.put("ticketId", t.getId()); out.put("queuedAt", t.getQueuedAt());
            out.put("searchExpiresAt", t.getQueuedAt() + SEARCH_MS); out.put("leaseUntil", t.getLeaseUntil());
            out.put("snapshot", snapshot(t.getSnapshot())); out.put("accepted", t.isAccepted());
            if ("QUEUED".equals(t.getStatus())) out.put("waitEstimate", waitEstimate.estimate(t.getRegion(), t.getBattlefield(), t.getRulesVersion()));
            if (t.getSessionId() != null) {
                var s = sessions.findById(t.getSessionId()).orElseThrow();
                out.put("sessionId", s.getId()); out.put("deadlineAt", s.getDeadlineAt()); out.put("status", s.getStatus());
            }
        } else if (p.getActiveSessionId() != null) {
            var s = sessions.findById(p.getActiveSessionId()).orElseThrow();
            out.put("status", s.getStatus()); out.put("sessionId", s.getId()); out.put("deadlineAt", s.getDeadlineAt());
            out.put("startsAt", s.getStartsAt()); out.put("round", s.getRoundNo());
        } else if (p.getLastSessionId() != null) {
            out.put("lastSessionId", p.getLastSessionId());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> preset(Long player) {
        var p = lockPlayers(List.of(player)).get(player);
        if (p.getPreset() == null) {
            Player real = players.findById(player).orElseThrow();
            Map<String, Object> outgoing = com.wargame.util.JsonUtil.parseObjMap(real.getOutgoingBattleActions());
            Map<String, BattleService.UnitOrder> defaults = new TreeMap<>();
            UnitDef.UNITS.forEach((id, u) -> {
                String a = Objects.toString(outgoing.get(id), Boolean.FALSE.equals(u.autoAdvance()) ? "HOLD" : "ADVANCE");
                if (!Set.of("ADVANCE", "RETREAT", "HOLD").contains(a)) a = Boolean.FALSE.equals(u.autoAdvance()) ? "HOLD" : "ADVANCE";
                defaults.put(id, new BattleService.UnitOrder(BattleService.CommandAction.valueOf(a)));
            });
            p.setPreset(PvpJson.write(new PvpPreset(0, "LAND_AIR", Map.of(), null, defaults)));
        }
        return Map.of("preset", PvpJson.read(p.getPreset(), PvpPreset.class), "presetVersion", p.getPresetVersion(),
                "record", Map.of("games", p.getGames(), "wins", p.getWins(), "losses", p.getLosses(), "draws", p.getDraws()),
                "status", statusView(p));
    }
    @Transactional
    public Map<String, Object> savePreset(Long player, String requestId, long expected, PvpPreset preset, PvpSnapshot preview, Object body) {
        var p = lockPlayers(List.of(player)).get(player);
        return write(player, requestId, body, () -> {
            if (p.getActiveTicketId() != null || p.getActiveSessionId() != null) throw new IllegalArgumentException("请先结束演习或取消匹配");
            if (expected != p.getPresetVersion()) throw new IllegalArgumentException("预设已在其他设备更新，请刷新");
            validateDefaults(preset.defaults());
            p.setPreset(PvpJson.write(preset)); p.setPresetVersion(p.getPresetVersion() + 1); changed(player, "pvpQueueChanged");
            return Map.of("presetVersion", p.getPresetVersion(), "status", statusView(p), "snapshot", preview);
        });
    }
    public static void validateDefaults(Map<String, BattleService.UnitOrder> preset) {
        if (preset == null || !preset.keySet().equals(UnitDef.UNITS.keySet())) throw new IllegalArgumentException("请设置全部兵种默认战术");
        preset.forEach((id, o) -> { if (o == null || o.action() == null || o.focusTarget() != null && !o.focusTarget().isBlank()) throw new IllegalArgumentException("默认战术不指定尚未匹配的敌方目标"); });
    }

    @Transactional
    public Map<String, Object> enqueue(Long player, String requestId, long expectedPreset, PvpSnapshot snap, Object body) {
        var p = lockPlayers(List.of(player)).get(player);
        return write(player, requestId, body, () -> {
            if (p.getActiveTicketId() != null || p.getActiveSessionId() != null) throw new IllegalArgumentException("账号已有匹配或演习，请返回当前状态");
            if (p.getCooldownUntil() > now()) throw new IllegalArgumentException("确认对局冷却尚未结束");
            if (expectedPreset != p.getPresetVersion()) throw new IllegalArgumentException("预设版本已改变，请刷新");
            if (!PvpSnapshotService.RULES.equals(snap.rulesVersion()) || !PvpPowerModel.VERSION.equals(snap.powerModelVersion())) throw new IllegalArgumentException("演习规则或实力模型已更新");
            PvpMatchTicket t = ticket(player, snap, now(), p);
            var oldPreset = PvpJson.read(p.getPreset(), PvpPreset.class);
            // 快捷开始下次回到最近编成；只更改演习配置，真实迎战编队不受影响。
            p.setPreset(PvpJson.write(new PvpPreset(snap.citySlot(), snap.battlefield(), snap.army(), snap.commanderId(), oldPreset.defaults())));
            p.setActiveTicketId(t.getId()); p.setLastSessionId(null); changed(player, "pvpQueueChanged");
            return statusView(p);
        });
    }
    private PvpMatchTicket ticket(Long player, PvpSnapshot snap, long queuedAt, PvpPlayerState p) {
        PvpMatchTicket t = new PvpMatchTicket(); t.setId(uuid()); t.setPlayerId(player); t.setRegion(region.id());
        t.setBattlefield(snap.battlefield()); t.setSnapshot(PvpJson.write(snap)); t.setSnapshotHash(PvpJson.hash(t.getSnapshot()));
        t.setRulesVersion(snap.rulesVersion()); t.setPower(snap.power()); t.setRating(p.getRating()); t.setPlacement(p.getGames() < 10);
        t.setPowerModelVersion(snap.powerModelVersion());
        t.setQueuedAt(queuedAt); t.setLeaseUntil(now() + LEASE_MS); t.setStatus("QUEUED"); tickets.save(t); return t;
    }
    @Transactional
    public Map<String, Object> heartbeat(Long player, String id) {
        var p = lockPlayers(List.of(player)).get(player);
        if (!Objects.equals(id, p.getActiveTicketId())) throw new IllegalArgumentException("旧票据不能延续新队列");
        var t = lockTicket(id, player);
        if (t.getLeaseUntil() <= now() || t.getQueuedAt() + SEARCH_MS <= now()) return statusView(p);
        t.setLeaseUntil(now() + LEASE_MS); return statusView(p);
    }

    /** 双方窗口取交集；定级期与普通玩家须双方连续等待至少60秒才混排。 */
    public static boolean eligible(PvpMatchTicket a, PvpMatchTicket b, long time) {
        if (a.getPlayerId().equals(b.getPlayerId()) || !Objects.equals(a.getRegion(), b.getRegion())
                || !Objects.equals(a.getBattlefield(), b.getBattlefield()) || !Objects.equals(a.getRulesVersion(), b.getRulesVersion())
                || !Objects.equals(a.getPowerModelVersion(), b.getPowerModelVersion())
                || !"QUEUED".equals(a.getStatus()) || !"QUEUED".equals(b.getStatus()) || a.getLeaseUntil() <= time || b.getLeaseUntil() <= time
                || a.getQueuedAt() + SEARCH_MS <= time || b.getQueuedAt() + SEARCH_MS <= time || a.getPower() <= 0 || b.getPower() <= 0) return false;
        long wait = Math.min(time - a.getQueuedAt(), time - b.getQueuedAt());
        if (a.isPlacement() != b.isPlacement() && wait < 60000) return false;
        double gap = (double) Math.max(a.getPower(), b.getPower()) / Math.min(a.getPower(), b.getPower()) - 1;
        return gap <= powerWindow(wait) + 1e-12 && Math.abs(a.getRating() - b.getRating()) <= ratingWindow(wait);
    }
    public static double powerWindow(long wait) { return wait < 30000 ? .10 : wait < 60000 ? .15 : wait < 120000 ? .20 : .25; }
    public static int ratingWindow(long wait) { return wait < 30000 ? 100 : wait < 60000 ? 150 : wait < 120000 ? 200 : 250; }

    @Transactional
    public boolean pair(String first, String second) {
        var a = tickets.findById(first).orElse(null); var b = tickets.findById(second).orElse(null);
        if (a == null || b == null) return false;
        var ps = lockPlayers(List.of(a.getPlayerId(), b.getPlayerId()));
        a = lockTicket(first, a.getPlayerId()); b = lockTicket(second, b.getPlayerId());
        if (!eligible(a, b, now()) || !Objects.equals(ps.get(a.getPlayerId()).getActiveTicketId(), a.getId())
                || !Objects.equals(ps.get(b.getPlayerId()).getActiveTicketId(), b.getId())) return false;
        if (!players.findById(a.getPlayerId()).orElseThrow().accountActive() || !players.findById(b.getPlayerId()).orElseThrow().accountActive()) return false;
        if (new java.security.SecureRandom().nextBoolean()) { var temp = a; a = b; b = temp; }
        PvpPracticeSession s = new PvpPracticeSession(); s.setId(uuid()); s.setPlayerA(a.getPlayerId()); s.setPlayerB(b.getPlayerId());
        s.setTicketA(a.getId()); s.setTicketB(b.getId()); s.setSnapshotA(a.getSnapshot()); s.setSnapshotB(b.getSnapshot());
        s.setRulesVersion(a.getRulesVersion()); s.setSeed(new java.security.SecureRandom().nextLong()); s.setStatus("ACCEPTING");
        s.setDeadlineAt(now() + ACCEPT_MS); s.setCreatedAt(now()); sessions.save(s);
        for (var t : List.of(a, b)) { t.setStatus("ACCEPTING"); t.setSessionId(s.getId()); }
        changedBoth(s, "pvpMatchFound"); return true;
    }

    @Transactional
    public Map<String, Object> cancel(Long player, String requestId, String ticketId, Object body) {
        var peek = tickets.findById(ticketId).orElseThrow(() -> new IllegalArgumentException("票据不存在"));
        if (!peek.getPlayerId().equals(player)) throw new IllegalArgumentException("票据不属于你");
        PvpPracticeSession s = peek.getSessionId() == null ? null : lockSession(peek.getSessionId(), player);
        if (s == null) lockPlayers(List.of(player));
        return write(player, requestId, body, () -> {
            var p = state(player); var t = lockTicket(ticketId, player);
            if (s != null && !"ACCEPTING".equals(s.getStatus()) && Objects.equals(p.getActiveSessionId(), s.getId())) return statusView(p);
            if (!Objects.equals(p.getActiveTicketId(), ticketId)) throw new IllegalArgumentException("旧票据已失效");
            if (s == null && t.getSessionId() != null) throw new IllegalArgumentException("匹配状态刚刚变化，请查询后操作");
            if (s != null) abortAcceptance(s, player); else { t.setStatus("CANCELLED"); p.setActiveTicketId(null); changed(player, "pvpQueueChanged"); }
            return statusView(p);
        });
    }
    @Transactional
    public Map<String, Object> accept(Long player, String requestId, String ticketId, String sessionId, Object body) {
        var s = lockSession(sessionId, player);
        return write(player, requestId, body, () -> {
            var t = lockTicket(ticketId, player);
            if (!Objects.equals(ticketId, state(player).getActiveTicketId()) || !Objects.equals(sessionId, t.getSessionId())) throw new IllegalArgumentException("确认票据已失效");
            if (!"ACCEPTING".equals(s.getStatus()) || now() >= s.getDeadlineAt() || now() >= t.getLeaseUntil()) throw new IllegalArgumentException("接受对局已截止，请查询当前状态");
            t.setAccepted(true);
            var ta = tickets.findById(s.getTicketA()).orElseThrow(); var tb = tickets.findById(s.getTicketB()).orElseThrow();
            if (ta.isAccepted() && tb.isAccepted()) {
                if (ta.getLeaseUntil() <= now() || tb.getLeaseUntil() <= now()) throw new IllegalArgumentException("匹配租约已过期");
                s.setStatus("LOADING"); s.setDeadlineAt(now() + LOAD_MS); initialize(s);
                for (var pt : List.of(ta, tb)) {
                    pt.setStatus("MATCHED"); var p = state(pt.getPlayerId()); p.setActiveTicketId(null); p.setActiveSessionId(s.getId());
                }
                changedBoth(s, "pvpBattleReady");
            } else changedBoth(s, "pvpAccepted");
            return statusView(state(player));
        });
    }
    private void initialize(PvpPracticeSession s) {
        var a = snapshot(s.getSnapshotA()); var b = snapshot(s.getSnapshotB());
        PvpBattleData d = new PvpBattleData(); d.setArmyA(a.army()); d.setArmyB(b.army());
        d.setDistance(new BattleService(s.getSeed()).calcInitialDistance(a.army(), b.army(), a.context(), b.context()));
        Map<String, Integer> ap = new TreeMap<>(), bp = new TreeMap<>();
        a.army().keySet().forEach(id -> ap.put(id, 0)); b.army().keySet().forEach(id -> bp.put(id, d.getDistance()));
        d.setPositionsA(ap); d.setPositionsB(bp); s.setBattleData(PvpJson.write(d));
    }
    @Transactional
    public Map<String, Object> loaded(Long player, String requestId, String session, Object body) {
        var s = lockSession(session, player);
        return write(player, requestId, body, () -> {
            if (!"LOADING".equals(s.getStatus())) return battleView(s, player);
            if (now() >= s.getDeadlineAt()) throw new IllegalArgumentException("入场已截止");
            if (s.getPlayerA().equals(player)) s.setLoadedA(true); else s.setLoadedB(true);
            if (s.isLoadedA() && s.isLoadedB()) {
                s.setStatus("COUNTDOWN"); s.setStartsAt(now() + COUNTDOWN_MS); s.setDeadlineAt(s.getStartsAt());
            }
            changedBoth(s, "pvpBattleReady"); return battleView(s, player);
        });
    }

    private void confirmationExit(PvpPlayerState p) {
        if (now() - p.getConfirmationWindow() >= 600000) { p.setConfirmationWindow(now()); p.setConfirmationExits(0); }
        p.setConfirmationExits(p.getConfirmationExits() + 1);
        if (p.getConfirmationExits() >= 3) { p.setCooldownUntil(now() + 60000); p.setConfirmationExits(0); }
    }
    private void abortAcceptance(PvpPracticeSession s, Long declining) {
        s.setStatus("VOID"); s.setResult("VOID"); s.setReason("MATCH_NOT_ACCEPTED"); s.setSettledAt(now());
        for (String id : List.of(s.getTicketA(), s.getTicketB())) {
            var t = tickets.findById(id).orElseThrow(); var p = state(t.getPlayerId());
            t.setStatus("CLOSED"); p.setActiveTicketId(null);
            if (!t.getPlayerId().equals(declining) && t.isAccepted() && t.getLeaseUntil() > now() && t.getQueuedAt() + SEARCH_MS > now()) {
                var replacement = ticket(t.getPlayerId(), snapshot(t.getSnapshot()), t.getQueuedAt(), p);
                p.setActiveTicketId(replacement.getId());
            // 对手提前退出并非本人超时，不能给仍在确认窗口内的另一人记一次退出。
            } else if (t.getPlayerId().equals(declining) || declining == null && !t.isAccepted()) confirmationExit(p);
        }
        changedBoth(s, "pvpQueueChanged");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> battle(Long player, String id) {
        var s = sessions.findById(id).orElseThrow(() -> new IllegalArgumentException("演习不存在"));
        if (!s.getPlayerA().equals(player) && !s.getPlayerB().equals(player)) throw new IllegalArgumentException("仅参战者可以查看演习");
        if ("ACCEPTING".equals(s.getStatus())) throw new IllegalArgumentException("双方接受后才能查看对手阵容");
        return battleView(s, player);
    }
    private Map<String, Object> battleView(PvpPracticeSession s, Long player) {
        if (s.getBattleData() == null) return Map.of("status", s.getStatus(), "sessionId", s.getId(), "serverTime", now(), "version", state(player).getSequence());
        boolean isA = s.getPlayerA().equals(player); var d = data(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.getStatus()); out.put("version", state(player).getSequence()); out.put("serverTime", now()); out.put("region", region.id());
        out.put("sessionId", s.getId()); out.put("side", isA ? "A" : "B"); out.put("round", s.getRoundNo()); out.put("maxRound", 24);
        out.put("deadlineAt", s.getDeadlineAt()); out.put("startsAt", s.getStartsAt()); out.put("initialDistance", d.getDistance());
        out.put("mine", snapshot(isA ? s.getSnapshotA() : s.getSnapshotB()));
        Map<String, Object> enemy = new LinkedHashMap<>(PvpJson.read(isA ? s.getSnapshotB() : s.getSnapshotA(), new TypeReference<Map<String, Object>>() {}));
        // 对手默认战术也是未结算指令，开战面板只公开参战属性。
        if (!terminal(s)) enemy.remove("defaults");
        out.put("enemy", enemy);
        out.put("myArmy", isA ? d.getArmyA() : d.getArmyB()); out.put("enemyArmy", isA ? d.getArmyB() : d.getArmyA());
        out.put("myPositions", mirror(isA ? d.getPositionsA() : d.getPositionsB(), !isA, d.getDistance()));
        out.put("enemyPositions", mirror(isA ? d.getPositionsB() : d.getPositionsA(), !isA, d.getDistance()));
        out.put("firstCombatRound", d.getFirstCombatRound()); out.put("auto", isA ? d.isAutoA() : d.isAutoB());
        out.put("pendingTakeover", isA ? d.isPendingTakeoverA() : d.isPendingTakeoverB());
        out.put("loaded", isA ? s.isLoadedA() : s.isLoadedB()); out.put("enemyLoaded", isA ? s.isLoadedB() : s.isLoadedA());
        var mine = orders.findBySessionIdAndRoundNoAndPlayerId(s.getId(), s.getRoundNo(), player);
        var foe = orders.findBySessionIdAndRoundNoAndPlayerId(s.getId(), s.getRoundNo(), isA ? s.getPlayerB() : s.getPlayerA());
        out.put("draft", mine.map(o -> readOrders(o.getOrders())).orElse(Map.of())); out.put("revision", mine.map(PvpRoundOrder::getRevision).orElse(0L));
        out.put("locked", mine.map(PvpRoundOrder::isLocked).orElse(false)); out.put("enemyReady", foe.map(PvpRoundOrder::isLocked).orElse(false));
        out.put("myLoss", isA ? d.getLossA() : d.getLossB()); out.put("enemyLoss", isA ? d.getLossB() : d.getLossA());
        if (terminal(s)) { out.put("result", personalResult(s, player)); out.put("reason", Objects.toString(s.getReason(), "")); }
        return out;
    }
    private Map<String, Integer> mirror(Map<String, Integer> positions, boolean mirror, int distance) {
        Map<String, Integer> out = new TreeMap<>(); positions.forEach((id, x) -> out.put(id, mirror ? distance - x : x)); return out;
    }
    private Map<String, BattleService.UnitOrder> readOrders(String json) { return json == null ? Map.of() : PvpJson.read(json, ORDER_TYPE); }
    private PvpRoundOrder order(PvpPracticeSession s, Long player) {
        return orders.findBySessionIdAndRoundNoAndPlayerId(s.getId(), s.getRoundNo(), player).orElseGet(() -> {
            PvpRoundOrder o = new PvpRoundOrder(); o.setId(uuid()); o.setSessionId(s.getId()); o.setRoundNo(s.getRoundNo()); o.setPlayerId(player); o.setOrders("{}"); o.setSource("DEFAULT"); return orders.save(o);
        });
    }
    private void commandable(PvpPracticeSession s, int expected) {
        if (!"BATTLING".equals(s.getStatus()) || s.getRoundNo() != expected || now() >= s.getDeadlineAt())
            throw new IllegalArgumentException("回合已变更或截止，请重新同步");
    }
    @Transactional
    public Map<String, Object> submit(Long player, String requestId, String session, int round, long revision,
            Map<String, BattleService.UnitOrder> submitted, boolean lock, Object body) {
        var s = lockSession(session, player);
        return write(player, requestId, body, () -> {
            commandable(s, round); var o = order(s, player); var d = data(s);
            if (o.isLocked() || (s.getPlayerA().equals(player) ? d.isAutoA() : d.isAutoB())) throw new IllegalArgumentException("本回合已锁定");
            if (revision <= o.getRevision()) throw new IllegalArgumentException("旧草稿不能覆盖新版本");
            if (submitted == null) throw new IllegalArgumentException("指令不能为空");
            Map<String, Integer> own = s.getPlayerA().equals(player) ? d.getArmyA() : d.getArmyB();
            Map<String, Integer> foe = s.getPlayerA().equals(player) ? d.getArmyB() : d.getArmyA();
            submitted.forEach((id, v) -> {
                if (!own.containsKey(id) || own.get(id) <= 0 || v == null || v.action() == null
                        || v.focusTarget() != null && !v.focusTarget().isBlank() && !foe.containsKey(v.focusTarget())) throw new IllegalArgumentException("非法兵种或集火目标");
            });
            o.setOrders(PvpJson.write(new TreeMap<>(submitted))); o.setRevision(revision); o.setLocked(lock); o.setRequestId(requestId);
            o.setSource(lock ? "MANUAL" : "DRAFT"); if (lock) o.setLockedAt(now());
            if (order(s, s.getPlayerA()).isLocked() && order(s, s.getPlayerB()).isLocked()) resolve(s, now(), false);
            else changedBoth(s, "pvpAccepted");
            return battleView(s, player);
        });
    }
    @Transactional
    public Map<String, Object> auto(Long player, String requestId, String session, int expected, boolean enabled, Object body) {
        var s = lockSession(session, player);
        return write(player, requestId, body, () -> {
            commandable(s, expected); boolean a = s.getPlayerA().equals(player); var d = data(s); var o = order(s, player);
            if (enabled) {
                if (!o.isLocked()) { o.setOrders("{}"); o.setRevision(o.getRevision() + 1); o.setLocked(true); o.setSource("AUTO"); o.setLockedAt(now()); }
                if (a) { d.setAutoA(true); d.setPendingTakeoverA(false); } else { d.setAutoB(true); d.setPendingTakeoverB(false); }
            } else {
                // 接管只能从下一可修改回合生效；不能撤销已锁定命令。
                if (a) d.setPendingTakeoverA(true); else d.setPendingTakeoverB(true);
            }
            s.setBattleData(PvpJson.write(d));
            if (order(s, s.getPlayerA()).isLocked() && order(s, s.getPlayerB()).isLocked()) resolve(s, now(), false);
            else changedBoth(s, "pvpAccepted"); return battleView(s, player);
        });
    }
    @Transactional
    public Map<String, Object> surrender(Long player, String requestId, String session, Object body) {
        var s = lockSession(session, player);
        return write(player, requestId, body, () -> {
            if (!terminal(s)) {
                if ("ACCEPTING".equals(s.getStatus())) throw new IllegalArgumentException("确认阶段请取消匹配");
                finish(s, s.getPlayerA().equals(player) ? "B_WIN" : "A_WIN", "SURRENDER");
            }
            return battleView(s, player);
        });
    }

    /** 子种子仅依赖场次和回合；事务重试不会消费其他会话随机数。 */
    public static long roundSeed(long base, int round) {
        long x = base + 0x9e3779b97f4a7c15L * round;
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L; x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL; return x ^ (x >>> 31);
    }
    private void beginRound(PvpPracticeSession s, long deadline) {
        var d = data(s);
        if (d.isPendingTakeoverA()) { d.setAutoA(false); d.setPendingTakeoverA(false); }
        if (d.isPendingTakeoverB()) { d.setAutoB(false); d.setPendingTakeoverB(false); }
        s.setBattleData(PvpJson.write(d)); s.setStatus("BATTLING"); s.setDeadlineAt(deadline);
        for (Long p : List.of(s.getPlayerA(), s.getPlayerB())) {
            if (p.equals(s.getPlayerA()) ? d.isAutoA() : d.isAutoB()) { var o = order(s, p); o.setLocked(true); o.setLockedAt(now()); o.setSource("AUTO"); }
        }
    }
    private Map<String, BattleService.UnitOrder> actual(PvpSnapshot snap, PvpRoundOrder o, Map<String, Integer> live) {
        Map<String, BattleService.UnitOrder> result = new TreeMap<>();
        for (String id : live.keySet()) if (live.get(id) > 0) result.put(id, snap.defaults().getOrDefault(id,
                new BattleService.UnitOrder(Boolean.FALSE.equals(UnitDef.UNITS.get(id).autoAdvance()) ? BattleService.CommandAction.HOLD : BattleService.CommandAction.ADVANCE)));
        result.putAll(readOrders(o.getOrders())); return result;
    }
    private Map<String, String> sources(PvpRoundOrder o, Map<String, BattleService.UnitOrder> actual) {
        Map<String, String> out = new TreeMap<>(); var draft = readOrders(o.getOrders());
        actual.forEach((id, v) -> out.put(id, draft.containsKey(id) ? o.getSource() : "AUTO".equals(o.getSource()) ? "AUTO" : "DEFAULT")); return out;
    }
    private void resolve(PvpPracticeSession s, long time, boolean overdue) {
        if (terminal(s) || rounds.existsBySessionIdAndRoundNo(s.getId(), s.getRoundNo())) return;
        var d = data(s); var a = snapshot(s.getSnapshotA()); var b = snapshot(s.getSnapshotB());
        var ao = order(s, s.getPlayerA()); var bo = order(s, s.getPlayerB());
        ao.setLocked(true); bo.setLocked(true); if (ao.getLockedAt() == 0) ao.setLockedAt(s.getDeadlineAt()); if (bo.getLockedAt() == 0) bo.setLockedAt(s.getDeadlineAt());
        var aa = actual(a, ao, d.getArmyA()); var ba = actual(b, bo, d.getArmyB());
        long seed = roundSeed(s.getSeed(), s.getRoundNo()); BattleService engine = new BattleService(seed);
        String before = PvpJson.write(d);
        var r = engine.resolvePracticeRound(d.getArmyA(), d.getArmyB(), d.getPositionsA(), d.getPositionsB(), d.getDistance(), a.context(), b.context(), s.getRoundNo(), d.getFirstCombatRound(), aa, ba);
        d.setArmyA(r.attackerArmy()); d.setArmyB(r.defenderArmy()); d.setPositionsA(r.attackerPositions()); d.setPositionsB(r.defenderPositions()); d.setFirstCombatRound(r.firstCombatRound());
        d.setLossA(frozenLoss(a, d.getArmyA())); d.setLossB(frozenLoss(b, d.getArmyB())); s.setBattleData(PvpJson.write(d));
        PvpRoundResult record = new PvpRoundResult(); record.setId(uuid()); record.setSessionId(s.getId()); record.setRoundNo(s.getRoundNo()); record.setSeed(seed);
        record.setData(PvpJson.write(Map.ofEntries(Map.entry("round", s.getRoundNo()), Map.entry("seed", seed),
                Map.entry("before", PvpJson.read(before, PvpBattleData.class)), Map.entry("after", d), Map.entry("ordersA", aa), Map.entry("ordersB", ba),
                Map.entry("sourcesA", sources(ao, aa)), Map.entry("sourcesB", sources(bo, ba)), Map.entry("events", engine.practiceEvents()),
                Map.entry("log", r.log()), Map.entry("resolvedAt", now())))); rounds.save(record);
        if (r.finished()) finish(s, result(d, s.getRoundNo()), "COMBAT");
        else {
            long previousDeadline = s.getDeadlineAt(); s.setRoundNo(s.getRoundNo() + 1);
            beginRound(s, (overdue ? previousDeadline : time) + ROUND_MS); changedBoth(s, "pvpRoundResolved");
        }
    }
    private static double frozenLoss(PvpSnapshot snap, Map<String, Integer> live) {
        double initial = 0, lost = 0;
        for (var e : snap.army().entrySet()) { double v = snap.baseValues().get(e.getKey()); initial += e.getValue() * v; lost += Math.max(0, e.getValue() - live.getOrDefault(e.getKey(), 0)) * v; }
        return initial == 0 ? 0 : Math.max(0, Math.min(1, lost / initial));
    }
    public static String result(PvpBattleData d, int round) {
        boolean a = d.getArmyA().values().stream().noneMatch(n -> n > 0), b = d.getArmyB().values().stream().noneMatch(n -> n > 0);
        if (a && b) return "DRAW"; if (a) return "B_WIN"; if (b) return "A_WIN";
        if (round < 24) throw new IllegalArgumentException("尚未终局");
        return Math.abs(d.getLossA() - d.getLossB()) <= .05 + 1e-12 ? "DRAW" : d.getLossB() > d.getLossA() ? "A_WIN" : "B_WIN";
    }
    private String personalResult(PvpPracticeSession s, Long p) {
        if ("VOID".equals(s.getResult()) || "DRAW".equals(s.getResult())) return s.getResult();
        return (s.getPlayerA().equals(p) == "A_WIN".equals(s.getResult())) ? "WIN" : "LOSS";
    }
    private List<Long> recent(PvpPlayerState p) {
        return p.getRecentOpponents() == null ? new ArrayList<>() : new ArrayList<>(PvpJson.read(p.getRecentOpponents(), new TypeReference<List<Long>>() {}));
    }
    private boolean farming(PvpPlayerState p, Long foe) { var r = recent(p); return r.size() >= 5 && r.subList(0, 5).stream().allMatch(foe::equals); }
    private void remember(PvpPlayerState p, Long foe) { var r = recent(p); r.add(0, foe); p.setRecentOpponents(PvpJson.write(r.subList(0, Math.min(5, r.size())))); }
    private void finish(PvpPracticeSession s, String result, String reason) {
        if (s.getSettledAt() != 0) return;
        s.setSettledAt(now()); s.setResult(result); s.setReason(reason); s.setStatus("VOID".equals(result) ? "VOID" : "FINISHED");
        var a = state(s.getPlayerA()); var b = state(s.getPlayerB());
        boolean rated = !"VOID".equals(result) && !farming(a, b.getPlayerId()) && !farming(b, a.getPlayerId());
        int oldA = a.getRating(), oldB = b.getRating();
        if (rated) {
            double expected = 1 / (1 + Math.pow(10, (oldB - oldA) / 400.0));
            double score = "DRAW".equals(result) ? .5 : "A_WIN".equals(result) ? 1 : 0;
            a.setRating(Math.max(0, oldA + (int) Math.round((a.getGames() < 10 ? 40 : 24) * (score - expected))));
            b.setRating(Math.max(0, oldB + (int) Math.round((b.getGames() < 10 ? 40 : 24) * ((1 - score) - (1 - expected)))));
        }
        if (!"VOID".equals(result)) {
            for (var p : List.of(a, b)) {
                p.setGames(p.getGames() + 1); String pr = personalResult(s, p.getPlayerId());
                if ("WIN".equals(pr)) p.setWins(p.getWins() + 1); else if ("LOSS".equals(pr)) p.setLosses(p.getLosses() + 1); else p.setDraws(p.getDraws() + 1);
            }
            remember(a, b.getPlayerId()); remember(b, a.getPlayerId());
        }
        for (var p : List.of(a, b)) {
            p.setActiveSessionId(null); p.setActiveTicketId(null); p.setLastSessionId(s.getId());
            PvpPracticeReport report = new PvpPracticeReport(); report.setId(uuid()); report.setSessionId(s.getId()); report.setPlayerId(p.getPlayerId());
            report.setCreatedAt(s.getSettledAt()); report.setResult(personalResult(s, p.getPlayerId()));
            Map<String, Object> payload;
            try { payload = new LinkedHashMap<>(battleView(s, p.getPlayerId())); }
            catch (IllegalStateException corrupt) {
                if (!"VOID".equals(result)) throw corrupt;
                payload = new LinkedHashMap<>(Map.of("sessionId", s.getId(), "status", "VOID", "result", "VOID", "reason", reason));
            }
            payload.put("mode", "PVP_PRACTICE"); payload.put("actualLoss", 0); payload.put("rated", rated); payload.put("rulesVersion", s.getRulesVersion());
            payload.put("seed", s.getSeed()); payload.put("createdAt", s.getCreatedAt()); payload.put("settledAt", s.getSettledAt());
            report.setData(PvpJson.write(payload)); reports.save(report);
            var old = reports.findByPlayerIdOrderByCreatedAtDesc(p.getPlayerId(), PageRequest.of(1, 50)); reports.deleteAll(old);
        }
        changedBoth(s, "pvpFinished");
    }

    /** 持续按原截止时间轴恢复，每次最多4回合；恢复期间拒绝新草稿。 */
    @Transactional
    public void advance(String id) {
        var s = lockSession(id, null); long time = now(); if (terminal(s)) return;
        if (!players.findById(s.getPlayerA()).map(Player::accountActive).orElse(false)
                || !players.findById(s.getPlayerB()).map(Player::accountActive).orElse(false)) { finish(s, "VOID", "ACCOUNT_UNAVAILABLE"); return; }
        if (!PvpSnapshotService.RULES.equals(s.getRulesVersion())) { finish(s, "VOID", "RULES_INCOMPATIBLE"); return; }
        if ("ACCEPTING".equals(s.getStatus())) {
            var a = tickets.findById(s.getTicketA()).orElseThrow(); var b = tickets.findById(s.getTicketB()).orElseThrow();
            if (time >= s.getDeadlineAt() || a.getLeaseUntil() <= time || b.getLeaseUntil() <= time) abortAcceptance(s, null); return;
        }
        if ("LOADING".equals(s.getStatus())) { if (time >= s.getDeadlineAt()) finish(s, "VOID", "LOAD_TIMEOUT"); return; }
        if (time - s.getDeadlineAt() > 3600000) { finish(s, "VOID", "OUTAGE_TOO_LONG"); return; }
        if ("COUNTDOWN".equals(s.getStatus()) && time >= s.getStartsAt()) { s.setRoundNo(1); beginRound(s, s.getStartsAt() + ROUND_MS); changedBoth(s, "pvpBattleReady"); }
        if ("RECOVERING".equals(s.getStatus())) s.setStatus("BATTLING");
        if ("BATTLING".equals(s.getStatus()) && time < s.getDeadlineAt()
                && order(s, s.getPlayerA()).isLocked() && order(s, s.getPlayerB()).isLocked()) { resolve(s, time, false); return; }
        for (int batch = 0; batch < 4 && !terminal(s) && "BATTLING".equals(s.getStatus()) && time >= s.getDeadlineAt(); batch++) resolve(s, time, true);
        if (!terminal(s) && "BATTLING".equals(s.getStatus()) && time >= s.getDeadlineAt()) { s.setStatus("RECOVERING"); changedBoth(s, "pvpRoundResolved"); }
    }
    @Transactional
    public void expireTicket(String id) {
        var t = tickets.findById(id).orElse(null); if (t == null) return;
        lockPlayers(List.of(t.getPlayerId())); t = lockTicket(id, t.getPlayerId());
        if ("QUEUED".equals(t.getStatus()) && (t.getLeaseUntil() <= now() || t.getQueuedAt() + SEARCH_MS <= now())) {
            t.setStatus("EXPIRED"); var p = state(t.getPlayerId()); if (Objects.equals(p.getActiveTicketId(), id)) p.setActiveTicketId(null); changed(p.getPlayerId(), "pvpQueueChanged");
        }
    }
    @Transactional
    public void voidSession(String id) { var s = lockSession(id, null); if (!terminal(s)) finish(s, "VOID", "ENGINE_ERROR"); }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Long player, int page) {
        if (page < 0 || page > 10) throw new IllegalArgumentException("页码无效");
        return reports.findByPlayerIdOrderByCreatedAtDesc(player, PageRequest.of(page, 10)).stream()
                .map(r -> Map.<String, Object>of("sessionId", r.getSessionId(), "result", r.getResult(), "createdAt", r.getCreatedAt())).toList();
    }
    @Transactional(readOnly = true)
    public Map<String, Object> report(Long player, String id) {
        var report = reports.findBySessionIdAndPlayerId(id, player).orElseThrow(() -> new IllegalArgumentException("战报不存在或无权访问"));
        Map<String, Object> out = new LinkedHashMap<>(PvpJson.read(report.getData(), new TypeReference<Map<String, Object>>() {}));
        out.put("rounds", rounds.findBySessionIdOrderByRoundNoAsc(id).stream().map(r -> PvpJson.read(r.getData(), new TypeReference<Map<String, Object>>() {})).toList());
        return out;
    }
    public Map<Long, Integer> repeatPenalties(Long player) {
        Map<Long, Integer> penalties = new HashMap<>();
        states.findById(player).ifPresent(p -> recent(p).forEach(foe -> penalties.merge(foe, 1, Integer::sum)));
        return penalties;
    }
}
