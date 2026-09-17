package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Lightweight, viewer-specific map read model. Never sends enemy army/resource data. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WorldMapService {
    public static final int CHUNK_SIZE = 16;
    private final WorldMapRepository worlds;
    private final NpcCityRepository npcs;
    private final PlayerCityRepository cities;
    private final BanditRepository bandits;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final WorldTerrainService terrain;

    public Map<String, Object> chunk(Long viewer, int cx, int cy) {
        int count = (WorldConfig.SIZE + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (cx < 0 || cy < 0 || cx >= count || cy >= count) throw new IllegalArgumentException("地图区域超出范围");
        Long world = worldId();
        List<Map<String, Object>> targets = new ArrayList<>();
        int x = cx * CHUNK_SIZE, y = cy * CHUNK_SIZE;
        int maxX = Math.min(x + CHUNK_SIZE - 1, WorldConfig.SIZE - 1);
        int maxY = Math.min(y + CHUNK_SIZE - 1, WorldConfig.SIZE - 1);
        if (world != null) {
            List<PlayerCity> cityList = cities.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(world, x, maxX, y, maxY);
            List<WildTile> wildList = wilds.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(world, x, maxX, y, maxY);
            Map<Long, Player> owners = new HashMap<>();
            Set<Long> ids = new HashSet<>();
            cityList.forEach(c -> { if (c.getOwnerId() != null) ids.add(c.getOwnerId()); });
            wildList.forEach(w -> {
                if (Boolean.TRUE.equals(w.getOccupied()) && w.getOccupiedBy() != null) ids.add(w.getOccupiedBy());
            });
            if (!ids.isEmpty()) players.findAllById(ids).forEach(p -> owners.put(p.getId(), p));
            cityList.stream().filter(c -> owners.get(c.getOwnerId()) == null ||
                    !owners.get(c.getOwnerId()).deletionDue(System.currentTimeMillis()))
                    .forEach(c -> targets.add(city(viewer, c, owners.get(c.getOwnerId()))));
            npcs.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(world, x, maxX, y, maxY).forEach(n -> {
                Map<String, Object> t = base("npc", n.getId(), n.getX(), n.getY(), n.getName(), n.getLevel());
                t.put("defeated", Boolean.TRUE.equals(n.getDefeated())); targets.add(t);
            });
            bandits.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(world, x, maxX, y, maxY).forEach(b -> {
                Map<String, Object> t = base("bandit", b.getId(), b.getX(), b.getY(), b.getName(), b.getLevel());
                t.put("defeated", Boolean.TRUE.equals(b.getDefeated())); targets.add(t);
            });
            wildList.forEach(w -> targets.add(wild(viewer, w, owners.get(w.getOccupiedBy()))));
        }
        return Map.of("cx", cx, "cy", cy, "size", CHUNK_SIZE, "worldSize", WorldConfig.SIZE,
                "targets", targets, "updatedAt", System.currentTimeMillis());
    }

    public Map<String, Object> target(Long viewer, String kind, Long id) {
        Long world = worldId();
        if (world == null) throw missing();
        switch (kind) {
            case "player", "simulated_npc" -> {
                PlayerCity c = cities.findById(id).filter(v -> world.equals(v.getWorldId())).orElseThrow(this::missing);
                Player owner = c.getOwnerId() == null ? null : players.findById(c.getOwnerId()).orElse(null);
                if (owner != null && owner.deletionDue(System.currentTimeMillis())) throw missing();
                Map<String, Object> t = city(viewer, c, owner);
                if (!kind.equals(t.get("kind"))) throw new IllegalArgumentException("目标归属已变化，请刷新地图");
                return t;
            }
            case "npc" -> {
                NpcCity n = npcs.findById(id).filter(v -> world.equals(v.getWorldId())).orElseThrow(this::missing);
                Map<String, Object> t = base(kind, id, n.getX(), n.getY(), n.getName(), n.getLevel());
                t.put("defeated", Boolean.TRUE.equals(n.getDefeated())); return t;
            }
            case "bandit" -> {
                Bandit b = bandits.findById(id).filter(v -> world.equals(v.getWorldId())).orElseThrow(this::missing);
                Map<String, Object> t = base(kind, id, b.getX(), b.getY(), b.getName(), b.getLevel());
                t.put("defeated", Boolean.TRUE.equals(b.getDefeated())); return t;
            }
            case "wild" -> {
                WildTile w = wilds.findById(id).filter(v -> world.equals(v.getWorldId())).orElseThrow(this::missing);
                Player owner = Boolean.TRUE.equals(w.getOccupied()) && w.getOccupiedBy() != null
                        ? players.findById(w.getOccupiedBy()).orElse(null) : null;
                Map<String, Object> t = wild(viewer, w, owner);
                // Only the owner receives live stock/defence. Other players use their scout reports.
                if (Boolean.TRUE.equals(t.get("occupied"))) {
                    t.put("totalRes", w.getTotalRes() == null ? 0 : w.getTotalRes());
                    t.put("mined", w.getMined() == null ? 0 : w.getMined());
                    t.put("garrison", JsonUtil.parseObjMap(w.getGarrison()));
                    t.put("scouted", true);
                    t.put("gathering", Boolean.TRUE.equals(w.getGathering()));
                    t.put("gatherStartAt", w.getGatherStartAt() == null ? 0L : w.getGatherStartAt());
                    t.put("gatherEndAt", w.getGatherEndAt() == null ? 0L : w.getGatherEndAt());
                    t.put("gatherLoad", w.getGatherLoad() == null ? 0 : w.getGatherLoad());
                    t.put("gatherRes", w.getGatherRes());
                }
                return t;
            }
            default -> throw new IllegalArgumentException("无效的地图目标类型");
        }
    }

    private Map<String, Object> city(Long viewer, PlayerCity c, Player owner) {
        Map<String, Object> t = base(owner == null ? "simulated_npc" : "player", c.getId(), c.getX(), c.getY(), c.getName(), owner == null ? c.getLevel() : null);
        boolean self = owner != null && viewer.equals(owner.getId());
        t.put("selfCity", self); t.put("mainCity", Integer.valueOf(0).equals(c.getCitySlot()));
        t.put("readyAt", c.getReadyAt() == null ? 0L : c.getReadyAt());
        t.put("defeated", false);
        t.put("coastal", terrain.coastal(c));
        t.put("legacyNaval", c.isLegacyNaval());
        if (owner != null) {
            t.put("ownerId", owner.getId());
            t.put("ownerName", owner.getUsername());
            t.put("prestige", owner.getPrestige() == null ? 0 : owner.getPrestige());
            boolean related = !self && viewer.equals(owner.getWarAgainstId());
            long warAt = related && owner.getWarAt() != null ? owner.getWarAt() : 0L;
            long warEnd = related && owner.getWarEndAt() != null ? owner.getWarEndAt() : 0L;
            t.put("warAt", warAt); t.put("warEndAt", warEnd);
            t.put("coolAt", warAt == 0 ? warEnd : 0L);
        }
        return t;
    }

    private Map<String, Object> wild(Long viewer, WildTile w, Player owner) {
        Map<String, Object> t = base("wild", w.getId(), w.getX(), w.getY(), w.getType(), w.getLevel());
        t.put("type", w.getType());
        t.put("occupied", Boolean.TRUE.equals(w.getOccupied()) && viewer.equals(w.getOccupiedBy()));
        t.put("claimed", Boolean.TRUE.equals(w.getOccupied()));
        if (Boolean.TRUE.equals(w.getOccupied()) && w.getOccupiedBy() != null) {
            t.put("ownerId", w.getOccupiedBy());
            if (owner != null) t.put("ownerName", owner.getUsername());
        }
        return t;
    }
    private Map<String, Object> base(String kind, Long id, int x, int y, String name, Integer level) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", kind); t.put("id", id); t.put("x", x); t.put("y", y);
        t.put("name", name == null ? "未知目标" : name);
        if (level != null) t.put("level", level);
        return t;
    }
    private Long worldId() { return worlds.findFirstByOrderByIdAsc().map(WorldMap::getId).orElse(null); }
    private IllegalArgumentException missing() { return new IllegalArgumentException("地图目标已不存在，请刷新地图"); }
}
