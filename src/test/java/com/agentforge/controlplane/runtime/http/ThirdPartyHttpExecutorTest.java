package com.agentforge.controlplane.runtime.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThirdPartyHttpExecutorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void safeRequestRetries503ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/safe", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            exchange.sendResponseHeaders(status, 0);
            exchange.close();
        });
        server.start();
        HttpRequest request = HttpRequest.newBuilder(uri("/safe")).GET().build();
        ThirdPartyHttpExecutor executor = new ThirdPartyHttpExecutor(HttpClient.newHttpClient());
        HttpResponse<String> response = executor.execute(request, HttpResponse.BodyHandlers.ofString(),
                HttpRequestSemantics.SAFE, fastPolicy(), Instant.now().plusSeconds(5), "safe-test", "localhost");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(2);
    }

    @Test
    void unknownSideEffectIsAttemptedOnlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mutate", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
        });
        server.start();
        HttpRequest request = HttpRequest.newBuilder(uri("/mutate")).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(1)).build();
        ThirdPartyHttpExecutor executor = new ThirdPartyHttpExecutor(HttpClient.newHttpClient());
        assertThatThrownBy(() -> executor.execute(request, HttpResponse.BodyHandlers.ofString(),
                HttpRequestSemantics.SIDE_EFFECTING_OR_UNKNOWN, fastPolicy(), Instant.now().plusSeconds(5),
                "mutation", "localhost")).isInstanceOf(ThirdPartyCallException.class);
        assertThat(calls).hasValue(1);
    }

    private RetryPolicy fastPolicy() {
        return new RetryPolicy(3, 1, 1.0, 2, 0, 5, RetryPolicy.DEFAULT_STATUSES);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
