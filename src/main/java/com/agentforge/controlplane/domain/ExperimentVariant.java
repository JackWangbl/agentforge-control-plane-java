package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "experiment_variants")
public class ExperimentVariant extends TenantOwnedEntity {

    @Column(name = "experiment_id", nullable = false)
    private Long experimentId;

    @Column(name = "`key`", length = 16, nullable = false)
    private String key = "A";

    @Column(name = "name", length = 80, nullable = false)
    private String name = "";

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "weight", nullable = false)
    private int weight = 50;

    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
}
