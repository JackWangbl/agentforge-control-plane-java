package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "experiments")
public class Experiment extends TenantOwnedEntity {

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    /** draft / running / paused / completed */
    @Column(name = "status", length = 24, nullable = false)
    private String status = "draft";

    /** user / session / request */
    @Column(name = "assignment_unit", length = 24, nullable = false)
    private String assignmentUnit = "session";

    /** user_hash / session_hash / user_first / random */
    @Column(name = "assignment_strategy", length = 32, nullable = false)
    private String assignmentStrategy = "session_hash";

    @Column(name = "traffic_percent", nullable = false)
    private int trafficPercent = 100;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 最近一次对比的快照，没跑过就是 null。 */
    @Convert(converter = JsonConverters.NullableMapConverter.class)
    @Column(name = "last_compare", columnDefinition = "json")
    private Map<String, Object> lastCompare;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAssignmentUnit() { return assignmentUnit; }
    public void setAssignmentUnit(String assignmentUnit) { this.assignmentUnit = assignmentUnit; }
    public String getAssignmentStrategy() { return assignmentStrategy; }
    public void setAssignmentStrategy(String assignmentStrategy) { this.assignmentStrategy = assignmentStrategy; }
    public int getTrafficPercent() { return trafficPercent; }
    public void setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Map<String, Object> getLastCompare() { return lastCompare; }
    public void setLastCompare(Map<String, Object> lastCompare) { this.lastCompare = lastCompare; }
}
