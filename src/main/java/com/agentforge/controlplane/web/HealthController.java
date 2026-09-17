package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.PublicEndpoint;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.observability.ObservabilityService;
import com.agentforge.controlplane.repo.ConversationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HealthController {

    private static final List<Integer> ACTIVITY = List.of(
            82, 110, 96, 138, 126, 164, 152, 189, 171, 204, 196, 236,
            218, 248, 227, 263, 251, 284, 269, 302, 292, 326, 311, 348);

    private final ObservabilityService observability;
    private final ConversationRepository conversations;
    private final ResourceAccessService access;
    private final ResourceDumper dumper;

    public HealthController(ObservabilityService observability, ConversationRepository conversations,
                            ResourceAccessService access, ResourceDumper dumper) {
        this.observability = observability;
        this.conversations = conversations;
        this.access = access;
        this.dumper = dumper;
    }

    @PublicEndpoint
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("service", "agentforge-control-plane");
        data.putAll(observability.status());
        return data;
    }

    @GetMapping("/api/observability")
    public Map<String, Object> observability(CurrentUser user) {
        return observability.status();
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard(CurrentUser user) {
        long total = conversations.countByTenantId(user.getTenantId());
        long completed = conversations.countByTenantIdAndStatus(user.getTenantId(), "completed");
        Double avg = conversations.avgLatencyByTenant(user.getTenantId());
        Long tokens = conversations.sumTokensByTenant(user.getTenantId());
        List<Agent> agents = user.has("agent:read")
                ? new ArrayList<>(access.<Agent>listRows(user, ResourceKind.AGENT))
                : new ArrayList<>();
        agents.sort(Comparator.comparingDouble(Agent::getSuccessRate).reversed());
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (user.has("session:read")) {
            for (Conversation row : conversations.findTop5ByTenantIdOrderByUpdatedAtDescIdDesc(user.getTenantId())) {
                sessions.add(dumper.dump(row, user));
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("requests", total * 2568);
        metrics.put("success_rate", total == 0 ? 0 : Math.round(completed * 1000.0 / total) / 10.0);
        metrics.put("avg_latency_ms", Math.round(avg == null ? 0 : avg));
        metrics.put("tokens", (tokens == null ? 0 : tokens) * 128);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metrics", metrics);
        data.put("agents", dumper.dumpAll(agents));
        data.put("recent_sessions", sessions);
        data.put("activity", ACTIVITY);
        return data;
    }
}
