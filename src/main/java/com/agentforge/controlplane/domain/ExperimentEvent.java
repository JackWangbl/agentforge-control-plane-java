package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "experiment_events")
public class ExperimentEvent extends TenantOwnedEntity {

    @Column(name = "experiment_id", nullable = false)
    private Long experimentId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "session_id", length = 80, nullable = false)
    private String sessionId = "";

    @Column(name = "unit_key", length = 120, nullable = false)
    private String unitKey = "";

    /** run / assign / compare */
    @Column(name = "kind", length = 24, nullable = false)
    private String kind = "run";

    @Column(name = "status", length = 24, nullable = false)
    private String status = "ok";

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs = 0;

    @Column(name = "tokens", nullable = false)
    private int tokens = 0;

    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUnitKey() { return unitKey; }
    public void setUnitKey(String unitKey) { this.unitKey = unitKey; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }
}
