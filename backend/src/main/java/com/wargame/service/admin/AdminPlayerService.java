package com.wargame.service.admin;

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
    private final BuildingRepository buildingRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final PlayerItemRepository playerItemRepository;
    private final AdminAuditLogService auditLogService;

    public AdminPlayerService(PlayerRepository playerRepository,
                              ResourcesRepository resourcesRepository,
                              PlayerCityRepository playerCityRepository,
                              ArmyUnitRepository armyUnitRepository,
                              BuildingRepository buildingRepository,
                              TechnologyRepository technologyRepository,
                              OfficerRepository officerRepository,
                              PlayerItemRepository playerItemRepository,
                              AdminAuditLogService auditLogService) {
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.playerCityRepository = playerCityRepository;
        this.armyUnitRepository = armyUnitRepository;
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

        List<Building> buildings = buildingRepository.findByPlayerId(playerId);
        detail.put("buildings", buildings);

        List<Technology> technologies = technologyRepository.findByPlayerId(playerId);
        detail.put("technologies", technologies);

        List<Officer> officers = officerRepository.findByPlayerId(playerId);
        detail.put("officers", officers);

        List<PlayerItem> items = playerItemRepository.findByPlayerId(playerId);
        detail.put("items", items);

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
        if (data.containsKey("level") && data.get("level") != null) player.setLevel(((Number) data.get("level")).intValue());
        if (data.containsKey("militaryRank") && data.get("militaryRank") != null) player.setMilitaryRank(((Number) data.get("militaryRank")).intValue());
        if (data.containsKey("prestige") && data.get("prestige") != null) player.setPrestige(((Number) data.get("prestige")).intValue());
        if (data.containsKey("morale") && data.get("morale") != null) player.setMorale(((Number) data.get("morale")).intValue());
        if (data.containsKey("resentment") && data.get("resentment") != null) player.setResentment(((Number) data.get("resentment")).intValue());
        if (data.containsKey("posX") && data.get("posX") != null) player.setPosX(((Number) data.get("posX")).intValue());
        if (data.containsKey("posY") && data.get("posY") != null) player.setPosY(((Number) data.get("posY")).intValue());
        if (data.containsKey("cityPosX") && data.get("cityPosX") != null) player.setCityPosX(((Number) data.get("cityPosX")).intValue());
        if (data.containsKey("cityPosY") && data.get("cityPosY") != null) player.setCityPosY(((Number) data.get("cityPosY")).intValue());

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

        if (resUpdates.containsKey("gold")) res.setGold(resUpdates.get("gold"));
        if (resUpdates.containsKey("diamond")) res.setDiamond(resUpdates.get("diamond"));
        if (resUpdates.containsKey("food")) res.setFood(resUpdates.get("food"));
        if (resUpdates.containsKey("steel")) res.setSteel(resUpdates.get("steel"));
        if (resUpdates.containsKey("oil")) res.setOil(resUpdates.get("oil"));
        if (resUpdates.containsKey("rare")) res.setRare(resUpdates.get("rare"));

        resourcesRepository.save(res);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_RESOURCES", "PLAYER", String.valueOf(playerId),
                "修改玩家资源: " + JsonUtil.toJson(resUpdates), req);
    }

    @Transactional
    public void updatePlayerArmy(Long playerId, String type, Integer count, String adminUsername, HttpServletRequest req) {
        List<ArmyUnit> units = armyUnitRepository.findByPlayerIdAndType(playerId, type);
        ArmyUnit unit;
        if (units.isEmpty()) {
            unit = new ArmyUnit();
            unit.setPlayerId(playerId);
            unit.setType(type);
            unit.setCount(count);
            unit.setCitySlot(0);
        } else {
            unit = units.get(0);
            unit.setCount(count);
        }
        armyUnitRepository.save(unit);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_ARMY", "PLAYER", String.valueOf(playerId),
                String.format("修改兵力 %s -> %d", type, count), req);
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
        item.setCount(count);
        item.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(item);
        auditLogService.record(adminUsername, "UPDATE_PLAYER_ITEM", "PLAYER", String.valueOf(playerId),
                String.format("修改道具 %s 数量为 %d", itemKey, count), req);
    }
}
