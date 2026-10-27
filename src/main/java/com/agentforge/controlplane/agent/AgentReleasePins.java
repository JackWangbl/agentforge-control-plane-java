package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.util.Jsons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 发布版里抄下来的提示词、Skill 和工具契约。比较和截取都不碰数据库。 */
public final class AgentReleasePins {

    private AgentReleasePins() {}

    public static boolean unpinnedTool(String name) {
        return name != null && (name.startsWith("sandbox_") || name.startsWith("flow_")
                || "search_documents".equals(name) || "remember".equals(name)
                || "forget".equals(name) || "list_memories".equals(name)
                || HttpAgentRuntime.isHttpAgentTool(name));
    }

    public static Map<String, Object> snapshot(String systemPrompt, String modelName,
                                               List<Map<String, Object>> skills,
                                               List<Map<String, Object>> tools,
                                               List<Map<String, Object>> flows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_prompt", systemPrompt == null ? "" : systemPrompt);
        body.put("model_name", modelName == null ? "" : modelName);
        body.put("skills", skills == null ? List.of() : skills);
        body.put("tools", tools == null ? List.of() : tools);
        body.put("flows", FlowRuntime.normalizeFlows(flows));
        return body;
    }

    public static Map<String, Object> readSnapshot(String json) {
        Object raw = Jsons.read(json);
        if (!(raw instanceof Map<?, ?> map)) {
            return snapshot("", "", List.of(), List.of(), List.of());
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    public static String systemPrompt(Map<String, Object> snapshot) {
        return text(snapshot == null ? null : snapshot.get("system_prompt"));
    }

    public static String modelName(Map<String, Object> snapshot) {
        return text(snapshot == null ? null : snapshot.get("model_name"));
    }

    public static List<Map<String, Object>> skills(Map<String, Object> snapshot) {
        return maps(snapshot == null ? null : snapshot.get("skills"));
    }

    public static List<Map<String, Object>> tools(Map<String, Object> snapshot) {
        return maps(snapshot == null ? null : snapshot.get("tools"));
    }

    public static List<Map<String, Object>> flows(Map<String, Object> snapshot) {
        return FlowRuntime.normalizeFlows(maps(snapshot == null ? null : snapshot.get("flows")));
    }

    public static String skillBlock(Map<String, Object> snapshot) {
        List<String> chunks = new ArrayList<>();
        for (Map<String, Object> skill : skills(snapshot)) {
            String body = text(skill.get("instruction")).strip();
            if (!body.isBlank()) {
                chunks.add("### Skill：" + text(skill.get("name")) + "\n" + body);
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }
        return "你必须遵循以下已绑定 Skill：\n\n" + String.join("\n\n", chunks);
    }

    public static String toolHint(Map<String, Object> snapshot) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : tools(snapshot)) {
            String name = text(tool.get("name"));
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        return "你可以调用这些 MCP 工具：" + String.join("、", names)
                + "。需要实时时间、计算、检索技能说明、查看 Agent 列表、查询远程浏览器页面或在沙箱里跑代码时必须先调用工具，不要猜测。";
    }

    public static Map<String, Object> toolByName(Map<String, Object> snapshot, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Map<String, Object> tool : tools(snapshot)) {
            if (name.equals(text(tool.get("name")))) {
                return tool;
            }
        }
        return null;
    }

    /** 远端多了必填参数，或同一个参数的类型变了，旧发布版就不能再照原样调用。 */
    public static boolean breaking(Map<String, Object> pinnedTool, Map<String, Object> liveTool) {
        if (pinnedTool == null || liveTool == null) {
            return true;
        }
        Map<String, Object> pinned = parametersOf(pinnedTool);
        Map<String, Object> live = parametersOf(liveTool);
        TreeSet<String> pinnedProps = propertyNames(pinned);
        for (String required : requiredNames(live)) {
            if (!pinnedProps.contains(required)) {
                return true;
            }
        }
        TreeSet<String> liveProps = propertyNames(live);
        for (String name : pinnedProps) {
            if (!liveProps.contains(name)) {
                continue;
            }
            String pinnedType = propertyType(pinned, name);
            String liveType = propertyType(live, name);
            if (!pinnedType.isEmpty() && !liveType.isEmpty() && !pinnedType.equals(liveType)) {
                return true;
            }
        }
        return false;
    }

    public static String fingerprint(Map<String, Object> snapshot) {
        StringBuilder out = new StringBuilder();
        out.append("prompt\n").append(systemPrompt(snapshot)).append('\n');
        out.append("model\n").append(modelName(snapshot)).append('\n');
        List<Map<String, Object>> skillRows = new ArrayList<>(skills(snapshot));
        skillRows.sort(Comparator.comparingLong(AgentReleasePins::skillId).thenComparing(row -> text(row.get("name"))));
        for (Map<String, Object> skill : skillRows) {
            out.append("skill ").append(skillId(skill)).append(' ').append(text(skill.get("name"))).append('\n');
            out.append(text(skill.get("instruction"))).append('\n');
        }
        List<Map<String, Object>> toolRows = new ArrayList<>(tools(snapshot));
        toolRows.sort(Comparator.comparing(row -> text(row.get("name"))));
        for (Map<String, Object> tool : toolRows) {
            Map<String, Object> params = parametersOf(tool);
            out.append("tool ").append(text(tool.get("name"))).append('\n');
            out.append(text(tool.get("description"))).append('\n');
            out.append("required");
            for (String name : requiredNames(params)) {
                out.append(' ').append(name);
            }
            out.append('\n');
            for (String name : propertyNames(params)) {
                out.append("prop ").append(name).append(' ').append(propertyType(params, name)).append('\n');
            }
        }
        out.append("flows\n").append(Jsons.json(flows(snapshot)));
        return sha256(out.toString());
    }

    private static long skillId(Map<String, Object> skill) {
        Object id = skill.get("id");
        if (id instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(skill.get("id")));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersOf(Map<String, Object> tool) {
        Object params = tool.get("parameters");
        if (params instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static TreeSet<String> requiredNames(Map<String, Object> params) {
        TreeSet<String> names = new TreeSet<>();
        Object required = params.get("required");
        if (required instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    names.add(String.valueOf(item));
                }
            }
        }
        return names;
    }

    private static TreeSet<String> propertyNames(Map<String, Object> params) {
        TreeSet<String> names = new TreeSet<>();
        Object properties = params.get("properties");
        if (properties instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                names.add(String.valueOf(key));
            }
        }
        return names;
    }

    private static String propertyType(Map<String, Object> params, String name) {
        Object properties = params.get("properties");
        if (!(properties instanceof Map<?, ?> map)) {
            return "";
        }
        Object spec = map.get(name);
        if (!(spec instanceof Map<?, ?> schema)) {
            return "";
        }
        Object type = schema.get("type");
        return type == null ? "" : String.valueOf(type);
    }

    private static List<Map<String, Object>> maps(Object raw) {
        return Jsons.mapList(raw);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
