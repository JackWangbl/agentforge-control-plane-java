package com.agentforge.controlplane.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 权限字符串、资源种类映射和前端设置页用的权限目录，逐条对齐 Python 版 app/access/kinds.py。 */
public final class Permissions {

    private Permissions() {}

    public static final Map<ResourceKind, String> READ = Map.ofEntries(
            Map.entry(ResourceKind.AGENT, "agent:read"),
            Map.entry(ResourceKind.HTTP_AGENT, "agent:read"),
            Map.entry(ResourceKind.CREDENTIAL, "model:read"),
            Map.entry(ResourceKind.MCP, "mcp:read"),
            Map.entry(ResourceKind.OPENCLI, "opencli:read"),
            Map.entry(ResourceKind.SKILL, "skill:read"),
            Map.entry(ResourceKind.KNOWLEDGE, "knowledge:read"),
            Map.entry(ResourceKind.WORKFLOW, "workflow:read"),
            Map.entry(ResourceKind.SANDBOX, "sandbox:read"),
            Map.entry(ResourceKind.DATASET, "eval:read"),
            Map.entry(ResourceKind.EVALUATION, "eval:read"),
            Map.entry(ResourceKind.SESSION, "session:read"),
            Map.entry(ResourceKind.TRACE, "trace:read"),
            Map.entry(ResourceKind.ROLE, "role:read"),
            Map.entry(ResourceKind.USER, "user:read"),
            Map.entry(ResourceKind.EXPERIMENT, "experiment:read"));

    public static final Map<ResourceKind, String> WRITE = Map.ofEntries(
            Map.entry(ResourceKind.AGENT, "agent:write"),
            Map.entry(ResourceKind.HTTP_AGENT, "agent:write"),
            Map.entry(ResourceKind.CREDENTIAL, "model:write"),
            Map.entry(ResourceKind.MCP, "mcp:write"),
            Map.entry(ResourceKind.OPENCLI, "opencli:write"),
            Map.entry(ResourceKind.SKILL, "skill:write"),
            Map.entry(ResourceKind.KNOWLEDGE, "knowledge:write"),
            Map.entry(ResourceKind.WORKFLOW, "workflow:write"),
            Map.entry(ResourceKind.SANDBOX, "sandbox:write"),
            // 数据集和评测的写权限是 eval:run，不是 eval:write
            Map.entry(ResourceKind.DATASET, "eval:run"),
            Map.entry(ResourceKind.EVALUATION, "eval:run"),
            Map.entry(ResourceKind.SESSION, "session:write"),
            // 链路没有独立写权限，读写都用 trace:read
            Map.entry(ResourceKind.TRACE, "trace:read"),
            Map.entry(ResourceKind.ROLE, "role:write"),
            Map.entry(ResourceKind.USER, "user:write"),
            Map.entry(ResourceKind.EXPERIMENT, "experiment:write"));

    /** URL 里的资源段 -> 资源种类。 */
    public static final Map<String, ResourceKind> ROUTE_KIND = Map.ofEntries(
            Map.entry("agents", ResourceKind.AGENT),
            Map.entry("http-agents", ResourceKind.HTTP_AGENT),
            Map.entry("models", ResourceKind.CREDENTIAL),
            Map.entry("mcp", ResourceKind.MCP),
            Map.entry("skills", ResourceKind.SKILL),
            Map.entry("workflows", ResourceKind.WORKFLOW),
            Map.entry("sandboxes", ResourceKind.SANDBOX),
            Map.entry("roles", ResourceKind.ROLE),
            Map.entry("traces", ResourceKind.TRACE),
            Map.entry("datasets", ResourceKind.DATASET),
            Map.entry("evaluations", ResourceKind.EVALUATION),
            Map.entry("experiments", ResourceKind.EXPERIMENT));

    public static final List<Map<String, String>> CATALOG = List.of(
            entry("*", "全部权限", "平台"),
            entry("platform:admin", "跨租户管理", "平台"),
            entry("tenant:admin", "租户管理", "租户"),
            entry("user:read", "查看用户", "租户"),
            entry("user:write", "管理用户", "租户"),
            entry("role:read", "查看角色", "租户"),
            entry("role:write", "管理角色", "租户"),
            entry("agent:read", "查看 Agent", "构建"),
            entry("agent:write", "编辑 Agent", "构建"),
            entry("mcp:read", "查看 MCP", "构建"),
            entry("mcp:write", "编辑 MCP", "构建"),
            entry("skill:read", "查看 Skill", "构建"),
            entry("skill:write", "编辑 Skill", "构建"),
            entry("knowledge:read", "查看知识库", "构建"),
            entry("knowledge:write", "编辑知识库", "构建"),
            entry("model:read", "查看模型", "构建"),
            entry("model:write", "编辑模型", "构建"),
            entry("workflow:read", "查看编排", "构建"),
            entry("workflow:write", "编辑编排", "构建"),
            entry("sandbox:read", "查看沙箱", "构建"),
            entry("sandbox:write", "编辑沙箱", "构建"),
            entry("session:read", "查看会话", "运行"),
            entry("session:write", "发起调试", "运行"),
            entry("trace:read", "查看链路", "运行"),
            entry("eval:read", "查看评测", "质量"),
            entry("eval:run", "执行评测", "质量"),
            entry("experiment:read", "查看实验", "质量"),
            entry("experiment:write", "管理实验", "质量"));

    private static Map<String, String> entry(String key, String label, String group) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("group", group);
        return item;
    }

    public static ResourceKind kindOf(String route) {
        ResourceKind kind = ROUTE_KIND.get(route);
        if (kind == null) {
            throw new IllegalArgumentException("未知资源类型：" + route);
        }
        return kind;
    }
}
