package com.agentforge.controlplane.access;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestAuthorizationTest {

    @Test
    void storesTheRawLoginHeadersAndClearsThem() {
        RequestAuthorization.set("Bearer token", "7");
        assertEquals("Bearer token", RequestAuthorization.authorization());
        assertEquals("7", RequestAuthorization.tenantId());
        RequestAuthorization.clear();
        assertNull(RequestAuthorization.authorization());
        assertNull(RequestAuthorization.tenantId());
    }
}
