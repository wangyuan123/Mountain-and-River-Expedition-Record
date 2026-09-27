package com.wargame.model.constants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 军官姓名生成器：
 * - 五星将领：姓名必然来自历史名将库（GameConstants.OFFICER_NAMES）
 * - 非五星将领（1~4星）：姓名由百家姓及军人风格字库随机拼装生成，且绝不与五星名将重名
 */
public final class OfficerNameGenerator {

    private OfficerNameGenerator() {}

    private static final Set<String> FAMOUS_NAME_SET = new HashSet<>(GameConstants.OFFICER_NAMES);

    /** 常用大姓（百家姓精选） */
    public static final List<String> SURNAMES = List.of(
            "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
            "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧",
            "程", "曹", "袁", "邓", "许", "傅", "沈", "曾", "彭", "吕",
            "苏", "卢", "蒋", "蔡", "贾", "丁", "魏", "薛", "叶", "阎",
            "潘", "杜", "戴", "夏", "钟", "汪", "田", "任", "姜", "范",
            "方", "石", "姚", "谭", "廖", "邹", "熊", "金", "陆", "郝",
            "孔", "白", "崔", "康", "毛", "邱", "秦", "江", "史", "顾",
            "侯", "邵", "孟", "龙", "万", "段", "雷", "钱", "汤", "尹",
            "黎", "易", "常", "武", "乔", "贺", "赖", "龚", "文"
    );

    /** 单字名常用字池（沉稳、刚毅、军人气质） */
    public static final List<String> SINGLE_GIVEN_NAMES = List.of(
            "刚", "勇", "毅", "峰", "强", "军", "平", "东", "文", "辉",
            "力", "明", "健", "志", "良", "海", "山", "波", "宁", "龙",
            "胜", "武", "新", "飞", "杰", "涛", "昌", "成", "康", "星",
            "光", "天", "达", "安", "岩", "彪", "博", "诚", "震", "振",
            "豪", "承", "功", "磊", "民", "超", "浩", "亮", "政", "斌",
            "栋", "翔", "旭", "鹏", "泽", "晨", "泰", "雄", "钧", "策",
            "腾", "航", "锐", "卓", "捷", "啸", "岳", "铠", "勋", "雷"
    );

    /** 双字名前缀字池 */
    public static final List<String> DOUBLE_FIRST_CHARS = List.of(
            "建", "志", "文", "海", "振", "天", "世", "宏", "学", "立",
            "延", "景", "绍", "启", "维", "宗", "廷", "德", "广", "正",
            "永", "培", "承", "修", "崇", "冠", "尚", "守", "兆", "秉",
            "家", "成", "树", "盛", "宪", "敬", "显", "书", "传", "继"
    );

    /** 双字名后缀字池 */
    public static final List<String> DOUBLE_SECOND_CHARS = List.of(
            "国", "强", "华", "伟", "民", "平", "祥", "安", "峰", "杰",
            "超", "森", "铭", "权", "洲", "辉", "远", "博", "成", "龙",
            "生", "林", "武", "庆", "春", "清", "顺", "胜", "宇", "鹏",
            "忠", "义", "勋", "海", "涛", "刚", "军", "明", "洋", "凯"
    );

    /**
     * 判断某个名字是否为五星历史名将
     */
    public static boolean isFamousOfficer(String name) {
        return name != null && FAMOUS_NAME_SET.contains(name);
    }

    /**
     * 为五星将领抽取名将名字（必然来自名将库）
     */
    public static String pickFamousOfficerName(RandomGenerator rng, Set<String> excluded) {
        List<String> pool = GameConstants.OFFICER_NAMES;
        if (excluded != null && !excluded.isEmpty()) {
            List<String> available = pool.stream().filter(n -> !excluded.contains(n)).toList();
            if (!available.isEmpty()) {
                return available.get(rng.nextInt(available.size()));
            }
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /**
     * 随机生成非五星将领姓名（保证绝不属于名将库，且尽量不与排除集合冲突）
     */
    public static String generateRandomNonFamousName(RandomGenerator rng, Set<String> excluded) {
        for (int i = 0; i < 50; i++) {
            String surname = SURNAMES.get(rng.nextInt(SURNAMES.size()));
            String name;
            // 35% 概率单字名，65% 概率双字名
            if (rng.nextDouble() < 0.35) {
                name = surname + SINGLE_GIVEN_NAMES.get(rng.nextInt(SINGLE_GIVEN_NAMES.size()));
            } else {
                name = surname + DOUBLE_FIRST_CHARS.get(rng.nextInt(DOUBLE_FIRST_CHARS.size()))
                        + DOUBLE_SECOND_CHARS.get(rng.nextInt(DOUBLE_SECOND_CHARS.size()));
            }

            // 严禁与名将池重名
            if (FAMOUS_NAME_SET.contains(name)) {
                continue;
            }
            if (excluded != null && excluded.contains(name)) {
                continue;
            }
            return name;
        }

        // 极端防死锁回退：带随机序号确保不重名
        String fallback = SURNAMES.get(rng.nextInt(SURNAMES.size())) + "准" + (10 + rng.nextInt(90));
        return fallback;
    }

    /**
     * 统一入口：根据军官星级生成姓名
     * - star >= 5: 从名将池抽取
     * - star < 5: 随机生成非名将姓名
     */
    public static String generateOfficerName(int star, RandomGenerator rng, Set<String> excluded) {
        if (star >= 5) {
            return pickFamousOfficerName(rng, excluded);
        } else {
            return generateRandomNonFamousName(rng, excluded);
        }
    }
}
