package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "knowledge_members")
public class KnowledgeMember extends TimestampedEntity {

    @Column(name = "knowledge_id", nullable = false)
    private Long knowledgeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role", length = 20, nullable = false)
    private String role = "viewer";

    @PrePersist
    void touch() {
        if (getCreatedAt() == null) {
            setCreatedAt(Instant.now());
        }
        setUpdatedAt(Instant.now());
    }

    public Long getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(Long knowledgeId) { this.knowledgeId = knowledgeId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role == null ? "" : role; }
    public void setRole(String role) { this.role = role == null ? "viewer" : role; }
}
