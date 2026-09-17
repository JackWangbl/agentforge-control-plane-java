package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 绑定在 Agent 上的确定性工具链路。
 *
 * 一条链路就是 Agent.tool_flows 里的一组有序步骤。模型只看到一个 flow_xxx 工具，
 * 调用一次就跑完整条链；这里负责按顺序执行、把上一步的输出喂给下一步的参数，
 * 并把每一步都回报出来，好让调试链路里能看到实际发生了什么。
 */
public final class FlowRuntime {

    public static final String FLOW_TOOL_PREFIX = "flow_";
    public static final int MAX_FLOW_STEPS = 10;
    public static final int MAX_FLOWS_PER_AGENT = 20;
    private static final int STEP_TEXT_LIMIT = 2000;
    private static final int SPAN_DETAIL_LIMIT = 240;

    private static final Pattern REFERENCE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");
    private static final Pattern ONLY_REFERENCE = Pattern.compile("^\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowRuntime() {}

    /** 步骤参数引用了前面几步从没产出过的值。 */
    public static class FlowReferenceException extends RuntimeException {
        public FlowReferenceException(String message) {
            super(message);
        }
    }

    public static String flowToolName(String name) {
        return FLOW_TOOL_PREFIX + name;
    }

    public static boolean isFlowTool(String name) {
        return name != null && name.startsWith(FLOW_TOOL_PREFIX);
    }

    /** 读出并规整 Agent 上的链路定义：丢掉没名字、重名和没步骤的，并且限制条数与步数。 */
    public static List<Map<String, Object>> agentFlows(Agent agent) {
        if (agent == null || agent.getToolFlows() == null) {
            return List.of();
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> item : agent.getToolFlows()) {
            if (item == null) {
                continue;
            }
            String name = text(item.get("name")).strip();
            List<Map<String, Object>> steps = stepsOf(item);
            if (name.isBlank() || seen.contains(name) || steps.isEmpty()) {
                continue;
            }
            seen.add(name);
            Map<String, Object> flow = new LinkedHashMap<>(item);
            flow.put("name", name);
            flow.put("steps", steps.subList(0, Math.min(steps.size(), MAX_FLOW_STEPS)));
            flows.add(flow);
            if (flows.size() >= MAX_FLOWS_PER_AGENT) {
                break;
            }
        }
        return flows;
    }

    public static Map<String, Object> findAgentFlow(Agent agent, String toolName) {
        if (!isFlowTool(toolName)) {
            return null;
        }
        String wanted = toolName.substring(FLOW_TOOL_PREFIX.length());
        for (Map<String, Object> flow : agentFlows(agent)) {
            if (wanted.equals(flow.get("name"))) {
                return flow;
            }
        }
        return null;
    }

    /** 链路里用到的所有工具，保存时校验绑定关系要用。 */
    public static List<String> flowStepTools(Agent agent) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> flow : agentFlows(agent)) {
            for (Map<String, Object> step : stepsOf(flow)) {
                String tool = text(step.get("tool")).strip();
                if (!tool.isBlank() && !names.contains(tool)) {
                    names.add(tool);
                }
            }
        }
        return names;
    }

    /** 每条链路对模型暴露成一个函数工具。 */
    public static List<Map<String, Object>> flowToolSpecs(Agent agent) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> flow : agentFlows(agent)) {
            String chain = chainOf(flow);
            String description = text(flow.get("description")).strip();
            String summary = "按固定顺序执行 " + chain + "，一次调用完成整条链路，不要再单独调用其中的工具。";
            Object parameters = flow.get("parameters");
            if (!(parameters instanceof Map)) {
                parameters = Map.of("type", "object", "properties", Map.of());
            }
            tools.add(Map.of(
                    "name", flowToolName(text(flow.get("name"))),
                    "description", (description + " " + summary).strip(),
                    "parameters", parameters));
        }
        return tools;
    }

    /** 拼进系统提示的链路说明，让模型知道该调链路而不是拆开调工具。 */
    public static String flowPromptHint(Agent agent) {
        List<Map<String, Object>> flows = agentFlows(agent);
        if (flows.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("以下固定链路已经编排好，需要时调用链路本身，平台会保证顺序和参数传递：");
        for (Map<String, Object> flow : flows) {
            String purpose = text(flow.get("description")).strip();
            out.append("\n- ")
                    .append(flowToolName(text(flow.get("name"))))
                    .append("：")
                    .append(purpose.isBlank() ? "" : purpose + "，")
                    .append("会依次执行 ")
                    .append(chainOf(flow));
        }
        return out.toString();
    }

    /**
     * 按顺序跑完每一步，返回整条链路的 JSON 报告。
     *
     * @param execute 真正执行单个工具的回调，入参是工具名和已解析好的参数
     * @param allows  判断某个工具是否绑定在当前 Agent 上
     */
    public static String runToolFlow(Map<String, Object> flow,
                                     Map<String, Object> arguments,
                                     BiFunction<String, Map<String, Object>, String> execute,
                                     Predicate<String> allows) {
        String defaultPolicy = "continue".equals(flow.get("on_error")) ? "continue" : "abort";
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments));
        Map<String, Object> stepScope = new LinkedHashMap<>();
        scope.put("steps", stepScope);

        List<Map<String, Object>> records = new ArrayList<>();
        Object result = null;
        boolean stopped = false;
        boolean degraded = false;

        List<Map<String, Object>> steps = stepsOf(flow);
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> step = steps.get(index);
            String tool = text(step.get("tool")).strip();
            String ref = text(step.get("id")).strip();
            if (ref.isBlank()) {
                ref = String.valueOf(index);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("index", index);
            record.put("id", ref);
            record.put("tool", tool);
            records.add(record);

            if (stopped) {
                record.put("status", "skip");
                record.put("error", "上一步失败且链路策略为 abort，本步未执行");
                continue;
            }

            String policy = defaultPolicy;
            Object stepPolicy = step.get("on_error");
            if (stepPolicy != null) {
                policy = "continue".equals(stepPolicy) ? "continue" : "abort";
            }

            String failure = "";
            Map<String, Object> resolved = new LinkedHashMap<>();
            if (tool.isBlank()) {
                failure = "步骤没有指定工具";
            } else if (isFlowTool(tool)) {
                failure = "链路步骤不能再调用另一条链路";
            } else if (!allows.test(tool)) {
                failure = "Agent 未绑定工具 " + tool;
            } else {
                Object rawArgs = step.get("arguments");
                try {
                    Object out = resolve(rawArgs instanceof Map ? rawArgs : Map.of(), scope);
                    if (out instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> resolved.put(String.valueOf(key), value));
                    }
                } catch (FlowReferenceException e) {
                    failure = e.getMessage();
                }
            }

            if (!failure.isBlank()) {
                record.put("status", "error");
                record.put("error", failure);
                if ("abort".equals(policy)) {
                    stopped = true;
                } else {
                    degraded = true;
                }
                continue;
            }

            record.put("arguments", shrink(resolved));
            long started = System.nanoTime();
            String raw;
            try {
                raw = execute.apply(tool, resolved);
            } catch (Exception e) {
                raw = json(Map.of("error", String.valueOf(e.getMessage())));
            }
            record.put("duration_ms", Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)));

            String text = raw == null ? "" : raw;
            Object output = parseOutput(text);
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("text", text);
            slot.put("output", output);
            stepScope.put(ref, slot);
            stepScope.putIfAbsent(String.valueOf(index), slot);

            String error = errorOf(output);
            record.put("output", shrink(output));
            if (!error.isBlank()) {
                record.put("status", "error");
                record.put("error", error);
                if ("abort".equals(policy)) {
                    stopped = true;
                } else {
                    degraded = true;
                }
                continue;
            }
            record.put("status", "ok");
            result = output;
        }

        String status = stopped ? "aborted" : degraded ? "partial" : "ok";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("flow", text(flow.get("name")));
        report.put("status", status);
        report.put("steps", records);
        report.put("result", shrink(result));
        return json(report);
    }

    /** 把链路报告摊成调试面板里的一步一条 span。 */
    public static List<Map<String, Object>> flowStepSpans(String toolName, String payload) {
        Map<String, Object> report;
        try {
            report = MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            return List.of();
        }
        if (!(report.get("steps") instanceof List<?> steps)) {
            return List.of();
        }
        List<Map<String, Object>> spans = new ArrayList<>();
        for (Object item : steps) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            raw.forEach((key, value) -> record.put(String.valueOf(key), value));
            String status = text(record.get("status"));
            if (status.isBlank()) {
                status = "ok";
            }
            Object detail = record.get("error");
            if (detail == null || text(detail).isBlank()) {
                detail = asText(record.get("output"));
            }
            int index = record.get("index") instanceof Number n ? n.intValue() : 0;
            String detailText = asText(detail);
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("name", toolName + "." + record.get("id"));
            span.put("title", "链路第 " + (index + 1) + " 步 · "
                    + (text(record.get("tool")).isBlank() ? "未知工具" : record.get("tool")));
            span.put("kind", "tool");
            span.put("status", List.of("ok", "error", "skip").contains(status) ? status : "ok");
            span.put("duration_ms", record.get("duration_ms") instanceof Number d ? d.intValue() : 0);
            span.put("detail", detailText.length() > SPAN_DETAIL_LIMIT
                    ? detailText.substring(0, SPAN_DETAIL_LIMIT) : detailText);
            spans.add(span);
        }
        return spans;
    }

    /** 整串就是一个引用时保留原类型，嵌在文字里则按文本插值。 */
    private static Object resolve(Object value, Map<String, Object> scope) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), resolve(item, scope)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(resolve(item, scope));
            }
            return out;
        }
        if (!(value instanceof String str)) {
            return value;
        }
        Matcher exact = ONLY_REFERENCE.matcher(str.strip());
        if (exact.matches()) {
            return lookup(exact.group(1), scope);
        }
        Matcher matcher = REFERENCE.matcher(str);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(asText(lookup(matcher.group(1), scope))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object lookup(String path, Map<String, Object> scope) {
        Object node = scope;
        for (String part : path.split("\\.")) {
            if (node instanceof Map<?, ?> map && map.containsKey(part)) {
                node = map.get(part);
                continue;
            }
            if (node instanceof List<?> list) {
                try {
                    node = list.get(Integer.parseInt(part));
                    continue;
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    // 落到下面统一报未解析
                }
            }
            throw new FlowReferenceException("未解析的引用 {{" + path + "}}");
        }
        return node;
    }

    /** 工具返回的是 JSON 对象或数组就解析出来，否则当纯文本。 */
    private static Object parseOutput(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return text;
        }
        try {
            Object parsed = MAPPER.readValue(trimmed, Object.class);
            return (parsed instanceof Map || parsed instanceof List) ? parsed : text;
        } catch (Exception e) {
            return text;
        }
    }

    private static String errorOf(Object output) {
        if (output instanceof Map<?, ?> map && map.get("error") != null) {
            return String.valueOf(map.get("error"));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> flow) {
        Object raw = flow == null ? null : flow.get("steps");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                steps.add((Map<String, Object>) map);
            }
        }
        return steps;
    }

    private static String chainOf(Map<String, Object> flow) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> step : stepsOf(flow)) {
            String tool = text(step.get("tool")).strip();
            names.add(tool.isBlank() ? "?" : tool);
        }
        return String.join(" → ", names);
    }

    static String asText(Object value) {
        if (value instanceof String str) {
            return str;
        }
        if (value == null) {
            return "";
        }
        return json(value);
    }

    /** 报告里的长文本和大数组要收一收，否则塞回模型上下文会爆。 */
    private static Object shrink(Object value) {
        if (value instanceof String str) {
            return str.length() <= STEP_TEXT_LIMIT ? str : str.substring(0, STEP_TEXT_LIMIT) + "…（已截断）";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), shrink(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list.subList(0, Math.min(list.size(), 20))) {
                out.add(shrink(item));
            }
            return out;
        }
        return value;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
