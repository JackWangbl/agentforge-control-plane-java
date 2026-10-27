package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

@Entity
@Table(name = "failure_cases")
public class FailureCase extends TenantOwnedEntity {
    public enum Status { RESUMABLE, RECOVERING, MANUAL_REQUIRED, RECOVERED }

    @Column(name = "case_key", length = 80, nullable = false, unique = true)
    private String caseKey = "case_" + UUID.randomUUID().toString().replace("-", "");
    @Column(name = "execution_record_id", nullable = false, unique = true) private Long executionRecordId;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 24, nullable = false)
    private Status status = Status.MANUAL_REQUIRED;
    @Column(name = "resumable", nullable = false) private boolean resumable;
    @Column(name = "latest_checkpoint_id") private Long latestCheckpointId;
    @Column(name = "failure_category", length = 40) private String failureCategory;
    @Column(name = "failure_step", length = 120) private String failureStep;
    @Column(name = "error_summary", length = 500) private String errorSummary;
    @Column(name = "recovered_execution_id") private Long recoveredExecutionId;
    @Column(name = "recovered_attempt_id") private Long recoveredAttemptId;
    @Version @Column(name = "version", nullable = false) private Long version = 0L;

    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String value) { caseKey = value; }
    public Long getExecutionRecordId() { return executionRecordId; }
    public void setExecutionRecordId(Long value) { executionRecordId = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public boolean isResumable() { return resumable; }
    public void setResumable(boolean value) { resumable = value; }
    public Long getLatestCheckpointId() { return latestCheckpointId; }
    public void setLatestCheckpointId(Long value) { latestCheckpointId = value; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String value) { failureCategory = value; }
    public String getFailureStep() { return failureStep; }
    public void setFailureStep(String value) { failureStep = value; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String value) { errorSummary = value; }
    public Long getRecoveredExecutionId() { return recoveredExecutionId; }
    public void setRecoveredExecutionId(Long value) { recoveredExecutionId = value; }
    public Long getRecoveredAttemptId() { return recoveredAttemptId; }
    public void setRecoveredAttemptId(Long value) { recoveredAttemptId = value; }
    public Long getVersion() { return version; }
}
