package com.wargame.model.constants;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 军官姓名生成器：
 * - 五星将领：姓名必然来自历史名将库（GameConstants.OFFICER_NAMES）
 * - 非五星将领（1~4星）：使用英美常见姓名的中文译名，以“名·姓”组合，且不与五星名将重名
 */
public final class OfficerNameGenerator {

    private OfficerNameGenerator() {}

    private static final Set<String> FAMOUS_NAME_SET = new HashSet<>(GameConstants.OFFICER_NAMES);

    /** 英美常见姓氏的中文译名，与名字池独立抽取，供国内测试阶段显示。 */
    public static final List<String> SURNAMES = List.of(
            "史密斯", "约翰逊", "威廉姆斯", "布朗", "琼斯", "米勒", "戴维斯", "威尔逊",
            "安德森", "泰勒", "托马斯", "摩尔", "杰克逊", "马丁", "李", "汤普森",
            "怀特", "哈里斯", "克拉克", "刘易斯", "罗宾逊", "沃克", "霍尔", "艾伦",
            "扬", "金", "赖特", "斯科特", "格林", "贝克", "亚当斯", "纳尔逊",
            "希尔", "坎贝尔", "米切尔", "罗伯茨", "卡特", "菲利普斯", "埃文斯", "特纳",
            "帕克", "爱德华兹", "柯林斯", "斯图尔特", "莫里斯", "罗杰斯", "里德", "库克",
            "摩根", "贝尔", "墨菲", "贝利", "库珀", "理查森", "考克斯", "霍华德",
            "沃德", "彼得森", "格雷", "詹姆斯", "沃森", "布鲁克斯", "凯利", "桑德斯",
            "普赖斯", "贝内特", "伍德", "巴恩斯", "罗斯", "亨德森", "科尔曼", "詹金斯",
            "佩里", "鲍威尔", "朗", "帕特森", "休斯", "福斯特", "巴特勒", "西蒙斯",
            "费舍尔", "韦伯", "格雷厄姆", "埃利斯", "哈里森", "吉布森", "汉密尔顿", "雷诺兹",
            "华莱士", "韦尔斯", "韦斯特", "科尔", "拉塞尔", "马歇尔", "查普曼", "哈德森",
            "帕尔默", "斯宾塞", "哈维", "巴顿"
    );

    /** 英美常见名字的中文译名，按完整译名抽取，不使用中国人的名字字库。 */
    public static final List<String> GIVEN_NAMES = List.of(
            "詹姆斯", "约翰", "罗伯特", "威廉", "查尔斯", "乔治", "托马斯", "约瑟夫",
            "爱德华", "理查德", "亨利", "沃尔特", "亚瑟", "弗兰克", "阿尔伯特", "塞缪尔",
            "戴维", "路易斯", "杰克", "本杰明", "丹尼尔", "哈罗德", "雷蒙德", "弗雷德里克",
            "保罗", "唐纳德", "肯尼斯", "安德鲁", "斯蒂芬", "彼得", "菲利普", "安东尼",
            "帕特里克", "迈克尔", "弗朗西斯", "斯坦利", "伦纳德", "欧内斯特", "赫伯特", "塞西尔",
            "劳伦斯", "霍华德", "尤金", "拉尔夫", "罗伊", "维克托", "文森特", "诺曼",
            "埃德温", "阿尔弗雷德", "伯纳德", "休", "艾伦", "戈登", "道格拉斯", "马尔科姆",
            "科林", "格雷厄姆", "布鲁斯", "伊恩"
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
     * 随机生成英美姓名的中文译名，使用“名·姓”格式，避开名将及排除集合；名字池耗尽时明确失败。
     * @param rng 随机源
     * @param excluded 不可重复的姓名，可为 null
     * @return 可用的英美姓名中文全名
     * @throws IllegalStateException 所有译名组合均不可用时抛出
     */
    public static String generateRandomNonFamousName(RandomGenerator rng, Set<String> excluded) {
        for (int i = 0; i < 50; i++) {
            String surname = SURNAMES.get(rng.nextInt(SURNAMES.size()));
            String name = GIVEN_NAMES.get(rng.nextInt(GIVEN_NAMES.size())) + "·" + surname;

            // 严禁与名将池重名
            if (FAMOUS_NAME_SET.contains(name)) {
                continue;
            }
            if (excluded != null && excluded.contains(name)) {
                continue;
            }
            return name;
        }

        // 连续碰撞后遍历剩余译名组合，保持“名·姓”格式和去重约束，不追加数字编号。
        for (String givenName : GIVEN_NAMES) {
            for (String surname : SURNAMES) {
                String name = givenName + "·" + surname;
                if (!FAMOUS_NAME_SET.contains(name) && (excluded == null || !excluded.contains(name))) {
                    return name;
                }
            }
        }
        throw new IllegalStateException("No available officer name combinations");
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
