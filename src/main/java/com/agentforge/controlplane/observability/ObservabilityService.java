package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Langfuse + AgentScope Studio 状态探测与 playground 导出。对齐 Python 版 studio_tracer / langfuse_tracer。 */
@Service
public class ObservabilityService {

    private final AppSettings settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1200))
            .build();

    public ObservabilityService(AppSettings settings) {
        this.settings = settings;
    }

    public Map<String, Object> status() {
        Map<String, Object> studio = new LinkedHashMap<>();
        studio.put("url", settings.resolvedStudioUrl());
        studio.put("reachable", probe(settings.resolvedStudioUrl()));
        studio.put("configured", settings.isStudioConfigured());
        Map<String, Object> langfuse = new LinkedHashMap<>();
        AppSettings.Langfuse lf = settings.getLangfuse();
        langfuse.put("enabled", lf.isConfigured());
        langfuse.put("host", lf.getHost() == null ? "" : lf.getHost());
        langfuse.put("configured", lf.isConfigured());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("langfuse", langfuse);
        out.put("studio", studio);
        return out;
    }

    public void exportPlayground(String agentName, String sessionId, String traceId, String message, String reply,
                                 String mode, String modelName, String modelId,
                                 List<Map<String, Object>> spans, Map<String, ?> usage, int latencyMs, String title) {
        if (!settings.isStudioConfigured()) {
            return;
        }
        try {
            Map<String, Object> run = Jsons.ordered(
                    "id", sessionId,
                    "project", agentName == null || agentName.isBlank() ? "AgentForge" : agentName,
                    "name", (title == null || title.isBlank() ? sessionId : title),
                    "timestamp", Jsons.iso(java.time.Instant.now()),
                    "pid", ProcessHandle.current().pid(),
                    "status", "done");
            post(settings.resolvedStudioUrl() + "/trpc/registerRun", run);
            Map<String, Object> payload = Jsons.ordered(
                    "resourceSpans", List.of(Jsons.ordered(
                            "scopeSpans", List.of(Jsons.ordered(
                                    "spans", List.of(Jsons.ordered(
                                            "traceId", traceId,
                                            "name", agentName,
                                            "attributes", List.of(
                                                    Map.of("key", "session.id", "value", Map.of("stringValue", sessionId)),
                                                    Map.of("key", "model", "value", Map.of("stringValue", modelId == null ? "" : modelId)),
                                                    Map.of("key", "mode", "value", Map.of("stringValue", mode == null ? "" : mode))))))))));
            post(settings.resolvedStudioUrl() + "/v1/traces", payload);
        } catch (Exception ignored) {
            // 导出失败不影响主流程
        }
    }

    private boolean probe(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(1200))
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void post(String url, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.json(body), StandardCharsets.UTF_8))
                .build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
