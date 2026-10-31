package com.wargame.model.dto;

import com.wargame.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** 司令官开战快照：只保存装备后的四项属性，不接受或传播技能。 */
public record CommandBonus(Long officerId, String name, int military, int defense, int logistics, int knowledge) {
    public static final double RATE = 0.001;
    public static final CommandBonus NONE = new CommandBonus(null, "", 0, 0, 0, 0);

    public double attackMul() { return 1 + Math.max(0, military) * RATE; }
    public double defenseMul() { return 1 + Math.max(0, defense) * RATE; }
    public double hpMul() { return 1 + Math.max(0, logistics) * RATE; }
    public double speedMul() { return 1 + Math.max(0, knowledge) * RATE; }

    /** 序列化展示值可往返读取，战斗仍仅使用冻结的四项属性计算。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public Map<String, Double> percents() {
        return Map.of("military", Math.max(0, military) * RATE * 100,
                "defense", Math.max(0, defense) * RATE * 100, "logistics", Math.max(0, logistics) * RATE * 100,
                "knowledge", Math.max(0, knowledge) * RATE * 100);
    }

    /** 战场、战报和持久化共享同一份数据；名称和属性在换任、换装后仍可复盘。 */
    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("officerId", officerId);
        data.put("name", name);
        data.put("military", military);
        data.put("defense", defense);
        data.put("logistics", logistics);
        data.put("knowledge", knowledge);
        data.put("rate", RATE);
        data.put("percents", percents());
        return data;
    }

    /** 历史会话没有司令官快照时按零加成处理，不能回读当前任职者补算。 */
    public static CommandBonus fromJson(String json) {
        Map<String, Object> data = JsonUtil.parseObjMap(json);
        if (data.isEmpty()) return NONE;
        Long id = data.get("officerId") instanceof Number n ? n.longValue() : null;
        return new CommandBonus(id, String.valueOf(data.getOrDefault("name", "")),
                value(data, "military"), value(data, "defense"), value(data, "logistics"), value(data, "knowledge"));
    }

    private static int value(Map<String, Object> data, String key) {
        return data.get(key) instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }
}
