package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "evaluation_results")
public class EvaluationResult extends TenantOwnedEntity {

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "case_id")
    private Long caseId;

    /** 同一条用例的第几次试跑，从 1 开始。 */
    @Column(name = "trial_index", nullable = false)
    private int trialIndex = 1;

    @Column(name = "case_key", length = 80, nullable = false)
    private String caseKey = "";

    /** passed / failed / skipped / error */
    @Column(name = "status", length = 24, nullable = false)
    private String status = "failed";

    @Column(name = "score", nullable = false)
    private double score = 0;

    @Lob
    @Column(name = "input", columnDefinition = "text")
    private String input = "";

    @Lob
    @Column(name = "expected", columnDefinition = "text")
    private String expected = "";

    @Lob
    @Column(name = "actual", columnDefinition = "text")
    private String actual = "";

    @Lob
    @Column(name = "reason", columnDefinition = "text")
    private String reason = "";

    @Convert(converter = JsonConverters.MapListConverter.class)
    @Column(name = "grader_results", columnDefinition = "json")
    private List<Map<String, Object>> graderResults = new ArrayList<>();

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs = 0;

    @Column(name = "tokens", nullable = false)
    private int tokens = 0;

    @Column(name = "trace_id", length = 80, nullable = false)
    private String traceId = "";

    @Column(name = "session_id", length = 80, nullable = false)
    private String sessionId = "";

    @Lob
    @Column(name = "error", columnDefinition = "text")
    private String error = "";

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public int getTrialIndex() { return trialIndex <= 0 ? 1 : trialIndex; }
    public void setTrialIndex(int trialIndex) { this.trialIndex = trialIndex; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getInput() { return input == null ? "" : input; }
    public void setInput(String input) { this.input = input; }
    public String getExpected() { return expected == null ? "" : expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getActual() { return actual == null ? "" : actual; }
    public void setActual(String actual) { this.actual = actual; }
    public String getReason() { return reason == null ? "" : reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<Map<String, Object>> getGraderResults() { return graderResults; }
    public void setGraderResults(List<Map<String, Object>> graderResults) {
        this.graderResults = graderResults == null ? new ArrayList<>() : graderResults;
    }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getError() { return error == null ? "" : error; }
    public void setError(String error) { this.error = error; }
}
