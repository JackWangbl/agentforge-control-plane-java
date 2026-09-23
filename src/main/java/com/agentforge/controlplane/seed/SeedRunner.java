package com.agentforge.controlplane.seed;

import com.agentforge.controlplane.access.AuthService;
import com.agentforge.controlplane.access.PasswordHasher;
import com.agentforge.controlplane.agent.ToolRuntime;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.OpenCliEndpoint;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.domain.Tenant;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.domain.Workflow;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.McpServerRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.OpenCliEndpointRepository;
import com.agentforge.controlplane.repo.RoleRepository;
import com.agentforge.controlplane.repo.SandboxPolicyRepository;
import com.agentforge.controlplane.repo.SkillRepository;
import com.agentforge.controlplane.repo.TenantRepository;
import com.agentforge.controlplane.repo.UserRepository;
import com.agentforge.controlplane.repo.WorkflowRepository;
import com.agentforge.controlplane.runtime.OpenCliRuntime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动时补齐租户/角色/内置工具。已有数据一律跳过，避免覆盖共用 MySQL 里的真实数据。 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final List<String> DEV_PERMS = List.of(
            "agent:read", "agent:write", "workflow:read", "workflow:write",
            "eval:read", "eval:run", "experiment:read", "experiment:write",
            "mcp:read", "mcp:write", "skill:read", "model:read",
            "knowledge:read", "knowledge:write",
            "sandbox:read", "session:read", "session:write");

    /** 试用可以进入除模型配置以外的页面。不给 tenant:admin，避免看到模型密钥。 */
    private static final List<String> TRIAL_PERMS = List.of(
            "agent:read", "agent:write", "workflow:read", "workflow:write",
            "eval:read", "eval:run", "experiment:read", "experiment:write",
            "mcp:read", "mcp:write", "skill:read", "skill:write",
            "knowledge:read", "knowledge:write",
            "sandbox:read", "sandbox:write", "session:read", "session:write",
            "trace:read", "role:read", "user:read", "vector:read");

    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final UserRepository users;
    private final AgentRepository agents;
    private final McpServerRepository mcps;
    private final SkillRepository skills;
    private final ModelConfigRepository models;
    private final SandboxPolicyRepository sandboxes;
    private final WorkflowRepository workflows;
    private final OpenCliEndpointRepository opencli;

    public SeedRunner(TenantRepository tenants, RoleRepository roles, UserRepository users, AgentRepository agents,
                      McpServerRepository mcps, SkillRepository skills, ModelConfigRepository models,
                      SandboxPolicyRepository sandboxes, WorkflowRepository workflows,
                      OpenCliEndpointRepository opencli) {
        this.tenants = tenants;
        this.roles = roles;
        this.users = users;
        this.agents = agents;
        this.mcps = mcps;
        this.skills = skills;
        this.models = models;
        this.sandboxes = sandboxes;
        this.workflows = workflows;
        this.opencli = opencli;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedIfEmpty();
        ensureIam();
        seedBuiltinTools();
        migrateOpencli();
    }

    private void seedIfEmpty() {
        if (agents.count() > 0) {
            return;
        }
        Agent a1 = new Agent();
        a1.setName("客服助手");
        a1.setDescription("售前咨询与工单分流");
        a1.setModelName("Qwen-Max");
        a1.setSuccessRate(98.6);
        Agent a2 = new Agent();
        a2.setName("数据分析师");
        a2.setDescription("自然语言数据分析");
        a2.setModelName("GPT-4.1");
        a2.setSuccessRate(96.2);
        agents.saveAll(List.of(a1, a2));

        McpServer builtin = new McpServer();
        builtin.setName(ToolRuntime.BUILTIN_MCP_NAME);
        builtin.setTransport("stdio");
        builtin.setEndpoint(ToolRuntime.BUILTIN_MCP_ENDPOINT);
        builtin.setToolsCount(4);
        builtin.setConfig(Map.of("kind", "builtin"));
        mcps.save(builtin);

        Skill skill = new Skill();
        skill.setName("客服回复规范");
        skill.setDescription("按企业客服口径回复用户");
        skill.setSource("skills/customer-reply/SKILL.md");
        skills.save(skill);

        ModelConfig qwen = new ModelConfig();
        qwen.setName("Qwen 生产集群");
        qwen.setProvider("DashScope");
        qwen.setModelId("qwen-max");
        qwen.setApiKeyRef("DASHSCOPE_API_KEY");
        models.save(qwen);

        SandboxPolicy box = new SandboxPolicy();
        box.setName("标准 Python 沙箱");
        sandboxes.save(box);

        Role admin = new Role();
        admin.setName("平台管理员");
        admin.setDescription("全部平台配置和审计权限");
        admin.setPermissions(List.of("*"));
        roles.save(admin);

        Workflow wf = new Workflow();
        wf.setName("智能客服协作流");
        wf.setDescription("意图识别、检索和工单协作");
        wf.setStatus("published");
        wf.setGraph(Map.of("nodes", List.of(), "edges", List.of()));
        workflows.save(wf);
    }

    private void ensureIam() {
        Tenant def = getOrCreateTenant("default", "默认租户", "控制面主租户，承接历史数据");
        Tenant demo = getOrCreateTenant("demo", "演示租户", "用于验证跨租户隔离");
        Role admin = getOrCreateRole(def.getId(), "平台管理员", "全部平台配置和审计权限", List.of("*"));
        Role dev = getOrCreateRole(def.getId(), "Agent 开发者", "创建、测试和发布 Agent", DEV_PERMS);
        Role auditor = getOrCreateRole(def.getId(), "审计员", "只读查看会话、链路与日志", List.of("session:read", "trace:read"));
        Role demoRole = getOrCreateRole(demo.getId(), "租户管理员", "演示租户内的全部权限",
                List.of("tenant:admin", "agent:read", "agent:write", "session:read", "session:write",
                        "model:read", "mcp:read", "skill:read"));
        List<String> granted = new ArrayList<>(dev.getPermissions());
        if (!granted.contains("experiment:read")) {
            granted.add("experiment:read");
        }
        if (!granted.contains("experiment:write")) {
            granted.add("experiment:write");
        }
        if (!granted.contains("knowledge:read")) {
            granted.add("knowledge:read");
        }
        if (!granted.contains("knowledge:write")) {
            granted.add("knowledge:write");
        }
        dev.setPermissions(granted);
        roles.save(dev);

        getOrCreateUser(def.getId(), "linmo", "林默", "admin123", admin.getId());
        getOrCreateUser(def.getId(), "developer", "陈开发", "dev123", dev.getId());
        Role trial = getOrCreateRole(def.getId(), "试用访客", "未登录试用，不能查看模型配置", TRIAL_PERMS);
        trial.setPermissions(new ArrayList<>(TRIAL_PERMS));
        roles.save(trial);
        getOrCreateUser(def.getId(), AuthService.TRIAL_USER, "试用访客", AuthService.newToken(), trial.getId());
        getOrCreateUser(def.getId(), "auditor", "周审计", "audit123", auditor.getId());
        getOrCreateUser(demo.getId(), "demo", "演示管理员", "demo123", demoRole.getId());

        for (Role role : roles.findAll()) {
            role.setUserCount(users.countByRoleId(role.getId()));
        }
        roles.saveAll(roles.findAll());
    }

    private void seedBuiltinTools() {
        if (mcps.findByName(ToolRuntime.BUILTIN_MCP_NAME).isEmpty()) {
            McpServer builtin = new McpServer();
            builtin.setName(ToolRuntime.BUILTIN_MCP_NAME);
            builtin.setTransport("stdio");
            builtin.setEndpoint(ToolRuntime.BUILTIN_MCP_ENDPOINT);
            builtin.setToolsCount(4);
            builtin.setConfig(Map.of("kind", "builtin"));
            mcps.save(builtin);
        }
        if (mcps.findByName(ToolRuntime.BUILTIN_BROWSER_NAME).isEmpty()) {
            McpServer browser = new McpServer();
            browser.setName(ToolRuntime.BUILTIN_BROWSER_NAME);
            browser.setTransport("stdio");
            browser.setEndpoint(ToolRuntime.BUILTIN_BROWSER_ENDPOINT);
            browser.setToolsCount(4);
            browser.setConfig(Map.of("kind", "builtin"));
            mcps.save(browser);
        }
    }

    private void migrateOpencli() {
        for (OpenCliEndpoint row : opencli.findAll()) {
            McpServer existing = mcps.findByName(row.getName()).orElse(null);
            if (existing == null) {
                existing = new McpServer();
                existing.setName(row.getName());
                existing.setTenantId(row.getTenantId());
                existing.setOwnerId(row.getOwnerId());
            }
            existing.setTransport("opencli");
            existing.setEndpoint(row.getEndpoint());
            existing.setEnabled(row.isEnabled());
            Map<String, Object> config = OpenCliRuntime.applyOpencliConfig(Map.of(
                    "kind", row.getKind(),
                    "target", row.getTarget(),
                    "session", row.getSession(),
                    "token", row.getToken()), row.getEndpoint());
            existing.setConfig(config);
            existing.setToolsCount(OpenCliRuntime.opencliToolSpecs().size());
            mcps.save(existing);
            opencli.delete(row);
        }
    }

    private Tenant getOrCreateTenant(String slug, String name, String description) {
        return tenants.findBySlug(slug).orElseGet(() -> {
            Tenant row = new Tenant();
            row.setSlug(slug);
            row.setName(name);
            row.setDescription(description);
            row.setStatus("active");
            return tenants.save(row);
        });
    }

    private Role getOrCreateRole(Long tenantId, String name, String description, List<String> permissions) {
        Role row = roles.findByName(name).orElse(null);
        if (row == null) {
            row = new Role();
            row.setName(name);
            row.setDescription(description);
            row.setPermissions(new ArrayList<>(permissions));
            row.setTenantId(tenantId);
            return roles.save(row);
        }
        if (row.getTenantId() == null) {
            row.setTenantId(tenantId);
        }
        if (row.getPermissions() == null || row.getPermissions().isEmpty()) {
            row.setPermissions(new ArrayList<>(permissions));
        }
        return roles.save(row);
    }

    private User getOrCreateUser(Long tenantId, String username, String displayName, String password, Long roleId) {
        User row = users.findByUsername(username).orElse(null);
        if (row == null) {
            row = new User();
            row.setUsername(username);
            row.setPasswordHash(PasswordHasher.hash(password));
        }
        row.setTenantId(tenantId);
        row.setDisplayName(displayName);
        row.setRoleId(roleId);
        row.setEnabled(true);
        if (row.getPasswordHash() == null || row.getPasswordHash().isBlank()) {
            row.setPasswordHash(PasswordHasher.hash(password));
        }
        return users.save(row);
    }
}
