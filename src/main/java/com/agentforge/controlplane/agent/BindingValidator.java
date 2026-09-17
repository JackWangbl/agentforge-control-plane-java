package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.OpenCliEndpoint;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.repo.McpServerRepository;
import com.agentforge.controlplane.repo.OpenCliEndpointRepository;
import com.agentforge.controlplane.repo.SandboxPolicyRepository;
import com.agentforge.controlplane.repo.SkillRepository;
import com.agentforge.controlplane.runtime.SandboxRuntime;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保存 Agent 前校验绑定资源和链路步骤里的工具名。 */
@Service
public class BindingValidator {

    private final SkillRepository skills;
    private final McpServerRepository mcps;
    private final OpenCliEndpointRepository openclis;
    private final SandboxPolicyRepository sandboxes;
    private final ToolRuntime tools;

    public BindingValidator(SkillRepository skills, McpServerRepository mcps, OpenCliEndpointRepository openclis,
                            SandboxPolicyRepository sandboxes, ToolRuntime tools) {
        this.skills = skills;
        this.mcps = mcps;
        this.openclis = openclis;
        this.sandboxes = sandboxes;
        this.tools = tools;
    }

    public void validateBindings(Long tenantId, List<Long> skillIds, List<Long> mcpIds,
                                 List<Long> opencliIds, Long sandboxId) {
        validateIds(skills.findAllById(orEmpty(skillIds)), skillIds, tenantId, "Skill");
        validateIds(mcps.findAllById(orEmpty(mcpIds)), mcpIds, tenantId, "MCP");
        validateIds(openclis.findAllById(orEmpty(opencliIds)), opencliIds, tenantId, "OpenCLI");
        if (sandboxId != null) {
            SandboxPolicy box = sandboxes.findById(sandboxId).orElse(null);
            if (box == null || box.getTenantId() == null || !box.getTenantId().equals(tenantId)) {
                throw ApiException.unprocessable("Sandbox 不存在或不属于当前租户");
            }
        }
    }

    public void validateFlowTools(Long tenantId, List<Long> mcpIds, Long sandboxId,
                                  List<Map<String, Object>> flows, Agent existing) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        List<Long> resolvedMcpIds = mcpIds != null ? mcpIds
                : existing == null ? List.of() : existing.getMcpIds();
        Long resolvedSandbox = sandboxId != null ? sandboxId
                : existing == null ? null : existing.getSandboxId();
        Set<String> available = new HashSet<>();
        if (resolvedMcpIds != null && !resolvedMcpIds.isEmpty()) {
            for (McpServer server : mcps.findAllById(resolvedMcpIds)) {
                if (server.isEnabled() && tenantId.equals(server.getTenantId())) {
                    for (Map<String, Object> tool : tools.listMcpTools(server)) {
                        available.add(String.valueOf(tool.get("name")));
                    }
                }
            }
        }
        if (resolvedSandbox != null) {
            SandboxRuntime.sandboxToolSpecs().forEach(spec -> available.add(String.valueOf(spec.get("name"))));
        }
        for (Map<String, Object> flow : flows) {
            Object stepsRaw = flow.get("steps");
            if (!(stepsRaw instanceof List<?> steps)) {
                continue;
            }
            for (Object stepObj : steps) {
                if (!(stepObj instanceof Map<?, ?> step)) {
                    continue;
                }
                String tool = String.valueOf(step.get("tool") == null ? "" : step.get("tool")).strip();
                if (!tool.isEmpty() && !available.contains(tool)) {
                    throw ApiException.unprocessable("链路 " + flow.get("name") + " 引用了未绑定的工具 " + tool);
                }
            }
        }
    }

    private static void validateIds(List<?> found, List<Long> wanted, Long tenantId, String label) {
        if (wanted == null || wanted.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>(wanted);
        Set<Long> ok = new HashSet<>();
        for (Object row : found) {
            Long id = invokeId(row);
            Long rowTenant = invokeTenant(row);
            if (id != null && tenantId.equals(rowTenant)) {
                ok.add(id);
            }
        }
        if (!ok.equals(ids)) {
            throw ApiException.unprocessable(label + " 不存在或不属于当前租户");
        }
    }

    private static Long invokeId(Object row) {
        try {
            return (Long) row.getClass().getMethod("getId").invoke(row);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long invokeTenant(Object row) {
        try {
            return (Long) row.getClass().getMethod("getTenantId").invoke(row);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Long> orEmpty(List<Long> value) {
        return value == null ? List.of() : value;
    }

    @SuppressWarnings("unused")
    private static Class<?> skillClass() {
        return Skill.class;
    }

    @SuppressWarnings("unused")
    private static Class<?> opencliClass() {
        return OpenCliEndpoint.class;
    }
}
