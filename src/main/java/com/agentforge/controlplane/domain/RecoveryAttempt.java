package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_attempts")
public class RecoveryAttempt extends TimestampedEntity {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MANUAL_REQUIRED }

    @Column(name = "attempt_key", length = 80, nullable = false, unique = true)
    private String attemptKey = "attempt_" + UUID.randomUUID().toString().replace("-", "");
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "failure_case_id", nullable = false) private Long failureCaseId;
    @Column(name = "initiated_by", nullable = false) private Long initiatedBy;
    @Column(name = "forced", nullable = false) private boolean forced;
    @Column(name = "force_reason", length = 500) private String forceReason;
    @Column(name = "risk_acknowledged_at") private Instant riskAcknowledgedAt;
    @Column(name = "start_checkpoint_id", nullable = false) private Long startCheckpointId;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 24, nullable = false) private Status status = Status.QUEUED;
    @Column(name = "sync_wait_ms", nullable = false) private long syncWaitMs;
    @Column(name = "step_timeout_ms", nullable = false) private long stepTimeoutMs;
    @Column(name = "overall_timeout_ms", nullable = false) private long overallTimeoutMs;
    @Column(name = "retry_policy_json", columnDefinition = "json", nullable = false) private String retryPolicyJson = "{}";
    @Column(name = "concurrency_policy_json", columnDefinition = "json", nullable = false) private String concurrencyPolicyJson = "{}";
    @Column(name = "result_execution_id") private Long resultExecutionId;
    @Column(name = "result_checkpoint_id") private Long resultCheckpointId;
    @Column(name = "failure_category", length = 40) private String failureCategory;
    @Column(name = "error_summary", length = 500) private String errorSummary;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public String getAttemptKey() { return attemptKey; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getFailureCaseId() { return failureCaseId; }
    public void setFailureCaseId(Long value) { failureCaseId = value; }
    public Long getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(Long value) { initiatedBy = value; }
    public boolean isForced() { return forced; }
    public void setForced(boolean value) { forced = value; }
    public String getForceReason() { return forceReason; }
    public void setForceReason(String value) { forceReason = value; }
    public Instant getRiskAcknowledgedAt() { return riskAcknowledgedAt; }
    public void setRiskAcknowledgedAt(Instant value) { riskAcknowledgedAt = value; }
    public Long getStartCheckpointId() { return startCheckpointId; }
    public void setStartCheckpointId(Long value) { startCheckpointId = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public long getSyncWaitMs() { return syncWaitMs; }
    public void setSyncWaitMs(long value) { syncWaitMs = value; }
    public long getStepTimeoutMs() { return stepTimeoutMs; }
    public void setStepTimeoutMs(long value) { stepTimeoutMs = value; }
    public long getOverallTimeoutMs() { return overallTimeoutMs; }
    public void setOverallTimeoutMs(long value) { overallTimeoutMs = value; }
    public String getRetryPolicyJson() { return retryPolicyJson; }
    public void setRetryPolicyJson(String value) { retryPolicyJson = value; }
    public String getConcurrencyPolicyJson() { return concurrencyPolicyJson; }
    public void setConcurrencyPolicyJson(String value) { concurrencyPolicyJson = value; }
    public Long getResultExecutionId() { return resultExecutionId; }
    public void setResultExecutionId(Long value) { resultExecutionId = value; }
    public Long getResultCheckpointId() { return resultCheckpointId; }
    public void setResultCheckpointId(Long value) { resultCheckpointId = value; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String value) { failureCategory = value; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String value) { errorSummary = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
