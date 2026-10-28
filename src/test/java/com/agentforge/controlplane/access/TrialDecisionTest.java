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
        assertEquals(10, AuthService.TRIAL_TTL.toMinutes());
        assertEquals(10_000, AuthService.TRIAL_TOKEN_LIMIT);
        assertEquals(false, AuthService.trialTokensExhausted(9_999));
        assertEquals(true, AuthService.trialTokensExhausted(10_000));
        assertEquals(true, AuthService.trialTokensExhausted(12_345));
    }
}
