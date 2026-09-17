package com.agentforge.controlplane.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLAlchemy 的 JSON 列在 JPA 侧用转换器实现。Python 版把 skill_ids / mcp_ids / tool_flows /
 * spans / metrics 等都存成 JSON，这里保持同样的库内表示，两版可以共用一个库。
 */
public final class JsonConverters {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConverters() {}

    private static String write(Object value, String emptyLiteral) {
        if (value == null) {
            return emptyLiteral;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列序列化失败", e);
        }
    }

    private static <T> T read(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            T parsed = MAPPER.readValue(raw, type);
            return parsed == null ? fallback : parsed;
        } catch (Exception e) {
            return fallback;
        }
    }

    @Converter
    public static class LongListConverter implements AttributeConverter<List<Long>, String> {
        @Override
        public String convertToDatabaseColumn(List<Long> attribute) {
            return write(attribute == null ? List.of() : attribute, "[]");
        }

        @Override
        public List<Long> convertToEntityAttribute(String dbData) {
            // Python 侧可能写入 int 或字符串形式的 id，两种都要接得住
            List<Object> raw = read(dbData, new TypeReference<List<Object>>() {}, List.of());
            List<Long> out = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Number n) {
                    out.add(n.longValue());
                } else if (item != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(item).trim()));
                    } catch (NumberFormatException ignored) {
                        // 脏数据跳过，跟 Python 版的宽松解析行为一致
                    }
                }
            }
            return out;
        }
    }

    @Converter
    public static class StringListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return write(attribute == null ? List.of() : attribute, "[]");
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            return new ArrayList<>(read(dbData, new TypeReference<List<String>>() {}, List.of()));
        }
    }

    @Converter
    public static class MapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return write(attribute == null ? Map.of() : attribute, "{}");
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            return new LinkedHashMap<>(read(dbData, new TypeReference<Map<String, Object>>() {}, Map.of()));
        }
    }

    /** 可为 null 的 JSON 对象列，用于 experiments.last_compare 这种“没跑过就是 null”的字段。 */
    @Converter
    public static class NullableMapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return attribute == null ? null : write(attribute, "{}");
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            return read(dbData, new TypeReference<Map<String, Object>>() {}, null);
        }
    }

    @Converter
    public static class MapListConverter implements AttributeConverter<List<Map<String, Object>>, String> {
        @Override
        public String convertToDatabaseColumn(List<Map<String, Object>> attribute) {
            return write(attribute == null ? List.of() : attribute, "[]");
        }

        @Override
        public List<Map<String, Object>> convertToEntityAttribute(String dbData) {
            return new ArrayList<>(read(dbData, new TypeReference<List<Map<String, Object>>>() {}, List.of()));
        }
    }
}
