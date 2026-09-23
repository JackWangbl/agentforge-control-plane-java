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

    private static CurrentUser user(boolean trial) {
        CurrentUser user = new CurrentUser(7L, "linmo", "林默", 1L, 1L, "默认租户", 1L, "管理员", List.of("*"));
        if (trial) {
            user.setTrialExpiresAt(Instant.parse("2026-09-23T04:00:00Z"));
        }
        return user;
    }
}
