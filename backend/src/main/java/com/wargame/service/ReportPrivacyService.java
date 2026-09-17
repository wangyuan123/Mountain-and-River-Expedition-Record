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
    private static final Set<String> NAME_FIELDS = Set.of("attackerName", "defenderName", "ownerName", "playerName");

    public void anonymize(List<Map<String, Object>> reports) {
        Set<String> names = new HashSet<>();
        reports.forEach(report -> collect(report, names));
        if (names.isEmpty()) return;
        Set<String> deleted = new HashSet<>();
        players.findByUsernameInAndAccountStatus(names, "DELETED").stream().map(Player::getUsername).forEach(deleted::add);
        reports.forEach(report -> report.replaceAll((key, child) -> redact(key, child, deleted)));
    }

    private void collect(Object value, Set<String> names) {
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> {
            if (NAME_FIELDS.contains(key) && child instanceof String name) names.add(name);
            else collect(child, names);
        });
        else if (value instanceof List<?> list) list.forEach(child -> collect(child, names));
    }

    private Object redact(String key, Object value, Set<String> names) {
        if (NAME_FIELDS.contains(key) && value instanceof String name && names.contains(name)) return "已注销玩家";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((childKey, child) -> copy.put(childKey.toString(), redact(childKey.toString(), child, names)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(child -> redact("", child, names)).toList();
        return value;
    }
}
