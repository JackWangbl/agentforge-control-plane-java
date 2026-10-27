package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.AgentRelease;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.memory.MemorySummarizer;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.runtime.BrowserRuntime;
import com.agentforge.controlplane.runtime.ExecutionContext;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import com.agentforge.controlplane.recovery.ExecutionRecorder;
import com.agentforge.controlplane.recovery.CheckpointService;
import com.agentforge.controlplane.recovery.RecoveryTrace;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
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
    private final MemoryService memories;
    private final MemorySummarizer summarizer;
    private final AgentReleaseService releases;
    private final ExecutionRecorder executionRecorder;
    private final CheckpointService checkpointService;

    public BoundAgentRuntime(ModelConfigRepository models, ObjectProvider<AgentScopeRuntime> runtime,
                             BrowserRuntime browser, WorkspaceStore workspaces, HttpAgentRuntime httpAgents,
                             MemoryService memories, MemorySummarizer summarizer, AgentReleaseService releases,
                             ExecutionRecorder executionRecorder, CheckpointService checkpointService) {
        this.models = models;
        this.runtime = runtime;
        this.browser = browser;
        this.workspaces = workspaces;
        this.httpAgents = httpAgents;
        this.memories = memories;
        this.summarizer = summarizer;
        this.releases = releases;
        this.executionRecorder = executionRecorder;
        this.checkpointService = checkpointService;
    }

    /** 调试台 / 对外接口共用。HTTP 接入的 Agent 直接打到对方平台。 */
    public ChatReply invoke(Agent target, String message, String sessionId) {
        return invoke(target, message, sessionId, true, true);
    }

    /**
     * @param pinRelease    为 true 时使用已发布版本；调试子智能体时为 false，仍读当前草稿
     * @param recordExchange 为 true 时写入会话记忆。编排里的 A2A 跳转只取这一轮回复，不另记一条会话
     */
    public ChatReply invoke(Agent target, String message, String sessionId, boolean pinRelease, boolean recordExchange) {
        return invoke(target, message, sessionId, pinRelease, recordExchange, ExecutionRecord.EntryPoint.AGENT_INVOKE);
    }

    public ChatReply invoke(Agent target, String message, String sessionId, boolean pinRelease, boolean recordExchange,
                            ExecutionRecord.EntryPoint entryPoint) {
        CurrentUser caller = CurrentUserHolder.get();
        String sid = sessionId == null || sessionId.isBlank()
                ? "invoke_" + WorkspaceStore.newTraceId() : sessionId.strip();
        ExecutionRecord execution = null;
        if (recordExchange && caller != null && target != null) {
            execution = executionRecorder.start(caller, target, null, entryPoint, sid,
                    Jsons.ordered("message", message, "session_id", sid, "agent_id", target.getId()),
                    ExecutionRecorder.agentConfig(target), ExecutionRecorder.credentialRefs(target));
        }
        try {
            ChatReply reply = execution == null
                    ? invokeUntracked(target, message, sid, pinRelease, recordExchange)
                    : RecoveryTrace.call(new RecoveryTrace.Binding(execution.getTenantId(), execution.getId(), null),
                    () -> invokeUntracked(target, message, sid, pinRelease, recordExchange));
            if (execution != null) {
                if ("error".equals(reply.mode())) {
                    recordFailure(execution, target, sid, new IllegalStateException("Agent 执行失败"));
                } else {
                    executionRecorder.succeed(execution.getId(), reply.traceId());
                }
            }
            return reply;
        } catch (RuntimeException error) {
            if (execution != null) recordFailure(execution, target, sid, error);
            throw error;
        }
    }

    private ChatReply invokeUntracked(Agent target, String message, String sessionId, boolean pinRelease, boolean recordExchange) {
        if (target == null) {
            throw ApiException.unprocessable("Agent 不存在");
        }
        String sid = sessionId == null || sessionId.isBlank()
                ? "invoke_" + WorkspaceStore.newTraceId()
                : sessionId.strip();
        if (httpAgents.isHttpBacked(target)) {
            ChatReply reply = httpAgents.chat(target, message, sid);
            if (recordExchange) {
                HttpAgent http = httpAgents.primary(target);
                workspaces.persistRun(target, sid, message, message, reply.reply(), reply.mode(),
                        http == null ? "HTTP" : http.getName(), reply.traceId(), reply.spans(), reply.usage(), 1, true);
            }
            return reply;
        }
        AgentRelease release = pinRelease ? releases.ensurePublished(target) : null;
        ModelConfig model = release == null ? resolveModel(target) : releases.resolveModel(release, resolveModel(target));
        if (model == null) {
            throw ApiException.unprocessable(target.getName() + " 没有可用的模型配置");
        }
        CurrentUser caller = CurrentUserHolder.get();
        if (recordExchange && caller != null) {
            memories.assertReadable(caller, sid);
        }
        List<Map<String, Object>> history;
        if (!recordExchange) {
            history = new ArrayList<>();
        } else if (caller == null) {
            history = historyFrom(workspaces.loadSession(target, sid));
        } else {
            history = memories.shortTermHistory(caller, target.getId(), sid);
        }
        history.add(Map.of("role", "user", "content", message == null ? "" : message));
        ExecutionContext nested = ExecutionContext.forAgent(target, sid + "-" + WorkspaceStore.newTraceId());
        ChatReply reply = ExecutionContext.runScoped(nested, browser, () -> {
            if (release == null) {
                return runtime.getObject().generate(target, model, history, sid, false, false);
            }
            return ReleaseContext.call(release, () -> runtime.getObject().generate(target, model, history, sid, false, false));
        });
        if (recordExchange && caller != null) {
            memories.recordExchange(caller, target, sid, message, reply.reply());
            summarizer.summarize(caller, model, sid, message, reply.reply(), reply.mode());
        }
        if (recordExchange) {
            workspaces.persistRun(target, sid, message, message, reply.reply(), reply.mode(),
                    model.getName(), reply.traceId(), reply.spans(), reply.usage(), 1, true);
        }
        return reply;
    }

    private void recordFailure(ExecutionRecord execution, Agent agent, String sessionId, RuntimeException error) {
        Map<String, Object> state = workspaces.loadCheckpoint(agent, sessionId);
        String step = state == null ? "execution.start" : Jsons.text(state.get("next"));
        if (state != null && checkpointService.history(execution.getTenantId(), execution.getId()).isEmpty()) {
            boolean pending = !Jsons.mapList(state.get("pending_tools")).isEmpty();
            checkpointService.append(execution.getTenantId(), execution.getId(), null,
                    step.isBlank() ? "llm" : step, step, ExecutionCheckpoint.Outcome.FAILED, !pending,
                    pending ? ExecutionCheckpoint.SideEffectSemantics.UNKNOWN : ExecutionCheckpoint.SideEffectSemantics.NONE,
                    null, state, "EXECUTION_FAILED", "执行失败");
        }
        executionRecorder.fail(execution.getId(), null, step.isBlank() ? "execution.start" : step, error);
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
                "a2a", Map.of(
                        "protocol", "JSONRPC",
                        "card", A2aAddresses.cardPath(agent.getId()),
                        "endpoint", A2aAddresses.rpcPath(agent.getId())),
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
