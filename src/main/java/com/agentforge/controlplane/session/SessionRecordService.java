package com.agentforge.controlplane.session;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.ChatMessageRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 清除会话查询里的记录：对话、消息、链路，以及 Agent 工作区里对应的会话文件。长期记忆不动。 */
@Service
public class SessionRecordService {

    private static final int DELETE_BATCH = 200;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final TraceRepository traces;
    private final AgentRepository agents;
    private final WorkspaceStore workspaces;

    public SessionRecordService(ConversationRepository conversations, ChatMessageRepository messages,
                                TraceRepository traces, AgentRepository agents, WorkspaceStore workspaces) {
        this.conversations = conversations;
        this.messages = messages;
        this.traces = traces;
        this.agents = agents;
        this.workspaces = workspaces;
    }

    public Specification<Conversation> specification(CurrentUser user, String agentName, String userId,
                                                     String sessionId, String status, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(cb.equal(root.get("tenantId"), user.getTenantId()), cb.isNull(root.get("tenantId"))));
            if (agentName != null && !agentName.isBlank()) {
                predicates.add(cb.equal(root.get("agentName"), agentName));
            }
            if (userId != null && !userId.isBlank()) {
                predicates.add(cb.like(root.get("userId"), "%" + userId + "%"));
            }
            if (sessionId != null && !sessionId.isBlank()) {
                predicates.add(cb.like(root.get("sessionId"), "%" + sessionId + "%"));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String needle = "%" + q + "%";
                List<String> matched = messages.findSessionIdsByKeyword(user.getTenantId(), q);
                Predicate text = cb.or(
                        cb.like(root.get("title"), needle),
                        cb.like(root.get("userId"), needle),
                        cb.like(root.get("sessionId"), needle));
                if (!matched.isEmpty()) {
                    text = cb.or(text, root.get("sessionId").in(matched));
                }
                predicates.add(text);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Transactional
    public Map<String, Object> clearMatching(CurrentUser user, String agentName, String userId,
                                              String sessionId, String status, String q) {
        List<Conversation> rows = conversations.findAll(specification(user, agentName, userId, sessionId, status, q));
        return purge(rows);
    }

    @Transactional
    public Map<String, Object> clearOne(CurrentUser user, String sessionId) {
        Conversation row = conversations.findBySessionId(sessionId).orElse(null);
        if (!visible(user, row)) {
            throw ApiException.notFound("Session not found");
        }
        return purge(List.of(row));
    }

    private Map<String, Object> purge(List<Conversation> rows) {
        List<String> ids = new ArrayList<>();
        for (Conversation row : rows) {
            ids.add(row.getSessionId());
            removeFiles(row);
        }
        if (!ids.isEmpty()) {
            for (int i = 0; i < ids.size(); i += DELETE_BATCH) {
                List<String> batch = ids.subList(i, Math.min(i + DELETE_BATCH, ids.size()));
                messages.deleteBySessionIdIn(batch);
                traces.deleteBySessionIdIn(batch);
            }
            conversations.deleteAll(rows);
        }
        String message = ids.isEmpty() ? "没有可清除的会话记录" : "已清除 " + ids.size() + " 条会话记录";
        return Jsons.ordered("deleted", ids.size(), "session_ids", ids, "message", message);
    }

    private void removeFiles(Conversation row) {
        if (row.getAgentId() == null) {
            return;
        }
        Agent agent = agents.findById(row.getAgentId()).orElse(null);
        if (agent == null) {
            return;
        }
        if (agent.getTenantId() != null && row.getTenantId() != null
                && !agent.getTenantId().equals(row.getTenantId())) {
            return;
        }
        List<String> traceIds = traces.findBySessionIdOrderByStartedAtDesc(row.getSessionId()).stream()
                .map(Trace::getTraceId)
                .toList();
        workspaces.deleteSessionFiles(agent, row.getSessionId(), traceIds);
    }

    static boolean visible(CurrentUser user, Conversation row) {
        if (user == null || row == null) {
            return false;
        }
        return row.getTenantId() == null || row.getTenantId().equals(user.getTenantId());
    }
}
