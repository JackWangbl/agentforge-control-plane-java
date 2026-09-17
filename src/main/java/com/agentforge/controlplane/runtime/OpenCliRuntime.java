package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.OpenCliEndpoint;
import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 登记远程 OpenCLI / Chrome CDP 端点并查询在线浏览器，
 * 对应 Python 版 app/services/opencli_runtime.py。
 *
 * Python 里读页面正文优先走 Playwright 的 connect_over_cdp；Java 侧没有 Playwright，
 * 换成直接用 CDP WebSocket 在目标标签上执行 Runtime.evaluate，语义等价。
 */
@Component
public class OpenCliRuntime {

    public static final List<String> OPENCLI_KINDS = List.of("cdp", "daemon");
    public static final int TEXT_LIMIT = 8000;

    private static final Pattern UNSAFE_COMMAND = Pattern.compile("[|&;<>`$(){}!\\n]|&&|\\|\\|");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    /** 对应 Python 里那个 SimpleNamespace：OpenCLI 目标的连接信息。 */
    public static final class OpenCliTarget {
        private final String kind;
        private final String endpoint;
        private final String target;
        private final String session;
        private final String token;
        private final String command;

        public OpenCliTarget(String kind, String endpoint, String target, String session, String token, String command) {
            this.kind = normalizeKind(kind);
            this.endpoint = endpoint == null ? "" : endpoint;
            this.target = target == null ? "" : target;
            String rawSession = session == null || session.isEmpty() ? "agentforge" : session;
            this.session = rawSession;
            this.token = token == null ? "" : token;
            this.command = command == null ? "" : command;
        }

        public String getKind() { return kind; }
        public String getEndpoint() { return endpoint; }
        public String getTarget() { return target; }
        public String getSession() { return session; }
        public String getToken() { return token; }
        public String getCommand() { return command; }
    }

    public static String normalizeKind(String value) {
        String kind = (value == null || value.isEmpty() ? "cdp" : value).strip().toLowerCase(Locale.ROOT);
        return OPENCLI_KINDS.contains(kind) ? kind : "cdp";
    }

    public static String kindLabel(String value) {
        return normalizeKind(value).equals("daemon") ? "OpenCLI Daemon" : "Chrome CDP";
    }

    public static String normalizeEndpoint(String value) {
        String raw = (value == null ? "" : value).strip();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            throw ApiException.badRequest("请填写 OpenCLI / CDP 地址");
        }
        URI parsed;
        try {
            parsed = new URI(raw);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("地址必须是 http 或 https，例如 http://127.0.0.1:9222");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest("地址必须是 http 或 https，例如 http://127.0.0.1:9222");
        }
        if (parsed.getRawAuthority() == null || parsed.getRawAuthority().isEmpty()) {
            throw ApiException.badRequest("地址不完整");
        }
        return raw;
    }

    // --- 指令解析 ---

    /** 返回 {command, args, endpoint, kind, target, session}，键名与 Python 版一致。 */
    public static Map<String, Object> parseOpencliCommand(String raw) {
        String text = (raw == null ? "" : raw).strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("请填写 OpenCLI 指令");
        }
        if (UNSAFE_COMMAND.matcher(text).find()) {
            throw ApiException.badRequest("指令里不能包含管道、重定向或命令拼接");
        }
        List<String> tokens;
        try {
            tokens = shlexSplit(text);
        } catch (IllegalArgumentException exc) {
            throw ApiException.badRequest("指令无法解析：" + exc.getMessage());
        }
        if (tokens.isEmpty()) {
            throw ApiException.badRequest("请填写 OpenCLI 指令");
        }
        String first = basename(tokens.get(0)).toLowerCase(Locale.ROOT);
        if (first.equals("opencli") || first.equals("open-cli") || first.endsWith("opencli")) {
            tokens = tokens.subList(1, tokens.size());
        }

        String endpoint = "";
        String kind = "cdp";
        String target = "";
        String session = "agentforge";
        String host = "";
        String port = "";
        List<String> args = new ArrayList<>();
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            String low = token.toLowerCase(Locale.ROOT);
            if (low.equals("--cdp") || low.equals("--endpoint") || low.equals("--url")) {
                index++;
                if (index >= tokens.size()) {
                    throw ApiException.badRequest(token + " 后面需要地址");
                }
                endpoint = tokens.get(index);
                index++;
                continue;
            }
            if (low.equals("--host")) {
                index++;
                if (index >= tokens.size()) {
                    throw ApiException.badRequest("--host 后面需要主机");
                }
                host = tokens.get(index);
                kind = "daemon";
                index++;
                continue;
            }
            if (low.equals("--port")) {
                index++;
                if (index >= tokens.size()) {
                    throw ApiException.badRequest("--port 后面需要端口");
                }
                port = tokens.get(index);
                kind = "daemon";
                index++;
                continue;
            }
            if (low.equals("--daemon")) {
                kind = "daemon";
                index++;
                continue;
            }
            if (low.equals("--session") || low.equals("-s")) {
                index++;
                if (index < tokens.size()) {
                    session = tokens.get(index);
                }
                index++;
                continue;
            }
            if (low.equals("--target") || low.equals("--filter")) {
                index++;
                if (index < tokens.size()) {
                    target = tokens.get(index);
                }
                index++;
                continue;
            }
            if (token.startsWith("http://") || token.startsWith("https://")) {
                endpoint = stripTrailing(token, "/,");
                index++;
                continue;
            }
            args.add(token);
            index++;
        }
        if (endpoint.isEmpty() && !host.isEmpty()) {
            endpoint = "http://" + host + ":" + (port.isEmpty() ? "19825" : port);
        }
        if (!endpoint.isEmpty()) {
            endpoint = normalizeEndpoint(endpoint);
            if (endpoint.contains(":19825")) {
                kind = "daemon";
            }
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("command", text);
        parsed.put("args", args);
        parsed.put("endpoint", endpoint);
        parsed.put("kind", kind);
        parsed.put("target", target);
        parsed.put("session", session.isEmpty() ? "agentforge" : session);
        return parsed;
    }

    public String runOpencliCommand(OpenCliTarget row) {
        return runOpencliCommand(row, "");
    }

    public String runOpencliCommand(OpenCliTarget row, String command) {
        Map<String, Object> parsed = resolveOpencliCommand(row, command);
        Path binary = DockerSandbox.which("opencli");
        if (binary == null) {
            throw new IllegalStateException("本机未安装 opencli。请安装后再执行指令，或在指令里写 http://host:9222 走 Chrome CDP。");
        }
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) parsed.get("args");
        List<String> argv = new ArrayList<>();
        argv.add(binary.toString());
        if (args != null && !args.isEmpty()) {
            argv.addAll(args);
        } else {
            argv.add("tab");
            argv.add("list");
        }
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String endpoint = text(parsed.get("endpoint"));
        if (!endpoint.isEmpty()) {
            env.put("OPENCLI_CDP_ENDPOINT", endpoint);
            if ("daemon".equals(parsed.get("kind"))) {
                env.put("OPENCLI_DAEMON", endpoint);
            }
        }
        String target = text(parsed.get("target"));
        if (!target.isEmpty()) {
            env.put("OPENCLI_CDP_TARGET", target);
        }
        DockerSandbox.ProcResult completed;
        try {
            completed = DockerSandbox.runProcess(argv, null, env, 45);
        } catch (Exception exc) {
            throw new IllegalStateException("执行 OpenCLI 失败：" + exc.getMessage(), exc);
        }
        String stdout = completed.stdout() == null ? "" : completed.stdout();
        String stderr = completed.stderr() == null ? "" : completed.stderr();
        String output = (stdout.isEmpty() ? stderr : stdout).strip();
        if (completed.exitCode() != 0 && output.isEmpty()) {
            throw new IllegalStateException("opencli 退出码 " + completed.exitCode());
        }
        return clip(output, TEXT_LIMIT);
    }

    public Map<String, Object> resolveOpencliCommand(OpenCliTarget row, String command) {
        String extra = (command == null ? "" : command).strip();
        String base = row == null ? "" : row.getCommand().strip();
        if (!extra.isEmpty()) {
            Map<String, Object> parsed = parseOpencliCommand(extra);
            if (text(parsed.get("endpoint")).isEmpty() && !base.isEmpty()) {
                Map<String, Object> inherited = parseOpencliCommand(base);
                String inheritedEndpoint = text(inherited.get("endpoint"));
                parsed.put("endpoint", inheritedEndpoint.isEmpty() ? parsed.get("endpoint") : inheritedEndpoint);
                String inheritedKind = text(inherited.get("kind"));
                parsed.put("kind", inheritedKind.isEmpty() ? parsed.get("kind") : inheritedKind);
                if ("agentforge".equals(parsed.get("session")) && !text(inherited.get("session")).isEmpty()) {
                    parsed.put("session", inherited.get("session"));
                }
                if (text(parsed.get("target")).isEmpty() && !text(inherited.get("target")).isEmpty()) {
                    parsed.put("target", inherited.get("target"));
                }
            }
            if (text(parsed.get("endpoint")).isEmpty()) {
                String fallback = row == null ? "" : row.getEndpoint();
                if (fallback.startsWith("http")) {
                    parsed.put("endpoint", fallback);
                }
            }
            return parsed;
        }
        if (!base.isEmpty()) {
            Map<String, Object> parsed = parseOpencliCommand(base);
            if (text(parsed.get("endpoint")).isEmpty()) {
                String fallback = row.getEndpoint();
                if (fallback.startsWith("http")) {
                    parsed.put("endpoint", fallback);
                }
            }
            return parsed;
        }
        throw ApiException.badRequest("没有可执行的 OpenCLI 指令");
    }

    // --- 从数据库实体转成连接目标 ---

    public static OpenCliTarget fromMcp(McpServer row) {
        Map<String, Object> config = row == null || row.getConfig() == null ? Map.of() : row.getConfig();
        Map<String, Object> headers = config.get("headers") instanceof Map<?, ?> map ? asStringMap(map) : Map.of();
        String token = firstText(
                config.get("token"),
                config.get("api_key"),
                headers.get("Authorization"),
                headers.get("authorization"));
        Object kindRaw = config.get("opencli_kind") != null ? config.get("opencli_kind") : config.get("kind");
        String kind = text(kindRaw).toLowerCase(Locale.ROOT);
        if (!OPENCLI_KINDS.contains(kind)) {
            kind = "cdp";
        }
        return new OpenCliTarget(
                normalizeKind(kind),
                row == null || row.getEndpoint() == null ? "" : row.getEndpoint(),
                text(config.get("target")),
                text(config.get("session")).isEmpty() ? "agentforge" : text(config.get("session")),
                token,
                text(config.get("command")));
    }

    public static OpenCliTarget fromEndpoint(OpenCliEndpoint row) {
        if (row == null) {
            return new OpenCliTarget("cdp", "", "", "agentforge", "", "");
        }
        return new OpenCliTarget(row.getKind(), row.getEndpoint(), row.getTarget(), row.getSession(), row.getToken(), "");
    }

    public static Map<String, Object> applyOpencliConfig(Map<String, Object> config, String endpoint) {
        Map<String, Object> data = new LinkedHashMap<>(config == null ? Map.of() : config);
        String command = text(data.get("command")).strip();
        String rawEndpoint = (endpoint == null || endpoint.isEmpty() ? text(data.get("endpoint")) : endpoint).strip();
        if (command.isEmpty()) {
            if (rawEndpoint.isEmpty()) {
                throw ApiException.badRequest("请填写 OpenCLI 指令，例如：opencli --cdp http://10.0.0.8:9222 tab list");
            }
            List<String> parts = new ArrayList<>();
            parts.add("opencli --cdp " + rawEndpoint);
            if (!text(data.get("target")).isEmpty()) {
                parts.add("--target " + data.get("target"));
            }
            parts.add("tab list");
            command = String.join(" ", parts);
        }
        Map<String, Object> parsed = parseOpencliCommand(command);
        String resolved = text(parsed.get("endpoint"));
        if (resolved.isEmpty()) {
            resolved = rawEndpoint;
        }
        if (!resolved.isEmpty()) {
            resolved = normalizeEndpoint(resolved);
        }
        data.put("command", command);
        data.put("kind", text(data.get("kind")).isEmpty() ? parsed.get("kind") : normalizeKind(text(data.get("kind"))));
        data.put("opencli_kind", data.get("kind"));
        data.put("target", text(data.get("target")).isEmpty() ? text(parsed.get("target")) : text(data.get("target")));
        String session = text(data.get("session")).isEmpty() ? text(parsed.get("session")) : text(data.get("session"));
        data.put("session", session.isEmpty() ? "agentforge" : session);
        data.put("endpoint", resolved);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> spec : opencliToolSpecs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.get("name"));
            item.put("description", spec.get("description"));
            item.put("parameters", spec.get("parameters"));
            tools.add(item);
        }
        data.put("tools", tools);
        return data;
    }

    // --- HTTP 探活 ---

    private static Map<String, String> headers(OpenCliTarget row) {
        String token = row.getToken().strip();
        if (token.isEmpty()) {
            return Map.of();
        }
        if (token.contains(":") && !token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            int split = token.indexOf(':');
            return Map.of(token.substring(0, split).strip(), token.substring(split + 1).strip());
        }
        return Map.of("Authorization",
                token.toLowerCase(Locale.ROOT).startsWith("bearer ") ? token : "Bearer " + token);
    }

    private HttpResponse<String> get(OpenCliTarget row, String path, int timeoutSeconds) {
        String url = normalizeEndpoint(row.getEndpoint()) + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        headers(row).forEach((key, value) -> {
            try {
                builder.header(key, value);
            } catch (IllegalArgumentException ignored) {
                // JDK 保留头不让自定义
            }
        });
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }

    public Map<String, Object> probeOpencli(OpenCliTarget row) {
        String sample = "";
        RuntimeException cliError = null;
        if (!row.getCommand().isEmpty()) {
            try {
                sample = runOpencliCommand(row);
            } catch (RuntimeException exc) {
                cliError = exc;
            }
        }
        String endpoint = row.getEndpoint();
        if (endpoint.startsWith("http")) {
            try {
                Map<String, Object> probed = probeHttp(row);
                probed.put("sample", sample);
                probed.put("command", row.getCommand());
                return probed;
            } catch (RuntimeException exc) {
                if (!sample.isEmpty()) {
                    return cliOnlyProbe(row, sample);
                }
                if (cliError != null) {
                    throw cliError;
                }
                throw exc;
            }
        }
        if (!sample.isEmpty()) {
            return cliOnlyProbe(row, sample);
        }
        if (cliError != null) {
            throw cliError;
        }
        return probeHttp(row);
    }

    private static Map<String, Object> cliOnlyProbe(OpenCliTarget row, String sample) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", true);
        result.put("kind", normalizeKind(row.getKind()));
        result.put("endpoint", row.getEndpoint());
        result.put("browser", "OpenCLI");
        result.put("tabs", 0);
        result.put("tab_list", List.of());
        result.put("sample", sample);
        result.put("command", row.getCommand());
        return result;
    }

    private Map<String, Object> probeHttp(OpenCliTarget row) {
        String kind = normalizeKind(row.getKind());
        String endpoint = normalizeEndpoint(row.getEndpoint());
        if (kind.equals("daemon")) {
            HttpResponse<String> ping = get(row, "/ping", 6);
            List<Map<String, Object>> tabs;
            try {
                tabs = listTabs(row, "");
            } catch (RuntimeException exc) {
                tabs = List.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ready", true);
            result.put("kind", kind);
            result.put("endpoint", endpoint);
            result.put("browser", "OpenCLI Daemon");
            if (isError(ping)) {
                HttpResponse<String> status = get(row, "/status", 6);
                if (isError(status)) {
                    throw new IllegalStateException("Daemon 不可达：HTTP " + ping.statusCode());
                }
                Map<String, Object> body = safeJsonObject(status);
                result.put("detail", body != null ? body : Map.of("raw", clip(status.body() == null ? "" : status.body(), 400)));
            } else {
                result.put("detail", safeText(ping));
            }
            result.put("tabs", tabs.size());
            result.put("tab_list", tabs.subList(0, Math.min(tabs.size(), 30)));
            return result;
        }
        HttpResponse<String> version = get(row, "/json/version", 6);
        if (isError(version)) {
            throw new IllegalStateException("CDP 不可达：HTTP " + version.statusCode());
        }
        Map<String, Object> data = safeJsonObject(version);
        if (data == null) {
            data = Map.of();
        }
        List<Map<String, Object>> tabs = listTabs(row, "");
        String browser = firstText(data.get("Browser"), data.get("BrowserVersion"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", true);
        result.put("kind", kind);
        result.put("endpoint", endpoint);
        result.put("browser", browser.isEmpty() ? "Chrome" : browser);
        result.put("tabs", tabs.size());
        result.put("tab_list", tabs.subList(0, Math.min(tabs.size(), 30)));
        result.put("detail", data);
        return result;
    }

    public List<Map<String, Object>> listTabs(OpenCliTarget row, String target) {
        String kind = normalizeKind(row.getKind());
        List<Map<String, Object>> tabs;
        if (kind.equals("daemon")) {
            tabs = listDaemonTabs(row);
        } else {
            HttpResponse<String> response = get(row, "/json/list", 6);
            if (isError(response)) {
                throw new IllegalStateException("读取标签失败：HTTP " + response.statusCode());
            }
            Object payload = safeJson(response);
            if (!(payload instanceof List<?> list)) {
                throw new IllegalStateException("CDP /json/list 返回不是标签数组");
            }
            tabs = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    tabs.add(normalizeTab(map));
                }
            }
        }
        String wanted = (target == null ? "" : target).strip();
        String needle = (wanted.isEmpty() ? row.getTarget() : wanted).strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return tabs;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> tab : tabs) {
            String blob = (text(tab.get("id")) + " " + text(tab.get("url")) + " " + text(tab.get("title")))
                    .toLowerCase(Locale.ROOT);
            if (blob.contains(needle)) {
                filtered.add(tab);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> listDaemonTabs(OpenCliTarget row) {
        try {
            HttpResponse<String> response = get(row, "/tabs", 6);
            if (!isError(response)) {
                Object payload = safeJson(response);
                Object rows = payload instanceof List ? payload
                        : payload instanceof Map<?, ?> map ? map.get("tabs") : null;
                List<Map<String, Object>> tabs = new ArrayList<>();
                if (rows instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            tabs.add(normalizeTab(map));
                        }
                    }
                }
                return tabs;
            }
        } catch (RuntimeException ignored) {
            // Daemon 没开 /tabs，落到本机 opencli
        }
        String cli = opencliCli(row, List.of("tab", "list"));
        if (cli != null) {
            return tabsFromCli(cli);
        }
        throw new IllegalStateException("Daemon 未提供 /tabs。请改用 Chrome CDP 地址（远程调试端口 9222），或保证本机已安装 opencli。");
    }

    public Map<String, Object> queryBrowser(OpenCliTarget row,
                                            String action,
                                            String target,
                                            String selector,
                                            String expression,
                                            String command) {
        String method = (action == null || action.isEmpty() ? "query" : action).strip().toLowerCase(Locale.ROOT);
        String cmd = command == null ? "" : command;
        String expr = expression == null ? "" : expression;
        boolean explicitExec = Set.of("exec", "run", "command").contains(method);
        if (explicitExec || (!cmd.isEmpty() && !Set.of("tabs", "query", "eval").contains(method))) {
            String output = runOpencliCommand(row, cmd.isEmpty() ? expr : cmd);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "exec");
            result.put("command", firstText(cmd, expr, row.getCommand()));
            result.put("output", output);
            return result;
        }
        if (method.equals("tabs")) {
            try {
                List<Map<String, Object>> tabs = listTabs(row, target);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("action", "tabs");
                result.put("count", tabs.size());
                result.put("tabs", tabs);
                result.put("filter", firstText(target, row.getTarget()));
                return result;
            } catch (RuntimeException exc) {
                String output = runOpencliCommand(row, cmd.isEmpty() ? "tab list" : cmd);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("action", "exec");
                result.put("command", cmd.isEmpty() ? "tab list" : cmd);
                result.put("output", output);
                return result;
            }
        }
        List<Map<String, Object>> tabs;
        try {
            tabs = listTabs(row, target);
        } catch (RuntimeException exc) {
            String output = runOpencliCommand(row, cmd.isEmpty() ? expr : cmd);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "exec");
            result.put("command", cmd.isEmpty() ? row.getCommand() : cmd);
            result.put("output", output);
            return result;
        }
        Map<String, Object> chosen = pickTab(tabs, firstText(target, row.getTarget()));
        if (chosen == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", method);
            result.put("error", "没有匹配的标签。先调用 opencli_tabs，或把 target 写成 URL 关键字 / 标签 id。");
            result.put("tabs", tabs.subList(0, Math.min(tabs.size(), 8)));
            return result;
        }
        if (method.equals("eval")) {
            String script = expr.strip().isEmpty() ? "document.title" : expr.strip();
            String value = evaluate(row, chosen, script);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "eval");
            result.put("tab", chosen);
            result.put("expression", script);
            result.put("result", value);
            return result;
        }
        String script = queryScript(selector);
        String value = evaluate(row, chosen, script);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "query");
        result.put("tab", chosen);
        result.put("text", value);
        return result;
    }

    public Map<String, Object> queryBrowser(OpenCliTarget row) {
        return queryBrowser(row, "query", "", "", "", "");
    }

    /**
     * 工具层的入口：按工具名分派到 queryBrowser，统一返回 JSON 字符串，
     * 对应 Python 版 tool_runtime 里 opencli_* 那段分支。
     */
    public String executeOpencliTool(OpenCliTarget row, String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            Map<String, Object> result;
            if ("opencli".equals(name) || "opencli_exec".equals(name)) {
                result = queryBrowser(row, "exec", "", "", "", text(args.get("command")));
            } else {
                String action = "opencli_tabs".equals(name) ? "tabs"
                        : "opencli_eval".equals(name) ? "eval" : "query";
                result = queryBrowser(
                        row,
                        action,
                        text(args.get("target")),
                        text(args.get("selector")),
                        text(args.get("expression")),
                        text(args.get("command")));
            }
            return McpStreamClient.json(result);
        } catch (Exception exc) {
            return McpStreamClient.json(Map.of("error", String.valueOf(exc.getMessage())));
        }
    }

    public static List<Map<String, Object>> opencliToolSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        specs.add(Map.of(
                "name", "opencli",
                "description", "执行一段 OpenCLI 指令，查询远程浏览器标签或页面数据。不填 command 则执行已登记的默认指令。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "opencli", Map.of("type", "string", "description", "OpenCLI MCP 名称，不填则用已绑定的第一个"),
                                "command", Map.of("type", "string", "description", "例如 tab list，或 eval document.title。可省略 opencli 前缀。")))));
        specs.add(Map.of(
                "name", "opencli_tabs",
                "description", "列出已注册 OpenCLI / 远程 Chrome 里的浏览器标签（标题、URL、id）。查询某个页面数据前先调用。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "opencli", Map.of("type", "string", "description", "OpenCLI 配置名称，不填则用已绑定的第一个"),
                                "target", Map.of("type", "string", "description", "按 URL 或标题关键字过滤")))));
        specs.add(Map.of(
                "name", "opencli_query",
                "description", "读取指定浏览器标签的标题、地址和可见文本。target 填标签 id 或 URL 关键字。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "opencli", Map.of("type", "string", "description", "OpenCLI 配置名称"),
                                "target", Map.of("type", "string", "description", "标签 id 或 URL/标题关键字"),
                                "selector", Map.of("type", "string", "description", "可选 CSS，只提取该节点文本")))));
        specs.add(Map.of(
                "name", "opencli_eval",
                "description", "在指定标签执行只读 JavaScript 并返回结果，例如 document.title 或 JSON.stringify(location.href)。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "opencli", Map.of("type", "string", "description", "OpenCLI 配置名称"),
                                "target", Map.of("type", "string", "description", "标签 id 或 URL/标题关键字"),
                                "expression", Map.of("type", "string", "description", "JavaScript 表达式")),
                        "required", List.of("expression"))));
        return specs;
    }

    // --- 标签与求值 ---

    private static Map<String, Object> normalizeTab(Map<?, ?> item) {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("id", firstText(item.get("id"), item.get("targetId"), item.get("webSocketDebuggerUrl")));
        tab.put("title", text(item.get("title")));
        tab.put("url", text(item.get("url")));
        String type = text(item.get("type"));
        tab.put("type", type.isEmpty() ? "page" : type);
        return tab;
    }

    private static Map<String, Object> pickTab(List<Map<String, Object>> tabs, String target) {
        String needle = (target == null ? "" : target).strip().toLowerCase(Locale.ROOT);
        if (tabs.isEmpty()) {
            return null;
        }
        if (needle.isEmpty()) {
            for (Map<String, Object> item : tabs) {
                if ("page".equals(item.get("type"))) {
                    return item;
                }
            }
            return tabs.get(0);
        }
        for (Map<String, Object> item : tabs) {
            String blob = (text(item.get("id")) + " " + text(item.get("url")) + " " + text(item.get("title")))
                    .toLowerCase(Locale.ROOT);
            if (blob.contains(needle) || text(item.get("id")).equals(target)) {
                return item;
            }
        }
        return null;
    }

    private static String queryScript(String selector) {
        String css = (selector == null ? "" : selector).strip();
        if (!css.isEmpty()) {
            return "(function(){const el=document.querySelector(" + McpStreamClient.json(css)
                    + "); return el ? (el.innerText||el.textContent||'') : '';})()";
        }
        return "(document.body && (document.body.innerText||document.body.textContent)||'').slice(0,8000)";
    }

    private String evaluate(OpenCliTarget row, Map<String, Object> tab, String expression) {
        String text = evaluateCdp(row, tab, expression);
        if (text != null) {
            return clip(text, TEXT_LIMIT);
        }
        String tabId = text(tab.get("id"));
        String cli = tabId.isEmpty()
                ? opencliCli(row, List.of("eval", expression))
                : opencliCli(row, List.of("eval", "--tab", tabId, expression));
        if (cli != null && !cli.isEmpty()) {
            return clip(cli, TEXT_LIMIT);
        }
        return "已定位标签，但无法读取正文（需要 CDP WebSocket 或本机 opencli）。title="
                + text(tab.get("title")) + " url=" + text(tab.get("url"));
    }

    /** 直连目标标签的 CDP WebSocket 求值，替代 Python 版的 playwright connect_over_cdp。 */
    private String evaluateCdp(OpenCliTarget row, Map<String, Object> tab, String expression) {
        String tabId = text(tab.get("id"));
        if (tabId.isEmpty()) {
            return null;
        }
        String wsUrl;
        if (tabId.startsWith("ws://") || tabId.startsWith("wss://")) {
            wsUrl = tabId;
        } else {
            String endpoint;
            try {
                endpoint = normalizeEndpoint(row.getEndpoint());
            } catch (RuntimeException exc) {
                return null;
            }
            wsUrl = endpoint.replaceFirst("^https", "wss").replaceFirst("^http:", "ws:")
                    + "/devtools/page/" + tabId;
        }
        BrowserRuntime.CdpConnection connection = null;
        try {
            connection = BrowserRuntime.CdpConnection.connect(wsUrl);
            var result = connection.call("Runtime.evaluate", Map.of(
                    "expression", expression,
                    "returnByValue", true,
                    "awaitPromise", true), 20_000);
            var value = result.path("result").path("value");
            if (value.isMissingNode() || value.isNull()) {
                return "";
            }
            if (value.isObject() || value.isArray()) {
                return clip(value.toString(), TEXT_LIMIT);
            }
            return value.isTextual() ? value.asText() : value.toString();
        } catch (RuntimeException exc) {
            return null;
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private String opencliCli(OpenCliTarget row, List<String> args) {
        Path binary = DockerSandbox.which("opencli");
        if (binary == null) {
            return null;
        }
        String session = row.getSession().strip().isEmpty() ? "agentforge" : row.getSession().strip();
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        if (normalizeKind(row.getKind()).equals("cdp")) {
            try {
                env.put("OPENCLI_CDP_ENDPOINT", normalizeEndpoint(row.getEndpoint()));
            } catch (RuntimeException ignored) {
                // 没登记地址就交给 opencli 自己找
            }
            if (!row.getTarget().isEmpty()) {
                env.put("OPENCLI_CDP_TARGET", row.getTarget());
            }
        }
        List<String> command = new ArrayList<>(List.of(binary.toString(), "browser", session));
        command.addAll(args);
        DockerSandbox.ProcResult completed;
        try {
            completed = DockerSandbox.runProcess(command, null, env, 45);
        } catch (Exception exc) {
            return null;
        }
        String stdout = completed.stdout() == null ? "" : completed.stdout();
        String stderr = completed.stderr() == null ? "" : completed.stderr();
        String output = (stdout.isEmpty() ? stderr : stdout).strip();
        return output.isEmpty() ? null : output;
    }

    private static List<Map<String, Object>> tabsFromCli(String raw) {
        List<Map<String, Object>> tabs = new ArrayList<>();
        try {
            Object data = MAPPER.readValue(raw, new TypeReference<Object>() {});
            Object rows = data instanceof List ? data : data instanceof Map<?, ?> map ? map.get("tabs") : null;
            if (rows instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        tabs.add(normalizeTab(map));
                    }
                }
            }
            if (!tabs.isEmpty()) {
                return tabs;
            }
        } catch (Exception ignored) {
            // 不是 JSON 就按行解析
        }
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Map<String, Object> tab = new LinkedHashMap<>();
            tab.put("id", trimmed.split("\\s+")[0]);
            tab.put("title", trimmed);
            tab.put("url", "");
            tab.put("type", "page");
            tabs.add(tab);
        }
        return tabs;
    }

    // --- 小工具 ---

    private static boolean isError(HttpResponse<String> response) {
        return response.statusCode() >= 400;
    }

    private static Object safeJson(HttpResponse<String> response) {
        try {
            return MAPPER.readValue(response.body(), new TypeReference<Object>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeJsonObject(HttpResponse<String> response) {
        Object parsed = safeJson(response);
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

    private static String safeText(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        return clip(body.strip(), 400);
    }

    private static Map<String, Object> asStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /** 等价于 shlex.split，引号没闭合时抛错。 */
    static List<String> shlexSplit(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = null;
        char quote = 0;
        int index = 0;
        while (index < text.length()) {
            char item = text.charAt(index);
            if (quote == 0) {
                if (Character.isWhitespace(item)) {
                    if (current != null) {
                        tokens.add(current.toString());
                        current = null;
                    }
                    index++;
                    continue;
                }
                if (item == '\'' || item == '"') {
                    quote = item;
                    if (current == null) {
                        current = new StringBuilder();
                    }
                    index++;
                    continue;
                }
                if (item == '\\' && index + 1 < text.length()) {
                    if (current == null) {
                        current = new StringBuilder();
                    }
                    current.append(text.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == null) {
                    current = new StringBuilder();
                }
                current.append(item);
                index++;
                continue;
            }
            if (item == quote) {
                quote = 0;
                index++;
                continue;
            }
            if (quote == '"' && item == '\\' && index + 1 < text.length()) {
                char next = text.charAt(index + 1);
                if (next == '"' || next == '\\') {
                    current.append(next);
                    index += 2;
                    continue;
                }
            }
            current.append(item);
            index++;
        }
        if (quote != 0) {
            throw new IllegalArgumentException("No closing quotation");
        }
        if (current != null) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String basename(String value) {
        Path path = Path.of(value);
        Path name = path.getFileName();
        return name == null ? value : name.toString();
    }

    private static String stripTrailing(String value, String chars) {
        String out = value;
        while (!out.isEmpty() && chars.indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String item = text(value);
            if (!item.isEmpty()) {
                return item;
            }
        }
        return "";
    }
}
