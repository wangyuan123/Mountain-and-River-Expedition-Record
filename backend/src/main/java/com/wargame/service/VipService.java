package com.wargame.service;

import com.wargame.config.VipConfig;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import com.wargame.util.PlayerNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VipService {

    /** VIP 每日额度按游戏时钟的北京时间自然日结算，避免服务器时区影响刷新边界。 */
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");

    private final PlayerRepository playerRepository;
    private final PlayerCityRepository playerCityRepository;
    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final PlayerVipRewardRepository playerVipRewardRepository;
    private final PlayerVipMilestoneRepository playerVipMilestoneRepository;
    private final CityScope cityScope;
    private final DepotService depotService;
    private final MailService mailService;
    private final ChatService chatService;

    // -------------------------------------------------------------
    // 1. 充值到账后累积 EXP 与更新等级
    // -------------------------------------------------------------
    /** 充值晋升时合并公告新解锁礼包的领取资格；公告与充值同事务保存，提交后推送。 */
    @Transactional
    public Map<String, Object> onRechargeSuccess(Long playerId, long rmbAmountCents) {
        if (playerId == null || rmbAmountCents <= 0) {
            throw new IllegalArgumentException("参数无效");
        }
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: " + playerId));

        long oldCents = Objects.requireNonNullElse(player.getTotalRechargeCents(), 0L);
        long newCents = oldCents + rmbAmountCents;
        player.setTotalRechargeCents(newCents);

        int oldLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        int newLevel = VipConfig.calculateVipLevel(newCents);
        boolean levelUp = newLevel > oldLevel;
        if (levelUp) {
            player.setVipLevel(newLevel);
            // 首次达到 VIP 3 自动赋予青铜战役头像框
            if (newLevel >= 3 && "default".equals(player.getAvatarFrame())) {
                player.setAvatarFrame("frame-bronze-v1");
            }
            // 发送统帅部表彰邮件
            try {
                VipConfig.VipLevelDef newDef = VipConfig.getLevelDef(newLevel);
                mailService.sendSystem(playerId,
                        "战时特勤统帅部",
                        "system",
                        "🎖️ 战时特勤统帅部·晋升委任状",
                        "致 " + (player.getDisplayName() != null ? player.getDisplayName() : player.getUsername()) + " 指挥官：\n\n" +
                        "鉴于阁下为前线战区物资保障做出的卓越战略贡献，最高军委会特晋升阁下为【VIP " + newLevel + " · " + newDef.title() + "】！\n" +
                        "专属后勤特许与战备物资大礼包已在特勤统帅部就绪，请前往主页特勤卡片查收！\n\n" +
                        "—— 盟军中国战区最高军事委员会 敬呈",
                        null);
            } catch (Exception e) {
                log.warn("Failed to send VIP promotion mail to player {}: {}", playerId, e.getMessage());
            }
        }
        playerRepository.save(player);
        if (levelUp) {
            String packLevels = newLevel == oldLevel + 1
                    ? "VIP " + newLevel
                    : "VIP " + (oldLevel + 1) + "～VIP " + newLevel;
            chatService.sendSystem("🎖️ 恭喜玩家「" + PlayerNames.commanderName(player)
                    + "」荣升 VIP " + newLevel + "【" + VipConfig.getLevelDef(newLevel).title()
                    + "】！获得「" + packLevels + " 晋升尊享大礼包」领取资格，请前往【战时特勤统帅部】领取！");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("oldLevel", oldLevel);
        result.put("newLevel", newLevel);
        result.put("vipLevel", newLevel);
        result.put("levelUp", levelUp);
        result.put("totalRechargeCents", newCents);
        result.put("vipExp", newCents / 10);
        return result;
    }

    // -------------------------------------------------------------
    // 2. 领取每日特勤军饷 (含 VIP 5+ 战地暂存库 3 天自动补领)
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> claimDailySalary(Long playerId) {
        return claimDailySalary(playerId, null);
    }

    @Transactional
    public Map<String, Object> claimDailySalary(Long playerId, Integer targetCitySlot) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        VipConfig.VipLevelDef def = VipConfig.getLevelDef(vipLevel);
        LocalDate today = gameDate();

        int storedDays = player.getStoredDailySalariesCount();
        LocalDate lastDate = player.getLastVipDailyRewardDate();
        int destSlot = rewardCitySlot(playerId, targetCitySlot);

        int claimDays = 0;
        if (lastDate == null) {
            claimDays = 1;
        } else if (today.equals(lastDate)) {
            if (storedDays > 0) {
                claimDays = storedDays;
                player.setStoredDailySalariesCount(0);
            } else {
                throw new IllegalArgumentException("今日特勤军饷已领取，明日 00:00 刷新");
            }
        } else {
            long diffDays = ChronoUnit.DAYS.between(lastDate, today);
            if (diffDays <= 0) {
                throw new IllegalArgumentException("今日特勤军饷已领取");
            }
            int missedDays = (int) (diffDays - 1);
            if (def.storedDailySalariesDays() > 0) {
                int eligibleBacklog = Math.min(missedDays + storedDays, def.storedDailySalariesDays());
                claimDays = 1 + eligibleBacklog;
            } else {
                claimDays = 1;
            }
            player.setStoredDailySalariesCount(0);
        }

        player.setLastVipDailyRewardDate(today);
        playerRepository.save(player);

        Map<String, Integer> rewardsGiven = injectRewards(playerId, destSlot, def.dailyRewards(), claimDays);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("claimDays", claimDays);
        res.put("storedDaysRemaining", player.getStoredDailySalariesCount());
        res.put("targetCitySlot", destSlot);
        res.put("rewards", rewardsGiven);
        res.put("message", "成功领受 " + claimDays + " 天特勤军饷，战略物资已发放到位！");
        return res;
    }

    // -------------------------------------------------------------
    // 3. 领取单等级晋升专属尊享大礼包
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> claimLevelPack(Long playerId, int targetLevel) {
        return claimLevelPack(playerId, targetLevel, null);
    }

    @Transactional
    public Map<String, Object> claimLevelPack(Long playerId, int targetLevel, Integer targetCitySlot) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int currentVip = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (targetLevel <= 0 || targetLevel > currentVip) {
            throw new IllegalArgumentException("尚未达到该 VIP 等级");
        }
        if (playerVipRewardRepository.existsByPlayerIdAndVipLevel(playerId, targetLevel)) {
            throw new IllegalArgumentException("VIP " + targetLevel + " 晋升大礼包已领取，每人限领一次");
        }

        VipConfig.VipLevelDef def = VipConfig.getLevelDef(targetLevel);
        int destSlot = rewardCitySlot(playerId, targetCitySlot);

        Map<String, Integer> rewardsGiven = injectRewards(playerId, destSlot, def.levelPackRewards(), 1);
        playerVipRewardRepository.save(new PlayerVipReward(playerId, targetLevel));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("vipLevel", targetLevel);
        res.put("targetCitySlot", destSlot);
        res.put("rewards", rewardsGiven);
        res.put("message", "VIP " + targetLevel + " 晋升尊享大礼包领受成功！");
        return res;
    }

    // -------------------------------------------------------------
    // 4. 一键领受全部积压晋级大礼包
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> claimAllLevelPacks(Long playerId) {
        return claimAllLevelPacks(playerId, null);
    }

    @Transactional
    public Map<String, Object> claimAllLevelPacks(Long playerId, Integer targetCitySlot) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int currentVip = Objects.requireNonNullElse(player.getVipLevel(), 0);
        Set<Integer> claimed = playerVipRewardRepository.findByPlayerId(playerId).stream()
                .map(PlayerVipReward::getVipLevel)
                .collect(Collectors.toSet());

        List<Integer> unclaimed = new ArrayList<>();
        for (int l = 1; l <= currentVip; l++) {
            if (!claimed.contains(l)) {
                unclaimed.add(l);
            }
        }
        if (unclaimed.isEmpty()) {
            throw new IllegalArgumentException("暂无可领取的晋升大礼包");
        }

        int destSlot = rewardCitySlot(playerId, targetCitySlot);
        Map<String, Integer> totalRewards = new LinkedHashMap<>();

        for (int lvl : unclaimed) {
            VipConfig.VipLevelDef def = VipConfig.getLevelDef(lvl);
            injectRewards(playerId, destSlot, def.levelPackRewards(), 1);
            def.levelPackRewards().forEach((k, v) -> totalRewards.merge(k, v, Integer::sum));
            playerVipRewardRepository.save(new PlayerVipReward(playerId, lvl));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("claimedLevels", unclaimed);
        res.put("targetCitySlot", destSlot);
        res.put("totalRewards", totalRewards);
        res.put("message", "一键领受成功！已将 VIP " + unclaimed + " 晋升大礼包全部调拨入库！");
        return res;
    }

    // -------------------------------------------------------------
    // 5. 领受高阶充值「阶段军备箱（微里程碑补给）」
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> claimMilestoneReward(Long playerId, long milestoneCents) {
        return claimMilestoneReward(playerId, milestoneCents, null);
    }

    @Transactional
    public Map<String, Object> claimMilestoneReward(Long playerId, long milestoneCents, Integer targetCitySlot) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        long totalCents = Objects.requireNonNullElse(player.getTotalRechargeCents(), 0L);
        if (totalCents < milestoneCents) {
            throw new IllegalArgumentException("累计充值金额未达到该里程碑要求 (还需 ¥" + ((milestoneCents - totalCents) / 100.0) + ")");
        }
        VipConfig.VipMilestoneDef milestone = VipConfig.getMilestoneDef(milestoneCents);
        if (milestone == null) {
            throw new IllegalArgumentException("未知的里程碑节点");
        }
        if (playerVipMilestoneRepository.existsByPlayerIdAndMilestoneCents(playerId, milestoneCents)) {
            throw new IllegalArgumentException("该阶段军备箱已领取");
        }

        int destSlot = rewardCitySlot(playerId, targetCitySlot);
        Map<String, Integer> rewardsGiven = injectRewards(playerId, destSlot, milestone.rewards(), 1);
        playerVipMilestoneRepository.save(new PlayerVipMilestone(playerId, milestoneCents));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("milestoneCents", milestoneCents);
        res.put("title", milestone.title());
        res.put("rewards", rewardsGiven);
        res.put("message", "成功领受【" + milestone.title() + "】，战略物资已送达！");
        return res;
    }

    // -------------------------------------------------------------
    // 6. VIP 6 主页多城全域一键安抚/大赦
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> allCitiesAppease(Long playerId) {
        return allCitiesAppease(playerId, null);
    }

    @Transactional
    public Map<String, Object> allCitiesAppease(Long playerId, String appeaseType) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (vipLevel < 6) {
            throw new IllegalArgumentException("需达到 VIP 6 (兵团总司令官) 方可开启全域统筹政务特权");
        }

        LocalDate today = gameDate();
        if (appeaseType == null || appeaseType.isBlank()) {
            if (!today.equals(player.getLastFreeAmnestyDate())) {
                appeaseType = "free_amnesty";
            } else {
                appeaseType = "batch_gold";
            }
        }
        List<PlayerCity> branchCities = playerCityRepository.findByOwnerId(playerId);

        if ("free_amnesty".equalsIgnoreCase(appeaseType)) {
            if (today.equals(player.getLastFreeAmnestyDate())) {
                throw new IllegalArgumentException("今日免费军委会特赦令已使用，明日 00:00 刷新，可使用一键黄金赈民");
            }
            player.setLastFreeAmnestyDate(today);

            // 主城 (slot 0)
            player.setMorale(100);
            player.setResentment(0);
            player.setLastAppeaseAt(GameClock.now());
            playerRepository.save(player);

            // 分城
            for (PlayerCity c : branchCities) {
                c.setMorale(100);
                c.setResentment(0);
                c.setLastAppeaseAt(GameClock.now());
                playerCityRepository.save(c);
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("type", "free_amnesty");
            res.put("action", "free_amnesty");
            res.put("affectedCities", branchCities.size() + 1);
            res.put("message", "军委会全域大赦令颁布生效！名下 " + (branchCities.size() + 1) + " 座城池民心拉满至 100，民怨全域清零！");
            return res;
        } else if ("batch_gold".equalsIgnoreCase(appeaseType)) {
            // 一键合并黄金赈民 (享 8 折特权)
            long totalGoldDeducted = 0L;

            // 主城扣减
            Resources mainRes = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElse(null);
            if (mainRes != null) {
                int civ = Objects.requireNonNullElse(player.getCivilianPopulation(), 0);
                long cost = Math.round(Math.max(1000, Math.min(10000, civ * 2)) * 0.8);
                if (mainRes.getGold() != null && mainRes.getGold() >= cost) {
                    mainRes.setGold(mainRes.getGold() - cost);
                    totalGoldDeducted += cost;
                    player.setMorale(Math.min(100, Objects.requireNonNullElse(player.getMorale(), 70) + 10));
                    player.setResentment(Math.max(0, Objects.requireNonNullElse(player.getResentment(), 0) - 5));
                    player.setLastAppeaseAt(GameClock.now());
                    resourcesRepository.save(mainRes);
                    playerRepository.save(player);
                }
            }

            // 各分城扣减
            for (PlayerCity c : branchCities) {
                if (c.getCitySlot() == null || c.getCitySlot() == 0) continue;
                Resources bRes = resourcesRepository.findByPlayerIdAndCitySlot(playerId, c.getCitySlot()).orElse(null);
                if (bRes != null) {
                    int civ = Objects.requireNonNullElse(c.getCivilianPopulation(), 0);
                    long cost = Math.round(Math.max(1000, Math.min(10000, civ * 2)) * 0.8);
                    if (bRes.getGold() != null && bRes.getGold() >= cost) {
                        bRes.setGold(bRes.getGold() - cost);
                        totalGoldDeducted += cost;
                        c.setMorale(Math.min(100, Objects.requireNonNullElse(c.getMorale(), 70) + 10));
                        c.setResentment(Math.max(0, Objects.requireNonNullElse(c.getResentment(), 0) - 5));
                        c.setLastAppeaseAt(GameClock.now());
                        resourcesRepository.save(bRes);
                        playerCityRepository.save(c);
                    }
                }
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("type", "batch_gold");
            res.put("action", "batch_gold");
            res.put("totalGoldDeducted", totalGoldDeducted);
            res.put("message", "全域一键黄金赈民完成，总计消耗 " + totalGoldDeducted + " 黄金 (已享 VIP 8 折优惠)！");
            return res;
        } else {
            throw new IllegalArgumentException("未知的全域政务类型: " + appeaseType);
        }
    }

    // -------------------------------------------------------------
    // 7. VIP 7+ 万能通用技能书 1:1 定向转化
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> convertUniversalSkillBook(Long playerId, String targetSkillId) {
        return convertUniversalSkillBook(playerId, targetSkillId, 1);
    }

    @Transactional
    public Map<String, Object> convertUniversalSkillBook(Long playerId, String targetSkillId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("转化数量必须大于0");
        }
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (vipLevel < 7) {
            throw new IllegalArgumentException("万能技能书定向转化需要达到 VIP 7 (战区副长官)");
        }
        if (targetSkillId == null || !OfficerSkillDef.OFFICER_SKILLS.containsKey(targetSkillId)) {
            throw new IllegalArgumentException("未知的技能目标: " + targetSkillId);
        }

        // 尝试消耗 count 本 universalSkillBook
        int consumed = playerItemRepository.tryConsume(playerId, "universalSkillBook", count, GameClock.now());
        if (consumed <= 0) {
            throw new IllegalArgumentException("战备背包中没有足够的通用技能书 (universalSkillBook)");
        }

        // 赋予对应指定技能书
        String outputItemKey = "skillBook_" + targetSkillId;
        depotService.addItem(playerId, outputItemKey, count);

        var skillDef = OfficerSkillDef.OFFICER_SKILLS.get(targetSkillId);
        String skillName = skillDef != null ? skillDef.name() : targetSkillId;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("targetSkillId", targetSkillId);
        res.put("outputItemKey", outputItemKey);
        res.put("skillName", skillName);
        res.put("count", count);
        res.put("message", "定向推演转化完成！已获得 " + count + " 本【" + skillName + "技能书】！");
        return res;
    }

    // -------------------------------------------------------------
    // 8. VIP 4+ 聊天金色名牌自主显隐设置
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> toggleVipBadge(Long playerId, boolean visible) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (vipLevel < 4) {
            throw new IllegalArgumentException("聊天专属金色铭牌需要达到 VIP 4 (方面军参谋长) 方可配置");
        }

        player.setVipBadgeVisible(visible);
        playerRepository.save(player);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("vipBadgeVisible", visible);
        res.put("message", visible ? "已开启 VIP 专属金色铭牌显示" : "已开启战术隐匿模式 (隐藏 VIP 聊天名牌)");
        return res;
    }

    // -------------------------------------------------------------
    // 9. 头像框更换
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> setAvatarFrame(Long playerId, String frameId) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (frameId == null || frameId.isBlank() || "default".equalsIgnoreCase(frameId)) {
            player.setAvatarFrame("default");
        } else {
            validateAvatarFramePrivilege(vipLevel, frameId);
            player.setAvatarFrame(frameId);
        }
        playerRepository.save(player);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("avatarFrame", player.getAvatarFrame());
        return res;
    }

    private void validateAvatarFramePrivilege(int vipLevel, String frameId) {
        if ("frame-bronze-v1".equals(frameId) && vipLevel < 3) throw new IllegalArgumentException("青铜战役头像框需 VIP 3 解锁");
        if ("frame-silver-v1".equals(frameId) && vipLevel < 5) throw new IllegalArgumentException("白银将星头像框需 VIP 5 解锁");
        if ("frame-gold-v1".equals(frameId) && vipLevel < 6) throw new IllegalArgumentException("黄金统帅头像框需 VIP 6 解锁");
        if ("frame-marshal-v1".equals(frameId) && vipLevel < 7) throw new IllegalArgumentException("元帅流光头像框需 VIP 7 解锁");
        if ("frame-supreme-v1".equals(frameId) && vipLevel < 10) throw new IllegalArgumentException("至尊五星统帅头像框需 VIP 10 解锁");
    }

    // -------------------------------------------------------------
    // 10. 外链自定义头像设置 (VIP 3+, 防 SSRF & 防盗链校验)
    // -------------------------------------------------------------
    @Transactional
    public Map<String, Object> setCustomAvatarUrl(Long playerId, String url) {
        Player player = playerRepository.lockById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        if (vipLevel < 3) {
            throw new IllegalArgumentException("自定义头像功能需要达到 VIP 3 (战略支柱长官)");
        }
        if (url == null || url.isBlank()) {
            player.setCustomAvatar(null);
        } else {
            String trimmed = url.trim();
            if (trimmed.startsWith("img/avatars/")) {
                // 本地上传相对路径
                player.setCustomAvatar(trimmed);
            } else if (trimmed.startsWith("https://")) {
                if (trimmed.contains("127.0.0.1") || trimmed.contains("localhost") || trimmed.contains("192.168.") || trimmed.contains("10.")) {
                    throw new IllegalArgumentException("禁止使用私有内网 IP 地址作为外链头像");
                }
                player.setCustomAvatar(trimmed);
            } else {
                throw new IllegalArgumentException("头像链接必须以 https:// 开头或为本地已上传资源以确保安全性");
            }
        }
        playerRepository.save(player);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("customAvatar", player.getCustomAvatar());
        return res;
    }

    // -------------------------------------------------------------
    // 11. 获取 VIP 完整状态展示对象
    // -------------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> getVipStatus(Long playerId) {
        Player player = playerRepository.findById(playerId).orElseThrow();
        int vipLevel = Objects.requireNonNullElse(player.getVipLevel(), 0);
        long totalCents = Objects.requireNonNullElse(player.getTotalRechargeCents(), 0L);
        long vipExp = totalCents / 10;

        VipConfig.VipLevelDef curDef = VipConfig.getLevelDef(vipLevel);
        VipConfig.VipLevelDef nextDef = vipLevel < VipConfig.getMaxLevel() ? VipConfig.getLevelDef(vipLevel + 1) : null;

        long curBaseExp = curDef.requiredCents() / 10;
        long nextReqExp = nextDef != null ? nextDef.requiredCents() / 10 : curBaseExp;
        long diffExp = Math.max(1, nextReqExp - curBaseExp);
        double intervalProgress = nextDef != null
                ? Math.min(100.0, Math.max(0.0, ((double) (vipExp - curBaseExp) / diffExp) * 100.0))
                : 100.0;
        long centsNeeded = nextDef != null ? Math.max(0L, nextDef.requiredCents() - totalCents) : 0L;

        LocalDate today = gameDate();
        boolean dailyClaimed = isDailySalaryClaimed(player);
        boolean freeAmnestyAvailable = !today.equals(player.getLastFreeAmnestyDate()) && vipLevel >= 6;

        Set<Integer> claimedPackLevels = playerVipRewardRepository.findByPlayerId(playerId).stream()
                .map(PlayerVipReward::getVipLevel).collect(Collectors.toSet());

        Set<Long> claimedMilestones = playerVipMilestoneRepository.findByPlayerId(playerId).stream()
                .map(PlayerVipMilestone::getMilestoneCents).collect(Collectors.toSet());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vipLevel", vipLevel);
        data.put("title", curDef.title());
        data.put("totalRechargeCents", totalCents);
        data.put("vipExp", vipExp);
        data.put("nextVipExp", nextReqExp);
        data.put("intervalProgress", Math.round(intervalProgress * 10.0) / 10.0);
        data.put("centsNeededForNext", centsNeeded);
        data.put("dailyClaimed", dailyClaimed);
        data.put("storedDailySalaries", player.getStoredDailySalariesCount());
        data.put("freeAmnestyAvailable", freeAmnestyAvailable);
        data.put("vipBadgeVisible", player.isVipBadgeVisible());
        data.put("customAvatar", player.getCustomAvatar());
        data.put("avatarFrame", player.getAvatarFrame());
        data.put("claimedPackLevels", claimedPackLevels);
        data.put("claimedMilestones", claimedMilestones);
        data.put("buildQueuePerCity", curDef.buildQueuePerCity());
        data.put("techQueuePerCity", curDef.techQueuePerCity());
        data.put("woundedGoldDiscount", curDef.woundedGoldDiscount());
        data.put("mapFavoriteLimit", curDef.mapFavoriteLimit());
        data.put("marchPresetLimit", curDef.marchPresetLimit());
        data.put("levels", VipConfig.getAllLevels());
        data.put("milestones", VipConfig.getAllMilestones());
        return data;
    }

    /** 当前游戏时间对应的北京时间自然日，用于所有 VIP 每日额度和状态展示。 */
    private static LocalDate gameDate() {
        return Instant.ofEpochMilli(GameClock.now()).atZone(GAME_ZONE).toLocalDate();
    }

    /** 首页和特勤弹窗共用每日军饷口径；有暂存军饷时仍保留领取入口。 */
    public static boolean isDailySalaryClaimed(Player player) {
        return gameDate().equals(player.getLastVipDailyRewardDate())
                && player.getStoredDailySalariesCount() == 0;
    }

    // -------------------------------------------------------------
    // 12. 特权数值查询方法 (供外部各 Service 快速调用)
    // -------------------------------------------------------------
    public int getBuildQueueLimit(Player player) {
        if (player == null) return 3;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).buildQueuePerCity();
    }

    public int getCityTechQueueLimit(Player player) {
        if (player == null) return 1;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).techQueuePerCity();
    }

    public double getWoundedHealGoldDiscount(Player player) {
        if (player == null) return 0.0;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).woundedGoldDiscount();
    }

    public double getWoundedGoldDiscount(Player player) {
        return getWoundedHealGoldDiscount(player);
    }

    public double getWoundedHealGoldDiscount(Long playerId) {
        if (playerId == null) return 0.0;
        return playerRepository.findById(playerId).map(this::getWoundedHealGoldDiscount).orElse(0.0);
    }

    public double getWoundedGoldDiscount(Long playerId) {
        return getWoundedHealGoldDiscount(playerId);
    }

    public int getMapFavoriteLimit(Player player) {
        if (player == null) return 10;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).mapFavoriteLimit();
    }

    public int getMarchPresetLimit(Player player) {
        if (player == null) return 2;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).marchPresetLimit();
    }

    public double getBuildSpeedBonus(Player player) {
        if (player == null) return 0.0;
        int level = Objects.requireNonNullElse(player.getVipLevel(), 0);
        return VipConfig.getLevelDef(level).buildSpeedBonus();
    }

    // -------------------------------------------------------------
    // 私有辅助：资源/道具发放分流器 (五大资源到对应城市仓库，道具到账号背包)
    // -------------------------------------------------------------
    /** 发奖前核对城市归属及可写状态，防止领取资格消耗后资源落入无主槽位。 */
    private int rewardCitySlot(Long playerId, Integer requestedSlot) {
        int slot = requestedSlot != null ? requestedSlot : cityScope.slot(playerId);
        PlayerCity city = playerCityRepository.findByOwnerIdAndCitySlot(playerId, slot).orElse(null);
        if (city == null) {
            // 兼容尚未补齐地图城市记录、但已有主城资产的历史账号；分城必须有当前归属。
            if (slot == 0 && resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).isPresent()) return slot;
            throw new IllegalArgumentException("受领城市不存在或不属于你，请重新选择");
        }
        cityScope.requireOwned(playerId, city.getId(), true);
        if (city.isSurrenderFrozen(GameClock.now())) {
            throw new com.wargame.security.GameAccessException("CITY_FROZEN", "归降移交中的城市不能受领奖励", 409);
        }
        return slot;
    }

    private Map<String, Integer> injectRewards(Long playerId, int targetCitySlot, Map<String, Integer> rewards, int multiplier) {
        Map<String, Integer> finalRewards = new LinkedHashMap<>();
        if (rewards == null || rewards.isEmpty() || multiplier <= 0) return finalRewards;

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, targetCitySlot)
                .orElseGet(() -> {
                    Resources r = new Resources();
                    r.setPlayerId(playerId);
                    r.setCitySlot(targetCitySlot);
                    r.setGold(0L); r.setFood(0L); r.setSteel(0L); r.setOil(0L); r.setRare(0L);
                    return r;
                });

        boolean resChanged = false;
        for (Map.Entry<String, Integer> entry : rewards.entrySet()) {
            String key = entry.getKey();
            int totalCount = entry.getValue() * multiplier;
            if (totalCount <= 0) continue;

            finalRewards.put(key, totalCount);

            switch (key) {
                case "gold" -> {
                    res.setGold(Objects.requireNonNullElse(res.getGold(), 0L) + totalCount);
                    resChanged = true;
                }
                case "food" -> {
                    res.setFood(Objects.requireNonNullElse(res.getFood(), 0L) + totalCount);
                    resChanged = true;
                }
                case "steel" -> {
                    res.setSteel(Objects.requireNonNullElse(res.getSteel(), 0L) + totalCount);
                    resChanged = true;
                }
                case "oil" -> {
                    res.setOil(Objects.requireNonNullElse(res.getOil(), 0L) + totalCount);
                    resChanged = true;
                }
                case "rare" -> {
                    res.setRare(Objects.requireNonNullElse(res.getRare(), 0L) + totalCount);
                    resChanged = true;
                }
                default -> {
                    // 通用背包道具
                    depotService.addItem(playerId, key, totalCount);
                }
            }
        }
        if (resChanged) {
            resourcesRepository.save(res);
        }
        return finalRewards;
    }
}
