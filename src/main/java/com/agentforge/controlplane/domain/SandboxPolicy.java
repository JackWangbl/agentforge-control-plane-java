package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "sandbox_policies")
public class SandboxPolicy extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false, unique = true)
    private String name;

    @Column(name = "runtime", length = 60, nullable = false)
    private String runtime = "docker:python:3.11-slim";

    @Column(name = "cpu_limit", length = 20, nullable = false)
    private String cpuLimit = "1 vCPU";

    @Column(name = "memory_limit", length = 20, nullable = false)
    private String memoryLimit = "1 GiB";

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 60;

    @Column(name = "network_mode", length = 30, nullable = false)
    private String networkMode = "deny";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public String getCpuLimit() { return cpuLimit; }
    public void setCpuLimit(String cpuLimit) { this.cpuLimit = cpuLimit; }
    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getNetworkMode() { return networkMode; }
    public void setNetworkMode(String networkMode) { this.networkMode = networkMode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
