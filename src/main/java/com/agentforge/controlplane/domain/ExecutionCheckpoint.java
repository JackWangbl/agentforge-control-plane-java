package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_checkpoints")
public class ExecutionCheckpoint {
    public enum Outcome { CONFIRMED, FAILED, NOT_STARTED, UNKNOWN }
    public enum SideEffectSemantics { NONE, IDEMPOTENT, SIDE_EFFECTING, UNKNOWN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "checkpoint_key", length = 80, nullable = false, unique = true)
    private String checkpointKey = "ckpt_" + UUID.randomUUID().toString().replace("-", "");
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "execution_record_id", nullable = false) private Long executionRecordId;
    @Column(name = "failure_case_id") private Long failureCaseId;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
    @Column(name = "step_key", length = 120, nullable = false) private String stepKey;
    @Column(name = "next_step_key", length = 120) private String nextStepKey;
    @Enumerated(EnumType.STRING) @Column(name = "outcome", length = 24, nullable = false) private Outcome outcome;
    @Column(name = "safe_to_resume", nullable = false) private boolean safeToResume;
    @Enumerated(EnumType.STRING) @Column(name = "side_effect_semantics", length = 32, nullable = false)
    private SideEffectSemantics sideEffectSemantics = SideEffectSemantics.UNKNOWN;
    @Column(name = "idempotency_key", length = 160) private String idempotencyKey;
    @Lob @Column(name = "state_snapshot_json", columnDefinition = "longtext", nullable = false)
    private String stateSnapshotJson = "{}";
    @Column(name = "state_hash", length = 64, nullable = false) private String stateHash;
    @Column(name = "error_category", length = 40) private String errorCategory;
    @Column(name = "error_summary", length = 500) private String errorSummary;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getCheckpointKey() { return checkpointKey; }
    public void setCheckpointKey(String value) { checkpointKey = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getExecutionRecordId() { return executionRecordId; }
    public void setExecutionRecordId(Long value) { executionRecordId = value; }
    public Long getFailureCaseId() { return failureCaseId; }
    public void setFailureCaseId(Long value) { failureCaseId = value; }
    public int getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(int value) { sequenceNo = value; }
    public String getStepKey() { return stepKey; }
    public void setStepKey(String value) { stepKey = value; }
    public String getNextStepKey() { return nextStepKey; }
    public void setNextStepKey(String value) { nextStepKey = value; }
    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome value) { outcome = value; }
    public boolean isSafeToResume() { return safeToResume; }
    public void setSafeToResume(boolean value) { safeToResume = value; }
    public SideEffectSemantics getSideEffectSemantics() { return sideEffectSemantics; }
    public void setSideEffectSemantics(SideEffectSemantics value) { sideEffectSemantics = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getStateSnapshotJson() { return stateSnapshotJson; }
    public void setStateSnapshotJson(String value) { stateSnapshotJson = value; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String value) { stateHash = value; }
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String value) { errorCategory = value; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String value) { errorSummary = value; }
    public Instant getCreatedAt() { return createdAt; }
}
