package com.wargame.model.constants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日军专属兵种定义与名称映射
 * 数值与底层 ID (infantry, motor, etc.) 与盟军完全一致，仅在 NPC 日寇城与流寇据点展示日军专属名称。
 */
public final class JapaneseUnitDef {

    private JapaneseUnitDef() {}

    public static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("infantry", "步兵-三八式步枪兵（Type 38）");
        m.put("motor", "摩托兵-九七式侧三轮（陆王）");
        m.put("truck", "卡车-九四式六轮卡车（Type 94）");
        m.put("armored", "装甲车-九三式装甲车（Type 93）");
        m.put("ltank", "轻型坦克-九五式轻战（Ha-Go）");
        m.put("htank", "重型坦克-四式中战（Chi-To）");
        m.put("assault", "突击炮-一式自走炮（Ho-Ni I）");
        m.put("rocket", "火箭-四式喷进炮（Type 4）");
        m.put("scout", "侦察机-百式司令部侦察机（Ki-46）");
        m.put("special", "特种兵-挺进战队（义烈空挺队）");
        m.put("fighter", "战斗机-零式战斗机（A6M5）");
        m.put("bomber", "轰炸机-一式陆攻（G4M2）");
        m.put("transport", "运输机-一式双发输送机（Ki-54）");
        m.put("destroyer", "驱逐舰-阳炎级（Kagero）");
        m.put("sub", "潜艇-伊号潜舰（I-400）");
        m.put("battleship", "战列舰-大和级（Yamato）");
        m.put("carrier", "航母-翔鹤级（Shokaku）");
        NAMES = Collections.unmodifiableMap(m);
    }

    /**
     * 获取指定单位在日军体系下的展示名称。
     * 若未找到日军专属名，则回退至常规 UnitDef/FortDef 名称。
     */
    public static String getUnitName(String unitId, boolean isJapanese) {
        if (unitId == null) return "";
        if (isJapanese && NAMES.containsKey(unitId)) {
            return NAMES.get(unitId);
        }
        UnitDef u = GameData.UNITS.get(unitId);
        if (u != null) return u.name();
        FortDef f = GameData.FORTS.get(unitId);
        if (f != null) return f.name();
        return unitId;
    }

    /**
     * 获取日军兵种名。
     */
    public static String getName(String unitId) {
        return getUnitName(unitId, true);
    }
}
