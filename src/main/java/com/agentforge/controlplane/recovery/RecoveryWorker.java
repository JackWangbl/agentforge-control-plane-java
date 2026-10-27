package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.domain.RecoveryAttempt;
import com.agentforge.controlplane.domain.RecoveryTask;
import com.agentforge.controlplane.repo.ExecutionCheckpointRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.agentforge.controlplane.repo.RecoveryAttemptRepository;
import com.agentforge.controlplane.repo.RecoveryTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class RecoveryWorker {
    public record Outcome(String status, String resultExecutionKey) {}

    private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);
    private final RecoveryTaskRepository tasks;
    private final RecoveryAttemptRepository attempts;
    private final FailureCaseRepository cases;
    private final ExecutionRecordRepository executions;
    private final ExecutionCheckpointRepository checkpoints;
    private final RecoveryExecutionService executionService;
    private final RecoveryStateMachine stateMachine;
    private final FailureClassifier classifier;
    private final AppSettings settings;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final TransactionTemplate transactions;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final Map<Long, CompletableFuture<Outcome>> completions = new ConcurrentHashMap<>();

    public RecoveryWorker(RecoveryTaskRepository tasks, RecoveryAttemptRepository attempts,
                          FailureCaseRepository cases, ExecutionRecordRepository executions,
                          ExecutionCheckpointRepository checkpoints, RecoveryExecutionService executionService,
                          RecoveryStateMachine stateMachine, FailureClassifier classifier, AppSettings settings,
                          ObjectMapper mapper, @Qualifier("recoveryExecutor") Executor executor,
                          TransactionTemplate transactions) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.cases = cases;
        this.executions = executions;
        this.checkpoints = checkpoints;
        this.executionService = executionService;
        this.stateMachine = stateMachine;
        this.classifier = classifier;
        this.settings = settings;
        this.mapper = mapper;
        this.executor = executor;
        this.transactions = transactions;
    }

    public CompletableFuture<Outcome> kick(Long taskId) {
        CompletableFuture<Outcome> completion = completions.computeIfAbsent(taskId, ignored -> new CompletableFuture<>());
        executor.execute(() -> process(taskId, completion));
        return completion;
    }

    @Scheduled(fixedDelayString = "${agentforge.recovery.poll-interval-ms:500}")
    public void poll() {
        for (RecoveryTask task : tasks.findTop100ByStatusOrderByQueueSequenceAsc(RecoveryTask.Status.QUEUED)) {
            if (!completions.containsKey(task.getId())) kick(task.getId());
        }
        recoverExpiredLeases();
    }

    public void recoverExpiredLeases() {
        for (RecoveryTask task : tasks.findByStatusAndLeaseUntilBefore(RecoveryTask.Status.RUNNING, Instant.now())) {
            transactions.executeWithoutResult(status -> {
                RecoveryTask locked = tasks.findById(task.getId()).orElse(null);
                if (locked == null || locked.getStatus() != RecoveryTask.Status.RUNNING
                        || locked.getLeaseUntil() == null || !locked.getLeaseUntil().isBefore(Instant.now())) return;
                RecoveryAttempt attempt = attempts.findById(locked.getAttemptId()).orElseThrow();
                ExecutionCheckpoint checkpoint = checkpoints.findById(attempt.getStartCheckpointId()).orElseThrow();
                if (checkpoint.isSafeToResume()) {
                    locked.setStatus(RecoveryTask.Status.QUEUED);
                    locked.setClaimedBy(null);
                    locked.setLeaseUntil(null);
                    locked.setAvailableAt(Instant.now());
                    tasks.save(locked);
                } else {
                    markManual(locked, attempt, "WORKER_LOST", "执行器中断且步骤结果未确认");
                }
            });
        }
    }

    private void process(Long taskId, CompletableFuture<Outcome> completion) {
        Claim claim = transactions.execute(status -> claim(taskId));
        if (claim == null) {
            completions.remove(taskId, completion);
            return;
        }
        try {
            RecoveryExecutionService.Result result = executionService.resume(claim.attempt(), claim.original(), claim.checkpoint());
            transactions.executeWithoutResult(status -> completeSuccess(claim, result));
            completion.complete(new Outcome("SUCCEEDED", result.execution().getExecutionKey()));
        } catch (Throwable error) {
            FailureClassifier.ClassifiedFailure failure = classifier.classify(error);
            String terminal = transactions.execute(status -> completeFailure(claim, error, failure));
            completion.complete(new Outcome(terminal, null));
        } finally {
            completions.remove(taskId, completion);
        }
    }

    private Claim claim(Long taskId) {
        RecoveryTask task = tasks.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != RecoveryTask.Status.QUEUED || task.getAvailableAt().isAfter(Instant.now())) return null;
        RecoveryAttempt attempt = attempts.findById(task.getAttemptId()).orElseThrow();
        Map<String, Object> concurrency = jsonMap(attempt.getConcurrencyPolicyJson());
        long tenantLimit = number(concurrency.get("per_tenant_concurrency"), settings.getRecovery().getPerTenantConcurrency());
        long agentLimit = number(concurrency.get("per_agent_concurrency"), settings.getRecovery().getPerAgentConcurrency());
        if (tasks.countByTenantIdAndStatus(task.getTenantId(), RecoveryTask.Status.RUNNING) >= tenantLimit
                || tasks.countByTenantIdAndAgentIdAndStatus(task.getTenantId(), task.getAgentId(), RecoveryTask.Status.RUNNING) >= agentLimit) {
            task.setAvailableAt(Instant.now().plusMillis(settings.getRecovery().getPollIntervalMs()));
            tasks.save(task);
            return null;
        }
        FailureCase item = cases.findById(attempt.getFailureCaseId()).orElseThrow();
        ExecutionRecord original = executions.findById(item.getExecutionRecordId()).orElseThrow();
        ExecutionCheckpoint checkpoint = checkpoints.findById(attempt.getStartCheckpointId()).orElseThrow();
        Instant now = Instant.now();
        task.setStatus(RecoveryTask.Status.RUNNING);
        task.setClaimedBy(workerId);
        task.setStartedAt(now);
        task.setHeartbeatAt(now);
        task.setLeaseUntil(now.plusMillis(settings.getRecovery().getLeaseMs()));
        attempt.setStatus(RecoveryAttempt.Status.RUNNING);
        attempt.setStartedAt(now);
        tasks.save(task);
        attempts.save(attempt);
        return new Claim(task.getId(), attempt.getId(), item.getId(), original.getId(), checkpoint.getId(),
                task, attempt, item, original, checkpoint);
    }

    private void completeSuccess(Claim claim, RecoveryExecutionService.Result result) {
        RecoveryTask task = tasks.findById(claim.taskId()).orElseThrow();
        RecoveryAttempt attempt = attempts.findById(claim.attemptId()).orElseThrow();
        FailureCase item = cases.findById(claim.caseId()).orElseThrow();
        Instant now = Instant.now();
        task.setStatus(RecoveryTask.Status.SUCCEEDED);
        task.setFinishedAt(now);
        task.setLeaseUntil(null);
        attempt.setStatus(RecoveryAttempt.Status.SUCCEEDED);
        attempt.setResultExecutionId(result.execution().getId());
        attempt.setResultCheckpointId(result.checkpoint().getId());
        attempt.setFinishedAt(now);
        stateMachine.succeed(item, result.execution().getId(), attempt.getId());
        tasks.save(task);
        attempts.save(attempt);
        cases.save(item);
        log.info("Recovery state changed tenant={} case={} attempt={} task={} agent={} status=SUCCEEDED",
                item.getTenantId(), item.getCaseKey(), attempt.getAttemptKey(), task.getTaskKey(), task.getAgentId());
    }

    private String completeFailure(Claim claim, Throwable error, FailureClassifier.ClassifiedFailure failure) {
        RecoveryTask task = tasks.findById(claim.taskId()).orElseThrow();
        RecoveryAttempt attempt = attempts.findById(claim.attemptId()).orElseThrow();
        FailureCase item = cases.findById(claim.caseId()).orElseThrow();
        ExecutionCheckpoint checkpoint = checkpoints.findById(claim.checkpointId()).orElseThrow();
        RecoveryExecutionService.RecoveryFailedException recoveryFailure = error instanceof RecoveryExecutionService.RecoveryFailedException value
                ? value : null;
        Throwable cause = recoveryFailure == null ? error : recoveryFailure.getCause();
        if (recoveryFailure != null) {
            checkpoint = recoveryFailure.getCheckpoint();
            attempt.setResultExecutionId(recoveryFailure.getExecution().getId());
            attempt.setResultCheckpointId(checkpoint.getId());
            item.setLatestCheckpointId(checkpoint.getId());
        }
        boolean timeout = cause instanceof RecoveryExecutionService.RecoveryTimeoutException;
        boolean safe = checkpoint.isSafeToResume();
        RecoveryTask.Status taskStatus = timeout ? RecoveryTask.Status.TIMED_OUT
                : (safe ? RecoveryTask.Status.FAILED : RecoveryTask.Status.MANUAL_REQUIRED);
        RecoveryAttempt.Status attemptStatus = timeout ? RecoveryAttempt.Status.TIMED_OUT
                : (safe ? RecoveryAttempt.Status.FAILED : RecoveryAttempt.Status.MANUAL_REQUIRED);
        task.setStatus(taskStatus);
        task.setFinishedAt(Instant.now());
        task.setLeaseUntil(null);
        attempt.setStatus(attemptStatus);
        attempt.setFailureCategory(failure.category());
        attempt.setErrorSummary(failure.summary());
        attempt.setFinishedAt(Instant.now());
        stateMachine.fail(item, safe, failure.category(), failure.summary());
        tasks.save(task);
        attempts.save(attempt);
        cases.save(item);
        log.warn("Recovery state changed tenant={} case={} attempt={} task={} agent={} status={} category={}",
                item.getTenantId(), item.getCaseKey(), attempt.getAttemptKey(), task.getTaskKey(), task.getAgentId(),
                taskStatus, failure.category());
        return taskStatus.name();
    }

    private void markManual(RecoveryTask task, RecoveryAttempt attempt, String category, String summary) {
        FailureCase item = cases.findById(attempt.getFailureCaseId()).orElseThrow();
        task.setStatus(RecoveryTask.Status.MANUAL_REQUIRED);
        task.setFinishedAt(Instant.now());
        task.setLeaseUntil(null);
        attempt.setStatus(RecoveryAttempt.Status.MANUAL_REQUIRED);
        attempt.setFailureCategory(category);
        attempt.setErrorSummary(summary);
        attempt.setFinishedAt(Instant.now());
        if (item.getStatus() == FailureCase.Status.RECOVERING) stateMachine.fail(item, false, category, summary);
        tasks.save(task);
        attempts.save(attempt);
        cases.save(item);
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (Exception error) {
            return Map.of();
        }
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private record Claim(Long taskId, Long attemptId, Long caseId, Long originalId, Long checkpointId,
                         RecoveryTask task, RecoveryAttempt attempt, FailureCase item,
                         ExecutionRecord original, ExecutionCheckpoint checkpoint) {}
}
