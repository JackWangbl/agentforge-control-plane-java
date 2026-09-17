package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.repo.HttpAgentRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把其他平台的 Agent 接到本平台：勾选 HTTP 接口后，这个 Agent 就是对方本身。
 * 对话、评测、invoke 都直接 POST 到对方，不再走本平台模型和 MCP。
 */
@Service
public class HttpAgentRuntime {

    public static final String TOOL_PREFIX = "ask_http_agent_";
    public static final int MAX_BOUND = 1;
    private static final int REPLY_LIMIT = 8000;
    private static final List<String> PROTOCOLS = List.of("generic", "openai", "agentforge", "dify");

    private final HttpAgentRepository httpAgents;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpAgentRuntime(HttpAgentRepository httpAgents) {
        this.httpAgents = httpAgents;
    }

    public static String toolName(Long id) {
        return TOOL_PREFIX + (id == null ? 0 : id);
    }

    public static boolean isHttpAgentTool(String name) {
        return name != null && name.startsWith(TOOL_PREFIX);
    }

    public static Long idFromTool(String name) {
        if (!isHttpAgentTool(name)) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(TOOL_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String normalizeProtocol(String value) {
        String raw = (value == null || value.isBlank() ? "generic" : value).strip().toLowerCase(Locale.ROOT)
                .replace("-", "_");
        return switch (raw) {
            case "openai", "chat", "chat_completions", "openai_chat" -> "openai";
            case "agentforge", "invoke", "agentforge_invoke" -> "agentforge";
            case "dify" -> "dify";
            default -> "generic";
        };
    }

    public static String protocolLabel(String value) {
        return switch (normalizeProtocol(value)) {
            case "openai" -> "OpenAI Chat";
            case "agentforge" -> "AgentForge invoke";
            case "dify" -> "Dify 对话";
            default -> "通用 JSON";
        };
    }

    public static String defaultInputField(String protocol) {
        return switch (normalizeProtocol(protocol)) {
            case "dify" -> "query";
            default -> "message";
        };
    }

    public static String defaultOutputPath(String protocol) {
        return switch (normalizeProtocol(protocol)) {
            case "openai" -> "choices.0.message.content";
            case "dify" -> "answer";
            case "agentforge" -> "reply";
            default -> "reply";
        };
    }

    public static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("HTTP 接口地址不合法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest("地址必须是 http 或 https，例如 https://other-platform/v1/chat");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest("请填写完整的 HTTP 接口地址");
        }
    }

    public static int clampTimeout(Integer seconds) {
        int value = seconds == null ? 30 : seconds;
        return Math.max(5, Math.min(120, value));
    }

    public List<HttpAgent> selectedAgents(Agent agent) {
        List<Long> ids = agent == null ? List.of() : agent.getHttpAgentIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, HttpAgent> found = new LinkedHashMap<>();
        for (HttpAgent row : httpAgents.findAllById(ids)) {
            if (row.getId() != null && sameTenant(row.getTenantId(), agent.getTenantId()) && row.isEnabled()) {
                found.put(row.getId(), row);
            }
        }
        List<HttpAgent> ordered = new ArrayList<>();
        for (Long id : ids) {
            HttpAgent row = found.get(id);
            if (row != null && ordered.size() < MAX_BOUND) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    public HttpAgent primary(Agent agent) {
        List<HttpAgent> rows = selectedAgents(agent);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean isHttpBacked(Agent agent) {
        return primary(agent) != null;
    }

    /** 这个 Agent 就是外部平台 Agent：把用户消息直接 POST 过去。 */
    public ChatReply chat(Agent agent, String message, String sessionId) {
        HttpAgent target = primary(agent);
        if (target == null) {
            throw ApiException.unprocessable("当前 Agent 没有接入 HTTP 接口");
        }
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("请提供要发给「" + target.getName() + "」的消息");
        }
        String sid = sessionId == null ? "" : sessionId.strip();
        String traceId = WorkspaceStore.newTraceId();
        long started = System.currentTimeMillis();
        try {
            CallResult result = call(target, text, sid);
            int latency = (int) Math.max(1, System.currentTimeMillis() - started);
            boolean ok = result.status() >= 200 && result.status() < 300
                    && (result.error() == null || result.error().isBlank());
            String reply = result.reply() == null ? "" : result.reply();
            if (!ok && reply.isBlank()) {
                reply = result.error() == null || result.error().isBlank()
                        ? ("HTTP " + result.status()) : result.error();
            }
            List<Map<String, Object>> spans = List.of(
                    AgentScopeRuntime.debugSpan("http.bind", "接入外部 Agent · " + target.getName(), "agent", "ok", 2,
                            protocolLabel(target.getProtocol()) + " · " + target.getEndpoint()),
                    AgentScopeRuntime.debugSpan("http.call", "直连 " + hostOf(target.getEndpoint()), "http",
                            ok ? "ok" : "error", latency, ok ? reply : result.error()));
            return new ChatReply(reply, ok ? "ready" : "error", spans, Map.of(), traceId);
        } catch (Exception e) {
            int latency = (int) Math.max(1, System.currentTimeMillis() - started);
            return new ChatReply(String.valueOf(e.getMessage()), "error", List.of(
                    AgentScopeRuntime.debugSpan("http.call", "直连失败", "http", "error", latency,
                            String.valueOf(e.getMessage()))), Map.of(), traceId);
        }
    }

    public static ModelConfig displayModel(HttpAgent http) {
        ModelConfig model = new ModelConfig();
        model.setName(http == null ? "HTTP Agent" : http.getName());
        model.setProvider("HTTP");
        model.setModelId(http == null ? "http" : protocolLabel(http.getProtocol()));
        model.setEnabled(true);
        return model;
    }

    public List<ToolSpec> toolSpecs(Agent agent) {
        List<ToolSpec> specs = new ArrayList<>();
        for (HttpAgent target : selectedAgents(agent)) {
            specs.add(specFor(target));
        }
        return specs;
    }

    public boolean allowsTool(Agent agent, String toolName) {
        Long id = idFromTool(toolName);
        if (id == null || agent == null || agent.getHttpAgentIds() == null) {
            return false;
        }
        return agent.getHttpAgentIds().contains(id)
                && selectedAgents(agent).stream().anyMatch(row -> id.equals(row.getId()));
    }

    public String executeTool(Agent caller, String toolName, Map<String, Object> arguments) {
        Long id = idFromTool(toolName);
        if (id == null) {
            return Jsons.json(Map.of("error", "未知的 HTTP Agent 工具 " + toolName));
        }
        HttpAgent target = selectedAgents(caller).stream()
                .filter(row -> id.equals(row.getId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Jsons.json(Map.of("error", "当前 Agent 未绑定 id=" + id + " 的 HTTP 接口"));
        }
        String message = Jsons.text(arguments == null ? null : arguments.get("message")).strip();
        if (message.isEmpty()) {
            return Jsons.json(Map.of("error", "请提供要发给「" + target.getName() + "」的 message"));
        }
        String sessionId = Jsons.text(arguments == null ? null : arguments.get("session_id")).strip();
        try {
            CallResult result = call(target, message, sessionId);
            Map<String, Object> payload = Jsons.ordered(
                    "agent", target.getName(),
                    "protocol", normalizeProtocol(target.getProtocol()),
                    "status", result.status(),
                    "reply", result.reply());
            if (result.error() != null && !result.error().isBlank()) {
                payload = new LinkedHashMap<>(payload);
                payload.put("error", result.error());
            }
            return Jsons.json(payload);
        } catch (Exception e) {
            return Jsons.json(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    public Map<String, Object> probe(HttpAgent row) {
        if (row == null) {
            throw ApiException.unprocessable("HTTP 接口不存在");
        }
        if (!row.isEnabled()) {
            throw ApiException.conflict("HTTP 接口已停用");
        }
        validateEndpoint(row.getEndpoint());
        long started = System.currentTimeMillis();
        try {
            CallResult result = call(row, "ping", "probe");
            long latency = System.currentTimeMillis() - started;
            boolean ok = result.status() >= 200 && result.status() < 300 && (result.error() == null || result.error().isBlank());
            String reply = result.reply() == null ? "" : result.reply();
            String preview = reply.length() > 240 ? reply.substring(0, 240) + "…" : reply;
            return Jsons.ordered(
                    "id", row.getId(),
                    "ready", ok,
                    "status", ok ? "ready" : "error",
                    "latency_ms", latency,
                    "http_status", result.status(),
                    "reply", preview,
                    "message", ok
                            ? row.getName() + " 已连通，耗时 " + latency + " ms"
                            : row.getName() + " 调用失败：" + (result.error() == null || result.error().isBlank()
                            ? ("HTTP " + result.status()) : result.error()));
        } catch (Exception e) {
            return Jsons.ordered(
                    "id", row.getId(),
                    "ready", false,
                    "status", "unreachable",
                    "latency_ms", System.currentTimeMillis() - started,
                    "message", row.getName() + " 无法访问：" + e.getMessage());
        }
    }

    public static Map<String, Object> publicHeaders(Map<String, Object> headers) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (headers == null) {
            return masked;
        }
        headers.forEach((key, value) -> {
            String text = value == null ? "" : String.valueOf(value);
            masked.put(key, text.isBlank() ? "" : "****");
        });
        return masked;
    }

    public static boolean hasAuth(Map<String, Object> headers) {
        if (headers == null) {
            return false;
        }
        for (Object value : headers.values()) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> mergeHeaders(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach((key, value) -> headers.put(key, value));
        }
        if (incoming == null) {
            return headers;
        }
        incoming.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String text = String.valueOf(value).strip();
            if (text.isEmpty() || "****".equals(text)) {
                return;
            }
            headers.put(key, text);
        });
        return headers;
    }

    private CallResult call(HttpAgent target, String message, String sessionId) throws Exception {
        validateEndpoint(target.getEndpoint());
        String protocol = normalizeProtocol(target.getProtocol());
        Map<String, Object> body = buildBody(target, protocol, message, sessionId);
        int timeout = clampTimeout(target.getTimeoutSeconds());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.getEndpoint().strip()))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.json(body)));
        applyHeaders(builder, target.getHeaders());
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = raw.length() > 400 ? raw.substring(0, 400) + "…" : raw;
            return new CallResult(response.statusCode(), "", "HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        String reply = extractReply(raw, target, protocol);
        if (reply.length() > REPLY_LIMIT) {
            reply = reply.substring(0, REPLY_LIMIT) + "…";
        }
        return new CallResult(response.statusCode(), reply, "");
    }

    private static Map<String, Object> buildBody(HttpAgent target, String protocol, String message, String sessionId) {
        Map<String, Object> config = target.getConfig() == null ? Map.of() : target.getConfig();
        return switch (protocol) {
            case "openai" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                String model = Jsons.text(config.get("model")).strip();
                if (!model.isEmpty()) {
                    body.put("model", model);
                }
                body.put("messages", List.of(Map.of("role", "user", "content", message)));
                yield body;
            }
            case "dify" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("query", message);
                String user = Jsons.text(config.get("user")).strip();
                body.put("user", user.isEmpty() ? "agentforge" : user);
                body.put("response_mode", "blocking");
                if (sessionId != null && !sessionId.isBlank()) {
                    body.put("conversation_id", sessionId);
                }
                yield body;
            }
            case "agentforge" -> Jsons.ordered(
                    "message", message,
                    "session_id", sessionId == null ? "" : sessionId);
            default -> {
                Map<String, Object> body = new LinkedHashMap<>();
                String field = target.getInputField() == null || target.getInputField().isBlank()
                        ? defaultInputField(protocol) : target.getInputField().strip();
                body.put(field, message);
                if (sessionId != null && !sessionId.isBlank()) {
                    body.put("session_id", sessionId);
                }
                yield body;
            }
        };
    }

    private static String extractReply(String raw, HttpAgent target, String protocol) {
        String path = target.getOutputPath() == null || target.getOutputPath().isBlank()
                ? defaultOutputPath(protocol) : target.getOutputPath().strip();
        Map<String, Object> parsed = Jsons.map(raw);
        if (parsed.isEmpty() && (raw.startsWith("{") || raw.startsWith("["))) {
            return raw.strip();
        }
        if (!parsed.isEmpty()) {
            Object hit = readPath(parsed, path);
            if (hit != null) {
                return stringify(hit);
            }
            for (String fallback : List.of("reply", "answer", "output", "text", "content",
                    "data.answer", "data.output", "choices.0.message.content")) {
                Object value = readPath(parsed, fallback);
                if (value != null) {
                    return stringify(value);
                }
            }
            return Jsons.json(parsed);
        }
        return raw.strip();
    }

    private static Object readPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current == null || part.isBlank()) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return Jsons.json(value);
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, Object> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String text = String.valueOf(value).strip();
            if (!text.isEmpty() && !"****".equals(text)) {
                builder.header(key, text);
            }
        });
    }

    private ToolSpec specFor(HttpAgent target) {
        String duty = target.getDescription() == null || target.getDescription().isBlank()
                ? protocolLabel(target.getProtocol()) + " 接口" : target.getDescription().strip();
        String host = hostOf(target.getEndpoint());
        String description = "通过 HTTP 调用外部 Agent「" + target.getName() + "」。"
                + duty + "。接口在 " + host + "，协议是 " + protocolLabel(target.getProtocol())
                + "。把完整问题放进 message，一次调用拿到对方回复。";
        return new ToolSpec(
                toolName(target.getId()),
                description,
                ToolSpec.objectSchema(Map.of(
                        "message", ToolSpec.stringParam("发给该外部 Agent 的完整问题或任务"),
                        "session_id", ToolSpec.stringParam("可选会话 id，能续上对方上下文时再传")), "message"));
    }

    private static String hostOf(String endpoint) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint.strip());
            return uri.getHost() == null ? endpoint : uri.getHost();
        } catch (Exception e) {
            return endpoint == null ? "" : endpoint;
        }
    }

    private static boolean sameTenant(Long left, Long right) {
        return left != null && left.equals(right);
    }

    private record CallResult(int status, String reply, String error) {}

    @SuppressWarnings("unused")
    private static List<String> supportedProtocols() {
        return PROTOCOLS;
    }
}
