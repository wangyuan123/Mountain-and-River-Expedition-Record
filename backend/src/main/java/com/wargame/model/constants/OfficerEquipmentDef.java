package com.wargame.model.constants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 六档四分支十二部位装备；整箱、属性和共鸣共用同一目录。旧档次 ID 不参与成长排序。 */
public record OfficerEquipmentDef(String key, String name, String description, String setKey,
                                  String setName, String branch, String slot, int tier,
                                  int requiredLevel, int military, int defense, int logistics, int knowledge) {
    public static final JsonNode CONFIG = loadConfig();
    public static final List<String> BRANCHES = List.of("military", "defense", "logistics", "knowledge");
    public static final List<String> SLOTS = new ArrayList<>();
    public static final Map<String, JsonNode> TIERS = new LinkedHashMap<>();
    public static final Map<String, OfficerEquipmentDef> ITEMS = createItems();

    private static JsonNode loadConfig() {
        try (var input = OfficerEquipmentDef.class.getResourceAsStream("/officer-equipment.json")) {
            return new ObjectMapper().readTree(Objects.requireNonNull(input));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static Map<String, OfficerEquipmentDef> createItems() {
        Map<String, OfficerEquipmentDef> items = new LinkedHashMap<>();
        CONFIG.path("slots").forEach(s -> SLOTS.add(s.path("key").asText()));
        for (JsonNode t : CONFIG.path("tiers")) {
            String tk = t.path("key").asText(); TIERS.put(tk, t);
            for (JsonNode b : CONFIG.path("branches")) {
                String bk = b.path("key").asText(), set = tk + "_" + bk;
                String setName = t.path("name").asText() + b.path("name").asText() + "套装";
                for (JsonNode s : CONFIG.path("slots")) {
                    String slot = s.path("key").asText(), key = set + "_" + slot;
                    boolean core = s.path("group").asText().equals("core");
                    int main = t.path("base_main").path(s.path("budget").asText()).asInt();
                    int sub = t.path(core ? "core_each_secondary" : "new_slot_each_secondary").asInt();
                    String name = core ? t.path("core_names").path(bk).get(SLOTS.indexOf(slot)).asText()
                            : t.path("name").asText() + CONFIG.path("new_slot_branch_names").path(bk).path(slot).asText();
                    String desc = setName + "·" + s.path("name").asText() + "：军官Lv." + t.path("required_level").asInt()
                            + "可穿戴，" + b.path("name").asText() + "+" + main + "，其余属性各+" + sub + "；同套3/6/9/12件累计激活共鸣";
                    int[] attrs = new int[4]; Arrays.fill(attrs, sub); attrs[BRANCHES.indexOf(bk)] = main;
                    items.put(key, new OfficerEquipmentDef(key, name, desc, set, setName, bk, slot,
                            t.path("persisted_tier_id").asInt(), t.path("required_level").asInt(), attrs[0], attrs[1], attrs[2], attrs[3]));
                }
            }
        }
        return Collections.unmodifiableMap(items);
    }

    public JsonNode tierConfig() { return TIERS.get(setKey.substring(0, setKey.indexOf('_'))); }
    public static List<OfficerEquipmentDef> setItems(String setKey) {
        return ITEMS.values().stream().filter(d -> d.setKey.equals(setKey)).toList();
    }
    public static List<OfficerEquipmentDef> boxItems(String boxKey) {
        return boxKey != null && boxKey.startsWith("box_") ? setItems(boxKey.substring(4)) : List.of();
    }
    public static Integer boxPrice(String boxKey) {
        var items = boxItems(boxKey);
        return items.isEmpty() ? null : items.get(0).tierConfig().path("box_diamonds").asInt();
    }
    public static String boxName(String boxKey) {
        var items = boxItems(boxKey);
        return items.isEmpty() ? null : items.get(0).setName.replace("套装", "装备箱");
    }
}
