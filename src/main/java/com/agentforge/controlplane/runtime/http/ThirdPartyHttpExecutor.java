package com.agentforge.controlplane.runtime.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class ThirdPartyHttpExecutor {
    private static final Logger log = LoggerFactory.getLogger(ThirdPartyHttpExecutor.class);
    private final HttpClient client;

    public ThirdPartyHttpExecutor() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public ThirdPartyHttpExecutor(HttpClient client) {
        this.client = client;
    }

    public <T> HttpResponse<T> execute(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                       HttpRequestSemantics semantics, RetryPolicy policy, Instant deadline,
                                       String operation, String remoteHost) {
        HttpRequestSemantics effectiveSemantics = semantics == null
                ? HttpRequestSemantics.SIDE_EFFECTING_OR_UNKNOWN : semantics;
        RetryPolicy effectivePolicy = policy == null ? RetryPolicy.defaults() : policy;
        int maxAttempts = effectiveSemantics == HttpRequestSemantics.SIDE_EFFECTING_OR_UNKNOWN
                ? 1 : effectivePolicy.totalAttempts();
        long started = System.nanoTime();
        Throwable lastError = null;
        Integer lastStatus = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, handler);
                lastStatus = response.statusCode();
                if (!effectivePolicy.retryableStatuses().contains(lastStatus)) return response;
                if (attempt >= maxAttempts || !waitForNext(response, attempt, effectivePolicy, deadline)) {
                    throw failure("UPSTREAM_HTTP_" + lastStatus, lastStatus, true, attempt, started,
                            effectiveSemantics == HttpRequestSemantics.SIDE_EFFECTING_OR_UNKNOWN, operation, remoteHost, null);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure("INTERRUPTED", lastStatus, false, attempt, started, false, operation, remoteHost, interrupted);
            } catch (IOException io) {
                lastError = io;
                boolean transientFailure = isTransient(io);
                if (!transientFailure || attempt >= maxAttempts || !waitForNext(null, attempt, effectivePolicy, deadline)) {
                    throw failure(transientFailure ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_PROTOCOL_ERROR", lastStatus,
                            transientFailure, attempt, started,
                            transientFailure && effectiveSemantics == HttpRequestSemantics.SIDE_EFFECTING_OR_UNKNOWN,
                            operation, remoteHost, io);
                }
            }
        }
        throw failure("UPSTREAM_UNAVAILABLE", lastStatus, true, maxAttempts, started, false,
                operation, remoteHost, lastError);
    }

    private boolean waitForNext(HttpResponse<?> response, int attempt, RetryPolicy policy, Instant deadline) {
        long delay = retryAfter(response, policy.maximumRetryAfterMs());
        if (delay < 0) {
            double raw = policy.initialDelayMs() * Math.pow(policy.multiplier(), Math.max(0, attempt - 1));
            delay = Math.min(policy.maximumDelayMs(), Math.round(raw));
            if (policy.jitter() > 0 && delay > 0) {
                double factor = 1 + ThreadLocalRandom.current().nextDouble(-policy.jitter(), policy.jitter());
                delay = Math.max(0, Math.round(delay * factor));
            }
        }
        if (deadline == null || Instant.now().plusMillis(delay + 1).isAfter(deadline)) return false;
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long retryAfter(HttpResponse<?> response, long maximum) {
        if (response == null) return -1;
        String value = response.headers().firstValue("Retry-After").orElse("").strip();
        if (value.isEmpty()) return -1;
        try {
            return Math.min(maximum, Math.max(0, Long.parseLong(value) * 1000));
        } catch (NumberFormatException ignored) {
            try {
                return Math.min(maximum, Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - Instant.now().toEpochMilli()));
            } catch (Exception invalidDate) {
                return -1;
            }
        }
    }

    private static boolean isTransient(IOException error) {
        if (error instanceof SSLException) return false;
        return error instanceof ConnectException || error instanceof SocketException
                || error instanceof HttpConnectTimeoutException || error instanceof HttpTimeoutException;
    }

    private ThirdPartyCallException failure(String category, Integer status, boolean transientFailure, int attempts,
                                             long started, boolean blocked, String operation, String remoteHost,
                                             Throwable cause) {
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        log.warn("Third-party call failed operation={} host={} category={} status={} attempts={} elapsedMs={} blockedBySemantics={}",
                safeLabel(operation), safeLabel(remoteHost), category, status, attempts, elapsed, blocked);
        return new ThirdPartyCallException(category, status, transientFailure, attempts, elapsed, blocked,
                "第三方服务调用失败（" + category + "）", cause);
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._:-]", "_").substring(0, Math.min(120, value.length()));
    }
}
