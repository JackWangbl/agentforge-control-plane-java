package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_tasks")
public class RecoveryTask extends TimestampedEntity {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MANUAL_REQUIRED, CANCELLED }

    @Column(name = "task_key", length = 80, nullable = false, unique = true)
    private String taskKey = "task_" + UUID.randomUUID().toString().replace("-", "");
    @Column(name = "attempt_id", nullable = false, unique = true) private Long attemptId;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "agent_id", nullable = false) private Long agentId;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 24, nullable = false) private Status status = Status.QUEUED;
    @Column(name = "queue_sequence", nullable = false) private long queueSequence;
    @Column(name = "claimed_by", length = 120) private String claimedBy;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "heartbeat_at") private Instant heartbeatAt;
    @Column(name = "available_at", nullable = false) private Instant availableAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public String getTaskKey() { return taskKey; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long value) { attemptId = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long value) { agentId = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public long getQueueSequence() { return queueSequence; }
    public void setQueueSequence(long value) { queueSequence = value; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String value) { claimedBy = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant value) { heartbeatAt = value; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant value) { availableAt = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
