package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "datasets")
public class Dataset extends TenantOwnedEntity {

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    @Column(name = "source_name", length = 255, nullable = false)
    private String sourceName = "";

    /** redteam / baseline / golden */
    @Column(name = "kind", length = 24, nullable = false)
    private String kind = "baseline";

    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "agent_ids", columnDefinition = "json")
    private List<Long> agentIds = new ArrayList<>();

    @Column(name = "case_count", nullable = false)
    private int caseCount = 0;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public List<Long> getAgentIds() { return agentIds; }
    public void setAgentIds(List<Long> agentIds) { this.agentIds = agentIds == null ? new ArrayList<>() : agentIds; }
    public int getCaseCount() { return caseCount; }
    public void setCaseCount(int caseCount) { this.caseCount = caseCount; }
}
