package com.agentforge.controlplane.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON / 时间小工具。Python 版用 naive UTC isoformat，这里对齐成不带 Z 的 UTC 本地时间。 */
public final class Jsons {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private Jsons() {}

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String iso(Instant instant) {
        if (instant == null) {
            return null;
        }
        return ISO.format(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    public static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static Long asLong(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<Long> longList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Long> out = new java.util.ArrayList<>();
            for (Object item : list) {
                Long id = asLong(item);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        }
        Long single = asLong(value);
        return single == null ? List.of() : List.of(single);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }
}
