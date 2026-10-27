package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.domain.RecoveryAttempt;
import com.agentforge.controlplane.domain.RecoveryTask;
import com.agentforge.controlplane.dto.FailureCaseDtos;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.agentforge.controlplane.repo.RecoveryAttemptRepository;
import com.agentforge.controlplane.repo.RecoveryTaskRepository;
import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FailureCaseService {
    private final FailureCaseRepository cases;
    private final ExecutionRecordRepository executions;
    private final RecoveryAttemptRepository attempts;
    private final RecoveryTaskRepository tasks;
    private final CheckpointService checkpointService;
    private final SensitiveDataSanitizer sanitizer;
    private final ResourceAccessService access;
    private final ObjectMapper mapper;

    public FailureCaseService(FailureCaseRepository cases, ExecutionRecordRepository executions,
                              RecoveryAttemptRepository attempts, RecoveryTaskRepository tasks,
                              CheckpointService checkpointService, SensitiveDataSanitizer sanitizer,
                              ResourceAccessService access, ObjectMapper mapper) {
        this.cases = cases;
        this.executions = executions;
        this.attempts = attempts;
        this.tasks = tasks;
        this.checkpointService = checkpointService;
        this.sanitizer = sanitizer;
        this.access = access;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public FailureCaseDtos.Page list(CurrentUser user, Instant from, Instant to,
                                     ExecutionRecord.EntryPoint entryPoint, Long agentId,
                                     FailureCase.Status status, int page, int size) {
        requireRead(user);
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(24, ChronoUnit.HOURS) : from;
        if (!effectiveFrom.isBefore(effectiveTo)) throw ApiException.unprocessable("from 必须早于 to");
        if (page < 0 || size < 1 || size > 200) throw ApiException.unprocessable("分页参数超出范围");
        if (agentId != null && !resourceVisible(user, ResourceKind.AGENT, agentId)) {
            return new FailureCaseDtos.Page(new FailureCaseDtos.Summary(0, 0, 0), page, size, 0, List.of());
        }

        Specification<FailureCase> caseSpec = caseSpecification(user.getTenantId(), effectiveFrom, effectiveTo,
                entryPoint, agentId, status);
        var result = cases.findAll(caseSpec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
        List<FailureCaseDtos.ListItem> items = result.getContent().stream().map(item -> {
            ExecutionRecord execution = executions.findByIdAndTenantId(item.getExecutionRecordId(), user.getTenantId())
                    .orElseThrow(() -> ApiException.notFound("Failure Case 不存在"));
            if (!visible(user, execution)) return null;
            return new FailureCaseDtos.ListItem(item.getCaseKey(), execution.getExecutionKey(),
                    execution.getEntryPoint().name(), execution.getAgentId(), execution.getAgentName(),
                    item.getStatus().name(), item.getFailureCategory(), item.getFailureStep(), item.getErrorSummary(),
                    item.isResumable(), execution.getFinishedAt());
        }).filter(java.util.Objects::nonNull).toList();

        Specification<ExecutionRecord> completedSpec = executionSpecification(user.getTenantId(), effectiveFrom,
                effectiveTo, entryPoint, agentId, false);
        long completed = executions.count(completedSpec);
        long failed = executions.count(executionSpecification(user.getTenantId(), effectiveFrom,
                effectiveTo, entryPoint, agentId, true));
        double rate = completed == 0 ? 0 : Math.round(failed * 1000.0 / completed) / 10.0;
        return new FailureCaseDtos.Page(new FailureCaseDtos.Summary(completed, failed, rate), page, size,
                result.getTotalElements(), items);
    }

    @Transactional(readOnly = true)
    public FailureCaseDtos.Detail detail(CurrentUser user, String caseKey) {
        requireRead(user);
        FailureCase item = requireCase(user.getTenantId(), caseKey);
        ExecutionRecord execution = executions.findByIdAndTenantId(item.getExecutionRecordId(), user.getTenantId())
                .orElseThrow(() -> ApiException.notFound("Failure Case 不存在"));
        if (!visible(user, execution)) throw ApiException.notFound("Failure Case 不存在");
        Object original = sanitizer.sanitize(readJson(execution.getRequestSnapshotJson(), new TypeReference<Object>() {}));
        Map<String, Object> config = readJson(execution.getConfigSnapshotJson(), new TypeReference<>() {});
        List<String> credentialRefs = readJson(execution.getCredentialRefsJson(), new TypeReference<>() {});
        List<ExecutionCheckpoint> history = checkpointService.history(user.getTenantId(), execution.getId());
        ExecutionCheckpoint latest = history.isEmpty() ? null : history.get(history.size() - 1);
        List<String> completed = history.stream().filter(cp -> cp.getOutcome() == ExecutionCheckpoint.Outcome.CONFIRMED)
                .map(ExecutionCheckpoint::getStepKey).toList();
        FailureCaseDtos.CheckpointView checkpoint = latest == null ? null : new FailureCaseDtos.CheckpointView(
                latest.getCheckpointKey(), latest.getSequenceNo(),
                latest.isSafeToResume() ? latest.getStepKey() : null, completed, latest.getNextStepKey(),
                latest.getOutcome().name(), latest.isSafeToResume(), latest.getSideEffectSemantics().name());
        List<RecoveryAttempt> attemptRows = attempts.findByFailureCaseIdAndTenantIdOrderByCreatedAtAsc(item.getId(), user.getTenantId());
        List<FailureCaseDtos.AttemptView> attemptViews = attemptRows.stream().map(attempt -> new FailureCaseDtos.AttemptView(
                attempt.getAttemptKey(), attempt.getInitiatedBy(), attempt.isForced(), attempt.getForceReason(),
                attempt.getRiskAcknowledgedAt(), attempt.getStatus().name(), attempt.getSyncWaitMs(),
                attempt.getStepTimeoutMs(), attempt.getOverallTimeoutMs(), attempt.getFailureCategory(),
                attempt.getErrorSummary(), attempt.getStartedAt(), attempt.getFinishedAt(),
                executionKey(attempt.getResultExecutionId(), user.getTenantId()))).toList();
        FailureCaseDtos.TaskView activeTask = null;
        for (int i = attemptRows.size() - 1; i >= 0; i--) {
            RecoveryAttempt attempt = attemptRows.get(i);
            RecoveryTask task = tasks.findByAttemptIdAndTenantId(attempt.getId(), user.getTenantId()).orElse(null);
            if (task != null && (task.getStatus() == RecoveryTask.Status.QUEUED || task.getStatus() == RecoveryTask.Status.RUNNING)) {
                activeTask = taskView(task, item, attempt, user.getTenantId());
                break;
            }
        }
        boolean admin = user.has("tenant:admin");
        boolean canRecover = user.has("session:write") && item.getStatus() == FailureCase.Status.RESUMABLE && item.isResumable();
        boolean canForce = user.has("session:write") && admin && item.getStatus() == FailureCase.Status.MANUAL_REQUIRED;
        return new FailureCaseDtos.Detail(new FailureCaseDtos.CaseView(item.getCaseKey(), execution.getExecutionKey(),
                execution.getEntryPoint().name(), execution.getAgentId(), execution.getAgentName(), execution.getWorkflowId(),
                item.getStatus().name(), item.getFailureCategory(), item.getFailureStep(), item.getErrorSummary(),
                execution.getFinishedAt(), item.getUpdatedAt()), original, execution.getRequestHash(),
                new FailureCaseDtos.ConfigurationView(execution.getConfigHash(), sanitizerMap(config), credentialRefs),
                checkpoint, attemptViews, activeTask, new FailureCaseDtos.Actions(canRecover, canForce));
    }

    @Transactional(readOnly = true)
    public FailureCase requireCase(Long tenantId, String caseKey) {
        return cases.findByCaseKeyAndTenantId(caseKey, tenantId)
                .orElseThrow(() -> ApiException.notFound("Failure Case 不存在"));
    }

    @Transactional(readOnly = true)
    public FailureCaseDtos.TaskView task(CurrentUser user, String taskKey) {
        requireRead(user);
        RecoveryTask task = tasks.findByTaskKeyAndTenantId(taskKey, user.getTenantId())
                .orElseThrow(() -> ApiException.notFound("恢复任务不存在"));
        RecoveryAttempt attempt = attempts.findById(task.getAttemptId())
                .filter(row -> user.getTenantId().equals(row.getTenantId()))
                .orElseThrow(() -> ApiException.notFound("恢复任务不存在"));
        FailureCase item = cases.findById(attempt.getFailureCaseId())
                .filter(row -> user.getTenantId().equals(row.getTenantId()))
                .orElseThrow(() -> ApiException.notFound("恢复任务不存在"));
        return taskView(task, item, attempt, user.getTenantId());
    }

    private FailureCaseDtos.TaskView taskView(RecoveryTask task, FailureCase item, RecoveryAttempt attempt, Long tenantId) {
        return new FailureCaseDtos.TaskView(task.getTaskKey(), item.getCaseKey(), attempt.getAttemptKey(),
                task.getStatus().name(), task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt(),
                attempt.getFailureCategory(), attempt.getErrorSummary(), executionKey(attempt.getResultExecutionId(), tenantId));
    }

    private String executionKey(Long id, Long tenantId) {
        if (id == null) return null;
        return executions.findByIdAndTenantId(id, tenantId).map(ExecutionRecord::getExecutionKey).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizerMap(Map<String, Object> value) {
        return (Map<String, Object>) sanitizer.sanitize(value);
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, type);
        } catch (Exception error) {
            throw new IllegalStateException("持久化快照损坏", error);
        }
    }

    private boolean visible(CurrentUser user, ExecutionRecord execution) {
        if (!resourceVisible(user, ResourceKind.AGENT, execution.getAgentId())) return false;
        return execution.getWorkflowId() == null || resourceVisible(user, ResourceKind.WORKFLOW, execution.getWorkflowId());
    }

    private boolean resourceVisible(CurrentUser user, ResourceKind kind, Long resourceId) {
        try {
            access.getRow(user, kind, resourceId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void requireRead(CurrentUser user) {
        if (!user.has("trace:read")) throw ApiException.forbidden("没有权限查看失败 Case");
    }

    private static Specification<FailureCase> caseSpecification(Long tenantId, Instant from, Instant to,
            ExecutionRecord.EntryPoint entryPoint, Long agentId, FailureCase.Status status) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            Subquery<Long> executionIds = query.subquery(Long.class);
            var execution = executionIds.from(ExecutionRecord.class);
            List<jakarta.persistence.criteria.Predicate> executionPredicates = new ArrayList<>();
            executionPredicates.add(cb.equal(execution.get("tenantId"), tenantId));
            executionPredicates.add(cb.between(execution.get("finishedAt"), from, to));
            if (entryPoint != null) executionPredicates.add(cb.equal(execution.get("entryPoint"), entryPoint));
            if (agentId != null) executionPredicates.add(cb.equal(execution.get("agentId"), agentId));
            executionIds.select(execution.get("id")).where(executionPredicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            predicates.add(root.get("executionRecordId").in(executionIds));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static Specification<ExecutionRecord> executionSpecification(Long tenantId, Instant from, Instant to,
            ExecutionRecord.EntryPoint entryPoint, Long agentId, boolean failedOnly) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.equal(root.get("executionKind"), ExecutionRecord.Kind.ORIGINAL));
            predicates.add(cb.between(root.get("finishedAt"), from, to));
            if (failedOnly) {
                predicates.add(root.get("status").in(ExecutionRecord.Status.FAILED,
                        ExecutionRecord.Status.TIMED_OUT, ExecutionRecord.Status.MANUAL_REQUIRED));
            } else {
                predicates.add(root.get("status").in(ExecutionRecord.Status.SUCCEEDED, ExecutionRecord.Status.FAILED,
                        ExecutionRecord.Status.TIMED_OUT, ExecutionRecord.Status.MANUAL_REQUIRED));
            }
            if (entryPoint != null) predicates.add(cb.equal(root.get("entryPoint"), entryPoint));
            if (agentId != null) predicates.add(cb.equal(root.get("agentId"), agentId));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
