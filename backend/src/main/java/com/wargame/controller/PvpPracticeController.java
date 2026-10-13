package com.wargame.controller;

import com.wargame.model.dto.pvp.*;
import com.wargame.service.AuthService;
import com.wargame.service.BattleService.UnitOrder;
import com.wargame.service.pvp.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 身份始终取登录态；不接收自己/对手的玩家ID，不直接序列化JPA会话。 */
@RestController
@RequestMapping("/api/game/pvp")
@RequiredArgsConstructor
public class PvpPracticeController {
    private final AuthService auth;
    private final PvpPracticeService practice;
    private final PvpSnapshotService snapshots;
    private final PvpPowerModel powers;
    public record QueueRequest(String requestId, int citySlot, String battlefield, Map<String, Object> army, Long commanderId, long presetVersion) {}
    public record PresetRequest(String requestId, int citySlot, String battlefield, Map<String, Object> army, Long commanderId, Map<String, UnitOrder> defaults, long presetVersion) {}
    public record TicketRequest(String requestId, String ticketId, String sessionId) {}
    public record SessionRequest(String requestId, String sessionId) {}
    public record OrdersRequest(String requestId, String sessionId, int round, long revision, Map<String, UnitOrder> orders, boolean lock) {}
    public record AutoRequest(String requestId, String sessionId, int expectedRound, boolean enabled) {}
    private Long me() { return auth.getCurrentPlayer().getId(); }
    private Object body(String op, Object request) { return Map.of("op", op, "request", request); }

    @GetMapping("/lobby")
    public Map<String, Object> lobby(@RequestParam(required = false) Integer citySlot) {
        Long player = me(); var preset = practice.preset(player);
        int slot = citySlot == null ? ((PvpPreset) preset.get("preset")).citySlot() : citySlot;
        Map<String, Object> out = new LinkedHashMap<>(preset); out.put("available", snapshots.available(player, slot));
        out.put("reports", practice.history(player, 0)); out.put("serverTime", System.currentTimeMillis()); out.put("rulesVersion", PvpSnapshotService.RULES); return out;
    }
    @PostMapping("/preset")
    public Map<String, Object> preset(@RequestBody PresetRequest req) {
        Long player = me(); var body = body("preset", req); var previous = practice.prior(player, req.requestId(), body); if (previous != null) return previous;
        PvpPracticeService.validateDefaults(req.defaults());
        var p = new PvpPreset(req.citySlot(), req.battlefield(), PvpSnapshotService.quantities(req.army()), req.commanderId(), req.defaults());
        var snapshot = powers.estimate(snapshots.freeze(player, p));
        return practice.savePreset(player, req.requestId(), req.presetVersion(), p, snapshot, body);
    }
    @PostMapping("/queue")
    public Map<String, Object> queue(@RequestBody QueueRequest req) {
        Long player = me(); var body = body("queue", req); var previous = practice.prior(player, req.requestId(), body); if (previous != null) return previous;
        var settings = practice.preset(player); var defaults = ((PvpPreset) settings.get("preset")).defaults();
        var p = new PvpPreset(req.citySlot(), req.battlefield(), PvpSnapshotService.quantities(req.army()), req.commanderId(), defaults);
        var snapshot = powers.estimate(snapshots.freeze(player, p));
        return practice.enqueue(player, req.requestId(), req.presetVersion(), snapshot, body);
    }
    @GetMapping("/status") public Map<String, Object> status() { return practice.status(me()); }
    @PostMapping("/queue/heartbeat") public Map<String, Object> heartbeat(@RequestBody TicketRequest req) { return practice.heartbeat(me(), req.ticketId()); }
    @PostMapping("/queue/cancel") public Map<String, Object> cancel(@RequestBody TicketRequest req) { return practice.cancel(me(), req.requestId(), req.ticketId(), body("cancel", req)); }
    @PostMapping("/accept") public Map<String, Object> accept(@RequestBody TicketRequest req) { return practice.accept(me(), req.requestId(), req.ticketId(), req.sessionId(), body("accept", req)); }
    @PostMapping("/loaded") public Map<String, Object> loaded(@RequestBody SessionRequest req) { return practice.loaded(me(), req.requestId(), req.sessionId(), body("loaded", req)); }
    @GetMapping("/battle") public Map<String, Object> battle(@RequestParam String sessionId) { return practice.battle(me(), sessionId); }
    @PostMapping("/orders") public Map<String, Object> orders(@RequestBody OrdersRequest req) { return practice.submit(me(), req.requestId(), req.sessionId(), req.round(), req.revision(), req.orders(), req.lock(), body("orders", req)); }
    @PostMapping("/auto") public Map<String, Object> auto(@RequestBody AutoRequest req) { return practice.auto(me(), req.requestId(), req.sessionId(), req.expectedRound(), req.enabled(), body("auto", req)); }
    @PostMapping("/surrender") public Map<String, Object> surrender(@RequestBody SessionRequest req) { return practice.surrender(me(), req.requestId(), req.sessionId(), body("surrender", req)); }
    @GetMapping("/reports") public List<Map<String, Object>> reports(@RequestParam(defaultValue = "0") int page) { return practice.history(me(), page); }
    @GetMapping("/report") public Map<String, Object> report(@RequestParam String sessionId) { return practice.report(me(), sessionId); }
}
