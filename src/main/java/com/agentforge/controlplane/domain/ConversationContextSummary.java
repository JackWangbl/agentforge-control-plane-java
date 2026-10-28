package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_context_summaries")
public class ConversationContextSummary extends TenantOwnedEntity {

    @Column(name = "session_id", length = 160, nullable = false)
    private String sessionId = "";

    @Column(name = "agent_id", nullable = false)
    private Long agentId = 0L;

    @Lob
    @Column(name = "summary_text", columnDefinition = "text", nullable = false)
    private String summaryText = "";

    @Column(name = "covered_through_message_id", nullable = false)
    private long coveredThroughMessageId;

    @Column(name = "source_message_count", nullable = false)
    private int sourceMessageCount;

    @Column(name = "source_char_count", nullable = false)
    private int sourceCharCount;

    @Column(name = "estimated_tokens_before", nullable = false)
    private int estimatedTokensBefore;

    @Column(name = "estimated_tokens_after", nullable = false)
    private int estimatedTokensAfter;

    @Column(name = "history_budget", nullable = false)
    private int historyBudget;

    @Column(name = "context_window_tokens", nullable = false)
    private int contextWindowTokens;

    @Column(name = "model_name", length = 120, nullable = false)
    private String modelName = "";

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash = "";

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId == null ? "" : sessionId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId == null ? 0L : agentId; }
    public String getSummaryText() { return summaryText == null ? "" : summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText == null ? "" : summaryText; }
    public long getCoveredThroughMessageId() { return coveredThroughMessageId; }
    public void setCoveredThroughMessageId(long coveredThroughMessageId) {
        this.coveredThroughMessageId = coveredThroughMessageId;
    }
    public int getSourceMessageCount() { return sourceMessageCount; }
    public void setSourceMessageCount(int sourceMessageCount) { this.sourceMessageCount = sourceMessageCount; }
    public int getSourceCharCount() { return sourceCharCount; }
    public void setSourceCharCount(int sourceCharCount) { this.sourceCharCount = sourceCharCount; }
    public int getEstimatedTokensBefore() { return estimatedTokensBefore; }
    public void setEstimatedTokensBefore(int estimatedTokensBefore) {
        this.estimatedTokensBefore = estimatedTokensBefore;
    }
    public int getEstimatedTokensAfter() { return estimatedTokensAfter; }
    public void setEstimatedTokensAfter(int estimatedTokensAfter) { this.estimatedTokensAfter = estimatedTokensAfter; }
    public int getHistoryBudget() { return historyBudget; }
    public void setHistoryBudget(int historyBudget) { this.historyBudget = historyBudget; }
    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName == null ? "" : modelName; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash == null ? "" : contentHash; }
}
