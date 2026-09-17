package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.domain.McpServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 远程 MCP 客户端：Streamable HTTP / SSE 上的 JSON-RPC，
 * 对应 Python 版 app/services/mcp_stream.py。只用 JDK 自带的 HttpClient。
 */
@Component
public class McpStreamClient {

    public static final List<String> PROTOCOL_VERSIONS = List.of("2025-03-26", "2024-11-05");
    public static final Set<String> HTTP_STREAM_ALIASES = Set.of("streamable_http", "http", "http_stream", "stream");
    public static final Set<String> ALLOWED_TRANSPORTS = Set.of("stdio", "sse", "streamable_http", "opencli");
    public static final String MASKED_SECRET = "****";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SECRET_TOKENS =
            List.of("authorization token", "authorization", "api_key", "apikey", "password", "secret", "token");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // --- transport 归一化 ---

    public static String normalizeMcpTransport(String value) {
        String raw = (value == null || value.isEmpty() ? "stdio" : value).strip().toLowerCase(Locale.ROOT).replace("-", "_");
        if (HTTP_STREAM_ALIASES.contains(raw)) {
            return "streamable_http";
        }
        if (raw.equals("sse") || raw.equals("stdio") || raw.equals("opencli")) {
            return raw;
        }
        return raw;
    }

    public static boolean isHttpStreamTransport(String value) {
        return normalizeMcpTransport(value).equals("streamable_http");
    }

    public static boolean isOpencliTransport(String value) {
        return normalizeMcpTransport(value).equals("opencli");
    }

    public static String transportLabel(String value) {
        return switch (normalizeMcpTransport(value)) {
            case "streamable_http" -> "HTTP Stream";
            case "sse" -> "SSE";
            case "stdio" -> "StdIO";
            case "opencli" -> "OpenCLI";
            default -> (value == null ? "" : value).toUpperCase(Locale.ROOT);
        };
    }

    public static String agentscopeTransport(String value) {
        String kind = normalizeMcpTransport(value);
        return kind.equals("streamable_http") ? "streamable_http" : kind;
    }

    // --- 鉴权头 ---

    public static Map<String, String> requestHeaders(McpServer row) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, Object> config = row == null || row.getConfig() == null ? Map.of() : row.getConfig();
        Object extra = config.get("headers");
        if (extra instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if (!key.isEmpty() && item != null && !String.valueOf(item).strip().isEmpty()) {
                    headers.put(key, String.valueOf(item).strip());
                }
            }
        }
        Object apiKey = config.get("api_key");
        Object rawToken = apiKey != null && !String.valueOf(apiKey).isEmpty() ? apiKey : config.get("token");
        String token = rawToken == null ? "" : String.valueOf(rawToken).strip();
        if (!token.isEmpty() && !headers.containsKey("Authorization")) {
            headers.put("Authorization",
                    token.toLowerCase(Locale.ROOT).startsWith("bearer ") ? token : "Bearer " + token);
        }
        return headers;
    }

    // --- 配置合并与脱敏 ---

    public static Map<String, Object> mergeMcpConfig(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> base = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        Map<String, Object> extra = new LinkedHashMap<>(incoming == null ? Map.of() : incoming);
        Map<String, Object> headers = new LinkedHashMap<>();
        Object baseHeaders = base.get("headers");
        if (baseHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> headers.put(String.valueOf(key), value));
        }
        Object incomingHeaders = extra.remove("headers");
        if (incomingHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value != null && !MASKED_SECRET.equals(String.valueOf(value))) {
                    headers.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        if (!headers.isEmpty()) {
            base.put("headers", headers);
        }
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            if (isSecretKey(entry.getKey()) && MASKED_SECRET.equals(entry.getValue())) {
                continue;
            }
            base.put(entry.getKey(), entry.getValue());
        }
        return base;
    }

    /** 回给浏览器的配置里不能带凭据。 */
    public static Map<String, Object> publicMcpConfig(Map<String, Object> config) {
        return maskMapping(config == null ? Map.of() : config);
    }

    private static Map<String, Object> maskMapping(Map<String, Object> value) {
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object item = entry.getValue();
            if (isSecretKey(entry.getKey()) && isTruthy(item)) {
                masked.put(entry.getKey(), MASKED_SECRET);
            } else if (item instanceof Map<?, ?> nested) {
                Map<String, Object> copy = new LinkedHashMap<>();
                nested.forEach((key, child) -> copy.put(String.valueOf(key), child));
                masked.put(entry.getKey(), maskMapping(copy));
            } else {
                masked.put(entry.getKey(), item);
            }
        }
        return masked;
    }

    private static boolean isTruthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static boolean isSecretKey(String key) {
        String normalized = (key == null ? "" : key).toLowerCase(Locale.ROOT).replace("-", "_");
        for (String token : SECRET_TOKENS) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // --- JSON-RPC ---

    private static Map<String, Object> rpc(String method, Map<String, Object> params, int requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", requestId);
        payload.put("method", method);
        if (params != null) {
            payload.put("params", params);
        }
        return payload;
    }

    /** SSE 帧里按 data: 聚合出若干 JSON 块，取最后一个能解析成对象的。 */
    public static Map<String, Object> parseSse(String text) {
        List<String> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\r?\n", -1)) {
            if (line.startsWith("data:")) {
                current.add(stripLeading(line.substring(5)));
            } else if (line.strip().isEmpty() && !current.isEmpty()) {
                blocks.add(String.join("\n", current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            blocks.add(String.join("\n", current));
        }
        for (int index = blocks.size() - 1; index >= 0; index--) {
            String block = blocks.get(index);
            if (block.strip().isEmpty()) {
                continue;
            }
            Map<String, Object> data = readMap(block);
            if (data != null) {
                return data;
            }
        }
        return Map.of();
    }

    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    private static Map<String, Object> parseResponse(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        String text = response.body() == null ? "" : response.body();
        String lstripped = stripLeading(text);
        if (contentType.contains("text/event-stream") || lstripped.startsWith("event:") || lstripped.startsWith("data:")) {
            return parseSse(text);
        }
        if (text.strip().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = readMap(text);
        return data == null ? parseSse(text) : data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String raw) {
        try {
            Object parsed = MAPPER.readValue(raw, new TypeReference<Object>() {});
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String rpcError(Map<String, Object> body) {
        Object err = body == null ? null : body.get("error");
        if (err instanceof Map<?, ?> map) {
            Object message = map.get("message");
            return message != null && !String.valueOf(message).isEmpty() ? String.valueOf(message) : json(map);
        }
        if (isTruthy(err)) {
            return String.valueOf(err);
        }
        return "";
    }

    private static List<Map<String, Object>> toolsFromResult(Map<String, Object> body) {
        Object resultRaw = body == null ? null : body.get("result");
        if (!(resultRaw instanceof Map<?, ?> result)) {
            return List.of();
        }
        Object raw = result.get("tools");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> item) || item.get("name") == null
                    || String.valueOf(item.get("name")).isEmpty()) {
                continue;
            }
            Object schema = firstNonEmpty(item.get("inputSchema"), item.get("input_schema"), item.get("parameters"));
            if (schema == null) {
                schema = Map.of("type", "object", "properties", Map.of());
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", String.valueOf(item.get("name")));
            tool.put("description", item.get("description") == null ? "" : String.valueOf(item.get("description")));
            tool.put("parameters", schema instanceof Map ? schema : Map.of("type", "object", "properties", Map.of()));
            tools.add(tool);
        }
        return tools;
    }

    private static Object firstNonEmpty(Object... values) {
        for (Object value : values) {
            if (isTruthy(value)) {
                return value;
            }
        }
        return null;
    }

    /** 发一次 JSON-RPC；顺手把 mcp-session-id 记回 headers，后续请求带上。 */
    private Map<String, Object> post(String url,
                                     Map<String, Object> payload,
                                     Map<String, String> headers,
                                     Duration timeout) {
        HttpResponse<String> response = send(url, payload, headers, timeout);
        response.headers().firstValue("mcp-session-id")
                .ifPresent(session -> headers.put("Mcp-Session-Id", session));
        if (response.statusCode() >= 400) {
            Map<String, Object> body = parseResponse(response);
            String detail = rpcError(body);
            if (detail.isEmpty()) {
                String raw = response.body() == null || response.body().isEmpty()
                        ? String.valueOf(response.statusCode()) : response.body();
                detail = raw.length() > 240 ? raw.substring(0, 240) : raw;
            }
            throw new IllegalStateException(response.statusCode() + " " + detail);
        }
        return parseResponse(response);
    }

    private HttpResponse<String> send(String url,
                                      Map<String, Object> payload,
                                      Map<String, String> headers,
                                      Duration timeout) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("HTTP Stream 需要填写 http(s) 服务地址，例如 https://host/mcp");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json(payload)));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            try {
                builder.header(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // JDK 保留头（Host/Connection 之类）不让自定义，跳过
            }
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }

    private static Map<String, String> streamHeaders(McpServer row) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        headers.putAll(requestHeaders(row));
        return headers;
    }

    private static Map<String, Object> initializeParams(String version) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", version);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "agentforge-control-plane", "version", "0.1.0"));
        return params;
    }

    /** 逐个协议版本试 initialize + tools/list，返回工具表和实际谈成的版本。 */
    private Session streamableSession(McpServer row) {
        String url = row == null || row.getEndpoint() == null ? "" : row.getEndpoint().strip();
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("HTTP Stream 需要填写 http(s) 服务地址，例如 https://host/mcp");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || parsed.getRawAuthority() == null
                || parsed.getRawAuthority().isEmpty()) {
            throw new IllegalStateException("HTTP Stream 需要填写 http(s) 服务地址，例如 https://host/mcp");
        }
        Map<String, String> headers = streamHeaders(row);
        Duration timeout = Duration.ofSeconds(12);
        String lastError = "";
        for (String version : PROTOCOL_VERSIONS) {
            try {
                Map<String, Object> body = post(url, rpc("initialize", initializeParams(version), 1), headers, timeout);
                String err = rpcError(body);
                if (!err.isEmpty()) {
                    lastError = err;
                    continue;
                }
                notifyInitialized(url, headers, timeout);
                Map<String, Object> listed = post(url, rpc("tools/list", Map.of(), 2), headers, timeout);
                err = rpcError(listed);
                if (!err.isEmpty()) {
                    lastError = err;
                    continue;
                }
                return new Session(toolsFromResult(listed), version);
            } catch (Exception exc) {
                lastError = String.valueOf(exc.getMessage());
            }
        }
        throw new IllegalStateException(lastError.isEmpty() ? "HTTP Stream 握手失败" : lastError);
    }

    private void notifyInitialized(String url, Map<String, String> headers, Duration timeout) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        try {
            send(url, notification, headers, timeout);
        } catch (Exception ignored) {
            // 通知失败不影响后续调用，与 Python 版不检查返回值一致
        }
    }

    private record Session(List<Map<String, Object>> tools, String protocol) {}

    public Map<String, Object> probeStreamableHttp(McpServer row) {
        Session session = streamableSession(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", session.tools());
        result.put("protocol", session.protocol());
        result.put("message", (row == null ? "" : row.getName()) + " 已通过 HTTP Stream 连通，发现 "
                + session.tools().size() + " 个工具。");
        return result;
    }

    public String callStreamableHttpTool(McpServer row, String name, Map<String, Object> arguments) {
        String url = row == null || row.getEndpoint() == null ? "" : row.getEndpoint().strip();
        Map<String, String> headers = streamHeaders(row);
        Duration timeout = Duration.ofSeconds(30);
        Map<String, Object> body = post(url, rpc("initialize", initializeParams(PROTOCOL_VERSIONS.get(0)), 1),
                headers, timeout);
        String err = rpcError(body);
        if (!err.isEmpty()) {
            throw new IllegalStateException(err);
        }
        notifyInitialized(url, headers, timeout);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Map<String, Object> result = post(url, rpc("tools/call", params, 3), headers, timeout);
        String callError = rpcError(result);
        if (!callError.isEmpty()) {
            return json(Map.of("error", callError));
        }
        Object payload = result.get("result") instanceof Map ? result.get("result") : result;
        return payload instanceof String text ? text : json(payload);
    }

    public List<Map<String, Object>> listTools(McpServer row) {
        return streamableSession(row).tools();
    }

    public static void applyDiscoveredTools(McpServer row, List<Map<String, Object>> tools) {
        List<Map<String, Object>> discovered = tools == null ? List.of() : tools;
        row.setToolsCount(discovered.size());
        Map<String, Object> config = new LinkedHashMap<>(row.getConfig() == null ? Map.of() : row.getConfig());
        config.put("tools", discovered);
        Object kind = config.get("kind");
        config.put("kind", isTruthy(kind) ? kind : "http");
        row.setConfig(config);
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
