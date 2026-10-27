package com.agentforge.controlplane.agent;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aMessagesTest {

    @Test
    void addressesStayUnderTheAgentPath() {
        assertEquals("/api/a2a/agents/22", A2aAddresses.rpcPath(22L));
        assertEquals("/api/a2a/agents/22/.well-known/agent-card.json", A2aAddresses.cardPath(22L));
        assertEquals("http://127.0.0.1:8080/api/a2a/agents/22", A2aAddresses.endpoint(8080, 22L));
        URI card = URI.create(A2aAddresses.cardBase(8080, 22L)).resolve(A2aAddresses.cardRelativePath());
        assertEquals("http://127.0.0.1:8080/api/a2a/agents/22/.well-known/agent-card.json", card.toString());
    }

    @Test
    void headerLookupIgnoresCase() {
        assertEquals("abc", A2aAddresses.header(Map.of("x-agentforge-call", "abc"), A2aAddresses.CALL_HEADER));
        assertNull(A2aAddresses.header(Map.of("Authorization", " "), "Authorization"));
    }

    @Test
    void replyEventsCarryTheFullText() {
        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) A2aMessages.replyEvents("您稍等 技术帮您查一下").get(0);
        assertEquals("您稍等 技术帮您查一下", delta.getDelta());
        assertEquals("您好", A2aMessages.textOf(List.of(
                Msg.builder().role(MsgRole.USER).textContent("您好").build())));
    }

    @Test
    void executionFailureIsNotANormalReply() {
        assertTrue(A2aMessages.failed("Agent execution failed: boom"));
        assertFalse(A2aMessages.failed("您稍等 技术帮您查一下"));
    }
}
