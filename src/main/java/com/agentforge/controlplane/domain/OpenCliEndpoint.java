package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 历史表。启动时 migrateOpenCliIntoMcp 会把这里的记录搬到 mcp_servers（transport=opencli），
 * 保留实体只为了能读到还没迁走的旧数据。
 */
@Entity
@Table(name = "opencli_endpoints")
public class OpenCliEndpoint extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "kind", length = 24, nullable = false)
    private String kind = "cdp";

    @Column(name = "endpoint", length = 500, nullable = false)
    private String endpoint;

    @Column(name = "target", length = 200, nullable = false)
    private String target = "";

    @Column(name = "session", length = 80, nullable = false)
    private String session = "agentforge";

    @Lob
    @Column(name = "token", columnDefinition = "text")
    private String token = "";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_status", length = 40, nullable = false)
    private String lastStatus = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }
    public String getToken() { return token == null ? "" : token; }
    public void setToken(String token) { this.token = token; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
}
