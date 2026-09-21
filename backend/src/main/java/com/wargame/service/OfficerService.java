package com.wargame.service;

import com.wargame.model.constants.GameConstants;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.HistoricalOfficers;
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.MarchRepository marches;
    private final OfficerRepository officerRepository;
    private final AcademyRepository academyRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerItemRepository playerItemRepository;
    private final OfficerEquipmentRepository equipmentRepository;
    private final com.wargame.service.quest.QuestService questService;

    /** 军官属性上限 - 对应 JS G.ATTR_MAX */
    private static final int ATTR_MAX = 219;
    /** 军官等级上限 - 对应 JS G.OFFICER_MAX_LEVEL */
    private static final int OFFICER_MAX_LEVEL = 100;
    /** 军校刷新费用 - 对应 JS refreshAcademy 中 var cost = 200（约 6.7 小时黄金产出） */
    private static final int ACADEMY_REFRESH_COST = 200;
    /** 军校刷新冷却 (1小时) - 对应 JS 60 * 60 * 1000 */
    private static final long ACADEMY_REFRESH_COOLDOWN = 60 * 60 * 1000L;
    /** 军校候选人数量 - 对应 JS var count = 7 */
    private static final int ACADEMY_COUNT = 7;
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
                          com.wargame.service.quest.QuestService questService) {
        this.officerRepository = officerRepository;
        this.academyRepository = academyRepository;
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

        long now = System.currentTimeMillis();
        int academyLv = buildingLevel(playerId, "academy");
        if (academyLv <= 0) {
            result.put("success", false);
            result.put("message", "请先建造军校");
            return result;
        }
        Academy academy = getOrCreateAcademy(playerId);
        long refreshAt = academy.getRefreshAt() != null ? academy.getRefreshAt() : 0L;

        // JS: if (!force && s.academy.refreshAt > now) - 冷却中
        if (refreshAt > now) {
            long mins = (long) Math.ceil((refreshAt - now) / 60000.0);
            result.put("success", false);
            result.put("message", "军校 " + mins + " 分钟后可再次刷新");
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

        List<Map<String, Object>> list = genAcademyCandidates(academyLv, ThreadLocalRandom.current());

        academy.setOfficers(JsonUtil.toJson(list));
        academy.setRefreshAt(now + ACADEMY_REFRESH_COOLDOWN);
        academyRepository.save(academy);

        result.put("success", true);
        result.put("message", "军校已刷新");
        result.put("list", list);
        result.put("refreshAt", academy.getRefreshAt());
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

    @Transactional
    public Map<String, Object> recruit(Long playerId, int officerIdx) {
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
        int cost = star * RECRUIT_COST_PER_STAR;
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
        officer.setRecruitAt(System.currentTimeMillis());
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

    @Transactional
    public Map<String, Object> appoint(Long playerId, Long officerId, String role) {
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

        if (marches.existsByPlayerIdAndCommanderId(playerId, officerId)) throw new IllegalArgumentException("军官正在行军，无法任命");
        String currentRole = target.getRole() != null ? target.getRole() : "idle";

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
            setGold(res, getGold(res) + refund);
            resourcesRepository.save(res);
        }

        String name = target.getName();
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

        long now = System.currentTimeMillis();
        long rewardAt = officer.getRewardAt() != null ? officer.getRewardAt() : 0L;

        // JS: if (o.rewardCoolAt && now < o.rewardCoolAt)
        if (rewardAt > 0 && now < rewardAt) {
            long remainMin = (long) Math.ceil((rewardAt - now) / 60000.0);
            result.put("success", false);
            result.put("message", "赏赐冷却中，还需 " + remainMin + " 分钟");
            return result;
        }

        // JS: var cost = (o.star || 1) * 50 + (o.level || 1) * 2
        int star = officer.getStar() != null ? officer.getStar() : 1;
        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        int cost = star * 50 + level * 2;

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
        result.put("message", "赏赐成功! 忠诚度 +" + gain + "，消耗黄金" + cost + "，30分钟后可再次赏赐");
        result.put("loyalty", officer.getLoyalty());
        result.put("cost", cost);
        return result;
    }

    // ================================================================
    //  learnSkill - 对应 JS G.Officer.learnSkill
    // ================================================================

    @Transactional
    public Map<String, Object> learnSkill(Long playerId, Long officerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        // Server-side consumption: must own at least 1 skill book.
        // Historical BUG: client only tracked skill book count locally,
        // so a refresh-tab would let players learn skills indefinitely.
        int consumed = playerItemRepository.tryConsume(playerId, "skillBook", 1, System.currentTimeMillis());
        if (consumed == 0) {
            result.put("success", false);
            result.put("message", "技能书不足");
            return result;
        }

        // Parse current skills
        List<Map<String, Object>> skills = parseSkills(officer.getSkills());

        // JS: if (o.skills.length >= 3)
        if (skills.size() >= MAX_SKILLS) {
            // Refund: we just consumed a book, give it back since we won't add a skill.
            playerItemRepository.tryConsume(playerId, "skillBook", -1, System.currentTimeMillis());
            result.put("success", false);
            result.put("message", "技能已满3个，请先废弃一个");
            return result;
        }

        // JS: build pool of unowned skills
        Set<String> owned = new HashSet<>();
        for (Map<String, Object> sk : skills) {
            Object id = sk.get("id");
            if (id != null) owned.add(id.toString());
        }

        List<String> pool = new ArrayList<>();
        for (String sid : GameData.OFFICER_SKILLS.keySet()) {
            if (!owned.contains(sid)) pool.add(sid);
        }

        if (pool.isEmpty()) {
            // Refund the skill book we just consumed.
            playerItemRepository.tryConsume(playerId, "skillBook", -1, System.currentTimeMillis());
            result.put("success", false);
            result.put("message", "已学完全部技能");
            return result;
        }

        // JS: var pickId = pool[Math.floor(Math.random() * pool.length)]
        String pickId = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        OfficerSkillDef pickInfo = GameData.OFFICER_SKILLS.get(pickId);

        // JS: var lv = 1 + Math.floor(Math.random() * Math.max(1, o.star))
        int star = officer.getStar() != null ? officer.getStar() : 1;
        int lv = 1 + ThreadLocalRandom.current().nextInt(Math.max(1, star));
        if (lv > pickInfo.max()) lv = pickInfo.max();

        Map<String, Object> newSkill = new LinkedHashMap<>();
        newSkill.put("id", pickId);
        newSkill.put("lv", lv);
        skills.add(newSkill);

        officer.setSkills(JsonUtil.toJson(skills));
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", "学习成功: " + pickInfo.name() + " Lv." + lv + " (消耗1本技能书)");
        result.put("skillId", pickId);
        result.put("skillName", pickInfo.name());
        result.put("level", lv);
        return result;
    }

    // ================================================================
    //  abandonSkill - 对应 JS G.Officer.forgetSkill (confirm action)
    // ================================================================

    @Transactional
    public Map<String, Object> abandonSkill(Long playerId, Long officerId, int skillIdx) {
        Map<String, Object> result = new LinkedHashMap<>();

        Officer officer = findOfficer(playerId, officerId);
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

    @Transactional
    public Map<String, Object> useExpBook(Long playerId, Long officerId, String itemId, int count) {
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

        // 如果未指定 itemId，智能判断：优先 expBook，若无则尝试 expBookAdv，再尝试 expBookMax
        if (itemId == null || itemId.isBlank()) {
            int cntNormal = playerItemRepository.findByPlayerIdAndItemKey(playerId, "expBook")
                    .map(it -> it.getCount() != null ? it.getCount() : 0).orElse(0);
            if (cntNormal >= count) {
                itemId = "expBook";
            } else {
                int cntAdv = playerItemRepository.findByPlayerIdAndItemKey(playerId, "expBookAdv")
                        .map(it -> it.getCount() != null ? it.getCount() : 0).orElse(0);
                if (cntAdv >= count) {
                    itemId = "expBookAdv";
                } else {
                    int cntMax = playerItemRepository.findByPlayerIdAndItemKey(playerId, "expBookMax")
                            .map(it -> it.getCount() != null ? it.getCount() : 0).orElse(0);
                    if (cntMax >= 1) {
                        itemId = "expBookMax";
                    } else {
                        itemId = "expBook";
                    }
                }
            }
        }

        if ("expBookMax".equals(itemId)) {
            int consumed = playerItemRepository.tryConsume(playerId, "expBookMax", 1, System.currentTimeMillis());
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

        boolean isAdv = "expBookAdv".equals(itemId);
        String bookName = isAdv ? "高级经验书" : "经验书";
        int singleGain = isAdv ? 100000 : 10000;

        int consumed = playerItemRepository.tryConsume(playerId, itemId, count, System.currentTimeMillis());
        if (consumed == 0) {
            result.put("success", false);
            result.put("message", bookName + "不足");
            return result;
        }

        int totalGain = singleGain * count;
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
            result.put("message", "军官已达最高等级 (Lv.100)");
            return result;
        }

        long curExp = officer.getExp() != null ? officer.getExp() : 0L;
        int upgraded = 0;

        if (all) {
            while (level < 100) {
                long need = (long) level * 200;
                if (curExp < need) break;
                curExp -= need;
                level++;
                upgraded++;
            }
        } else {
            long need = (long) level * 200;
            if (curExp >= need) {
                curExp -= need;
                level++;
                upgraded = 1;
            }
        }

        if (upgraded == 0) {
            long need = (long) level * 200;
            result.put("success", false);
            result.put("message", "经验不足，升级需要 " + need + " 经验");
            return result;
        }

        if (level >= 100) {
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
        Map<String, Object> result = new LinkedHashMap<>();
        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) {
            result.put("success", false);
            result.put("message", "军官不存在");
            return result;
        }

        int cost = 200;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null || getGold(res) < cost) {
            result.put("success", false);
            result.put("message", "黄金不足(需 " + cost + " 黄金)");
            return result;
        }

        setGold(res, getGold(res) - cost);
        resourcesRepository.save(res);

        int star = officer.getStar() != null ? officer.getStar() : 1;
        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        int totalPoints = Math.max(0, level - 1);

        if (star >= 5) {
            int curMil = officer.getMilitary() != null ? officer.getMilitary() : 0;
            int curDef = officer.getDefense() != null ? officer.getDefense() : 0;
            int curLog = officer.getLogistics() != null ? officer.getLogistics() : 0;
            int curKno = officer.getKnowledge() != null ? officer.getKnowledge() : 0;
            int maxVal = Math.max(Math.max(curMil, curDef), Math.max(curLog, curKno));
            int mainBase = 120;
            if (curMil == maxVal) {
                officer.setMilitary(mainBase);
                officer.setDefense(75);
                officer.setLogistics(75);
                officer.setKnowledge(75);
            } else if (curDef == maxVal) {
                officer.setDefense(mainBase);
                officer.setMilitary(75);
                officer.setLogistics(75);
                officer.setKnowledge(75);
            } else if (curLog == maxVal) {
                officer.setLogistics(mainBase);
                officer.setMilitary(75);
                officer.setDefense(75);
                officer.setKnowledge(75);
            } else {
                officer.setKnowledge(mainBase);
                officer.setMilitary(75);
                officer.setDefense(75);
                officer.setLogistics(75);
            }
        } else {
            int base = 30 + star * 12;
            officer.setMilitary(base);
            officer.setDefense(base);
            officer.setLogistics(base);
            officer.setKnowledge(base);
        }

        officer.setAttrPoints(totalPoints);
        officerRepository.save(officer);

        result.put("success", true);
        result.put("message", officer.getName() + " 洗点成功！属性恢复初始值，返还 " + totalPoints + " 点可分配属性点");
        result.put("attrPoints", totalPoints);
        result.put("military", officer.getMilitary());
        result.put("defense", officer.getDefense());
        result.put("logistics", officer.getLogistics());
        result.put("knowledge", officer.getKnowledge());
        return result;
    }

    @Transactional
    public Map<String, Object> equip(Long playerId, Long officerId, String itemId) {
        Officer officer = findOfficer(playerId, officerId);
        OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(itemId);
        if (officer == null) return equipmentError("军官不存在");
        if (def == null) return equipmentError("无效的装备");
        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        if (level < def.requiredLevel()) return equipmentError("军官等级不足，需要 Lv." + def.requiredLevel());
        if (playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis()) == 0) {
            return equipmentError("装备不在库存中");
        }

        // 同槽位只能装备一件，若已有装备则自动卸下返还背包，并就地复用记录更新（避免 Hibernate flush 顺序导致的唯一键冲突）
        List<OfficerEquipment> current = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId);
        OfficerEquipment existingSameSlot = null;
        for (OfficerEquipment old : current) {
            OfficerEquipmentDef oldDef = OfficerEquipmentDef.ITEMS.get(old.getItemKey());
            if (oldDef != null && oldDef.slot().equals(def.slot())) {
                addToInventory(playerId, old.getItemKey());
                if (existingSameSlot == null) {
                    existingSameSlot = old;
                } else {
                    equipmentRepository.delete(old);
                    equipmentRepository.flush();
                }
            }
        }

        long now = System.currentTimeMillis();
        OfficerEquipment equipment;
        if (existingSameSlot != null) {
            // 复用已有实体直接更新字段，发出 UPDATE 语句，杜绝先删后插时的 uk_officer_equipment_slot 唯一键冲突
            equipment = existingSameSlot;
        } else {
            equipment = new OfficerEquipment();
            equipment.setPlayerId(playerId);
            equipment.setOfficerId(officerId);
            equipment.setCreatedAt(now);
            officer.setEquipmentCount((officer.getEquipmentCount() != null ? officer.getEquipmentCount() : 0) + 1);
        }

        equipment.setItemKey(def.key());
        equipment.setSetType(def.setKey());
        equipment.setTier(def.tier());
        equipment.setSlot(def.slot());
        equipment.setMilitaryBonus(def.military());
        equipment.setLogisticsBonus(def.logistics());
        equipment.setKnowledgeBonus(def.knowledge());
        equipment.setEquippedAt(now);
        equipmentRepository.saveAndFlush(equipment);
        officerRepository.save(officer);
        return equipmentSuccess("已为 " + officer.getName() + " 装备 " + def.name());
    }

    @Transactional
    public Map<String, Object> unequip(Long playerId, Long officerId, String itemId) {
        Officer officer = findOfficer(playerId, officerId);
        if (officer == null) return equipmentError("军官不存在");
        OfficerEquipment equipment = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId).stream()
                .filter(e -> itemId != null && itemId.equals(e.getItemKey())).findFirst().orElse(null);
        if (equipment == null) return equipmentError("该装备未穿戴");
        addToInventory(playerId, equipment.getItemKey());
        equipmentRepository.delete(equipment);
        equipmentRepository.flush();
        officer.setEquipmentCount(Math.max(0, (officer.getEquipmentCount() != null ? officer.getEquipmentCount() : 0) - 1));
        officerRepository.save(officer);
        OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(itemId);
        return equipmentSuccess("已卸下 " + (def != null ? def.name() : itemId));
    }

    /**
     * 计算军官装备后的总属性（含套装加成）。
     * 用于前端展示及战斗属性叠加。
     */
    public Map<String, Object> computeAttributes(Long playerId, Long officerId) {
        Officer officer = findOfficer(playerId, officerId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (officer == null) { result.put("success", false); result.put("message", "军官不存在"); return result; }
        int military = officer.getMilitary() != null ? officer.getMilitary() : 0;
        int logistics = officer.getLogistics() != null ? officer.getLogistics() : 0;
        int knowledge = officer.getKnowledge() != null ? officer.getKnowledge() : 0;
        List<OfficerEquipment> equipped = equipmentRepository.findByPlayerIdAndOfficerId(playerId, officerId);
        Map<String, List<OfficerEquipment>> sets = new HashMap<>();
        for (OfficerEquipment e : equipped) {
            military += e.getMilitaryBonus() != null ? e.getMilitaryBonus() : 0;
            logistics += e.getLogisticsBonus() != null ? e.getLogisticsBonus() : 0;
            knowledge += e.getKnowledgeBonus() != null ? e.getKnowledgeBonus() : 0;
            sets.computeIfAbsent(e.getSetType(), k -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> bonuses = new ArrayList<>();
        for (Map.Entry<String, List<OfficerEquipment>> entry : sets.entrySet()) {
            if (entry.getValue().size() < 3) continue;
            OfficerEquipmentDef def = OfficerEquipmentDef.ITEMS.get(entry.getValue().get(0).getItemKey());
            if (def == null) continue;
            int setMain = def.tier() == 1 ? 3 : def.tier() == 2 ? 15 : 30;
            int all = def.tier() == 3 ? 5 : 0;
            if (def.branch().equals("military")) military += setMain;
            if (def.branch().equals("logistics")) logistics += setMain;
            if (def.branch().equals("knowledge")) knowledge += setMain;
            military += all; logistics += all; knowledge += all;
            Map<String, Object> bonus = new LinkedHashMap<>();
            bonus.put("setName", def.setName());
            bonus.put("description", "集齐3件：" + branchName(def.branch()) + "+" + setMain + (all > 0 ? "，全属性+" + all : ""));
            bonus.put("military", (def.branch().equals("military") ? setMain : 0) + all);
            bonus.put("logistics", (def.branch().equals("logistics") ? setMain : 0) + all);
            bonus.put("knowledge", (def.branch().equals("knowledge") ? setMain : 0) + all);
            bonuses.add(bonus);
        }
        result.put("success", true);
        result.put("military", military);
        result.put("logistics", logistics);
        result.put("knowledge", knowledge);
        result.put("bonuses", bonuses);
        result.put("equipment", equipped.stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("itemKey", e.getItemKey());
            OfficerEquipmentDef d = OfficerEquipmentDef.ITEMS.get(e.getItemKey());
            em.put("name", d != null ? d.name() : e.getItemKey());
            em.put("setType", e.getSetType());
            em.put("tier", e.getTier());
            em.put("military", e.getMilitaryBonus());
            em.put("logistics", e.getLogisticsBonus());
            em.put("knowledge", e.getKnowledgeBonus());
            return em;
        }).toList());
        return result;
    }

    private void addToInventory(Long playerId, String itemKey) {
        long now = System.currentTimeMillis();
        playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey).ifPresentOrElse(item -> {
            item.setCount(item.getCount() + 1);
            item.setUpdatedAt(now);
            playerItemRepository.save(item);
        }, () -> {
            PlayerItem item = new PlayerItem();
            item.setPlayerId(playerId);
            item.setItemKey(itemKey);
            item.setCount(1);
            item.setUpdatedAt(now);
            playerItemRepository.save(item);
        });
    }

    private static Map<String, Object> equipmentError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> equipmentSuccess(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private static String branchName(String branch) {
        return switch (branch) { case "military" -> "军事"; case "logistics" -> "后勤"; default -> "学识"; };
    }

    /** 军校整批出现一名五星的概率：每级增加 0.3%，1 级 0.3%，10 级满级 3%。 */
    public static double academyFiveStarBatchChance(int academyLevel) {
        if (academyLevel <= 0) return 0;
        return Math.min(10, academyLevel) * 0.003;
    }

    /** 整批只判定一次五星；命中后随机放入一个位置，其余候选人仅生成 1～4 星。 */
    List<Map<String, Object>> genAcademyCandidates(int academyLevel, RandomGenerator rng) {
        boolean hasFiveStar = rng.nextDouble() < academyFiveStarBatchChance(academyLevel);
        int fiveStarSlot = hasFiveStar ? rng.nextInt(ACADEMY_COUNT) : -1;
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < ACADEMY_COUNT; i++) {
            int star = 5;
            if (i != fiveStarSlot) {
                // 保留旧普通招募中 1～4 星的相对权重，排除再次独立抽出五星。
                double roll = rng.nextDouble() * 0.985;
                star = roll < 0.5 ? 1 : roll < 0.78 ? 2 : roll < 0.92 ? 3 : 4;
            }
            list.add(genOfficerWithStar(star));
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

        return genOfficerWithStar(star);
    }

    /** 先确定星级，再生成对应初始属性与技能，避免仅改星标导致品质不匹配。 */
    private Map<String, Object> genOfficerWithStar(int star) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<String> names = GameConstants.OFFICER_NAMES;
        String name = names.get(rng.nextInt(names.size()));

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
        List<Map<String, Object>> skills = genSkills(star);

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

    private List<Map<String, Object>> genSkills(int star) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<String> pool = new ArrayList<>(GameData.OFFICER_SKILLS.keySet());
        if (pool.isEmpty()) {
            return new ArrayList<>();
        }

        String sid = pool.get(rng.nextInt(pool.size()));
        OfficerSkillDef skDef = GameData.OFFICER_SKILLS.get(sid);
        int lv = Math.min(skDef.max(), 1 + rng.nextInt(Math.max(1, star)));

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

    private Academy getOrCreateAcademy(Long playerId) {
        return academyRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElseGet(() -> {
            Academy a = new Academy();
            a.setPlayerId(playerId);
            a.setCitySlot(cityScope.slot(playerId));
            a.setRefreshAt(0L);
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

    private int getGold(Resources r) {
        return r.getGold() != null ? r.getGold() : 0;
    }

    private void setGold(Resources r, int value) {
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
