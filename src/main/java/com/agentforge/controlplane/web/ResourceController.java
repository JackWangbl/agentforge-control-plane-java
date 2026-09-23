package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.Permissions;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.agent.BindingValidator;
import com.agentforge.controlplane.agent.BoundAgentRuntime;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.agent.HttpAgentRuntime;
import com.agentforge.controlplane.agent.ToolRuntime;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.domain.Workflow;
import com.agentforge.controlplane.dto.ApiDtos;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.HttpAgentRepository;
import com.agentforge.controlplane.repo.McpServerRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.RoleRepository;
import com.agentforge.controlplane.repo.SandboxPolicyRepository;
import com.agentforge.controlplane.repo.SkillRepository;
import com.agentforge.controlplane.repo.WorkflowRepository;
import com.agentforge.controlplane.runtime.McpStreamClient;
import com.agentforge.controlplane.runtime.OpenCliRuntime;
import com.agentforge.controlplane.runtime.SandboxRuntime;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class ResourceController {

    private static final Set<String> LISTABLE = Set.of(
            "agents", "http-agents", "mcp", "skills", "models", "workflows", "sandboxes", "roles", "traces");
    private static final Set<String> UPDATABLE = Set.of(
            "agents", "http-agents", "mcp", "skills", "models", "workflows", "sandboxes", "roles");
    private static final Set<String> STATUSABLE = Set.of("mcp", "skills", "models", "sandboxes", "http-agents");

    private final ResourceAccessService access;
    private final ResourceDumper dumper;
    private final AgentRepository agents;
    private final HttpAgentRepository httpAgents;
    private final McpServerRepository mcps;
    private final SkillRepository skills;
    private final ModelConfigRepository models;
    private final WorkflowRepository workflows;
    private final SandboxPolicyRepository sandboxes;
    private final RoleRepository roles;
    private final ToolRuntime tools;
    private final BindingValidator bindings;
    private final BoundAgentRuntime boundAgents;
    private final HttpAgentRuntime httpAgentRuntime;
    private final WorkspaceStore workspaces;
    private final SandboxRuntime sandbox;
    private final OpenCliRuntime opencli;
    private final McpStreamClient mcpStream;

    public ResourceController(ResourceAccessService access, ResourceDumper dumper, AgentRepository agents,
                              HttpAgentRepository httpAgents, McpServerRepository mcps, SkillRepository skills,
                              ModelConfigRepository models,
                              WorkflowRepository workflows, SandboxPolicyRepository sandboxes, RoleRepository roles,
                              ToolRuntime tools, BindingValidator bindings, BoundAgentRuntime boundAgents,
                              HttpAgentRuntime httpAgentRuntime,
                              WorkspaceStore workspaces,
                              SandboxRuntime sandbox, OpenCliRuntime opencli, McpStreamClient mcpStream) {
        this.access = access;
        this.dumper = dumper;
        this.agents = agents;
        this.httpAgents = httpAgents;
        this.mcps = mcps;
        this.skills = skills;
        this.models = models;
        this.workflows = workflows;
        this.sandboxes = sandboxes;
        this.roles = roles;
        this.tools = tools;
        this.bindings = bindings;
        this.boundAgents = boundAgents;
        this.httpAgentRuntime = httpAgentRuntime;
        this.workspaces = workspaces;
        this.sandbox = sandbox;
        this.opencli = opencli;
        this.mcpStream = mcpStream;
    }

    @GetMapping("/api/{resource}")
    public List<Map<String, Object>> list(CurrentUser user, @PathVariable String resource) {
        if (!LISTABLE.contains(resource)) {
            throw ApiException.notFound("Unknown resource");
        }
        return dumper.dumpAll(access.listRows(user, Permissions.kindOf(resource)));
    }

    @RequirePermission("mcp:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/mcp")
    public Map<String, Object> createMcp(CurrentUser user, @Valid @RequestBody ApiDtos.McpCreate payload) {
        McpServer row = new McpServer();
        row.setName(payload.name());
        row.setTransport(McpStreamClient.normalizeMcpTransport(payload.transport()));
        row.setEndpoint(payload.endpoint());
        row.setEnabled(payload.enabled());
        row.setConfig(McpStreamClient.mergeMcpConfig(Map.of(), payload.config()));
        if (McpStreamClient.isOpencliTransport(row.getTransport())) {
            try {
                row.setConfig(OpenCliRuntime.applyOpencliConfig(row.getConfig(), row.getEndpoint()));
                row.setEndpoint(Jsons.text(row.getConfig().getOrDefault("endpoint", row.getEndpoint())).strip());
            } catch (ApiException | IllegalArgumentException e) {
                throw ApiException.unprocessable(e.getMessage());
            }
        }
        Object toolsCfg = row.getConfig().get("tools");
        row.setToolsCount(toolsCfg instanceof List<?> list ? list.size() : 0);
        access.stampOwner(row, user);
        if (ToolRuntime.isBuiltinMcp(row)) {
            List<Map<String, Object>> listed = tools.listMcpTools(row);
            row.setToolsCount(listed.size());
            Map<String, Object> cfg = new LinkedHashMap<>(row.getConfig());
            cfg.put("kind", "builtin");
            cfg.put("tools", listed);
            row.setConfig(cfg);
        }
        if (McpStreamClient.isOpencliTransport(row.getTransport())) {
            row.setToolsCount(tools.listMcpTools(row).size());
        }
        mcps.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("skill:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/skills")
    public Map<String, Object> createSkill(CurrentUser user, @Valid @RequestBody ApiDtos.SkillCreate payload) {
        Skill row = new Skill();
        row.setName(payload.name());
        row.setDescription(payload.description());
        row.setSource(payload.source());
        row.setVersion(payload.version());
        row.setInstruction(payload.instruction());
        row.setEnabled(payload.enabled());
        access.stampOwner(row, user);
        tools.persistSkillMarkdown(row);
        skills.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("model:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/models")
    public Map<String, Object> createModel(CurrentUser user, @Valid @RequestBody ApiDtos.ModelCreate payload) {
        ModelConfig row = new ModelConfig();
        row.setName(payload.name());
        row.setProvider(payload.provider());
        row.setModelId(payload.model_id());
        row.setBaseUrl(payload.base_url());
        row.setApiKey(payload.api_key());
        row.setApiKeyRef(payload.api_key_ref());
        row.setTemperature(payload.temperature());
        row.setEnabled(payload.enabled());
        row.setPurpose(normalizePurpose(payload.purpose()));
        access.stampOwner(row, user);
        models.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("model:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/models/{modelId}/copy")
    public Map<String, Object> copyModel(CurrentUser user, @PathVariable Long modelId) {
        ModelConfig source = access.getRow(user, ResourceKind.CREDENTIAL, modelId);
        ModelConfig row = new ModelConfig();
        row.setName(uniqueModelCopyName(source.getName()));
        row.setProvider(source.getProvider());
        row.setModelId(source.getModelId());
        row.setBaseUrl(source.getBaseUrl());
        row.setApiKey(source.getApiKey());
        row.setApiKeyRef(source.getApiKeyRef());
        row.setTemperature(source.getTemperature());
        row.setEnabled(source.isEnabled());
        row.setPurpose(source.getPurpose());
        access.stampOwner(row, user);
        models.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("workflow:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/workflows")
    public Map<String, Object> createWorkflow(CurrentUser user, @Valid @RequestBody ApiDtos.WorkflowCreate payload) {
        Workflow row = new Workflow();
        row.setName(payload.name());
        row.setDescription(payload.description());
        row.setStatus(payload.status());
        row.setGraph(payload.graph());
        access.stampOwner(row, user);
        workflows.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("agent:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/agents")
    public Map<String, Object> createAgent(CurrentUser user, @Valid @RequestBody ApiDtos.AgentCreate payload) {
        requireUniqueAgentName(payload.name(), null);
        bindings.validateBindings(user.getTenantId(), payload.skill_ids(), payload.mcp_ids(),
                payload.opencli_ids(), payload.sandbox_id(), payload.http_agent_ids());
        bindings.validateKnowledge(user, payload.knowledge_ids(), List.of(), payload.http_agent_ids());
        bindings.validateFlowTools(user.getTenantId(), payload.mcp_ids(), payload.sandbox_id(),
                payload.http_agent_ids(), payload.tool_flows(), null);
        Agent row = new Agent();
        row.setName(payload.name().strip());
        row.setDescription(payload.description());
        row.setModelName(payload.model_name());
        row.setStatus(payload.status());
        row.setVersion(payload.version());
        row.setSystemPrompt(payload.system_prompt());
        row.setSkillIds(payload.skill_ids());
        row.setMcpIds(payload.mcp_ids());
        row.setOpencliIds(payload.opencli_ids());
        row.setHttpAgentIds(payload.http_agent_ids());
        row.setKnowledgeIds(payload.knowledge_ids());
        row.setToolFlows(payload.tool_flows());
        row.setSandboxId(payload.sandbox_id());
        applyHttpProxyMode(row);
        access.stampOwner(row, user);
        agents.save(row);
        workspaces.ensureWorkspace(row);
        agents.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("agent:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/http-agents")
    public Map<String, Object> createHttpAgent(CurrentUser user, @Valid @RequestBody ApiDtos.HttpAgentCreate payload) {
        HttpAgentRuntime.validateEndpoint(payload.endpoint());
        if (httpAgents.existsByName(payload.name().strip())) {
            throw ApiException.conflict("HTTP 接口名称已存在");
        }
        HttpAgent row = new HttpAgent();
        row.setName(payload.name().strip());
        row.setDescription(payload.description());
        row.setProtocol(HttpAgentRuntime.normalizeProtocol(payload.protocol()));
        row.setEndpoint(payload.endpoint().strip());
        row.setHeaders(HttpAgentRuntime.mergeHeaders(Map.of(), payload.headers()));
        row.setInputField(payload.input_field().strip());
        row.setOutputPath(payload.output_path().strip());
        row.setTimeoutSeconds(HttpAgentRuntime.clampTimeout(payload.timeout_seconds()));
        row.setEnabled(payload.enabled());
        row.setConfig(payload.config() == null ? Map.of() : new LinkedHashMap<>(payload.config()));
        access.stampOwner(row, user);
        httpAgents.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("agent:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/agents/{itemId}/copy")
    public Map<String, Object> copyAgent(CurrentUser user, @PathVariable Long itemId,
                                         @RequestBody(required = false) ApiDtos.AgentCopy payload) {
        Agent source = access.getRow(user, ResourceKind.AGENT, itemId);
        String requested = payload == null ? null : payload.name();
        Agent row = new Agent();
        row.setName(uniqueCopyName(source.getName(), requested));
        row.setDescription(source.getDescription());
        row.setModelName(source.getModelName());
        row.setStatus("draft");
        row.setVersion(source.getVersion());
        row.setSystemPrompt(source.getSystemPrompt());
        row.setSkillIds(new ArrayList<>(source.getSkillIds()));
        row.setMcpIds(new ArrayList<>(source.getMcpIds()));
        row.setOpencliIds(new ArrayList<>(source.getOpencliIds()));
        row.setHttpAgentIds(new ArrayList<>(source.getHttpAgentIds()));
        row.setKnowledgeIds(new ArrayList<>(bindings.retainVisibleKnowledge(user, source.getKnowledgeIds())));
        row.setToolFlows(new ArrayList<>(source.getToolFlows()));
        row.setSandboxId(source.getSandboxId());
        row.setWorkspace("");
        row.setSuccessRate(0);
        applyHttpProxyMode(row);
        access.stampOwner(row, user);
        agents.save(row);
        workspaces.ensureWorkspace(row);
        agents.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("agent:write")
    @PostMapping("/api/agents/{itemId}/rename")
    public Map<String, Object> renameAgent(CurrentUser user, @PathVariable Long itemId,
                                           @Valid @RequestBody ApiDtos.AgentRename payload) {
        Agent row = access.resolveForEdit(user, ResourceKind.AGENT, itemId);
        row.setName(requireUniqueAgentName(payload.name(), itemId));
        workspaces.ensureWorkspace(row);
        agents.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("agent:read")
    @GetMapping("/api/agents/{itemId}/interface")
    public Map<String, Object> agentInterface(CurrentUser user, @PathVariable Long itemId) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, itemId);
        return boundAgents.interfaceCard(agent);
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping("/api/agents/{itemId}/invoke")
    public Map<String, Object> invokeAgent(CurrentUser user, @PathVariable Long itemId,
                                           @Valid @RequestBody ApiDtos.AgentInvoke payload) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, itemId);
        ChatReply reply = boundAgents.invoke(agent, payload.message(), payload.session_id());
        return Jsons.ordered(
                "agent", agent.getName(),
                "agent_id", agent.getId(),
                "reply", reply.reply(),
                "mode", reply.mode(),
                "trace_id", reply.traceId(),
                "session_id", payload.session_id().isBlank() ? "" : payload.session_id(),
                "usage", reply.usage());
    }

    @RequirePermission("agent:read")
    @GetMapping("/api/agents/{itemId}/workspace")
    public Map<String, Object> workspace(CurrentUser user, @PathVariable Long itemId) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, itemId);
        Map<String, Object> status = workspaces.workspaceStatus(agent);
        agents.save(agent);
        return status;
    }

    @RequirePermission("agent:read")
    @GetMapping("/api/agents/{itemId}/workspace/sessions/{sessionId}")
    public Map<String, Object> workspaceSession(CurrentUser user, @PathVariable Long itemId,
                                                @PathVariable String sessionId) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, itemId);
        workspaces.ensureWorkspace(agent);
        Map<String, Object> data = workspaces.loadSession(agent, sessionId);
        if (data == null) {
            throw ApiException.notFound("Session not found in agent workspace");
        }
        return data;
    }

    @RequirePermission("sandbox:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/sandboxes")
    public Map<String, Object> createSandbox(CurrentUser user, @Valid @RequestBody ApiDtos.SandboxCreate payload) {
        SandboxPolicy row = new SandboxPolicy();
        row.setName(payload.name());
        row.setRuntime(payload.runtime());
        row.setCpuLimit(payload.cpu_limit());
        row.setMemoryLimit(payload.memory_limit());
        row.setTimeoutSeconds(payload.timeout_seconds());
        row.setNetworkMode(payload.network_mode());
        row.setEnabled(payload.enabled());
        access.stampOwner(row, user);
        sandboxes.save(row);
        return dumper.dump(row, user);
    }

    @RequirePermission("role:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/roles")
    public Map<String, Object> createRole(CurrentUser user, @Valid @RequestBody ApiDtos.RoleCreate payload) {
        Role row = new Role();
        row.setName(payload.name());
        row.setDescription(payload.description());
        row.setPermissions(payload.permissions());
        row.setUserCount(0);
        access.stampOwner(row, user);
        roles.save(row);
        return dumper.dump(row, user);
    }

    @DeleteMapping("/api/{resource}/{itemId}")
    public Map<String, Object> delete(CurrentUser user, @PathVariable String resource, @PathVariable Long itemId) {
        if (!UPDATABLE.contains(resource)) {
            throw ApiException.notFound("Unknown resource");
        }
        Object row = access.resolveForEdit(user, Permissions.kindOf(resource), itemId);
        if (row instanceof Agent agent) {
            workspaces.removeWorkspace(agent);
        }
        switch (resource) {
            case "agents" -> agents.deleteById(itemId);
            case "http-agents" -> httpAgents.deleteById(itemId);
            case "mcp" -> mcps.deleteById(itemId);
            case "skills" -> skills.deleteById(itemId);
            case "models" -> models.deleteById(itemId);
            case "workflows" -> workflows.deleteById(itemId);
            case "sandboxes" -> sandboxes.deleteById(itemId);
            case "roles" -> roles.deleteById(itemId);
            default -> throw ApiException.notFound("Unknown resource");
        }
        return Map.of("id", itemId, "deleted", true);
    }

    @PutMapping("/api/{resource}/{itemId}")
    public Map<String, Object> update(CurrentUser user, @PathVariable String resource, @PathVariable Long itemId,
                                      @RequestBody Map<String, Object> payload) {
        if (!UPDATABLE.contains(resource)) {
            throw ApiException.notFound("Unknown resource");
        }
        Object row = access.resolveForEdit(user, Permissions.kindOf(resource), itemId);
        applyUpdate(user, resource, row, payload);
        persist(resource, row);
        if (row instanceof Skill skill) {
            tools.persistSkillMarkdown(skill);
            skills.save(skill);
        }
        if (row instanceof Agent agent) {
            workspaces.ensureWorkspace(agent);
            agents.save(agent);
        }
        return dumper.dump(row, user);
    }

    @PatchMapping("/api/{resource}/{itemId}/status")
    public Map<String, Object> updateStatus(CurrentUser user, @PathVariable String resource, @PathVariable Long itemId,
                                            @Valid @RequestBody ApiDtos.ResourceStatusUpdate payload) {
        if (!STATUSABLE.contains(resource)) {
            throw ApiException.notFound("Resource does not support status changes");
        }
        Object row = access.resolveForEdit(user, Permissions.kindOf(resource), itemId);
        if (row instanceof McpServer mcp) {
            mcp.setEnabled(payload.enabled());
            mcps.save(mcp);
        } else if (row instanceof Skill skill) {
            skill.setEnabled(payload.enabled());
            skills.save(skill);
        } else if (row instanceof ModelConfig model) {
            model.setEnabled(payload.enabled());
            models.save(model);
        } else if (row instanceof SandboxPolicy box) {
            box.setEnabled(payload.enabled());
            sandboxes.save(box);
        } else if (row instanceof HttpAgent httpAgent) {
            httpAgent.setEnabled(payload.enabled());
            httpAgents.save(httpAgent);
        }
        return dumper.dump(row, user);
    }

    @RequirePermission("sandbox:read")
    @PostMapping("/api/sandboxes/{itemId}/test")
    public Map<String, Object> testSandbox(CurrentUser user, @PathVariable Long itemId) {
        SandboxPolicy row = access.getRow(user, ResourceKind.SANDBOX, itemId);
        if (!row.isEnabled()) {
            throw ApiException.conflict("Sandbox is disabled");
        }
        Map<String, Object> probed = sandbox.probeSandbox(row);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("name", row.getName());
        data.putAll(probed);
        return data;
    }

    @RequirePermission("agent:read")
    @PostMapping("/api/http-agents/{itemId}/test")
    public Map<String, Object> testHttpAgent(CurrentUser user, @PathVariable Long itemId) {
        HttpAgent row = access.getRow(user, ResourceKind.HTTP_AGENT, itemId);
        return httpAgentRuntime.probe(row);
    }

    @RequirePermission("mcp:read")
    @PostMapping("/api/mcp/{itemId}/test")
    public Map<String, Object> testMcp(CurrentUser user, @PathVariable Long itemId) {
        McpServer row = access.getRow(user, ResourceKind.MCP, itemId);
        if (!row.isEnabled()) {
            throw ApiException.conflict("MCP server is disabled");
        }
        if (McpStreamClient.isOpencliTransport(row.getTransport())) {
            try {
                Map<String, Object> probed = opencli.probeOpencli(OpenCliRuntime.fromMcp(row));
                row.setConfig(OpenCliRuntime.applyOpencliConfig(row.getConfig(), row.getEndpoint()));
                row.setToolsCount(tools.listMcpTools(row).size());
                mcps.save(row);
                Object tabs = probed.get("tabs");
                String extra = tabs instanceof Number n ? "，已发现 " + n.intValue() + " 个标签" : "";
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", row.getId());
                data.put("ready", true);
                data.put("status", "ready");
                data.put("tools", tools.listMcpTools(row));
                data.put("message", row.getName() + " 已连通 "
                        + (probed.get("browser") == null
                        ? OpenCliRuntime.kindLabel(Jsons.text(row.getConfig().get("kind")))
                        : probed.get("browser")) + extra);
                probed.forEach((key, value) -> {
                    if (!"detail".equals(key)) {
                        data.putIfAbsent(key, value);
                    }
                });
                return data;
            } catch (Exception e) {
                return Jsons.ordered("id", row.getId(), "ready", false, "status", "unreachable",
                        "tools", tools.listMcpTools(row), "message", row.getName() + " 探测失败：" + e.getMessage());
            }
        }
        if (McpStreamClient.isHttpStreamTransport(row.getTransport()) && !ToolRuntime.isBuiltinMcp(row)) {
            try {
                Map<String, Object> probed = mcpStream.probeStreamableHttp(row);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> discovered = probed.get("tools") instanceof List<?> list
                        ? (List<Map<String, Object>>) list : List.of();
                McpStreamClient.applyDiscoveredTools(row, discovered);
                mcps.save(row);
                return Jsons.ordered("id", row.getId(), "ready", true, "status", "ready",
                        "tools", tools.listMcpTools(row), "message", probed.get("message"));
            } catch (Exception e) {
                return Jsons.ordered("id", row.getId(), "ready", false, "status", "unreachable",
                        "tools", tools.listMcpTools(row),
                        "message", row.getName() + " HTTP Stream 探测失败：" + e.getMessage());
            }
        }
        List<Map<String, Object>> listed = tools.listMcpTools(row);
        if (!ToolRuntime.isBuiltinMcp(row)) {
            return Jsons.ordered("id", row.getId(), "ready", false, "status", "not_runnable", "tools", listed,
                    "message", row.getName() + " 当前仅 StdIO 内置、HTTP Stream 或 OpenCLI 可直接探测。");
        }
        String sample = tools.executeTool("get_current_time", Map.of(), null);
        row.setToolsCount(listed.size());
        Map<String, Object> cfg = new LinkedHashMap<>(row.getConfig() == null ? Map.of() : row.getConfig());
        cfg.put("kind", "builtin");
        cfg.put("tools", listed);
        row.setConfig(cfg);
        mcps.save(row);
        return Jsons.ordered("id", row.getId(), "ready", true, "status", "ready", "tools", listed, "sample", sample,
                "message", row.getName() + " 已连通，可用 " + listed.size() + " 个工具。" + sample);
    }

    @RequirePermission("mcp:read")
    @PostMapping("/api/mcp/{itemId}/query")
    public Map<String, Object> queryMcp(CurrentUser user, @PathVariable Long itemId,
                                        @Valid @RequestBody ApiDtos.OpenCliQuery payload) {
        McpServer row = access.getRow(user, ResourceKind.MCP, itemId);
        if (!row.isEnabled()) {
            throw ApiException.conflict("MCP 已停用");
        }
        if (!McpStreamClient.isOpencliTransport(row.getTransport())) {
            throw ApiException.unprocessable("该 MCP 不是 OpenCLI 类型");
        }
        try {
            Map<String, Object> result = opencli.queryBrowser(OpenCliRuntime.fromMcp(row), payload.action(),
                    payload.target(), payload.selector(), payload.expression(), payload.command());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", row.getId());
            data.put("name", row.getName());
            data.putAll(result);
            return data;
        } catch (Exception e) {
            throw ApiException.badGateway("查询浏览器失败：" + e.getMessage());
        }
    }

    @RequirePermission("skill:read")
    @PostMapping("/api/skills/{itemId}/test")
    public Map<String, Object> testSkill(CurrentUser user, @PathVariable Long itemId) {
        Skill row = access.getRow(user, ResourceKind.SKILL, itemId);
        if (!row.isEnabled()) {
            throw ApiException.conflict("Skill is disabled");
        }
        String instruction = tools.skillInstruction(row);
        if (instruction.isBlank()) {
            return Jsons.ordered("id", row.getId(), "ready", false, "status", "empty",
                    "message", row.getName() + " 没有可执行指令，请补充 Skill 正文。");
        }
        return Jsons.ordered("id", row.getId(), "ready", true, "status", "ready", "instruction", instruction,
                "message", row.getName() + " 已就绪，调试台会把它写入 Agent 指令。");
    }

    @RequirePermission("model:read")
    @PostMapping("/api/models/{modelId}/test")
    public Map<String, Object> testModel(CurrentUser user, @PathVariable Long modelId) {
        ModelConfig model = access.getRow(user, ResourceKind.CREDENTIAL, modelId);
        if (!model.isEnabled()) {
            throw ApiException.conflict("Model config is disabled");
        }
        boolean ready = AgentScopeRuntime.modelHasCredential(model);
        return Jsons.ordered("id", model.getId(), "ready", ready,
                "status", ready ? "ready" : "missing_credential",
                "message", ready
                        ? model.getName() + " 配置检查通过，可以进入调试台。"
                        : model.getName() + " 尚未配置 API 密钥，请在模型配置中直接填写密钥。");
    }

    private void applyUpdate(CurrentUser user, String resource, Object row, Map<String, Object> payload) {
        switch (resource) {
            case "agents" -> applyAgent(user, (Agent) row, payload);
            case "http-agents" -> applyHttpAgent((HttpAgent) row, payload);
            case "mcp" -> applyMcp((McpServer) row, payload);
            case "skills" -> applySkill((Skill) row, payload);
            case "models" -> applyModel((ModelConfig) row, payload);
            case "workflows" -> applyWorkflow((Workflow) row, payload);
            case "sandboxes" -> applySandbox((SandboxPolicy) row, payload);
            case "roles" -> applyRole((Role) row, payload);
            default -> {
            }
        }
    }

    private void applyAgent(CurrentUser user, Agent row, Map<String, Object> payload) {
        if (payload.containsKey("name") && payload.get("name") != null) {
            row.setName(requireUniqueAgentName(Jsons.text(payload.get("name")), row.getId()));
        }
        if (payload.containsKey("description")) {
            row.setDescription(Jsons.text(payload.get("description")));
        }
        if (payload.containsKey("model_name") && payload.get("model_name") != null) {
            row.setModelName(Jsons.text(payload.get("model_name")));
        }
        if (payload.containsKey("status")) {
            row.setStatus(Jsons.text(payload.get("status")));
        }
        if (payload.containsKey("version")) {
            row.setVersion(Jsons.text(payload.get("version")));
        }
        if (payload.containsKey("system_prompt")) {
            row.setSystemPrompt(Jsons.text(payload.get("system_prompt")));
        }
        if (payload.containsKey("skill_ids")) {
            row.setSkillIds(Jsons.longList(payload.get("skill_ids")));
        }
        if (payload.containsKey("mcp_ids")) {
            row.setMcpIds(Jsons.longList(payload.get("mcp_ids")));
        }
        if (payload.containsKey("opencli_ids")) {
            row.setOpencliIds(Jsons.longList(payload.get("opencli_ids")));
        }
        if (payload.containsKey("http_agent_ids")) {
            row.setHttpAgentIds(Jsons.longList(payload.get("http_agent_ids")));
        }
        List<Long> previousKnowledge = row.getKnowledgeIds() == null
                ? List.of() : new ArrayList<>(row.getKnowledgeIds());
        if (payload.containsKey("knowledge_ids")) {
            row.setKnowledgeIds(Jsons.longList(payload.get("knowledge_ids")));
        }
        if (payload.containsKey("tool_flows") && payload.get("tool_flows") instanceof List<?>) {
            row.setToolFlows(Jsons.mapList(payload.get("tool_flows")));
        }
        if (payload.containsKey("sandbox_id")) {
            row.setSandboxId(Jsons.asLong(payload.get("sandbox_id")));
        }
        bindings.validateBindings(user.getTenantId(),
                payload.containsKey("skill_ids") ? row.getSkillIds() : null,
                payload.containsKey("mcp_ids") ? row.getMcpIds() : null,
                payload.containsKey("opencli_ids") ? row.getOpencliIds() : null,
                payload.containsKey("sandbox_id") ? row.getSandboxId() : null,
                payload.containsKey("http_agent_ids") ? row.getHttpAgentIds() : null);
        bindings.validateKnowledge(user,
                payload.containsKey("knowledge_ids") ? row.getKnowledgeIds() : null,
                previousKnowledge,
                row.getHttpAgentIds());
        bindings.validateFlowTools(user.getTenantId(),
                payload.containsKey("mcp_ids") ? row.getMcpIds() : null,
                payload.containsKey("sandbox_id") ? row.getSandboxId() : null,
                payload.containsKey("http_agent_ids") ? row.getHttpAgentIds() : null,
                payload.containsKey("tool_flows") ? row.getToolFlows() : null, row);
        applyHttpProxyMode(row);
    }

    private static void applyHttpProxyMode(Agent row) {
        List<Long> ids = row.getHttpAgentIds();
        if (ids == null || ids.isEmpty()) {
            if (row.getModelName() == null) {
                row.setModelName("");
            }
            return;
        }
        row.setHttpAgentIds(List.of(ids.get(0)));
        row.setSkillIds(List.of());
        row.setMcpIds(List.of());
        row.setOpencliIds(List.of());
        row.setKnowledgeIds(List.of());
        row.setToolFlows(List.of());
        row.setSandboxId(null);
        if (row.getModelName() == null) {
            row.setModelName("");
        }
    }

    private void applyHttpAgent(HttpAgent row, Map<String, Object> payload) {
        if (payload.containsKey("name") && payload.get("name") != null) {
            String name = Jsons.text(payload.get("name")).strip();
            if (httpAgents.existsByNameAndIdNot(name, row.getId())) {
                throw ApiException.conflict("HTTP 接口名称已存在");
            }
            row.setName(name);
        }
        if (payload.containsKey("description")) {
            row.setDescription(Jsons.text(payload.get("description")));
        }
        if (payload.containsKey("protocol") && payload.get("protocol") != null) {
            row.setProtocol(HttpAgentRuntime.normalizeProtocol(Jsons.text(payload.get("protocol"))));
        }
        if (payload.containsKey("endpoint") && payload.get("endpoint") != null) {
            String endpoint = Jsons.text(payload.get("endpoint")).strip();
            HttpAgentRuntime.validateEndpoint(endpoint);
            row.setEndpoint(endpoint);
        }
        if (payload.containsKey("headers") && payload.get("headers") instanceof Map<?, ?> map) {
            Map<String, Object> incoming = new LinkedHashMap<>();
            map.forEach((key, value) -> incoming.put(String.valueOf(key), value));
            row.setHeaders(HttpAgentRuntime.mergeHeaders(row.getHeaders(), incoming));
        }
        if (payload.containsKey("input_field")) {
            row.setInputField(Jsons.text(payload.get("input_field")).strip());
        }
        if (payload.containsKey("output_path")) {
            row.setOutputPath(Jsons.text(payload.get("output_path")).strip());
        }
        if (payload.containsKey("timeout_seconds") && payload.get("timeout_seconds") instanceof Number number) {
            row.setTimeoutSeconds(HttpAgentRuntime.clampTimeout(number.intValue()));
        }
        if (payload.containsKey("enabled") && payload.get("enabled") instanceof Boolean enabled) {
            row.setEnabled(enabled);
        }
        if (payload.containsKey("config") && payload.get("config") instanceof Map<?, ?> map) {
            Map<String, Object> config = new LinkedHashMap<>(row.getConfig() == null ? Map.of() : row.getConfig());
            map.forEach((key, value) -> config.put(String.valueOf(key), value));
            row.setConfig(config);
        }
    }

    private void applyMcp(McpServer row, Map<String, Object> payload) {
        if (payload.containsKey("name") && payload.get("name") != null) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("transport") && payload.get("transport") != null) {
            row.setTransport(McpStreamClient.normalizeMcpTransport(Jsons.text(payload.get("transport"))));
        }
        if (payload.containsKey("endpoint")) {
            row.setEndpoint(Jsons.text(payload.get("endpoint")));
        }
        if (payload.containsKey("enabled") && payload.get("enabled") instanceof Boolean enabled) {
            row.setEnabled(enabled);
        }
        if (payload.containsKey("config") && payload.get("config") instanceof Map<?, ?> map) {
            Map<String, Object> incoming = new LinkedHashMap<>();
            map.forEach((key, value) -> incoming.put(String.valueOf(key), value));
            row.setConfig(McpStreamClient.mergeMcpConfig(row.getConfig(), incoming));
        }
        if (McpStreamClient.isOpencliTransport(row.getTransport())) {
            try {
                row.setConfig(OpenCliRuntime.applyOpencliConfig(row.getConfig(), row.getEndpoint()));
                if (!Jsons.text(row.getConfig().get("endpoint")).isBlank()) {
                    row.setEndpoint(Jsons.text(row.getConfig().get("endpoint")));
                }
            } catch (RuntimeException e) {
                throw ApiException.unprocessable(e.getMessage());
            }
        }
    }

    private static void applySkill(Skill row, Map<String, Object> payload) {
        if (payload.containsKey("name") && payload.get("name") != null) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("description")) {
            row.setDescription(Jsons.text(payload.get("description")));
        }
        if (payload.containsKey("source")) {
            row.setSource(Jsons.text(payload.get("source")));
        }
        if (payload.containsKey("version")) {
            row.setVersion(Jsons.text(payload.get("version")));
        }
        if (payload.containsKey("instruction")) {
            row.setInstruction(Jsons.text(payload.get("instruction")));
        }
        if (payload.containsKey("enabled") && payload.get("enabled") instanceof Boolean enabled) {
            row.setEnabled(enabled);
        }
    }

    private static void applyModel(ModelConfig row, Map<String, Object> payload) {
        if (payload.containsKey("name")) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("provider")) {
            row.setProvider(Jsons.text(payload.get("provider")));
        }
        if (payload.containsKey("model_id")) {
            row.setModelId(Jsons.text(payload.get("model_id")));
        }
        if (payload.containsKey("base_url")) {
            row.setBaseUrl(Jsons.text(payload.get("base_url")));
        }
        if (payload.containsKey("api_key")) {
            String key = Jsons.text(payload.get("api_key"));
            if (!key.isEmpty()) {
                row.setApiKey(key);
            }
        }
        if (payload.containsKey("api_key_ref")) {
            row.setApiKeyRef(Jsons.text(payload.get("api_key_ref")));
        }
        if (payload.containsKey("temperature") && payload.get("temperature") instanceof Number number) {
            row.setTemperature(number.doubleValue());
        }
        if (payload.containsKey("enabled") && payload.get("enabled") instanceof Boolean enabled) {
            row.setEnabled(enabled);
        }
        if (payload.containsKey("purpose")) {
            row.setPurpose(normalizePurpose(Jsons.text(payload.get("purpose"))));
        }
    }

    private static String normalizePurpose(String purpose) {
        String value = purpose == null || purpose.isBlank() ? "chat" : purpose.strip();
        if (!"chat".equals(value) && !"embedding".equals(value) && !"rerank".equals(value)) {
            throw ApiException.unprocessable("模型用途只能是 chat、embedding 或 rerank");
        }
        return value;
    }

    private static void applyWorkflow(Workflow row, Map<String, Object> payload) {
        if (payload.containsKey("name")) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("description")) {
            row.setDescription(Jsons.text(payload.get("description")));
        }
        if (payload.containsKey("status")) {
            row.setStatus(Jsons.text(payload.get("status")));
        }
        if (payload.containsKey("graph") && payload.get("graph") instanceof Map<?, ?> map) {
            Map<String, Object> graph = new LinkedHashMap<>();
            map.forEach((key, value) -> graph.put(String.valueOf(key), value));
            row.setGraph(graph);
        }
    }

    private static void applySandbox(SandboxPolicy row, Map<String, Object> payload) {
        if (payload.containsKey("name")) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("runtime")) {
            row.setRuntime(Jsons.text(payload.get("runtime")));
        }
        if (payload.containsKey("cpu_limit")) {
            row.setCpuLimit(Jsons.text(payload.get("cpu_limit")));
        }
        if (payload.containsKey("memory_limit")) {
            row.setMemoryLimit(Jsons.text(payload.get("memory_limit")));
        }
        if (payload.containsKey("timeout_seconds") && payload.get("timeout_seconds") instanceof Number number) {
            row.setTimeoutSeconds(number.intValue());
        }
        if (payload.containsKey("network_mode")) {
            row.setNetworkMode(Jsons.text(payload.get("network_mode")));
        }
        if (payload.containsKey("enabled") && payload.get("enabled") instanceof Boolean enabled) {
            row.setEnabled(enabled);
        }
    }

    private static void applyRole(Role row, Map<String, Object> payload) {
        if (payload.containsKey("name")) {
            row.setName(Jsons.text(payload.get("name")));
        }
        if (payload.containsKey("description")) {
            row.setDescription(Jsons.text(payload.get("description")));
        }
        if (payload.containsKey("permissions") && payload.get("permissions") instanceof List<?> list) {
            row.setPermissions(list.stream().map(String::valueOf).toList());
        }
    }

    private void persist(String resource, Object row) {
        switch (resource) {
            case "agents" -> agents.save((Agent) row);
            case "http-agents" -> httpAgents.save((HttpAgent) row);
            case "mcp" -> mcps.save((McpServer) row);
            case "skills" -> skills.save((Skill) row);
            case "models" -> models.save((ModelConfig) row);
            case "workflows" -> workflows.save((Workflow) row);
            case "sandboxes" -> sandboxes.save((SandboxPolicy) row);
            case "roles" -> roles.save((Role) row);
            default -> {
            }
        }
    }

    private String requireUniqueAgentName(String name, Long excludeId) {
        String clean = name == null ? "" : name.strip();
        if (clean.length() < 2) {
            throw ApiException.unprocessable("Agent 名称至少 2 个字符");
        }
        boolean taken = excludeId == null ? agents.existsByName(clean) : agents.existsByNameAndIdNot(clean, excludeId);
        if (taken) {
            throw ApiException.conflict("Agent 名称已存在");
        }
        return clean;
    }

    private String uniqueModelCopyName(String sourceName) {
        String raw = ((sourceName == null || sourceName.isBlank() ? "模型" : sourceName.strip()) + " 副本");
        if (raw.length() > 100) {
            raw = raw.substring(0, 100).strip();
        }
        if (models.findByName(raw).isEmpty()) {
            return raw;
        }
        String base = raw.length() > 90 ? raw.substring(0, 90).strip() : raw;
        for (int index = 2; index < 1000; index++) {
            String candidate = base + " " + index;
            if (candidate.length() > 100) {
                candidate = candidate.substring(0, 100);
            }
            if (models.findByName(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw ApiException.conflict("无法生成不重复的模型名称");
    }

    private String uniqueCopyName(String sourceName, String requested) {
        String raw = (requested == null || requested.isBlank() ? sourceName + " 副本" : requested).strip();
        if (raw.length() < 2) {
            raw = "Agent 副本";
        }
        if (raw.length() > 80) {
            raw = raw.substring(0, 80);
        }
        if (!agents.existsByName(raw)) {
            return raw;
        }
        String base = raw.length() > 70 ? raw.substring(0, 70).strip() : raw;
        int index = 2;
        while (true) {
            String candidate = (base + " " + index);
            if (candidate.length() > 80) {
                candidate = candidate.substring(0, 80);
            }
            if (!agents.existsByName(candidate)) {
                return candidate;
            }
            index++;
        }
    }
}
