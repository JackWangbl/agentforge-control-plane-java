package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_records")
public class ExecutionRecord extends TenantOwnedEntity {
    public enum Kind { ORIGINAL, RECOVERY }
    public enum EntryPoint { PLAYGROUND, AGENT_INVOKE, WORKFLOW_DEBUG, WORKFLOW_INVOKE, A2A }
    public enum Status { RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MANUAL_REQUIRED }

    @Column(name = "execution_key", length = 80, nullable = false, unique = true)
    private String executionKey = "exec_" + UUID.randomUUID().toString().replace("-", "");
    @Enumerated(EnumType.STRING) @Column(name = "execution_kind", length = 20, nullable = false)
    private Kind executionKind = Kind.ORIGINAL;
    @Enumerated(EnumType.STRING) @Column(name = "entry_point", length = 32, nullable = false)
    private EntryPoint entryPoint;
    @Column(name = "agent_id", nullable = false) private Long agentId;
    @Column(name = "agent_name", length = 80, nullable = false) private String agentName = "";
    @Column(name = "workflow_id") private Long workflowId;
    @Column(name = "session_id", length = 160) private String sessionId;
    @Column(name = "trace_id", length = 160) private String traceId;
    @Column(name = "parent_execution_id") private Long parentExecutionId;
    @Lob @Column(name = "request_snapshot_json", columnDefinition = "longtext", nullable = false)
    private String requestSnapshotJson = "{}";
    @Column(name = "request_hash", length = 64, nullable = false) private String requestHash;
    @Lob @Column(name = "config_snapshot_json", columnDefinition = "longtext", nullable = false)
    private String configSnapshotJson = "{}";
    @Column(name = "config_hash", length = 64, nullable = false) private String configHash;
    @Column(name = "credential_refs_json", columnDefinition = "json") private String credentialRefsJson = "[]";
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 24, nullable = false)
    private Status status = Status.RUNNING;
    @Column(name = "failure_category", length = 40) private String failureCategory;
    @Column(name = "failure_step", length = 120) private String failureStep;
    @Column(name = "error_summary", length = 500) private String errorSummary;
    @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;

    public String getExecutionKey() { return executionKey; }
    public void setExecutionKey(String value) { executionKey = value; }
    public Kind getExecutionKind() { return executionKind; }
    public void setExecutionKind(Kind value) { executionKind = value; }
    public EntryPoint getEntryPoint() { return entryPoint; }
    public void setEntryPoint(EntryPoint value) { entryPoint = value; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long value) { agentId = value; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String value) { agentName = value == null ? "" : value; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long value) { workflowId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public Long getParentExecutionId() { return parentExecutionId; }
    public void setParentExecutionId(Long value) { parentExecutionId = value; }
    public String getRequestSnapshotJson() { return requestSnapshotJson; }
    public void setRequestSnapshotJson(String value) { requestSnapshotJson = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String value) { configSnapshotJson = value; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String value) { configHash = value; }
    public String getCredentialRefsJson() { return credentialRefsJson; }
    public void setCredentialRefsJson(String value) { credentialRefsJson = value == null ? "[]" : value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String value) { failureCategory = value; }
    public String getFailureStep() { return failureStep; }
    public void setFailureStep(String value) { failureStep = value; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String value) { errorSummary = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
