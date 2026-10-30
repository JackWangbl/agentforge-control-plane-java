package com.agentforge.controlplane.access;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrialDecisionTest {

    @Test
    void newBrowserCanStartAndTheSameBrowserKeepsTheSameTrial() {
        assertEquals("new", AuthService.trialDecision(false));
        assertEquals("resume", AuthService.trialDecision(true));
        assertEquals(10_000, AuthService.TRIAL_TOKEN_LIMIT);
        assertEquals(false, AuthService.trialTokensExhausted(9_999));
        assertEquals(true, AuthService.trialTokensExhausted(10_000));
        assertEquals(true, AuthService.trialTokensExhausted(12_345));
    }
}
