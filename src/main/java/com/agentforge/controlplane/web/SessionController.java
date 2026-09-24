package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.session.SessionRecordService;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SessionController {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final TraceRepository traces;
    private final ResourceDumper dumper;
    private final SessionRecordService records;

    public SessionController(ConversationRepository conversations, ChatMessageRepository messages,
                             TraceRepository traces, ResourceDumper dumper, SessionRecordService records) {
        this.conversations = conversations;
        this.messages = messages;
        this.traces = traces;
        this.dumper = dumper;
        this.records = records;
    }

    @RequirePermission("session:read")
    @GetMapping("/api/sessions")
    public List<Map<String, Object>> list(CurrentUser user,
                                          @RequestParam(required = false) String agent_name,
                                          @RequestParam(required = false) String user_id,
                                          @RequestParam(required = false) String session_id,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        return conversations.findAll(
                        records.specification(user, agent_name, user_id, session_id, status, q),
                        PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "updatedAt", "id")))
                .stream().map(row -> dumper.dump(row, user)).toList();
    }

    @RequirePermission("session:write")
    @DeleteMapping("/api/sessions")
    public Map<String, Object> clear(CurrentUser user,
                                     @RequestParam(required = false) String agent_name,
                                     @RequestParam(required = false) String user_id,
                                     @RequestParam(required = false) String session_id,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String q) {
        return records.clearMatching(user, agent_name, user_id, session_id, status, q);
    }

    @RequirePermission("session:write")
    @DeleteMapping("/api/sessions/{sessionId}")
    public Map<String, Object> clearOne(CurrentUser user, @PathVariable String sessionId) {
        return records.clearOne(user, sessionId);
    }

    @RequirePermission("session:read")
    @GetMapping("/api/sessions/{sessionId}")
    public Map<String, Object> detail(CurrentUser user, @PathVariable String sessionId) {
        Conversation row = conversations.findBySessionId(sessionId).orElse(null);
        if (row == null || (row.getTenantId() != null && !row.getTenantId().equals(user.getTenantId()))) {
            throw ApiException.notFound("Session not found");
        }
        Map<String, Object> data = new LinkedHashMap<>(dumper.dump(row, user));
        data.put("messages", messages.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(item -> dumper.dump(item, user)).toList());
        data.put("traces", traces.findBySessionIdOrderByStartedAtDesc(sessionId).stream()
                .map(item -> dumper.dump(item, user)).toList());
        return data;
    }

    @RequirePermission("trace:read")
    @GetMapping("/api/traces/{itemId}")
    public Map<String, Object> trace(CurrentUser user, @PathVariable String itemId) {
        Trace row = null;
        if (itemId.chars().allMatch(Character::isDigit)) {
            row = traces.findById(Long.parseLong(itemId)).orElse(null);
        }
        if (row == null) {
            row = traces.findByTraceId(itemId).orElse(null);
        }
        if (row == null || (row.getTenantId() != null && !row.getTenantId().equals(user.getTenantId()))) {
            throw ApiException.notFound("Trace not found");
        }
        Map<String, Object> data = new LinkedHashMap<>(dumper.dump(row, user));
        List<Map<String, Object>> items = new ArrayList<>();
        if (row.getSessionId() != null && !row.getSessionId().isBlank()) {
            for (ChatMessage item : messages.findBySessionIdOrderByIdAsc(row.getSessionId())) {
                items.add(Jsons.ordered("id", item.getId(), "role", item.getRole(), "content", item.getContent(),
                        "agent_name", item.getAgentName(), "created_at", Jsons.iso(item.getCreatedAt())));
            }
        }
        data.put("messages", items);
        return data;
    }
}
