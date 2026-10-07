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
        ensureExampleOrchestrations();
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
        wf.setStatus("draft");
        wf.setGraph(Map.of(
                "mode", "multi_agent",
                "dispatch", "start",
                "global_prompt", "",
                "nodes", List.of(Map.of("id", "start", "type", "start", "label", "开始", "x", 72, "y", 200)),
                "edges", List.of()));
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

    /**
     * 给新用户准备三种编排模式的现成例子。已存在同名智能体 / 编排则跳过，不覆盖用户修改。
     */
    private void ensureExampleOrchestrations() {
        Tenant def = tenants.findBySlug("default").orElse(null);
        long tenantId = def == null ? 1L : def.getId();
        String modelName = defaultModelName();

        Agent sales = ensureExampleAgent(tenantId, "示例·售前顾问", "解答产品功能、套餐和报价", modelName,
                "你是售前顾问。用简洁中文介绍产品能力、套餐差异和大致报价区间。不确定的承诺不要编造。");
        Agent support = ensureExampleAgent(tenantId, "示例·售后专员", "处理退换货、故障和投诉", modelName,
                "你是售后专员。先确认订单或现象，再给出可执行的退换货、检修或升级建议。语气冷静、有步骤。");
        Agent boss = ensureExampleAgent(tenantId, "示例·主控策划", "旅行规划总控，按需调用子智能体", modelName,
                "你是旅行规划主控。先理解用户需求，需要细节时调用子智能体工具，再汇总成完整方案。");
        Agent trip = ensureExampleAgent(tenantId, "示例·行程规划", "安排目的地、日程和交通", modelName,
                "你是行程规划师。只输出日程、景点和交通建议，不要谈预算细节。");
        Agent budget = ensureExampleAgent(tenantId, "示例·预算核算", "估算旅行花费与节省建议", modelName,
                "你是预算顾问。根据行程给出分项花费估算和可节省项，用人民币。");
        Agent gather = ensureExampleAgent(tenantId, "示例·素材收集", "整理主题要点与参考资料", modelName,
                "你是素材收集员。围绕主题列出关键事实、要点和可用角度，条目清晰。");
        Agent writer = ensureExampleAgent(tenantId, "示例·文案撰写", "把要点写成可读短文", modelName,
                "你是文案写手。根据上游素材写成 300 字左右的短文，标题另起一行。");
        Agent editor = ensureExampleAgent(tenantId, "示例·校对润色", "检查事实、语气并给出终稿", modelName,
                "你是校对编辑。检查事实、错别字和语气，输出终稿，并附三行修改说明。");

        ensureExampleWorkflow(tenantId, "示例·场景移交｜智能客服",
                "跟着练场景移交：发布后试「企业版一年多少钱」「订单坏了要退货」「转人工」。",
                handoffExampleGraph(sales, support));
        ensureExampleWorkflow(tenantId, "示例·主从 SubAgent｜旅行规划",
                "跟着练主从模式：发布后试「帮我规划三天上海亲子游，预算五千」。看主控是否调用行程/预算子智能体。",
                supervisorExampleGraph(boss, trip, budget));
        ensureExampleWorkflow(tenantId, "示例·工作流｜内容生产",
                "跟着练工作流：发布后试「写一篇介绍多智能体编排的短文」。会依次跑素材→文案→校对。",
                pipelineExampleGraph(gather, writer, editor));
    }

    private String defaultModelName() {
        return models.findAll().stream()
                .filter(ModelConfig::isEnabled)
                .map(ModelConfig::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("Qwen-Max");
    }

    private Agent ensureExampleAgent(long tenantId, String name, String description, String modelName, String prompt) {
        Agent row = agents.findByName(name).orElse(null);
        if (row != null) {
            return row;
        }
        row = new Agent();
        row.setTenantId(tenantId);
        row.setOwnerId(null);
        row.setName(name);
        row.setDescription(description);
        row.setModelName(modelName);
        row.setSystemPrompt(prompt);
        row.setStatus("published");
        row.setVersion("v1.0.0");
        row.setSuccessRate(97.0);
        return agents.save(row);
    }

    private void ensureExampleWorkflow(long tenantId, String name, String description, Map<String, Object> graph) {
        Workflow row = workflows.findByName(name).orElse(null);
        if (row == null) {
            row = new Workflow();
            row.setTenantId(tenantId);
            row.setOwnerId(null);
            row.setName(name);
            row.setStatus("draft");
        }
        row.setDescription(description);
        row.setGraph(graph);
        workflows.save(row);
    }

    private static Map<String, Object> handoffExampleGraph(Agent sales, Agent support) {
        return Map.of(
                "mode", "multi_agent",
                "pattern", "handoff",
                "dispatch", "start",
                "global_prompt", "你在一家 ToB SaaS 公司做客服。回答用简体中文，先给结论再补细节。",
                "nodes", List.of(
                        node("start", "start", "开始", 72, 240, null, "", "", ""),
                        node("n_sales", "agent", sales.getName(), 340, 140, sales.getId(),
                                "用户询问产品功能、套餐、价格、试用或对比竞品",
                                "回答时可以举例说明，不要承诺未上线的功能。", ""),
                        node("n_support", "agent", support.getName(), 340, 340, support.getId(),
                                "用户反馈故障、退换货、发票、投诉或已有订单问题",
                                "先复述问题，再给排查或处理步骤。", ""),
                        node("j_human", "jump", "转人工", 620, 240, null, "", "",
                                "用户明确要求转人工、找真人客服或升级投诉")),
                "edges", List.of(
                        edge("start", "n_sales"),
                        edge("start", "n_support"),
                        edge("n_sales", "n_support"),
                        edge("j_human", "n_support")));
    }

    private static Map<String, Object> supervisorExampleGraph(Agent boss, Agent trip, Agent budget) {
        return Map.of(
                "mode", "multi_agent",
                "pattern", "supervisor",
                "dispatch", "start",
                "global_prompt", "面向个人旅行用户，方案要可执行，费用用人民币。",
                "nodes", List.of(
                        node("start", "start", "开始", 72, 220, null, "", "", ""),
                        node("n_boss", "agent", boss.getName(), 300, 220, boss.getId(),
                                "统筹旅行需求，决定是否调用子智能体",
                                "最终回复要包含行程概要和预算摘要。", ""),
                        node("n_trip", "agent", trip.getName(), 560, 120, trip.getId(),
                                "需要详细日程、景点顺序或交通安排时调用",
                                "输出按天排列的行程表。", ""),
                        node("n_budget", "agent", budget.getName(), 560, 320, budget.getId(),
                                "需要估算总花费、分项预算或省钱建议时调用",
                                "给出分项表格和合计。", "")),
                "edges", List.of(
                        edge("start", "n_boss"),
                        edge("n_boss", "n_trip"),
                        edge("n_boss", "n_budget")));
    }

    private static Map<String, Object> pipelineExampleGraph(Agent gather, Agent writer, Agent editor) {
        return Map.of(
                "mode", "multi_agent",
                "pattern", "pipeline",
                "dispatch", "start",
                "global_prompt", "内容面向产品新人，语气专业但不堆术语。",
                "nodes", List.of(
                        node("start", "start", "开始", 72, 220, null, "", "", ""),
                        node("n_gather", "agent", gather.getName(), 280, 220, gather.getId(),
                                "收集主题相关要点与素材",
                                "只输出要点列表，不要写完整文章。", ""),
                        node("n_writer", "agent", writer.getName(), 500, 220, writer.getId(),
                                "根据素材写成短文",
                                "根据上游要点写成完整短文。", ""),
                        node("n_editor", "agent", editor.getName(), 720, 220, editor.getId(),
                                "校对并输出终稿",
                                "在终稿后附修改说明。", "")),
                "edges", List.of(
                        edge("start", "n_gather"),
                        edge("n_gather", "n_writer"),
                        edge("n_writer", "n_editor")));
    }

    private static Map<String, Object> node(String id, String type, String label, int x, int y,
                                           Long agentId, String scenario, String prompt, String condition) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("label", label);
        item.put("x", x);
        item.put("y", y);
        item.put("agent_id", agentId == null ? "" : agentId);
        item.put("agent", "agent".equals(type) ? label : "");
        item.put("scenario", scenario);
        item.put("prompt", prompt);
        item.put("condition", condition);
        return item;
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("source", source, "target", target);
    }
}
