package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "vector_stores")
public class VectorStore extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "type", length = 40, nullable = false)
    private String type = "milvus";

    @Column(name = "uri", length = 500, nullable = false)
    private String uri = "";

    @Column(name = "database_name", length = 120, nullable = false)
    private String databaseName = "default";

    @Lob
    @Column(name = "token", columnDefinition = "text")
    private String token = "";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUri() { return uri == null ? "" : uri; }
    public void setUri(String uri) { this.uri = uri == null ? "" : uri; }
    public String getDatabaseName() { return databaseName == null || databaseName.isBlank() ? "default" : databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName == null ? "default" : databaseName; }
    public String getToken() { return token == null ? "" : token; }
    public void setToken(String token) { this.token = token == null ? "" : token; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
