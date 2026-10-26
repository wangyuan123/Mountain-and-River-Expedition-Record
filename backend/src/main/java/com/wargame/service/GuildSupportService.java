package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.entity.March;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.Officer;
import com.wargame.model.dto.JointDefenseState;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.util.JsonUtil;
import com.wargame.util.PlayerNames;
import com.wargame.model.constants.UnitEquipmentEffects;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 同军团支援权限、独立驻军编队及开战快照的兵力分摊。 */
@Service
@RequiredArgsConstructor
public class GuildSupportService {
    private final GuildRelationService relations;
    private final PlayerCityRepository cities;
    private final PlayerRepository players;
    private final MarchRepository marches;
    private final CityScope scope;
    private final BattleActionPreferences preferences;
    private final EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private UnitEquipmentService equipment;

    @org.springframework.beans.factory.annotation.Autowired private MarchTargetService targets;
    @org.springframework.beans.factory.annotation.Autowired private EquipmentService officerEquipment;
    @org.springframework.beans.factory.annotation.Autowired private GarrisonFoodService food;
    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;

    /** 每座城市独立授权；关闭仅拒收后续抵达，现有驻军继续计粮和参战。 */
    @Transactional
    public void setAcceptance(Long owner, Long cityId, boolean allowed) {
        accounts.lockPlayer(owner);
        PlayerCity city = scope.requireOwned(owner, cityId, true);
        try (var ignored = scope.enter(city)) { scope.assertWritable(owner); }
        food.settle(city, GameClock.now()); city.setAcceptGarrison(allowed); cities.save(city);
    }

    public record Defense(Map<String, Integer> army, Map<String, Map<String, Integer>> support, Map<String, Integer> equipment, Map<String, Object> sources) {
        public Defense(Map<String, Integer> army, Map<String, Map<String, Integer>> support) { this(army, support, Map.of(), Map.of()); }
    }
    public record SupportLoss(March march, Map<String, Integer> initial, Map<String, Integer> survivors) {}
    public record Losses(Map<String, Integer> initial, Map<String, Integer> survivors, List<SupportLoss> support) {}

    /** 在出发与抵达时重验城主和军团身份；自己的城市仍沿用原运输权限。 */
    public boolean canReceive(Long sender, PlayerCity city, Long expectedOwner, long now) {
        if (city == null || city.getCitySlot() == null || city.getOwnerId() == null
                || Objects.requireNonNullElse(city.getReadyAt(), 0L) > now) return false;
        Long owner = city.getOwnerId();
        if (expectedOwner != null && !expectedOwner.equals(owner)) return false;
        Player player = players.findById(owner).orElse(null);
        return player != null && !player.deletionDue(now)
                && (sender.equals(owner) || relations.areGuildmates(sender, owner));
    }

    /** 返回指定军团成员的全部城市，供支援弹窗选择主城或分城。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> memberCities(Long viewer, Long memberId) {
        if (!relations.areGuildmates(viewer, memberId)) throw new IllegalArgumentException("只能支援同军团成员");
        long now = GameClock.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerCity city : cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(memberId)) {
            if (!canReceive(viewer, city, memberId, now)) continue;
            result.add(Map.of("id", city.getId(), "name", city.getName(), "ownerId", memberId,
                    "x", city.getX(), "y", city.getY(), "readyAt", Objects.requireNonNullElse(city.getReadyAt(), 0L), "acceptGarrison", city.isAcceptGarrison()));
        }
        return result;
    }

    public List<March> stationed(PlayerCity city) {
        return marches.findByTargetKindAndTargetIdAndStationedTrueOrderByIdAsc("player", city.getId().toString());
    }

    /** 城主锁到手后重读驻军，避免等待战斗结算期间缓存的旧兵力覆盖幸存者。 */
    public Optional<March> refreshGarrison(Long id) {
        Optional<March> march = marches.lockSupportId(id).flatMap(marches::findById);
        march.ifPresent(value -> em.refresh(value, LockModeType.PESSIMISTIC_WRITE));
        return march;
    }

    /** 仅城主可见收到的驻军；派兵人自己的驻军继续出现在原城军情中。 */
    public List<Map<String, Object>> received(Long viewer) {
        PlayerCity city = scope.selected(viewer).orElse(null);
        if (city == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        var sessions = em.createQuery("select s from BattleSession s where s.targetKind = 'player' and s.targetId = :city", com.wargame.model.entity.BattleSession.class).setParameter("city", city.getId().toString()).getResultList();
        for (March march : stationed(city)) {
            if (!viewer.equals(march.getSupportOwnerId())) continue;
            String sender = players.findById(march.getPlayerId()).map(PlayerNames::commanderName).orElse("已离开的玩家");
            result.add(Map.of("id", march.getId(), "playerId", march.getPlayerId(), "senderName", sender,
                    "army", UnitEquipmentEffects.aggregate(food.living(march, sessions)), "targetName", city.getName(),
                    "foodPerHour", GarrisonFoodService.baseUse(food.living(march, sessions)) * food.reduction(city),
                    "inBattle", food.locked(march, sessions)));
        }
        return result;
    }

    /** 先选择本城迎战编队，再追加全量有效驻军，驻军不受城主预设二次裁剪。 */
    public Defense defense(PlayerCity city, Map<String, Integer> ownArmy) {
        return defense(city, ownArmy, true);
    }

    /** 侦查情报及拦截统计全部驻军，不受战术迎战预设限制。 */
    public Defense reconnaissance(PlayerCity city, Map<String, Integer> ownArmy) {
        return defense(city, ownArmy, false);
    }

    private Defense defense(PlayerCity city, Map<String, Integer> ownArmy, boolean sortie) {
        Map<String, Map<String, Integer>> sources = new LinkedHashMap<>();
        Map<String, Long> owners = new LinkedHashMap<>(); owners.put("0", city.getOwnerId());
        sources.put("0", sortie ? preferences.selectSortieArmy(city.getOwnerId(), ownArmy) : ownArmy);
        Map<String, Integer> total = new LinkedHashMap<>(sources.get("0"));
        for (March march : stationed(city)) {
            if (!canReceive(march.getPlayerId(), city, march.getSupportOwnerId(), GameClock.now())) continue;
            Map<String, Integer> army = JsonUtil.parseIntMap(march.getArmy());
            sources.put(march.getId().toString(), army);
            owners.put(march.getId().toString(), march.getPlayerId());
            army.forEach((unit, count) -> total.merge(unit, count, Integer::sum));
        }
        Map<String, Integer> selected = total;
        Map<String, Map<String, Integer>> allocated = sortie ? sources : allocate(sources, selected);
        Map<String, Integer> ownEquipment = equipment == null ? Map.of() : equipment.snapshot(city.getOwnerId());
        Map<String, Integer> frozen = new LinkedHashMap<>(ownEquipment), grouped = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> support = new LinkedHashMap<>();
        Map<String, Object> provenance = new LinkedHashMap<>();
        Map<String, String> profileIds = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> ownerEquipment = new HashMap<>(); ownerEquipment.put(city.getOwnerId(), ownEquipment);
        // 普通守城无论配装是否相同均保留来源；独立侦察继续沿用旧配装分组。
        allocated.forEach((source, sent) -> {
            Map<String, Integer> sourceEquipment = !"0".equals(source) && sortie
                    ? marches.findById(Long.valueOf(source)).map(m -> JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot())).orElse(Map.of())
                    : ownerEquipment.computeIfAbsent(owners.get(source), id -> equipment == null ? Map.of() : equipment.snapshot(id));
            Map<String, Integer> groupedSource = new LinkedHashMap<>();
            sent.forEach((unit, count) -> {
                Map<String, Integer> profile = UnitEquipmentEffects.profile(sourceEquipment, unit);
                Map<String, Integer> ownProfile = UnitEquipmentEffects.profile(ownEquipment, unit);
                // 空配装的 version 标记不产生人为分组。
                profile.remove("version"); ownProfile.remove("version");
                String signature = unit + JsonUtil.toJson(profile);
                String alias = sortie ? ("0".equals(source) ? unit : unit + "~s" + new ArrayList<>(allocated.keySet()).indexOf(source))
                        : profile.equals(ownProfile) ? unit : profileIds.computeIfAbsent(signature, k -> unit + "~" + (profileIds.size() + 1));
                UnitEquipmentEffects.copyProfile(frozen, alias, profile);
                if (!profile.isEmpty()) frozen.put(UnitEquipmentEffects.key(alias, "version"), UnitEquipmentEffects.VERSION);
                grouped.merge(alias, count, Integer::sum); groupedSource.put(alias, count);
            });
            if (!"0".equals(source)) support.put(source, groupedSource);
            Long owner = owners.get(source);
            // 驻军面板和战斗来源使用同一公开昵称，不向其他玩家暴露登录账号。
            provenance.put(source, Map.of("playerId", owner, "playerName", players.findById(owner).map(PlayerNames::commanderName).orElse("已离开的玩家"), "army", groupedSource));
        });
        return new Defense(grouped, support, frozen, provenance);
    }

    /** 新编队按独立别名的实际幸存数结算；历史合编快照继续按原比例结算。 */
    public Losses settle(String snapshot, Map<String, Integer> initial, Map<String, Integer> survivors) {
        if (JsonUtil.parseObjMap(snapshot).isEmpty()) return new Losses(UnitEquipmentEffects.aggregate(initial), UnitEquipmentEffects.aggregate(survivors), List.of());
        Map<String, Map<String, Integer>> sources = new LinkedHashMap<>();
        Map<String, Integer> ownInitial = new LinkedHashMap<>(initial);
        JsonUtil.parseObjMap(snapshot).forEach((id, army) -> {
            Map<String, Integer> sent = JsonUtil.parseIntMap(JsonUtil.toJson(army));
            sources.put(id, sent);
            sent.forEach((unit, count) -> ownInitial.compute(unit, (key, value) -> Math.max(0, Objects.requireNonNullElse(value, 0) - count)));
        });
        Map<String, Map<String, Integer>> ordered = new LinkedHashMap<>();
        ordered.put("0", ownInitial); ordered.putAll(sources);
        Map<String, Integer> lost = new LinkedHashMap<>();
        initial.forEach((unit, count) -> lost.put(unit, Math.max(0, count - survivors.getOrDefault(unit, 0))));
        boolean independent = initial.keySet().stream().anyMatch(id -> id.contains("~s"));
        Map<String, Map<String, Integer>> allocated = independent ? new LinkedHashMap<>() : allocate(ordered, lost);
        if (independent) ordered.forEach((id, army) -> {
            Map<String, Integer> actual = new LinkedHashMap<>();
            army.forEach((unit, count) -> actual.put(unit, Math.max(0, count - survivors.getOrDefault(unit, 0))));
            allocated.put(id, actual);
        });
        List<SupportLoss> supportLosses = new ArrayList<>();
        for (var source : sources.entrySet()) {
            March march = marches.findById(Long.valueOf(source.getKey())).orElse(null);
            if (march == null) continue;
            Map<String, Integer> remaining = new LinkedHashMap<>(JsonUtil.parseIntMap(march.getArmy()));
            Map<String, Integer> alive = new LinkedHashMap<>(source.getValue());
            allocated.get(source.getKey()).forEach((unit, count) -> {
                remaining.computeIfPresent(UnitEquipmentEffects.base(unit), (key, value) -> Math.max(0, value - count));
                alive.computeIfPresent(unit, (key, value) -> Math.max(0, value - count));
            });
            remaining.values().removeIf(count -> count <= 0);
            march.setArmy(JsonUtil.toJson(remaining));
            if (remaining.isEmpty()) marches.delete(march); else marches.save(march);
            supportLosses.add(new SupportLoss(march, UnitEquipmentEffects.aggregate(source.getValue()), UnitEquipmentEffects.aggregate(alive)));
        }
        Map<String, Integer> ownAlive = new LinkedHashMap<>(ownInitial);
        allocated.get("0").forEach((unit, count) -> ownAlive.computeIfPresent(unit, (key, value) -> Math.max(0, value - count)));
        return new Losses(UnitEquipmentEffects.aggregate(ownInitial), UnitEquipmentEffects.aggregate(ownAlive), supportLosses);
    }

    /** 冻结来源身份、随队军官和出发配装，战斗中不再读取实时换装或调任。 */
    public JointDefenseState freeze(Defense defense, PlayerCity city, Officer home, March attack, Officer attacker,
            Map<String, Integer> attackerArmy, Map<String, Integer> attackerEquipment, Map<String, Integer> defenderArmy) {
        JointDefenseState state = new JointDefenseState(); state.setSeed(attack.getId());
        state.getFormations().put("attack", formation("attack", "attack", attack.getPlayerId(), attack.getCitySlot(), attack.getId(), attacker,
                attackerArmy, attackerEquipment));
        Map<String, Integer> homeArmy = new LinkedHashMap<>(defenderArmy);
        defense.support().values().forEach(army -> army.keySet().forEach(homeArmy::remove));
        state.getFormations().put("0", formation("0", "home", city.getOwnerId(), city.getCitySlot(), null, home, homeArmy,
                UnitEquipmentEffects.only(defense.equipment())));
        defense.support().forEach((id, army) -> {
            March m = marches.findById(Long.valueOf(id)).orElseThrow();
            Officer officer = targets.getOfficerById(m.getPlayerId(), m.getCommanderId());
            state.getFormations().put(id, formation(id, "support", m.getPlayerId(), m.getCitySlot(), m.getId(), officer, army,
                    UnitEquipmentEffects.only(defense.equipment())));
        });
        return state;
    }
    private JointDefenseState.Formation formation(String id, String kind, Long owner, Integer slot, Long marchId,
            Officer officer, Map<String, Integer> army, Map<String, Integer> equipment) {
        var f = new JointDefenseState.Formation(); f.setId(id); f.setKind(kind); f.setPlayerId(owner); f.setCitySlot(slot); f.setMarchId(marchId);
        f.setPlayerName(owner == null ? "来袭敌军" : players.findById(owner).map(PlayerNames::commanderName).orElse("已离开的玩家"));
        f.setOfficerName(officer == null ? "无随队将领" : officer.getName()); f.setOfficerId(officer == null ? null : officer.getId());
        if (officer != null) { var attrs = officerEquipment.attributes(officer); f.setMilitary(attrs.military()); f.setDefense(attrs.defense()); }
        f.setSkills(new LinkedHashMap<>(targets.getCommanderSkills(officer))); f.setInitial(new LinkedHashMap<>(army));
        f.setEquipment(new LinkedHashMap<>(UnitEquipmentEffects.forArmy(equipment, army)));
        return f;
    }

    /** 逐批按剩余比例分配，最后一批承接整数余数，确保总数严格守恒。 */
    private Map<String, Map<String, Integer>> allocate(Map<String, Map<String, Integer>> sources, Map<String, Integer> requested) {
        Map<String, Integer> total = new LinkedHashMap<>();
        sources.values().forEach(army -> army.forEach((unit, count) -> total.merge(unit, count, Integer::sum)));
        Map<String, Integer> remaining = new LinkedHashMap<>(requested);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        sources.forEach((id, army) -> {
            Map<String, Integer> selected = new LinkedHashMap<>();
            army.forEach((unit, count) -> {
                int available = total.getOrDefault(unit, 0);
                int wanted = Math.min(available, remaining.getOrDefault(unit, 0));
                int amount = available > 0 ? (int) ((long) wanted * count / available) : 0;
                if (amount > 0) selected.put(unit, amount);
                total.put(unit, available - count); remaining.put(unit, wanted - amount);
            });
            result.put(id, selected);
        });
        return result;
    }
}
