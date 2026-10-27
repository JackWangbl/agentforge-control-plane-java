package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.domain.RecoveryAttempt;
import com.agentforge.controlplane.domain.RecoveryTask;
import com.agentforge.controlplane.dto.FailureCaseDtos;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.agentforge.controlplane.repo.RecoveryAttemptRepository;
import com.agentforge.controlplane.repo.RecoveryTaskRepository;
import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

@Service
public class RecoveryCoordinator {
    public record Submission(boolean completed, FailureCaseDtos.RecoverResponse response) {}
    private record Created(String caseKey, String attemptKey, String taskKey, Long taskId, long syncWaitMs) {}

    private final FailureCaseRepository cases;
    private final ExecutionRecordRepository executions;
    private final RecoveryAttemptRepository attempts;
    private final RecoveryTaskRepository tasks;
    private final AgentRepository agents;
    private final CheckpointService checkpointService;
    private final RecoveryWorker worker;
    private final AppSettings settings;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public RecoveryCoordinator(FailureCaseRepository cases, ExecutionRecordRepository executions,
                               RecoveryAttemptRepository attempts, RecoveryTaskRepository tasks,
                               AgentRepository agents, CheckpointService checkpointService, RecoveryWorker worker,
                               AppSettings settings, ObjectMapper mapper, TransactionTemplate transactions) {
        this.cases = cases;
        this.executions = executions;
        this.attempts = attempts;
        this.tasks = tasks;
        this.agents = agents;
        this.checkpointService = checkpointService;
        this.worker = worker;
        this.settings = settings;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public Submission submit(CurrentUser user, String caseKey, FailureCaseDtos.RecoverRequest request) {
        if (!user.has("session:write", "agent:write")) throw ApiException.forbidden("没有重拾执行权限");
        if (request.force() && !user.has("tenant:admin")) throw ApiException.forbidden("只有租户管理员可强制重拾");
        Created created = transactions.execute(status -> create(user, caseKey, request));
        if (created == null) throw new IllegalStateException("重拾任务创建失败");
        CompletableFuture<RecoveryWorker.Outcome> future = worker.kick(created.taskId());
        try {
            RecoveryWorker.Outcome outcome = future.get(created.syncWaitMs(), TimeUnit.MILLISECONDS);
            return new Submission(true, new FailureCaseDtos.RecoverResponse(created.caseKey(), created.attemptKey(),
                    created.taskKey(), outcome.status(), outcome.resultExecutionKey(), null));
        } catch (TimeoutException timeout) {
            return new Submission(false, new FailureCaseDtos.RecoverResponse(created.caseKey(), created.attemptKey(),
                    created.taskKey(), "QUEUED", null, "/api/recovery-tasks/" + created.taskKey()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Submission(false, new FailureCaseDtos.RecoverResponse(created.caseKey(), created.attemptKey(),
                    created.taskKey(), "QUEUED", null, "/api/recovery-tasks/" + created.taskKey()));
        } catch (Exception failure) {
            throw ApiException.unavailable("重拾任务状态暂时不可用");
        }
    }

    public Submission submitForSession(CurrentUser user, Long agentId, String sessionId) {
        ExecutionRecord execution = executions
                .findFirstByTenantIdAndAgentIdAndSessionIdAndStatusInOrderByFinishedAtDesc(user.getTenantId(), agentId,
                        sessionId, List.of(ExecutionRecord.Status.FAILED, ExecutionRecord.Status.TIMED_OUT,
                                ExecutionRecord.Status.MANUAL_REQUIRED))
                .orElseThrow(() -> ApiException.conflict("没有可恢复的 Failure Case"));
        FailureCase item = cases.findByExecutionRecordIdAndTenantId(execution.getId(), user.getTenantId())
                .orElseThrow(() -> ApiException.conflict("没有可恢复的 Failure Case"));
        return submit(user, item.getCaseKey(), new FailureCaseDtos.RecoverRequest(false, "", false));
    }

    private Created create(CurrentUser user, String caseKey, FailureCaseDtos.RecoverRequest request) {
        FailureCase item = cases.findByCaseKeyAndTenantId(caseKey, user.getTenantId())
                .orElseThrow(() -> ApiException.notFound("Failure Case 不存在"));
        if (item.getStatus() == FailureCase.Status.RECOVERED) throw ApiException.conflict("该 Case 已重拾成功");
        if (item.getStatus() == FailureCase.Status.RECOVERING) throw ApiException.conflict("该 Case 正在重拾");
        if (request.force() && (request.force_reason() == null || request.force_reason().isBlank() || !request.risk_acknowledged())) {
            throw ApiException.unprocessable("强制重拾必须填写理由并确认风险");
        }
        ExecutionRecord original = executions.findByIdAndTenantId(item.getExecutionRecordId(), user.getTenantId())
                .orElseThrow(() -> ApiException.notFound("Failure Case 不存在"));
        ExecutionCheckpoint checkpoint;
        try {
            checkpoint = checkpointService.requireCheckpoint(user.getTenantId(), original.getId(), request.force());
        } catch (IllegalStateException invalid) {
            throw ApiException.conflict("检查点不可重拾或完整性校验失败");
        }
        Agent agent = agents.findById(original.getAgentId())
                .filter(value -> user.getTenantId().equals(value.getTenantId()))
                .orElseThrow(() -> ApiException.unavailable("失败时 Agent 配置不可用"));
        EffectivePolicy policy = effectivePolicy(agent.getRecoveryPolicy());
        long queued = tasks.countByTenantIdAndStatus(user.getTenantId(), RecoveryTask.Status.QUEUED);
        if (queued >= policy.tenantQueueCapacity()) throw ApiException.tooManyRequests("租户重拾队列已满，请稍后重试");
        FailureCase.Status expected = request.force() ? FailureCase.Status.MANUAL_REQUIRED : FailureCase.Status.RESUMABLE;
        if (cases.claim(item.getId(), user.getTenantId(), expected) != 1) throw ApiException.conflict("Case 状态已变化");

        RecoveryAttempt attempt = new RecoveryAttempt();
        attempt.setTenantId(user.getTenantId());
        attempt.setFailureCaseId(item.getId());
        attempt.setInitiatedBy(user.getId());
        attempt.setForced(request.force());
        attempt.setForceReason(request.force() ? request.force_reason().strip() : null);
        attempt.setRiskAcknowledgedAt(request.force() ? Instant.now() : null);
        attempt.setStartCheckpointId(checkpoint.getId());
        attempt.setSyncWaitMs(policy.syncWaitMs());
        attempt.setStepTimeoutMs(policy.stepTimeoutMs());
        attempt.setOverallTimeoutMs(policy.overallTimeoutMs());
        attempt.setRetryPolicyJson(json(Map.of(
                "total_attempts", settings.getHttpRetry().getTotalAttempts(),
                "initial_delay_ms", settings.getHttpRetry().getInitialDelayMs(),
                "maximum_delay_ms", settings.getHttpRetry().getMaximumDelayMs())));
        attempt.setConcurrencyPolicyJson(json(Map.of(
                "per_agent_concurrency", policy.perAgentConcurrency(),
                "per_tenant_concurrency", policy.perTenantConcurrency(),
                "tenant_queue_capacity", policy.tenantQueueCapacity())));
        attempt = attempts.saveAndFlush(attempt);

        RecoveryTask task = new RecoveryTask();
        task.setAttemptId(attempt.getId());
        task.setTenantId(user.getTenantId());
        task.setAgentId(original.getAgentId());
        task.setQueueSequence(System.currentTimeMillis() * 1000 + Math.floorMod(attempt.getId(), 1000));
        task = tasks.saveAndFlush(task);
        return new Created(item.getCaseKey(), attempt.getAttemptKey(), task.getTaskKey(), task.getId(), policy.syncWaitMs());
    }

    private EffectivePolicy effectivePolicy(Map<String, Object> override) {
        Map<String, Object> value = override == null ? new LinkedHashMap<>() : override;
        return new EffectivePolicy(number(value, "sync_wait_ms", settings.getRecovery().getSyncWaitMs()),
                number(value, "step_timeout_ms", settings.getRecovery().getStepTimeoutMs()),
                number(value, "overall_timeout_ms", settings.getRecovery().getOverallTimeoutMs()),
                (int) number(value, "per_agent_concurrency", settings.getRecovery().getPerAgentConcurrency()),
                (int) number(value, "per_tenant_concurrency", settings.getRecovery().getPerTenantConcurrency()),
                (int) number(value, "tenant_queue_capacity", settings.getRecovery().getTenantQueueCapacity()));
    }

    private static long number(Map<String, Object> values, String key, long fallback) {
        return values.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("恢复策略无法固化", error);
        }
    }

    private record EffectivePolicy(long syncWaitMs, long stepTimeoutMs, long overallTimeoutMs,
                                   int perAgentConcurrency, int perTenantConcurrency, int tenantQueueCapacity) {}
}
