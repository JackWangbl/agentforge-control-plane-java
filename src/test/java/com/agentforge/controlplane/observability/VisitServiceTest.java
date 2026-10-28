package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.access.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitServiceTest {

    @Test
    void loggedInUserAndTrialBrowserAreDifferentVisitors() {
        CurrentUser member = user(false);
        CurrentUser trial = user(true);
        MockHttpServletRequest browser = new MockHttpServletRequest();
        browser.setCookies(new jakarta.servlet.http.Cookie("af_trial", "browser-a"));

        assertEquals("user:7", VisitService.visitorKey(member, browser));
        assertEquals("trial:browser-a", VisitService.visitorKey(trial, browser));
        assertTrue(VisitService.ZONE.getId().equals("Asia/Shanghai"));
    }

    @Test
    void resolvesForwardedIpOnlyWhenRequestComesFromTrustedProxy() {
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Forwarded-For", "203.0.113.8, 10.0.0.2");
        assertEquals("203.0.113.8", ClientIpResolver.resolve(proxied));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.9");
        direct.addHeader("X-Forwarded-For", "198.51.100.7");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(direct));
    }

    @Test
    void normalizesIpv4Ipv6AndRejectsInvalidValues() {
        assertEquals("192.0.2.4", ClientIpResolver.normalize("192.0.2.4:8080"));
        assertEquals("2001:db8:0:0:0:0:0:1", ClientIpResolver.normalize("[2001:db8::1]:443"));
        assertEquals("", ClientIpResolver.normalize("not-an-ip"));
    }

    private static CurrentUser user(boolean trial) {
        CurrentUser user = new CurrentUser(7L, "linmo", "林默", 1L, 1L, "默认租户", 1L, "管理员", List.of("*"));
        if (trial) {
            user.setTrialExpiresAt(Instant.parse("2026-09-23T04:00:00Z"));
        }
        return user;
    }
}
