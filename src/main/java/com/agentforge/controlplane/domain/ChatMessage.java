package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_messages")
public class ChatMessage extends TenantOwnedEntity {

    @Column(name = "session_id", length = 80, nullable = false)
    private String sessionId;

    @Column(name = "agent_id")
    private Long agentId;

    /** user / assistant */
    @Column(name = "role", length = 20, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "text")
    private String content = "";

    @Column(name = "agent_name", length = 80, nullable = false)
    private String agentName = "";

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content == null ? "" : content; }
    public void setContent(String content) { this.content = content; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
}
