package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "evaluation_runs")
public class EvaluationRun extends TenantOwnedEntity {

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "dataset", length = 120, nullable = false)
    private String dataset = "";

    @Column(name = "agent_name", length = 80, nullable = false)
    private String agentName = "";

    @Column(name = "dataset_id")
    private Long datasetId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "judge_model_id")
    private Long judgeModelId;

    /** offline / online / performance */
    @Column(name = "mode", length = 24, nullable = false)
    private String mode = "offline";

    /** contains / exact / regex / llm */
    @Column(name = "scorer", length = 24, nullable = false)
    private String scorer = "contains";

    /** queued / running / completed / failed / cancelled */
    @Column(name = "status", length = 24, nullable = false)
    private String status = "queued";

    @Column(name = "score", nullable = false)
    private double score = 0;

    @Column(name = "cases", nullable = false)
    private int cases = 0;

    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "case_ids", columnDefinition = "json")
    private List<Long> caseIds = new ArrayList<>();

    @Column(name = "total", nullable = false)
    private int total = 0;

    @Column(name = "passed", nullable = false)
    private int passed = 0;

    @Column(name = "failed", nullable = false)
    private int failed = 0;

    @Column(name = "skipped", nullable = false)
    private int skipped = 0;

    @Column(name = "avg_latency_ms", nullable = false)
    private int avgLatencyMs = 0;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens = 0;

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "metrics", columnDefinition = "json")
    private Map<String, Object> metrics = new LinkedHashMap<>();

    @Lob
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage = "";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Long getJudgeModelId() { return judgeModelId; }
    public void setJudgeModelId(Long judgeModelId) { this.judgeModelId = judgeModelId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getScorer() { return scorer; }
    public void setScorer(String scorer) { this.scorer = scorer; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public int getCases() { return cases; }
    public void setCases(int cases) { this.cases = cases; }
    public List<Long> getCaseIds() { return caseIds; }
    public void setCaseIds(List<Long> caseIds) { this.caseIds = caseIds == null ? new ArrayList<>() : caseIds; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public int getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(int avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics == null ? new LinkedHashMap<>() : metrics;
    }
    public String getErrorMessage() { return errorMessage == null ? "" : errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
