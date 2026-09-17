package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "roles")
public class Role extends TenantOwnedEntity {

    @Column(name = "name", length = 60, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 200, nullable = false)
    private String description = "";

    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(name = "permissions", columnDefinition = "json")
    private List<String> permissions = new ArrayList<>();

    @Column(name = "user_count", nullable = false)
    private int userCount = 0;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }
    public int getUserCount() { return userCount; }
    public void setUserCount(int userCount) { this.userCount = userCount; }
}
