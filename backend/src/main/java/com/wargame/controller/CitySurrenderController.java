package com.wargame.controller;

import com.wargame.service.AuthService;
import com.wargame.service.CitySurrenderService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/game/surrender")
public class CitySurrenderController {
    private final AuthService auth;
    private final CitySurrenderService surrender;
    private final GameStateService state;

    public CitySurrenderController(AuthService auth, CitySurrenderService surrender, GameStateService state) {
        this.auth = auth; this.surrender = surrender; this.state = state;
    }

    @PostMapping("/offer")
    public ResponseEntity<Map<String, Object>> offer(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("cityId");
        if (raw == null) throw new IllegalArgumentException("目标城市不能为空");
        Long cityId;
        try { cityId = Long.valueOf(raw.toString()); } catch (NumberFormatException e) { throw new IllegalArgumentException("目标城市无效"); }
        Long playerId = auth.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>(surrender.offer(playerId, cityId));
        result.put("success", true); result.put("state", state.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/respond")
    public ResponseEntity<Map<String, Object>> respond(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("offerId") == null) throw new IllegalArgumentException("劝降邀请不能为空");
        Long offerId;
        try { offerId = Long.valueOf(body.get("offerId").toString()); } catch (NumberFormatException e) { throw new IllegalArgumentException("劝降邀请无效"); }
        String decision = body.get("decision") == null ? "" : body.get("decision").toString();
        Long playerId = auth.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>(surrender.respond(playerId, offerId, decision));
        result.put("success", true); result.put("state", state.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{offerId}")
    public Map<String, Object> get(@PathVariable Long offerId) {
        return surrender.get(auth.getCurrentPlayer().getId(), offerId);
    }
}
