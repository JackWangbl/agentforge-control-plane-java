package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 可交互浏览器工具，对应 Python 版 app/services/browser_runtime.py。
 *
 * Python 版走 Playwright；Java 侧不引入额外依赖，改成用 JDK 自带的 HttpClient + WebSocket
 * 直连 Chrome DevTools Protocol，自己拉起一个 Chrome 进程。工具语义、限流和中文提示保持一致。
 */
@Component
public class BrowserRuntime {

    public static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    public static final int MAX_TEXT = 8000;

    /** Playwright 的 page.set_default_timeout(25000)。 */
    private static final long DEFAULT_TIMEOUT_MS = 25_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> BLOCK_TOKENS =
            List.of("验证码", "captcha", "滑动验证", "punish", "login.1688", "请登录", "扫码登录");

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, PageSession> pages = new LinkedHashMap<>();
    private final AppSettings settings;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Process chrome;
    private Path chromeProfile;
    private int devtoolsPort;
    private CdpConnection browserConnection;

    public BrowserRuntime(AppSettings settings) {
        this.settings = settings;
    }

    public List<Map<String, Object>> browserToolSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        specs.add(Map.of(
                "name", "browser_open",
                "description", "用可交互浏览器打开网页并返回标题、地址和可见正文。1688、需要登录或动态加载的页面必须用这个工具，不要空口说打不开。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "完整 http/https 链接"),
                                "wait_ms", Map.of("type", "integer", "description", "额外等待毫秒，默认 2500")),
                        "required", List.of("url"))));
        specs.add(Map.of(
                "name", "browser_text",
                "description", "读取当前浏览器页面的可见文本。打开页面后继续摘字段时使用。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "max_chars", Map.of("type", "integer", "description", "最多返回多少字，默认 8000")))));
        specs.add(Map.of(
                "name", "browser_links",
                "description", "列出当前页面链接。可按关键词过滤，例如 offer、头巾、hijab。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "只保留包含该关键词的链接或锚文本"),
                                "limit", Map.of("type", "integer", "description", "最多返回多少条，默认 20")))));
        specs.add(Map.of(
                "name", "browser_close",
                "description", "关闭当前浏览器会话，释放资源。",
                "parameters", Map.of("type", "object", "properties", Map.of())));
        return specs;
    }

    public String executeBrowserTool(String name, Map<String, Object> arguments) {
        return executeBrowserTool(name, arguments, "standalone");
    }

    public String executeBrowserTool(String name, Map<String, Object> arguments, String scopeId) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            if ("browser_open".equals(name)) {
                return openPage(text(args.get("url")), intOf(args.get("wait_ms"), 2500), scopeId);
            }
            if ("browser_text".equals(name)) {
                return pageText(intOf(args.get("max_chars"), MAX_TEXT), scopeId);
            }
            if ("browser_links".equals(name)) {
                return pageLinks(text(args.get("query")), intOf(args.get("limit"), 20), scopeId);
            }
            if ("browser_close".equals(name)) {
                closeBrowser(scopeId);
                return "浏览器已关闭。";
            }
            return "未知浏览器工具 " + name;
        } catch (Exception exc) {
            return "浏览器工具失败：" + message(exc);
        }
    }

    // --- 四个工具 ---

    public String openPage(String url, int waitMs, String scopeId) {
        String target = safeUrl(url);
        int wait = Math.max(0, Math.min(waitMs, 15000));
        lock.lock();
        try {
            PageSession page = ensurePage(scopeId);
            page.navigate(target);
            if (wait > 0) {
                sleep(wait);
            }
            String title = page.evaluateString("document.title");
            String current = page.evaluateString("location.href");
            String body = visibleText(page, MAX_TEXT);
            String blocked = blockHint(title, body, current);
            List<String> parts = new ArrayList<>();
            parts.add("已打开：" + title);
            parts.add("地址：" + current);
            if (!body.isEmpty()) {
                parts.add(body);
            }
            if (!blocked.isEmpty()) {
                parts.add(blocked);
            }
            return String.join("\n", parts);
        } finally {
            lock.unlock();
        }
    }

    public String pageText(int maxChars, String scopeId) {
        lock.lock();
        try {
            PageSession page = pages.get(scopeId);
            if (page == null) {
                return "还没有打开页面，请先调用 browser_open。";
            }
            return visibleText(page, Math.max(200, Math.min(maxChars, 20000)));
        } finally {
            lock.unlock();
        }
    }

    public String pageLinks(String query, int limit, String scopeId) {
        int bounded = Math.max(1, Math.min(limit, 50));
        JsonNode items;
        lock.lock();
        try {
            PageSession page = pages.get(scopeId);
            if (page == null) {
                return "还没有打开页面，请先调用 browser_open。";
            }
            String raw = page.evaluateString(
                    "JSON.stringify(Array.prototype.slice.call(document.querySelectorAll('a[href]'))"
                            + ".map(function(el){return {href: el.href || '', text: (el.innerText || '').trim()};}))");
            items = parseJson(raw);
        } finally {
            lock.unlock();
        }

        String needle = (query == null ? "" : query).strip().toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String href = item.path("href").asText("").strip();
                String label = String.join(" ", item.path("text").asText("").trim().split("\\s+")).strip();
                if (!href.startsWith("http")) {
                    continue;
                }
                String blob = (href + " " + label).toLowerCase(Locale.ROOT);
                if (!needle.isEmpty() && !blob.contains(needle)) {
                    continue;
                }
                String key = href.split("\\?")[0];
                if (!seen.add(key)) {
                    continue;
                }
                rows.add("- " + (label.isEmpty() ? href : label) + " | " + href);
                if (rows.size() >= bounded) {
                    break;
                }
            }
        }
        if (rows.isEmpty()) {
            return "当前页没有匹配的链接。";
        }
        return "页面链接：\n" + String.join("\n", rows);
    }

    /** scopeId 传 null 表示连同 Chrome 进程一起收掉。 */
    public void closeBrowser(String scopeId) {
        lock.lock();
        try {
            List<String> scopeIds = scopeId != null ? List.of(scopeId) : new ArrayList<>(pages.keySet());
            for (String key : scopeIds) {
                PageSession page = pages.remove(key);
                if (page != null) {
                    page.close();
                }
            }
            if (scopeId == null) {
                shutdownChrome();
            }
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        closeBrowser(null);
    }

    // --- 链接与私网校验 ---

    String safeUrl(String url) {
        String target = (url == null ? "" : url).strip();
        URI parsed;
        try {
            parsed = new URI(target);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("请提供完整的 http/https 链接");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String authority = parsed.getRawAuthority();
        if (!ALLOWED_SCHEMES.contains(scheme) || authority == null || authority.isEmpty()) {
            throw ApiException.badRequest("请提供完整的 http/https 链接");
        }
        if (parsed.getRawUserInfo() != null && !parsed.getRawUserInfo().isEmpty()) {
            throw ApiException.badRequest("浏览器链接不能包含用户名或密码");
        }
        if (!settings.isBrowserAllowPrivateNetwork()) {
            rejectPrivateHost(parsed.getHost() == null ? "" : parsed.getHost());
        }
        return target;
    }

    private void rejectPrivateHost(String hostname) {
        String normalized = hostname.strip().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.equals("localhost") || normalized.endsWith(".localhost")) {
            throw ApiException.badRequest("浏览器不允许访问本机或私有网络地址");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("浏览器目标域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (!isGlobal(address)) {
                throw ApiException.badRequest("浏览器不允许访问本机或私有网络地址");
            }
        }
    }

    /** 对齐 Python ipaddress 的 is_global：环回、私网、链路本地、保留段一律拒。 */
    static boolean isGlobal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = raw[0] & 0xFF;
            int b = raw[1] & 0xFF;
            int c = raw[2] & 0xFF;
            if (a == 0 || a == 10 || a == 127 || a >= 240) {
                return false;
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return false;
            }
            if (a == 169 && b == 254) {
                return false;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return false;
            }
            if (a == 192 && b == 168) {
                return false;
            }
            if (a == 192 && b == 0 && (c == 0 || c == 2)) {
                return false;
            }
            if (a == 198 && (b == 18 || b == 19)) {
                return false;
            }
            if (a == 198 && b == 51 && c == 100) {
                return false;
            }
            return a != 203 || b != 0 || c != 113;
        }
        if (address instanceof Inet6Address) {
            int first = raw[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return false;
            }
            return (first != 0x00 || raw[1] != 0x00);
        }
        return true;
    }

    // --- 页面文本 ---

    private static String visibleText(PageSession page, int maxChars) {
        String text = page.evaluateString("(document.body && document.body.innerText) || ''");
        String compact = Stream.of(text.split("\r?\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (compact.length() > maxChars) {
            compact = compact.substring(0, maxChars) + "\n…（正文已截断）";
        }
        return compact.isEmpty() ? "页面可见文本为空（可能是验证码、登录墙或纯脚本渲染）。" : compact;
    }

    private static String blockHint(String title, String text, String url) {
        String blob = (title + "\n" + text + "\n" + url).toLowerCase(Locale.ROOT);
        for (String token : BLOCK_TOKENS) {
            if (blob.contains(token)) {
                return "提示：页面疑似验证码或登录墙。可设置环境变量 BROWSER_HEADED=1 后重启控制台，"
                        + "用有界面浏览器手动通过验证，再继续调用 browser_text / browser_links。";
            }
        }
        return "";
    }

    // --- Chrome 生命周期 ---

    private PageSession ensurePage(String scopeId) {
        PageSession existing = pages.get(scopeId);
        if (existing != null) {
            return existing;
        }
        ensureChrome();
        String browserContextId = browserConnection
                .call("Target.createBrowserContext", Map.of("disposeOnDetach", false), DEFAULT_TIMEOUT_MS)
                .path("browserContextId").asText("");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", "about:blank");
        if (!browserContextId.isEmpty()) {
            params.put("browserContextId", browserContextId);
        }
        String targetId = browserConnection.call("Target.createTarget", params, DEFAULT_TIMEOUT_MS)
                .path("targetId").asText("");
        if (targetId.isEmpty()) {
            throw new IllegalStateException("无法创建浏览器标签页");
        }
        CdpConnection connection = CdpConnection.connect("ws://127.0.0.1:" + devtoolsPort + "/devtools/page/" + targetId);
        PageSession page = new PageSession(connection, targetId, browserContextId, this);
        connection.call("Page.enable", Map.of(), DEFAULT_TIMEOUT_MS);
        connection.call("Runtime.enable", Map.of(), DEFAULT_TIMEOUT_MS);
        pages.put(scopeId, page);
        return page;
    }

    private void ensureChrome() {
        if (chrome != null && chrome.isAlive() && browserConnection != null && browserConnection.isOpen()) {
            return;
        }
        shutdownChrome();
        Path binary = chromeBinary();
        if (binary == null) {
            throw new IllegalStateException(
                    "未找到可用的 Chrome/Chromium。请安装 Google Chrome 或 Chromium，"
                            + "或用环境变量 BROWSER_CHROME_PATH 指定可执行文件路径。");
        }
        boolean headed = Set.of("1", "true", "yes")
                .contains((System.getenv("BROWSER_HEADED") == null ? "" : System.getenv("BROWSER_HEADED")).strip());
        try {
            chromeProfile = Files.createTempDirectory("agentforge-chrome-");
            List<String> command = new ArrayList<>(List.of(
                    binary.toString(),
                    "--remote-debugging-port=0",
                    "--remote-allow-origins=*",
                    "--user-data-dir=" + chromeProfile,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-background-networking",
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "about:blank"));
            if (!headed) {
                command.add(1, "--headless=new");
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            chrome = builder.start();
            devtoolsPort = awaitDevtoolsPort(chromeProfile);
            String wsUrl = browserWebSocketUrl(devtoolsPort);
            browserConnection = CdpConnection.connect(wsUrl);
        } catch (IOException e) {
            shutdownChrome();
            throw new IllegalStateException("启动 Chrome 失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            shutdownChrome();
            throw e;
        }
    }

    /** Chrome 用 --remote-debugging-port=0 时会把真实端口写进 DevToolsActivePort。 */
    private int awaitDevtoolsPort(Path profile) {
        Path portFile = profile.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (chrome != null && !chrome.isAlive()) {
                throw new IllegalStateException("Chrome 启动后立即退出，无法连接调试端口");
            }
            try {
                if (Files.isRegularFile(portFile)) {
                    List<String> lines = Files.readAllLines(portFile);
                    if (!lines.isEmpty() && lines.get(0).strip().matches("\\d+")) {
                        return Integer.parseInt(lines.get(0).strip());
                    }
                }
            } catch (IOException ignored) {
                // 文件正在写入，下一轮再看
            }
            sleep(100);
        }
        throw new IllegalStateException("等待 Chrome 调试端口超时");
    }

    private String browserWebSocketUrl(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parseJson(response.body());
            String url = body == null ? "" : body.path("webSocketDebuggerUrl").asText("");
            if (url.isEmpty()) {
                throw new IllegalStateException("Chrome 未返回调试 WebSocket 地址");
            }
            return url;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Chrome 调试信息失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取 Chrome 调试信息被中断", e);
        }
    }

    private static Path chromeBinary() {
        String configured = System.getenv("BROWSER_CHROME_PATH");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured.strip()))) {
            return Path.of(configured.strip());
        }
        List<String> candidates = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/snap/bin/chromium");
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")) {
            Path found = DockerSandbox.which(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void shutdownChrome() {
        if (browserConnection != null) {
            browserConnection.close();
            browserConnection = null;
        }
        if (chrome != null) {
            chrome.destroy();
            try {
                if (!chrome.waitFor(5, TimeUnit.SECONDS)) {
                    chrome.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                chrome.destroyForcibly();
            }
            chrome = null;
        }
        if (chromeProfile != null) {
            deleteTree(chromeProfile);
            chromeProfile = null;
        }
        devtoolsPort = 0;
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录残留无所谓
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    void detachPage(PageSession page) {
        if (browserConnection == null || !browserConnection.isOpen()) {
            return;
        }
        try {
            browserConnection.call("Target.closeTarget", Map.of("targetId", page.targetId), 5000);
        } catch (Exception ignored) {
            // 标签可能已经关了
        }
        if (!page.browserContextId.isEmpty()) {
            try {
                browserConnection.call("Target.disposeBrowserContext",
                        Map.of("browserContextId", page.browserContextId), 5000);
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    // --- 一个标签页 ---

    static final class PageSession {
        private final CdpConnection connection;
        private final String targetId;
        private final String browserContextId;
        private final BrowserRuntime owner;

        PageSession(CdpConnection connection, String targetId, String browserContextId, BrowserRuntime owner) {
            this.connection = connection;
            this.targetId = targetId;
            this.browserContextId = browserContextId;
            this.owner = owner;
        }

        /** 等 domcontentloaded，超时就按现状继续读，避免慢站直接失败。 */
        void navigate(String url) {
            CompletableFuture<JsonNode> loaded = connection.waitEvent("Page.domContentEventFired");
            JsonNode result = connection.call("Page.navigate", Map.of("url", url), DEFAULT_TIMEOUT_MS);
            String errorText = result.path("errorText").asText("");
            if (!errorText.isEmpty()) {
                loaded.cancel(true);
                throw new IllegalStateException(errorText);
            }
            try {
                loaded.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 页面一直在加载也先读当前内容
            }
        }

        String evaluateString(String expression) {
            JsonNode result = connection.call("Runtime.evaluate", Map.of(
                    "expression", expression,
                    "returnByValue", true,
                    "awaitPromise", false), DEFAULT_TIMEOUT_MS);
            JsonNode value = result.path("result").path("value");
            if (value.isMissingNode() || value.isNull()) {
                return "";
            }
            return value.isTextual() ? value.asText() : value.toString();
        }

        void close() {
            connection.close();
            owner.detachPage(this);
        }
    }

    // --- 最小 CDP 客户端 ---

    static final class CdpConnection {

        private static final HttpClient WS_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        private final WebSocket socket;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<JsonNode>> events = new ConcurrentHashMap<>();
        private final StringBuilder buffer = new StringBuilder();

        private CdpConnection(WebSocket socket) {
            this.socket = socket;
        }

        static CdpConnection connect(String url) {
            CdpConnection[] holder = new CdpConnection[1];
            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    webSocket.request(1);
                    CdpConnection self = holder[0];
                    if (self != null) {
                        self.onFragment(data, last);
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    CdpConnection self = holder[0];
                    if (self != null) {
                        self.failAll(error);
                    }
                }

                @Override
                public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    CdpConnection self = holder[0];
                    if (self != null) {
                        self.failAll(new IllegalStateException("CDP 连接已关闭"));
                    }
                    return null;
                }
            };
            try {
                WebSocket socket = WS_CLIENT
                        .newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(url), listener)
                        .get(20, TimeUnit.SECONDS);
                holder[0] = new CdpConnection(socket);
                return holder[0];
            } catch (Exception e) {
                throw new IllegalStateException("连接 Chrome 调试端口失败：" + rootMessage(e), e);
            }
        }

        private void onFragment(CharSequence data, boolean last) {
            String payload;
            synchronized (buffer) {
                buffer.append(data);
                if (!last) {
                    return;
                }
                payload = buffer.toString();
                buffer.setLength(0);
            }
            JsonNode node = parseJson(payload);
            if (node == null) {
                return;
            }
            if (node.hasNonNull("id")) {
                CompletableFuture<JsonNode> future = pending.remove(node.get("id").asInt());
                if (future != null) {
                    future.complete(node);
                }
                return;
            }
            String method = node.path("method").asText("");
            CompletableFuture<JsonNode> waiter = events.remove(method);
            if (waiter != null) {
                waiter.complete(node.path("params"));
            }
        }

        private void failAll(Throwable error) {
            pending.values().forEach(future -> future.completeExceptionally(error));
            pending.clear();
            events.values().forEach(future -> future.completeExceptionally(error));
            events.clear();
        }

        CompletableFuture<JsonNode> waitEvent(String method) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            events.put(method, future);
            return future;
        }

        JsonNode call(String method, Map<String, Object> params, long timeoutMs) {
            int id = nextId.getAndIncrement();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params == null ? Map.of() : params);
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                socket.sendText(MAPPER.writeValueAsString(payload), true).get(10, TimeUnit.SECONDS);
                JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                JsonNode error = response.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new IllegalStateException(error.path("message").asText(error.toString()));
                }
                return response.path("result");
            } catch (IllegalStateException e) {
                pending.remove(id);
                throw e;
            } catch (Exception e) {
                pending.remove(id);
                throw new IllegalStateException(method + " 调用失败：" + rootMessage(e), e);
            }
        }

        boolean isOpen() {
            return !socket.isOutputClosed();
        }

        void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
                // 连接可能已经断了
            }
            socket.abort();
            failAll(new IllegalStateException("CDP 连接已关闭"));
        }
    }

    // --- 杂项 ---

    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getMessage() == null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static String message(Exception exc) {
        if (exc instanceof ApiException api) {
            return String.valueOf(api.getMessage());
        }
        return rootMessage(exc);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed == 0 ? fallback : parsed;
        }
        String raw = text(value).strip();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = (int) Double.parseDouble(raw);
            return parsed == 0 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
