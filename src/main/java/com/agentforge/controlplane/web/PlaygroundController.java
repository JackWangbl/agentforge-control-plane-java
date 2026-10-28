package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.agent.HttpAgentRuntime;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.dto.ApiDtos;
import com.agentforge.controlplane.experiment.ExperimentService;
import com.agentforge.controlplane.memory.ContextCompressionService;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.memory.MemorySummarizer;
import com.agentforge.controlplane.playground.PlaygroundService;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import com.agentforge.controlplane.recovery.ExecutionRecorder;
import com.agentforge.controlplane.recovery.CheckpointService;
import com.agentforge.controlplane.recovery.RecoveryCoordinator;
import com.agentforge.controlplane.recovery.RecoveryTrace;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class PlaygroundController {

    private final ResourceAccessService access;
    private final PlaygroundService playground;
    private final WorkspaceStore workspaces;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ExperimentService experiments;
    private final HttpAgentRuntime httpAgents;
    private final ModelConfigRepository models;
    private final MemoryService memories;
    private final MemorySummarizer summarizer;
    private final ContextCompressionService compression;
    private final ExecutionRecorder executionRecorder;
    private final CheckpointService checkpointService;
    private final RecoveryCoordinator recoveryCoordinator;

    public PlaygroundController(ResourceAccessService access, PlaygroundService playground, WorkspaceStore workspaces,
                                ConversationRepository conversations, ChatMessageRepository messages,
                                ExperimentService experiments, HttpAgentRuntime httpAgents,
                                ModelConfigRepository models, MemoryService memories, MemorySummarizer summarizer) {
        this(access, playground, workspaces, conversations, messages, experiments, httpAgents, models, memories,
                summarizer, null, null, null, null);
    }

    @Autowired
    public PlaygroundController(ResourceAccessService access, PlaygroundService playground, WorkspaceStore workspaces,
                                ConversationRepository conversations, ChatMessageRepository messages,
                                ExperimentService experiments, HttpAgentRuntime httpAgents,
                                ModelConfigRepository models, MemoryService memories, MemorySummarizer summarizer,
                                ExecutionRecorder executionRecorder, CheckpointService checkpointService,
                                RecoveryCoordinator recoveryCoordinator, ContextCompressionService compression) {
        this.access = access;
        this.playground = playground;
        this.workspaces = workspaces;
        this.conversations = conversations;
        this.messages = messages;
        this.experiments = experiments;
        this.httpAgents = httpAgents;
        this.models = models;
        this.memories = memories;
        this.summarizer = summarizer;
        this.executionRecorder = executionRecorder;
        this.checkpointService = checkpointService;
        this.recoveryCoordinator = recoveryCoordinator;
        this.compression = compression;
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping("/api/playground/run")
    public Map<String, Object> run(CurrentUser user, @Valid @RequestBody ApiDtos.PlaygroundRun payload) {
        Experiment experiment = null;
        Map<String, Object> assignment = Map.of();
        Long agentId = payload.agent_id();
        String sessionId = payload.session_id();
        if (payload.experiment_id() != null) {
            experiment = access.getRow(user, ResourceKind.EXPERIMENT, payload.experiment_id());
            if (!"running".equals(experiment.getStatus())) {
                throw ApiException.conflict("只有进行中的实验才会分流，请先启动实验");
            }
            sessionId = sessionId == null || sessionId.isBlank() ? "debug_" + shortId() : sessionId;
            assignment = experiments.assignUnit(experiment, sessionId,
                    (payload.user_key() == null || payload.user_key().isBlank() ? user.getUsername() : payload.user_key()),
                    user);
            if (!Boolean.TRUE.equals(assignment.get("holdout")) && assignment.get("agent_id") != null) {
                agentId = ((Number) assignment.get("agent_id")).longValue();
            }
        }
        Agent agent = access.getRow(user, ResourceKind.AGENT, agentId);
        ModelConfig model = resolvePlaygroundModel(user, agent);
        workspaces.ensureWorkspace(agent);
        sessionId = sessionId == null || sessionId.isBlank() ? "debug_" + shortId() : sessionId;
        Conversation conversation = conversations.findBySessionId(sessionId).orElse(null);
        if (conversation != null && conversation.getTenantId() != null
                && !conversation.getTenantId().equals(user.getTenantId())) {
            throw ApiException.conflict("Session belongs to another tenant");
        }
        if (conversation != null && conversation.getAgentId() != null && !conversation.getAgentId().equals(agent.getId())) {
            if (experiment != null) {
                sessionId = "debug_" + shortId();
                conversation = null;
                assignment = experiments.assignUnit(experiment, sessionId,
                        (payload.user_key() == null || payload.user_key().isBlank() ? user.getUsername() : payload.user_key()),
                        user);
            } else {
                throw ApiException.conflict("Session belongs to another agent workspace");
            }
        }
        Map<String, Object> stored = workspaces.loadSession(agent, sessionId);
        if (stored != null && stored.get("agent_id") != null
                && !agent.getId().equals(Jsons.asLong(stored.get("agent_id")))) {
            if (experiment != null) {
                sessionId = "debug_" + shortId();
                stored = null;
                conversation = null;
                assignment = experiments.assignUnit(experiment, sessionId,
                        (payload.user_key() == null || payload.user_key().isBlank() ? user.getUsername() : payload.user_key()),
                        user);
            } else {
                throw ApiException.conflict("Session belongs to another agent workspace");
            }
        }
        if (conversation != null && !memories.canRead(user, conversation)) {
            throw ApiException.conflict("这个会话属于其他人");
        }
        final String sid = sessionId;
        List<Map<String, Object>> history = compression == null
                ? memories.shortTermHistory(user, agent.getId(), sid)
                : compression.assemble(user, agent, model, sid, payload.message());
        history.add(Map.of("role", "user", "content", payload.message()));
        Instant started = Instant.now();
        ExecutionRecord execution = beginExecution(user, agent, null, ExecutionRecord.EntryPoint.PLAYGROUND,
                sid, Jsons.ordered("message", payload.message(), "session_id", sid, "agent_id", agent.getId()));
        try {
            ChatReply reply = execution == null
                    ? playground.generate(agent, model, history, sid, false, false)
                    : RecoveryTrace.call(new RecoveryTrace.Binding(execution.getTenantId(), execution.getId(), null),
                    () -> playground.generate(agent, model, history, sid, false, false));
            Map<String, Object> result = playground.finalizeTurn(user, agent, model, sid, conversation, payload.message(), reply,
                    started, true, "POST /api/playground/run", assignment, experiment);
            summarizer.summarize(user, model, sid, payload.message(), reply.reply(), reply.mode());
            finishExecution(execution, agent, sid, reply);
            return result;
        } catch (RuntimeException error) {
            failExecution(execution, agent, sid, error);
            throw error;
        }
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping("/api/playground/resume")
    public Map<String, Object> resume(CurrentUser user, @Valid @RequestBody ApiDtos.PlaygroundResume payload) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, payload.agent_id());
        resolvePlaygroundModel(user, agent);
        workspaces.ensureWorkspace(agent);
        String sessionId = payload.session_id();
        Map<String, Object> ckpt = workspaces.loadCheckpoint(agent, sessionId);
        if (ckpt == null || !List.of("failed", "running").contains(Jsons.text(ckpt.get("status")))) {
            throw ApiException.conflict("没有可恢复的检查点");
        }
        Conversation conversation = conversations.findBySessionId(sessionId).orElse(null);
        if (conversation != null && conversation.getTenantId() != null
                && !conversation.getTenantId().equals(user.getTenantId())) {
            throw ApiException.conflict("Session belongs to another tenant");
        }
        if (conversation != null && conversation.getAgentId() != null && !conversation.getAgentId().equals(agent.getId())) {
            throw ApiException.conflict("Session belongs to another agent workspace");
        }
        Map<String, Object> stored = workspaces.loadSession(agent, sessionId);
        if (stored != null && stored.get("agent_id") != null
                && !agent.getId().equals(Jsons.asLong(stored.get("agent_id")))) {
            throw ApiException.conflict("Session belongs to another agent workspace");
        }
        if (conversation != null && !memories.canRead(user, conversation)) {
            throw ApiException.conflict("这个会话属于其他人");
        }
        if (recoveryCoordinator == null) {
            throw ApiException.conflict("没有可恢复的 Failure Case");
        }
        RecoveryCoordinator.Submission submission = recoveryCoordinator.submitForSession(user, agent.getId(), sessionId);
        if (!submission.completed()) {
            return Jsons.ordered(
                    "mode", "queued",
                    "reply", "重拾已转到后台继续执行",
                    "response", "重拾已转到后台继续执行",
                    "output", "重拾已转到后台继续执行",
                    "session_id", sessionId,
                    "agent", agent.getName(),
                    "agent_id", agent.getId(),
                    "task_id", submission.response().task_id(),
                    "poll_url", submission.response().poll_url(),
                    "checkpoint", workspaces.checkpointPublic(workspaces.loadCheckpoint(agent, sessionId)));
        }
        if (!"SUCCEEDED".equals(submission.response().status())) {
            throw ApiException.conflict("续跑未能完成：" + submission.response().status());
        }
        stored = workspaces.loadSession(agent, sessionId);
        String reply = lastAssistant(stored);
        return Jsons.ordered(
                "mode", "ready",
                "reply", reply,
                "response", reply,
                "output", reply,
                "session_id", sessionId,
                "agent", agent.getName(),
                "agent_id", agent.getId(),
                "workspace", agent.getWorkspace(),
                "messages", stored == null ? List.of() : stored.get("messages"),
                "spans", List.of(),
                "checkpoint", null);
    }

    private static String lastAssistant(Map<String, Object> stored) {
        String reply = "";
        for (Map<String, Object> item : Jsons.mapList(stored == null ? null : stored.get("messages"))) {
            if ("assistant".equals(Jsons.text(item.get("role")))) {
                reply = Jsons.text(item.get("content"));
            }
        }
        return reply.isBlank() ? "重拾已完成" : reply;
    }

    @RequirePermission({"session:read", "agent:write"})
    @GetMapping("/api/playground/sessions/{sessionId}")
    public Map<String, Object> session(CurrentUser user, @PathVariable String sessionId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChatMessage row : messages.findBySessionIdAndTenantIdOrderByIdAsc(sessionId, user.getTenantId())) {
            items.add(Jsons.ordered("id", row.getId(), "role", row.getRole(), "content", row.getContent(),
                    "agent_name", row.getAgentName(), "created_at", Jsons.iso(row.getCreatedAt())));
        }
        return Jsons.ordered("session_id", sessionId, "messages", items);
    }

    private ModelConfig resolvePlaygroundModel(CurrentUser user, Agent agent) {
        if (httpAgents.isHttpBacked(agent)) {
            HttpAgent http = httpAgents.primary(agent);
            return HttpAgentRuntime.displayModel(http);
        }
        String name = agent.getModelName() == null ? "" : agent.getModelName().strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("这个 Agent 还没有绑定模型");
        }
        ModelConfig model = models.findByName(name)
                .filter(row -> row.getTenantId() == null || row.getTenantId().equals(user.getTenantId()))
                .orElseThrow(() -> ApiException.badRequest("Agent 绑定的模型不存在：" + name));
        if (!model.isEnabled()) {
            throw ApiException.conflict("Agent 绑定的模型已停用：" + name);
        }
        return model;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private ExecutionRecord beginExecution(CurrentUser user, Agent agent, Long workflowId,
                                            ExecutionRecord.EntryPoint entryPoint, String sessionId,
                                            Map<String, Object> request) {
        if (executionRecorder == null) return null;
        return executionRecorder.start(user, agent, workflowId, entryPoint, sessionId, request,
                ExecutionRecorder.agentConfig(agent), ExecutionRecorder.credentialRefs(agent));
    }

    private void finishExecution(ExecutionRecord execution, Agent agent, String sessionId, ChatReply reply) {
        if (execution == null) return;
        if ("error".equals(reply.mode())) {
            failExecution(execution, agent, sessionId, new IllegalStateException("Agent 执行失败"));
        } else {
            executionRecorder.succeed(execution.getId(), reply.traceId());
        }
    }

    private void failExecution(ExecutionRecord execution, Agent agent, String sessionId, RuntimeException error) {
        if (execution == null) return;
        Map<String, Object> state = workspaces.loadCheckpoint(agent, sessionId);
        String step = state == null ? "execution.start" : Jsons.text(state.get("next"));
        if (state != null && checkpointService != null
                && checkpointService.history(execution.getTenantId(), execution.getId()).isEmpty()) {
            boolean hasPending = !Jsons.mapList(state.get("pending_tools")).isEmpty();
            checkpointService.append(execution.getTenantId(), execution.getId(), null,
                    step.isBlank() ? "llm" : step, step, ExecutionCheckpoint.Outcome.FAILED, !hasPending,
                    hasPending ? ExecutionCheckpoint.SideEffectSemantics.UNKNOWN : ExecutionCheckpoint.SideEffectSemantics.NONE,
                    null, state, "EXECUTION_FAILED", "执行失败");
        }
        executionRecorder.fail(execution.getId(), null, step.isBlank() ? "execution.start" : step, error);
    }
}
