package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversations")
public class Conversation extends TenantOwnedEntity {

    @Column(name = "session_id", length = 80, nullable = false, unique = true)
    private String sessionId;

    @Column(name = "user_id", length = 80, nullable = false)
    private String userId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_name", length = 80, nullable = false)
    private String agentName;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "completed";

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens = 0;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs = 0;

    /** API / Playground / WeCom 等入口标识。 */
    @Column(name = "channel", length = 32, nullable = false)
    private String channel = "API";

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
}
