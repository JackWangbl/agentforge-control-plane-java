package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** 一条长期记忆。只属于一个租户里的一个身份。 */
@Entity
@Table(name = "memory_items")
public class MemoryItem extends TimestampedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "subject_key", length = 120, nullable = false)
    private String subjectKey;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(length = 500, nullable = false)
    private String content;

    @Column(length = 24, nullable = false)
    private String kind = "preference";

    @Column(length = 16, nullable = false)
    private String source = "user";

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "session_id", length = 80)
    private String sessionId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getSubjectKey() { return subjectKey; }
    public void setSubjectKey(String subjectKey) { this.subjectKey = subjectKey; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getContent() { return content == null ? "" : content; }
    public void setContent(String content) { this.content = content; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
