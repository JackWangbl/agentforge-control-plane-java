package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "agents")
public class Agent extends TenantOwnedEntity {

    @Column(name = "name", length = 80, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 255, nullable = false)
    private String description = "";

    @Column(name = "model_name", length = 120, nullable = false)
    private String modelName;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "published";

    @Column(name = "version", length = 20, nullable = false)
    private String version = "v1.0.0";

    @Lob
    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt = "";

    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "skill_ids", columnDefinition = "json")
    private List<Long> skillIds = new ArrayList<>();

    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "mcp_ids", columnDefinition = "json")
    private List<Long> mcpIds = new ArrayList<>();

    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "opencli_ids", columnDefinition = "json")
    private List<Long> opencliIds = new ArrayList<>();

    /** 绑定的外部 HTTP Agent 接口：运行时直接 POST 到对方平台。 */
    @Convert(converter = JsonConverters.LongListConverter.class)
    @Column(name = "http_agent_ids", columnDefinition = "json")
    private List<Long> httpAgentIds = new ArrayList<>();

    /** 工具链路定义：按顺序串起多个工具，对模型只暴露一个 flow_xxx 工具。 */
    @Convert(converter = JsonConverters.MapListConverter.class)
    @Column(name = "tool_flows", columnDefinition = "json")
    private List<Map<String, Object>> toolFlows = new ArrayList<>();

    @Column(name = "sandbox_id")
    private Long sandboxId;

    @Column(name = "workspace", length = 255, nullable = false)
    private String workspace = "";

    @Column(name = "success_rate", nullable = false)
    private double successRate = 0;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSystemPrompt() { return systemPrompt == null ? "" : systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public List<Long> getSkillIds() { return skillIds; }
    public void setSkillIds(List<Long> skillIds) { this.skillIds = skillIds == null ? new ArrayList<>() : skillIds; }
    public List<Long> getMcpIds() { return mcpIds; }
    public void setMcpIds(List<Long> mcpIds) { this.mcpIds = mcpIds == null ? new ArrayList<>() : mcpIds; }
    public List<Long> getOpencliIds() { return opencliIds; }
    public void setOpencliIds(List<Long> v) { this.opencliIds = v == null ? new ArrayList<>() : v; }
    public List<Long> getHttpAgentIds() { return httpAgentIds; }
    public void setHttpAgentIds(List<Long> httpAgentIds) {
        this.httpAgentIds = httpAgentIds == null ? new ArrayList<>() : httpAgentIds;
    }
    public List<Map<String, Object>> getToolFlows() { return toolFlows; }
    public void setToolFlows(List<Map<String, Object>> v) { this.toolFlows = v == null ? new ArrayList<>() : v; }
    public Long getSandboxId() { return sandboxId; }
    public void setSandboxId(Long sandboxId) { this.sandboxId = sandboxId; }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
}
