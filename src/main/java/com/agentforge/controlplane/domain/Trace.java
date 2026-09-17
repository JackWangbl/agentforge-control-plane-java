package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** traces 表只有 started_at，没有 created_at/updated_at，所以自己声明主键和时间列。 */
@Entity
@Table(name = "traces")
public class Trace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 80, nullable = false, unique = true)
    private String traceId;

    @Column(name = "session_id", length = 80, nullable = false)
    private String sessionId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_name", length = 80, nullable = false)
    private String agentName;

    @Column(name = "operation", length = 120, nullable = false)
    private String operation;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "ok";

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens = 0;

    @Convert(converter = JsonConverters.MapListConverter.class)
    @Column(name = "spans", columnDefinition = "json")
    private List<Map<String, Object>> spans = new ArrayList<>();

    @Column(name = "langfuse_url", length = 500, nullable = false)
    private String langfuseUrl = "";

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "owner_id")
    private Long ownerId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public List<Map<String, Object>> getSpans() { return spans; }
    public void setSpans(List<Map<String, Object>> spans) {
        this.spans = spans == null ? new ArrayList<>() : spans;
    }
    public String getLangfuseUrl() { return langfuseUrl; }
    public void setLangfuseUrl(String langfuseUrl) { this.langfuseUrl = langfuseUrl; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}
