package com.wargame.model.constants;

import com.wargame.model.entity.Officer;
import com.wargame.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NPC 敌军（日寇据点、流寇、NPC城市）专用的日军历史将领池。
 * 严格与玩家招募池隔离，提供真实历史将领名称、星级、属性与特技构建。
 */
public final class JapaneseOfficers {

    private JapaneseOfficers() {}

    /**
     * 36名二战日军指挥官（以中缅印战区及太平洋/亚洲战场为主）
     */
    public static final List<String> COMMANDERS = List.of(
            "细谷资彦",     // Lv.1 侵缅第55师团第112联队长
            "辻政信",       // Lv.2 驻缅甸作战高级参谋
            "樱井德太郎",   // Lv.3 第55师团步兵指挥官
            "宫崎繁三郎",   // Lv.4 第31师团步兵指挥官（科希马作战）
            "铃木宗作",     // Lv.5 第35军司令官
            "竹原三郎",     // Lv.6 第49师团师团长
            "山内正文",     // Lv.7 第15师团师团长
            "佐藤幸德",     // Lv.8 第31师团师团长
            "渡边正夫",     // Lv.9 第56师团师团长（松山、腾冲激战）
            "田中新一",     // Lv.10 第18师团师团长（胡康河谷、孟拱战役）
            "樱井省三",     // Lv.11 第28军司令官（若开战役）
            "饭田祥二郎",   // Lv.12 第15军首任司令官（入缅初期总指挥）
            "笠原幸雄",     // Lv.13 关东军总参谋长、第11军司令官
            "喜多诚一",     // Lv.14 第一方面军司令官
            "安藤利吉",     // Lv.15 第十方面军司令官
            "后宫淳",       // Lv.16 第三方面军司令官
            "冢田攻",       // Lv.17 第十一军司令官
            "矶谷廉介",     // Lv.18 第十师团长、香港总督
            "梅津美治郎",   // Lv.19 关东军总司令官、参谋总长
            "土肥原贤二",   // Lv.20 第七方面军司令官
            "松井石根",     // Lv.21 华中方面军司令官
            "今村均",       // Lv.22 第八方面军司令官
            "阿南惟几",     // Lv.23 陆军大臣、第十一军司令官
            "小泽治三郎",   // Lv.24 联合舰队最后一任司令长官
            "南云忠一",     // Lv.25 第一航空舰队司令长官
            "栗林忠道",     // Lv.26 小笠原兵团司令官（硫磺岛战役）
            "牛岛满",       // Lv.27 第32军司令官（冲绳战役）
            "木村兵太郎",   // Lv.28 缅甸方面军总司令官（甲级战犯）
            "牟田口廉也",   // Lv.29 第15军司令官（英帕尔战役总指挥）
            "寺内寿一",     // Lv.30 南方军总司令官（元帅，辖缅甸全境）
            // 备用名录
            "冈村宁次", "畑俊六", "板垣征四郎", "山下奉文", "山本五十六", "东条英机"
    );

    /**
     * 根据 NPC 等级（1~30）获取对应的日军将领名称。
     */
    public static String getCommanderForLevel(int level) {
        int idx = Math.max(1, Math.min(level, 30)) - 1;
        return COMMANDERS.get(idx);
    }

    /**
     * 构建参战的虚拟日军将领实体。
     * 星级、等级、四维属性及特技均与据点等级强相关。
     */
    public static Officer buildOfficer(String commanderName, int level) {
        int safeLevel = Math.max(1, Math.min(level, 30));
        String name = (commanderName != null && !commanderName.isBlank())
                ? commanderName : getCommanderForLevel(safeLevel);

        Officer officer = new Officer();
        officer.setId(-1L);
        officer.setName(name);
        officer.setLevel(Math.max(1, safeLevel * 3));

        // 星级分布: 1~5级 1星; 6~12级 2星; 13~20级 3星; 21~27级 4星; 28~30级 5星
        int star = safeLevel <= 5 ? 1 : safeLevel <= 12 ? 2 : safeLevel <= 20 ? 3 : safeLevel <= 27 ? 4 : 5;
        officer.setStar(star);

        // 军事与防御属性随等级梯次提升
        int mil = 45 + safeLevel * 6;
        int def = 45 + safeLevel * 6;
        officer.setMilitary(mil);
        officer.setDefense(def);
        officer.setLogistics(40 + safeLevel * 4);
        officer.setKnowledge(40 + safeLevel * 4);

        // 将领技能配置
        List<Map<String, Object>> skills = new ArrayList<>();
        if (safeLevel >= 3) {
            skills.add(Map.of("id", "bulwark", "lv", Math.min(5, 1 + safeLevel / 6)));
        }
        if (safeLevel >= 7) {
            skills.add(Map.of("id", "frenzy", "lv", Math.min(5, 1 + safeLevel / 7)));
        }
        if (safeLevel >= 14) {
            skills.add(Map.of("id", "suppress", "lv", Math.min(5, 1 + safeLevel / 8)));
        }
        if (safeLevel >= 21) {
            skills.add(Map.of("id", "pierce", "lv", Math.min(5, 1 + safeLevel / 9)));
        }
        if (safeLevel >= 28) {
            skills.add(Map.of("id", "counter", "lv", 5));
        }
        officer.setSkills(JsonUtil.toJson(skills));
        return officer;
    }
}
