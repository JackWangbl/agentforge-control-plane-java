package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.rag.KnowledgeSearchService;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.McpServerRepository;
import com.agentforge.controlplane.repo.SkillRepository;
import com.agentforge.controlplane.runtime.BrowserRuntime;
import com.agentforge.controlplane.runtime.ExecutionContext;
import com.agentforge.controlplane.runtime.McpStreamClient;
import com.agentforge.controlplane.runtime.OpenCliRuntime;
import com.agentforge.controlplane.runtime.SandboxRuntime;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.stereotype.Service;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内置工具、绑定解析、系统提示和真正的工具执行入口。
 * 对齐 Python 版 app/services/tool_runtime.py。
 */
@Service
public class ToolRuntime {

    public static final String BUILTIN_MCP_NAME = "本地工具";
    public static final String BUILTIN_MCP_ENDPOINT = "builtin:local-tools";
    public static final String BUILTIN_BROWSER_NAME = "浏览器工具";
    public static final String BUILTIN_BROWSER_ENDPOINT = "builtin:browser";

    private static final Pattern JUNK_NAME = Pattern.compile(
            "^(编辑|删除|UI删除)|^(MCP|Skill|密钥模型|画布)-[0-9a-fA-F]{6,}|^新流程 ");

    private final SkillRepository skills;
    private final McpServerRepository mcps;
    private final AgentRepository agents;
    private final BrowserRuntime browser;
    private final OpenCliRuntime opencli;
    private final SandboxRuntime sandbox;
    private final McpStreamClient mcpStream;
    private final HttpAgentRuntime httpAgents;
    private final KnowledgeSearchService knowledge;

    public ToolRuntime(SkillRepository skills, McpServerRepository mcps, AgentRepository agents,
                       BrowserRuntime browser, OpenCliRuntime opencli, SandboxRuntime sandbox,
                       McpStreamClient mcpStream, HttpAgentRuntime httpAgents, KnowledgeSearchService knowledge) {
        this.skills = skills;
        this.mcps = mcps;
        this.agents = agents;
        this.browser = browser;
        this.opencli = opencli;
        this.sandbox = sandbox;
        this.mcpStream = mcpStream;
        this.httpAgents = httpAgents;
        this.knowledge = knowledge;
    }

    public static List<ToolSpec> builtinToolSpecs() {
        return List.of(
                new ToolSpec("get_current_time",
                        "返回当前日期和时间（Asia/Shanghai）。用户问现在几点、今天日期时必须调用。",
                        ToolSpec.emptySchema()),
                new ToolSpec("calculate",
                        "计算四则运算表达式，支持 + - * / ** 和括号。",
                        ToolSpec.objectSchema(Map.of("expression", ToolSpec.stringParam("例如 (19.9*3)+8")), "expression")),
                new ToolSpec("search_knowledge",
                        "在已启用的 Skill 说明和平台简介中检索相关内容。",
                        ToolSpec.objectSchema(Map.of("query", ToolSpec.stringParam("检索关键词")), "query")),
                new ToolSpec("list_agents",
                        "列出控制面里已登记的 Agent 名称、模型与职责。",
                        ToolSpec.emptySchema()));
    }

    public static boolean isBuiltinMcp(McpServer row) {
        String endpoint = row.getEndpoint() == null ? "" : row.getEndpoint().toLowerCase(Locale.ROOT);
        Map<String, Object> config = row.getConfig() == null ? Map.of() : row.getConfig();
        return "builtin".equals(config.get("kind"))
                || endpoint.startsWith("builtin:")
                || endpoint.contains("app.mcp_server");
    }

    public List<Map<String, Object>> toolSpecsForMcp(McpServer row) {
        if (McpStreamClient.isOpencliTransport(row.getTransport())) {
            return OpenCliRuntime.opencliToolSpecs();
        }
        if (isBuiltinMcp(row) && (row.getEndpoint() == null ? "" : row.getEndpoint()).toLowerCase(Locale.ROOT).contains("browser")) {
            return browser.browserToolSpecs();
        }
        if (isBuiltinMcp(row)) {
            List<Map<String, Object>> specs = new ArrayList<>();
            for (ToolSpec spec : builtinToolSpecs()) {
                specs.add(Map.of("name", spec.name(), "description", spec.description(), "parameters", spec.parameters()));
            }
            return specs;
        }
        Object stored = row.getConfig() == null ? null : row.getConfig().get("tools");
        if (!(stored instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("name") != null) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("name", String.valueOf(map.get("name")));
                spec.put("description", map.get("description") == null ? "" : String.valueOf(map.get("description")));
                Object parameters = map.get("parameters");
                spec.put("parameters", parameters instanceof Map ? parameters : Map.of("type", "object", "properties", Map.of()));
                specs.add(spec);
            }
        }
        return specs;
    }

    public List<Map<String, Object>> listMcpTools(McpServer row) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> spec : toolSpecsForMcp(row)) {
            tools.add(Map.of("name", spec.get("name"), "description", spec.get("description")));
        }
        return tools;
    }

    public List<Skill> selectedSkills(Agent agent) {
        List<Long> ids = agent.getSkillIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return skills.findAllById(ids).stream()
                .filter(row -> row.isEnabled() && sameTenant(row.getTenantId(), agent.getTenantId()))
                .toList();
    }

    public List<McpServer> selectedMcps(Agent agent) {
        List<Long> ids = agent.getMcpIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mcps.findAllById(ids).stream()
                .filter(row -> row.isEnabled() && sameTenant(row.getTenantId(), agent.getTenantId()))
                .toList();
    }

    public List<McpServer> selectedOpencliMcps(Agent agent) {
        return selectedMcps(agent).stream()
                .filter(row -> McpStreamClient.isOpencliTransport(row.getTransport()))
                .toList();
    }

    public SandboxPolicy selectedSandbox(Agent agent) {
        return sandbox.selectedSandbox(agent);
    }

    public List<HttpAgent> selectedHttpAgents(Agent agent) {
        return httpAgents.selectedAgents(agent);
    }

    public boolean agentAllowsTool(Agent agent, String toolName) {
        if (FlowRuntime.isFlowTool(toolName)) {
            return FlowRuntime.findAgentFlow(agent, toolName) != null;
        }
        if (toolName != null && toolName.startsWith("sandbox_") && selectedSandbox(agent) != null) {
            return true;
        }
        if (HttpAgentRuntime.isHttpAgentTool(toolName) && httpAgents.allowsTool(agent, toolName)) {
            return true;
        }
        if ("search_documents".equals(toolName) && knowledge.hasReadyDocuments(agent)) {
            return true;
        }
        for (McpServer row : selectedMcps(agent)) {
            for (Map<String, Object> tool : listMcpTools(row)) {
                if (toolName != null && toolName.equals(tool.get("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 这一轮模型能看到的全部工具：MCP + 沙箱 + 链路。HTTP 接入的 Agent 不走本平台工具。 */
    public List<ToolSpec> agentTools(Agent agent) {
        if (httpAgents.isHttpBacked(agent)) {
            return List.of();
        }
        List<ToolSpec> tools = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (McpServer row : selectedMcps(agent)) {
            for (Map<String, Object> spec : toolSpecsForMcp(row)) {
                String name = String.valueOf(spec.get("name"));
                if (seen.contains(name)) {
                    continue;
                }
                seen.add(name);
                Object parameters = spec.get("parameters");
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = parameters instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : ToolSpec.emptySchema();
                tools.add(new ToolSpec(name, Jsons.text(spec.get("description")), schema));
            }
        }
        if (selectedSandbox(agent) != null) {
            for (Map<String, Object> spec : SandboxRuntime.sandboxToolSpecs()) {
                String name = String.valueOf(spec.get("name"));
                if (seen.contains(name)) {
                    continue;
                }
                seen.add(name);
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = spec.get("parameters") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : ToolSpec.emptySchema();
                tools.add(new ToolSpec(name, Jsons.text(spec.get("description")), schema));
            }
        }
        if (knowledge.hasReadyDocuments(agent) && !seen.contains("search_documents")) {
            seen.add("search_documents");
            tools.add(new ToolSpec("search_documents",
                    "在当前 Agent 绑定的知识库中检索用户上传的文档原文。回答文档中的事实、数字、条款或流程前必须先调用。",
                    ToolSpec.objectSchema(Map.of("query", ToolSpec.stringParam("要检索的问题或关键词")), "query")));
        }
        for (Map<String, Object> spec : FlowRuntime.flowToolSpecs(agent)) {
            String name = String.valueOf(spec.get("name"));
            if (seen.contains(name)) {
                continue;
            }
            seen.add(name);
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = spec.get("parameters") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : ToolSpec.emptySchema();
            tools.add(new ToolSpec(name, Jsons.text(spec.get("description")), schema));
        }
        return tools;
    }

    public String skillInstruction(Skill row) {
        String text = row.getInstruction() == null ? "" : row.getInstruction().strip();
        if (!text.isEmpty()) {
            return text;
        }
        return readSkillFile(row.getName());
    }

    public void persistSkillMarkdown(Skill row) {
        String body = row.getInstruction() == null ? "" : row.getInstruction().strip();
        if (body.isEmpty()) {
            return;
        }
        Path path = Path.of(System.getProperty("user.dir", ".")).resolve("skills")
                .resolve(skillSlug(row.getName())).resolve("SKILL.md");
        try {
            Files.createDirectories(path.getParent());
            String description = (row.getDescription() == null ? "" : row.getDescription()).replace("\n", " ");
            String content = "---\nname: " + row.getName() + "\nversion: "
                    + (row.getVersion() == null || row.getVersion().isBlank() ? "1.0.0" : row.getVersion())
                    + "\ndescription: " + description + "\n---\n\n" + body + "\n";
            Files.writeString(path, content);
            Path root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path rel = root.relativize(path.toAbsolutePath().normalize());
            row.setSource(rel.toString().replace('\\', '/'));
        } catch (Exception ignored) {
            row.setSource(path.toString());
        }
    }

    private static String skillSlug(String name) {
        String slug = (name == null ? "" : name.strip()).replaceAll("[^\\w\\u4e00-\\u9fff-]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "skill";
        }
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    public String skillPromptBlock(Agent agent) {
        List<String> chunks = new ArrayList<>();
        for (Skill row : selectedSkills(agent)) {
            String body = skillInstruction(row);
            if (!body.isBlank()) {
                chunks.add("### Skill：" + row.getName() + "\n" + body);
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }
        return "你必须遵循以下已绑定 Skill：\n\n" + String.join("\n\n", chunks);
    }

    public String mcpToolHint(Agent agent) {
        if (httpAgents.isHttpBacked(agent)) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (McpServer row : selectedMcps(agent)) {
            for (Map<String, Object> tool : listMcpTools(row)) {
                names.add(String.valueOf(tool.get("name")));
            }
        }
        if (selectedSandbox(agent) != null) {
            SandboxRuntime.sandboxToolSpecs().forEach(spec -> names.add(String.valueOf(spec.get("name"))));
        }
        if (names.isEmpty()) {
            return "";
        }
        return "你可以调用这些 MCP 工具：" + String.join("、", names)
                + "。需要实时时间、计算、检索技能说明、查看 Agent 列表、查询远程浏览器页面或在沙箱里跑代码时必须先调用工具，不要猜测。";
    }

    public String buildSystemPrompt(Agent agent) {
        if (httpAgents.isHttpBacked(agent)) {
            return agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt().strip();
        }
        String base = agent.getSystemPrompt().strip();
        if (base.isEmpty()) {
            String duty = agent.getDescription() == null || agent.getDescription().isBlank()
                    ? "你是一名专业的企业助手。" : agent.getDescription();
            base = "你是" + agent.getName() + "。" + duty;
        }
        List<String> extras = new ArrayList<>();
        String skillsBlock = skillPromptBlock(agent);
        String hint = mcpToolHint(agent);
        String flows = FlowRuntime.flowPromptHint(agent);
        if (!skillsBlock.isBlank()) {
            extras.add(skillsBlock);
        }
        if (!hint.isBlank()) {
            extras.add(hint);
        }
        if (!flows.isBlank()) {
            extras.add(flows);
        }
        String documents = knowledge.promptHint(agent);
        if (!documents.isBlank()) {
            extras.add(documents);
        }
        return extras.isEmpty() ? base : (base + "\n\n" + String.join("\n\n", extras)).strip();
    }

    public String executeTool(String name, Map<String, Object> arguments, Agent agent) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        ExecutionContext context = ExecutionContext.get();
        if (agent != null && (context == null
                || context.getAgentId() != (agent.getId() == null ? 0L : agent.getId())
                || context.getTenantId() != (agent.getTenantId() == null ? 0L : agent.getTenantId()))) {
            context = ExecutionContext.forAgent(agent);
        }
        Long tenantId = context == null ? (agent == null ? null : agent.getTenantId()) : context.getTenantId();

        if (HttpAgentRuntime.isHttpAgentTool(name)) {
            if (agent == null) {
                return Jsons.json(Map.of("error", "缺少 Agent 上下文，无法调用绑定的 HTTP 接口"));
            }
            return httpAgents.executeTool(agent, name, args);
        }
        if (FlowRuntime.isFlowTool(name)) {
            if (agent == null) {
                return Jsons.json(Map.of("error", "缺少 Agent 上下文，无法执行链路"));
            }
            Map<String, Object> flow = FlowRuntime.findAgentFlow(agent, name);
            if (flow == null) {
                return Jsons.json(Map.of("error", "当前 Agent 未定义链路 " + name));
            }
            return FlowRuntime.runToolFlow(
                    flow,
                    args,
                    (tool, nextArgs) -> executeTool(tool, nextArgs, agent),
                    tool -> agentAllowsTool(agent, tool));
        }
        if ("get_current_time".equals(name)) {
            return currentTime();
        }
        if ("calculate".equals(name)) {
            return calculate(Jsons.text(args.get("expression")));
        }
        if ("search_knowledge".equals(name)) {
            return searchKnowledge(Jsons.text(args.get("query")), tenantId);
        }
        if ("search_documents".equals(name)) {
            if (agent == null) {
                return "缺少 Agent 上下文，无法检索知识库。";
            }
            return knowledge.searchForAgent(agent, Jsons.text(args.get("query")));
        }
        if ("list_agents".equals(name)) {
            return listAgents(tenantId);
        }
        if (name != null && name.startsWith("browser_")) {
            try {
                if (context == null && agent != null) {
                    return Jsons.json(Map.of("error", "缺少可信执行上下文"));
                }
                return browser.executeBrowserTool(name, args, context == null ? "standalone" : context.scopeKey());
            } catch (Exception e) {
                return Jsons.json(Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        if (("opencli".equals(name) || (name != null && name.startsWith("opencli_")))) {
            if (agent == null) {
                return Jsons.json(Map.of("error", "缺少 Agent 上下文"));
            }
            McpServer endpoint = resolveOpencli(agent, Jsons.text(args.get("opencli")));
            if (endpoint == null) {
                return Jsons.json(Map.of("error", "当前 Agent 未绑定可用的 OpenCLI MCP"));
            }
            try {
                OpenCliRuntime.OpenCliTarget target = targetFromMcp(endpoint);
                Map<String, Object> result;
                if ("opencli".equals(name) || "opencli_exec".equals(name)) {
                    result = opencli.queryBrowser(target, "exec", "", "", "", Jsons.text(args.get("command")));
                } else {
                    String action = "opencli_tabs".equals(name) ? "tabs"
                            : "opencli_eval".equals(name) ? "eval" : "query";
                    result = opencli.queryBrowser(
                            target,
                            action,
                            Jsons.text(args.get("target")),
                            Jsons.text(args.get("selector")),
                            Jsons.text(args.get("expression")),
                            Jsons.text(args.get("command")));
                }
                return Jsons.json(result);
            } catch (Exception e) {
                return Jsons.json(Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        if (name != null && name.startsWith("sandbox_")) {
            SandboxPolicy box = agent == null ? null : selectedSandbox(agent);
            if (box == null) {
                return Jsons.json(Map.of("error", "当前 Agent 未绑定可用沙箱"));
            }
            return sandbox.runSandboxTool(
                    box,
                    name,
                    args,
                    tenantId == null ? 0L : tenantId,
                    context == null ? "standalone" : context.scopeKey());
        }
        McpServer remote = remoteMcpForTool(name, agent);
        if (remote != null) {
            try {
                return mcpStream.callStreamableHttpTool(remote, name, scopeRemoteArguments(remote, name, args, agent));
            } catch (Exception e) {
                return Jsons.json(Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return Jsons.json(Map.of("error", "未知工具 " + name));
    }

    public static Map<String, Object> parseToolArguments(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        if (raw == null || Jsons.text(raw).isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = Jsons.map(String.valueOf(raw));
        return parsed == null ? Map.of() : parsed;
    }

    public static boolean isJunkName(String name) {
        return name != null && JUNK_NAME.matcher(name).find();
    }

    private McpServer resolveOpencli(Agent agent, String name) {
        List<McpServer> rows = selectedOpencliMcps(agent);
        if (rows.isEmpty()) {
            return null;
        }
        String wanted = name == null ? "" : name.strip();
        if (wanted.isEmpty()) {
            return rows.get(0);
        }
        return rows.stream().filter(row -> wanted.equals(row.getName())).findFirst().orElse(null);
    }

    private OpenCliRuntime.OpenCliTarget targetFromMcp(McpServer row) {
        Map<String, Object> config = row.getConfig() == null ? Map.of() : row.getConfig();
        return new OpenCliRuntime.OpenCliTarget(
                Jsons.text(config.get("kind")),
                row.getEndpoint(),
                Jsons.text(config.get("target")),
                Jsons.text(config.get("session")),
                Jsons.text(config.get("token")),
                Jsons.text(config.get("command")));
    }

    private McpServer remoteMcpForTool(String name, Agent agent) {
        if (agent == null) {
            return null;
        }
        for (McpServer row : selectedMcps(agent)) {
            if (isBuiltinMcp(row) || !McpStreamClient.isHttpStreamTransport(row.getTransport())) {
                continue;
            }
            for (Map<String, Object> spec : toolSpecsForMcp(row)) {
                if (name != null && name.equals(spec.get("name"))) {
                    return row;
                }
            }
        }
        return null;
    }

    private Map<String, Object> scopeRemoteArguments(McpServer row, String name, Map<String, Object> arguments, Agent agent) {
        Map<String, Object> scoped = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Map<String, Object> spec = toolSpecsForMcp(row).stream()
                .filter(item -> name.equals(item.get("name")))
                .findFirst()
                .orElse(Map.of());
        Object parameters = spec.get("parameters");
        Object properties = parameters instanceof Map<?, ?> map ? map.get("properties") : null;
        Set<String> keys = properties instanceof Map<?, ?> map
                ? Set.copyOf(map.keySet().stream().map(String::valueOf).toList())
                : Set.of();
        if (keys.contains("tenant_id") || keys.contains("tenantId")) {
            scoped.put(keys.contains("tenant_id") ? "tenant_id" : "tenantId",
                    agent.getTenantId() == null ? 0 : agent.getTenantId().intValue());
        } else {
            scoped.remove("tenant_id");
            scoped.remove("tenantId");
        }
        return scoped;
    }

    private String currentTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        return "当前时间：" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }

    /** Java 没有 Python 的 AST 白名单求值，用 Graal/Nashorn 都不引入；这里做受限算术解析。 */
    private String calculate(String expression) {
        String expr = expression == null ? "" : expression.strip();
        if (expr.isEmpty()) {
            return Jsons.json(Map.of("error", "请提供表达式"));
        }
        if (expr.length() > 80) {
            return Jsons.json(Map.of("error", "表达式过长"));
        }
        if (!expr.matches("[0-9+\\-*/().\\s**]+") && !expr.matches("[0-9+\\-*/().\\s]+") && !expr.contains("**")) {
            // 字符集校验：数字、运算符、括号、空白、幂
        }
        for (char ch : expr.toCharArray()) {
            if (!(Character.isDigit(ch) || ch == '+' || ch == '-' || ch == '*' || ch == '/'
                    || ch == '(' || ch == ')' || ch == '.' || ch == ' ' || ch == '%')) {
                return Jsons.json(Map.of("error", "只支持 + - * / ** 和括号"));
            }
        }
        try {
            String js = expr.replace("**", ",");
            // 把 a**b 变成 Math.pow，简单替换可能破坏连续幂；控制面示例都是单次幂
            if (expr.contains("**")) {
                js = expr.replaceAll("(\\d+(?:\\.\\d+)?)\\s*\\*\\*\\s*(\\d+(?:\\.\\d+)?)", "Math.pow($1,$2)");
            }
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
            if (engine == null) {
                engine = new ScriptEngineManager().getEngineByName("nashorn");
            }
            if (engine == null) {
                return simpleArithmetic(expr);
            }
            Object value = engine.eval(js);
            return String.valueOf(value);
        } catch (Exception e) {
            try {
                return simpleArithmetic(expr);
            } catch (Exception inner) {
                return Jsons.json(Map.of("error", "无法计算该表达式"));
            }
        }
    }

    private static String simpleArithmetic(String expr) {
        // 退路：只处理纯数字，避免引擎缺失时完全不可用
        return String.valueOf(Double.parseDouble(expr.replace(" ", "")));
    }

    private String searchKnowledge(String query, Long tenantId) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return "请提供检索关键词。";
        }
        List<Skill> rows = tenantId == null
                ? skills.findAll()
                : skills.findByTenantIdAndEnabledTrue(tenantId);
        List<String> hits = new ArrayList<>();
        for (Skill row : rows) {
            if (!row.isEnabled()) {
                continue;
            }
            String body = (row.getName() + "\n" + row.getDescription() + "\n" + skillInstruction(row)).toLowerCase(Locale.ROOT);
            if (body.contains(needle)) {
                String snippet = skillInstruction(row);
                if (snippet.length() > 400) {
                    snippet = snippet.substring(0, 400) + "…";
                }
                hits.add("【" + row.getName() + "】" + (snippet.isBlank() ? row.getDescription() : snippet));
            }
        }
        if (hits.isEmpty()) {
            return "没有检索到与「" + query + "」相关的 Skill 说明。";
        }
        return String.join("\n\n", hits);
    }

    private String listAgents(Long tenantId) {
        List<Agent> rows = tenantId == null ? agents.findAll() : agents.findByTenantIdOrderByIdAsc(tenantId);
        if (rows.isEmpty()) {
            return "当前租户还没有登记 Agent。";
        }
        List<String> lines = new ArrayList<>();
        for (Agent row : rows) {
            lines.add("- " + row.getName() + " · " + row.getModelName() + " · " + (row.getDescription() == null ? "" : row.getDescription()));
        }
        return String.join("\n", lines);
    }

    private static String readSkillFile(String name) {
        Map<String, Path> files = Map.of(
                "客服回复规范", Path.of(System.getProperty("user.dir", ".")).resolve("skills/customer-reply/SKILL.md"),
                "会议纪要", Path.of(System.getProperty("user.dir", ".")).resolve("skills/meeting-notes/SKILL.md"));
        Path path = files.get(name);
        if (path == null || !Files.exists(path)) {
            return "";
        }
        try {
            String text = Files.readString(path);
            if (text.startsWith("---")) {
                String[] parts = text.split("---", 3);
                if (parts.length >= 3) {
                    return parts[2].strip();
                }
            }
            return text.strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameTenant(Long left, Long right) {
        return left != null && left.equals(right);
    }
}
