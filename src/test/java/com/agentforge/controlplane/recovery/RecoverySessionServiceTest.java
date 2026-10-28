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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoverySessionServiceTest {

    @Mock ConversationRepository conversations;
    @Mock ChatMessageRepository messages;
    @Mock TraceRepository traces;
    @Mock WorkspaceStore workspaces;
    @Mock ResourceAccessService access;
    @Mock MemoryService memories;

    RecoverySessionService service;

    @BeforeEach
    void setUp() {
        service = new RecoverySessionService(conversations, messages, traces, workspaces, access, memories);
        org.mockito.Mockito.lenient().when(conversations.save(any(Conversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(messages.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(traces.save(any(Trace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(traces.findByTraceId(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(workspaces.persistRun(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyBoolean())).thenReturn(Map.of());
    }

    @Test
    void createsSessionMessagesAndTraceForSessionQuery() {
        when(conversations.findBySessionId("debug_ticket")).thenReturn(Optional.empty());
        when(messages.findBySessionIdOrderByIdAsc("debug_ticket")).thenReturn(List.of());

        RecoverySessionService.Persisted persisted = service.persistSuccess(
                user(), agent(), model(), original(), reply(), Instant.now().minusSeconds(2),
                "我的帖子为什么无法发布");

        assertThat(persisted.sessionId()).isEqualTo("debug_ticket");
        assertThat(persisted.traceId()).isEqualTo("trace_recover");

        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(conversationCaptor.capture());
        Conversation conversation = conversationCaptor.getValue();
        assertThat(conversation.getSessionId()).isEqualTo("debug_ticket");
        assertThat(conversation.getAgentName()).isEqualTo("b端工单");
        assertThat(conversation.getChannel()).isEqualTo("Playground");
        assertThat(conversation.getStatus()).isEqualTo("completed");
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getTitle()).contains("帖子");

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues()).extracting(ChatMessage::getRole)
                .containsExactly("user", "assistant");
        assertThat(messageCaptor.getAllValues()).extracting(ChatMessage::getContent)
                .containsExactly("我的帖子为什么无法发布", "已从失败点继续，帖子可以发布了");

        ArgumentCaptor<Trace> traceCaptor = ArgumentCaptor.forClass(Trace.class);
        verify(traces).save(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getSessionId()).isEqualTo("debug_ticket");
        assertThat(traceCaptor.getValue().getOperation()).isEqualTo("failure-case.recover");
        assertThat(traceCaptor.getValue().getStatus()).isEqualTo("ok");

        verify(workspaces).persistRun(any(), eq("debug_ticket"), any(), eq("我的帖子为什么无法发布"),
                eq("已从失败点继续，帖子可以发布了"), eq("ready"), eq("Qwen"), eq("trace_recover"),
                any(), any(), anyInt(), eq(true));
    }

    @Test
    void reusesExistingSessionAndDoesNotDuplicateUserMessage() {
        Conversation existing = new Conversation();
        existing.setId(8L);
        existing.setSessionId("debug_ticket");
        existing.setTenantId(1L);
        existing.setAgentId(22L);
        existing.setAgentName("b端工单");
        existing.setUserId("linmo");
        existing.setTitle("旧标题");
        existing.setStatus("failed");
        existing.setChannel("Playground");
        existing.setMessageCount(1);
        ChatMessage priorUser = new ChatMessage();
        priorUser.setRole("user");
        priorUser.setContent("我的帖子为什么无法发布");
        when(conversations.findBySessionId("debug_ticket")).thenReturn(Optional.of(existing));
        when(messages.findBySessionIdOrderByIdAsc("debug_ticket")).thenReturn(List.of(priorUser));

        service.persistSuccess(user(), agent(), model(), original(), reply(), Instant.now(),
                "我的帖子为什么无法发布");

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, org.mockito.Mockito.times(1)).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("assistant");
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().getId()).isEqualTo(8L);
        assertThat(conversationCaptor.getValue().getStatus()).isEqualTo("completed");
        assertThat(conversationCaptor.getValue().getMessageCount()).isEqualTo(2);
        assertThat(conversationCaptor.getValue().getSessionId()).isEqualTo("debug_ticket");
    }

    @Test
    void rejectsMissingSessionId() {
        ExecutionRecord original = original();
        original.setSessionId(" ");
        assertThatThrownBy(() -> service.persistSuccess(user(), agent(), model(), original, reply(),
                Instant.now(), "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话标识");
        verify(conversations, never()).save(any());
    }

    @Test
    void playgroundChannelMapsFromEntryPoint() {
        assertThat(RecoverySessionService.channel(ExecutionRecord.EntryPoint.PLAYGROUND)).isEqualTo("Playground");
        assertThat(RecoverySessionService.channel(ExecutionRecord.EntryPoint.WORKFLOW_DEBUG)).isEqualTo("Playground");
        assertThat(RecoverySessionService.channel(ExecutionRecord.EntryPoint.AGENT_INVOKE)).isEqualTo("API");
        assertThat(RecoverySessionService.channel(ExecutionRecord.EntryPoint.A2A)).isEqualTo("API");
    }

    @Test
    void originalUserMessagePrefersCheckpointThenRequest() {
        ExecutionRecord original = original();
        original.setRequestSnapshotJson("{\"message\":\"请求里的话\"}");
        assertThat(RecoveryExecutionService.originalUserMessage(original, Map.of("last_user", "检查点原话")))
                .isEqualTo("检查点原话");
        assertThat(RecoveryExecutionService.originalUserMessage(original,
                Map.of("working", List.of(Map.of("role", "user", "content", "历史原话")))))
                .isEqualTo("历史原话");
        assertThat(RecoveryExecutionService.originalUserMessage(original, Map.of())).isEqualTo("请求里的话");
    }

    private static CurrentUser user() {
        return new CurrentUser(2L, "linmo", "林默", 1L, 1L, "默认租户", 1L, "管理员",
                List.of("session:write", "session:read"));
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(22L);
        agent.setTenantId(1L);
        agent.setName("b端工单");
        agent.setWorkspace("workspaces/tenants/1/agents/22-b");
        return agent;
    }

    private static ModelConfig model() {
        ModelConfig model = new ModelConfig();
        model.setName("Qwen");
        return model;
    }

    private static ExecutionRecord original() {
        ExecutionRecord original = new ExecutionRecord();
        original.setId(99L);
        original.setTenantId(1L);
        original.setAgentId(22L);
        original.setAgentName("b端工单");
        original.setSessionId("debug_ticket");
        original.setEntryPoint(ExecutionRecord.EntryPoint.PLAYGROUND);
        original.setRequestSnapshotJson("{\"message\":\"我的帖子为什么无法发布\"}");
        return original;
    }

    private static ChatReply reply() {
        return new ChatReply("已从失败点继续，帖子可以发布了", "ready",
                List.of(Map.of("name", "reply.emit")), Map.of("total_tokens", 24), "trace_recover");
    }
}
