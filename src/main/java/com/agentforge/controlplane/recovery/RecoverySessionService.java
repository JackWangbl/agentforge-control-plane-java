package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把重拾成功写入「会话查询」依赖的平台会话事实。工作区 JSON 不能替代这里。
 */
@Service
public class RecoverySessionService {
    public record Persisted(String sessionId, String traceId) {}

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final TraceRepository traces;
    private final WorkspaceStore workspaces;
    private final ResourceAccessService access;
    private final MemoryService memories;

    public RecoverySessionService(ConversationRepository conversations, ChatMessageRepository messages,
                                  TraceRepository traces, WorkspaceStore workspaces, ResourceAccessService access,
                                  MemoryService memories) {
        this.conversations = conversations;
        this.messages = messages;
        this.traces = traces;
        this.workspaces = workspaces;
        this.access = access;
        this.memories = memories;
    }

    @Transactional
    public Persisted persistSuccess(CurrentUser user, Agent agent, ModelConfig model, ExecutionRecord original,
                                    ChatReply reply, Instant started, String userMessage) {
        if (user == null) throw new IllegalStateException("重拾缺少发起用户，无法写入会话查询");
        if (agent == null) throw new IllegalStateException("重拾缺少 Agent，无法写入会话查询");
        String sessionId = original == null ? "" : original.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("重拾缺少会话标识，无法写入会话查询");
        }
        if (reply == null || "error".equals(reply.mode())) {
            throw new IllegalStateException("重拾没有成功回复，无法写入会话查询");
        }
        String assistant = reply.reply() == null ? "" : reply.reply();
        if (assistant.isBlank()) throw new IllegalStateException("重拾没有助手回复，无法写入会话查询");
        String message = userMessage == null ? "" : userMessage;
        Conversation conversation = conversations.findBySessionId(sessionId).orElse(null);
        if (conversation != null) {
            if (conversation.getTenantId() != null && !conversation.getTenantId().equals(user.getTenantId())) {
                throw new IllegalStateException("会话属于其他租户");
            }
            if (conversation.getAgentId() != null && !conversation.getAgentId().equals(agent.getId())) {
                throw new IllegalStateException("会话属于另一个 Agent");
            }
        }
        List<ChatMessage> existing = messages.findBySessionIdOrderByIdAsc(sessionId);
        boolean includeUser = !message.isBlank() && existing.stream()
                .noneMatch(row -> "user".equals(row.getRole()) && message.equals(row.getContent()));
        ChatMessage last = existing.isEmpty() ? null : existing.get(existing.size() - 1);
        boolean includeAssistant = last == null || !"assistant".equals(last.getRole())
                || !assistant.equals(last.getContent());
        int added = 0;
        if (includeUser) {
            messages.save(newMessage(user, sessionId, agent, "user", message));
            added++;
        }
        if (includeAssistant) {
            messages.save(newMessage(user, sessionId, agent, "assistant", assistant));
            added++;
        }
        int latencyMs = Math.max(1, (int) (Instant.now().toEpochMilli() - started.toEpochMilli()));
        int tokens = reply.totalTokens() > 0 ? reply.totalTokens()
                : Math.max(12, (includeUser ? message.length() : 0) + assistant.length());
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setSessionId(sessionId);
            conversation.setUserId(user.getUsername());
            conversation.setAgentId(agent.getId());
            conversation.setAgentName(agent.getName() == null ? "" : agent.getName());
            conversation.setTitle(cut(message.isBlank() ? assistant : message, 80));
            conversation.setStatus("completed");
            conversation.setMessageCount(added);
            conversation.setTotalTokens(tokens);
            conversation.setLatencyMs(latencyMs);
            conversation.setChannel(channel(original.getEntryPoint()));
            conversation.setWorkflowId(original.getWorkflowId());
            memories.bindSubject(user, conversation);
            access.stampOwner(conversation, user);
        } else {
            conversation.setMessageCount(conversation.getMessageCount() + added);
            conversation.setTotalTokens(conversation.getTotalTokens() + tokens);
            conversation.setLatencyMs(latencyMs);
            conversation.setStatus("completed");
            conversation.setAgentId(agent.getId());
            conversation.setAgentName(agent.getName() == null ? conversation.getAgentName() : agent.getName());
            if (includeUser && !message.isBlank()) conversation.setTitle(cut(message, 80));
            conversation.setUpdatedAt(Instant.now());
            memories.bindSubject(user, conversation);
            if (conversation.getTenantId() == null) conversation.setTenantId(user.getTenantId());
            if (conversation.getOwnerId() == null) conversation.setOwnerId(user.getId());
            if (conversation.getUserId() == null || conversation.getUserId().isBlank()) {
                conversation.setUserId(user.getUsername());
            }
            if (conversation.getWorkflowId() == null) conversation.setWorkflowId(original.getWorkflowId());
        }
        conversations.save(conversation);
        String traceId = reply.traceId() == null || reply.traceId().isBlank()
                ? WorkspaceStore.newTraceId() : reply.traceId();
        List<Map<String, Object>> spans = reply.spans() == null ? List.of() : reply.spans();
        Trace trace = traces.findByTraceId(traceId).orElseGet(Trace::new);
        if (trace.getId() == null) {
            trace.setTraceId(traceId);
            access.stampOwner(trace, user);
        }
        trace.setSessionId(sessionId);
        trace.setAgentId(agent.getId());
        trace.setAgentName(agent.getName() == null ? "" : agent.getName());
        trace.setOperation("failure-case.recover");
        trace.setStatus("ok");
        trace.setDurationMs(latencyMs);
        trace.setInputTokens(reply.promptTokens() > 0 ? reply.promptTokens() : (includeUser ? message.length() : 0));
        trace.setOutputTokens(reply.completionTokens() > 0 ? reply.completionTokens() : assistant.length());
        trace.setSpans(spans);
        trace.setLangfuseUrl("");
        trace.setStartedAt(started);
        traces.save(trace);
        workspaces.persistRun(agent, sessionId, conversation.getTitle(), message, assistant, reply.mode(),
                model == null ? "" : model.getName(), traceId, spans, usageMap(reply), latencyMs, includeUser);
        return new Persisted(sessionId, traceId);
    }

    static String channel(ExecutionRecord.EntryPoint entryPoint) {
        return entryPoint == ExecutionRecord.EntryPoint.PLAYGROUND
                || entryPoint == ExecutionRecord.EntryPoint.WORKFLOW_DEBUG
                ? "Playground" : "API";
    }

    private ChatMessage newMessage(CurrentUser user, String sessionId, Agent agent, String role, String content) {
        ChatMessage row = new ChatMessage();
        row.setSessionId(sessionId);
        row.setAgentId(agent.getId());
        row.setRole(role);
        row.setContent(content);
        row.setAgentName(agent.getName() == null ? "" : agent.getName());
        access.stampOwner(row, user);
        return row;
    }

    private static Map<String, Object> usageMap(ChatReply reply) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (reply.usage() != null) usage.putAll(reply.usage());
        usage.putIfAbsent("prompt_tokens", reply.promptTokens());
        usage.putIfAbsent("completion_tokens", reply.completionTokens());
        usage.putIfAbsent("total_tokens", reply.totalTokens());
        return usage;
    }

    private static String cut(String value, int limit) {
        if (value == null || value.isBlank()) return "重拾会话";
        String text = value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
