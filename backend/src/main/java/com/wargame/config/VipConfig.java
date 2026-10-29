package com.wargame.config;

import java.util.*;

/**
 * 战时最高军委会·战略后勤特勤委任（VIP 特许系统）核心配置
 */
public final class VipConfig {

    private VipConfig() {}

    public record VipLevelDef(
            int level,
            long requiredCents,
            String title,
            int buildQueuePerCity,
            int techQueuePerCity,
            double woundedGoldDiscount,
            int mapFavoriteLimit,
            int marchPresetLimit,
            double buildSpeedBonus,
            boolean customAvatarUnlocked,
            boolean allCitiesAppeaseUnlocked,
            int storedDailySalariesDays,
            Map<String, Integer> dailyRewards,
            Map<String, Integer> levelPackRewards
    ) {}

    public record VipMilestoneDef(
            long milestoneCents,
            String title,
            Map<String, Integer> rewards
    ) {}

    private static final List<VipLevelDef> LEVELS = new ArrayList<>();
    private static final List<VipMilestoneDef> MILESTONES = new ArrayList<>();

    private static Map<String, Integer> mapOf(Object... kvs) {
        if (kvs == null || kvs.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], (Integer) kvs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    static {
        // 每日四类资源从VIP4起每级增加10万，不叠加资源包；黄金从VIP0的1万起每级增加1万，道具与晋升礼包独立。
        // VIP 0
        LEVELS.add(new VipLevelDef(
                0, 0L, "预备指挥官",
                3, 1, 0.0, 10, 2, 0.0,
                false, false, 0,
                mapOf("gold", 10000, "food", 10000, "steel", 10000, "oil", 10000, "rare", 10000, "speedUp10m", 2, "skillBook", 1, "populationOrder", 1),
                mapOf()
        ));

        // VIP 1 (¥6 = 600 cents)
        LEVELS.add(new VipLevelDef(
                1, 600L, "特遣队指挥官",
                5, 1, 0.0, 10, 2, 0.0,
                false, false, 0,
                mapOf("gold", 20000, "food", 20000, "steel", 20000, "oil", 20000, "rare", 20000, "speedUp10m", 3, "skillBook", 1, "populationOrder", 1, "marchOrd", 1),
                mapOf("recruitOrd", 1, "gold", 400000, "shield2h", 6, "shield", 2, "starUp", 20, "skillBook", 3, "speedUp10m", 10, "expBook", 5)
        ));

        // VIP 2 (¥30 = 3000 cents)
        LEVELS.add(new VipLevelDef(
                2, 3000L, "前线先锋官",
                5, 1, 0.0, 15, 2, 0.05,
                false, false, 0,
                mapOf("gold", 30000, "food", 50000, "steel", 50000, "oil", 50000, "rare", 50000, "speedUp10m", 6, "expBook", 2, "skillBook", 2, "populationOrder", 2, "marchOrd", 1),
                mapOf("recruitOrd", 2, "teleportNormal", 2, "gold", 800000, "shield2h", 8, "shield", 4, "starUp", 40, "skillBook", 6, "speedUp1h", 6, "expBook", 10)
        ));

        // VIP 3 (¥98 = 9800 cents)
        LEVELS.add(new VipLevelDef(
                3, 9800L, "战略支柱长官",
                5, 1, 0.20, 20, 3, 0.05,
                true, false, 0,
                mapOf("gold", 40000, "food", 100000, "steel", 100000, "oil", 100000, "rare", 100000, "speedUp1h", 2, "expBook", 3, "skillBook", 2, "populationOrder", 2, "marchOrd", 1, "signalJammingOrder", 1),
                mapOf("recruitOrd", 3, "teleportNormal", 3, "gold", 1200000, "shield2h", 8, "shield", 6, "shield12h", 2, "commanderRenameCard", 1, "starUp", 60, "skillBook", 9, "speedUp1h", 12, "speedUp5h", 2, "expBookAdv", 2)
        ));

        // VIP 4 (¥198 = 19800 cents)
        LEVELS.add(new VipLevelDef(
                4, 19800L, "方面军参谋长",
                5, 1, 0.20, 30, 3, 0.05,
                true, false, 0,
                mapOf("gold", 50000, "food", 200000, "steel", 200000, "oil", 200000, "rare", 200000, "speedUp1h", 3, "speedUp10m", 3, "expBook", 4, "skillBook", 3, "populationOrder", 3, "marchOrd", 2, "signalJammingOrder", 1),
                mapOf("recruitOrd", 4, "teleportNormal", 4, "gold", 1600000, "shield2h", 10, "shield", 8, "shield12h", 4, "shield24h", 1, "commanderRenameCard", 1, "starUp", 100, "skillBook", 12, "speedUp5h", 5, "expBookAdv", 4)
        ));

        // VIP 5 (¥328 = 32800 cents)
        LEVELS.add(new VipLevelDef(
                5, 32800L, "兵团副司令官",
                5, 2, 0.30, 30, 4, 0.08,
                true, false, 3,
                mapOf("gold", 60000, "food", 300000, "steel", 300000, "oil", 300000, "rare", 300000, "speedUp5h", 1, "speedUp1h", 2, "expBookAdv", 1, "skillBook", 3, "populationOrder", 3, "emergencyPopulationOrder", 1, "marchOrd", 2, "signalJammingOrder", 1, "feintOrder", 1),
                mapOf("recruitOrd", 5, "teleportNormal", 5, "gold", 2000000, "shield2h", 12, "shield", 10, "shield12h", 6, "shield24h", 2, "starUp", 150, "skillBook", 15, "speedUp5h", 10, "expBookAdv", 6)
        ));

        // VIP 6 (¥648 = 64800 cents)
        LEVELS.add(new VipLevelDef(
                6, 64800L, "兵团总司令官",
                5, 2, 0.30, 40, 4, 0.08,
                true, true, 3,
                mapOf("gold", 70000, "food", 400000, "steel", 400000, "oil", 400000, "rare", 400000, "speedUp5h", 2, "expBookAdv", 2, "skillBook", 4, "populationOrder", 4, "emergencyPopulationOrder", 1, "marchOrd", 2, "signalJammingOrder", 2, "feintOrder", 1),
                mapOf("recruitOrd", 6, "teleportNormal", 6, "gold", 2400000, "shield", 12, "shield12h", 8, "shield24h", 4, "shield72h", 1, "teleportAdvanced", 2, "starUp", 200, "skillBook", 18, "speedUp5h", 8, "speedUp12h", 2, "expBookAdv", 10, "supplyPack", 3)
        ));

        // VIP 7 (¥1280 = 128000 cents)
        LEVELS.add(new VipLevelDef(
                7, 128000L, "战区副长官",
                5, 2, 0.40, 50, 5, 0.10,
                true, true, 3,
                mapOf("gold", 80000, "food", 500000, "steel", 500000, "oil", 500000, "rare", 500000, "shield", 1, "speedUp12h", 1, "expBookAdv", 3, "skillBook", 4, "populationOrder", 4, "emergencyPopulationOrder", 2, "marchOrd", 3, "signalJammingOrder", 2, "feintOrder", 2),
                mapOf("recruitOrd", 7, "teleportNormal", 7, "gold", 2800000, "shield", 16, "shield12h", 12, "shield24h", 6, "shield72h", 2, "teleportAdvanced", 3, "starUp", 280, "skillBook", 21, "universalSkillBook", 1, "speedUp12h", 4, "expBookMax", 2)
        ));

        // VIP 8 (¥3000 = 300000 cents)
        LEVELS.add(new VipLevelDef(
                8, 300000L, "战区最高司令",
                5, 2, 0.40, 50, 5, 0.12,
                true, true, 3,
                mapOf("gold", 90000, "food", 600000, "steel", 600000, "oil", 600000, "rare", 600000, "shield12h", 1, "speedUp12h", 1, "speedUp5h", 1, "expBookAdv", 4, "skillBook", 5, "populationOrder", 5, "emergencyPopulationOrder", 2, "marchOrd", 3, "signalJammingOrder", 3, "feintOrder", 2),
                mapOf("recruitOrd", 8, "teleportNormal", 8, "gold", 3200000, "shield", 16, "shield12h", 16, "shield24h", 8, "shield72h", 3, "teleportAdvanced", 4, "starUp", 360, "skillBook", 24, "universalSkillBook", 2, "speedUp12h", 6, "expBookMax", 3)
        ));

        // VIP 9 (¥6000 = 600000 cents)
        LEVELS.add(new VipLevelDef(
                9, 600000L, "战时常务长官",
                5, 2, 0.50, 60, 6, 0.15,
                true, true, 3,
                mapOf("gold", 100000, "food", 700000, "steel", 700000, "oil", 700000, "rare", 700000, "shield12h", 1, "shield", 1, "speedUp12h", 2, "expBookAdv", 5, "skillBook", 5, "populationOrder", 5, "emergencyPopulationOrder", 3, "marchOrd", 4, "signalJammingOrder", 3, "feintOrder", 3),
                mapOf("recruitOrd", 9, "teleportNormal", 9, "gold", 3600000, "shield", 20, "shield12h", 20, "shield24h", 10, "shield72h", 4, "teleportAdvanced", 5, "starUp", 450, "skillBook", 27, "universalSkillBook", 3, "speedUp24h", 4, "expBookMax", 5)
        ));

        // VIP 10 (¥10000 = 1000000 cents)
        LEVELS.add(new VipLevelDef(
                10, 1000000L, "特级上将·五星统帅",
                5, 2, 0.50, 80, 6, 0.15,
                true, true, 3,
                mapOf("gold", 110000, "food", 800000, "steel", 800000, "oil", 800000, "rare", 800000, "shield24h", 1, "speedUp24h", 1, "speedUp12h", 1, "expBookAdv", 8, "skillBook", 6, "populationOrder", 6, "emergencyPopulationOrder", 3, "marchOrd", 5, "signalJammingOrder", 4, "feintOrder", 4),
                mapOf("recruitOrd", 10, "teleportNormal", 10, "gold", 4000000, "shield", 24, "shield12h", 24, "shield24h", 12, "shield72h", 6, "teleportAdvanced", 8, "starUp", 600, "skillBook", 30, "universalSkillBook", 5, "speedUp36h", 4, "expBookMax", 8, "supplyPack", 5)
        ));

        // 微里程碑 (VIP 7~10 区间)
        MILESTONES.add(new VipMilestoneDef(150000L, "特勤战备军需箱 A",
                mapOf("gold", 500000, "speedUp5h", 2, "teleportNormal", 2, "starUp", 10, "expBookAdv", 2)));
        MILESTONES.add(new VipMilestoneDef(200000L, "特勤战备军需箱 B",
                mapOf("gold", 800000, "speedUp12h", 1, "recruitOrd", 2, "starUp", 15, "skillBook", 3)));
        MILESTONES.add(new VipMilestoneDef(250000L, "特勤战备军需箱 C",
                mapOf("gold", 1000000, "speedUp12h", 2, "teleportAdvanced", 1, "starUp", 20, "skillBook", 5)));

        MILESTONES.add(new VipMilestoneDef(400000L, "统帅将星战备箱 A",
                mapOf("gold", 1500000, "speedUp24h", 1, "teleportAdvanced", 2, "starUp", 25, "universalSkillBook", 1)));
        MILESTONES.add(new VipMilestoneDef(500000L, "统帅将星战备箱 B",
                mapOf("gold", 2000000, "speedUp24h", 1, "speedUp36h", 1, "starUp", 30)));

        MILESTONES.add(new VipMilestoneDef(700000L, "五星战区统帅箱 A",
                mapOf("gold", 2500000, "speedUp36h", 2, "teleportAdvanced", 3, "starUp", 35, "universalSkillBook", 1)));
        MILESTONES.add(new VipMilestoneDef(800000L, "五星战区统帅箱 B",
                mapOf("gold", 3000000, "speedUp48h", 1, "speedUp36h", 2, "starUp", 40)));
        MILESTONES.add(new VipMilestoneDef(900000L, "五星战区统帅箱 C",
                mapOf("gold", 3500000, "speedUp48h", 2, "shield72h", 1, "universalSkillBook", 2, "starUp", 50)));
    }

    public static List<VipLevelDef> getAllLevels() {
        return Collections.unmodifiableList(LEVELS);
    }

    public static List<VipMilestoneDef> getAllMilestones() {
        return Collections.unmodifiableList(MILESTONES);
    }

    public static int getMaxLevel() {
        return LEVELS.get(LEVELS.size() - 1).level();
    }

    public static VipLevelDef getLevelDef(int level) {
        if (level < 0) return LEVELS.get(0);
        if (level >= LEVELS.size()) return LEVELS.get(LEVELS.size() - 1);
        return LEVELS.get(level);
    }

    public static int calculateVipLevel(long totalCents) {
        int lvl = 0;
        for (VipLevelDef def : LEVELS) {
            if (totalCents >= def.requiredCents()) {
                lvl = def.level();
            } else {
                break;
            }
        }
        return lvl;
    }

    public static VipMilestoneDef getMilestoneDef(long milestoneCents) {
        for (VipMilestoneDef def : MILESTONES) {
            if (def.milestoneCents() == milestoneCents) {
                return def;
            }
        }
        return null;
    }
}
