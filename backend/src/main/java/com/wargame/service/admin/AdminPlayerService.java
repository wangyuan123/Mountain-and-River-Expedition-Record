package com.wargame.service.admin;

import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AdminPlayerService {

    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final PlayerCityRepository playerCityRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final FortificationRepository fortificationRepository;
    private final BuildingRepository buildingRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final PlayerItemRepository playerItemRepository;
    private final AdminAuditLogService auditLogService;

    public AdminPlayerService(PlayerRepository playerRepository,
                              ResourcesRepository resourcesRepository,
                              PlayerCityRepository playerCityRepository,
                              ArmyUnitRepository armyUnitRepository,
                              FortificationRepository fortificationRepository,
                              BuildingRepository buildingRepository,
                              TechnologyRepository technologyRepository,
                              OfficerRepository officerRepository,
                              PlayerItemRepository playerItemRepository,
                              AdminAuditLogService auditLogService) {
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.playerCityRepository = playerCityRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.fortificationRepository = fortificationRepository;
        this.buildingRepository = buildingRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.playerItemRepository = playerItemRepository;
        this.auditLogService = auditLogService;
    }

    public Page<Map<String, Object>> getPlayers(String query, String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Specification<Player> spec = Specification.where(null);

        if (query != null && !query.isBlank()) {
            String q = "%" + query.trim() + "%";
            spec = spec.and((root, cq, cb) -> cb.or(
                    cb.like(root.get("username"), q),
                    cb.like(root.get("displayName"), q),
                    cb.like(root.get("cityName"), q)
            ));
        }

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            if ("BANNED".equalsIgnoreCase(status)) {
                spec = spec.and((root, cq, cb) -> cb.equal(root.get("banStatus"), "BANNED"));
            } else if ("NORMAL".equalsIgnoreCase(status)) {
                spec = spec.and((root, cq, cb) -> cb.equal(root.get("banStatus"), "NORMAL"));
            }
        }

        Page<Player> playerPage = playerRepository.findAll(spec, pageRequest);
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Player p : playerPage.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("username", p.getUsername());
            map.put("displayName", p.getDisplayName() != null ? p.getDisplayName() : p.getUsername());
            map.put("cityName", p.getCityName());
            map.put("faction", p.getFaction());
            map.put("level", p.getLevel());
            map.put("militaryRank", p.getMilitaryRank());
            map.put("prestige", p.getPrestige());
            map.put("posX", p.getPosX());
            map.put("posY", p.getPosY());
            map.put("cityPosX", p.getCityPosX());
            map.put("cityPosY", p.getCityPosY());
            map.put("banStatus", p.getBanStatus());
            map.put("banReason", p.getBanReason());
            map.put("bannedUntil", p.getBannedUntil());
            map.put("isBanned", p.isBanned(now));
            map.put("disabled", p.getDisabled());
            map.put("accountStatus", p.getAccountStatus());
            map.put("createdAt", p.getCreatedAt());

            resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(p.getId()).ifPresent(r -> {
                map.put("gold", r.getGold());
                map.put("diamond", r.getDiamond());
            });

            list.add(map);
        }

        return new PageImpl<>(list, pageRequest, playerPage.getTotalElements());
    }

    public Map<String, Object> getPlayerDetail(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("player", player);
        detail.put("isBanned", player.isBanned(System.currentTimeMillis()));

        Resources res = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(playerId).orElse(null);
        detail.put("resources", res);

        List<PlayerCity> cities = playerCityRepository.findByOwnerId(playerId);
        detail.put("cities", cities);

        List<ArmyUnit> armyUnits = armyUnitRepository.findByPlayerId(playerId);
        detail.put("armyUnits", armyUnits);

        List<Building> rawBuildings = buildingRepository.findByPlayerId(playerId);
        List<Map<String, Object>> buildingList = new ArrayList<>();
        for (Building b : rawBuildings) {
            BuildingDef def = BuildingDef.BUILDINGS.get(b.getType());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", b.getId());
            item.put("playerId", b.getPlayerId());
            item.put("citySlot", b.getCitySlot() != null ? b.getCitySlot() : 0);
            item.put("type", b.getType());
            item.put("name", def != null ? def.name() : b.getType());
            item.put("desc", def != null ? def.desc() : "");
            item.put("level", b.getLevel() != null ? b.getLevel() : 1);
            item.put("slot", b.getSlot() != null ? b.getSlot() : 0);
            buildingList.add(item);
        }
        detail.put("buildings", buildingList);

        List<Technology> rawTechs = technologyRepository.findByPlayerId(playerId);
        Map<String, Technology> techMap = new HashMap<>();
        for (Technology t : rawTechs) {
            techMap.put(t.getType(), t);
        }
        List<Map<String, Object>> techList = new ArrayList<>();
        for (Map.Entry<String, TechDef> entry : TechDef.TECHS.entrySet()) {
            String key = entry.getKey();
            TechDef def = entry.getValue();
            Technology t = techMap.get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", key);
            item.put("name", def.name());
            item.put("desc", def.desc());
            item.put("branch", def.branch());
            item.put("maxLevel", def.maxLevel());
            item.put("level", t != null && t.getLevel() != null ? t.getLevel() : 0);
            techList.add(item);
        }
        detail.put("technologies", techList);

        List<Officer> officers = officerRepository.findByPlayerId(playerId);
        detail.put("officers", officers);

        List<PlayerItem> items = playerItemRepository.findByPlayerId(playerId);
        detail.put("items", items);

        List<Fortification> forts = fortificationRepository.findByPlayerId(playerId);
        Map<String, Fortification> fortMap = new HashMap<>();
        for (Fortification f : forts) {
            fortMap.put(f.getType(), f);
        }
        List<Map<String, Object>> fortList = new ArrayList<>();
        for (String key : List.of("bunker", "howitzer", "antitank", "flak")) {
            FortDef def = FortDef.FORTS.get(key);
            Fortification f = fortMap.get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", key);
            item.put("name", def != null ? def.name() : key);
            item.put("desc", def != null ? def.desc() : "");
            item.put("count", f != null && f.getCount() != null ? f.getCount() : 0);
            fortList.add(item);
        }
        detail.put("fortifications", fortList);

        return detail;
    }

    @Transactional
    public void banPlayer(Long playerId, String reason, Long durationHours, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        player.setBanStatus("BANNED");
        player.setBanReason(reason != null ? reason.trim() : "违规操作，予以封号");
        if (durationHours != null && durationHours > 0) {
            player.setBannedUntil(System.currentTimeMillis() + durationHours * 3600_000L);
        } else {
            player.setBannedUntil(0L); // 永久
        }
        // 关键：递增 authVersion 强制失效该玩家当前登录的 JWT Token，即刻踢下线
        player.setAuthVersion(player.getAuthVersion() + 1);
        playerRepository.save(player);

        String detail = String.format("封禁玩家 %s(ID:%d)，时长:%s，原因:%s",
                player.getUsername(), playerId, durationHours != null && durationHours > 0 ? durationHours + "小时" : "永久", player.getBanReason());
        auditLogService.record(adminUsername, "BAN_PLAYER", "PLAYER", String.valueOf(playerId), detail, req);
    }

    @Transactional
    public void unbanPlayer(Long playerId, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        player.setBanStatus("NORMAL");
        player.setBanReason(null);
        player.setBannedUntil(null);
        playerRepository.save(player);

        auditLogService.record(adminUsername, "UNBAN_PLAYER", "PLAYER", String.valueOf(playerId),
                "解封玩家 " + player.getUsername() + "(ID:" + playerId + ")", req);
    }

    @Transactional
    public void updatePlayerBasic(Long playerId, Map<String, Object> data, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        if (data.containsKey("displayName")) player.setDisplayName((String) data.get("displayName"));
        if (data.containsKey("cityName")) player.setCityName((String) data.get("cityName"));
        if (data.containsKey("militaryRank") && data.get("militaryRank") != null) {
            int rank = ((Number) data.get("militaryRank")).intValue();
            player.setMilitaryRank(Math.max(1, Math.min(17, rank)));
        }
        if (data.containsKey("prestige") && data.get("prestige") != null) {
            int prestige = ((Number) data.get("prestige")).intValue();
            player.setPrestige(Math.max(0, Math.min(1_000_000_000, prestige)));
        }
        if (data.containsKey("morale") && data.get("morale") != null) {
            player.setMorale(Math.max(0, Math.min(100, ((Number) data.get("morale")).intValue())));
        }
        if (data.containsKey("resentment") && data.get("resentment") != null) {
            player.setResentment(Math.max(0, Math.min(100, ((Number) data.get("resentment")).intValue())));
        }
        if (data.containsKey("cityPosX") && data.get("cityPosX") != null) {
            player.setCityPosX(Math.max(0, Math.min(799, ((Number) data.get("cityPosX")).intValue())));
        }
        if (data.containsKey("cityPosY") && data.get("cityPosY") != null) {
            player.setCityPosY(Math.max(0, Math.min(799, ((Number) data.get("cityPosY")).intValue())));
        }

        playerRepository.save(player);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_BASIC", "PLAYER", String.valueOf(playerId),
                "修改玩家基础数据: " + JsonUtil.toJson(data), req);
    }

    @Transactional
    public void updatePlayerResources(Long playerId, Map<String, Integer> resUpdates, String adminUsername, HttpServletRequest req) {
        Resources res = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(playerId)
                .orElseGet(() -> {
                    Resources r = new Resources();
                    r.setPlayerId(playerId);
                    r.setCitySlot(0);
                    r.setFood(0); r.setSteel(0); r.setOil(0); r.setRare(0); r.setGold(0); r.setDiamond(0);
                    return r;
                });

        if (resUpdates.containsKey("gold") && resUpdates.get("gold") != null) res.setGold(Math.max(0, Math.min(1_000_000_000, resUpdates.get("gold"))));
        if (resUpdates.containsKey("diamond") && resUpdates.get("diamond") != null) res.setDiamond(Math.max(0, Math.min(1_000_000_000, resUpdates.get("diamond"))));
        if (resUpdates.containsKey("food") && resUpdates.get("food") != null) res.setFood(Math.max(0, Math.min(1_000_000_000, resUpdates.get("food"))));
        if (resUpdates.containsKey("steel") && resUpdates.get("steel") != null) res.setSteel(Math.max(0, Math.min(1_000_000_000, resUpdates.get("steel"))));
        if (resUpdates.containsKey("oil") && resUpdates.get("oil") != null) res.setOil(Math.max(0, Math.min(1_000_000_000, resUpdates.get("oil"))));
        if (resUpdates.containsKey("rare") && resUpdates.get("rare") != null) res.setRare(Math.max(0, Math.min(1_000_000_000, resUpdates.get("rare"))));

        resourcesRepository.save(res);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_RESOURCES", "PLAYER", String.valueOf(playerId),
                "修改玩家资源: " + JsonUtil.toJson(resUpdates), req);
    }

    @Transactional
    public void updatePlayerArmy(Long playerId, String type, Integer count, String adminUsername, HttpServletRequest req) {
        updatePlayerArmy(playerId, null, 0, type, count, adminUsername, req);
    }

    @Transactional
    public void updatePlayerArmy(Long playerId, Long unitId, Integer citySlot, String type, Integer count, String adminUsername, HttpServletRequest req) {
        ArmyUnit unit = null;
        if (unitId != null && unitId > 0) {
            unit = armyUnitRepository.findById(unitId).orElse(null);
            if (unit != null && !unit.getPlayerId().equals(playerId)) {
                unit = null;
            }
        }
        if (unit == null && type != null && !type.isBlank()) {
            int slot = citySlot != null ? citySlot : 0;
            List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, slot, type);
            if (!units.isEmpty()) {
                unit = units.get(0);
            } else {
                unit = new ArmyUnit();
                unit.setPlayerId(playerId);
                unit.setType(type);
                unit.setCitySlot(slot);
            }
        }
        if (unit == null) {
            throw new IllegalArgumentException("未找到待修改的兵种记录");
        }
        int finalCount = Math.max(0, Math.min(1_000_000_000, count != null ? count : 0));
        unit.setCount(finalCount);
        armyUnitRepository.save(unit);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_ARMY", "PLAYER", String.valueOf(playerId),
                String.format("修改城池(Slot:%d)兵力 %s(ID:%d) -> %d", unit.getCitySlot(), unit.getType(), unit.getId(), unit.getCount()), req);
    }

    @Transactional
    public void updatePlayerArmiesBatch(Long playerId, List<Map<String, Object>> armies, String adminUsername, HttpServletRequest req) {
        if (armies == null || armies.isEmpty()) return;
        StringBuilder auditDetail = new StringBuilder("批量修改兵力: ");
        int updatedCount = 0;
        for (Map<String, Object> item : armies) {
            Long unitId = item.get("id") != null ? ((Number) item.get("id")).longValue() : null;
            Integer citySlot = item.get("citySlot") != null ? ((Number) item.get("citySlot")).intValue() : null;
            String type = (String) item.get("type");
            int count = item.get("count") != null ? ((Number) item.get("count")).intValue() : 0;
            count = Math.max(0, Math.min(1_000_000_000, count));

            ArmyUnit unit = null;
            if (unitId != null && unitId > 0) {
                unit = armyUnitRepository.findById(unitId).orElse(null);
                if (unit != null && !unit.getPlayerId().equals(playerId)) {
                    unit = null;
                }
            }
            if (unit == null && type != null && !type.isBlank()) {
                int slot = citySlot != null ? citySlot : 0;
                List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, slot, type);
                if (!units.isEmpty()) {
                    unit = units.get(0);
                } else {
                    unit = new ArmyUnit();
                    unit.setPlayerId(playerId);
                    unit.setType(type);
                    unit.setCitySlot(slot);
                }
            }
            if (unit != null) {
                unit.setCount(count);
                armyUnitRepository.save(unit);
                if (updatedCount < 5) {
                    auditDetail.append(String.format("Slot%d-%s=%d, ", unit.getCitySlot(), unit.getType(), count));
                }
                updatedCount++;
            }
        }
        if (updatedCount > 5) {
            auditDetail.append(String.format("等共 %d 项", updatedCount));
        } else if (auditDetail.toString().endsWith(", ")) {
            auditDetail.setLength(auditDetail.length() - 2);
        }
        auditLogService.record(adminUsername, "UPDATE_PLAYER_ARMY_BATCH", "PLAYER", String.valueOf(playerId),
                auditDetail.toString(), req);
    }

    @Transactional
    public void updatePlayerFortification(Long playerId, String type, Integer count, String adminUsername, HttpServletRequest req) {
        count = Math.max(0, Math.min(1_000_000_000, count != null ? count : 0));
        List<Fortification> existing = fortificationRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, type);
        Fortification fort;
        if (existing.isEmpty()) {
            fort = new Fortification();
            fort.setPlayerId(playerId);
            fort.setCitySlot(0);
            fort.setType(type);
            fort.setCount(count);
        } else {
            fort = existing.get(0);
            fort.setCount(count);
        }
        fortificationRepository.save(fort);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_FORTIFICATION", "PLAYER", String.valueOf(playerId),
                String.format("修改城防工事 %s -> %d", type, count), req);
    }

    @Transactional
    public void updatePlayerFortificationsBatch(Long playerId, List<Map<String, Object>> forts, String adminUsername, HttpServletRequest req) {
        if (forts == null || forts.isEmpty()) return;
        StringBuilder auditDetail = new StringBuilder("批量修改城防工事: ");
        int updatedCount = 0;
        for (Map<String, Object> item : forts) {
            String type = (String) item.get("type");
            if (type == null || type.isBlank()) continue;
            int count = item.get("count") != null ? ((Number) item.get("count")).intValue() : 0;
            count = Math.max(0, Math.min(1_000_000_000, count));

            List<Fortification> existing = fortificationRepository.findByPlayerIdAndCitySlotAndType(playerId, 0, type);
            Fortification fort;
            if (existing.isEmpty()) {
                fort = new Fortification();
                fort.setPlayerId(playerId);
                fort.setCitySlot(0);
                fort.setType(type);
                fort.setCount(count);
            } else {
                fort = existing.get(0);
                fort.setCount(count);
            }
            fortificationRepository.save(fort);
            auditDetail.append(String.format("%s=%d, ", type, count));
            updatedCount++;
        }
        if (auditDetail.toString().endsWith(", ")) {
            auditDetail.setLength(auditDetail.length() - 2);
        }
        auditLogService.record(adminUsername, "UPDATE_PLAYER_FORTIFICATION_BATCH", "PLAYER", String.valueOf(playerId),
                auditDetail.toString(), req);
    }

    @Transactional
    public void updatePlayerBuilding(Long playerId, Long buildingId, Integer level, String adminUsername, HttpServletRequest req) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new IllegalArgumentException("建筑不存在"));
        if (!building.getPlayerId().equals(playerId)) {
            throw new IllegalArgumentException("建筑不属于该玩家");
        }
        building.setLevel(level);
        buildingRepository.save(building);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_BUILDING", "BUILDING", String.valueOf(buildingId),
                String.format("修改建筑 %s 等级为 %d", building.getType(), level), req);
    }

    @Transactional
    public void updatePlayerTechnology(Long playerId, String techType, Integer level, String adminUsername, HttpServletRequest req) {
        List<Technology> techs = technologyRepository.findByPlayerIdAndType(playerId, techType);
        Technology tech;
        if (techs.isEmpty()) {
            tech = new Technology();
            tech.setPlayerId(playerId);
            tech.setType(techType);
            tech.setLevel(level);
        } else {
            tech = techs.get(0);
            tech.setLevel(level);
        }
        technologyRepository.save(tech);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_TECH", "PLAYER", String.valueOf(playerId),
                String.format("修改科技 %s 等级为 %d", techType, level), req);
    }

    @Transactional
    public void updatePlayerItem(Long playerId, String itemKey, Integer count, String adminUsername, HttpServletRequest req) {
        PlayerItem item = playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey)
                .orElseGet(() -> {
                    PlayerItem pi = new PlayerItem();
                    pi.setPlayerId(playerId);
                    pi.setItemKey(itemKey);
                    pi.setCount(0);
                    pi.setUpdatedAt(System.currentTimeMillis());
                    return pi;
                });
        if (count == null || count <= 0) {
            if (item.getId() != null) {
                playerItemRepository.delete(item);
            }
        } else {
            item.setCount(count);
            item.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(item);
        }
        auditLogService.record(adminUsername, "UPDATE_PLAYER_ITEM", "PLAYER", String.valueOf(playerId),
                String.format("修改道具 %s 数量为 %d", itemKey, count != null ? count : 0), req);
    }

    @Transactional
    public void deletePlayerItem(Long playerId, String itemKey, String adminUsername, HttpServletRequest req) {
        playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey).ifPresent(playerItemRepository::delete);
        auditLogService.record(adminUsername, "DELETE_PLAYER_ITEM", "PLAYER", String.valueOf(playerId),
                String.format("清空删除道具 %s", itemKey), req);
    }

    @Transactional
    public int updatePlayerItemsBatch(Long playerId, List<Map<String, Object>> itemsList, String adminUsername, HttpServletRequest req) {
        if (itemsList == null || itemsList.isEmpty()) return 0;
        int modified = 0;
        StringBuilder sb = new StringBuilder("批量调整道具: ");
        for (Map<String, Object> m : itemsList) {
            String key = (String) m.get("itemKey");
            if (key == null || key.isBlank()) continue;
            int num = m.get("count") != null ? ((Number) m.get("count")).intValue() : 0;
            PlayerItem item = playerItemRepository.findByPlayerIdAndItemKey(playerId, key)
                    .orElseGet(() -> {
                        PlayerItem pi = new PlayerItem();
                        pi.setPlayerId(playerId);
                        pi.setItemKey(key);
                        pi.setCount(0);
                        pi.setUpdatedAt(System.currentTimeMillis());
                        return pi;
                    });
            if (num <= 0) {
                if (item.getId() != null) {
                    playerItemRepository.delete(item);
                }
            } else {
                item.setCount(num);
                item.setUpdatedAt(System.currentTimeMillis());
                playerItemRepository.save(item);
            }
            sb.append(key).append("=").append(num).append("; ");
            modified++;
        }
        auditLogService.record(adminUsername, "BATCH_UPDATE_PLAYER_ITEMS", "PLAYER", String.valueOf(playerId),
                sb.toString(), req);
        return modified;
    }

    @Transactional
    public int maxAllPlayerBuildings(Long playerId, Integer targetLevel, Integer citySlot, String adminUsername, HttpServletRequest req) {
        int target = targetLevel != null && targetLevel > 0 ? targetLevel : 10;
        List<Building> buildings = buildingRepository.findByPlayerId(playerId);
        if (citySlot != null) {
            buildings = buildings.stream().filter(b -> Objects.equals(b.getCitySlot(), citySlot)).toList();
        }
        for (Building b : buildings) {
            b.setLevel(target);
        }
        buildingRepository.saveAll(buildings);
        auditLogService.record(adminUsername, "MAX_ALL_BUILDINGS", "PLAYER", String.valueOf(playerId),
                String.format("一键满级所有建筑为 %d 级 (共 %d 座建筑)", target, buildings.size()), req);
        return buildings.size();
    }

    @Transactional
    public int maxAllPlayerTechnologies(Long playerId, String adminUsername, HttpServletRequest req) {
        List<Technology> existing = technologyRepository.findByPlayerId(playerId);
        Map<String, Technology> techMap = new HashMap<>();
        for (Technology t : existing) {
            techMap.put(t.getType(), t);
        }
        List<Technology> toSave = new ArrayList<>();
        for (Map.Entry<String, TechDef> entry : TechDef.TECHS.entrySet()) {
            String key = entry.getKey();
            int maxLevel = entry.getValue().maxLevel();
            Technology tech = techMap.get(key);
            if (tech == null) {
                tech = new Technology();
                tech.setPlayerId(playerId);
                tech.setType(key);
            }
            tech.setLevel(maxLevel);
            toSave.add(tech);
        }
        technologyRepository.saveAll(toSave);
        auditLogService.record(adminUsername, "MAX_ALL_TECHS", "PLAYER", String.valueOf(playerId),
                String.format("一键满级全部科技 (共 %d 项)", toSave.size()), req);
        return toSave.size();
    }

    @Transactional
    public int resetAllPlayerTechnologies(Long playerId, String adminUsername, HttpServletRequest req) {
        List<Technology> existing = technologyRepository.findByPlayerId(playerId);
        Map<String, Technology> techMap = new HashMap<>();
        for (Technology t : existing) {
            techMap.put(t.getType(), t);
        }
        List<Technology> toSave = new ArrayList<>();
        for (String key : TechDef.TECHS.keySet()) {
            Technology tech = techMap.get(key);
            if (tech != null) {
                tech.setLevel(0);
                toSave.add(tech);
            }
        }
        technologyRepository.saveAll(toSave);
        auditLogService.record(adminUsername, "RESET_ALL_TECHS", "PLAYER", String.valueOf(playerId),
                String.format("一键重置全部科技为 Lv.0 (共 %d 项)", toSave.size()), req);
        return toSave.size();
    }
}
