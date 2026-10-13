package com.wargame.service.pvp;
import com.wargame.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 冻结数据解析失败必须作废，禁止 JsonUtil 的空集合容错偷偷改变演习阵容。 */
public final class PvpJson {
    private PvpJson() {}
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = JsonUtil.getMapper().copy()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    public static String write(Object data) {
        try { return MAPPER.writeValueAsString(data); }
        catch (Exception e) { throw new IllegalStateException("演习序列化失败", e); }
    }
    public static <T> T read(String data, Class<T> type) {
        try { return JsonUtil.getMapper().readValue(data, type); }
        catch (Exception e) { throw new IllegalStateException("演习快照损坏", e); }
    }
    public static <T> T read(String data, TypeReference<T> type) {
        try { return JsonUtil.getMapper().readValue(data, type); }
        catch (Exception e) { throw new IllegalStateException("演习记录损坏", e); }
    }
    public static String hash(String data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
