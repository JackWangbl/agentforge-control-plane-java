package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_configs")
public class ModelConfig extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "provider", length = 60, nullable = false)
    private String provider;

    @Column(name = "model_id", length = 160, nullable = false)
    private String modelId;

    @Column(name = "base_url", length = 500, nullable = false)
    private String baseUrl = "";

    /** 环境变量名，凭据不落库时用它去 env 里取。 */
    @Column(name = "api_key_ref", length = 160, nullable = false)
    private String apiKeyRef = "";

    @Lob
    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey = "";

    @Column(name = "temperature", nullable = false)
    private double temperature = 0.2;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** chat、embedding 或 rerank。旧数据缺列时由启动迁移补成 chat。 */
    @Column(name = "purpose", length = 20, nullable = false)
    private String purpose = "chat";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getBaseUrl() { return baseUrl == null ? "" : baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyRef() { return apiKeyRef == null ? "" : apiKeyRef; }
    public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }
    public String getApiKey() { return apiKey == null ? "" : apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPurpose() { return purpose == null || purpose.isBlank() ? "chat" : purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose == null || purpose.isBlank() ? "chat" : purpose; }
}
