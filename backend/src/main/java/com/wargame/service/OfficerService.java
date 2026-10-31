package com.wargame.service;

import com.wargame.util.DurationText;

import com.wargame.util.GameClock;
import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.AcademyRecruitmentRules;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.constants.OfficerExperienceRules;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.HistoricalOfficers;
import com.wargame.model.constants.OfficerNameGenerator;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * 军官管理服务 - 对应 JS 中 G.Officer 和 G.genOfficer 的后端实现。
 * <p>
 * 核心逻辑参考 js/officer.js 和 js/save.js：
 * <ul>
 *   <li>{@code G.Officer.refreshAcademy} -> {@link #refreshAcademy}</li>
 *   <li>{@code G.Officer.recruit}        -> {@link #recruit}</li>
 *   <li>{@code G.Officer.appoint}        -> {@link #appoint}</li>
 *   <li>{@code G.Officer.dismiss}        -> {@link #dismiss}</li>
 *   <li>{@code G.Officer.reward}         -> {@link #reward}</li>
 *   <li>{@code G.Officer.learnSkill}     -> {@link #learnSkill}</li>
 *   <li>{@code G.Officer.forgetSkill}    -> {@link #abandonSkill}</li>
 *   <li>{@code G.genOfficer}             -> {@link #genOfficer}</li>
 * </ul>
 */
@Service
public class OfficerService {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodService garrisonFood;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private TechService techService;

    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.MarchRepository marches;
    private final OfficerRepository officerRepository;
    private final AcademyRepository academyRepository;
    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerItemRepository playerItemRepository;
    private final OfficerEquipmentRepository equipmentRepository;
    private final com.wargame.service.quest.QuestService questService;

    /** 军官属性上限 - 对应 JS G.ATTR_MAX */
    public static final int ATTR_MAX = 219;

    /** 随队资格共用于状态预览和事务内派遣校验，浏览器不再重建岗位或忙碌规则。 */
    public static String dispatchReason(String role, boolean marching) {
        if ("commander_in_chief".equals(role)) return "请先解除司令官任命再出征";
        if ("mayor".equals(role) || "commander".equals(role)) return "请先解除市长或指挥官任命再出征";
        if (marching || "march".equals(role)) return "军官正在执行行军任务";
        return "";
    }
    /** 军官等级上限 - 对应 JS G.OFFICER_MAX_LEVEL */
    private static final int OFFICER_MAX_LEVEL = OfficerExperienceRules.MAX_LEVEL;
    /** 军校刷新费用 - 对应 JS refreshAcademy 中 var cost = 200（约 6.7 小时黄金产出） */
    private static final int ACADEMY_REFRESH_COST = 200;
    /** 每次军校刷新生成的候选人数量；五星概率仍按整批只判定一次。 */
    public static final int ACADEMY_CANDIDATE_COUNT = 10;
    /** 招募费用系数 - 对应 JS var cost = o.star * 80 */
    private static final int RECRUIT_COST_PER_STAR = 80;
    /** 解雇返还系数 - 对应 JS s.resources.gold += o.star * 20 */
    private static final int DISMISS_REFUND_PER_STAR = 20;
    /** 赏赐冷却 (30分钟) - 对应 JS 30 * 60 * 1000 */
    private static final long REWARD_COOLDOWN = 30 * 60 * 1000L;
    /** 技能最大数量 - 对应 JS o.skills.length >= 3 */
    private static final int MAX_SKILLS = 3;

    /** 通用军官简介 - 对应 JS genBio 中的 genericBios */
    private static final List<String> GENERIC_BIOS = List.of(
            "出身于军人世家，自幼熟读兵法，立志报效国家。",
            "行伍出身，从基层一步步摸爬滚打，积累了丰富的实战经验。",
            "毕业于军事学院，擅长战术分析与兵力调度，是不可多得的将才。",
            "身经百战的老将，曾在多次战役中力挽狂澜，威名远扬。",
            "年轻有为的军事天才，以大胆果断的指挥风格著称。"
    );

    public OfficerService(OfficerRepository officerRepository,
                          AcademyRepository academyRepository,
                          ResourcesRepository resourcesRepository,
                          BuildingRepository buildingRepository,
                          PlayerItemRepository playerItemRepository,
                          OfficerEquipmentRepository equipmentRepository,
                          com.wargame.service.quest.QuestService questService,
                          PlayerRepository playerRepository) {
        this.officerRepository = officerRepository;
        this.academyRepository = academyRepository;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.playerItemRepository = playerItemRepository;
        this.equipmentRepository = equipmentRepository;
        this.questService = questService;
    }

    // ================================================================
    //  refreshAcademy - 对应 JS G.Officer.refreshAcademy
    // ================================================================

    @Transactional
    public Map<String, Object> refreshAcademy(Long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        int academyLv = buildingLevel(playerId, "academy");
        if (academyLv <= 0) {
            result.put("success", false);
            result.put("message", "请先建造军校");
            return result;
        }
        // 鉴权可能已缓存旧玩家版本；先落库本事务变更，再加锁重新读取，避免旧版本锁升级失败。
        Player player = playerRepository.findById(playerId).orElseThrow();
        entityManager.flush();
        entityManager.refresh(player, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        long now = GameClock.now();
        AcademyRefreshPolicy.Snapshot quota = AcademyRefreshPolicy.snapshot(player, now);
        result.putAll(quota.toState());
        if (quota.dailyCount() >= AcademyRefreshPolicy.DAILY_LIMIT) {
            result.put("success", false);
            result.put("message", "今日军校刷新已达100次，请于游戏时间次日0点后再刷新（停服顺延）");
            return result;
        }
        if (quota.refreshAt() > now) {
            result.put("success", false);
            result.put("message", "本轮已刷新30次，军校 " + DurationText.millis(quota.refreshAt() - now) + "后可再次刷新");
            return result;
        }

        // 主动刷新整批候选人需花费黄金。
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null || getGold(res) < ACADEMY_REFRESH_COST) {
            result.put("success", false);
            result.put("message", "黄金不足(需" + ACADEMY_REFRESH_COST + ")");
            return result;
        }
        setGold(res, getGold(res) - ACADEMY_REFRESH_COST);
        resourcesRepository.save(res);

        // 持有账号锁时先结算到期科研，使本次刷新与随后返回的状态使用相同等级。
        techService.settleCompletedResearchForCity(playerId, cityScope.slot(playerId), now);
        int theoryLevel = techService.getTechLevel(playerId, AcademyRecruitmentRules.MILITARY_THEORY);
        List<Map<String, Object>> list = genAcademyCandidates(academyLv, theoryLevel, ThreadLocalRandom.current());

        AcademyRefreshPolicy.recordSuccess(player, quota, now);
        playerRepository.save(player);
        Academy academy = getOrCreateAcademy(playerId);
        academy.setOfficers(JsonUtil.toJson(list));
        academyRepository.save(academy);

        result.put("success", true);
        result.put("message", "军校已刷新");
        result.put("list", list);
        result.put("candidateCount", ACADEMY_CANDIDATE_COUNT);
        result.putAll(AcademyRecruitmentRules.state(academyLv, theoryLevel));
        result.putAll(AcademyRefreshPolicy.snapshot(player, now).toState());
        return result;
    }

    // ================================================================
    //  getAcademyList - 返回军校当前军官列表
    // ================================================================

    public List<Map<String, Object>> getAcademyList(Long playerId) {
        Academy academy = academyRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (academy == null || academy.getOfficers() == null || academy.getOfficers().isBlank()) {
            return Collections.emptyList();
        }
        return JsonUtil.parseList(academy.getOfficers());
    }

    // ================================================================
    //  recruit - 对应 JS G.Officer.recruit
    // ================================================================

    /** 先按旧军官名单结清本城经济，招募费和新工资只从本次操作时刻生效。 */
    @Transactional
    public Map<String, Object> recruit(Long playerId, int officerIdx) {
        economy.settleEconomyBeforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Academy academy = academyRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (academy == null) {
            result.put("success", false);
            result.put("message", "军校未初始化");
            return result;
        }

        List<Map<String, Object>> list = JsonUtil.parseList(academy.getOfficers());
        if (officerIdx < 0 || officerIdx >= list.size()) {
            result.put("success", false);
            result.put("message", "无效的军官索引");
            return result;
        }

        Map<String, Object> o = list.get(officerIdx);
        int star = o.get("star") != null ? ((Number) o.get("star")).intValue() : 1;

        // JS: var cost = o.star * 80
        int cost = recruitCost(star);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null || getGold(res) < cost) {
            result.put("success", false);
            result.put("message", "黄金不足(需" + cost + ")");
            return result;
        }

        // 扣除黄金
        setGold(res, getGold(res) - cost);
        resourcesRepository.save(res);

        // 从军校列表中移除 (JS: s.academy.list.splice(idx, 1))
        list.remove(officerIdx);
        academy.setOfficers(JsonUtil.toJson(list));
        academyRepository.save(academy);

        // 添加到军官列表 (JS: o.role = 'idle'; s.officers.push(o))
        Officer officer = new Officer();
        officer.setPlayerId(playerId);
        officer.setCitySlot(cityScope.slot(playerId));
        officer.setName(o.get("name") != null ? o.get("name").toString() : "未知");
        officer.setStar(star);
        officer.setLevel(1);
        officer.setLogistics(getInt(o, "logistics"));
        officer.setMilitary(getInt(o, "military"));
        officer.setDefense(getInt(o, "defense"));
        officer.setKnowledge(getInt(o, "knowledge"));
        officer.setLoyalty(getInt(o, "loyalty"));
        officer.setSalary(getInt(o, "salary"));
        officer.setExp(0L);
        officer.setRole("idle");
        officer.setRecruitAt(GameClock.now());
        officer.setRewardAt(0L);
        officer.setSkills(o.get("skills") != null ? JsonUtil.toJson(o.get("skills")) : "[]");
        officer.setBio(o.get("bio") != null ? o.get("bio").toString() : "");
        officerRepository.save(officer);

        // 主线任务进度钩子
        try {
            int starValue = officer.getStar() != null ? officer.getStar() : 0;
            questService.onEvent(playerId, "OFFICER_RECRUIT", null, 1);
            questService.onEvent(playerId, "OFFICER_RECRUIT_STAR", null, starValue);
        } catch (Exception ignored) {}

        result.put("success", true);
        result.put("message", "招募 " + officer.getName() + " " + starToStr(star));
        result.put("officerId", officer.getId());
        return result;
    }

    // ================================================================
    //  appoint - 对应 JS G.Officer.appoint
    // ================================================================

    /** 每城每职只任命一人；司令部落成后解锁司令官，行军军官不能改任。 */
    @Transactional
    public Map<String, Object> appoint(Long playerId, Long officerId, String role) {
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (role == null || !Set.of("mayor", "commander", "commander_in_chief").contains(role)) {
            throw new IllegalArgumentException("职位无效");
        }
        // 使用玩家行锁串行化同一城市的任命，避免并发产生两名司令官。
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Officer target = null;
        for (Officer o : officers) {
            if (o.getId().equals(officerId)) {
                target = o;
                break;
            }
        }
        if (target == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        if (marches.existsByPlayerIdAndCommanderId(playerId, officerId)) throw new IllegalArgumentException("军官正在行军，无法任命");
        String currentRole = target.getRole() != null ? target.getRole() : "idle";

        if ("commander_in_chief".equals(role) && !currentRole.equals(role) && buildingLevel(playerId, "headquarters") < 1) {
            throw new IllegalArgumentException("请先建造司令部");
        }

        // JS: if (s.officers[i].role === role) { set to idle (取消任命) }
        if (currentRole.equals(role)) {
            target.setRole("idle");
            officerRepository.save(target);
            result.put("success", true);
            result.put("message", "取消任命成功");
        } else {
            // JS: for (j) { if (s.officers[j].role === role) s.officers[j].role = 'idle' }
            for (Officer o : officers) {
                String r = o.getRole() != null ? o.getRole() : "idle";
                if (r.equals(role)) {
                    o.setRole("idle");
                    officerRepository.save(o);
                }
            }
            target.setRole(role);
            officerRepository.save(target);

            // 主线任务进度钩子
            try {
                questService.onEvent(playerId, "OFFICER_APPOINT", null, 1);
            } catch (Exception ignored) {}

            result.put("success", true);
            result.put("message", "任命成功！" + target.getName() + " 出任 " + roleText(role));
        }

        return result;
    }

    // ================================================================
    //  dismiss - 对应 JS G.Officer.dismiss (confirm action)
    // ================================================================

    @Transactional
    public Map<String, Object> dismiss(Long playerId, Long officerId) {
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        equipmentProgress.lockPlayer(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Officer target = null;
        for (Officer o : officers) {
            if (o.getId().equals(officerId)) {
                target = o;
                break;
            }
        }
        if (target == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        if (marches.existsByPlayerIdAndCommanderId(playerId, officerId)) throw new IllegalArgumentException("军官正在行军，无法解雇");
        // JS: s.resources.gold += o.star * 20
        int star = target.getStar() != null ? target.getStar() : 1;
        int refund = star * DISMISS_REFUND_PER_STAR;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res != null) {
            res.addResource("gold", refund);
            resourcesRepository.save(res);
        }

        String name = target.getName();
        equipmentService.unequip(playerId, officerId, "all");
        officerRepository.delete(target);

        result.put("success", true);
        result.put("message", "已解雇 " + name);
        result.put("refund", refund);
        return result;
    }

    // ================================================================
    //  reward - 对应 JS G.Officer.reward
    // ================================================================

    @Transactional
    public Map<String, Object> reward(Long playerId, Long officerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        long now = GameClock.now();
        long rewardAt = officer.getRewardAt() != null ? officer.getRewardAt() : 0L;

        // JS: if (o.rewardCoolAt && now < o.rewardCoolAt)
        if (rewardAt > 0 && now < rewardAt) {
            result.put("success", false);
            result.put("message", "赏赐冷却中，还需 " + DurationText.millis(rewardAt - now));
            return result;
        }

        // JS: var cost = (o.star || 1) * 50 + (o.level || 1) * 2
        int star = officer.getStar() != null ? officer.getStar() : 1;
        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        int cost = rewardCost(star, level);

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null || getGold(res) < cost) {
            result.put("success", false);
            result.put("message", "黄金不足(需" + cost + ")");
            return result;
        }

        setGold(res, getGold(res) - cost);
        resourcesRepository.save(res);

        // JS: var gain = 5 + Math.floor(Math.random() * 6)  -> 5~10
        int gain = 5 + ThreadLocalRandom.current().nextInt(6);
        int loyalty = officer.getLoyalty() != null ? officer.getLoyalty() : 0;
        officer.setLoyalty(Math.min(100, loyalty + gain));
        officer.setRewardAt(now + REWARD_COOLDOWN);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "赏赐成功! 忠诚度 +" + gain + "，消耗黄金" + cost + "，" + DurationText.millis(REWARD_COOLDOWN) + "后可再次赏赐");
        result.put("loyalty", officer.getLoyalty());
        result.put("cost", cost);
        return result;
    }

    // ================================================================
    //  learnSkill - 对应 JS G.Officer.learnSkill
    // ================================================================

    /** 普通书从未掌握的技能中随机学习，各技能可自由组合。 */
    @Transactional
    public Map<String, Object> learnSkill(Long playerId, Long officerId) {
        playerRepository.lockById(playerId).orElseThrow();
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficerForSkillChange(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        // Parse current skills
        List<Map<String, Object>> skills = parseSkills(officer.getSkills());

        // JS: if (o.skills.length >= 3)
        if (skills.size() >= MAX_SKILLS) {
            result.put("success", false);
            result.put("message", "技能已满3个，请先废弃一个");
            return result;
        }

        // JS: build pool of unowned skills
        Set<String> owned = new HashSet<>();
        for (Map<String, Object> sk : skills) {
            Object id = sk.get("id");
            if (id != null) {
                OfficerSkillDef ownedSkill = OfficerSkillDef.getSkill(id.toString());
                owned.add(ownedSkill != null ? ownedSkill.key() : id.toString());
            }
        }

        List<String> pool = new ArrayList<>();
        for (String sid : GameData.OFFICER_SKILLS.keySet()) {
            if (!owned.contains(sid)) {
                pool.add(sid);
            }
        }

        if (pool.isEmpty()) {
            result.put("success", false);
            result.put("message", "已学完全部技能");
            return result;
        }

        // JS: var pickId = pool[Math.floor(Math.random() * pool.length)]
        String pickId = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        OfficerSkillDef pickInfo = GameData.OFFICER_SKILLS.get(pickId);

        // 军官星级不再决定技能强度，随机学习也直接获得原满级效果。
        int lv = pickInfo.max();

        if (playerItemRepository.tryConsume(playerId, "skillBook", 1, GameClock.now()) == 0) {
            result.put("success", false);
            result.put("message", "普通技能书不足");
            return result;
        }

        Map<String, Object> newSkill = new LinkedHashMap<>();
        newSkill.put("id", pickId);
        newSkill.put("lv", lv);
        skills.add(newSkill);

        officer.setSkills(JsonUtil.toJson(skills));
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "学习成功: " + pickInfo.name() + " 满级 (消耗1本普通技能书)");
        result.put("skillId", pickId);
        result.put("skillName", pickInfo.name());
        result.put("level", lv);
        return result;
    }

    /**
     * 使用指定技能书为军官学习对应技能。
     * 指定书固定授予满级技能，且继续执行技能槽上限和重复技能校验，各技能可自由组合。
     *
     * @param playerId 玩家 ID
     * @param officerId 要学习技能的军官 ID
     * @param itemId 指定技能书道具 ID
     * @param skillId 指定技能 ID
     * @return 学习结果及获得的技能信息
     */
    @Transactional
    public Map<String, Object> learnSpecificSkill(Long playerId, Long officerId, String itemId, String skillId) {
        return learnChosenSkill(playerId, officerId, itemId, skillId, false);
    }

    /** 通用书只能从正式池学习新的满级技能，不接受历史别名，也不能升级已有技能。 */
    @Transactional
    public Map<String, Object> learnUniversalSkill(Long playerId, Long officerId, String skillId) {
        return learnChosenSkill(playerId, officerId, "universalSkillBook", skillId, true);
    }

    /** 在玩家写锁内校验、扣书并写入技能；两类指定学习共享槽位与重复校验，各技能可自由组合。 */
    private Map<String, Object> learnChosenSkill(Long playerId, Long officerId, String itemId, String skillId, boolean universal) {
        playerRepository.lockById(playerId).orElseThrow();
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        OfficerSkillDef skill = skillId == null ? null : GameData.OFFICER_SKILLS.get(skillId);
        if (skill == null || (!universal && !("skillBook_" + skillId).equals(itemId))) {
            result.put("success", false);
            result.put("message", universal ? "请选择技能池中的有效技能" : "无效的指定技能书");
            return result;
        }

        Officer officer = findOfficerForSkillChange(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        List<Map<String, Object>> skills = parseSkills(officer.getSkills());
        String reason = skillLearningReason(skills, skillId);
        if (!reason.isEmpty()) {
            result.put("success", false); result.put("message", reason); return result;
        }

        String bookName = universal ? "通用技能书" : skill.name() + "技能书";
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) {
            result.put("success", false);
            result.put("message", bookName + "不足");
            return result;
        }

        Map<String, Object> learned = new LinkedHashMap<>();
        learned.put("id", skillId);
        learned.put("lv", skill.max());
        skills.add(learned);
        officer.setSkills(JsonUtil.toJson(skills));
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", universal
                ? officer.getName() + " 已学会「" + skill.name() + "」满级 (消耗1本" + bookName + ")"
                : "学习成功: " + skill.name() + " 满级 (消耗1本" + bookName + ")");
        result.put("skillId", skillId);
        result.put("skillName", skill.name());
        result.put("level", skill.max());
        return result;
    }

    /** 兼容缓存客户端：技能升级已取消，拒绝旧请求且不消耗任何书籍。 */
    public Map<String, Object> upgradeSkill(Long playerId, Long officerId, int skillIdx, String itemId) {
        return new LinkedHashMap<>(Map.of("success", false, "message", "技能已统一为满级，无需升级"));
    }

    // ================================================================
    //  abandonSkill - 对应 JS G.Officer.forgetSkill (confirm action)
    // ================================================================

    @Transactional
    public Map<String, Object> abandonSkill(Long playerId, Long officerId, int skillIdx) {
        playerRepository.lockById(playerId).orElseThrow();
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficerForSkillChange(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        List<Map<String, Object>> skills = parseSkills(officer.getSkills());
        if (skillIdx < 0 || skillIdx >= skills.size()) {
            result.put("success", false);
            result.put("message", "技能不存在");
            return result;
        }

        Map<String, Object> sk = skills.get(skillIdx);
        String skId = sk.get("id") != null ? sk.get("id").toString() : "";
        OfficerSkillDef skInfo = GameData.OFFICER_SKILLS.get(skId);
        String skName = skInfo != null ? skInfo.name() : "技能";

        skills.remove(skillIdx);
        officer.setSkills(JsonUtil.toJson(skills));
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "已废弃 " + skName);
        return result;
    }

    // ================================================================
    //  useExpBook - 经验书(对应 JS useExpBook, 但做服务端校验)
    // ================================================================

    @Transactional
    public Map<String, Object> useExpBook(Long playerId, Long officerId) {
        return useExpBook(playerId, officerId, null, 1);
    }

    /** 定额经验书支持批量加经验，仍由玩家另行升级；满级书每次只扣一本并直接升满。 */
    @Transactional
    public Map<String, Object> useExpBook(Long playerId, Long officerId, String itemId, int count) {
        economy.settleEconomyBeforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        if (level >= 100) {
            result.put("success", false);
            result.put("message", "军官已达等级上限 (Lv.100)");
            return result;
        }

        if (count <= 0) count = 1;

        // 兼容未指定档位的旧调用，按普通、高级、极速、满级依次选择库存充足的档位。
        if (itemId == null || itemId.isBlank()) {
            itemId = "expBook";
            for (String candidate : List.of("expBook", "expBookAdv", "expBookRapid", "expBookMax")) {
                int stock = playerItemRepository.findByPlayerIdAndItemKey(playerId, candidate)
                        .map(it -> it.getCount() != null ? it.getCount() : 0).orElse(0);
                if (stock >= ("expBookMax".equals(candidate) ? 1 : count)) {
                    itemId = candidate;
                    break;
                }
            }
        }

        if ("expBookMax".equals(itemId)) {
            int consumed = playerItemRepository.tryConsume(playerId, "expBookMax", 1, GameClock.now());
            if (consumed == 0) {
                result.put("success", false);
                result.put("message", "满级经验书不足");
                return result;
            }

            int upgraded = 100 - level;
            int pointsGained = upgraded;
            officer.setLevel(100);
            officer.setExp(0L);
            officer.setAttrPoints((officer.getAttrPoints() != null ? officer.getAttrPoints() : 0) + pointsGained);
            officerRepository.save(officer);

            result.put("success", true);
            result.put("message", "⚡ " + officer.getName() + " 使用满级经验书直升满级 Lv.100！获得 " + pointsGained + " 点可分配属性！");
            result.put("exp", 0L);
            result.put("level", 100);
            result.put("gain", 0);
            result.put("count", 1);
            result.put("itemId", itemId);
            return result;
        }

        Integer singleGain = ItemDef.EXPERIENCE_BOOK_EXP.get(itemId);
        if (singleGain == null) {
            result.put("success", false);
            result.put("message", "无效的经验书档位");
            return result;
        }
        String bookName = ItemDef.ITEMS.get(itemId).name();

        int consumed = playerItemRepository.tryConsume(playerId, itemId, count, GameClock.now());
        if (consumed == 0) {
            result.put("success", false);
            result.put("message", bookName + "不足");
            return result;
        }

        // 五十万经验档批量使用时可能超过 int 范围，先以 long 计算再写入经验余额。
        long totalGain = (long) singleGain * count;
        long exp = (officer.getExp() != null ? officer.getExp() : 0L) + totalGain;
        officer.setExp(exp);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "已使用" + bookName + (count > 1 ? " ×" + count : "") + "，获得 +" + totalGain + " 经验");
        result.put("exp", exp);
        result.put("level", level);
        result.put("gain", totalGain);
        result.put("count", count);
        result.put("itemId", itemId);
        return result;
    }

    // ================================================================
    //  levelUp - 军官升级入口(支持升1级或一键升多级)
    // ================================================================

    @Transactional
    public Map<String, Object> levelUp(Long playerId, Long officerId, boolean all) {
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        if (level >= OFFICER_MAX_LEVEL) {
            result.put("success", false);
            result.put("message", "军官已达最高等级 (Lv.100)");
            return result;
        }

        long curExp = officer.getExp() != null ? officer.getExp() : 0L;
        int upgraded = 0;

        if (all) {
            var growth = OfficerExperienceRules.preview(level, curExp);
            level = growth.level(); curExp = growth.exp(); upgraded = growth.upgraded();
        } else {
            long need = OfficerExperienceRules.expNeeded(level);
            if (curExp >= need) {
                curExp -= need;
                level++;
                upgraded = 1;
            }
        }

        if (upgraded == 0) {
            long need = OfficerExperienceRules.expNeeded(level);
            result.put("success", false);
            result.put("message", "经验不足，升级需要 " + need + " 经验");
            return result;
        }

        if (level >= OFFICER_MAX_LEVEL) {
            curExp = 0L;
        }
        int pointsGained = upgraded;
        officer.setLevel(level);
        officer.setExp(curExp);
        officer.setAttrPoints((officer.getAttrPoints() != null ? officer.getAttrPoints() : 0) + pointsGained);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "⚡ " + officer.getName() + " 成功升至 Lv." + level + (upgraded > 1 ? " (+" + upgraded + "级)" : "") + "，获得 " + pointsGained + " 点可分配属性！");
        result.put("level", level);
        result.put("exp", curExp);
        result.put("upgraded", upgraded);
        result.put("attrPointsGained", pointsGained);
        result.put("attrPoints", officer.getAttrPoints());
        return result;
    }

    // ================================================================
    //  assignAttr - 玩家自主分配军官属性点
    // ================================================================

    @Transactional
    public Map<String, Object> assignAttr(Long playerId, Long officerId, String attr, int points) {
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (points <= 0) {
            result.put("success", false);
            result.put("message", "分配点数必须大于 0");
            return result;
        }
        if (!"military".equals(attr) && !"defense".equals(attr) && !"logistics".equals(attr) && !"knowledge".equals(attr)) {
            result.put("success", false);
            result.put("message", "未知的属性类型: " + attr);
            return result;
        }

        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        int available = officer.getAttrPoints() != null ? officer.getAttrPoints() : 0;
        if (points > available) {
            result.put("success", false);
            result.put("message", "可分配点数不足，当前仅有 " + available + " 点");
            return result;
        }

        int currentVal;
        String attrName;
        if ("military".equals(attr)) {
            currentVal = officer.getMilitary() != null ? officer.getMilitary() : 0;
            attrName = "军事";
        } else if ("defense".equals(attr)) {
            currentVal = officer.getDefense() != null ? officer.getDefense() : 0;
            attrName = "防御";
        } else if ("logistics".equals(attr)) {
            currentVal = officer.getLogistics() != null ? officer.getLogistics() : 0;
            attrName = "后勤";
        } else {
            currentVal = officer.getKnowledge() != null ? officer.getKnowledge() : 0;
            attrName = "学识";
        }

        int maxAdd = Math.max(0, ATTR_MAX - currentVal);
        if (maxAdd <= 0) {
            result.put("success", false);
            result.put("message", attrName + " 已达到上限 (" + ATTR_MAX + ")");
            return result;
        }

        int actualAdd = Math.min(points, maxAdd);
        int newVal = currentVal + actualAdd;
        if ("military".equals(attr)) {
            officer.setMilitary(newVal);
        } else if ("defense".equals(attr)) {
            officer.setDefense(newVal);
        } else if ("logistics".equals(attr)) {
            officer.setLogistics(newVal);
        } else {
            officer.setKnowledge(newVal);
        }

        // 记录真实加点方向，洗点不再按星级猜测出生资质。
        switch (attr) {
            case "military" -> officer.setAssignedMilitary(officer.getAssignedMilitary() + actualAdd);
            case "defense" -> officer.setAssignedDefense(officer.getAssignedDefense() + actualAdd);
            case "logistics" -> officer.setAssignedLogistics(officer.getAssignedLogistics() + actualAdd);
            case "knowledge" -> officer.setAssignedKnowledge(officer.getAssignedKnowledge() + actualAdd);
        }
        officer.setAttrPoints(available - actualAdd);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", attrName + " +" + actualAdd + "，剩余可分配点: " + officer.getAttrPoints());
        result.put("attr", attr);
        result.put("added", actualAdd);
        result.put("newValue", newVal);
        result.put("attrPoints", officer.getAttrPoints());
        return result;
    }

    // ================================================================
    //  wash - 洗点(消耗200黄金，重置初始属性并全额返还属性点)
    // ================================================================

    @Transactional
    public Map<String, Object> wash(Long playerId, Long officerId) {
        economy.settleEconomyBeforeChange(playerId);
        if (garrisonFood != null) garrisonFood.beforeChange(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        int refund = officer.assignedPoints();
        if (refund == 0) {
            return Map.of("success", false, "message", officer.isLegacyAttrAllocation()
                    ? "历史加点未记录方向，保留原有属性；此后分配的点数均可重置"
                    : "没有已分配的属性点，无需洗点");
        }
        int cost = 200;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null || getGold(res) < cost) {
            return Map.of("success", false, "message", "黄金不足(需 200 黄金)");
        }
        setGold(res, getGold(res) - cost);
        resourcesRepository.save(res);
        officer.setMilitary(officer.getMilitary() - officer.getAssignedMilitary());
        officer.setDefense(officer.getDefense() - officer.getAssignedDefense());
        officer.setLogistics(officer.getLogistics() - officer.getAssignedLogistics());
        officer.setKnowledge(officer.getKnowledge() - officer.getAssignedKnowledge());
        officer.setAssignedMilitary(0);
        officer.setAssignedDefense(0);
        officer.setAssignedLogistics(0);
        officer.setAssignedKnowledge(0);
        int totalPoints = (officer.getAttrPoints() == null ? 0 : officer.getAttrPoints()) + refund;
        officer.setAttrPoints(totalPoints);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", officer.getName() + " 洗点成功！保留军官资质，返还 " + refund + " 点可分配属性点");
        result.put("attrPoints", totalPoints);
        result.put("military", officer.getMilitary());
        result.put("defense", officer.getDefense());
        result.put("logistics", officer.getLogistics());
        result.put("knowledge", officer.getKnowledge());
        return result;
    }

    @Transactional
    public Map<String, Object> rename(Long playerId, Long officerId, String rawName) {
        Map<String, Object> result = new LinkedHashMap<>();
        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        if (rawName == null || rawName.isBlank()) {
            result.put("success", false);
            result.put("message", "军官名称不能为空");
            return result;
        }

        String newName;
        try {
            newName = com.wargame.model.constants.DisplayNamePolicy.validate(rawName, 12, "军官名称");
        } catch (IllegalArgumentException error) {
            result.put("success", false);
            result.put("message", error.getMessage());
            return result;
        }

        // 行锁让两个同时到达的改名请求共用同一份当日额度。
        entityManager.refresh(officer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (newName.equals(officer.getName())) {
            result.put("success", false);
            result.put("message", "新名称与当前军官名称相同");
            return result;
        }
        long now = GameClock.now();
        if (NameChangePolicy.nextAllowedAt(officer.getNameRenamedAt(), now) > now) {
            result.put("success", false);
            result.put("message", "这名军官今天已改名，请明日 0:00 后再试（按游戏时间，停服顺延）");
            return result;
        }

        // 消耗判定：优先消耗军官改名卡，若无军官改名卡则尝试消耗 60 黄金
        int consumedCard = playerItemRepository.tryConsume(playerId, "renameCard", 1, GameClock.now());
        boolean usedCard = consumedCard > 0;
        int goldCost = 60;
        if (!usedCard) {
            Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
            if (res == null || getGold(res) < goldCost) {
                result.put("success", false);
                result.put("message", "军官改名卡或黄金不足（需 1 张军官改名卡或 " + goldCost + " 黄金）");
                return result;
            }
            setGold(res, getGold(res) - goldCost);
            resourcesRepository.save(res);
        }

        String oldName = officer.getName();
        officer.setName(newName);
        officer.setNameRenamedAt(now);
        officerRepository.save(officer);
        try {
            questService.onEvent(playerId, "OFFICER_RENAME", null, 1);
        } catch (Exception ignored) {}

        result.put("success", true);
        result.put("message", usedCard
                ? "消耗 1 张军官改名卡，改名成功: " + oldName + " → " + newName
                : "消耗 " + goldCost + " 黄金，改名成功: " + oldName + " → " + newName);
        result.put("usedCard", usedCard);
        result.put("officerId", officer.getId());
        result.put("name", newName);
        return result;
    }

    @org.springframework.beans.factory.annotation.Autowired private EquipmentService equipmentService;
    @org.springframework.beans.factory.annotation.Autowired private OfficerEquipmentProgressService equipmentProgress;

    /** 单件穿卸与属性接口委托共享结算，避免详情漏算防御或共鸣阶段。 */
    @Transactional
    public Map<String, Object> equip(Long playerId, Long officerId, String itemId) {
        return equipmentService.equip(playerId, officerId, itemId);
    }
    @Transactional
    public Map<String, Object> unequip(Long playerId, Long officerId, String itemId) {
        return equipmentService.unequip(playerId, officerId, itemId);
    }
    public Map<String, Object> computeAttributes(Long playerId, Long officerId) {
        var officer = findOfficer(playerId, officerId);
        if (officer == null) return new LinkedHashMap<>(Map.of("success", false, "message", "军官不存在"));
        var a = equipmentService.attributes(officer);
        return new LinkedHashMap<>(Map.of("success", true, "military", a.military(), "defense", a.defense(),
                "logistics", a.logistics(), "knowledge", a.knowledge(), "bonuses", a.bonuses(),
                "equipment", a.equipment().stream().map(e -> equipmentService.equipmentView(e)).toList()));
    }

    /** 军校整批出现一名五星的概率：每级增加 0.3%，1 级 0.3%，10 级满级 3%。 */
    public static int recruitCost(int star) { return star * RECRUIT_COST_PER_STAR; }
    public static int rewardCost(int star, int level) { return star * 50 + level * 2; }

    /** 技能自由组合，仅限制槽位和重复；预览与指定学习共用。 */
    public static String skillLearningReason(List<Map<String, Object>> owned, String skillId) {
        if (owned.size() >= MAX_SKILLS) return "技能已满3个，请先废弃一个";
        for (var skill : owned) {
            var definition = OfficerSkillDef.getSkill(Objects.toString(skill.get("id"), ""));
            if (skillId.equals(definition == null ? skill.get("id") : definition.key())) return "已掌握";
        }
        return "";
    }

    public static double academyFiveStarBatchChance(int academyLevel) {
        return AcademyRecruitmentRules.fiveStarBatchChance(academyLevel, 0);
    }

    /** 未提供军事理论的旧调用保持仅使用军校基础概率。 */
    List<Map<String, Object>> genAcademyCandidates(int academyLevel, RandomGenerator rng) {
        return genAcademyCandidates(academyLevel, 0, rng);
    }

    /** 建筑与科研概率相加后整批只判定一次；命中最多一名五星名将。 */
    List<Map<String, Object>> genAcademyCandidates(int academyLevel, int theoryLevel, RandomGenerator rng) {
        boolean hasFiveStar = rng.nextDouble() < AcademyRecruitmentRules.fiveStarBatchChance(academyLevel, theoryLevel);
        int fiveStarSlot = hasFiveStar ? rng.nextInt(ACADEMY_CANDIDATE_COUNT) : -1;
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> batchNames = new HashSet<>();
        for (int i = 0; i < ACADEMY_CANDIDATE_COUNT; i++) {
            int star = 5;
            if (i != fiveStarSlot) {
                // 保留旧普通招募中 1～4 星的相对权重，排除再次独立抽出五星。
                double roll = rng.nextDouble() * 0.985;
                star = roll < 0.5 ? 1 : roll < 0.78 ? 2 : roll < 0.92 ? 3 : 4;
            }
            list.add(genOfficerWithStar(star, ThreadLocalRandom.current(), batchNames));
        }
        return list;
    }

    /** 道具生成沿用既有规则；普通军校刷新使用整批概率入口。 */
    public Map<String, Object> genOfficer(int liaisonLv) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // JS: var starBias = liaisonLv || 0
        int starBias = liaisonLv;
        // JS: var starRoll = Math.random() + starBias * 0.04
        double starRoll = rng.nextDouble() + starBias * 0.04;
        // JS: star = starRoll < 0.5 ? 1 : (starRoll < 0.78 ? 2 : (starRoll < 0.92 ? 3 : (starRoll < 0.985 ? 4 : 5)))
        int star;
        if (starRoll < 0.5) star = 1;
        else if (starRoll < 0.78) star = 2;
        else if (starRoll < 0.92) star = 3;
        else if (starRoll < 0.985) star = 4;
        else star = 5;

        return genOfficerWithStar(star, rng, null);
    }

    public Map<String, Object> genOfficerWithStar(int star) {
        return genOfficerWithStar(star, ThreadLocalRandom.current(), null);
    }

    /** 先确定星级，再生成对应初始属性与技能。五星军官必然使用名将姓名，非五星随机生成 */
    public Map<String, Object> genOfficerWithStar(int star, RandomGenerator rng, Set<String> excludeNames) {
        String name = OfficerNameGenerator.generateOfficerName(star, rng, excludeNames);
        if (excludeNames != null) {
            excludeNames.add(name);
        }

        int military;
        int defense;
        int logistics;
        int knowledge;
        int base;

        if (star >= 5) {
            base = 120;
            int specialtyRoll = rng.nextInt(4); // 0: military, 1: defense, 2: logistics, 3: knowledge
            int mainAttr = 111 + rng.nextInt(10); // 111 ~ 120 (极品满级可达 219)
            int subAttr1 = 50 + rng.nextInt(51);  // 50 ~ 100
            int subAttr2 = 50 + rng.nextInt(51);  // 50 ~ 100
            int subAttr3 = 50 + rng.nextInt(51);  // 50 ~ 100

            if (specialtyRoll == 0) {
                military = mainAttr;
                defense = subAttr1;
                logistics = subAttr2;
                knowledge = subAttr3;
            } else if (specialtyRoll == 1) {
                defense = mainAttr;
                military = subAttr1;
                logistics = subAttr2;
                knowledge = subAttr3;
            } else if (specialtyRoll == 2) {
                logistics = mainAttr;
                military = subAttr1;
                defense = subAttr2;
                knowledge = subAttr3;
            } else {
                knowledge = mainAttr;
                military = subAttr1;
                defense = subAttr2;
                logistics = subAttr3;
            }
        } else {
            // JS: var base = 30 + star * 12 + Math.floor(Math.random() * 10)
            base = 30 + star * 12 + rng.nextInt(10);

            // JS: logistics = rollAttr(base, 10) = min(ATTR_MAX, base + floor(random*10) + 10)
            logistics = Math.min(ATTR_MAX, base + rng.nextInt(10) + 10);
            military = Math.min(ATTR_MAX, base + rng.nextInt(10) - 8);
            defense = Math.min(ATTR_MAX, base + rng.nextInt(10) - 6);
            knowledge = Math.min(ATTR_MAX, base + rng.nextInt(10) - 5);
        }

        // JS: genSkills(star)
        List<Map<String, Object>> skills = genSkills(star, rng);

        // JS: loyalty = 60 + Math.floor(Math.random() * 40)
        int loyalty = 60 + rng.nextInt(40);

        // JS: salary = star * 10 + Math.floor(base / 10)
        int salary = star * 10 + base / 10;

        // JS: bio = genBio(name, star)
        String bio = genBio(name);

        Map<String, Object> officer = new LinkedHashMap<>();
        officer.put("name", name);
        officer.put("star", star);
        officer.put("level", 1);
        officer.put("bio", bio);
        officer.put("logistics", logistics);
        officer.put("military", military);
        officer.put("defense", defense);
        officer.put("knowledge", knowledge);
        officer.put("skills", skills);
        officer.put("loyalty", loyalty);
        officer.put("salary", salary);
        officer.put("role", "idle");
        return officer;
    }

    // ================================================================
    //  genSkills - 1-5星军官默认都带1个技能，在当前技能库中随机挑选一个
    // ================================================================

    private List<Map<String, Object>> genSkills(int star, RandomGenerator rng) {
        List<String> pool = new ArrayList<>(GameData.OFFICER_SKILLS.keySet());
        if (pool.isEmpty()) {
            return new ArrayList<>();
        }

        String sid = pool.get(rng.nextInt(pool.size()));
        OfficerSkillDef skDef = GameData.OFFICER_SKILLS.get(sid);
        int lv = skDef.max();

        Map<String, Object> sk = new LinkedHashMap<>();
        sk.put("id", sid);
        sk.put("lv", lv);

        List<Map<String, Object>> picked = new ArrayList<>();
        picked.add(sk);
        return picked;
    }

    // ================================================================
    //  genBio - 对应 JS genBio(name, star)
    // ================================================================

    private String genBio(String name) {
        // Check historical officers first
        for (HistoricalOfficers.Officer h : GameData.HISTORICAL_OFFICERS) {
            if (h.name().equals(name)) {
                return h.fullName() + "（" + h.birth() + "-" + h.death() + "）\n"
                        + "国籍: " + h.nation() + "  最高军衔: " + h.rank() + "\n"
                        + h.bio();
            }
        }
        // Generic bio
        return name + " " + GENERIC_BIOS.get(ThreadLocalRandom.current().nextInt(GENERIC_BIOS.size()));
    }

    // ================================================================
    //  Helper methods
    // ================================================================

    /** 写锁内刷新技能快照，避免预先加载的军官覆盖并发提交。 */
    private Officer findOfficerForSkillChange(Long playerId, Long officerId) {
        Officer officer = findOfficer(playerId, officerId);
        if (officer != null) entityManager.refresh(officer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return officer;
    }

    private Academy getOrCreateAcademy(Long playerId) {
        return academyRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElseGet(() -> {
            Academy a = new Academy();
            a.setPlayerId(playerId);
            a.setCitySlot(cityScope.slot(playerId));
            a.setOfficers("[]");
            return academyRepository.save(a);
        });
    }

    private Officer findOfficer(Long playerId, Long officerId) {
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        for (Officer o : officers) {
            if (o.getId().equals(officerId)) return o;
        }
        return null;
    }

    private List<Map<String, Object>> parseSkills(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        return new ArrayList<>(JsonUtil.parseList(json));
    }

    private int buildingLevel(Long playerId, String buildingType) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), buildingType);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    private long getGold(Resources r) {
        return r.getGold() != null ? r.getGold() : 0L;
    }

    private void setGold(Resources r, long value) {
        r.setGold(value);
    }

    private int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private String roleText(String role) {
        return switch (role) {
            case "mayor" -> "市长";
            case "commander" -> "指挥官";
            case "commander_in_chief" -> "司令官";
            default -> "闲置";
        };
    }

    private String starToStr(int star) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < star; i++) sb.append("★");
        for (int i = star; i < 5; i++) sb.append("☆");
        return sb.toString();
    }
}
