package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.web.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 平台默认 + Agent JSON 覆盖。非法值拒绝保存，不静默夹逼。 */
public final class ContextCompressionPolicy {
    public record Effective(
            boolean enabled,
            int contextWindowTokens,
            int reservedOutputTokens,
            int reservedToolTokens,
            int minHistoryTokens,
            double charsPerToken,
            int messageCountTrigger,
            int minRecentMessages,
            int recentTokenLimit,
            int summaryTokenLimit,
            int maxCompressCallsPerTurn,
            String compressModelName) {}

    private ContextCompressionPolicy() {}

    public static Map<String, Object> validateOverride(Map<String, Object> policy) {
        if (policy == null || policy.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        policy.forEach((key, value) -> {
            switch (key) {
                case "enabled" -> out.put(key, asBoolean(key, value));
                case "context_window_tokens" -> out.put(key, asLong(key, value, 1024, 131072));
                case "reserved_output_tokens" -> out.put(key, asLong(key, value, 128, 8192));
                case "reserved_tool_tokens" -> out.put(key, asLong(key, value, 0, 8192));
                case "min_history_tokens" -> out.put(key, asLong(key, value, 128, 8192));
                case "chars_per_token" -> out.put(key, asDouble(key, value, 0.5, 8.0));
                case "message_count_trigger" -> out.put(key, asLong(key, value, 4, 200));
                case "min_recent_messages" -> out.put(key, asLong(key, value, 2, 50));
                case "recent_token_limit" -> out.put(key, asLong(key, value, 256, 32000));
                case "summary_token_limit" -> out.put(key, asLong(key, value, 128, 4096));
                case "max_compress_calls_per_turn" -> out.put(key, asLong(key, value, 1, 2));
                case "compress_model_name" -> {
                    String name = value == null ? "" : String.valueOf(value).strip();
                    if (name.length() > 120) throw ApiException.unprocessable("上下文压缩 compress_model_name 超出安全范围");
                    if (!name.isEmpty()) out.put(key, name);
                }
                default -> { }
            }
        });
        return out;
    }

    public static Effective merge(AppSettings.ContextCompression defaults, Map<String, Object> override) {
        AppSettings.ContextCompression base = defaults == null ? new AppSettings.ContextCompression() : defaults;
        Map<String, Object> extra = override == null ? Map.of() : override;
        return new Effective(
                extra.containsKey("enabled") ? asBoolean("enabled", extra.get("enabled")) : base.isEnabled(),
                number(extra, "context_window_tokens", base.getContextWindowTokens()),
                number(extra, "reserved_output_tokens", base.getReservedOutputTokens()),
                number(extra, "reserved_tool_tokens", base.getReservedToolTokens()),
                number(extra, "min_history_tokens", base.getMinHistoryTokens()),
                extra.get("chars_per_token") instanceof Number n ? n.doubleValue() : base.getCharsPerToken(),
                number(extra, "message_count_trigger", base.getMessageCountTrigger()),
                number(extra, "min_recent_messages", base.getMinRecentMessages()),
                number(extra, "recent_token_limit", base.getRecentTokenLimit()),
                number(extra, "summary_token_limit", base.getSummaryTokenLimit()),
                number(extra, "max_compress_calls_per_turn", base.getMaxCompressCallsPerTurn()),
                extra.get("compress_model_name") == null ? "" : String.valueOf(extra.get("compress_model_name")).strip());
    }

    static int tokens(String text, double charsPerToken) {
        if (text == null || text.isBlank()) return 0;
        double divisor = charsPerToken <= 0 ? 1.5 : charsPerToken;
        return Math.max(1, (int) Math.ceil(text.length() / divisor));
    }

    static String clipToTokens(String text, int tokenLimit, double charsPerToken) {
        if (text == null) return "";
        int limit = Math.max(1, tokenLimit);
        int maxChars = Math.max(1, (int) Math.floor(limit * (charsPerToken <= 0 ? 1.5 : charsPerToken)));
        String value = text.strip();
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static int number(Map<String, Object> extra, String key, int fallback) {
        return extra.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean asBoolean(String key, Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text) {
            String normalized = text.strip().toLowerCase();
            if ("true".equals(normalized) || "1".equals(normalized)) return true;
            if ("false".equals(normalized) || "0".equals(normalized)) return false;
        }
        throw ApiException.unprocessable("上下文压缩 " + key + " 超出安全范围");
    }

    private static long asLong(String key, Object value, long min, long max) {
        if (!(value instanceof Number number) || number.longValue() < min || number.longValue() > max) {
            throw ApiException.unprocessable("上下文压缩 " + key + " 超出安全范围");
        }
        return number.longValue();
    }

    private static double asDouble(String key, Object value, double min, double max) {
        if (!(value instanceof Number number) || number.doubleValue() < min || number.doubleValue() > max) {
            throw ApiException.unprocessable("上下文压缩 " + key + " 超出安全范围");
        }
        return number.doubleValue();
    }
}
