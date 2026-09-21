package com.wargame.model.constants;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 军官可穿戴装备定义。
 *
 * <p>3 阶套装设计：低级(列兵)→ 中级(校官) → 满级(元帅)。
 * 每个套装下设 3 个分支：军事 / 后勤 / 学识。
 * 每个分支下设 3 个槽位：武器 / 徽章 / 外套。
 * 收集同一套装同一分支的 3 件可激活套装加成。</p>
 */
public record OfficerEquipmentDef(String key, String name, String description, String setKey,
                                  String setName, String branch, String slot, int tier,
                                  int requiredLevel, int military, int defense, int logistics, int knowledge) {
    public static final Map<String, OfficerEquipmentDef> ITEMS = createItems();

    private static Map<String, OfficerEquipmentDef> createItems() {
        Map<String, OfficerEquipmentDef> items = new LinkedHashMap<>();
        // tierKey, tierName, requiredLevel, primary(主属性), secondary(副属性)
        // 列兵套装：低级装备，Lv.1 可装备
        addSet(items, "recruit", "列兵", 1, 5, 1, 3);
        // 校官套装：中级装备，Lv.40 可装备
        addSet(items, "officer", "校官", 40, 15, 3, 15);
        // 元帅套装：满级装备，Lv.100 可装备
        addSet(items, "marshal", "元帅", 100, 30, 5, 30);
        return Map.copyOf(items);
    }

    /**
     * 注册一整套（4 个分支）。
     */
    private static void addSet(Map<String, OfficerEquipmentDef> items, String tierKey, String tierName,
                               int requiredLevel, int primary, int secondary, int setPrimary) {
        addBranch(items, tierKey, tierName, requiredLevel, primary, secondary, setPrimary, "military", "军事");
        addBranch(items, tierKey, tierName, requiredLevel, primary, secondary, setPrimary, "defense", "防御");
        addBranch(items, tierKey, tierName, requiredLevel, primary, secondary, setPrimary, "logistics", "后勤");
        addBranch(items, tierKey, tierName, requiredLevel, primary, secondary, setPrimary, "knowledge", "学识");
    }

    /**
     * 注册一个分支的 3 个槽位（武器/徽章/外套）。
     */
    private static void addBranch(Map<String, OfficerEquipmentDef> items, String tierKey, String tierName,
                                  int requiredLevel, int primary, int secondary, int setPrimary,
                                  String branch, String branchName) {
        String setKey = tierKey + "_" + branch;
        String setName = tierName + branchName + "套装";
        addSlot(items, setKey, setName, branch, requiredLevel, primary, secondary,
                tierKey, tierName, branchName, "weapon", "武器", getWeaponName(tierKey, branch));
        addSlot(items, setKey, setName, branch, requiredLevel, primary, secondary,
                tierKey, tierName, branchName, "badge", "徽章", getBadgeName(tierKey, branch));
        addSlot(items, setKey, setName, branch, requiredLevel, primary, secondary,
                tierKey, tierName, branchName, "coat", "外套", getCoatName(tierKey, branch));
    }

    private static void addSlot(Map<String, OfficerEquipmentDef> items, String setKey, String setName,
                                String branch, int level, int primary, int secondary,
                                String tierKey, String tierName, String branchName,
                                String slot, String slotLabel, String itemName) {
        int military = branch.equals("military") ? primary : secondary;
        int defense = branch.equals("defense") ? primary : secondary;
        int logistics = branch.equals("logistics") ? primary : secondary;
        int knowledge = branch.equals("knowledge") ? primary : secondary;
        String key = setKey + "_" + slot;
        String desc = tierName + branchName + "套装·" + slotLabel + "：Lv." + level + " 可装备，"
                + branchName + "主属性+" + primary
                + "，其余属性+" + secondary
                + "；集齐 3 件激活套装加成 " + branchName + "+" + (tierKey.equals("recruit") ? 3
                        : tierKey.equals("officer") ? 15 : 30)
                + (tierKey.equals("marshal") ? "，全属性+5" : "")
                + "。";
        int tierNum = tierKey.equals("recruit") ? 1 : tierKey.equals("officer") ? 2 : 3;
        items.put(key, new OfficerEquipmentDef(key, itemName, desc, setKey, setName, branch, slot,
                tierNum, level, military, defense, logistics, knowledge));
    }

    // —— 武器槽位名称 ——
    private static String getWeaponName(String tierKey, String branch) {
        return switch (tierKey) {
            case "recruit" -> switch (branch) {
                case "military" -> "列兵军刀";
                case "defense" -> "列兵护身盾";
                case "logistics" -> "列兵工具包";
                default -> "列兵笔记本";
            };
            case "officer" -> switch (branch) {
                case "military" -> "校官军刀";
                case "defense" -> "校官防暴盾";
                case "logistics" -> "校官补给箱";
                default -> "校官战术罗盘";
            };
            default -> switch (branch) {
                case "military" -> "元帅佩剑";
                case "defense" -> "元帅重装盾";
                case "logistics" -> "元帅辎重车";
                default -> "元帅望远镜";
            };
        };
    }

    // —— 徽章槽位名称 ——
    private static String getBadgeName(String tierKey, String branch) {
        return switch (tierKey) {
            case "recruit" -> switch (branch) {
                case "military" -> "列兵臂章";
                case "defense" -> "列兵坚守勋章";
                case "logistics" -> "列兵通行证";
                default -> "列兵学员章";
            };
            case "officer" -> switch (branch) {
                case "military" -> "校官勋章";
                case "defense" -> "校官铁壁勋章";
                case "logistics" -> "校官调度章";
                default -> "校官参谋章";
            };
            default -> switch (branch) {
                case "military" -> "元帅将星";
                case "defense" -> "元帅不屈之星";
                case "logistics" -> "元帅军需印";
                default -> "元帅军师印";
            };
        };
    }

    // —— 外套槽位名称 ——
    private static String getCoatName(String tierKey, String branch) {
        return switch (tierKey) {
            case "recruit" -> switch (branch) {
                case "military" -> "列兵作训服";
                case "defense" -> "列兵防弹背心";
                case "logistics" -> "列兵工作服";
                default -> "列兵学员服";
            };
            case "officer" -> switch (branch) {
                case "military" -> "校官军服";
                case "defense" -> "校官重装防弹甲";
                case "logistics" -> "校官军需服";
                default -> "校官参谋服";
            };
            default -> switch (branch) {
                case "military" -> "元帅礼服";
                case "defense" -> "元帅钛金铠";
                case "logistics" -> "元帅长袍";
                default -> "元帅军礼服";
            };
        };
    }
}
