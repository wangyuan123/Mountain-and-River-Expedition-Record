package com.wargame.service.quest;

import java.util.List;
import com.wargame.model.constants.TechDef;

/** 主线任务与阶段引导的静态定义。 */
public final class QuestCatalog {
    private QuestCatalog() {}

    public record Chapter(String id, String name, String intro, String giftKey, String giftName, List<Quest> quests) {}
    public record Quest(String id, String chapterId, String title, String desc, String eventType,
                        String targetKey, int targetValue, Reward reward, String requires) {
        public boolean hasRequirement() { return requires != null && !requires.isBlank(); }
    }
    public record Reward(int food, int steel, int oil, int rare, int gold, int exp,
                         String skillBook, String expBook, String itemKey, int itemCount) {
        public boolean isEmpty() {
            return food == 0 && steel == 0 && oil == 0 && rare == 0 && gold == 0 && exp == 0
                    && (skillBook == null || skillBook.isBlank())
                    && (expBook == null || expBook.isBlank())
                    && (itemKey == null || itemKey.isBlank() || itemCount == 0);
        }
    }

    public static final List<Chapter> CHAPTERS = List.of(
        // =====================================================================
        //  第一阶段 · 初立基业
        // =====================================================================
        new Chapter("stage1", "第一阶段 · 初立基业", "破晓开荒，初掌军政，打造主城基石与首支步兵卫队。", "stage_gift_1", "新兵奠基礼包", List.of(
            q("q1_1", "stage1", "万丈高楼", "建造或升级民居至 2 级，提升城镇人口上限", "BUILD_UPGRADE_DONE", "house", 2, r(0, 1000, 0, 0, 200), null),
            q("q1_2", "stage1", "拓荒开垦", "建造或升级农田至 2 级，保障部队粮饷供给", "BUILD_UPGRADE_DONE", "farm", 2, r(2000, 0, 0, 0, 200), "q1_1"),
            q("q1_3", "stage1", "钢铁脊梁", "建造或升级炼钢厂至 2 级，提供重工业基础原料", "BUILD_UPGRADE_DONE", "refinery", 2, r(0, 2000, 0, 0, 200), "q1_2"),
            q("q1_4", "stage1", "招兵买马", "在军工厂训练 100 名步兵，组建首支主力部队", "ARMY_TOTAL", "infantry", 100, r(3000, 1000, 0, 0, 300), "q1_3"),
            q("q1_5", "stage1", "更名立威", "更换一次统帅名称，确立三军威名", "DISPLAY_NAME_CHANGE", null, 1, r(1000, 1000, 1000, 0, 200), "q1_4"),
            q("q1_6", "stage1", "基地命名", "更改一次主城城市名称，确立根据地名号", "CITY_NAME_CHANGE", null, 1, r(1000, 1000, 1000, 0, 200), "q1_5"),
            q("q1_7", "stage1", "军校提拔", "在军校招募并为一名军官更名", "OFFICER_RENAME", null, 1, r(2000, 2000, 1000, 0, 300), "q1_6"),
            q("q1_8", "stage1", "参谋建言", "在军情论坛发布 1 次帖子参与战略交流", "FORUM_POST", null, 1, r(1000, 1000, 500, 0, 200), "q1_7"),
            q("q1_9", "stage1", "受衔晋升", "积累声望达到 200，晋升为上等兵", "MILITARY_RANK_TIER", null, 2, r(3000, 3000, 1000, 0, 400), "q1_8")
        )),

        // =====================================================================
        //  第二阶段 · 厉兵秣马
        // =====================================================================
        new Chapter("stage2", "第二阶段 · 厉兵秣马", "战备扩建，走出主城，侦察与讨伐野地，选择夺取资源或占领，开拓第二座分城。", "stage_gift_2", "拔寨起步礼包", List.of(
            q("q2_1", "stage2", "石油开采", "建造或升级石油基地至 3 级，储备机械化燃油", "BUILD_UPGRADE_DONE", "oilfield", 3, r(0, 3000, 3000, 0, 400), null),
            q("q2_2", "stage2", "稀矿冶炼", "建造或升级稀矿厂至 2 级，储备尖端军工合金", "BUILD_UPGRADE_DONE", "raremine", 2, r(0, 0, 1000, 500, 400), "q2_1"),
            q("q2_3", "stage2", "野地侦察", "派出侦察部队完成 1 次野地侦察", "SCOUT_WILD", null, 1, r(1000, 1000, 500, 0, 300), "q2_2"),
            q("q2_4", "stage2", "野地取资", "讨伐野地，选择「掠夺」并成功取得资源", "WILD_PLUNDER", null, 1, r(3000, 2000, 1000, 0, 400), "q2_3"),
            q("q2_5", "stage2", "占领野地", "讨伐野地，选择「征服」并占领成功", "WILD_CONQUER", null, 1, r(5000, 3000, 1000, 500, 500), "q2_4"),
            q("q2_6", "stage2", "研习战术", "在科研中心将军事攻击科技研发至 2 级", "TECH_LEVEL", "attack_tech", 2, r(3000, 3000, 1000, 0, 500), "q2_5"),
            q("q2_7", "stage2", "千军成列", "陆军各兵种部队总数达到 1,000", "ARMY_TOTAL", null, 1000, r(5000, 4000, 2000, 0, 600), "q2_6"),
            q("q2_8", "stage2", "晋升中士", "统帅军衔晋升至中士（第4阶，解锁双城名额）", "MILITARY_RANK_TIER", null, 4, r(5000, 5000, 2000, 500, 600), "q2_7"),
            q("q2_9", "stage2", "筑造分城", "在占领的丘陵地形建立第 2 座城池，双城联动", "CITY_COUNT", null, 2, r(10000, 10000, 5000, 1000, 1000), "q2_8")
        )),

        // =====================================================================
        //  第三阶段 · 初露锋芒
        // =====================================================================
        new Chapter("stage3", "第三阶段 · 初露锋芒", "海空并起，招揽名将，战术指挥击破敌军据点并俘虏敌将。", "stage_gift_3", "整军备战礼包", List.of(
            q("q3_1", "stage3", "鹰击长空", "建造空军基地并制造 50 架战机（侦察机/战斗机）", "ARMY_AIR_TOTAL", null, 50, r(5000, 8000, 5000, 500, 800), null),
            q("q3_2", "stage3", "碧海启航", "建造军港和造船厂并修造 20 艘驱逐舰", "ARMY_NAVY_TOTAL", null, 20, r(5000, 10000, 5000, 1000, 800), "q3_1"),
            q("q3_3", "stage3", "名将效命", "招募或拥有 1 名二战历史名将（5星将领）", "HISTORICAL_OFFICER", null, 1, new Reward(5000, 5000, 3000, 1000, 1500, 0, "1", "1", null, 0), "q3_2"),
            q("q3_4", "stage3", "运筹帷幄", "在战役交火中下达指令，亲自指挥 1 次战术战斗", "BATTLE_COMMAND", null, 1, r(3000, 3000, 2000, 0, 500), "q3_3"),
            // 取消 NPC 城市后沿用任务 ID、奖励和前置条件，保留已完成及已领取的旧进度。
            q("q3_5", "stage3", "击败陆地寇", "讨伐陆地寇并获胜", "BANDIT_DEFEAT", null, 1, r(10000, 8000, 4000, 1000, 1000), "q3_4"),
            q("q3_6", "stage3", "生擒敌帅", "在战斗获胜中俘虏 1 名敌军将领", "CAPTIVE_OFFICER", null, 1, new Reward(5000, 5000, 3000, 500, 800, 0, null, "1", "loyaltyBox", 1), "q3_5"),
            q("q3_7", "stage3", "晋升准尉", "统帅军衔晋升至准尉（第7阶，解锁三城名额）", "MILITARY_RANK_TIER", null, 7, r(8000, 8000, 4000, 1000, 1000), "q3_6"),
            q("q3_8", "stage3", "鼎足之势", "拥有城池数量达到 3 座", "CITY_COUNT", null, 3, r(15000, 15000, 8000, 2000, 1200), "q3_7")
        )),

        // =====================================================================
        //  第四阶段 · 阵营角力
        // =====================================================================
        new Chapter("stage4", "第四阶段 · 阵营角力", "与敌对玩家全面对决，侦查、宣战与讨伐，选择夺取资源或接管城市，兵力突破万人大关。", "stage_gift_4", "初露锋芒礼包", List.of(
            q("q4_1", "stage4", "谍影重重", "派出侦察部队侦查 1 次其他玩家主城", "SCOUT_PLAYER", null, 1, r(5000, 5000, 3000, 500, 800), null),
            q("q4_2", "stage4", "正式宣战", "向敌对阵营玩家城池正式宣战 1 次", "WAR_DECLARE", null, 1, r(8000, 8000, 5000, 1000, 1000), "q4_1"),
            q("q4_3", "stage4", "兵临城下", "讨伐玩家城市，选择「掠夺」并取得胜利", "PLAYER_PLUNDER", null, 1, r(10000, 10000, 6000, 1000, 1500), "q4_2"),
            q("q4_4", "stage4", "破城克敌", "讨伐玩家城市，选择「征服」并接管成功", "PLAYER_CONQUER", null, 1, r(15000, 15000, 8000, 1500, 2000), "q4_3"),
            q("q4_5", "stage4", "万人雄师", "全军部队总数达到 10,000（1w 兵力大关）", "ARMY_TOTAL", null, 10000, r(20000, 20000, 10000, 2000, 2500), "q4_4"),
            q("q4_6", "stage4", "名望雀起", "统帅声望达到 10,000（1w 声望大关）", "PRESTIGE_VALUE", null, 10000, r(10000, 10000, 5000, 1000, 1500), "q4_5"),
            q("q4_7", "stage4", "坚固防线", "建造或升级城防（城墙）至 5 级", "BUILD_UPGRADE_DONE", "wall", 5, r(10000, 15000, 5000, 1000, 1200), "q4_6"),
            q("q4_8", "stage4", "四海扬名", "军衔达到上尉（第10阶），并拥有 4 座城池", "CITY_COUNT", null, 4, r(25000, 25000, 12000, 3000, 3000), "q4_7")
        )),

        // =====================================================================
        //  第五阶段 · 三军齐发
        // =====================================================================
        new Chapter("stage5", "第五阶段 · 三军齐发", "军事科技深化，深海舰队破浪，装甲洪流奔涌，五城据点鼎盛。", "stage_gift_5", "百战精锐礼包", List.of(
            q("q5_1", "stage5", "坚壁科技", "科研中心军事防御科技研发至 5 级", "TECH_LEVEL", "defense_tech", 5, r(20000, 20000, 10000, 2000, 2000), null),
            q("q5_2", "stage5", "舰船动力", "航海科研中心舰船动力科技研发至 5 级", "TECH_LEVEL", "nav_engine", 5, r(20000, 25000, 15000, 3000, 2500), "q5_1"),
            q("q5_3", "stage5", "深蓝巡洋", "海军部队总数达到 5,000（驱逐舰/潜艇/战列舰）", "ARMY_NAVY_TOTAL", null, 5000, r(30000, 40000, 20000, 5000, 3000), "q5_2"),
            q("q5_4", "stage5", "装甲洪流", "制造 1,000 辆坦克战车（轻型坦克/重型坦克/突击炮）", "ARMY_ARMOR_TOTAL", null, 1000, r(30000, 30000, 20000, 3000, 2500), "q5_3"),
            q("q5_5", "stage5", "军阵浩荡", "全军总兵力达到 50,000", "ARMY_TOTAL", null, 50000, r(40000, 40000, 25000, 5000, 4000), "q5_4"),
            q("q5_6", "stage5", "名镇一方", "统帅军衔晋升至少校（第11阶，声望达到45,000）", "MILITARY_RANK_TIER", null, 11, r(50000, 50000, 30000, 8000, 5000), "q5_5"),
            q("q5_7", "stage5", "五城拱卫", "拥有城池数量达到 5 座", "CITY_COUNT", null, 5, r(60000, 60000, 35000, 10000, 6000), "q5_6")
        )),

        // =====================================================================
        //  第六阶段 · 雄师百万
        // =====================================================================
        new Chapter("stage6", "第六阶段 · 雄师百万", "十万兵力大关破茧成蝶，大洋舰队破浪前行，晋升少将坐镇六城。", "stage_gift_6", "横扫千军礼包", List.of(
            q("q6_1", "stage6", "十万雄狮", "全军总兵力达到 100,000（10w 兵力大关）", "ARMY_TOTAL", null, 100000, r(80000, 80000, 50000, 10000, 8000), null),
            q("q6_2", "stage6", "大洋舰队", "海军部队总数达到 20,000（战列舰/航母特混舰队）", "ARMY_NAVY_TOTAL", null, 20000, r(100000, 120000, 60000, 15000, 10000), "q6_1"),
            q("q6_3", "stage6", "威震八荒", "统帅声望达到 100,000（10w 声望大关）", "PRESTIGE_VALUE", null, 100000, r(80000, 80000, 40000, 10000, 8000), "q6_2"),
            q("q6_4", "stage6", "高阶科研", "至少 5 项科技研发等级达到 8 级以上", "TECH_LEVEL_COUNT", null, 5, r(100000, 100000, 60000, 15000, 10000), "q6_3"),
            q("q6_5", "stage6", "宏伟首府", "主城市政厅升级至 15 级", "BUILD_UPGRADE_DONE", "command", 15, r(120000, 120000, 60000, 15000, 12000), "q6_4"),
            q("q6_6", "stage6", "拜领少将", "统帅军衔晋升至少将（第15阶，声望达到600,000）", "MILITARY_RANK_TIER", null, 15, r(150000, 150000, 80000, 20000, 15000), "q6_5"),
            q("q6_7", "stage6", "六城呼应", "拥有城池数量达到 6 座", "CITY_COUNT", null, 6, r(200000, 200000, 100000, 30000, 20000), "q6_6")
        )),

        // =====================================================================
        //  第七阶段 · 征战天下
        // =====================================================================
        new Chapter("stage7", "第七阶段 · 征战天下", "五十万大军席卷平原，十万水师控制深蓝，核心建筑与科技迈向极境。", "stage_gift_7", "威震四海礼包", List.of(
            q("q7_1", "stage7", "半壁江山", "全军总兵力达到 500,000（50w 兵力大关）", "ARMY_TOTAL", null, 500000, r(300000, 300000, 150000, 40000, 30000), null),
            q("q7_2", "stage7", "十万水师", "海军部队总数达到 100,000（10w 海军大关）", "ARMY_NAVY_TOTAL", null, 100000, r(400000, 500000, 250000, 60000, 40000), "q7_1"),
            q("q7_3", "stage7", "巅峰科研", "至少 3 项科技研发至各自满级", "TECH_MAX_COUNT", null, 3, r(300000, 300000, 200000, 50000, 35000), "q7_2"),
            q("q7_4", "stage7", "筑城极境", "主城市政厅升级至满级（20级）", "BUILD_UPGRADE_DONE", "command", 20, r(500000, 500000, 300000, 80000, 50000), "q7_3"),
            q("q7_5", "stage7", "金汤要塞", "城防升级至满级（20级）", "BUILD_UPGRADE_DONE", "wall", 20, r(400000, 600000, 200000, 60000, 40000), "q7_4"),
            q("q7_6", "stage7", "中将督军", "统帅军衔晋升至中将（第16阶，声望达到1,200,000）", "MILITARY_RANK_TIER", null, 16, r(600000, 600000, 400000, 100000, 60000), "q7_5"),
            q("q7_7", "stage7", "七星连珠", "拥有城池数量达到 7 座", "CITY_COUNT", null, 7, r(800000, 800000, 500000, 120000, 80000), "q7_6"),
            q("q7_8", "stage7", "威加海内", "统帅声望达到 500,000", "PRESTIGE_VALUE", null, 500000, r(500000, 500000, 300000, 80000, 50000), "q7_7")
        )),

        // =====================================================================
        //  第八阶段 · 山河远征
        // =====================================================================
        new Chapter("stage8", "第八阶段 · 山河远征", "全建筑满级，全科技满级，百万大军，三十万舰队，八城大成，最高军衔【上将】！", "stage_gift_8", "三军统帅大礼包", List.of(
            q("q8_1", "stage8", "核心满级", "主城全套核心工业与军事建筑均升至满级", "BUILD_ALL_MAX", null, 1, r(1000000, 1000000, 600000, 150000, 100000), null),
            q("q8_2", "stage8", "科技巅峰", "全部 " + TechDef.TECHS.size() + " 种军工科技全部研发至满级", "TECH_ALL_MAX", null, TechDef.TECHS.size(), r(1500000, 1500000, 1000000, 250000, 150000), "q8_1"),
            q("q8_3", "stage8", "百万雄师", "全军总兵力突破 1,000,000（100w 终极兵力）", "ARMY_TOTAL", null, 1000000, r(2000000, 2000000, 1200000, 300000, 200000), "q8_2"),
            q("q8_4", "stage8", "大洋主宰", "海军舰队总数突破 300,000", "ARMY_NAVY_TOTAL", null, 300000, r(2500000, 3000000, 1500000, 400000, 250000), "q8_3"),
            q("q8_5", "stage8", "八荒统御", "拥有全部 8 座城池（达到城池上限）", "CITY_COUNT", null, 8, r(3000000, 3000000, 2000000, 500000, 300000), "q8_4"),
            q("q8_6", "stage8", "旷世名望", "统帅声望突破 1,000,000（100w 声望大关）", "PRESTIGE_VALUE", null, 1000000, r(2000000, 2000000, 1500000, 400000, 200000), "q8_5"),
            q("q8_7", "stage8", "终极荣耀", "晋升至最高统帅军衔【上将】！", "MILITARY_RANK_TIER", null, 17, r(5000000, 5000000, 3000000, 800000, 500000), "q8_6")
        ))
    );

    private static Quest q(String id, String chapter, String title, String desc, String event, String key, int target, Reward reward, String requires) {
        // 军衔任务仍以正式授衔为完成条件，描述从晋升定义生成，避免数值漂移。
        if ("MILITARY_RANK_TIER".equals(event)) desc = rankDescription(target);
        if ("q4_8".equals(id)) desc = rankDescription(10) + "，并拥有4座城池";
        return new Quest(id, chapter, title, desc, event, key, target, reward, requires);
    }

    private static String rankDescription(int tier) {
        var rank = com.wargame.model.constants.MilitaryRankDef.getRank(tier);
        int count = com.wargame.model.constants.RankExamDef.questionCount(tier);
        int correct = com.wargame.model.constants.RankExamDef.requiredCorrect(tier);
        return "逐阶晋升至" + rank.name() + "（第" + tier + "阶）：本阶需声望" + rank.prestige()
                + "、累计剿寇胜场" + rank.npcVictories() + "、" + rank.certificateName()
                + "×1，以及军事笔试答对至少" + correct + "/" + count + "题；凭证来源："
                + com.wargame.model.constants.MilitaryRankDef.certificateSource(rank);
    }
    private static Reward r(int food, int steel, int oil, int rare, int gold) {
        return new Reward(food, steel, oil, rare, gold, 0, null, null, null, 0);
    }
    public static Quest findQuest(String id) {
        return CHAPTERS.stream().flatMap(c -> c.quests().stream()).filter(q -> q.id().equals(id)).findFirst().orElse(null);
    }
    public static Chapter findChapter(String stageId) {
        return CHAPTERS.stream().filter(c -> c.id().equals(stageId)).findFirst().orElse(null);
    }
    public static String nextChapterId(String currentStageId) {
        for (int i = 0; i < CHAPTERS.size(); i++) {
            if (CHAPTERS.get(i).id().equals(currentStageId)) {
                return i + 1 < CHAPTERS.size() ? CHAPTERS.get(i + 1).id() : null;
            }
        }
        return null;
    }
    public static String firstQuestOf(String chapterId) {
        return CHAPTERS.stream().filter(c -> c.id().equals(chapterId)).findFirst().map(c -> c.quests().get(0).id()).orElse(null);
    }
    public static String nextQuestInChapter(String id) {
        for (Chapter c : CHAPTERS) {
            for (int i = 0; i < c.quests().size(); i++) {
                if (c.quests().get(i).id().equals(id)) {
                    return i + 1 < c.quests().size() ? c.quests().get(i + 1).id() : null;
                }
            }
        }
        return null;
    }
}
