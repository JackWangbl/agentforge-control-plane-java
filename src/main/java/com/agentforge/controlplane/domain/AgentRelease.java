package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 智能体发布时抄下来的一份不能再改的内容：提示词、Skill 正文、工具清单和工具链路。 */
@Entity
@Table(name = "agent_releases")
public class AgentRelease extends TimestampedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint = "";

    @Lob
    @Column(name = "snapshot_json", columnDefinition = "longtext", nullable = false)
    private String snapshotJson = "{}";

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public String getFingerprint() { return fingerprint == null ? "" : fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint == null ? "" : fingerprint; }
    public String getSnapshotJson() { return snapshotJson == null ? "{}" : snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson == null ? "{}" : snapshotJson; }
}
