package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.runtime.BrowserRuntime;
import com.agentforge.controlplane.runtime.ExecutionContext;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本平台 Agent 自己的对外对话接口。其他平台若要调我们，走 /api/agents/{id}/invoke。
 * 绑定外部平台 Agent 不在这里，见 {@link HttpAgentRuntime}。
 */
@Service
public class BoundAgentRuntime {

    private final ModelConfigRepository models;
    private final ObjectProvider<AgentScopeRuntime> runtime;
    private final BrowserRuntime browser;
    private final WorkspaceStore workspaces;
    private final HttpAgentRuntime httpAgents;

    public BoundAgentRuntime(ModelConfigRepository models, ObjectProvider<AgentScopeRuntime> runtime,
                             BrowserRuntime browser, WorkspaceStore workspaces, HttpAgentRuntime httpAgents) {
        this.models = models;
        this.runtime = runtime;
        this.browser = browser;
        this.workspaces = workspaces;
        this.httpAgents = httpAgents;
    }

    /** 调试台 / 对外接口共用。HTTP 接入的 Agent 直接打到对方平台。 */
    public ChatReply invoke(Agent target, String message, String sessionId) {
        if (target == null) {
            throw ApiException.unprocessable("Agent 不存在");
        }
        String sid = sessionId == null || sessionId.isBlank()
                ? "invoke_" + WorkspaceStore.newTraceId()
                : sessionId.strip();
        if (httpAgents.isHttpBacked(target)) {
            ChatReply reply = httpAgents.chat(target, message, sid);
            HttpAgent http = httpAgents.primary(target);
            workspaces.persistRun(target, sid, message, message, reply.reply(), reply.mode(),
                    http == null ? "HTTP" : http.getName(), reply.traceId(), reply.spans(), reply.usage(), 1, true);
            return reply;
        }
        ModelConfig model = resolveModel(target);
        if (model == null) {
            throw ApiException.unprocessable(target.getName() + " 没有可用的模型配置");
        }
        List<Map<String, Object>> history = historyFrom(workspaces.loadSession(target, sid));
        history.add(Map.of("role", "user", "content", message));
        ExecutionContext nested = ExecutionContext.forAgent(target, sid + "-" + WorkspaceStore.newTraceId());
        ChatReply reply = ExecutionContext.runScoped(nested, browser,
                () -> runtime.getObject().generate(target, model, history, sid, false, false));
        workspaces.persistRun(target, sid, message, message, reply.reply(), reply.mode(),
                model.getName(), reply.traceId(), reply.spans(), reply.usage(), 1, true);
        return reply;
    }

    public Map<String, Object> interfaceCard(Agent agent) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", Map.of("type", "string", "required", true, "description", "发给该 Agent 的完整问题或任务"));
        input.put("session_id", Map.of("type", "string", "required", false, "description", "可选会话 id，传入则延续上下文"));
        return Jsons.ordered(
                "id", agent.getId(),
                "name", agent.getName(),
                "description", agent.getDescription() == null ? "" : agent.getDescription(),
                "protocol", "agentforge.invoke.v1",
                "method", "POST",
                "path", "/api/agents/" + agent.getId() + "/invoke",
                "input", input,
                "output", Map.of(
                        "reply", "string",
                        "mode", "ready | preview | error",
                        "trace_id", "string",
                        "session_id", "string"));
    }

    private ModelConfig resolveModel(Agent agent) {
        if (agent == null || agent.getModelName() == null || agent.getModelName().isBlank()) {
            return null;
        }
        return models.findByName(agent.getModelName())
                .filter(ModelConfig::isEnabled)
                .orElseGet(() -> {
                    List<ModelConfig> enabled = models.findByTenantIdAndEnabledTrue(agent.getTenantId());
                    return enabled.isEmpty() ? null : enabled.get(0);
                });
    }

    private static List<Map<String, Object>> historyFrom(Map<String, Object> stored) {
        List<Map<String, Object>> history = new ArrayList<>();
        if (stored == null) {
            return history;
        }
        for (Map<String, Object> item : Jsons.mapList(stored.get("messages"))) {
            String role = Jsons.text(item.get("role"));
            if ("user".equals(role) || "assistant".equals(role)) {
                history.add(Map.of("role", role, "content", Jsons.text(item.get("content"))));
            }
        }
        return history;
    }
}
