package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.repo.SandboxPolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在沙箱里执行 Agent 代码，对应 Python 版 app/services/sandbox_runtime.py。
 * 优先走 Docker，宿主机本地执行只在显式放开时才允许。
 */
@Component
public class SandboxRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本地兜底执行时塞在用户代码前面的断网垫片，与 Python 版逐字一致。 */
    private static final String NET_BLOCK = """

            import socket
            class _Denied(socket.socket):
                def __init__(self, *args, **kwargs):
                    raise OSError("sandbox network is denied")
            socket.socket = _Denied
            socket.create_connection = lambda *a, **k: (_ for _ in ()).throw(OSError("sandbox network is denied"))
            """;

    private static final Pattern MEMORY = Pattern.compile("^(\\d+(?:\\.\\d+)?)(gi?b?|mi?b?|ki?b?|b)?$");
    private static final Set<String> DENY_MODES = Set.of("deny", "denied", "none", "off");
    private static final List<String> LOCAL_ENV_KEYS =
            List.of("PATH", "LANG", "LC_ALL", "TZ", "SSL_CERT_FILE", "SSL_CERT_DIR");

    private final AppSettings settings;
    private final DockerSandbox docker;
    private final SandboxPolicyRepository sandboxes;

    public SandboxRuntime(AppSettings settings, DockerSandbox docker, SandboxPolicyRepository sandboxes) {
        this.settings = settings;
        this.docker = docker;
        this.sandboxes = sandboxes;
    }

    public static int parseTimeout(SandboxPolicy row) {
        int raw = row == null || row.getTimeoutSeconds() == 0 ? 60 : row.getTimeoutSeconds();
        return Math.max(1, Math.min(raw, 3600));
    }

    public static long parseMemoryBytes(SandboxPolicy row) {
        String raw = row == null || row.getMemoryLimit() == null || row.getMemoryLimit().isBlank()
                ? "1 GiB" : row.getMemoryLimit();
        String text = raw.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = MEMORY.matcher(text);
        if (!match.matches()) {
            return 1024L * 1024L * 1024L;
        }
        double value = Double.parseDouble(match.group(1));
        String unit = match.group(2) == null ? "gib" : match.group(2);
        if (unit.startsWith("g")) {
            return (long) (value * 1024 * 1024 * 1024);
        }
        if (unit.startsWith("m")) {
            return (long) (value * 1024 * 1024);
        }
        if (unit.startsWith("k")) {
            return (long) (value * 1024);
        }
        return (long) value;
    }

    public static boolean networkDenied(SandboxPolicy row) {
        String mode = row == null || row.getNetworkMode() == null || row.getNetworkMode().isBlank()
                ? "deny" : row.getNetworkMode();
        return DENY_MODES.contains(mode.toLowerCase(Locale.ROOT));
    }

    public Path sandboxRoot() {
        String configured = settings.getSandboxesDir() == null ? "" : settings.getSandboxesDir().strip();
        if (!configured.isEmpty()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.dir", ".")).resolve("workspaces").resolve("_sandboxes");
    }

    public Path sandboxWorkdir(SandboxPolicy row, long tenantId, String executionId) {
        String safeExecution = (executionId == null ? "" : executionId).replaceAll("[^a-zA-Z0-9._-]+", "-");
        if (safeExecution.length() > 96) {
            safeExecution = safeExecution.substring(0, 96);
        }
        String name = row == null || row.getName() == null || row.getName().isEmpty() ? "box" : row.getName();
        String boxName = name.replaceAll("[^a-zA-Z0-9._-]+", "-");
        long policyId = row == null || row.getId() == null ? 0L : row.getId();
        Path path = sandboxRoot()
                .resolve("tenant-" + tenantId)
                .resolve("policy-" + policyId + "-" + boxName)
                .resolve(safeExecution);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {
            // 目录已存在或没权限，后面执行时自然会报错
        }
        return path;
    }

    /** Java 版没有 agentscope_runtime / agentscope.workspace 这两个 Python 包，只剩 local 和 docker。 */
    public List<String> detectBackends() {
        List<String> found = new ArrayList<>();
        found.add("local");
        if (docker.dockerAvailable()) {
            found.add("docker");
        }
        return found;
    }

    public String preferredBackend(SandboxPolicy row) {
        String runtime = row == null || row.getRuntime() == null ? "" : row.getRuntime().toLowerCase(Locale.ROOT);
        if (docker.dockerImage(row == null ? null : row.getRuntime()) != null) {
            return "docker";
        }
        for (String token : List.of("docker", "agentscope", "runtime-sandbox", "e2b", "k8s")) {
            if (runtime.contains(token)) {
                List<String> backends = detectBackends();
                if (backends.contains("agentscope_runtime")) {
                    return "agentscope_runtime";
                }
                if (backends.contains("agentscope_workspace")) {
                    return "agentscope_workspace";
                }
                break;
            }
        }
        return "local";
    }

    public boolean backendReady(SandboxPolicy row) {
        String backend = preferredBackend(row);
        if (backend.equals("docker")) {
            String image = docker.dockerImage(row == null ? null : row.getRuntime());
            return docker.dockerAvailable() && image != null && !image.isEmpty() && docker.allowedImages().contains(image);
        }
        if (backend.equals("local")) {
            return localBackendAllowed();
        }
        return detectBackends().contains(backend);
    }

    public boolean localBackendAllowed() {
        return settings.isAllowUnsafeLocalSandbox();
    }

    public Map<String, Object> executeInSandbox(SandboxPolicy row, String kind, String payload) {
        return executeInSandbox(row, kind, payload, 0L, "standalone");
    }

    public Map<String, Object> executeInSandbox(SandboxPolicy row,
                                                String kind,
                                                String payload,
                                                long tenantId,
                                                String executionId) {
        if (row == null || !row.isEnabled()) {
            return failure("none", "沙箱已停用");
        }
        String body = payload == null ? "" : payload.strip();
        if (body.isEmpty()) {
            return failure("none", "没有可执行内容");
        }
        String backend = preferredBackend(row);
        if (backend.equals("docker")) {
            return docker.runInDocker(
                    row.getRuntime(),
                    kind,
                    body,
                    sandboxWorkdir(row, tenantId, executionId),
                    tenantId,
                    executionId,
                    parseTimeout(row),
                    parseMemoryBytes(row),
                    row.getCpuLimit(),
                    networkDenied(row));
        }
        return runLocal(kind, body, row, tenantId, executionId);
    }

    private Map<String, Object> runLocal(String kind,
                                         String payload,
                                         SandboxPolicy row,
                                         long tenantId,
                                         String executionId) {
        if (!localBackendAllowed()) {
            return failure("local", "宿主机本地沙箱默认禁用，请配置独立沙箱运行时");
        }
        int timeout = parseTimeout(row);
        Path workdir = sandboxWorkdir(row, tenantId, executionId);
        boolean deny = networkDenied(row);
        Map<String, String> env = safeSubprocessEnv(workdir);
        List<String> prefix = deny ? sandboxExecPrefix(workdir) : List.of();

        List<String> inner = new ArrayList<>();
        if ("python".equals(kind)) {
            String script = (deny ? NET_BLOCK : "") + "\n" + payload;
            inner.add(pythonExecutable());
            inner.add("-I");
            inner.add("-c");
            inner.add(script);
        } else {
            inner.add("/bin/sh");
            inner.add("-c");
            inner.add(payload);
        }

        DockerSandbox.ProcResult completed;
        String text;
        try {
            List<String> command = new ArrayList<>(prefix);
            command.addAll(inner);
            completed = DockerSandbox.runProcess(command, workdir, env, timeout);
            text = combine(completed);
            // 沙箱壳子本身被系统拦下来时退回裸执行，跟 Python 版一样
            if (!prefix.isEmpty() && completed.exitCode() != 0 && text.contains("Operation not permitted")) {
                completed = DockerSandbox.runProcess(inner, workdir, env, timeout);
                text = combine(completed);
            }
        } catch (DockerSandbox.ProcTimeoutException e) {
            return failure("local", "执行超时（" + timeout + "s）");
        } catch (Exception e) {
            return failure("local", String.valueOf(e.getMessage()));
        }
        if (completed.exitCode() != 0) {
            Map<String, Object> result = failure("local", text.strip().isEmpty() ? "exit " + completed.exitCode() : text.strip());
            result.put("output", text.strip());
            return result;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("backend", "local");
        ok.put("output", text.strip());
        return ok;
    }

    private static String combine(DockerSandbox.ProcResult completed) {
        String stdout = completed.stdout() == null ? "" : completed.stdout();
        String stderr = completed.stderr() == null ? "" : completed.stderr();
        return stdout + (stderr.isEmpty() ? "" : "\n" + stderr);
    }

    private static String pythonExecutable() {
        Path found = DockerSandbox.which("python3");
        if (found == null) {
            found = DockerSandbox.which("python");
        }
        return found == null ? "python3" : found.toString();
    }

    private static List<String> sandboxExecPrefix(Path workdir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && DockerSandbox.which("sandbox-exec") != null) {
            String profile = "(version 1)\n"
                    + "(allow default)\n"
                    + "(deny network*)\n"
                    + "(allow file-write* (subpath \"" + workdir + "\"))\n";
            return List.of("sandbox-exec", "-p", profile);
        }
        if (DockerSandbox.which("unshare") != null) {
            return List.of("unshare", "--net", "--map-root-user");
        }
        return List.of();
    }

    private static Map<String, String> safeSubprocessEnv(Path workdir) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : LOCAL_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                env.put(key, value);
            }
        }
        env.put("HOME", workdir.toString());
        env.put("TMPDIR", workdir.toString());
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        return env;
    }

    public Map<String, Object> probeSandbox(SandboxPolicy row) {
        long tenantId = row == null || row.getTenantId() == null ? 0L : row.getTenantId();
        String executionId = "probe-" + (row == null || row.getId() == null ? 0L : row.getId());
        Map<String, Object> python = executeInSandbox(row, "python", "print('sandbox-ok', 1+1)", tenantId, executionId);
        Map<String, Object> network = executeInSandbox(
                row,
                "python",
                "import socket\nprint(socket.create_connection(('1.1.1.1', 53), 1))",
                tenantId,
                executionId);
        boolean denied = networkDenied(row);
        boolean pythonOk = Boolean.TRUE.equals(python.get("ok"));
        boolean networkOk = denied ? !Boolean.TRUE.equals(network.get("ok")) : true;
        boolean ready = pythonOk && networkOk;
        String name = row == null || row.getName() == null ? "" : row.getName();

        String message;
        if (denied && Boolean.TRUE.equals(network.get("ok"))) {
            message = name + " 代码能跑，但网络隔离未生效。";
        } else if (pythonOk) {
            message = name + " 已用 " + text(python.get("backend")) + " 后端跑通：" + text(python.get("output"));
        } else {
            String detail = text(python.get("error"));
            message = name + " 试跑失败：" + (detail.isEmpty() ? text(python.get("output")) : detail);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        String backend = text(python.get("backend"));
        result.put("backend", backend.isEmpty() ? preferredBackend(row) : backend);
        result.put("available_backends", detectBackends());
        result.put("sample", text(python.get("output")));
        result.put("network_isolated", denied && !Boolean.TRUE.equals(network.get("ok")));
        result.put("message", message);
        if (ready) {
            result.put("error", "");
        } else {
            String error = text(python.get("error"));
            result.put("error", error.isEmpty() ? text(network.get("error")) : error);
        }
        return result;
    }

    /** Agent 绑定的沙箱，停用或跨租户都当没绑。 */
    public SandboxPolicy selectedSandbox(Agent agent) {
        if (agent == null || agent.getSandboxId() == null) {
            return null;
        }
        Optional<SandboxPolicy> found = sandboxes.findById(agent.getSandboxId());
        if (found.isEmpty()) {
            return null;
        }
        SandboxPolicy row = found.get();
        boolean sameTenant = row.getTenantId() != null && row.getTenantId().equals(agent.getTenantId());
        if (!sameTenant || !row.isEnabled()) {
            return null;
        }
        return row;
    }

    public static List<Map<String, Object>> sandboxToolSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        specs.add(Map.of(
                "name", "sandbox_run_python",
                "description", "在 Agent 绑定的沙箱里执行 Python 代码。需要计算、写文件或验证网络隔离时调用。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of("code", Map.of("type", "string", "description", "要执行的 Python 代码")),
                        "required", List.of("code"))));
        specs.add(Map.of(
                "name", "sandbox_run_shell",
                "description", "在 Agent 绑定的沙箱里执行 shell 命令，受策略的超时和网络限制约束。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of("command", Map.of("type", "string", "description", "例如 ls 或 echo hello")),
                        "required", List.of("command"))));
        return specs;
    }

    public String runSandboxTool(SandboxPolicy row, String name, Map<String, Object> arguments) {
        return runSandboxTool(row, name, arguments, 0L, "standalone");
    }

    /** 成功返回纯文本输出，失败返回 JSON 错误体，与 Python 版一致。 */
    public String runSandboxTool(SandboxPolicy row,
                                 String name,
                                 Map<String, Object> arguments,
                                 long tenantId,
                                 String executionId) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Map<String, Object> result;
        if ("sandbox_run_python".equals(name)) {
            result = executeInSandbox(row, "python", text(args.get("code")), tenantId, executionId);
        } else if ("sandbox_run_shell".equals(name)) {
            result = executeInSandbox(row, "shell", text(args.get("command")), tenantId, executionId);
        } else {
            return json(Map.of("error", "未知沙箱工具 " + name));
        }
        if (Boolean.TRUE.equals(result.get("ok"))) {
            String output = text(result.get("output"));
            return output.isEmpty() ? "(无输出)" : output;
        }
        Map<String, Object> error = new LinkedHashMap<>();
        String detail = text(result.get("error"));
        error.put("error", detail.isEmpty() ? "沙箱执行失败" : detail);
        error.put("output", text(result.get("output")));
        error.put("backend", result.get("backend"));
        return json(error);
    }

    private static Map<String, Object> failure(String backend, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("backend", backend);
        result.put("output", "");
        result.put("error", error);
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
