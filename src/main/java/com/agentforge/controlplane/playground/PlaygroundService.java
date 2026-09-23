package com.agentforge.controlplane.playground;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.agent.HttpAgentRuntime;
import com.agentforge.controlplane.agent.ToolRuntime;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.experiment.ExperimentService;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.observability.ObservabilityService;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.runtime.BrowserRuntime;
import com.agentforge.controlplane.runtime.ExecutionContext;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlaygroundService {

    private final AgentScopeRuntime runtime;
    private final WorkspaceStore workspaces;
    private final ResourceAccessService access;
    private final ToolRuntime tools;
    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final TraceRepository traces;
    private final ObservabilityService observability;
    private final ExperimentService experiments;
    private final BrowserRuntime browser;
    private final HttpAgentRuntime httpAgents;
    private final MemoryService memories;

    public PlaygroundService(AgentScopeRuntime runtime, WorkspaceStore workspaces, ResourceAccessService access,
                             ToolRuntime tools, ChatMessageRepository messages, ConversationRepository conversations,
                             TraceRepository traces, ObservabilityService observability,
                             ExperimentService experiments, BrowserRuntime browser, HttpAgentRuntime httpAgents,
                             MemoryService memories) {
        this.runtime = runtime;
        this.workspaces = workspaces;
        this.access = access;
        this.tools = tools;
        this.messages = messages;
        this.conversations = conversations;
        this.traces = traces;
        this.observability = observability;
        this.experiments = experiments;
        this.browser = browser;
        this.httpAgents = httpAgents;
        this.memories = memories;
    }

    @Transactional
    public Map<String, Object> finalizeTurn(CurrentUser user, Agent agent, ModelConfig model, String sessionId,
                                            Conversation conversation, String message, ChatReply reply,
                                            Instant started, boolean includeUser, String operation,
                                            Map<String, Object> assignment, Experiment experiment) {
        int latencyMs = Math.max(1, (int) ((Instant.now().toEpochMilli() - started.toEpochMilli())));
        List<Map<String, Object>> spans = buildDebugSpans(agent, model, reply.mode(), reply.spans(), latencyMs);
        if (includeUser && message != null && !message.isBlank()) {
            messages.save(newMessage(user, sessionId, agent, "user", message));
        }
        messages.save(newMessage(user, sessionId, agent, "assistant", reply.reply()));
        int added = includeUser && message != null && !message.isBlank() ? 2 : 1;
        int tokens = Math.max(12, (includeUser ? message.length() : 0) + reply.reply().length());
        if (reply.totalTokens() > 0) {
            tokens = reply.totalTokens();
        }
        if (conversation != null) {
            conversation.setMessageCount(conversation.getMessageCount() + added);
            conversation.setTotalTokens(conversation.getTotalTokens() + tokens);
            conversation.setLatencyMs(latencyMs);
            conversation.setStatus("completed");
            conversation.setAgentId(agent.getId());
            conversation.setAgentName(agent.getName());
            if (includeUser && message != null && !message.isBlank()) {
                conversation.setTitle(cut(message, 80));
            }
            conversation.setUpdatedAt(Instant.now());
            memories.bindSubject(user, conversation);
            if (conversation.getTenantId() == null) {
                conversation.setTenantId(user.getTenantId());
            }
            if (conversation.getOwnerId() == null) {
                conversation.setOwnerId(user.getId());
            }
            conversation.setUserId(user.getUsername());
            conversations.save(conversation);
        } else {
            conversation = new Conversation();
            conversation.setSessionId(sessionId);
            conversation.setUserId(user.getUsername());
            conversation.setAgentId(agent.getId());
            conversation.setAgentName(agent.getName());
            conversation.setTitle(cut((message == null || message.isBlank() ? reply.reply() : message), 80));
            conversation.setStatus("completed");
            conversation.setMessageCount(added);
            conversation.setTotalTokens(tokens);
            conversation.setLatencyMs(latencyMs);
            conversation.setChannel("Playground");
            memories.bindSubject(user, conversation);
            access.stampOwner(conversation, user);
            conversations.save(conversation);
        }
        String traceId = reply.traceId() == null || reply.traceId().isBlank()
                ? WorkspaceStore.newTraceId() : reply.traceId();
        Map<String, Object> usage = usageMap(reply);
        Trace trace = new Trace();
        trace.setTraceId(traceId);
        trace.setSessionId(sessionId);
        trace.setAgentId(agent.getId());
        trace.setAgentName(agent.getName());
        trace.setOperation(operation);
        trace.setStatus("error".equals(reply.mode()) ? "error" : "ok");
        trace.setDurationMs(latencyMs);
        trace.setInputTokens(intValue(usage.get("prompt_tokens"), includeUser ? message.length() : 0));
        trace.setOutputTokens(intValue(usage.get("completion_tokens"), reply.reply().length()));
        trace.setSpans(spans);
        trace.setLangfuseUrl("");
        trace.setStartedAt(started);
        access.stampOwner(trace, user);
        traces.save(trace);
        Map<String, Object> persisted = workspaces.persistRun(
                agent, sessionId, message == null || message.isBlank() ? conversation.getTitle() : message,
                message, reply.reply(), reply.mode(),                 model == null ? "" : model.getName(), traceId, spans, usage, latencyMs, includeUser);
        observability.exportPlayground(agent.getName(), sessionId, traceId, message, reply.reply(), reply.mode(),
                model == null ? "" : model.getName(), model == null ? "" : model.getModelId(), spans, usage, latencyMs,
                message == null || message.isBlank() ? conversation.getTitle() : message);
        if (experiment != null) {
            experiments.recordRun(experiment, assignment, sessionId, reply.mode(), latencyMs,
                    intValue(usage.get("total_tokens"), tokens), user);
        }
        Object storedMessages = persisted.get("messages");
        return Jsons.ordered(
                "mode", reply.mode(),
                "reply", reply.reply(),
                "response", reply.reply(),
                "output", reply.reply(),
                "trace_id", traceId,
                "session_id", sessionId,
                "model", model == null ? "" : model.getName(),
                "agent", agent.getName(),
                "agent_id", agent.getId(),
                "workspace", agent.getWorkspace(),
                "messages", storedMessages == null ? List.of() : storedMessages,
                "spans", spans,
                "latency_ms", latencyMs,
                "usage", usage,
                "experiment", assignment == null || assignment.isEmpty() ? null : assignment,
                "checkpoint", workspaces.checkpointPublic(workspaces.loadCheckpoint(agent, sessionId)));
    }

    public ChatReply generate(Agent agent, ModelConfig model, List<Map<String, Object>> history,
                              String sessionId, boolean resume, boolean forceRerun) {
        if (httpAgents.isHttpBacked(agent)) {
            String lastUser = lastUserMessage(history);
            return httpAgents.chat(agent, lastUser, sessionId);
        }
        ExecutionContext context = ExecutionContext.forAgent(agent, sessionId + "-" + WorkspaceStore.newTraceId());
        return ExecutionContext.runScoped(context, browser,
                () -> runtime.generate(agent, model, history, sessionId, resume, forceRerun));
    }

    private static String lastUserMessage(List<Map<String, Object>> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> item = history.get(i);
            if ("user".equals(Jsons.text(item.get("role")))) {
                return Jsons.text(item.get("content"));
            }
        }
        return "";
    }

    public List<Map<String, Object>> buildDebugSpans(Agent agent, ModelConfig model, String mode,
                                                     List<Map<String, Object>> toolSpans, int latencyMs) {
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(AgentScopeRuntime.debugSpan("user.message", "接收用户消息", "input", "ok", 2, ""));
        HttpAgent http = httpAgents.primary(agent);
        if (http != null) {
            spans.add(AgentScopeRuntime.debugSpan("agent.resolve", "外部 Agent · " + agent.getName(), "agent", "ok", 4,
                    HttpAgentRuntime.protocolLabel(http.getProtocol()) + " · " + http.getEndpoint()));
            if (toolSpans != null) {
                spans.addAll(toolSpans);
            }
            spans.add(AgentScopeRuntime.debugSpan("reply.emit", "回写外部回复", "output",
                    "error".equals(mode) ? "error" : "ok", 4, ""));
            return spans;
        }
        spans.add(AgentScopeRuntime.debugSpan("agent.resolve", "解析 Agent · " + agent.getName(), "agent", "ok", 8,
                agent.getSystemPrompt().isBlank() ? agent.getDescription() : agent.getSystemPrompt()));
        List<Skill> skills = tools.selectedSkills(agent);
        List<McpServer> mcps = tools.selectedMcps(agent);
        List<String> toolNames = new ArrayList<>();
        for (McpServer mcp : mcps) {
            tools.listMcpTools(mcp).forEach(tool -> toolNames.add(String.valueOf(tool.get("name"))));
        }
        if (skills.isEmpty()) {
            spans.add(AgentScopeRuntime.debugSpan("skill.inject", "未关联技能", "skill", "skip", 0,
                    "可在 Agent 编辑页勾选技能"));
        } else {
            spans.add(AgentScopeRuntime.debugSpan("skill.inject", "注入 " + skills.size() + " 个技能", "skill", "ok", 4,
                    String.join("、", skills.stream().map(Skill::getName).toList())));
        }
        if (toolNames.isEmpty()) {
            spans.add(AgentScopeRuntime.debugSpan("mcp.bind", "未关联工具", "mcp", "skip", 0,
                    "可在 Agent 编辑页勾选 MCP"));
        } else {
            spans.add(AgentScopeRuntime.debugSpan("mcp.bind", "关联 " + toolNames.size() + " 个工具", "mcp", "ok", 4,
                    String.join("、", toolNames)));
        }
        String modelDetail = "preview".equals(mode) ? "预览模式：当前模型没有可用密钥"
                : (model == null ? "" : model.getModelId());
        spans.add(AgentScopeRuntime.debugSpan("model.chat", "调用模型 · " + (model == null ? "" : model.getName()), "llm",
                "error".equals(mode) ? "error" : "ok", Math.max(20, latencyMs - 40), modelDetail));
        if (toolSpans != null) {
            spans.addAll(toolSpans);
        }
        spans.add(AgentScopeRuntime.debugSpan("reply.emit", "生成回复", "output",
                "error".equals(mode) ? "error" : "ok", 6, ""));
        return spans;
    }

    private ChatMessage newMessage(CurrentUser user, String sessionId, Agent agent, String role, String content) {
        ChatMessage row = new ChatMessage();
        row.setSessionId(sessionId);
        row.setAgentId(agent.getId());
        row.setRole(role);
        row.setContent(content);
        row.setAgentName(agent.getName());
        access.stampOwner(row, user);
        return row;
    }

    private static Map<String, Object> usageMap(ChatReply reply) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (reply.usage() != null) {
            reply.usage().forEach(usage::put);
        }
        usage.putIfAbsent("prompt_tokens", reply.promptTokens());
        usage.putIfAbsent("completion_tokens", reply.completionTokens());
        usage.putIfAbsent("total_tokens", reply.totalTokens());
        return usage;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static String cut(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
