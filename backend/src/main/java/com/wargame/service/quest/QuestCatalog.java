package com.wargame.service.quest;

import java.util.List;

/** 主线任务与新手引导步骤的静态定义。 */
public final class QuestCatalog {
    private QuestCatalog() {}

    public record Chapter(String id, String name, String intro, List<Quest> quests) {}
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
    public record GuideStep(String id, String title, String body, String nextRoute, int order,
                            String goal, String checkType, String checkKey, int checkValue,
                            String targetBuilding, Reward reward) {}

    public static final List<Chapter> CHAPTERS = List.of(
        new Chapter("ch1", "第一章 · 开荒奠基", "建设主城根基，建立第一支部队。", List.of(
            q("q1_1", "ch1", "升级民居", "把民居升到 2 级，提升人口上限", "BUILD_UPGRADE_DONE", "house", 2, r(0,0,0,0,200), null),
            q("q1_2", "ch1", "扩建农田", "建造第 2 座农田，保障粮食供应", "BUILD_COUNT", "farm", 2, r(3000,0,0,0,200), "q1_1"),
            q("q1_3", "ch1", "炼钢起步", "把炼钢厂升到 2 级", "BUILD_UPGRADE_DONE", "refinery", 2, r(0,3000,0,0,250), "q1_2"),
            q("q1_4", "ch1", "招兵买马", "训练 30 个步兵", "ARMY_RECRUIT", "infantry", 30, r(3000,0,0,0,300), "q1_3"),
            q("q1_5", "ch1", "统帅初现", "招募 1 名军官", "OFFICER_RECRUIT", null, 1, new Reward(0,0,0,0,300,0,"1",null,null,0), "q1_4"),
            q("q1_6", "ch1", "委以重任", "任命一名军官为市长或指挥官", "OFFICER_APPOINT", null, 1, r(2000,2000,1000,0,300), "q1_5")
        )),
        new Chapter("ch2", "第二章 · 站稳脚跟", "走出主城，侦察、采集并清理周边威胁。", List.of(
            q("q2_1", "ch2", "扩建民居", "民居总等级达到 5", "BUILD_LEVEL_SUM", "house", 5, r(4000,2000,0,0,500), "q1_6"),
            q("q2_2", "ch2", "炮兵连", "训练 20 个炮兵", "ARMY_RECRUIT", "artillery", 20, r(0,3000,1000,0,500), "q2_1"),
            q("q2_3", "ch2", "前线侦察", "派出侦察兵完成 1 次侦查", "SCOUT_COMPLETE", null, 1, r(1000,1000,500,0,400), "q2_2"),
            q("q2_4", "ch2", "远征采集", "完成 1 次野外资源采集并运回主城", "GATHER_COMPLETE", null, 1, r(3000,2000,1000,200,500), "q2_3"),
            q("q2_5", "ch2", "肃清流寇", "击败 1 个流寇据点", "BANDIT_DEFEAT", null, 1, new Reward(0,0,0,0,500,0,"1",null,null,0), "q2_4"),
                q("q2_6", "ch2", "占领野地", "占领 1 块资源野地", "WILD_CLAIM", null, 1, r(5000,2000,0,0,500), "q2_5")
        )),
        new Chapter("ch3", "第三章 · 开疆扩土", "补齐资源产能，打造攻守兼备的基地。", List.of(
            q("q3_1", "ch3", "稀矿起步", "将稀有矿升到 2 级", "BUILD_UPGRADE_DONE", "raremine", 2, r(0,0,0,500,600), "q2_6"),
            q("q3_2", "ch3", "油田上马", "将油田升到 2 级", "BUILD_UPGRADE_DONE", "oilfield", 2, r(0,0,1000,500,700), "q3_1"),
            q("q3_3", "ch3", "兵强马壮", "总兵力达到 100", "ARMY_TOTAL", null, 100, r(5000,3000,2000,0,800), "q3_2"),
            q("q3_4", "ch3", "城防初具", "将城墙升到 2 级", "BUILD_UPGRADE_DONE", "wall", 2, r(0,3000,0,0,600), "q3_3"),
            q("q3_5", "ch3", "名将加盟", "招募 1 名 3 星或以上军官", "OFFICER_RECRUIT_STAR", null, 3, new Reward(0,0,0,0,1500,0,"1","1",null,0), "q3_4")
        )),
        new Chapter("ch4", "第四章 · 阵营争锋", "完成从备战到宣战的第一次战争循环。", List.of(
            q("q4_1", "ch4", "高级兵工厂", "军工厂总等级达到 4", "BUILD_LEVEL_SUM", "factory", 4, r(0,5000,2000,0,1000), "q3_5"),
            q("q4_2", "ch4", "雄狮之师", "总兵力达到 300", "ARMY_TOTAL", null, 300, new Reward(5000,5000,3000,500,1500,0,null,"1",null,0), "q4_1"),
            q("q4_3", "ch4", "正式宣战", "向其他玩家主城宣战", "WAR_DECLARE", null, 1, new Reward(0,0,0,0,2000,0,"2",null,"shield",1), "q4_2"),
            q("q4_4", "ch4", "首战告捷", "赢得 1 场玩家城战斗", "PLAYER_WIN", null, 1, new Reward(10000,5000,2000,500,3000,0,"2","1","renameCard",1), "q4_3")
        ))
    );

    public static final List<GuideStep> NEWBIE_STEPS = List.of(
        g("g_welcome", "欢迎来到山河远征录", "我是您的作战参谋。完成训练营后，您将拥有一座能生产、能防守的主城。", null, 0, "开始新手训练营", "NONE", null, 0, null, new Reward(0,0,0,0,0,0,null,null,null,0)),
        g("g_upgrade_command", "第一步 · 升级市政厅", "升级市政厅到 2 级，解锁更高等级的建设。", "buildArmy", 1, "市政厅等级 ≥ 2", "BUILD_LEVEL", "command", 2, "command", r(2000,1500,0,0,100)),
        g("g_build_house", "第二步 · 建造民居", "把民居升到 2 级，增加人口上限。", "buildArmy", 2, "民居等级 ≥ 2", "BUILD_LEVEL", "house", 2, "house", r(1500,500,0,0,50)),
        g("g_build_farm", "第三步 · 建造农田", "建造并升级农田，为军队提供粮食。", "buildRes", 3, "农田等级 ≥ 2", "BUILD_LEVEL", "farm", 2, "farm", r(2000,0,0,0,50)),
        g("g_build_refinery", "第四步 · 建造炼钢厂", "钢铁是建造和训练的核心资源。", "buildRes", 4, "炼钢厂等级 ≥ 2", "BUILD_LEVEL", "refinery", 2, "refinery", r(0,2500,0,0,50)),
        g("g_build_oilfield", "第五步 · 建造石油基地", "石油支撑机动部队和高级军工生产。", "buildRes", 5, "石油基地等级 ≥ 1", "BUILD_LEVEL", "oilfield", 1, "oilfield", r(0,0,1500,0,50)),
        g("g_build_factory", "第六步 · 建造军工厂", "军工厂是训练地面部队的前置建筑。", "buildArmy", 6, "军工厂等级 ≥ 1", "BUILD_LEVEL", "factory", 1, "factory", r(0,1500,500,0,80)),
        g("g_recruit_infantry", "第七步 · 训练步兵", "训练 20 个步兵，建立第一支守军。", "buildArmy", 7, "步兵累计 ≥ 20", "ARMY_RECRUIT", "infantry", 20, "factory", r(0,0,0,0,150)),
        g("g_recruit_officer", "第八步 · 招募军官", "军官可以显著提升部队与城市能力。", "buildArmy", 8, "拥有 ≥ 1 名军官", "OFFICER_RECRUIT", null, 1, "academy", new Reward(0,0,0,0,200,0,"1",null,null,0)),
        g("g_appoint_mayor", "第九步 · 任命市长", "任命军官管理主城，提升发展效率。", "buildArmy", 9, "已任命 1 名市长", "OFFICER_APPOINT", "mayor", 1, "staff", r(2000,2000,1000,0,200)),
        g("g_done", "新手训练营 · 毕业", "恭喜您掌握了主城建设、军队训练和军官任用。接下来沿主线任务扩张领土吧。", null, 99, "已毕业", "NONE", null, 0, null, new Reward(5000,5000,2000,0,500,0,"1","1",null,0))
    );

    private static Quest q(String id, String chapter, String title, String desc, String event, String key, int target, Reward reward, String requires) { return new Quest(id, chapter, title, desc, event, key, target, reward, requires); }
    private static Reward r(int food, int steel, int oil, int rare, int gold) { return new Reward(food, steel, oil, rare, gold, 0, null, null, null, 0); }
    private static GuideStep g(String id, String title, String body, String route, int order, String goal, String type, String key, int value, String building, Reward reward) { return new GuideStep(id, title, body, route, order, goal, type, key, value, building, reward); }
    public static Quest findQuest(String id) { return CHAPTERS.stream().flatMap(c -> c.quests().stream()).filter(q -> q.id().equals(id)).findFirst().orElse(null); }
    public static String firstQuestOf(String chapterId) { return CHAPTERS.stream().filter(c -> c.id().equals(chapterId)).findFirst().map(c -> c.quests().get(0).id()).orElse(null); }
    public static String nextQuestInChapter(String id) { for (Chapter c : CHAPTERS) for (int i = 0; i < c.quests().size(); i++) if (c.quests().get(i).id().equals(id)) return i + 1 < c.quests().size() ? c.quests().get(i + 1).id() : null; return null; }
}
