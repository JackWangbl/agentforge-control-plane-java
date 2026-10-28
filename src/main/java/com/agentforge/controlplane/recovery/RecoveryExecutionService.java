package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.RecoveryAttempt;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.playground.PlaygroundService;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.RoleRepository;
import com.agentforge.controlplane.repo.UserRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RecoveryExecutionService {
    public record Result(ExecutionRecord execution, ExecutionCheckpoint checkpoint) {}

    private final ExecutionRecordRepository executions;
    private final AgentRepository agents;
    private final ModelConfigRepository models;
    private final UserRepository users;
    private final RoleRepository roles;
    private final CheckpointService checkpoints;
    private final PlaygroundService playground;
    private final RecoverySessionService sessions;
    private final WorkspaceStore workspaces;
    private final ObjectMapper mapper;

    public RecoveryExecutionService(ExecutionRecordRepository executions, AgentRepository agents,
                                    ModelConfigRepository models, UserRepository users, RoleRepository roles,
                                    CheckpointService checkpoints, PlaygroundService playground,
                                    RecoverySessionService sessions, WorkspaceStore workspaces, ObjectMapper mapper) {
        this.executions = executions;
        this.agents = agents;
        this.models = models;
        this.users = users;
        this.roles = roles;
        this.checkpoints = checkpoints;
        this.playground = playground;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.mapper = mapper;
    }

    public Result resume(RecoveryAttempt attempt, ExecutionRecord original, ExecutionCheckpoint checkpoint) {
        CurrentUser user = currentUser(attempt);
        Agent live = agents.findById(original.getAgentId())
                .filter(agent -> original.getTenantId().equals(agent.getTenantId()))
                .orElseThrow(() -> new IllegalStateException("失败时 Agent 已不可用"));
        Map<String, Object> frozen = map(original.getConfigSnapshotJson());
        Agent agent = frozenAgent(live, frozen);
        String modelName = Jsons.text(frozen.get("model_name"));
        if (modelName.isBlank()) modelName = agent.getModelName();
        final String resolvedModelName = modelName;
        ModelConfig model = models.findByName(resolvedModelName)
                .filter(value -> value.isEnabled() && (value.getTenantId() == null || original.getTenantId().equals(value.getTenantId())))
                .orElseThrow(() -> new IllegalStateException("失败时模型配置或凭证引用不可用"));
        Map<String, Object> state = checkpoints.state(checkpoint);
        Map<String, Object> workspaceCheckpoint = new java.util.LinkedHashMap<>(state);
        workspaceCheckpoint.put("status", "failed");
        workspaceCheckpoint.putIfAbsent("next", checkpoint.getNextStepKey() == null ? "llm" : checkpoint.getNextStepKey());
        workspaceCheckpoint.putIfAbsent("step", checkpoint.getSequenceNo());
        workspaces.saveCheckpoint(agent, original.getSessionId(), workspaceCheckpoint);
        List<Map<String, Object>> history = new ArrayList<>(Jsons.mapList(state.get("working")));
        Instant started = Instant.now();
        Instant deadline = started.plusMillis(attempt.getOverallTimeoutMs());
        ExecutionRecord recovery = newRecoveryExecution(original);
        try {
            ChatReply reply = RecoveryTrace.call(
                    new RecoveryTrace.Binding(recovery.getTenantId(), recovery.getId(), attempt.getFailureCaseId()),
                    () -> CurrentUserHolder.call(user,
                            () -> playground.generate(agent, model, history, original.getSessionId(), true, false)));
            if (Instant.now().isAfter(deadline)) throw new RecoveryTimeoutException("整次重拾超时");
            if ("error".equals(reply.mode())) throw new IllegalStateException("Agent 执行失败");
            String userMessage = originalUserMessage(original, state);
            RecoverySessionService.Persisted persisted = CurrentUserHolder.call(user,
                    () -> sessions.persistSuccess(user, agent, model, original, reply, started, userMessage));
            recovery.setTraceId(persisted.traceId());
            recovery.setStatus(ExecutionRecord.Status.SUCCEEDED);
            recovery.setFinishedAt(Instant.now());
            recovery = executions.saveAndFlush(recovery);
            Map<String, Object> resultState = new java.util.LinkedHashMap<>(state);
            resultState.put("status", "succeeded");
            resultState.put("reply", reply.reply());
            ExecutionCheckpoint resultCheckpoint = checkpoints.append(original.getTenantId(), recovery.getId(),
                    attempt.getFailureCaseId(), "reply.emit", null, ExecutionCheckpoint.Outcome.CONFIRMED, true,
                    ExecutionCheckpoint.SideEffectSemantics.NONE, null, resultState, null, null);
            return new Result(recovery, resultCheckpoint);
        } catch (RuntimeException error) {
            Map<String, Object> failedState = workspaces.loadCheckpoint(agent, original.getSessionId());
            if (failedState == null) failedState = state;
            boolean pending = !Jsons.mapList(failedState.get("pending_tools")).isEmpty();
            String step = Jsons.text(failedState.get("next"));
            if (step.isBlank()) step = checkpoint.getNextStepKey() == null ? checkpoint.getStepKey() : checkpoint.getNextStepKey();
            ExecutionCheckpoint failedCheckpoint = checkpoints.append(original.getTenantId(), recovery.getId(),
                    attempt.getFailureCaseId(), step, step, ExecutionCheckpoint.Outcome.FAILED, !pending,
                    pending ? ExecutionCheckpoint.SideEffectSemantics.UNKNOWN : ExecutionCheckpoint.SideEffectSemantics.NONE,
                    null, failedState, error instanceof RecoveryTimeoutException ? "TIMEOUT" : "EXECUTION_FAILED",
                    "重拾执行失败");
            recovery.setStatus(error instanceof RecoveryTimeoutException
                    ? ExecutionRecord.Status.TIMED_OUT : ExecutionRecord.Status.FAILED);
            recovery.setFailureCategory(error instanceof RecoveryTimeoutException ? "TIMEOUT" : "EXECUTION_FAILED");
            recovery.setFailureStep(step);
            recovery.setErrorSummary("重拾执行失败");
            recovery.setFinishedAt(Instant.now());
            executions.save(recovery);
            throw new RecoveryFailedException(recovery, failedCheckpoint, error);
        }
    }

    static String originalUserMessage(ExecutionRecord original, Map<String, Object> state) {
        String fromState = Jsons.text(state == null ? null : state.get("last_user"));
        if (!fromState.isBlank()) return fromState;
        String fromHistory = "";
        for (Map<String, Object> item : Jsons.mapList(state == null ? null : state.get("working"))) {
            if ("user".equals(Jsons.text(item.get("role")))) {
                fromHistory = Jsons.text(item.get("content"));
            }
        }
        if (!fromHistory.isBlank()) return fromHistory;
        Map<String, Object> request = Jsons.map(original == null ? null : original.getRequestSnapshotJson());
        String message = Jsons.text(request.get("message"));
        if (!message.isBlank()) return message;
        return Jsons.text(request.get("input"));
    }

    private ExecutionRecord newRecoveryExecution(ExecutionRecord original) {
        ExecutionRecord recovery = new ExecutionRecord();
        recovery.setTenantId(original.getTenantId());
        recovery.setOwnerId(original.getOwnerId());
        recovery.setExecutionKind(ExecutionRecord.Kind.RECOVERY);
        recovery.setEntryPoint(original.getEntryPoint());
        recovery.setAgentId(original.getAgentId());
        recovery.setAgentName(original.getAgentName());
        recovery.setWorkflowId(original.getWorkflowId());
        recovery.setSessionId(original.getSessionId());
        recovery.setParentExecutionId(original.getId());
        recovery.setRequestSnapshotJson(original.getRequestSnapshotJson());
        recovery.setRequestHash(original.getRequestHash());
        recovery.setConfigSnapshotJson(original.getConfigSnapshotJson());
        recovery.setConfigHash(original.getConfigHash());
        recovery.setCredentialRefsJson(original.getCredentialRefsJson());
        recovery.setStatus(ExecutionRecord.Status.RUNNING);
        return executions.saveAndFlush(recovery);
    }

    private CurrentUser currentUser(RecoveryAttempt attempt) {
        User row = users.findById(attempt.getInitiatedBy())
                .filter(User::isEnabled)
                .filter(user -> attempt.getTenantId().equals(user.getTenantId()))
                .orElseThrow(() -> new SecurityException("发起用户已停用或权限已变更"));
        Role role = row.getRoleId() == null ? null : roles.findById(row.getRoleId()).orElse(null);
        List<String> permissions = role == null ? List.of() : role.getPermissions();
        CurrentUser user = new CurrentUser(row.getId(), row.getUsername(), row.getDisplayName(), row.getTenantId(),
                row.getTenantId(), "", row.getRoleId(), role == null ? "" : role.getName(), permissions);
        if (!user.has("session:write", "agent:write")) throw new SecurityException("重拾执行权限已被撤销");
        return user;
    }

    private Agent frozenAgent(Agent live, Map<String, Object> config) {
        Agent copy = new Agent();
        copy.setId(live.getId());
        copy.setTenantId(live.getTenantId());
        copy.setOwnerId(live.getOwnerId());
        copy.setName(Jsons.text(config.get("name")).isBlank() ? live.getName() : Jsons.text(config.get("name")));
        copy.setDescription(Jsons.text(config.get("description")));
        copy.setModelName(Jsons.text(config.get("model_name")).isBlank() ? live.getModelName() : Jsons.text(config.get("model_name")));
        copy.setSystemPrompt(Jsons.text(config.get("system_prompt")));
        copy.setSkillIds(config.containsKey("skill_ids") ? Jsons.longList(config.get("skill_ids")) : live.getSkillIds());
        copy.setMcpIds(config.containsKey("mcp_ids") ? Jsons.longList(config.get("mcp_ids")) : live.getMcpIds());
        copy.setOpencliIds(config.containsKey("opencli_ids") ? Jsons.longList(config.get("opencli_ids")) : live.getOpencliIds());
        copy.setHttpAgentIds(config.containsKey("http_agent_ids") ? Jsons.longList(config.get("http_agent_ids")) : live.getHttpAgentIds());
        copy.setKnowledgeIds(config.containsKey("knowledge_ids") ? Jsons.longList(config.get("knowledge_ids")) : live.getKnowledgeIds());
        copy.setToolFlows(config.containsKey("tool_flows") ? Jsons.mapList(config.get("tool_flows")) : live.getToolFlows());
        copy.setSandboxId(config.containsKey("sandbox_id") ? Jsons.asLong(config.get("sandbox_id")) : live.getSandboxId());
        copy.setWorkspace(live.getWorkspace());
        return copy;
    }

    private Map<String, Object> map(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("失败时逻辑配置快照损坏", error);
        }
    }

    public static class RecoveryTimeoutException extends RuntimeException {
        public RecoveryTimeoutException(String message) { super(message); }
    }

    public static class RecoveryFailedException extends RuntimeException {
        private final ExecutionRecord execution;
        private final ExecutionCheckpoint checkpoint;

        public RecoveryFailedException(ExecutionRecord execution, ExecutionCheckpoint checkpoint, Throwable cause) {
            super("重拾执行失败", cause);
            this.execution = execution;
            this.checkpoint = checkpoint;
        }

        public ExecutionRecord getExecution() { return execution; }
        public ExecutionCheckpoint getCheckpoint() { return checkpoint; }
    }
}
