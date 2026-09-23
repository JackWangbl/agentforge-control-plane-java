package com.agentforge.controlplane.access;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrialDecisionTest {

    @Test
    void newBrowserCanStartAndTheSameBrowserCannotRestartAfterExpiry() {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        assertEquals("new", AuthService.trialDecision(now, false, null));
        assertEquals("resume", AuthService.trialDecision(now, true, now.plus(AuthService.TRIAL_TTL)));
        assertEquals("expired", AuthService.trialDecision(now, true, now));
        assertEquals("expired", AuthService.trialDecision(now, true, now.minusSeconds(1)));
        assertEquals(5, AuthService.TRIAL_TTL.toMinutes());
    }
}
