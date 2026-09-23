package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.domain.ModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class HttpRerankClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public List<Scored> rerank(ModelConfig model, String query, List<String> documents, int topN) {
        String credential = AgentScopeRuntime.resolveCredential(model);
        if (credential.isBlank()) {
            throw new RagCallException("重排序模型没有密钥", false);
        }
        String root = model.getBaseUrl() == null ? "" : model.getBaseUrl().strip().replaceAll("/+$", "");
        if (root.isEmpty()) {
            throw new RagCallException("重排序模型没有地址", false);
        }
        try {
            String body = json.writeValueAsString(java.util.Map.of(
                    "model", model.getModelId(),
                    "query", query,
                    "documents", documents,
                    "top_n", topN));
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/rerank"))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RagCallException("重排序服务返回 " + response.statusCode(), true);
            }
            JsonNode results = json.readTree(response.body()).get("results");
            if (results == null || !results.isArray()) {
                throw new RagCallException("重排序响应缺少 results", false);
            }
            List<Scored> scored = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0);
                if (index >= 0 && index < documents.size()) {
                    scored.add(new Scored(index, score));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            return scored;
        } catch (RagCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RagCallException("调用重排序服务失败：" + e.getMessage(), true);
        }
    }

    public record Scored(int index, double score) {}
}
