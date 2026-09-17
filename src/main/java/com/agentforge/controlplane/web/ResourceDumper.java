package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.agent.ToolRuntime;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Dataset;
import com.agentforge.controlplane.domain.EvaluationRun;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.runtime.McpStreamClient;
import com.agentforge.controlplane.runtime.OpenCliRuntime;
import com.agentforge.controlplane.runtime.SandboxRuntime;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把实体转成前端认识的下划线 JSON，并补上 Python dump() 里那些派生字段。 */
@Component
public class ResourceDumper {

    private static final Map<Class<?>, ResourceKind> KINDS = Map.ofEntries(
            Map.entry(Agent.class, ResourceKind.AGENT),
            Map.entry(ModelConfig.class, ResourceKind.CREDENTIAL),
            Map.entry(McpServer.class, ResourceKind.MCP),
            Map.entry(Skill.class, ResourceKind.SKILL),
            Map.entry(com.agentforge.controlplane.domain.Workflow.class, ResourceKind.WORKFLOW),
            Map.entry(SandboxPolicy.class, ResourceKind.SANDBOX),
            Map.entry(Dataset.class, ResourceKind.DATASET),
            Map.entry(EvaluationRun.class, ResourceKind.EVALUATION),
            Map.entry(Experiment.class, ResourceKind.EXPERIMENT),
            Map.entry(com.agentforge.controlplane.domain.Conversation.class, ResourceKind.SESSION),
            Map.entry(Trace.class, ResourceKind.TRACE),
            Map.entry(com.agentforge.controlplane.domain.Role.class, ResourceKind.ROLE));

    private final ResourceAccessService access;
    private final ToolRuntime tools;
    private final SandboxRuntime sandbox;

    public ResourceDumper(ResourceAccessService access, ToolRuntime tools, SandboxRuntime sandbox) {
        this.access = access;
        this.tools = tools;
        this.sandbox = sandbox;
    }

    public Map<String, Object> dump(Object row) {
        return dump(row, CurrentUserHolder.get());
    }

    public Map<String, Object> dump(Object row, CurrentUser user) {
        Map<String, Object> data = beanToSnake(row);
        if (row instanceof ModelConfig model) {
            String secret = model.getApiKey();
            data.put("has_api_key", secret != null && !secret.isBlank());
            data.put("has_credential", AgentScopeRuntime.modelHasCredential(model));
            data.put("api_key", maskSecret(secret));
            String ref = model.getApiKeyRef();
            if (ref != null && ref.startsWith("sk-")) {
                data.put("api_key_ref", "");
            }
            if (user == null || !access.canViewSecret(user, model)) {
                data.put("api_key", maskSecret(secret));
            }
        }
        if (row instanceof McpServer mcp) {
            List<Map<String, Object>> mcpTools = tools.listMcpTools(mcp);
            data.put("tools", mcpTools);
            data.put("tools_count", mcpTools.isEmpty() ? mcp.getToolsCount() : mcpTools.size());
            data.put("runnable", ToolRuntime.isBuiltinMcp(mcp)
                    || McpStreamClient.isOpencliTransport(mcp.getTransport())
                    || (McpStreamClient.isHttpStreamTransport(mcp.getTransport()) && !mcpTools.isEmpty()));
            data.put("transport_label", McpStreamClient.transportLabel(mcp.getTransport()));
            data.put("config", McpStreamClient.publicMcpConfig(mcp.getConfig()));
            if (McpStreamClient.isOpencliTransport(mcp.getTransport())) {
                Map<String, Object> cfg = mcp.getConfig() == null ? Map.of() : mcp.getConfig();
                data.put("kind", cfg.getOrDefault("kind", "cdp"));
                data.put("kind_label", OpenCliRuntime.kindLabel(Jsons.text(cfg.get("kind"))));
                data.put("target", cfg.getOrDefault("target", ""));
                data.put("session", cfg.getOrDefault("session", "agentforge"));
                data.put("command", cfg.getOrDefault("command", ""));
            }
        }
        if (row instanceof Skill skill) {
            String instruction = tools.skillInstruction(skill);
            data.put("instruction", instruction);
            data.put("has_instruction", !instruction.isBlank());
        }
        if (row instanceof Agent agent) {
            data.put("skill_ids", agent.getSkillIds());
            data.put("mcp_ids", agent.getMcpIds());
            data.put("opencli_ids", agent.getOpencliIds());
            data.put("system_prompt", agent.getSystemPrompt());
            List<Map<String, Object>> boundSkills = new ArrayList<>();
            for (Skill skill : tools.selectedSkills(agent)) {
                boundSkills.add(Map.of("id", skill.getId(), "name", skill.getName()));
            }
            List<Map<String, Object>> boundMcps = new ArrayList<>();
            for (McpServer mcp : tools.selectedMcps(agent)) {
                boundMcps.add(Map.of("id", mcp.getId(), "name", mcp.getName(), "tools", tools.listMcpTools(mcp)));
            }
            data.put("bound_skills", boundSkills);
            data.put("bound_mcps", boundMcps);
            data.put("workspace", agent.getWorkspace() == null ? "" : agent.getWorkspace());
            SandboxPolicy box = tools.selectedSandbox(agent);
            data.put("sandbox_name", box == null ? "" : box.getName());
        }
        if (row instanceof SandboxPolicy box) {
            data.put("backend", sandbox.preferredBackend(box));
            data.put("available_backends", sandbox.detectBackends());
            data.put("runnable", sandbox.backendReady(box));
        }
        if (row instanceof Trace trace) {
            data.put("spans", trace.getSpans() == null ? List.of() : trace.getSpans());
            data.put("langfuse_url", trace.getLangfuseUrl() == null ? "" : trace.getLangfuseUrl());
        }
        if (row instanceof Dataset dataset) {
            data.put("description", dataset.getDescription() == null ? "" : dataset.getDescription());
            data.put("source_name", dataset.getSourceName() == null ? "" : dataset.getSourceName());
        }
        return attachAccess(data, row, user);
    }

    public List<Map<String, Object>> dumpAll(List<?> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            out.add(dump(row));
        }
        return out;
    }

    public static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "••••••••";
        }
        return value.substring(0, 3) + "••••" + value.substring(value.length() - 4);
    }

    private Map<String, Object> attachAccess(Map<String, Object> data, Object row, CurrentUser user) {
        if (user == null) {
            return data;
        }
        ResourceKind kind = KINDS.get(row.getClass());
        if (kind != null) {
            data.put("editable", access.canEdit(user, kind, row));
        }
        data.put("tenant_id", getter(row, "getTenantId", user.getTenantId()));
        data.put("owner_id", getter(row, "getOwnerId", null));
        return data;
    }

    private static Object getter(Object row, String name, Object fallback) {
        try {
            Object value = row.getClass().getMethod(name).invoke(row);
            return value == null ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }

    static Map<String, Object> beanToSnake(Object row) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Method method : row.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName();
            String field;
            if (name.startsWith("get") && name.length() > 3) {
                field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                field = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            } else {
                continue;
            }
            if (field.equals("class")) {
                continue;
            }
            Object value;
            try {
                value = method.invoke(row);
            } catch (Exception e) {
                continue;
            }
            if (value instanceof Instant instant) {
                value = Jsons.iso(instant);
            }
            data.put(camelToSnake(field), value);
        }
        return data;
    }

    static String camelToSnake(String field) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char ch = field.charAt(i);
            if (Character.isUpperCase(ch)) {
                out.append('_').append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
