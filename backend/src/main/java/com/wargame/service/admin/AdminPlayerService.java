package com.wargame.service.admin;

import com.wargame.config.VipConfig;
import com.wargame.model.constants.BuildingDef;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.MailService;
import com.wargame.service.VipService;
import com.wargame.util.JsonUtil;
import com.wargame.util.PlayerNames;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AdminPlayerService.class);

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
    private final VipService vipService;
    private final PlayerVipRewardRepository playerVipRewardRepository;
    private final PlayerVipMilestoneRepository playerVipMilestoneRepository;
    private final MailService mailService;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.service.AccountCredentialService credentials;

    public AdminPlayerService(PlayerRepository playerRepository,
                              ResourcesRepository resourcesRepository,
                              PlayerCityRepository playerCityRepository,
                              ArmyUnitRepository armyUnitRepository,
                              FortificationRepository fortificationRepository,
                              BuildingRepository buildingRepository,
                              TechnologyRepository technologyRepository,
                              OfficerRepository officerRepository,
                              PlayerItemRepository playerItemRepository,
                              AdminAuditLogService auditLogService,
                              VipService vipService,
                              PlayerVipRewardRepository playerVipRewardRepository,
                              PlayerVipMilestoneRepository playerVipMilestoneRepository,
                              MailService mailService) {
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
        this.vipService = vipService;
        this.playerVipRewardRepository = playerVipRewardRepository;
        this.playerVipMilestoneRepository = playerVipMilestoneRepository;
        this.mailService = mailService;
    }

    public Page<Map<String, Object>> getPlayers(String query, String status, int page, int size) {
        return getPlayers(query, status, null, page, size);
    }

    public Page<Map<String, Object>> getPlayers(String query, String status, Integer vipLevel, int page, int size) {
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

        if (vipLevel != null && vipLevel >= 0) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("vipLevel"), vipLevel));
        }

        Page<Player> playerPage = playerRepository.findAll(spec, pageRequest);
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Player p : playerPage.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("username", p.getUsername());
            map.put("plainPassword", p.getPlainPassword());
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
            int curVipLevel = p.getVipLevel() != null ? p.getVipLevel() : 0;
            map.put("vipLevel", curVipLevel);
            map.put("totalRechargeCents", p.getTotalRechargeCents() != null ? p.getTotalRechargeCents() : 0L);
            map.put("vipBadgeVisible", p.isVipBadgeVisible());
            map.put("avatarFrame", p.getAvatarFrame() != null ? p.getAvatarFrame() : "default");
            VipConfig.VipLevelDef vDef = VipConfig.getLevelDef(curVipLevel);
            map.put("vipTitle", vDef != null ? vDef.title() : "");
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

        try {
            detail.put("vipStatus", vipService.getVipStatus(playerId));
        } catch (Exception e) {
            detail.put("vipStatus", Collections.emptyMap());
        }

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
    public void resetPlayerPassword(Long playerId, String newPassword, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));
        String pwd = (newPassword != null && !newPassword.isBlank()) ? newPassword.trim() : "abcd1234";
        credentials.changePassword(player, pwd);
        player.setPlainPassword(pwd);
        playerRepository.save(player);
        auditLogService.record(adminUsername, "RESET_PLAYER_PASSWORD", "PLAYER", String.valueOf(playerId),
                String.format("已重置登录密码为 %s 并使全部旧会话失效", pwd), req);
    }

    @Transactional
    public void updatePlayerVip(Long playerId, Map<String, Object> data, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        int oldVipLevel = player.getVipLevel() != null ? player.getVipLevel() : 0;
        long oldTotalRecharge = player.getTotalRechargeCents() != null ? player.getTotalRechargeCents() : 0L;
        boolean forceOverride = Boolean.TRUE.equals(data.get("forceOverrideRecharge"));

        if (forceOverride) {
            // 高级强制覆盖模式（用于争议退款扣减、违规惩戒或特殊数据修正，无视门槛保护与已有充值保留规则）
            if (data.containsKey("vipLevel") && data.get("vipLevel") != null) {
                int newLevel = Math.max(0, Math.min(10, ((Number) data.get("vipLevel")).intValue()));
                player.setVipLevel(newLevel);
            }
            if (data.containsKey("totalRechargeCents") && data.get("totalRechargeCents") != null) {
                long cents = Math.max(0L, ((Number) data.get("totalRechargeCents")).longValue());
                player.setTotalRechargeCents(cents);
            }
        } else {
            // 常规模式：遵循已有充值保护与门槛自动补齐
            if (data.containsKey("vipLevel") && data.get("vipLevel") != null) {
                int newLevel = Math.max(0, Math.min(10, ((Number) data.get("vipLevel")).intValue()));
                player.setVipLevel(newLevel);
                long requiredCents = VipConfig.getLevelDef(newLevel).requiredCents();
                long currentCents = player.getTotalRechargeCents() != null ? player.getTotalRechargeCents() : 0L;

                // 调整 VIP 等级时强制同步累充门槛，同时严格保留玩家已有充值记录：
                // 1. 若玩家现有累充低于目标等级门槛，强制补齐至门槛；
                // 2. 若玩家已有充值已超出门槛，予以完整保留，不作扣减抹除；
                // 3. 若管理员显式指定了充值金额，以指定值为准（保底不低于该等级门槛）。
                if (data.containsKey("totalRechargeCents") && data.get("totalRechargeCents") != null) {
                    long inputCents = Math.max(0L, ((Number) data.get("totalRechargeCents")).longValue());
                    player.setTotalRechargeCents(Math.max(inputCents, requiredCents));
                } else {
                    if (currentCents < requiredCents) {
                        player.setTotalRechargeCents(requiredCents);
                    }
                }
            } else if (data.containsKey("totalRechargeCents") && data.get("totalRechargeCents") != null) {
                long cents = Math.max(0L, ((Number) data.get("totalRechargeCents")).longValue());
                player.setTotalRechargeCents(cents);
                if (Boolean.TRUE.equals(data.get("autoCalculateLevel"))) {
                    player.setVipLevel(VipConfig.calculateVipLevel(cents));
                }
            }
        }

        if (data.containsKey("vipBadgeVisible") && data.get("vipBadgeVisible") != null) {
            player.setVipBadgeVisible(Boolean.parseBoolean(String.valueOf(data.get("vipBadgeVisible"))));
        }

        if (data.containsKey("avatarFrame") && data.get("avatarFrame") != null) {
            player.setAvatarFrame(String.valueOf(data.get("avatarFrame")));
        }

        if (data.containsKey("customAvatar")) {
            Object ca = data.get("customAvatar");
            player.setCustomAvatar(ca != null ? String.valueOf(ca) : null);
        }

        if (Boolean.TRUE.equals(data.get("resetDailySalary"))) {
            player.setLastVipDailyRewardDate(null);
        }

        if (data.containsKey("storedDailySalariesCount") && data.get("storedDailySalariesCount") != null) {
            player.setStoredDailySalariesCount(Math.max(0, ((Number) data.get("storedDailySalariesCount")).intValue()));
        }

        if (Boolean.TRUE.equals(data.get("resetLevelRewards"))) {
            List<PlayerVipReward> claimedRewards = playerVipRewardRepository.findByPlayerId(playerId);
            if (!claimedRewards.isEmpty()) {
                playerVipRewardRepository.deleteAll(claimedRewards);
            }
        }

        if (Boolean.TRUE.equals(data.get("resetMilestones"))) {
            List<PlayerVipMilestone> claimedMilestones = playerVipMilestoneRepository.findByPlayerId(playerId);
            if (!claimedMilestones.isEmpty()) {
                playerVipMilestoneRepository.deleteAll(claimedMilestones);
            }
        }

        if (Boolean.TRUE.equals(data.get("revokeUnusedRewards"))) {
            revokeUnusedRewards(playerId, player.getVipLevel(), player.getTotalRechargeCents(), adminUsername, req);
        }

        playerRepository.save(player);

        String detail = String.format("更新玩家 %s(ID:%d) VIP特许%s: Lv.%d->Lv.%d, 累充分:%d->%d, 徽章:%s",
                player.getUsername(), playerId,
                forceOverride ? "【强制覆盖扣除模式】" : "",
                oldVipLevel, player.getVipLevel(),
                oldTotalRecharge, player.getTotalRechargeCents(),
                player.isVipBadgeVisible());
        auditLogService.record(adminUsername, "UPDATE_PLAYER_VIP", "PLAYER", String.valueOf(playerId), detail, req);
    }

    @Transactional
    public Map<String, Object> revokePlayerUnusedRewards(Long playerId, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));
        int lvl = player.getVipLevel() != null ? player.getVipLevel() : 0;
        long cents = player.getTotalRechargeCents() != null ? player.getTotalRechargeCents() : 0L;
        return revokeUnusedRewards(playerId, lvl, cents, adminUsername, req);
    }

    @Transactional
    public Map<String, Object> revokeUnusedRewards(Long playerId, int targetVipLevel, long targetTotalRechargeCents, String adminUsername, HttpServletRequest req) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: ID " + playerId));

        // 1. 查找超出目标 VIP 等级的已领等级礼包
        List<PlayerVipReward> claimedLevelRewards = playerVipRewardRepository.findByPlayerId(playerId);
        List<Integer> revokedLevels = new ArrayList<>();
        Map<String, Integer> itemsToReclaim = new HashMap<>();

        for (PlayerVipReward reward : claimedLevelRewards) {
            if (reward.getVipLevel() > targetVipLevel) {
                revokedLevels.add(reward.getVipLevel());
                VipConfig.VipLevelDef def = VipConfig.getLevelDef(reward.getVipLevel());
                if (def != null && def.levelPackRewards() != null) {
                    def.levelPackRewards().forEach((k, v) -> itemsToReclaim.merge(k, v, Integer::sum));
                }
            }
        }

        // 2. 查找超出目标累计充值的已领里程碑宝箱
        List<PlayerVipMilestone> claimedMilestones = playerVipMilestoneRepository.findByPlayerId(playerId);
        List<Long> revokedMilestones = new ArrayList<>();

        for (PlayerVipMilestone milestone : claimedMilestones) {
            if (milestone.getMilestoneCents() > targetTotalRechargeCents) {
                revokedMilestones.add(milestone.getMilestoneCents());
                VipConfig.VipMilestoneDef def = VipConfig.getMilestoneDef(milestone.getMilestoneCents());
                if (def != null && def.rewards() != null) {
                    def.rewards().forEach((k, v) -> itemsToReclaim.merge(k, v, Integer::sum));
                }
            }
        }

        // 3. 执行“已消耗的暂不回收，未消耗的回收”
        Map<String, Integer> actuallyReclaimed = new LinkedHashMap<>();
        Resources res = resourcesRepository.findFirstByPlayerIdOrderByCitySlotAsc(playerId).orElse(null);
        boolean resChanged = false;

        for (Map.Entry<String, Integer> entry : itemsToReclaim.entrySet()) {
            String key = entry.getKey();
            int amount = entry.getValue();
            if (amount <= 0) continue;

            if (res != null && Set.of("gold", "food", "steel", "oil", "rare").contains(key)) {
                long currentVal = switch (key) {
                    case "gold" -> Objects.requireNonNullElse(res.getGold(), 0L);
                    case "food" -> Objects.requireNonNullElse(res.getFood(), 0L);
                    case "steel" -> Objects.requireNonNullElse(res.getSteel(), 0L);
                    case "oil" -> Objects.requireNonNullElse(res.getOil(), 0L);
                    case "rare" -> Objects.requireNonNullElse(res.getRare(), 0L);
                    default -> 0L;
                };
                if (currentVal > 0) {
                    long deduct = Math.min(currentVal, (long) amount);
                    switch (key) {
                        case "gold" -> res.setGold(currentVal - deduct);
                        case "food" -> res.setFood(currentVal - deduct);
                        case "steel" -> res.setSteel(currentVal - deduct);
                        case "oil" -> res.setOil(currentVal - deduct);
                        case "rare" -> res.setRare(currentVal - deduct);
                    }
                    actuallyReclaimed.put(key, (int) deduct);
                    resChanged = true;
                }
            } else {
                // 背包道具：若当前库存中有剩余，回收 min(库存, 发放量)；若玩家已消耗使用（库存为0或少于发放量），仅回收剩余库存，不扣为负数
                PlayerItem item = playerItemRepository.findByPlayerIdAndItemKey(playerId, key).orElse(null);
                if (item != null && item.getCount() != null && item.getCount() > 0) {
                    int currentCount = item.getCount();
                    int deduct = Math.min(currentCount, amount);
                    int remaining = currentCount - deduct;
                    if (remaining > 0) {
                        item.setCount(remaining);
                        item.setUpdatedAt(System.currentTimeMillis());
                        playerItemRepository.save(item);
                    } else {
                        playerItemRepository.delete(item);
                    }
                    actuallyReclaimed.put(key, deduct);
                }
            }
        }

        if (resChanged && res != null) {
            resourcesRepository.save(res);
        }

        // 4. 撤销/删除被收回的礼包与里程碑领取记录
        for (PlayerVipReward reward : claimedLevelRewards) {
            if (revokedLevels.contains(reward.getVipLevel())) {
                playerVipRewardRepository.delete(reward);
            }
        }
        for (PlayerVipMilestone milestone : claimedMilestones) {
            if (revokedMilestones.contains(milestone.getMilestoneCents())) {
                playerVipMilestoneRepository.delete(milestone);
            }
        }

        // 5. 下发军纪检查与调拨告知邮件
        try {
            StringBuilder mailBody = new StringBuilder();
            mailBody.append("尊敬的指挥官 ").append(PlayerNames.commanderName(player)).append("：\n\n");
            mailBody.append("根据最高军政特勤条例与军委会审计规程，您的特许委任及战备配给已完成阶段性重定与核准。\n");
            mailBody.append("当前特许评级：VIP ").append(player.getVipLevel()).append("（有效累充战费：¥").append(player.getTotalRechargeCents() / 100).append("）。\n\n");

            if (!revokedLevels.isEmpty() || !revokedMilestones.isEmpty()) {
                mailBody.append("【特许资格变更】\n");
                if (!revokedLevels.isEmpty()) {
                    mailBody.append("· 撤销超出当前评级的等级特权礼包资格：VIP ").append(revokedLevels).append("\n");
                }
                if (!revokedMilestones.isEmpty()) {
                    List<String> milestoneYuan = revokedMilestones.stream().map(c -> "¥" + (c / 100)).toList();
                    mailBody.append("· 撤销超出当前战费的战略里程碑奖励资格：").append(milestoneYuan).append("\n");
                }
                mailBody.append("注：后续战费追加达到对应标准时，对应档位资格将即时解冻并恢复领取。\n\n");
            }

            mailBody.append("【库存物资调拨与回收明细】\n");
            if (actuallyReclaimed.isEmpty()) {
                mailBody.append("经军械库与粮饷仓储核查，超出档位的配给物资已在战役中消耗殆尽，遵照军委会抚恤与保护原则，未予扣减负数。\n");
            } else {
                mailBody.append("经核查，系统已从您的基地储备中收回下列尚未消耗的特许战备物资：\n");
                for (Map.Entry<String, Integer> entry : actuallyReclaimed.entrySet()) {
                    mailBody.append("· ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
                }
                mailBody.append("（声明：已投入建设或战场消耗之战略物资免于追缴，绝不扣为负数）。\n");
            }
            mailBody.append("\n最高军委会纪律检查部 谨启");

            mailService.sendSystem(playerId, "最高军委会纪律检查部", "system",
                    "📋 军务纪律部·战备物资调拨与特许调整告知书", mailBody.toString(), null);
        } catch (Exception e) {
            log.error("发送VIP物资回收通知邮件失败: playerId={}", playerId, e);
        }

        String detail = String.format("玩家 %s(ID:%d) 回收超出档位道具: 撤销VIP等级礼包%s, 撤销里程碑%s, 实际回收未消耗道具: %s",
                player.getUsername(), playerId, revokedLevels, revokedMilestones, actuallyReclaimed);
        auditLogService.record(adminUsername, "REVOKE_UNCLAIMED_VIP_REWARDS", "PLAYER", String.valueOf(playerId), detail, req);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("revokedLevels", revokedLevels);
        result.put("revokedMilestones", revokedMilestones);
        result.put("actuallyReclaimed", actuallyReclaimed);
        result.put("message", String.format("成功回收 %d 种未消耗战略物资，撤销 %d 个等级礼包与 %d 个里程碑领奖资格",
                actuallyReclaimed.size(), revokedLevels.size(), revokedMilestones.size()));
        return result;
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

        if (resUpdates.containsKey("gold") && resUpdates.get("gold") != null) res.setGold(Math.max(0, resUpdates.get("gold")));
        if (resUpdates.containsKey("diamond") && resUpdates.get("diamond") != null) res.setDiamond(Math.max(0, Math.min(1_000_000_000, resUpdates.get("diamond"))));
        if (resUpdates.containsKey("food") && resUpdates.get("food") != null) res.setFood(Math.max(0, resUpdates.get("food")));
        if (resUpdates.containsKey("steel") && resUpdates.get("steel") != null) res.setSteel(Math.max(0, resUpdates.get("steel")));
        if (resUpdates.containsKey("oil") && resUpdates.get("oil") != null) res.setOil(Math.max(0, resUpdates.get("oil")));
        if (resUpdates.containsKey("rare") && resUpdates.get("rare") != null) res.setRare(Math.max(0, resUpdates.get("rare")));

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
