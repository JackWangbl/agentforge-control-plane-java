package com.agentforge.controlplane.runtime.http;

public class ThirdPartyCallException extends RuntimeException {
    private final String category;
    private final Integer statusCode;
    private final boolean transientFailure;
    private final int attempts;
    private final long elapsedMs;
    private final boolean retryBlockedBySemantics;

    public ThirdPartyCallException(String category, Integer statusCode, boolean transientFailure, int attempts,
                                   long elapsedMs, boolean retryBlockedBySemantics, String safeSummary,
                                   Throwable cause) {
        super(safeSummary, cause);
        this.category = category;
        this.statusCode = statusCode;
        this.transientFailure = transientFailure;
        this.attempts = attempts;
        this.elapsedMs = elapsedMs;
        this.retryBlockedBySemantics = retryBlockedBySemantics;
    }

    public String getCategory() { return category; }
    public Integer getStatusCode() { return statusCode; }
    public boolean isTransientFailure() { return transientFailure; }
    public int getAttempts() { return attempts; }
    public long getElapsedMs() { return elapsedMs; }
    public boolean isRetryBlockedBySemantics() { return retryBlockedBySemantics; }
}
