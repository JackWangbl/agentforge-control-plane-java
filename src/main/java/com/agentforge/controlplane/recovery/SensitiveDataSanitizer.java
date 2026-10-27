package com.agentforge.controlplane.recovery;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SensitiveDataSanitizer {
    public static final String MASK = "******";
    private static final Set<String> SENSITIVE_PARTS = Set.of(
            "password", "passwd", "secret", "token", "api_key", "apikey", "authorization", "cookie", "credential");

    public Object sanitize(Object value) {
        return sanitize(value, "");
    }

    public String safeError(Throwable error) {
        if (error == null) return "执行失败";
        return "执行失败（" + error.getClass().getSimpleName() + "）";
    }

    private Object sanitize(Object value, String key) {
        if (isSensitiveKey(key)) return MASK;
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof byte[]) return "[binary omitted]";
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString();
            return text.length() > 16_384 ? text.substring(0, 16_384) + "…[truncated]" : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((itemKey, itemValue) -> {
                String normalized = String.valueOf(itemKey);
                out.put(normalized, sanitize(itemValue, normalized));
            });
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            iterable.forEach(item -> out.add(sanitize(item, "")));
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) out.add(sanitize(Array.get(value, i), ""));
            return out;
        }
        return String.valueOf(value);
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace('-', '_');
        return SENSITIVE_PARTS.stream().anyMatch(normalized::contains);
    }
}
