package com.wargame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.RadarPolicy;
import com.wargame.config.RetentionPolicy;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import com.wargame.util.PlayerNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RadarService {
    private final PlayerCityRepository cities;
    private final PlayerRepository players;
    private final BuildingRepository buildings;
    private final GuildMemberRepository members;
    private final WildTileRepository wilds;
    private final MarchRepository marches;
    private final RadarContactRepository contacts;
    private final RadarMonitorRepository monitors;
    private final IncomingIntelligenceService intelligence;
    private final GuildSupportService support;
    private final CityScope scope;
    private final RadarPolicy policy;
    private final RetentionPolicy retention;
    private final ObjectMapper mapper;
    private final RadarEvents events;

    private record Sensor(PlayerCity city, int level, double radius) {}
    private record Threat(March march, Set<Long> owners, String kind, String targetKey) {}

    private RadarMonitor monitor(Long owner) {
        RadarMonitor monitor = monitors.findForUpdate(owner).orElse(null);
        if (monitor != null) return monitor;
        players.lockById(owner).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        monitor = monitors.findForUpdate(owner).orElse(null);
        if (monitor == null) {
            monitor = new RadarMonitor();
            monitor.setPlayerId(owner);
            monitors.saveAndFlush(monitor);
        }
        return monitor;
    }

    /** 区域覆盖只读取本城联防雷达；城防雷达和全账号科技均不能替代建筑范围。 */
    private List<Sensor> sensors(Long owner, boolean enabledOnly) {
        long now = GameClock.now();
        List<Sensor> result = new ArrayList<>();
        for (PlayerCity city : cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(owner)) {
            if (city.getReadyAt() > now || (enabledOnly && !city.isRadarEnabled())) continue;
            int level = buildings.findByPlayerIdAndCitySlotAndType(owner, city.getCitySlot(), "guild_radar").stream()
                    .mapToInt(building -> Objects.requireNonNullElse(building.getLevel(), 0)).max().orElse(0);
            if (!enabledOnly || level > 0) result.add(new Sensor(city, level, policy.radius(level)));
        }
        return result;
    }

    /** 先锁监测者，再修改所属城市；关停立即撤销来源并冻结历史快照。 */
    @Transactional
    public Map<String, Object> setEnabled(Long owner, Long cityId, boolean enabled) {
        RadarMonitor monitor = monitor(owner);
        PlayerCity city = scope.requireOwned(owner, cityId, true);
        try (var ignored = scope.enter(city)) { scope.assertWritable(owner); }
        if (enabled && buildings.findByPlayerIdAndCitySlotAndType(owner, city.getCitySlot(), "guild_radar")
                .stream().noneMatch(building -> Objects.requireNonNullElse(building.getLevel(), 0) > 0))
            throw new IllegalArgumentException("本城未建设联防雷达");
        if (city.isRadarEnabled() != enabled) {
            city.setRadarEnabled(enabled);
            city.setRadarRevision(city.getRadarRevision() + 1);
            monitor.setRevision(monitor.getRevision() + 1);
            cities.saveAndFlush(city);
        }
        reconcile(owner);
        events.changed(owner);
        return view(owner, 0);
    }

    private Set<Long> guildPlayers(Long owner) {
        return members.findByPlayerId(owner).map(member -> {
            Set<Long> result = new HashSet<Long>();
            members.findByGuildIdOrderByJoinedAtAsc(member.getGuildId()).forEach(item -> result.add(item.getPlayerId()));
            return result;
        }).orElse(Set.of());
    }

    private boolean validMarch(March march) {
        return march != null && !Boolean.TRUE.equals(march.getReturning())
                && !Boolean.TRUE.equals(march.getStationed()) && !Boolean.TRUE.equals(march.getGathering())
                && (march.getAction() == null || BattleService.isOffensiveAction(march.getAction()) || "scout".equals(march.getAction()));
    }

    private Threat threat(March march, Set<Long> peers, Long observer) {
        if (!validMarch(march) || observer.equals(march.getPlayerId())) return null;
        Set<Long> owners = new TreeSet<>();
        if ("player".equals(march.getTargetKind())) {
            PlayerCity city = city(march.getTargetId());
            if (city == null || city.getCitySlot() == null || city.getOwnerId() == null || observer.equals(city.getOwnerId())) return null;
            owners.add(city.getOwnerId());
            for (March sent : support.stationed(city)) {
                if (support.canReceive(sent.getPlayerId(), city, sent.getSupportOwnerId(), GameClock.now())
                        && JsonUtil.parseIntMap(sent.getArmy()).values().stream().anyMatch(count -> count > 0)) owners.add(sent.getPlayerId());
            }
        } else if ("wild".equals(march.getTargetKind())) {
            WildTile tile = wild(march.getTargetId());
            if (tile != null && Boolean.TRUE.equals(tile.getOccupied()) && tile.getOccupiedBy() != null) owners.add(tile.getOccupiedBy());
        }
        owners.retainAll(peers);
        owners.remove(observer);
        if (owners.isEmpty()) return null;
        return new Threat(march, owners, "scout".equals(march.getAction()) ? "scout" : "attack",
                march.getTargetKind() + ":" + march.getTargetId());
    }

    private PlayerCity city(String id) {
        try { return cities.findById(Long.valueOf(id)).orElse(null); }
        catch (NumberFormatException ignored) { return null; }
    }

    private WildTile wild(String id) {
        try { return wilds.findById(Long.valueOf(id)).orElse(null); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<March> candidates(Set<Long> peers) {
        if (peers.isEmpty()) return List.of();
        Set<String> cityIds = new HashSet<>(Set.of("-1")), wildIds = new HashSet<>(Set.of("-1"));
        cities.findByOwnerIdInAndCitySlotIsNotNull(peers).forEach(city -> cityIds.add(city.getId().toString()));
        marches.findByPlayerIdInAndStationedTrueAndTargetKind(peers, "player").forEach(sent -> cityIds.add(sent.getTargetId()));
        for (Long peer : peers) wilds.findByOccupiedBy(peer).stream().filter(tile -> Boolean.TRUE.equals(tile.getOccupied()))
                .forEach(tile -> wildIds.add(tile.getId().toString()));
        return marches.findRegionalThreats(cityIds, wildIds);
    }

    /** 完整权威折线参与判定，端点使用实际城市／地块中心；损坏路线不猜测绕行路径。 */
    private List<double[]> route(March march) {
        List<double[]> result = new ArrayList<>();
        if (march.getRouteData() != null && !march.getRouteData().isBlank()) {
            try {
                JsonNode data = mapper.readTree(march.getRouteData());
                if (!data.isArray() || data.size() < 2) return List.of();
                for (JsonNode point : data) {
                    if (!point.isArray() || point.size() < 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) return List.of();
                    double routeX = point.get(0).asDouble(), routeY = point.get(1).asDouble();
                    if (!Double.isFinite(routeX) || !Double.isFinite(routeY)) return List.of();
                    result.add(new double[]{routeX + .5, routeY + .5});
                }
            } catch (Exception ignored) { return List.of(); }
        } else if (march.getFromX() != null && march.getFromY() != null && march.getTargetX() != null && march.getTargetY() != null
                && (march.getRouteMode() == null || "air".equals(march.getRouteMode()))) {
            result.add(new double[]{march.getFromX() + 1.0, march.getFromY() + 1.0});
            result.add(new double[]{march.getTargetX() + .5, march.getTargetY() + .5});
        }
        if (!result.isEmpty()) {
            if (march.getFromX() != null && march.getFromY() != null) result.set(0, new double[]{march.getFromX() + 1.0, march.getFromY() + 1.0});
            if ("player".equals(march.getTargetKind()) && march.getTargetX() != null && march.getTargetY() != null)
                result.set(result.size() - 1, new double[]{march.getTargetX() + 1.0, march.getTargetY() + 1.0});
        }
        return result;
    }

    private List<Map<String, Object>> sources(Threat threat, List<Sensor> sensors) {
        List<double[]> route = route(threat.march());
        if (route.isEmpty()) return List.of();
        double minX = route.stream().mapToDouble(point -> point[0]).min().orElse(0), maxX = route.stream().mapToDouble(point -> point[0]).max().orElse(0);
        double minY = route.stream().mapToDouble(point -> point[1]).min().orElse(0), maxY = route.stream().mapToDouble(point -> point[1]).max().orElse(0);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Sensor sensor : sensors) {
            double centerX = sensor.city().getX() + 1.0, centerY = sensor.city().getY() + 1.0, radius = sensor.radius();
            if (maxX < centerX - radius || minX > centerX + radius || maxY < centerY - radius || minY > centerY + radius) continue;
            if (RadarGeometry.intersects(route, centerX, centerY, radius)) result.add(Map.of("cityId", sensor.city().getId(),
                    "name", sensor.city().getName(), "citySlot", sensor.city().getCitySlot(), "revision", sensor.city().getRadarRevision()));
        }
        return result;
    }

    /** 区域警报只公开威胁类别；具体征服／掠夺行动仍须通过一级情报授权。 */
    private Map<String, Object> project(Threat threat, int level) {
        March march = threat.march();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "regional-march-" + march.getId()); raw.put("marchId", march.getId());
        raw.put("targetName", march.getTargetName()); raw.put("targetX", march.getTargetX()); raw.put("targetY", march.getTargetY());
        raw.put("fromX", march.getFromX()); raw.put("fromY", march.getFromY());
        raw.put("fromName", players.findById(march.getPlayerId()).map(PlayerNames::commanderName).orElse("未知玩家"));
        raw.put("army", JsonUtil.parseIntMap(march.getArmy())); raw.put("action", Objects.requireNonNullElse(march.getAction(), "conquer"));
        raw.put("startAt", march.getStartAt()); raw.put("arriveAt", march.getArriveAt()); raw.put("inBattle", march.getBattleId() != null);
        Map<String, Object> result = intelligence.view(level, raw, march);
        result.put("intelligenceType", "regional_radar"); result.put("threatKind", threat.kind());
        result.put("targetKind", march.getTargetKind()); result.put("targetId", march.getTargetId());
        result.put("waitingForBattle", march.getBattleId() == null && march.getArriveAt() != null && march.getArriveAt() <= GameClock.now());
        result.put("threatenedPlayers", threat.owners().stream().map(owner -> Map.of("playerId", owner,
                "name", players.findById(owner).map(PlayerNames::commanderName).orElse("未知团友"))).toList());
        return result;
    }

    /** 监测记录独立于战斗结算；同一玩家与行军仅一条，关闭时保留最后授权快照。 */
    @Transactional
    public void reconcile(Long owner) {
        if (!players.existsById(owner)) return;
        RadarMonitor monitor = monitor(owner);
        List<Sensor> sensors = sensors(owner, true);
        Set<Long> peers = guildPlayers(owner);
        Long guildId = members.findByPlayerId(owner).map(GuildMember::getGuildId).orElse(null);
        List<RadarContact> known = contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(owner);
        Map<Long, RadarContact> byMarch = new HashMap<>();
        known.forEach(contact -> byMarch.put(contact.getMarchId(), contact));
        Set<Long> matched = new HashSet<>();
        Map<Long, List<Map<String, Object>>> matchedSources = new HashMap<>();
        List<Threat> eligible = new ArrayList<>();
        long now = GameClock.now();
        int level = intelligence.radar(owner);
        if (!sensors.isEmpty() && guildId != null) for (March march : candidates(peers)) {
            Threat threat = threat(march, peers, owner);
            if (threat == null) continue;
            List<Map<String, Object>> sources = sources(threat, sensors);
            if (sources.isEmpty()) continue;
            matched.add(march.getId());
            matchedSources.put(march.getId(), sources); eligible.add(threat);
        }
        for (Threat threat : eligible) {
            March march = threat.march();
            List<Map<String, Object>> sources = matchedSources.get(march.getId());
            RadarContact contact = byMarch.get(march.getId());
            if (contact == null) {
                contact = new RadarContact(); contact.setObserverId(owner); contact.setMarchId(march.getId()); contact.setFirstSeenAt(now);
                contact.setAttackerId(march.getPlayerId()); contact.setTargetKey(threat.targetKey()); contact.setThreatKind(threat.kind());
                RadarContact group = known.stream().filter(item -> matched.contains(item.getMarchId()) && Objects.equals(item.getGuildId(), guildId) && "active".equals(item.getStatus()) && item.getAttackerId().equals(march.getPlayerId())
                        && item.getTargetKey().equals(threat.targetKey()) && item.getThreatKind().equals(threat.kind())
                        && item.getNoticeAt() > 0 && now - item.getNoticeAt() < Math.max(1, policy.getReminderWindowMs()))
                        .max(Comparator.comparingLong(RadarContact::getNoticeAt)).orElse(null);
                contact.setNoticePending(group == null); contact.setNoticeAt(group == null ? now : group.getNoticeAt());
                contact.setReminderGroup(group == null ? UUID.randomUUID().toString() : group.getReminderGroup());
                contact.setExpiresAt(System.currentTimeMillis() + retention.days(retention.getReportDetailDays()));
                known.add(contact); byMarch.put(march.getId(), contact);
            }
            contact.setGuildId(guildId); contact.setStatus("active"); contact.setLastSeenAt(now);
            Map<String, Object> snapshot = project(threat, level); snapshot.put("sources", sources);
            contact.setSnapshot(JsonUtil.toJson(snapshot)); contact.setSources(JsonUtil.toJson(sources));
            contacts.save(contact);
        }
        for (RadarContact contact : known) {
            if (matched.contains(contact.getMarchId()) || !"active".equals(contact.getStatus())) continue;
            March march = marches.findById(contact.getMarchId()).orElse(null);
            String status = sensors.isEmpty() ? "monitoring_disabled" : validMarch(march) ? "monitoring_lost"
                    : march != null && Boolean.TRUE.equals(march.getReturning()) ? "withdrawn" : "ended";
            contact.setStatus(status); contact.setNoticePending(false); contact.setSources("[]");
            contacts.save(contact);
        }
        monitor.setRevision(monitor.getRevision() + 1);
        monitors.save(monitor);
    }

    private Map<String, Object> sensorView(Sensor sensor) {
        return Map.of("cityId", sensor.city().getId(), "citySlot", sensor.city().getCitySlot(), "name", sensor.city().getName(),
                "x", sensor.city().getX() + 1.0, "y", sensor.city().getY() + 1.0, "level", sensor.level(), "radius", sensor.radius(),
                "enabled", sensor.city().isRadarEnabled(), "revision", sensor.city().getRadarRevision());
    }

    private Map<String, Object> snapshot(RadarContact contact, int level) {
        Map<String, Object> saved = JsonUtil.parseObjMap(contact.getSnapshot());
        int granted = saved.get("radarLevel") instanceof Number number ? number.intValue() : 0;
        int allowed = Math.min(granted, level);
        Map<String, Object> data = IncomingIntelligenceService.project(saved, allowed);
        for (String key : List.of("targetKind", "targetId", "threatenedPlayers")) {
            if (saved.containsKey(key)) data.put(key, saved.get(key));
        }
        for (Map.Entry<String, Integer> field : Map.of("branches", 3, "strengthBand", 4, "unitTypes", 5, "totalTroops", 6).entrySet()) {
            if (allowed >= field.getValue() && saved.containsKey(field.getKey())) data.put(field.getKey(), saved.get(field.getKey()));
            else data.remove(field.getKey());
        }
        return data;
    }

    /** 读取也校验当前成员、开关和几何权限；历史快照只降权，不借新科技补全。 */
    @Transactional(readOnly = true)
    public Map<String, Object> view(Long owner, long beforeId) {
        List<Sensor> allSensors = sensors(owner, false), active = allSensors.stream().filter(sensor -> sensor.city().isRadarEnabled() && sensor.level() > 0).toList();
        Set<Long> peers = guildPlayers(owner);
        Long guildId = members.findByPlayerId(owner).map(GuildMember::getGuildId).orElse(null);
        List<Map<String, Object>> current = new ArrayList<>(), history = new ArrayList<>();
        long unread = 0;
        for (RadarContact contact : contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(owner)) {
            March march = "active".equals(contact.getStatus()) ? marches.findById(contact.getMarchId()).orElse(null) : null;
            Threat threat = threat(march, peers, owner);
            List<Map<String, Object>> sources = threat == null || !Objects.equals(contact.getGuildId(), guildId) ? List.of() : sources(threat, active);
            boolean live = !sources.isEmpty();
            Map<String, Object> data;
            if (live) data = project(threat, intelligence.radar(owner));
            else data = snapshot(contact, intelligence.radar(owner));
            data.put("id", "radar-" + contact.getId()); data.put("contactId", contact.getId()); data.put("marchId", contact.getMarchId());
            data.put("intelligenceType", "regional_radar"); data.put("threatKind", contact.getThreatKind());
            data.put("firstSeenAt", contact.getFirstSeenAt()); data.put("lastSeenAt", contact.getLastSeenAt());
            data.put("read", contact.getReadAt() > 0); data.put("sources", live ? sources : List.of());
            data.put("reminderGroup", contact.getReminderGroup());
            if (live && "player".equals(march.getTargetKind())) {
                PlayerCity target = city(march.getTargetId());
                if (target != null && target.isAcceptGarrison() && support.canReceive(owner, target, target.getOwnerId(), GameClock.now())) {
                    data.put("canSupport", true); data.put("targetOwnerId", target.getOwnerId());
                }
            }
            data.put("status", live ? "active" : "active".equals(contact.getStatus()) ? "monitoring_lost" : contact.getStatus());
            if (live) { current.add(data); if (contact.getReadAt() == 0) unread++; }
            else if ((beforeId <= 0 || contact.getId() < beforeId) && history.size() < Math.max(1, Math.min(100, policy.getPageSize()))) {
                Map<String, Object> saved = JsonUtil.parseObjMap(contact.getSnapshot());
                data.put("threatenedPlayers", saved.getOrDefault("threatenedPlayers", List.of()));
                data.put("snapshotAt", contact.getLastSeenAt()); data.put("targetKind", saved.get("targetKind")); data.put("targetId", saved.get("targetId"));
                data.put("snapshotSources", saved.getOrDefault("sources", List.of()));
                history.add(data);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", monitors.findById(owner).map(RadarMonitor::getRevision).orElse(0L));
        result.put("cities", allSensors.stream().map(this::sensorView).toList()); result.put("contacts", current); result.put("history", history);
        result.put("joinedGuild", guildId != null);
        result.put("unread", unread); result.put("nextBeforeId", history.size() == Math.max(1, Math.min(100, policy.getPageSize()))
                ? history.get(history.size() - 1).get("contactId") : null);
        return result;
    }

    @Transactional
    public Map<String, Object> read(Long owner, Long contactId) {
        RadarMonitor monitor = monitor(owner);
        RadarContact contact = contacts.findById(contactId).filter(item -> owner.equals(item.getObserverId()))
                .orElseThrow(() -> new IllegalArgumentException("军情不存在或无权读取"));
        contact.setReadAt(GameClock.now()); contacts.save(contact);
        monitor.setRevision(monitor.getRevision() + 1);
        return view(owner, 0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> shareable(Long owner, Long contactId) {
        return ((List<?>) view(owner, 0).get("contacts")).stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(data -> Objects.equals(data.get("contactId"), contactId)).findFirst()
                .map(data -> new LinkedHashMap<String, Object>(data))
                .orElseThrow(() -> new IllegalArgumentException("区域军情已结束或无权分享"));
    }

    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Set<Long> monitoredPlayers() {
        Set<Long> result = new TreeSet<>(contacts.findActiveObserverIds());
        cities.findByRadarEnabledTrueAndCitySlotIsNotNull().forEach(city -> result.add(city.getOwnerId()));
        return result;
    }
}
