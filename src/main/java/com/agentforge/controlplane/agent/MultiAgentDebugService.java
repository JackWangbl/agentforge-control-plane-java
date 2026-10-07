package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Workflow;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.memory.MemorySummarizer;
import com.agentforge.controlplane.playground.PlaygroundService;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 已发布的多智能体编排：场景移交 / 主从 SubAgent / 工作流串行。 */
@Service
public class MultiAgentDebugService {

    private final ResourceAccessService access;
    private final PlaygroundService playground;
    private final ConversationRepository conversations;
    private final ModelConfigRepository models;
    private final HttpAgentRuntime httpAgents;
    private final MemoryService memories;
    private final MemorySummarizer summarizer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json = new ObjectMapper();

    public MultiAgentDebugService(ResourceAccessService access, PlaygroundService playground,
                                  ConversationRepository conversations, ModelConfigRepository models,
                                  HttpAgentRuntime httpAgents, MemoryService memories, MemorySummarizer summarizer) {
        this.access = access;
        this.playground = playground;
        this.conversations = conversations;
        this.models = models;
        this.httpAgents = httpAgents;
        this.memories = memories;
        this.summarizer = summarizer;
    }

    @Transactional
    public Map<String, Object> debug(CurrentUser user, Long workflowId, String message, String sessionId, String focusNodeId) {
        return run(user, workflowId, message, sessionId, focusNodeId,
                "POST /api/workflows/" + workflowId + "/debug");
    }

    @Transactional
    public Map<String, Object> invoke(CurrentUser user, Long workflowId, String message, String sessionId) {
        Map<String, Object> result = run(user, workflowId, message, sessionId, "",
                "POST /api/workflows/" + workflowId + "/invoke");
        return Jsons.ordered(
                "workflow_id", result.get("workflow_id"),
                "reply", result.get("reply"),
                "response", result.get("response"),
                "session_id", result.get("session_id"),
                "mode", result.get("mode"),
                "trace_id", result.get("trace_id"),
                "latency_ms", result.get("latency_ms"),
                "usage", result.get("usage"));
    }

    public Map<String, Object> interfaceCard(CurrentUser user, Long workflowId) {
        Workflow workflow = access.getRow(user, ResourceKind.WORKFLOW, workflowId);
        if (!"published".equals(workflow.getStatus())) {
            throw ApiException.conflict("请先发布这条编排");
        }
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(workflow.getGraph());
        MultiAgentGraphs.requirePublishable(graph, agentId -> agentVisible(user, agentId));
        return Jsons.ordered(
                "id", workflow.getId(),
                "name", workflow.getName(),
                "description", workflow.getDescription(),
                "status", workflow.getStatus(),
                "pattern", graph.pattern(),
                "endpoint", "/api/workflows/" + workflow.getId() + "/invoke",
                "method", "POST");
    }

    private Map<String, Object> run(CurrentUser user, Long workflowId, String message, String sessionId,
                                    String focusNodeId, String endpoint) {
        Workflow workflow = access.getRow(user, ResourceKind.WORKFLOW, workflowId);
        if (!"published".equals(workflow.getStatus())) {
            throw ApiException.conflict("请先发布这条编排，再开始调用");
        }
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(workflow.getGraph());
        MultiAgentGraphs.requirePublishable(graph, agentId -> agentVisible(user, agentId));
        String sid = sessionId == null || sessionId.isBlank() ? "orch_" + UUID.randomUUID().toString().substring(0, 8) : sessionId.strip();
        Conversation conversation = conversations.findBySessionId(sid).orElse(null);
        if (conversation != null && conversation.getTenantId() != null && !conversation.getTenantId().equals(user.getTenantId())) {
            throw ApiException.conflict("这个会话属于其他租户");
        }
        if (conversation != null && !memories.canRead(user, conversation)) {
            throw ApiException.conflict("这个会话属于其他人");
        }
        if (conversation != null && conversation.getWorkflowId() != null && !conversation.getWorkflowId().equals(workflow.getId())) {
            throw ApiException.conflict("这个会话属于另一条编排");
        }
        if (conversation != null && conversation.getWorkflowId() == null && conversation.getAgentId() != null) {
            throw ApiException.conflict("这个会话是从单个智能体开始的，请新开一条调试会话");
        }

        String pattern = graph.pattern();
        if (MultiAgentGraphs.PATTERN_SUPERVISOR.equals(pattern)) {
            return runSupervisor(user, workflow, graph, message, sid, conversation, focusNodeId, endpoint);
        }
        if (MultiAgentGraphs.PATTERN_PIPELINE.equals(pattern)) {
            return runPipeline(user, workflow, graph, message, sid, conversation, focusNodeId, endpoint);
        }
        return runHandoff(user, workflow, graph, message, sid, conversation, focusNodeId, endpoint);
    }

    private Map<String, Object> runHandoff(CurrentUser user, Workflow workflow, MultiAgentGraphs.Graph graph,
                                          String message, String sid, Conversation conversation,
                                          String focusNodeId, String endpoint) {
        boolean focused = false;
        MultiAgentGraphs.Node current = null;
        if (focusNodeId != null && !focusNodeId.isBlank()) {
            MultiAgentGraphs.Node focus = graph.node(focusNodeId.strip());
            if (focus != null && "agent".equals(focus.type())) {
                current = focus;
                focused = true;
            }
        }
        if (current == null && "last".equals(graph.dispatch()) && conversation != null) {
            MultiAgentGraphs.Node last = graph.node(conversation.getCurrentNodeId() == null ? "" : conversation.getCurrentNodeId());
            if (last != null && "agent".equals(last.type())) {
                current = last;
            }
        }
        if (current == null) {
            current = graph.start();
        }

        List<Map<String, Object>> routeSpans = new ArrayList<>();
        boolean jumpHit = false;
        boolean fallback = false;
        MultiAgentGraphs.Node landed = null;
        ModelConfig routerModel = routerModel(user, graph);
        String fromId = current.id();

        if (!focused) {
            JumpResult jump = evaluateJumps(user, graph, message, routerModel);
            routeSpans.add(jump.span());
            if (jump.landed() != null) {
                landed = jump.landed();
                jumpHit = true;
            }
        }

        String handoffDetail;
        if (landed != null) {
            handoffDetail = "全局跳转已选定 " + MultiAgentGraphs.labelOf(landed);
        } else if ("start".equals(current.type())) {
            List<MultiAgentGraphs.Node> options = graph.outgoingAgents(current.id());
            if (options.isEmpty()) {
                return noHandoff(sid);
            }
            PickResult pick = pickAmong(options, message, null, false, routerModel, true);
            landed = pick.node();
            fallback = pick.fallback();
            handoffDetail = pick.detail();
        } else {
            List<MultiAgentGraphs.Node> options = graph.outgoingAgents(current.id());
            if (options.isEmpty()) {
                landed = current;
                handoffDetail = "留在 " + MultiAgentGraphs.labelOf(current);
            } else {
                PickResult pick = pickAmong(options, message, current, true, routerModel, false);
                landed = pick.node() == null ? current : pick.node();
                fallback = pick.fallback();
                handoffDetail = pick.detail();
            }
        }
        routeSpans.add(AgentScopeRuntime.debugSpan("route.handoff", "场景移交", "llm", fallback ? "error" : "ok", 1, handoffDetail));

        return answerAndPersist(user, workflow, graph, landed, message, sid, conversation, endpoint, routeSpans,
                Jsons.ordered(
                        "pattern", MultiAgentGraphs.PATTERN_HANDOFF,
                        "jump", jumpHit,
                        "handoff", !fromId.equals(landed.id()),
                        "stayed", fromId.equals(landed.id()),
                        "fallback", fallback,
                        "focused", focused,
                        "from_id", fromId,
                        "to_id", landed.id(),
                        "to_label", MultiAgentGraphs.labelOf(landed),
                        "detail", handoffDetail));
    }

    private Map<String, Object> runSupervisor(CurrentUser user, Workflow workflow, MultiAgentGraphs.Graph graph,
                                             String message, String sid, Conversation conversation,
                                             String focusNodeId, String endpoint) {
        MultiAgentGraphs.Node main = graph.outgoingAgents(graph.start().id()).get(0);
        List<Map<String, Object>> routeSpans = new ArrayList<>();
        boolean focused = false;
        MultiAgentGraphs.Node landed = main;
        boolean jumpHit = false;
        ModelConfig routerModel = routerModel(user, graph);

        if (focusNodeId != null && !focusNodeId.isBlank()) {
            MultiAgentGraphs.Node focus = graph.node(focusNodeId.strip());
            if (focus != null && "agent".equals(focus.type())) {
                landed = focus;
                focused = true;
            }
        }
        if (!focused) {
            JumpResult jump = evaluateJumps(user, graph, message, routerModel);
            routeSpans.add(jump.span());
            if (jump.landed() != null) {
                landed = jump.landed();
                jumpHit = true;
            }
        }

        List<MultiAgentGraphs.Node> subs = graph.outgoingAgents(main.id());
        boolean useSubs = landed.id().equals(main.id()) && !subs.isEmpty();
        String detail;
        if (focused) {
            detail = "只测节点「" + MultiAgentGraphs.labelOf(landed) + "」";
        } else if (jumpHit) {
            detail = "全局跳转交给「" + MultiAgentGraphs.labelOf(landed) + "」";
        } else if (useSubs) {
            detail = "主智能体「" + MultiAgentGraphs.labelOf(main) + "」可调用 " + subs.size() + " 个子智能体";
        } else {
            detail = "主智能体「" + MultiAgentGraphs.labelOf(main) + "」直接回答";
        }
        routeSpans.add(AgentScopeRuntime.debugSpan("route.supervisor", "主从调度", "llm", "ok", 1, detail));

        Agent agent = access.getRow(user, ResourceKind.AGENT, landed.agentId());
        if (useSubs && httpAgents.isHttpBacked(agent)) {
            throw ApiException.conflict("主从模式的主智能体需要走 AgentScope 运行时，HTTP 接口智能体不能作为主智能体挂 SubAgent");
        }

        List<ToolOverlay.Extra> extras = useSubs ? buildSubTools(user, graph, subs, message, sid) : List.of();
        String supervisorHint = useSubs ? supervisorHint(subs) : "";
        String basePrompt = landed.prompt();
        final String nodePrompt = supervisorHint.isBlank()
                ? basePrompt
                : (basePrompt == null || basePrompt.isBlank() ? supervisorHint : basePrompt + "\n\n" + supervisorHint);

        Instant started = Instant.now();
        ModelConfig model = resolveModel(user, agent);
        List<Map<String, Object>> history = memories.shortTermHistory(user, null, sid);
        history.add(Map.of("role", "user", "content", message));
        ChatReply reply = ToolOverlay.call(extras, () -> PromptOverlay.call(graph.globalPrompt(), nodePrompt,
                () -> playground.generate(agent, model, history, sid, false, false)));
        return persistReply(user, workflow, agent, model, landed, message, sid, conversation, endpoint,
                started, reply, routeSpans, Jsons.ordered(
                        "pattern", MultiAgentGraphs.PATTERN_SUPERVISOR,
                        "jump", jumpHit,
                        "handoff", !main.id().equals(landed.id()),
                        "stayed", main.id().equals(landed.id()),
                        "fallback", false,
                        "focused", focused,
                        "from_id", main.id(),
                        "to_id", landed.id(),
                        "to_label", MultiAgentGraphs.labelOf(landed),
                        "subagents", subs.size(),
                        "detail", detail));
    }

    private Map<String, Object> runPipeline(CurrentUser user, Workflow workflow, MultiAgentGraphs.Graph graph,
                                           String message, String sid, Conversation conversation,
                                           String focusNodeId, String endpoint) {
        List<Map<String, Object>> routeSpans = new ArrayList<>();
        ModelConfig routerModel = routerModel(user, graph);
        MultiAgentGraphs.Node cursor;
        boolean focused = false;
        boolean jumpHit = false;

        if (focusNodeId != null && !focusNodeId.isBlank()) {
            MultiAgentGraphs.Node focus = graph.node(focusNodeId.strip());
            if (focus != null && "agent".equals(focus.type())) {
                cursor = focus;
                focused = true;
            } else {
                cursor = null;
            }
        } else {
            cursor = null;
        }
        if (cursor == null) {
            JumpResult jump = evaluateJumps(user, graph, message, routerModel);
            routeSpans.add(jump.span());
            if (jump.landed() != null) {
                cursor = jump.landed();
                jumpHit = true;
            }
        }
        if (cursor == null) {
            List<MultiAgentGraphs.Node> roots = graph.outgoingAgents(graph.start().id());
            PickResult pick = pickAmong(roots, message, null, false, routerModel, true);
            cursor = pick.node();
            routeSpans.add(AgentScopeRuntime.debugSpan("route.pipeline", "工作流起点", "llm",
                    pick.fallback() ? "error" : "ok", 1, pick.detail()));
        } else {
            routeSpans.add(AgentScopeRuntime.debugSpan("route.pipeline", "工作流起点", "llm", "ok", 1,
                    (focused ? "只测节点 " : jumpHit ? "跳转进入 " : "进入 ") + MultiAgentGraphs.labelOf(cursor)));
        }
        if (cursor == null) {
            return noHandoff(sid);
        }

        String stepInput = message;
        ChatReply lastReply = null;
        Agent lastAgent = null;
        ModelConfig lastModel = null;
        MultiAgentGraphs.Node lastNode = cursor;
        Instant started = Instant.now();
        Set<String> visited = new LinkedHashSet<>();
        int steps = 0;

        while (cursor != null && steps < MultiAgentGraphs.MAX_PIPELINE_STEPS) {
            if (!visited.add(cursor.id())) {
                routeSpans.add(AgentScopeRuntime.debugSpan("route.pipeline", "工作流环路", "llm", "error", 1,
                        "检测到环路，已在「" + MultiAgentGraphs.labelOf(cursor) + "」停止"));
                break;
            }
            steps++;
            Agent agent = access.getRow(user, ResourceKind.AGENT, cursor.agentId());
            ModelConfig model = resolveModel(user, agent);
            String stepMessage = steps == 1
                    ? stepInput
                    : "用户原话：\n" + clip(message, 800) + "\n\n上一步输出：\n" + clip(stepInput, 2000)
                    + "\n\n请基于上一步结果继续完成本环节任务。";
            List<Map<String, Object>> history = List.of(Map.of("role", "user", "content", stepMessage));
            String stepSession = sid + "::pipe::" + cursor.id() + "::" + steps;
            final MultiAgentGraphs.Node stepNode = cursor;
            final String stepPrompt = stepNode.prompt();
            ChatReply reply = ToolOverlay.nest(() -> PromptOverlay.call(graph.globalPrompt(), stepPrompt,
                    () -> playground.generate(agent, model, history, stepSession, false, false)));
            routeSpans.add(AgentScopeRuntime.debugSpan("pipeline.step", "步骤 " + steps + " · " + MultiAgentGraphs.labelOf(stepNode),
                    "agent", "error".equals(reply.mode()) ? "error" : "ok", 1, clip(reply.reply(), 200)));
            if (reply.spans() != null) {
                routeSpans.addAll(reply.spans());
            }
            lastReply = reply;
            lastAgent = agent;
            lastModel = model;
            lastNode = cursor;
            stepInput = reply.reply() == null ? "" : reply.reply();

            if (focused) {
                break;
            }
            List<MultiAgentGraphs.Node> next = graph.outgoingAgents(cursor.id());
            if (next.isEmpty()) {
                break;
            }
            if (next.size() == 1) {
                cursor = next.get(0);
            } else {
                PickResult pick = pickAmong(next, message + "\n\n当前步骤输出：\n" + clip(stepInput, 600),
                        null, false, routerModel, true);
                routeSpans.add(AgentScopeRuntime.debugSpan("route.branch", "工作流分支", "llm",
                        pick.fallback() ? "error" : "ok", 1, pick.detail()));
                cursor = pick.node();
            }
        }

        if (lastReply == null || lastAgent == null) {
            return noHandoff(sid);
        }
        ChatReply merged = new ChatReply(lastReply.reply(), lastReply.mode(), routeSpans, lastReply.usage(), lastReply.traceId());
        Map<String, Object> result = playground.finalizeTurn(user, lastAgent, lastModel, sid, conversation, message, merged,
                started, true, endpoint, Map.of(), null);
        Conversation saved = conversations.findBySessionId(sid).orElse(null);
        if (saved != null) {
            saved.setWorkflowId(workflow.getId());
            saved.setCurrentNodeId(lastNode.id());
            conversations.save(saved);
        }
        summarizer.summarize(user, lastModel, sid, message, lastReply.reply(), lastReply.mode());
        result.put("node_id", lastNode.id());
        result.put("node_label", MultiAgentGraphs.labelOf(lastNode));
        result.put("workflow_id", workflow.getId());
        result.put("route", Jsons.ordered(
                "pattern", MultiAgentGraphs.PATTERN_PIPELINE,
                "jump", jumpHit,
                "handoff", steps > 1,
                "stayed", steps <= 1,
                "fallback", false,
                "focused", focused,
                "from_id", graph.start().id(),
                "to_id", lastNode.id(),
                "to_label", MultiAgentGraphs.labelOf(lastNode),
                "steps", steps,
                "detail", "工作流执行 " + steps + " 步，落在「" + MultiAgentGraphs.labelOf(lastNode) + "」"));
        return result;
    }

    private List<ToolOverlay.Extra> buildSubTools(CurrentUser user, MultiAgentGraphs.Graph graph,
                                                  List<MultiAgentGraphs.Node> subs, String userMessage, String sid) {
        List<ToolOverlay.Extra> extras = new ArrayList<>();
        for (MultiAgentGraphs.Node sub : subs) {
            String toolName = MultiAgentGraphs.subToolName(sub);
            String description = "调用子智能体「" + MultiAgentGraphs.labelOf(sub) + "」完成专项任务。"
                    + "适用场景：" + clip(sub.scenario(), 180)
                    + "。输入明确的任务说明，返回该智能体的完整回复。不要编造它的结果。";
            ToolSpec spec = new ToolSpec(toolName, description,
                    ToolSpec.objectSchema(Map.of("task", ToolSpec.stringParam("交给该子智能体的具体任务或问题")), "task"));
            MultiAgentGraphs.Node target = sub;
            extras.add(new ToolOverlay.Extra(spec, args -> {
                String task = Jsons.text(args.get("task")).strip();
                if (task.isBlank()) {
                    task = userMessage;
                }
                try {
                    Agent subAgent = access.getRow(user, ResourceKind.AGENT, target.agentId());
                    ModelConfig model = resolveModel(user, subAgent);
                    String prompt = "你是编排中的子智能体「" + MultiAgentGraphs.labelOf(target) + "」。"
                            + "只完成交给你的专项任务，不要替主智能体做最终汇总。"
                            + (target.prompt().isBlank() ? "" : "\n\n" + target.prompt());
                    List<Map<String, Object>> history = List.of(Map.of("role", "user", "content",
                            "用户原话：\n" + clip(userMessage, 600) + "\n\n主智能体交给你的任务：\n" + clip(task, 1500)));
                    String subSession = sid + "::sub::" + target.id();
                    ChatReply reply = ToolOverlay.nest(() -> PromptOverlay.call(graph.globalPrompt(), prompt,
                            () -> playground.generate(subAgent, model, history, subSession, false, false)));
                    String text = reply.reply() == null ? "" : reply.reply().strip();
                    return text.isBlank() ? "子智能体没有返回内容" : text;
                } catch (RuntimeException ex) {
                    return "子智能体调用失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                }
            }));
        }
        return extras;
    }

    private static String supervisorHint(List<MultiAgentGraphs.Node> subs) {
        StringBuilder out = new StringBuilder();
        out.append("你是主智能体（Supervisor）。遇到需要专项处理的问题时，先调用对应的子智能体工具，再根据工具返回汇总给用户。");
        out.append("可用子智能体：\n");
        for (MultiAgentGraphs.Node sub : subs) {
            out.append("- ").append(MultiAgentGraphs.subToolName(sub)).append(" → ")
                    .append(MultiAgentGraphs.labelOf(sub)).append("：")
                    .append(clip(sub.scenario(), 120)).append('\n');
        }
        out.append("简单寒暄或你能直接回答的问题，不必调用子智能体。");
        return out.toString();
    }

    private Map<String, Object> answerAndPersist(CurrentUser user, Workflow workflow, MultiAgentGraphs.Graph graph,
                                                 MultiAgentGraphs.Node landed, String message, String sid,
                                                 Conversation conversation, String endpoint,
                                                 List<Map<String, Object>> routeSpans, Map<String, Object> route) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, landed.agentId());
        ModelConfig model = resolveModel(user, agent);
        List<Map<String, Object>> history = memories.shortTermHistory(user, null, sid);
        history.add(Map.of("role", "user", "content", message));
        Instant started = Instant.now();
        ChatReply reply = PromptOverlay.call(graph.globalPrompt(), landed.prompt(),
                () -> playground.generate(agent, model, history, sid, false, false));
        return persistReply(user, workflow, agent, model, landed, message, sid, conversation, endpoint,
                started, reply, routeSpans, route);
    }

    private Map<String, Object> persistReply(CurrentUser user, Workflow workflow, Agent agent, ModelConfig model,
                                             MultiAgentGraphs.Node landed, String message, String sid,
                                             Conversation conversation, String endpoint, Instant started,
                                             ChatReply reply, List<Map<String, Object>> routeSpans,
                                             Map<String, Object> route) {
        List<Map<String, Object>> spans = new ArrayList<>(routeSpans);
        if (reply.spans() != null) {
            spans.addAll(reply.spans());
        }
        ChatReply merged = new ChatReply(reply.reply(), reply.mode(), spans, reply.usage(), reply.traceId());
        Map<String, Object> result = playground.finalizeTurn(user, agent, model, sid, conversation, message, merged,
                started, true, endpoint, Map.of(), null);
        Conversation saved = conversations.findBySessionId(sid).orElse(null);
        if (saved != null) {
            saved.setWorkflowId(workflow.getId());
            saved.setCurrentNodeId(landed.id());
            conversations.save(saved);
        }
        summarizer.summarize(user, model, sid, message, reply.reply(), reply.mode());
        result.put("node_id", landed.id());
        result.put("node_label", MultiAgentGraphs.labelOf(landed));
        result.put("workflow_id", workflow.getId());
        result.put("route", route);
        return result;
    }

    private record JumpResult(MultiAgentGraphs.Node landed, Map<String, Object> span) {}

    private JumpResult evaluateJumps(CurrentUser user, MultiAgentGraphs.Graph graph, String message, ModelConfig routerModel) {
        List<MultiAgentGraphs.Node> jumps = graph.nodes().stream().filter(node -> "jump".equals(node.type())).toList();
        String jumpDetail;
        MultiAgentGraphs.Node landed = null;
        if (jumps.isEmpty()) {
            jumpDetail = "没有全局跳转";
        } else if (routerModel == null) {
            jumpDetail = "模型不可用，跳过全局跳转";
        } else {
            Integer choice = classify(routerModel, jumpPrompt(message, jumps));
            int index = MultiAgentGraphs.resolveChoice(choice, jumps.size(), true, false);
            if (choice == null) {
                jumpDetail = "跳转判断失败，已跳过";
            } else if (index >= 0) {
                List<MultiAgentGraphs.Node> targets = graph.outgoing(jumps.get(index).id(), "agent");
                if (!targets.isEmpty()) {
                    landed = targets.get(0);
                    jumpDetail = "命中「" + clip(jumps.get(index).condition(), 40) + "」，交给"
                            + MultiAgentGraphs.labelOf(landed);
                } else {
                    jumpDetail = "跳转没有目标";
                }
            } else {
                jumpDetail = "未命中 " + jumps.size() + " 条全局跳转";
            }
        }
        return new JumpResult(landed, AgentScopeRuntime.debugSpan("route.jump", "跳转判断", "llm", "ok", 1, jumpDetail));
    }

    private record PickResult(MultiAgentGraphs.Node node, boolean fallback, String detail) {}

    private PickResult pickAmong(List<MultiAgentGraphs.Node> options, String message, MultiAgentGraphs.Node current,
                                 boolean allowStay, ModelConfig routerModel, boolean fallbackFirst) {
        if (options == null || options.isEmpty()) {
            return new PickResult(current, false, current == null ? "没有候选人" : "留在 " + MultiAgentGraphs.labelOf(current));
        }
        if (options.size() == 1 && !allowStay) {
            return new PickResult(options.get(0), false, "只有一个候选人：" + MultiAgentGraphs.labelOf(options.get(0)));
        }
        if (routerModel == null) {
            if (fallbackFirst) {
                return new PickResult(options.get(0), true, "分发失败，已使用默认节点「" + MultiAgentGraphs.labelOf(options.get(0)) + "」");
            }
            return new PickResult(current, true, current == null ? "分发失败" : "分发失败，留在 " + MultiAgentGraphs.labelOf(current));
        }
        Integer choice = classify(routerModel, pickPrompt(message, current, options, allowStay));
        int index = MultiAgentGraphs.resolveChoice(choice, options.size(), allowStay, fallbackFirst);
        if (choice == null) {
            if (fallbackFirst) {
                return new PickResult(options.get(0), true, "分发失败，已使用默认节点「" + MultiAgentGraphs.labelOf(options.get(0)) + "」");
            }
            return new PickResult(current, true, current == null ? "分发失败" : "分发失败，留在 " + MultiAgentGraphs.labelOf(current));
        }
        if (index < 0) {
            return new PickResult(current, false, "留在 " + MultiAgentGraphs.labelOf(current));
        }
        MultiAgentGraphs.Node landed = options.get(index);
        String detail = allowStay && current != null
                ? "移交给 " + MultiAgentGraphs.labelOf(landed)
                : "候选人里交给 " + MultiAgentGraphs.labelOf(landed);
        return new PickResult(landed, false, detail);
    }

    private Map<String, Object> noHandoff(String sessionId) {
        return Jsons.ordered(
                "mode", "preview",
                "reply", "这条编排还没有可交接的智能体",
                "response", "这条编排还没有可交接的智能体",
                "output", "这条编排还没有可交接的智能体",
                "session_id", sessionId,
                "agent", "",
                "agent_id", null,
                "node_id", "",
                "spans", List.of(AgentScopeRuntime.debugSpan("route.handoff", "场景移交", "llm", "error", 1,
                        "开始节点没有连出智能体")),
                "route", Jsons.ordered("jump", false, "handoff", false, "stayed", false, "fallback", true,
                        "detail", "开始节点没有连出智能体"));
    }

    private boolean agentVisible(CurrentUser user, long agentId) {
        try {
            access.getRow(user, ResourceKind.AGENT, agentId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private ModelConfig routerModel(CurrentUser user, MultiAgentGraphs.Graph graph) {
        for (MultiAgentGraphs.Node node : graph.nodes()) {
            if (!"agent".equals(node.type()) || node.agentId() == null || !agentVisible(user, node.agentId())) {
                continue;
            }
            Agent agent = access.getRow(user, ResourceKind.AGENT, node.agentId());
            ModelConfig model = resolveModel(user, agent);
            if (model != null && model.getId() != null && !AgentScopeRuntime.resolveCredential(model).isBlank()) {
                return model;
            }
        }
        return null;
    }

    private ModelConfig resolveModel(CurrentUser user, Agent agent) {
        if (httpAgents.isHttpBacked(agent)) {
            HttpAgent http = httpAgents.primary(agent);
            return HttpAgentRuntime.displayModel(http);
        }
        String name = agent.getModelName() == null ? "" : agent.getModelName().strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("智能体「" + agent.getName() + "」还没有绑定模型");
        }
        ModelConfig model = models.findByName(name)
                .filter(row -> row.getTenantId() == null || row.getTenantId().equals(user.getTenantId()))
                .orElseThrow(() -> ApiException.badRequest("智能体绑定的模型不存在：" + name));
        if (!model.isEnabled()) {
            throw ApiException.conflict("智能体绑定的模型已停用：" + name);
        }
        return model;
    }

    private Integer classify(ModelConfig model, String userPrompt) {
        try {
            return MultiAgentGraphs.parseChoice(complete(model, userPrompt));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String complete(ModelConfig model, String userPrompt) {
        String credential = AgentScopeRuntime.resolveCredential(model);
        if (credential.isBlank()) {
            return "";
        }
        String url = AgentScopeRuntime.modelEndpoint(model) + "/chat/completions";
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model.getModelId(),
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你只负责选择编号。只输出 JSON，不要解释。"),
                            Map.of("role", "user", "content", userPrompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? "" : content.asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private static String jumpPrompt(String message, List<MultiAgentGraphs.Node> jumps) {
        StringBuilder out = new StringBuilder();
        out.append("判断用户原话是否符合下面某一条全局跳转。符合就输出 {\"choice\":编号}，编号从 0 开始。都不符合输出 {\"choice\":-1}。\n用户原话：\n");
        out.append(clip(message, 800)).append("\n条件：\n");
        for (int i = 0; i < jumps.size(); i++) {
            out.append(i).append(". ").append(clip(jumps.get(i).condition(), 200)).append('\n');
        }
        return out.toString();
    }

    private static String pickPrompt(String message, MultiAgentGraphs.Node current, List<MultiAgentGraphs.Node> options, boolean allowStay) {
        StringBuilder out = new StringBuilder();
        if (allowStay) {
            out.append("决定这句话留给当前智能体，还是移交给候选人。移交输出 {\"choice\":编号}，编号从 0 开始。留下输出 {\"choice\":-1}。\n");
            out.append("当前智能体：").append(MultiAgentGraphs.labelOf(current)).append('\n');
        } else {
            out.append("必须从候选人里选一个接手这句话。只输出 {\"choice\":编号}，编号从 0 开始。\n");
        }
        out.append("用户原话：\n").append(clip(message, 800)).append("\n候选人：\n");
        for (int i = 0; i < options.size(); i++) {
            MultiAgentGraphs.Node node = options.get(i);
            out.append(i).append(". ").append(MultiAgentGraphs.labelOf(node)).append("：")
                    .append(clip(node.scenario().isBlank() ? "未写适用场景" : node.scenario(), 200)).append('\n');
        }
        return out.toString();
    }

    private static String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
