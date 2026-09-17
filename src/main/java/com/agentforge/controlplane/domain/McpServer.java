package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "mcp_servers")
public class McpServer extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    /** builtin / streamable_http / sse / stdio / opencli */
    @Column(name = "transport", length = 30, nullable = false)
    private String transport;

    @Column(name = "endpoint", length = 500, nullable = false)
    private String endpoint;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "tools_count", nullable = false)
    private int toolsCount = 0;

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "config", columnDefinition = "json")
    private Map<String, Object> config = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getToolsCount() { return toolsCount; }
    public void setToolsCount(int toolsCount) { this.toolsCount = toolsCount; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) {
        this.config = config == null ? new LinkedHashMap<>() : config;
    }
}
