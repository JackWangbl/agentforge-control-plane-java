package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenants")
public class Tenant extends TimestampedEntity {

    @Column(name = "slug", length = 60, nullable = false, unique = true)
    private String slug;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "description", length = 200, nullable = false)
    private String description = "";

    @Column(name = "status", length = 24, nullable = false)
    private String status = "active";

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
