package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "skills")
public class Skill extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    @Column(name = "source", length = 500, nullable = false)
    private String source = "manual";

    @Column(name = "version", length = 20, nullable = false)
    private String version = "1.0.0";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Lob
    @Column(name = "instruction", columnDefinition = "text")
    private String instruction = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getInstruction() { return instruction == null ? "" : instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
}
