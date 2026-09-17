package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

/** 其他平台 Agent 的 HTTP 对话接口。绑定到本平台 Agent 后，运行时按接口协议直接 POST 调用。 */
@Entity
@Table(name = "http_agents")
public class HttpAgent extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    /** generic / openai / agentforge / dify */
    @Column(name = "protocol", length = 40, nullable = false)
    private String protocol = "generic";

    @Column(name = "endpoint", length = 500, nullable = false)
    private String endpoint = "";

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "headers", columnDefinition = "json")
    private Map<String, Object> headers = new LinkedHashMap<>();

    @Column(name = "input_field", length = 80, nullable = false)
    private String inputField = "";

    @Column(name = "output_path", length = 120, nullable = false)
    private String outputPath = "";

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "config", columnDefinition = "json")
    private Map<String, Object> config = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Map<String, Object> getHeaders() { return headers; }
    public void setHeaders(Map<String, Object> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
    }
    public String getInputField() { return inputField; }
    public void setInputField(String inputField) { this.inputField = inputField == null ? "" : inputField; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath == null ? "" : outputPath; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) {
        this.config = config == null ? new LinkedHashMap<>() : config;
    }
}
