package com.agentforge.controlplane.runtime;

import com.agentforge.controlplane.domain.Agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * 一次 Agent 执行的可信身份，对应 Python 版 app/services/execution_context.py。
 *
 * 这些字段只能由服务端构造，绝不能来自模型的工具参数。
 */
public final class ExecutionContext {

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    private final long tenantId;
    private final long agentId;
    private final String executionId;

    public ExecutionContext(long tenantId, long agentId, String executionId) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.executionId = executionId == null ? "" : executionId;
    }

    public static ExecutionContext forAgent(Agent agent) {
        return forAgent(agent, "");
    }

    public static ExecutionContext forAgent(Agent agent, String executionId) {
        long tenantId = agent == null || agent.getTenantId() == null ? 0L : agent.getTenantId();
        long agentId = agent == null || agent.getId() == null ? 0L : agent.getId();
        String raw = executionId == null || executionId.isEmpty() ? "one-shot" : executionId;
        return new ExecutionContext(tenantId, agentId, raw.length() > 160 ? raw.substring(0, 160) : raw);
    }

    public long getTenantId() {
        return tenantId;
    }

    public long getAgentId() {
        return agentId;
    }

    public String getExecutionId() {
        return executionId;
    }

    /** 浏览器、沙箱按这个 key 做隔离；格式必须与 Python 版完全一致。 */
    public String scopeKey() {
        String raw = tenantId + ":" + agentId + ":" + executionId;
        return "tenant-" + tenantId + "-agent-" + agentId + "-" + sha256Hex(raw).substring(0, 24);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                out.append(Character.forDigit((item >> 4) & 0xF, 16));
                out.append(Character.forDigit(item & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- ThreadLocal 版的 contextvar ---

    public static void set(ExecutionContext context) {
        CURRENT.set(context);
    }

    public static ExecutionContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 对应 Python 版 generate_chat_reply 里的 set/close_browser/reset 三段式：
     * 执行期间挂上上下文，结束时先关掉该 scope 的浏览器再恢复原上下文。
     */
    public static <T> T runScoped(ExecutionContext context, BrowserRuntime browser, Supplier<T> body) {
        ExecutionContext previous = CURRENT.get();
        set(context);
        try {
            return body.get();
        } finally {
            closeBrowserOnExit(context, browser);
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }

    /** 单独暴露出来，方便调用方用自己的 try/finally 结构。 */
    public static void closeBrowserOnExit(ExecutionContext context, BrowserRuntime browser) {
        if (context == null || browser == null) {
            return;
        }
        try {
            browser.closeBrowser(context.scopeKey());
        } catch (Exception ignored) {
            // 清理失败不影响主流程
        }
    }

    @Override
    public String toString() {
        return "ExecutionContext[tenantId=" + tenantId + ", agentId=" + agentId + ", executionId=" + executionId + "]";
    }
}
