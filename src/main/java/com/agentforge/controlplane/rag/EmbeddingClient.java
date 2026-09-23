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
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class EmbeddingClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public List<float[]> embed(ModelConfig model, List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        String credential = AgentScopeRuntime.resolveCredential(model);
        if (credential.isBlank()) {
            throw new RagCallException("向量模型没有密钥", false);
        }
        String url = AgentScopeRuntime.modelEndpoint(model) + "/embeddings";
        try {
            String body = json.writeValueAsString(java.util.Map.of(
                    "model", model.getModelId(),
                    "input", inputs));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RagCallException("向量模型返回 " + response.statusCode() + "：" + clip(response.body()), false);
            }
            JsonNode root = json.readTree(response.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != inputs.size()) {
                throw new RagCallException("向量模型响应里没有完整的 data 数组", false);
            }
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                    throw new RagCallException("向量模型响应缺少 embedding", false);
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (RagCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RagCallException("调用向量模型失败：" + e.getMessage(), true);
        }
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").strip();
        return text.length() > 180 ? text.substring(0, 180) : text;
    }
}
