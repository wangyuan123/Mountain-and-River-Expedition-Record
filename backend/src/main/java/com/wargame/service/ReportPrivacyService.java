package com.wargame.service;

import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 历史战报属于持有人；读取时隐藏已注销参与者名称，不删除战果或改写正文。 */
@Service
@RequiredArgsConstructor
public class ReportPrivacyService {
    private final PlayerRepository players;
    private static final Map<String, String> ID_FIELDS = Map.of(
            "attackerName", "attackerPlayerId", "defenderName", "defenderPlayerId",
            "ownerName", "ownerId", "playerName", "playerId", "fromName", "attackerPlayerId");

    /** 新战报按稳定ID脱敏；无身份的旧快照只兼容无歧义名称，不误伤同名活跃账号。 */
    public void anonymize(List<Map<String, Object>> reports) {
        Set<Long> ids = new HashSet<>(); Set<String> names = new HashSet<>();
        reports.forEach(r -> collect(r, ids, names));
        Set<Long> deleted = new HashSet<>();
        players.findAllById(ids).stream().filter(p -> "DELETED".equals(p.getAccountStatus())).forEach(p -> deleted.add(p.getId()));
        Map<String, Set<Long>> aliases = new HashMap<>();
        if (!names.isEmpty()) for (Player p : players.findReportParticipants(names)) {
            for (String name : new String[]{p.getUsername(), p.getDisplayName()}) if (name != null && names.contains(name))
                aliases.computeIfAbsent(name, k -> new HashSet<>()).add(p.getId());
            if ("DELETED".equals(p.getAccountStatus())) deleted.add(p.getId());
        }
        for (Map<String, Object> report : reports) {
            @SuppressWarnings("unchecked") Map<String, Object> clean = (Map<String, Object>) redact(report, deleted, aliases);
            report.clear(); report.putAll(clean);
        }
    }

    private void collect(Object value, Set<Long> ids, Set<String> names) {
        if (value instanceof Map<?, ?> map) {
            ID_FIELDS.forEach((name, id) -> {
                if (map.get(id) instanceof Number n) ids.add(n.longValue());
                else if (map.get(name) instanceof String text) names.add(text);
            });
            map.values().forEach(child -> collect(child, ids, names));
        } else if (value instanceof List<?> list) list.forEach(child -> collect(child, ids, names));
    }

    private Object redact(Object value, Set<Long> deleted, Map<String, Set<Long>> aliases) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String field = key.toString();
                Object identity = ID_FIELDS.containsKey(field) ? map.get(ID_FIELDS.get(field)) : null;
                boolean hidden = identity instanceof Number n && deleted.contains(n.longValue());
                if (identity == null && ID_FIELDS.containsKey(field) && child instanceof String name) {
                    Set<Long> candidates = aliases.getOrDefault(name, Set.of());
                    hidden = candidates.size() == 1 && deleted.contains(candidates.iterator().next());
                }
                result.put(field, hidden ? "已注销玩家" : redact(child, deleted, aliases));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(child -> redact(child, deleted, aliases)).toList();
        return value;
    }
}
