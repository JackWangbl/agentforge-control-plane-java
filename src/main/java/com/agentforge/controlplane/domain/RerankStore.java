package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "rerank_stores")
public class RerankStore extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "type", length = 40, nullable = false)
    private String type = "http";

    @Column(name = "base_url", length = 500, nullable = false)
    private String baseUrl = "";

    @Column(name = "model_id", length = 160, nullable = false)
    private String modelId = "";

    @Lob
    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey = "";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getBaseUrl() { return baseUrl == null ? "" : baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl; }
    public String getModelId() { return modelId == null ? "" : modelId; }
    public void setModelId(String modelId) { this.modelId = modelId == null ? "" : modelId; }
    public String getApiKey() { return apiKey == null ? "" : apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
