package com.wargame.service;

import com.wargame.model.entity.PersonalWar;
import com.wargame.repository.PersonalWarRepository;
import com.wargame.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PersonalWarService {
    private final PersonalWarRepository wars;
    private final PlayerRepository players;

    /** 读取指定双方的战争；旧存档仅在不存在新关系时兼容，绝不读取第三方的倒计时。 */
    public PersonalWar between(Long first, Long second) {
        if (first == null || second == null || first.equals(second)) return null;
        PersonalWar saved = wars.findByPlayerLowIdAndPlayerHighId(Math.min(first, second), Math.max(first, second)).orElse(null);
        if (saved != null) return saved;
        var legacy = players.findById(first).filter(p -> second.equals(p.getWarAgainstId()))
                .or(() -> players.findById(second).filter(p -> first.equals(p.getWarAgainstId()))).orElse(null);
        if (legacy == null) return null;
        PersonalWar result = new PersonalWar();
        result.setPlayerLowId(Math.min(first, second)); result.setPlayerHighId(Math.max(first, second));
        result.setInitiatorId(Objects.requireNonNullElse(legacy.getWarInitiatorId(), legacy.getId()));
        result.setCitySlot(Objects.requireNonNullElse(legacy.getWarCitySlot(), 0));
        result.setTargetCityId(legacy.getWarTargetCityId()); result.setDeclaredAt(Objects.requireNonNullElse(legacy.getWarDeclaredAt(), 0L));
        result.setWarAt(Objects.requireNonNullElse(legacy.getWarAt(), 0L)); result.setWarEndAt(Objects.requireNonNullElse(legacy.getWarEndAt(), 0L));
        result.setAccelerated(legacy.isWarAccelerated()); result.setOrderEffect(legacy.getWarOrderEffect());
        return result;
    }

    /** 调用方持有双方账号锁；已结束关系可重新宣战，其他对手的关系保持原样。 */
    public PersonalWar declare(Long owner, Long defender, int slot, Long target, long now, long start, long end) {
        PersonalWar relation = wars.findByPlayerLowIdAndPlayerHighId(Math.min(owner, defender), Math.max(owner, defender)).orElseGet(PersonalWar::new);
        relation.setPlayerLowId(Math.min(owner, defender)); relation.setPlayerHighId(Math.max(owner, defender));
        relation.setInitiatorId(owner); relation.setCitySlot(slot); relation.setTargetCityId(target);
        relation.setDeclaredAt(now); relation.setWarAt(start); relation.setWarEndAt(end);
        relation.setAccelerated(false); relation.setOrderEffect(null);
        return wars.save(relation);
    }

    public void save(PersonalWar relation) { wars.save(relation); }
    public boolean active(Long player, long now) {
        return wars.existsByPlayerLowIdAndWarEndAtGreaterThanOrPlayerHighIdAndWarEndAtGreaterThan(player, now, player, now);
    }
}
