package com.agentforge.controlplane.runtime.http;

import java.util.Set;

public record RetryPolicy(int totalAttempts, long initialDelayMs, double multiplier, long maximumDelayMs,
                          double jitter, long maximumRetryAfterMs, Set<Integer> retryableStatuses) {
    public static final Set<Integer> DEFAULT_STATUSES = Set.of(408, 425, 429, 500, 502, 503, 504);

    public RetryPolicy {
        if (totalAttempts < 1 || totalAttempts > 5) throw new IllegalArgumentException("totalAttempts must be 1..5");
        if (initialDelayMs < 0 || initialDelayMs > 5000) throw new IllegalArgumentException("initialDelayMs must be 0..5000");
        if (multiplier < 1 || multiplier > 4) throw new IllegalArgumentException("multiplier must be 1..4");
        if (maximumDelayMs < 0 || maximumDelayMs > 5000) throw new IllegalArgumentException("maximumDelayMs must be 0..5000");
        if (jitter < 0 || jitter > 0.5) throw new IllegalArgumentException("jitter must be 0..0.5");
        if (maximumRetryAfterMs < 0 || maximumRetryAfterMs > 30_000) throw new IllegalArgumentException("maximumRetryAfterMs must be 0..30000");
        retryableStatuses = retryableStatuses == null ? DEFAULT_STATUSES : Set.copyOf(retryableStatuses);
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 200, 2, 2000, 0.20, 5000, DEFAULT_STATUSES);
    }
}
