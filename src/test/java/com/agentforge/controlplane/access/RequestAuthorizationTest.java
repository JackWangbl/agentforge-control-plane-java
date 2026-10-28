package com.agentforge.controlplane.access;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestAuthorizationTest {

    @Test
    void storesTheRawLoginHeadersAndClearsThem() {
        RequestAuthorization.set("Bearer token", "7");
        RequestAuthorization.setClientIp("203.0.113.9");
        assertEquals("Bearer token", RequestAuthorization.authorization());
        assertEquals("7", RequestAuthorization.tenantId());
        assertEquals("203.0.113.9", RequestAuthorization.clientIp());
        RequestAuthorization.clear();
        assertNull(RequestAuthorization.authorization());
        assertNull(RequestAuthorization.tenantId());
        assertNull(RequestAuthorization.clientIp());
    }
}
