package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "workflows")
public class Workflow extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    @Column(name = "status", length = 24, nullable = false)
    private String status = "draft";

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "graph", columnDefinition = "json")
    private Map<String, Object> graph = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getGraph() { return graph; }
    public void setGraph(Map<String, Object> graph) {
        this.graph = graph == null ? new LinkedHashMap<>() : graph;
    }
}
