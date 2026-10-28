package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.access.RequestAuthorization;
import com.agentforge.controlplane.domain.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @AfterEach
    void tearDown() {
        RequestAuthorization.clear();
    }

    @Test
    void stampWritesCurrentRequestIpOntoConversation() {
        RequestAuthorization.setClientIp("198.51.100.24");
        Conversation conversation = new Conversation();
        ClientIpResolver.stamp(conversation);
        assertThat(conversation.getIpAddress()).isEqualTo("198.51.100.24");
    }

    @Test
    void stampIgnoresBlankAndUnknown() {
        Conversation conversation = new Conversation();
        ClientIpResolver.stamp(conversation);
        assertThat(conversation.getIpAddress()).isEmpty();
        RequestAuthorization.setClientIp("unknown");
        ClientIpResolver.stamp(conversation);
        assertThat(conversation.getIpAddress()).isEmpty();
    }
}
