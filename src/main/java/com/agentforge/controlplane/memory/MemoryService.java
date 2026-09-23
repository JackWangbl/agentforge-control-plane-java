package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.MemoryItem;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.MemoryItemRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 短期记忆是当前会话的消息窗口，长期记忆是这个人在当前租户下的事实。 */
@Service
public class MemoryService {

    static final int MAX_MESSAGES = 20;
    static final int MAX_CHARS = 6000;
    static final int MAX_ITEMS = 200;
    static final int PROMPT_ITEMS = 12;
    static final int PROMPT_CHARS = 1500;
    static final int CONTENT_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryItemRepository items;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ResourceAccessService access;

    public MemoryService(MemoryItemRepository items, ConversationRepository conversations,
                         ChatMessageRepository messages, ResourceAccessService access) {
        this.items = items;
        this.conversations = conversations;
        this.messages = messages;
        this.access = access;
    }

    public static String subjectKey(CurrentUser user) {
        if (user == null || user.getId() == null) {
            return null;
        }
        if (user.isTrial()) {
            String trial = user.getTrialKey() == null ? "" : user.getTrialKey().strip();
            if (trial.isEmpty()) {
                return null;
            }
            String key = "trial:" + trial;
            return key.length() <= 120 ? key : key.substring(0, 120);
        }
        return "user:" + user.getId();
    }

    /** 空的 subject_key 只对已登录的原主人开放。试用不能读共用 guest 留下的旧会话。 */
    public static boolean canReadHistory(CurrentUser user, String storedSubject, Long ownerId) {
        String key = subjectKey(user);
        if (key == null) {
            return false;
        }
        if (storedSubject != null && !storedSubject.isBlank()) {
            return storedSubject.equals(key);
        }
        if (user.isTrial()) {
            return false;
        }
        return ownerId != null && ownerId.equals(user.getId());
    }

    public boolean canRead(CurrentUser user, Conversation conversation) {
        if (user == null || conversation == null) {
            return false;
        }
        if (conversation.getTenantId() != null && user.getTenantId() != null
                && !conversation.getTenantId().equals(user.getTenantId())) {
            return false;
        }
        return canReadHistory(user, conversation.getSubjectKey(), conversation.getOwnerId());
    }

    public void assertReadable(CurrentUser user, String sessionId) {
        if (user == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Conversation row = conversations.findBySessionId(sessionId).orElse(null);
        if (row == null) {
            return;
        }
        if (row.getTenantId() != null && user.getTenantId() != null
                && !row.getTenantId().equals(user.getTenantId())) {
            throw ApiException.conflict("这个会话属于其他租户");
        }
        if (!canRead(user, row)) {
            throw ApiException.conflict("这个会话属于其他人");
        }
    }

    public List<Map<String, Object>> shortTermHistory(CurrentUser user, Long agentId, String sessionId) {
        if (user == null || sessionId == null || sessionId.isBlank()) {
            return new ArrayList<>();
        }
        Conversation conversation = conversations.findBySessionId(sessionId).orElse(null);
        if (conversation != null && !canRead(user, conversation)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> prior = new ArrayList<>();
        for (ChatMessage row : messages.findBySessionIdAndTenantIdOrderByIdAsc(sessionId, user.getTenantId())) {
            if (agentId != null && row.getAgentId() != null && !agentId.equals(row.getAgentId())) {
                continue;
            }
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) {
                continue;
            }
            prior.add(Map.of("role", row.getRole(), "content", row.getContent()));
        }
        return applyWindow(prior);
    }

    static List<Map<String, Object>> applyWindow(List<Map<String, Object>> prior) {
        if (prior == null || prior.isEmpty()) {
            return new ArrayList<>();
        }
        int from = Math.max(0, prior.size() - MAX_MESSAGES);
        List<Map<String, Object>> slice = new ArrayList<>(prior.subList(from, prior.size()));
        while (slice.size() > 1 && chars(slice) > MAX_CHARS) {
            slice.remove(0);
        }
        if (slice.size() == 1 && chars(slice) > MAX_CHARS) {
            Map<String, Object> only = new LinkedHashMap<>(slice.get(0));
            String content = Jsons.text(only.get("content"));
            only.put("content", content.substring(0, MAX_CHARS));
            slice.set(0, only);
        }
        return slice;
    }

    @Transactional
    public void recordExchange(CurrentUser user, Agent agent, String sessionId, String userMessage, String assistantReply) {
        if (user == null || agent == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String key = requireSubject(user);
        Conversation conversation = conversations.findBySessionId(sessionId).orElse(null);
        if (conversation != null) {
            if (conversation.getTenantId() != null && !conversation.getTenantId().equals(user.getTenantId())) {
                throw ApiException.conflict("这个会话属于其他租户");
            }
            if (!canRead(user, conversation)) {
                throw ApiException.conflict("这个会话属于其他人");
            }
            if (conversation.getAgentId() != null && !conversation.getAgentId().equals(agent.getId())) {
                throw ApiException.conflict("Session belongs to another agent workspace");
            }
        } else {
            conversation = new Conversation();
            conversation.setSessionId(sessionId);
            conversation.setUserId(user.getUsername());
            conversation.setAgentId(agent.getId());
            conversation.setAgentName(agent.getName());
            conversation.setTitle(cut(userMessage, 80));
            conversation.setStatus("completed");
            conversation.setChannel("API");
            conversation.setSubjectKey(key);
            access.stampOwner(conversation, user);
        }
        if (conversation.getSubjectKey() == null || conversation.getSubjectKey().isBlank()) {
            conversation.setSubjectKey(key);
        }
        int added = 0;
        if (userMessage != null && !userMessage.isBlank()) {
            messages.save(newMessage(user, sessionId, agent, "user", userMessage));
            added++;
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            messages.save(newMessage(user, sessionId, agent, "assistant", assistantReply));
            added++;
        }
        conversation.setMessageCount(conversation.getMessageCount() + added);
        conversation.setUpdatedAt(Instant.now());
        conversations.save(conversation);
    }

    public void bindSubject(CurrentUser user, Conversation conversation) {
        if (user == null || conversation == null) {
            return;
        }
        if (conversation.getSubjectKey() != null && !conversation.getSubjectKey().isBlank()) {
            return;
        }
        String key = subjectKey(user);
        if (key != null) {
            conversation.setSubjectKey(key);
        }
    }

    public String promptBlock() {
        try {
            CurrentUser user = CurrentUserHolder.get();
            String key = subjectKey(user);
            if (user == null || key == null || user.getTenantId() == null) {
                return "";
            }
            List<MemoryItem> rows = items
                    .findByTenantIdAndSubjectKeyAndDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc(
                            user.getTenantId(), key);
            return formatPrompt(rows);
        } catch (RuntimeException e) {
            log.warn("长期记忆读取失败：{}", e.getMessage());
            return "";
        }
    }

    static String formatPrompt(List<MemoryItem> rows) {
        String instruction = "这些事实由系统在每轮对话后自动总结。回答时直接使用，不要主动复述整份清单。用户明确说忘掉时调用 forget。";
        if (rows == null || rows.isEmpty()) {
            return "关于当前用户的记忆：目前没有已保存的事实。\n" + instruction;
        }
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (MemoryItem row : rows) {
            if (lines.size() >= PROMPT_ITEMS) {
                break;
            }
            String content = row.getContent().strip();
            if (content.isEmpty()) {
                continue;
            }
            if (used > 0 && used + content.length() > PROMPT_CHARS) {
                break;
            }
            if (content.length() > PROMPT_CHARS) {
                content = content.substring(0, PROMPT_CHARS);
            }
            lines.add("- [" + kindLabel(row.getKind()) + "] " + content);
            used += content.length();
        }
        if (lines.isEmpty()) {
            return "关于当前用户的记忆：目前没有已保存的事实。\n" + instruction;
        }
        return "关于当前用户的记忆（仅供回答时参考，不要主动复述整份清单）：\n"
                + String.join("\n", lines) + "\n" + instruction;
    }

    @Transactional
    public MemoryItem create(CurrentUser user, String content, String kind, String source, String sessionId) {
        String key = requireSubject(user);
        String text = requireContent(content);
        long existing = items.countByTenantIdAndSubjectKeyAndDeletedAtIsNull(user.getTenantId(), key);
        if (existing >= MAX_ITEMS) {
            throw ApiException.unprocessable("长期记忆已有 " + MAX_ITEMS + " 条，请先删除一些再记");
        }
        MemoryItem row = new MemoryItem();
        row.setTenantId(user.getTenantId());
        row.setSubjectKey(key);
        row.setContent(text);
        row.setKind(normalizeKind(kind));
        row.setSource("agent".equals(source) ? "agent" : "user");
        row.setSessionId(blankToNull(sessionId));
        row.setPinned(false);
        return items.save(row);
    }

    @Transactional
    public MemoryItem update(CurrentUser user, Long id, String content, String kind, Boolean pinned) {
        MemoryItem row = own(user, id);
        if (content != null) {
            row.setContent(requireContent(content));
        }
        if (kind != null && !kind.isBlank()) {
            row.setKind(normalizeKind(kind));
        }
        if (pinned != null) {
            row.setPinned(pinned);
        }
        row.setUpdatedAt(Instant.now());
        return items.save(row);
    }

    @Transactional
    public void delete(CurrentUser user, Long id) {
        MemoryItem row = own(user, id);
        row.setDeletedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        items.save(row);
    }

    @Transactional(readOnly = true)
    public List<MemoryItem> list(CurrentUser user) {
        String key = requireSubject(user);
        return items.findByTenantIdAndSubjectKeyAndDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc(
                user.getTenantId(), key);
    }

    /** 把模型整理出的增删改应用到当前身份。已有的相同事实不重复写入。 */
    @Transactional
    public int applySummary(CurrentUser user, String sessionId, List<Map<String, Object>> ops) {
        if (user == null || ops == null || ops.isEmpty() || subjectKey(user) == null) {
            return 0;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MemoryItem row : list(user)) {
            seen.add(normalizeFact(row.getContent()));
        }
        int applied = 0;
        int adds = 0;
        for (Map<String, Object> op : ops) {
            if (applied >= 8) {
                break;
            }
            String action = Jsons.text(op.get("op")).strip().toLowerCase();
            try {
                if ("add".equals(action) || "create".equals(action)) {
                    if (adds >= 5) {
                        continue;
                    }
                    String content = clipFact(Jsons.text(op.get("content")));
                    String fact = normalizeFact(content);
                    if (fact.length() < 2 || seen.contains(fact)) {
                        continue;
                    }
                    create(user, content, Jsons.text(op.get("kind")), "agent", sessionId);
                    seen.add(fact);
                    adds++;
                    applied++;
                } else if ("update".equals(action)) {
                    Long id = Jsons.asLong(op.get("id"));
                    String content = clipFact(Jsons.text(op.get("content")));
                    if (id == null || content.length() < 2) {
                        continue;
                    }
                    String kind = Jsons.text(op.get("kind")).isBlank() ? null : Jsons.text(op.get("kind"));
                    update(user, id, content, kind, null);
                    applied++;
                } else if ("delete".equals(action) || "forget".equals(action)) {
                    Long id = Jsons.asLong(op.get("id"));
                    if (id == null) {
                        continue;
                    }
                    delete(user, id);
                    applied++;
                }
            } catch (ApiException e) {
                log.info("跳过一条记忆整理：{}", e.getMessage());
            }
        }
        return applied;
    }

    public String remember(CurrentUser user, String content, String kind) {
        String sessionId = user == null ? null : user.getActiveSessionId();
        MemoryItem row = create(user, content, kind, "agent", sessionId);
        return "已记住：" + row.getContent();
    }

    @Transactional
    public String forget(CurrentUser user, String keyword) {
        String needle = keyword == null ? "" : keyword.strip();
        if (needle.isEmpty()) {
            return "请说明要忘掉的内容。";
        }
        String key = requireSubject(user);
        String folded = needle.toLowerCase();
        List<MemoryItem> matched = new ArrayList<>();
        for (MemoryItem row : items.findByTenantIdAndSubjectKeyAndDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc(
                user.getTenantId(), key)) {
            if (row.getContent().toLowerCase().contains(folded)) {
                matched.add(row);
            }
        }
        if (matched.isEmpty()) {
            return "没有找到匹配的记忆。";
        }
        Instant now = Instant.now();
        for (MemoryItem row : matched) {
            row.setDeletedAt(now);
            row.setUpdatedAt(now);
        }
        items.saveAll(matched);
        return "已忘掉 " + matched.size() + " 条。";
    }

    public String listText(CurrentUser user, String keyword) {
        String needle = keyword == null ? "" : keyword.strip().toLowerCase();
        List<String> lines = new ArrayList<>();
        for (MemoryItem row : list(user)) {
            if (!needle.isEmpty() && !row.getContent().toLowerCase().contains(needle)) {
                continue;
            }
            lines.add("- [" + kindLabel(row.getKind()) + "] " + row.getContent());
            if (lines.size() >= 20) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return "没有匹配的记忆。";
        }
        return String.join("\n", lines);
    }

    public static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "profile" -> "资料";
            case "decision" -> "决定";
            case "correction" -> "更正";
            default -> "偏好";
        };
    }

    public static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.strip().toLowerCase();
        if (value.isEmpty() || "preference".equals(value) || "偏好".equals(kind == null ? "" : kind.strip())) {
            return "preference";
        }
        if ("profile".equals(value) || "资料".equals(kind.strip())) {
            return "profile";
        }
        if ("decision".equals(value) || "决定".equals(kind.strip())) {
            return "decision";
        }
        if ("correction".equals(value) || "更正".equals(kind.strip())) {
            return "correction";
        }
        throw ApiException.badRequest("记忆类型只能是偏好、资料、决定或更正");
    }

    private MemoryItem own(CurrentUser user, Long id) {
        String key = requireSubject(user);
        return items.findByIdAndTenantIdAndSubjectKeyAndDeletedAtIsNull(id, user.getTenantId(), key)
                .orElseThrow(() -> ApiException.notFound("记忆不存在"));
    }

    private String requireSubject(CurrentUser user) {
        String key = subjectKey(user);
        if (key == null || user.getTenantId() == null) {
            throw ApiException.badRequest("当前身份无法写入记忆");
        }
        return key;
    }

    private static String requireContent(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("记忆内容不能为空");
        }
        if (text.length() > CONTENT_LIMIT) {
            throw ApiException.badRequest("一条记忆最多 " + CONTENT_LIMIT + " 字");
        }
        return text;
    }

    private ChatMessage newMessage(CurrentUser user, String sessionId, Agent agent, String role, String content) {
        ChatMessage row = new ChatMessage();
        row.setSessionId(sessionId);
        row.setAgentId(agent.getId());
        row.setRole(role);
        row.setContent(content);
        row.setAgentName(agent.getName() == null ? "" : agent.getName());
        access.stampOwner(row, user);
        return row;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String clipFact(String value) {
        String text = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private static String normalizeFact(String value) {
        return value == null ? "" : value.strip().toLowerCase().replaceAll("\\s+", "");
    }

    private static String cut(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "新会话";
        }
        String text = value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static int chars(List<Map<String, Object>> rows) {
        int total = 0;
        for (Map<String, Object> row : rows) {
            total += Jsons.text(row.get("content")).length();
        }
        return total;
    }
}
