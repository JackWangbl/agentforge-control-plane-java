package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.domain.MemoryItem;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一轮对话结束后，用当前 Agent 的对话模型整理长期记忆。失败不影响回复。 */
@Service
public class MemorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(MemorySummarizer.class);
    private static final String INSTRUCTION = """
            你负责维护用户的长期记忆。只保留稳定事实：偏好、称呼和身份、做过的决定、对已有事实的更正或删除。
            不要记录闲聊、一次性提问、任务过程、知识库文档里的内容和你自己的推测。
            已有记忆里已经写过的事实不要再 add。用户否定或要求忘掉某条时，对那条做 delete。
            只输出 JSON，不要解释：{"items":[{"op":"add","kind":"preference","content":"一句事实"},{"op":"update","id":1,"kind":"profile","content":"更正后的事实"},{"op":"delete","id":2}]}
            kind 只能是 preference、profile、decision、correction。没有变化时 items 为空数组。每条 content 只写一件事，不超过 80 字。
            """;

    private final MemoryService memories;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public MemorySummarizer(MemoryService memories) {
        this.memories = memories;
    }

    public void summarize(CurrentUser user, ModelConfig model, String sessionId,
                          String userMessage, String assistantReply, String mode) {
        if (user == null || model == null || !"ready".equals(mode)) {
            return;
        }
        String question = clip(userMessage, 1500);
        String answer = clip(assistantReply, 1500);
        if (question.isBlank() || answer.isBlank()) {
            return;
        }
        if (AgentScopeRuntime.resolveCredential(model).isBlank()) {
            return;
        }
        try {
            String raw = complete(model, prompt(user, question, answer));
            List<Map<String, Object>> ops = parseOps(raw);
            int applied = memories.applySummary(user, sessionId, ops);
            if (applied > 0) {
                log.info("已根据对话整理 {} 条长期记忆", applied);
            }
        } catch (RuntimeException e) {
            log.warn("长期记忆自动总结失败：{}", e.getMessage());
        }
    }

    static List<Map<String, Object>> parseOps(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String raw = text.strip();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        Object items = Jsons.map(raw.substring(start, end + 1)).get("items");
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(items)) {
            if (item.get("op") != null && (item.get("content") != null || item.get("id") != null)) {
                ops.add(item);
            }
        }
        return ops;
    }

    private String prompt(CurrentUser user, String question, String answer) {
        StringBuilder existing = new StringBuilder();
        try {
            for (MemoryItem row : memories.list(user)) {
                existing.append("- id=").append(row.getId())
                        .append(" [").append(row.getKind()).append("] ")
                        .append(row.getContent()).append('\n');
                if (existing.length() > 2000) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            existing.append("（暂时读不到已有记忆）\n");
        }
        if (existing.isEmpty()) {
            existing.append("（还没有长期记忆）\n");
        }
        return "已有记忆：\n" + existing + "\n本轮用户：\n" + question + "\n\n本轮助手：\n" + answer;
    }

    private String complete(ModelConfig model, String userPrompt) {
        String credential = AgentScopeRuntime.resolveCredential(model);
        String url = AgentScopeRuntime.modelEndpoint(model) + "/chat/completions";
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model.getModelId(),
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", INSTRUCTION),
                            Map.of("role", "user", "content", userPrompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("记忆总结返回 " + response.statusCode());
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? "" : content.asText("");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? "记忆总结调用失败" : e.getMessage());
        }
    }

    private static String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
