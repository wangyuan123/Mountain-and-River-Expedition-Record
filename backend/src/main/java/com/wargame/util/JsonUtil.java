package com.wargame.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSON utility for serializing/deserializing entity JSON text fields.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    private static final TypeReference<Map<String, Integer>> MAP_INT = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_OBJ = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

    /** Serialize a Map to JSON string. Returns "null" for null input. */
    public static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Parse a JSON string into a Map<String, Integer>. Returns empty map on null/blank. */
    public static Map<String, Integer> parseIntMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, MAP_INT);
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    /** Parse a JSON string into a Map<String, Object>. Returns empty map on null/blank. */
    public static Map<String, Object> parseObjMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, MAP_OBJ);
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    /** Parse a JSON string into a List of Maps. Returns empty list on null/blank. */
    public static List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, LIST_MAP);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    /** Parse a JSON string into a generic JsonNode tree. */
    public static com.fasterxml.jackson.databind.JsonNode parseTree(String json) {
        if (json == null || json.isBlank()) return MAPPER.nullNode();
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return MAPPER.nullNode();
        }
    }

    /** Get the shared ObjectMapper instance. */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
