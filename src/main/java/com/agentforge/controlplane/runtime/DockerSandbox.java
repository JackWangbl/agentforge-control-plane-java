package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.config.AppSettings;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把不可信的 Agent 代码丢进短命且收紧过的 Docker 容器里跑，
 * 对应 Python 版 app/services/docker_sandbox.py。直接调 docker 可执行文件。
 */
@Component
public class DockerSandbox {

    public static final String DEFAULT_IMAGE = "python:3.11-slim";
    public static final Set<String> DEFAULT_ALLOWED_IMAGES = Set.of("python:3.11-slim", "python:3.12-slim");
    public static final int MAX_OUTPUT_CHARS = 20_000;

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.:/@-]{0,199}$");
    private static final Pattern SAFE_NETWORK = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$");
    private static final List<String> DOCKER_ENV_KEYS =
            List.of("PATH", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH");

    private final AppSettings settings;

    public DockerSandbox(AppSettings settings) {
        this.settings = settings;
    }

    public boolean dockerAvailable() {
        return which("docker") != null;
    }

    /** runtime 写成 docker / docker://image / docker:image 时才落到 Docker 后端。 */
    public String dockerImage(String runtime) {
        String value = runtime == null ? "" : runtime.strip();
        String lowered = value.toLowerCase();
        if (lowered.equals("docker")) {
            String configured = settings.getSandboxDefaultImage() == null ? "" : settings.getSandboxDefaultImage().strip();
            return configured.isEmpty() ? DEFAULT_IMAGE : configured;
        }
        if (lowered.startsWith("docker://")) {
            return value.substring("docker://".length()).strip();
        }
        if (lowered.startsWith("docker:")) {
            return value.substring("docker:".length()).strip();
        }
        return null;
    }

    public Set<String> allowedImages() {
        String configured = settings.getSandboxAllowedImages() == null ? "" : settings.getSandboxAllowedImages().strip();
        if (configured.isEmpty()) {
            return new LinkedHashSet<>(DEFAULT_ALLOWED_IMAGES);
        }
        Set<String> images = new LinkedHashSet<>();
        for (String item : configured.split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                images.add(trimmed);
            }
        }
        return images;
    }

    public static double parseCpuLimit(String value) {
        String text = value == null || value.isBlank() ? "1" : value;
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
        double parsed = matcher.find() ? Double.parseDouble(matcher.group()) : 1.0;
        return Math.max(0.1, Math.min(parsed, 8.0));
    }

    /** 返回 {ok, backend, output} 或 {ok=false, backend, output, error}，与 Python 版键名一致。 */
    public Map<String, Object> runInDocker(String runtime,
                                           String kind,
                                           String payload,
                                           Path workdir,
                                           long tenantId,
                                           String executionId,
                                           int timeoutSeconds,
                                           long memoryBytes,
                                           String cpuLimit,
                                           boolean networkDenied) {
        String image = dockerImage(runtime);
        if (image == null || image.isEmpty() || !SAFE_NAME.matcher(image).matches() || !allowedImages().contains(image)) {
            return error("Docker 镜像未列入 SANDBOX_ALLOWED_IMAGES 白名单");
        }
        if (!dockerAvailable()) {
            return error("Docker 不可用，拒绝回退到控制面宿主机");
        }

        List<String> networkArgs;
        try {
            networkArgs = networkArgs(networkDenied);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }

        Path resolved;
        Path scriptPath = null;
        List<String> inner;
        try {
            Files.createDirectories(workdir.toAbsolutePath().normalize());
            resolved = workdir.toAbsolutePath().normalize().toRealPath();
            if ("python".equals(kind)) {
                Path scripts = resolved.resolve(".agentforge");
                Files.createDirectories(scripts);
                scriptPath = scripts.resolve("run-" + UUID.randomUUID().toString().replace("-", "") + ".py");
                Files.writeString(scriptPath, payload == null ? "" : payload, StandardCharsets.UTF_8);
                inner = List.of("python", "-I", "/workspace/.agentforge/" + scriptPath.getFileName());
            } else {
                inner = List.of("/bin/sh", "-lc", payload == null ? "" : payload);
            }
        } catch (IOException e) {
            return error(String.valueOf(e.getMessage()));
        }

        String containerName = containerName(tenantId, executionId);
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--pull=never",
                "--name", containerName,
                "--label", "agentforge.tenant_id=" + tenantId,
                "--label", "agentforge.execution_id=" + labelValue(executionId)));
        command.addAll(networkArgs);
        command.addAll(List.of(
                "--init",
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", "64",
                "--memory", String.valueOf(memoryBytes),
                "--memory-swap", String.valueOf(memoryBytes),
                "--cpus", String.valueOf(parseCpuLimit(cpuLimit)),
                "--ulimit", "nofile=256:256",
                "--user", currentUid() + ":" + currentGid(),
                "--workdir", "/workspace",
                "--env", "HOME=/workspace",
                "--env", "TMPDIR=/tmp",
                "--env", "PYTHONDONTWRITEBYTECODE=1",
                "--mount", "type=bind,src=" + resolved + ",dst=/workspace,rw",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m",
                image));
        command.addAll(inner);

        try {
            ProcResult completed = runProcess(command, null, dockerCliEnv(), timeoutSeconds);
            String output = boundedOutput(completed.stdout(), completed.stderr());
            if (completed.exitCode() != 0) {
                return error(output.isEmpty() ? "Docker exit " + completed.exitCode() : output, output);
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("backend", "docker");
            ok.put("output", output);
            return ok;
        } catch (ProcTimeoutException e) {
            forceRemove(containerName);
            return error("Docker 沙箱执行超时（" + timeoutSeconds + "s）");
        } catch (Exception e) {
            forceRemove(containerName);
            return error(String.valueOf(e.getMessage()));
        } finally {
            if (scriptPath != null) {
                try {
                    Files.deleteIfExists(scriptPath);
                } catch (IOException ignored) {
                    // 清理失败不影响结果
                }
            }
        }
    }

    private List<String> networkArgs(boolean denied) {
        if (denied) {
            return List.of("--network", "none");
        }
        String network = settings.getSandboxEgressNetwork() == null ? "" : settings.getSandboxEgressNetwork().strip();
        if (network.isEmpty() || !SAFE_NETWORK.matcher(network).matches()) {
            throw new IllegalStateException("联网沙箱必须配置受控的 SANDBOX_EGRESS_NETWORK");
        }
        return List.of("--network", network);
    }

    private static String containerName(long tenantId, String executionId) {
        String raw = tenantId + ":" + (executionId == null ? "" : executionId) + ":"
                + UUID.randomUUID().toString().replace("-", "");
        return "agentforge-t" + Math.max(0L, tenantId) + "-" + sha256Hex(raw).substring(0, 20);
    }

    private static String labelValue(String value) {
        String cleaned = (value == null ? "" : value).replaceAll("[^a-zA-Z0-9_.-]+", "-");
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        return cleaned.isEmpty() ? "standalone" : cleaned;
    }

    private static Map<String, String> dockerCliEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : DOCKER_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                env.put(key, value);
            }
        }
        return env;
    }

    private void forceRemove(String containerName) {
        try {
            runProcess(List.of("docker", "rm", "-f", containerName), null, dockerCliEnv(), 5);
        } catch (Exception ignored) {
            // 容器可能已经自己退出了
        }
    }

    static String boundedOutput(String stdout, String stderr) {
        String combined = (stdout == null ? "" : stdout)
                + (stderr == null || stderr.isEmpty() ? "" : "\n" + stderr);
        combined = combined.strip();
        if (combined.length() <= MAX_OUTPUT_CHARS) {
            return combined;
        }
        return combined.substring(0, MAX_OUTPUT_CHARS) + "\n…（沙箱输出已截断）";
    }

    private static Map<String, Object> error(String message) {
        return error(message, "");
    }

    private static Map<String, Object> error(String message, String output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("backend", "docker");
        result.put("output", output);
        result.put("error", message);
        return result;
    }

    // --- 包内共用的小工具 ---

    /** 进程执行超时，对应 Python 的 subprocess.TimeoutExpired。 */
    static class ProcTimeoutException extends RuntimeException {
        ProcTimeoutException(String message) {
            super(message);
        }
    }

    record ProcResult(int exitCode, String stdout, String stderr) {}

    /** env 传 null 表示继承当前进程环境，非 null 则完全替换。 */
    static ProcResult runProcess(List<String> command, Path cwd, Map<String, String> env, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        if (env != null) {
            builder.environment().clear();
            builder.environment().putAll(env);
        }
        Process process = builder.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outReader = drain(process.getInputStream(), out);
        Thread errReader = drain(process.getErrorStream(), err);
        boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outReader.join(500);
            errReader.join(500);
            throw new ProcTimeoutException("timeout");
        }
        outReader.join(2000);
        errReader.join(2000);
        return new ProcResult(process.exitValue(), out.toString(), err.toString());
    }

    private static Thread drain(InputStream stream, StringBuilder target) {
        Thread thread = new Thread(() -> {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (target) {
                        target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时读流会抛异常
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 等价于 shutil.which。 */
    static Path which(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry, binary);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // PATH 里可能有非法路径
            }
        }
        return null;
    }

    static String sha256Hex(String raw) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                out.append(Character.forDigit((item >> 4) & 0xF, 16));
                out.append(Character.forDigit(item & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static volatile String cachedUid;
    private static volatile String cachedGid;

    /** JDK 没有 os.getuid()，只能问一次 id 命令并缓存。 */
    private static String currentUid() {
        if (cachedUid == null) {
            cachedUid = idValue("-u");
        }
        return cachedUid;
    }

    private static String currentGid() {
        if (cachedGid == null) {
            cachedGid = idValue("-g");
        }
        return cachedGid;
    }

    private static String idValue(String flag) {
        try {
            ProcResult result = runProcess(Arrays.asList("id", flag), null, null, 5);
            String text = result.stdout().strip();
            if (result.exitCode() == 0 && text.matches("\\d+")) {
                return text;
            }
        } catch (Exception ignored) {
            // 非 POSIX 平台读不到
        }
        return "0";
    }
}
