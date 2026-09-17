package com.agentforge.controlplane.eval;

import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 解析 CSV / JSON / JSONL 数据集文件，对齐 Python 版 dataset_import.py。 */
public final class DatasetImporter {

    public static final int MAX_CASES = 2000;
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Set<String> INPUT_ALIASES = Set.of("input", "问题", "query", "question", "prompt", "user", "用户");
    private static final Set<String> EXPECTED_ALIASES = Set.of("expected", "期望", "answer", "output", "label", "reference", "标准答案");
    private static final Set<String> ID_ALIASES = Set.of("id", "case_id", "key", "case_key", "编号");
    private static final Set<String> TAG_ALIASES = Set.of("tags", "tag", "标签");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatasetImporter() {}

    public record Parsed(List<Map<String, Object>> cases, List<Map<String, Object>> errors, int count) {}

    public static Parsed parse(byte[] raw, String filename) {
        if (raw != null && raw.length > MAX_BYTES) {
            throw ApiException.badRequest("文件不能超过 " + (MAX_BYTES / 1024 / 1024) + "MB");
        }
        return parseText(decode(raw == null ? new byte[0] : raw), filename == null ? "" : filename);
    }

    static Parsed parseText(String text, String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        String stripped = text.stripLeading();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        if (name.endsWith(".jsonl") || (stripped.startsWith("{") && stripped.contains("\n{"))) {
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                try {
                    Object row = MAPPER.readValue(lines[i], Object.class);
                    if (!(row instanceof Map<?, ?> map)) {
                        throw new IllegalArgumentException("JSONL 每行必须是对象");
                    }
                    cases.add(rowToCase(asMap(map), i + 1));
                } catch (Exception e) {
                    errors.add(Map.of("line", i + 1, "reason", String.valueOf(e.getMessage())));
                }
            }
        } else if (stripped.startsWith("{") || stripped.startsWith("[")) {
            Object payload;
            try {
                payload = MAPPER.readValue(text, Object.class);
            } catch (Exception e) {
                throw ApiException.badRequest("JSON 无法解析：" + e.getMessage());
            }
            Object rows = payload instanceof Map<?, ?> map ? map.get("cases") : payload;
            if (!(rows instanceof List<?> list)) {
                throw ApiException.badRequest("JSON 需要是数组，或带 cases 数组的对象");
            }
            int index = 1;
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> map)) {
                    errors.add(Map.of("line", index, "reason", "条目必须是对象"));
                    index++;
                    continue;
                }
                try {
                    cases.add(rowToCase(asMap(map), index));
                } catch (Exception e) {
                    errors.add(Map.of("line", index, "reason", String.valueOf(e.getMessage())));
                }
                index++;
            }
        } else {
            List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
            if (lines.isEmpty() || lines.get(0).isBlank()) {
                throw ApiException.badRequest("CSV 缺少表头");
            }
            List<String> headers = splitCsv(lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                Map<String, Object> row = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    String value = c < cells.size() ? cells.get(c) : "";
                    row.put(headers.get(c), value);
                    if (!value.isBlank()) {
                        any = true;
                    }
                }
                if (!any) {
                    continue;
                }
                try {
                    cases.add(rowToCase(row, i + 1));
                } catch (Exception e) {
                    errors.add(Map.of("line", i + 1, "reason", String.valueOf(e.getMessage())));
                }
            }
        }
        if (cases.isEmpty() && !errors.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    Map.of("message", "没有解析到有效用例", "errors", errors), "没有解析到有效用例");
        }
        if (cases.isEmpty()) {
            throw ApiException.badRequest("文件是空的");
        }
        if (cases.size() > MAX_CASES) {
            throw ApiException.badRequest("单次最多导入 " + MAX_CASES + " 条，当前 " + cases.size() + " 条");
        }
        return new Parsed(cases, errors, cases.size());
    }

    private static Map<String, Object> rowToCase(Map<String, Object> row, int index) {
        String text = pick(row, INPUT_ALIASES, "");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("第 " + index + " 行缺少 input / 问题");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        Set<String> known = new java.util.HashSet<>();
        known.addAll(INPUT_ALIASES);
        known.addAll(EXPECTED_ALIASES);
        known.addAll(ID_ALIASES);
        known.addAll(TAG_ALIASES);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!known.contains(normKey(entry.getKey())) && entry.getValue() != null
                    && !String.valueOf(entry.getValue()).isBlank()) {
                extra.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("case_key", pick(row, ID_ALIASES, String.valueOf(index)));
        item.put("input", text);
        item.put("expected", pick(row, EXPECTED_ALIASES, ""));
        item.put("tags", tags(pick(row, TAG_ALIASES, "")));
        item.put("extra", extra);
        return item;
    }

    private static String pick(Map<String, Object> row, Set<String> aliases, String fallback) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        row.forEach((key, value) -> mapped.put(normKey(key), value));
        for (String alias : aliases) {
            Object value = mapped.get(alias);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).strip();
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Object value) {
        if (value == null || "".equals(value)) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                String text = String.valueOf(item).strip();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return out;
        }
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(value).replace("，", ",").split(",")) {
            if (!part.strip().isEmpty()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static String normKey(String key) {
        return WHITESPACE.matcher(key == null ? "" : key.strip().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String decode(byte[] raw) {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"))) {
            try {
                String text = new String(raw, charset);
                if (charset.equals(StandardCharsets.UTF_8) && text.contains("\uFFFD") && raw.length > 0) {
                    continue;
                }
                if (text.startsWith("\uFEFF")) {
                    return text.substring(1);
                }
                return text;
            } catch (Exception ignored) {
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /** 足够覆盖模板 CSV：带引号字段、逗号分隔。 */
    static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
