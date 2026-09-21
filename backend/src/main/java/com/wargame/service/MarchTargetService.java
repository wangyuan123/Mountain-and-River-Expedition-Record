package com.wargame.service;

import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import java.util.*;

/** Target lookup and intelligence access, shared by dispatch and battle settlement. */
@Service
public class MarchTargetService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired private ResourcesRepository resourcesRepository;
    private final WildTileRepository wildTileRepository;
    private final BanditRepository banditRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final PlayerRepository playerRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final FortificationRepository fortificationRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final BuildingRepository buildingRepository;

    public MarchTargetService(WildTileRepository wildTileRepository, BanditRepository banditRepository, NpcCityRepository npcCityRepository, PlayerCityRepository playerCityRepository, PlayerRepository playerRepository, ArmyUnitRepository armyUnitRepository, FortificationRepository fortificationRepository, TechnologyRepository technologyRepository, OfficerRepository officerRepository, BuildingRepository buildingRepository) {
        this.wildTileRepository = wildTileRepository;
        this.banditRepository = banditRepository;
        this.npcCityRepository = npcCityRepository;
        this.playerCityRepository = playerCityRepository;
        this.playerRepository = playerRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.fortificationRepository = fortificationRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.buildingRepository = buildingRepository;
    }

    public WildTile findWildTileById(String targetId) {
        if (targetId == null) return null;
        try {
            Long id = Long.parseLong(targetId);
            return wildTileRepository.findById(id).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Object findTargetById(String kind, String targetId) {
        if (targetId == null) return null;
        try {
            Long id = Long.parseLong(targetId);
            return findTargetByLongId(kind, id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Object findTargetByLongId(String kind, Long id) {
        if (kind == null || id == null) return null;
        switch (kind) {
            case "wild":
            case "wild_gather":
                return wildTileRepository.findById(id).orElse(null);
            case "bandit":
                return banditRepository.findById(id).orElse(null);
            case "npc":
                return npcCityRepository.findById(id).orElse(null);
            case "simulated_npc":
                return playerCityRepository.findById(id).filter(pc -> !hasRealOwner(pc)).orElse(null);
            case "player":
                return playerCityRepository.findById(id).filter(this::hasRealOwner)
                        .filter(pc -> playerRepository.findById(pc.getOwnerId())
                                .map(p -> !p.deletionDue(System.currentTimeMillis())).orElse(false)).orElse(null);
            default:
                return null;
        }
    }

    public boolean hasRealOwner(PlayerCity city) {
        return city.getOwnerId() != null && playerRepository.existsById(city.getOwnerId());
    }

    public boolean isDefeated(Object target) {
        if (target instanceof Bandit b) return Boolean.TRUE.equals(b.getDefeated());
        if (target instanceof NpcCity n) return Boolean.TRUE.equals(n.getDefeated());
        return false;
    }

    // ===== 目标属性访问器 =====

    public Map<String, Integer> getTargetArmy(Object target) {
        if (target instanceof WildTile wt) return JsonUtil.parseIntMap(wt.getGarrison());
        if (target instanceof Bandit b) return JsonUtil.parseIntMap(b.getArmy());
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getArmy());
        if (target instanceof PlayerCity p && hasRealOwner(p)) { try (var ignored = cityScope.enter(p)) { return getArmyMap(p.getOwnerId()); } }
        return Collections.emptyMap();
    }

    public Map<String, Integer> getTargetForts(Object target) {
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getForts());
        if (target instanceof PlayerCity p) { if (hasRealOwner(p)) { try (var ignored = cityScope.enter(p)) { return getFortsMap(p.getOwnerId()); } } return JsonUtil.parseIntMap(p.getForts()); }
        return Collections.emptyMap();
    }

    public Map<String, Integer> getTargetResources(Object target) {
        if (target instanceof WildTile wt) {
            WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
            if (wtDef == null || wtDef.res() == null) return Collections.emptyMap();
            int remain = Math.max(0, (wt.getTotalRes() != null ? wt.getTotalRes() : 0)
                    - (wt.getMined() != null ? wt.getMined() : 0));
            return Map.of(wtDef.res(), remain);
        }
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getResources());
        if (target instanceof PlayerCity p) {
            if (hasRealOwner(p)) {
                Resources r = resourcesRepository.findByPlayerIdAndCitySlot(p.getOwnerId(), Objects.requireNonNullElse(p.getCitySlot(), 0)).orElse(null);
                if (r == null) return Collections.emptyMap();
                return Map.of("food", Objects.requireNonNullElse(r.getFood(), 0), "steel", Objects.requireNonNullElse(r.getSteel(), 0), "oil", Objects.requireNonNullElse(r.getOil(), 0), "rare", Objects.requireNonNullElse(r.getRare(), 0), "gold", Objects.requireNonNullElse(r.getGold(), 0));
            }
            return JsonUtil.parseIntMap(p.getResources());
        }
        return Collections.emptyMap();
    }

    public String getTargetName(Object target) {
        if (target instanceof WildTile wt) {
            WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
            return (wtDef != null ? wtDef.name() : "野地") + " Lv." + (wt.getLevel() != null ? wt.getLevel() : 1);
        }
        if (target instanceof Bandit b) return b.getName();
        if (target instanceof NpcCity n) return n.getName();
        if (target instanceof PlayerCity p) return p.getName();
        return "";
    }

    public Integer getTargetX(Object target) {
        if (target instanceof WildTile wt) return wt.getX();
        if (target instanceof Bandit b) return b.getX();
        if (target instanceof NpcCity n) return n.getX();
        if (target instanceof PlayerCity p) return p.getX();
        return 0;
    }

    public Integer getTargetY(Object target) {
        if (target instanceof WildTile wt) return wt.getY();
        if (target instanceof Bandit b) return b.getY();
        if (target instanceof NpcCity n) return n.getY();
        if (target instanceof PlayerCity p) return p.getY();
        return 0;
    }

    public Integer getTargetLevel(Object target) {
        if (target instanceof WildTile wt) return wt.getLevel();
        if (target instanceof Bandit b) return b.getLevel();
        if (target instanceof NpcCity n) return n.getLevel();
        if (target instanceof PlayerCity p) return p.getLevel();
        return 1;
    }

    public void setTargetScoutCount(Object target, int scoutCount) {
        if (target instanceof WildTile wt) {
            Map<String, Integer> army = JsonUtil.parseIntMap(wt.getGarrison());
            army.put("scout", scoutCount);
            wt.setGarrison(JsonUtil.toJson(army));
            wildTileRepository.save(wt);
        } else if (target instanceof PlayerCity pc && pc.getOwnerId() != null) {
            List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlotAndType(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0), "scout");
            ArmyUnit unit = units.isEmpty() ? new ArmyUnit(null, pc.getOwnerId(), "scout", 0) : units.get(0);
            unit.setCitySlot(Objects.requireNonNullElse(pc.getCitySlot(), 0));
            unit.setCount(Math.max(0, scoutCount));
            armyUnitRepository.save(unit);
        } else if (target instanceof NpcCity nc) {
            Map<String, Integer> army = JsonUtil.parseIntMap(nc.getArmy());
            army.put("scout", scoutCount);
            nc.setArmy(JsonUtil.toJson(army));
            npcCityRepository.save(nc);
        }
    }

    // ===== 玩家信息获取 =====

    public Map<String, Integer> getArmyMap(Long playerId) {
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ArmyUnit u : units) {
            int count = u.getCount() != null ? u.getCount() : 0;
            if (count > 0) map.put(u.getType(), count);
        }
        return map;
    }

    public Map<String, Integer> getFortsMap(Long playerId) {
        List<Fortification> forts = fortificationRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Fortification f : forts) {
            int count = f.getCount() != null ? f.getCount() : 0;
            if (count > 0) map.put(f.getType(), count);
        }
        return map;
    }

    public Map<String, Integer> getTechMap(Long playerId) {
        List<Technology> techs = technologyRepository.findByPlayerId(playerId);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Technology t : techs) {
            map.put(t.getType(), t.getLevel() != null ? t.getLevel() : 0);
        }
        return map;
    }

    public Officer getCommander(Long playerId) {
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "commander");
        return (officers != null && !officers.isEmpty()) ? officers.get(0) : null;
    }

    /** 根据 officerId 取具体军官（行军里的 commanderId 字段） */
    public Officer getOfficerById(Long playerId, Long officerId) {
        if (officerId == null) return null;
        return officerRepository.findById(officerId).filter(o -> playerId.equals(o.getPlayerId())).orElse(null);
    }

    /** 解析军官技能 JSON -> Map<skillId, level> - 对应 JS Core.getCommanderSkills */
    public Map<String, Integer> getCommanderSkills(Officer commander) {
        if (commander == null || commander.getSkills() == null || commander.getSkills().isBlank()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> skillList = JsonUtil.parseList(commander.getSkills());
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> skill : skillList) {
            Object id = skill.get("id");
            Object lv = skill.get("lv");
            if (id != null && lv != null) {
                map.put(id.toString(), ((Number) lv).intValue());
            }
        }
        return map;
    }

    public int buildingLevel(Long playerId, String type) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), type);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    public int getTechLevel(Long playerId, String techType) {
        if (playerId == null) return 0;
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        return (techs != null && !techs.isEmpty() && techs.get(0).getLevel() != null) ? techs.get(0).getLevel() : 0;
    }

    public Integer getTargetPrestige(Object target) {
        if (target instanceof PlayerCity pc) {
            return pc.getPrestige() != null ? pc.getPrestige() : 0;
        } else if (target instanceof NpcCity nc) {
            return (nc.getLevel() != null ? nc.getLevel() : 1) * 1000;
        }
        return null;
    }

    public String getDefenderCommanderName(Object target) {
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            Officer cmd = getCommander(pc.getOwnerId());
            if (cmd != null) {
                String starStr = cmd.getStar() != null && cmd.getStar() > 0 ? "★".repeat(cmd.getStar()) + " " : "";
                return starStr + cmd.getName() + " (Lv." + (cmd.getLevel() != null ? cmd.getLevel() : 1) + ")";
            }
            Optional<Player> p = playerRepository.findById(pc.getOwnerId());
            return p.map(player -> player.getUsername() + " (城主亲督)").orElse("城防卫戍官");
        } else if (target instanceof NpcCity nc) {
            return nc.getName() + "守备统领";
        }
        return "守军头目";
    }

    public Map<String, Integer> getTargetBuildingsMap(Object target) {
        Map<String, Integer> bMap = new LinkedHashMap<>();
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlot(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0));
            if (buildings != null) {
                for (Building b : buildings) {
                    int lv = b.getLevel() != null ? b.getLevel() : 0;
                    if (lv > 0) {
                        bMap.merge(b.getType(), lv, Integer::sum);
                    }
                }
            }
        } else if (target instanceof NpcCity nc) {
            int lv = nc.getLevel() != null ? nc.getLevel() : 1;
            bMap.put("command", lv);
            bMap.put("wall", Math.max(1, lv - 1));
            bMap.put("factory", Math.max(1, lv - 1));
            bMap.put("radar", Math.max(1, lv / 2));
        }
        return bMap;
    }

    public int getTargetOfficerCount(Object target) {
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0));
            return officers != null ? officers.size() : 0;
        } else if (target instanceof NpcCity nc) {
            return Math.max(1, (nc.getLevel() != null ? nc.getLevel() : 1) / 2);
        }
        return 0;
    }

    public Map<String, Integer> getTargetTechMap(Object target) {
        Map<String, Integer> tMap = new LinkedHashMap<>();
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            List<Technology> techs = technologyRepository.findByPlayerId(pc.getOwnerId());
            if (techs != null) {
                for (Technology t : techs) {
                    if (GameData.TECHS.containsKey(t.getType()) && t.getLevel() != null && t.getLevel() > 0) {
                        tMap.put(t.getType(), t.getLevel());
                    }
                }
            }
        } else if (target instanceof NpcCity nc) {
            int lv = nc.getLevel() != null ? nc.getLevel() : 1;
            tMap.put("attack_tech", Math.min(10, lv));
            tMap.put("defense_tech", Math.min(10, lv));
        }
        return tMap;
    }

    public int getWarehouseProtection(Object target) {
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            return buildingLevel(pc.getOwnerId(), "depot") * 1000;
        }
        return 0;
    }

    public Map<String, Integer> calculatePlunderable(Object target) {
        Map<String, Integer> res = getTargetResources(target);
        int protect = getWarehouseProtection(target);
        Map<String, Integer> plunderable = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : res.entrySet()) {
            plunderable.put(e.getKey(), Math.max(0, e.getValue() - protect));
        }
        return plunderable;
    }

    public List<Map<String, Object>> getTargetOfficerList(Object target) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (target instanceof PlayerCity pc && hasRealOwner(pc)) {
            List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0));
            if (officers != null) {
                for (Officer o : officers) {
                    Map<String, Object> oMap = new LinkedHashMap<>();
                    oMap.put("name", o.getName());
                    oMap.put("star", o.getStar() != null ? o.getStar() : 1);
                    oMap.put("level", o.getLevel() != null ? o.getLevel() : 1);
                    oMap.put("military", o.getMilitary() != null ? o.getMilitary() : 0);
                    oMap.put("defense", o.getDefense() != null ? o.getDefense() : 0);
                    oMap.put("knowledge", o.getKnowledge() != null ? o.getKnowledge() : 0);
                    oMap.put("logistics", o.getLogistics() != null ? o.getLogistics() : 0);
                    oMap.put("role", o.getRole() != null ? o.getRole() : "idle");
                    list.add(oMap);
                }
            }
        } else if (target instanceof NpcCity nc) {
            int cityLv = nc.getLevel() != null ? nc.getLevel() : 1;
            Map<String, Object> oMap = new LinkedHashMap<>();
            oMap.put("name", nc.getName() + "守备统领");
            oMap.put("star", Math.min(5, 1 + cityLv / 2));
            oMap.put("level", cityLv * 5);
            oMap.put("military", 50 + cityLv * 8);
            oMap.put("defense", 50 + cityLv * 8);
            oMap.put("knowledge", 40 + cityLv * 6);
            oMap.put("logistics", 40 + cityLv * 6);
            oMap.put("role", "commander");
            list.add(oMap);
        }
        return list;
    }

}
