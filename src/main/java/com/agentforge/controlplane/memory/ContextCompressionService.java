package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.ConversationContextSummary;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationContextSummaryRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextCompressionService {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionService.class);
    private static final String INSTRUCTION = """
            你负责把同一场对话里即将被裁掉的旧轮次压成一段滚动摘要，供后续对话继续使用。
            只保留：用户目标、已确认事实、未完成事项、关键实体（单号、帖子、接口名等）、已经做过和还没做的动作。
            不要抽取跨会话偏好（那是长期记忆的事）。不要复述闲聊。不要写入密码、令牌、Cookie、Authorization 或完整工具返回。
            只输出摘要正文，不要标题、不要 JSON、不要解释。
            """;

    private final MemoryService memories;
    private final ChatMessageRepository messages;
    private final ConversationContextSummaryRepository summaries;
    private final ModelConfigRepository models;
    private final AppSettings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ContextCompressionService(MemoryService memories, ChatMessageRepository messages,
                                     ConversationContextSummaryRepository summaries, ModelConfigRepository models,
                                     AppSettings settings) {
        this.memories = memories;
        this.messages = messages;
        this.summaries = summaries;
        this.models = models;
        this.settings = settings;
    }

    public List<Map<String, Object>> assemble(CurrentUser user, Agent agent, ModelConfig model,
                                              String sessionId, String currentUserMessage) {
        return assemble(user, agent, model, sessionId, currentUserMessage, agent == null ? null : agent.getId());
    }

    public List<Map<String, Object>> assemble(CurrentUser user, Agent agent, ModelConfig model,
                                              String sessionId, String currentUserMessage, Long historyAgentId) {
        try {
            return assembleInternal(user, agent, model, sessionId, currentUserMessage, historyAgentId);
        } catch (RuntimeException error) {
            log.warn("上下文压缩失败，回退窗口裁剪 session={} agent={}: {}",
                    sessionId, agent == null ? null : agent.getId(), error.getMessage());
            return memories.shortTermHistory(user, historyAgentId, sessionId);
        }
    }

    private List<Map<String, Object>> assembleInternal(CurrentUser user, Agent agent, ModelConfig model,
                                                       String sessionId, String currentUserMessage, Long historyAgentId) {
        if (user == null || sessionId == null || sessionId.isBlank()) {
            return new ArrayList<>();
        }
        ContextCompressionPolicy.Effective policy = ContextCompressionPolicy.merge(
                settings.getContextCompression(), agent == null ? Map.of() : agent.getContextCompression());
        List<ChatMessage> rows = loadMessages(user, sessionId, historyAgentId);
        if (!policy.enabled() || agent == null || httpBacked(agent) || !AgentScopeRuntime.modelHasCredential(model)) {
            return MemoryService.applyWindow(toMaps(rows));
        }
        Long summaryAgentId = historyAgentId == null ? 0L : historyAgentId;
        String lockKey = user.getTenantId() + ":" + sessionId + ":" + summaryAgentId;
        Object lock = locks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            ConversationContextSummary stored = summaries
                    .findByTenantIdAndSessionIdAndAgentId(user.getTenantId(), sessionId, summaryAgentId)
                    .orElse(null);
            String systemText = agent.getSystemPrompt() + "\n" + memories.promptBlock();
            ModelConfig compressModel = resolveCompressModel(user, model, policy.compressModelName());
            ContextHistoryAssembler.Result result = ContextHistoryAssembler.assemble(
                    rows,
                    stored == null ? "" : stored.getSummaryText(),
                    stored == null ? null : stored.getCoveredThroughMessageId(),
                    stored == null ? "" : stored.getContentHash(),
                    policy,
                    systemText,
                    currentUserMessage,
                    (previous, evicted, effective) -> complete(compressModel, previous, evicted, effective));
            if ("compressed".equals(result.outcome()) && result.coveredThroughId() != null) {
                persist(user, sessionId, summaryAgentId, compressModel, result, policy);
            }
            log.info("Context compression tenant={} session={} agent={} status={} budget={} before={} after={}",
                    user.getTenantId(), sessionId, summaryAgentId, result.outcome(), result.budget(),
                    result.tokensBefore(), result.tokensAfter());
            return result.history();
        }
    }

    private List<ChatMessage> loadMessages(CurrentUser user, String sessionId, Long historyAgentId) {
        memories.assertReadable(user, sessionId);
        List<ChatMessage> rows = new ArrayList<>();
        for (ChatMessage row : messages.findBySessionIdAndTenantIdOrderByIdAsc(sessionId, user.getTenantId())) {
            if (historyAgentId != null && row.getAgentId() != null && !historyAgentId.equals(row.getAgentId())) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    @Transactional
    protected void persist(CurrentUser user, String sessionId, Long agentId, ModelConfig model,
                           ContextHistoryAssembler.Result result, ContextCompressionPolicy.Effective policy) {
        ConversationContextSummary row = summaries
                .findByTenantIdAndSessionIdAndAgentId(user.getTenantId(), sessionId, agentId)
                .orElseGet(ConversationContextSummary::new);
        if (row.getId() == null) {
            row.setTenantId(user.getTenantId());
            row.setOwnerId(user.getId());
            row.setSessionId(sessionId);
            row.setAgentId(agentId);
            row.setCreatedAt(Instant.now());
        }
        row.setSummaryText(result.summary());
        row.setCoveredThroughMessageId(result.coveredThroughId() == null ? 0L : result.coveredThroughId());
        row.setSourceMessageCount(result.sourceCount());
        row.setSourceCharCount(result.sourceChars());
        row.setEstimatedTokensBefore(result.tokensBefore());
        row.setEstimatedTokensAfter(result.tokensAfter());
        row.setHistoryBudget(result.budget());
        row.setContextWindowTokens(policy.contextWindowTokens());
        row.setModelName(model == null ? "" : model.getName());
        row.setContentHash(result.contentHash() == null ? "" : result.contentHash());
        row.setUpdatedAt(Instant.now());
        summaries.saveAndFlush(row);
    }

    private String complete(ModelConfig model, String previousSummary, List<ChatMessage> evicted,
                            ContextCompressionPolicy.Effective policy) {
        if (model == null || !AgentScopeRuntime.modelHasCredential(model) || evicted == null || evicted.isEmpty()) {
            return null;
        }
        String credential = AgentScopeRuntime.resolveCredential(model);
        String url = AgentScopeRuntime.modelEndpoint(model) + "/chat/completions";
        StringBuilder body = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            body.append("已有摘要：\n").append(previousSummary.strip()).append("\n\n");
        }
        body.append("需要压缩的旧轮次：\n");
        int used = 0;
        for (ChatMessage row : evicted) {
            String content = row.getContent() == null ? "" : row.getContent();
            if (content.length() > 800) content = content.substring(0, 800);
            body.append("- ").append(row.getRole()).append(": ").append(content).append('\n');
            used += content.length();
            if (used > 12000) break;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model.getModelId());
            payload.put("temperature", 0);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", INSTRUCTION),
                    Map.of("role", "user", "content", body.toString())));
            String jsonBody = json.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("上下文压缩返回 " + response.statusCode());
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? "" : content.asText("");
            return ContextCompressionPolicy.clipToTokens(text, policy.summaryTokenLimit(), policy.charsPerToken());
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage() == null ? "上下文压缩调用失败" : error.getMessage());
        }
    }

    private ModelConfig resolveCompressModel(CurrentUser user, ModelConfig fallback, String name) {
        if (name == null || name.isBlank()) return fallback;
        return models.findByName(name)
                .filter(ModelConfig::isEnabled)
                .filter(row -> row.getTenantId() == null || row.getTenantId().equals(user.getTenantId()))
                .filter(AgentScopeRuntime::modelHasCredential)
                .orElse(fallback);
    }

    private static boolean httpBacked(Agent agent) {
        return agent.getHttpAgentIds() != null && !agent.getHttpAgentIds().isEmpty();
    }

    private static List<Map<String, Object>> toMaps(List<ChatMessage> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage row : rows) {
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) continue;
            out.add(Map.of("role", row.getRole(), "content", row.getContent() == null ? "" : row.getContent()));
        }
        return out;
    }
}
