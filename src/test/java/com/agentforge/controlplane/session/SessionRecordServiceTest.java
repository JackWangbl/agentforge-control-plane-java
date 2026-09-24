package com.agentforge.controlplane.session;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRecordServiceTest {

    @Mock ConversationRepository conversations;
    @Mock ChatMessageRepository messages;
    @Mock TraceRepository traces;
    @Mock AgentRepository agents;
    @Mock WorkspaceStore workspaces;
    @InjectMocks SessionRecordService records;

    @Test
    void clearOneRemovesMessagesTracesAndWorkspaceFiles() {
        Conversation row = conversation("debug_1", 1L, 3L);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setTenantId(1L);
        Trace trace = new Trace();
        trace.setTraceId("abc123");
        when(conversations.findBySessionId("debug_1")).thenReturn(Optional.of(row));
        when(agents.findById(3L)).thenReturn(Optional.of(agent));
        when(traces.findBySessionIdOrderByStartedAtDesc("debug_1")).thenReturn(List.of(trace));

        Map<String, Object> result = records.clearOne(user(1L), "debug_1");

        assertEquals(1, result.get("deleted"));
        assertEquals(List.of("debug_1"), result.get("session_ids"));
        verify(workspaces).deleteSessionFiles(eq(agent), eq("debug_1"), eq(List.of("abc123")));
        verify(messages).deleteBySessionIdIn(List.of("debug_1"));
        verify(traces).deleteBySessionIdIn(List.of("debug_1"));
        verify(conversations).deleteAll(List.of(row));
    }

    @Test
    void clearOneHidesAnotherTenant() {
        when(conversations.findBySessionId("debug_9")).thenReturn(Optional.of(conversation("debug_9", 9L, 3L)));

        assertThrows(ApiException.class, () -> records.clearOne(user(1L), "debug_9"));
        verify(messages, never()).deleteBySessionIdIn(any());
        verify(conversations, never()).deleteAll(any());
        verify(workspaces, never()).deleteSessionFiles(any(), any(), any());
    }

    @Test
    void clearMatchingDeletesEveryVisibleRow() {
        Conversation first = conversation("debug_1", 1L, null);
        Conversation second = conversation("debug_2", null, null);
        when(conversations.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(first, second));

        Map<String, Object> result = records.clearMatching(user(1L), null, null, null, null, null);

        assertEquals(2, result.get("deleted"));
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(messages).deleteBySessionIdIn(ids.capture());
        assertEquals(List.of("debug_1", "debug_2"), ids.getValue());
        verify(workspaces, never()).deleteSessionFiles(any(), any(), any());
    }

    private static Conversation conversation(String sessionId, Long tenantId, Long agentId) {
        Conversation row = new Conversation();
        row.setSessionId(sessionId);
        row.setTenantId(tenantId);
        row.setAgentId(agentId);
        return row;
    }

    private static CurrentUser user(Long tenantId) {
        return new CurrentUser(7L, "linmo", "林默", tenantId, tenantId, "默认租户", 1L, "管理员", List.of("session:write"));
    }
}
