package com.agentforge.controlplane.experiment;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.DatasetCase;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.domain.ExperimentAssignment;
import com.agentforge.controlplane.domain.ExperimentEvent;
import com.agentforge.controlplane.domain.ExperimentVariant;
import com.agentforge.controlplane.eval.EvalScorers;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.DatasetCaseRepository;
import com.agentforge.controlplane.repo.ExperimentAssignmentRepository;
import com.agentforge.controlplane.repo.ExperimentEventRepository;
import com.agentforge.controlplane.repo.ExperimentRepository;
import com.agentforge.controlplane.repo.ExperimentVariantRepository;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ExperimentService {

    public static final List<String> VARIANT_KEYS = List.of("A", "B", "C", "D", "E", "F");
    public static final Map<String, Map<String, String>> STRATEGIES = Map.of(
            "user_hash", Map.of("unit", "user", "algo", "hash", "label", "按用户 ID 哈希", "hint", "同一用户始终分到同一 Agent"),
            "session_hash", Map.of("unit", "session", "algo", "hash", "label", "按 Session ID 哈希", "hint", "同一会话始终同一 Agent，换会话可能换 Agent"),
            "user_first", Map.of("unit", "user", "algo", "first", "label", "按用户首次进组", "hint", "用户第一次按权重随机进组，之后一直粘滞"),
            "random", Map.of("unit", "request", "algo", "random", "label", "每次随机", "hint", "每次请求独立抽取，不保证同一用户同一 Agent"));

    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAssignmentRepository assignments;
    private final ExperimentEventRepository events;
    private final AgentRepository agents;
    private final DatasetCaseRepository cases;
    private final ResourceAccessService access;

    public ExperimentService(ExperimentRepository experiments, ExperimentVariantRepository variants,
                             ExperimentAssignmentRepository assignments, ExperimentEventRepository events,
                             AgentRepository agents, DatasetCaseRepository cases, ResourceAccessService access) {
        this.experiments = experiments;
        this.variants = variants;
        this.assignments = assignments;
        this.events = events;
        this.agents = agents;
        this.cases = cases;
        this.access = access;
    }

    public static String normalizeStrategy(String value, String assignmentUnit) {
        String raw = value == null ? "" : value.strip().toLowerCase();
        if (STRATEGIES.containsKey(raw)) {
            return raw;
        }
        if ("user".equals(raw) || "user".equals(assignmentUnit == null ? "" : assignmentUnit.strip().toLowerCase())) {
            return "user_hash";
        }
        return "session_hash";
    }

    public void applyStrategy(Experiment row, String strategy, String assignmentUnit) {
        String normalized = normalizeStrategy(strategy, assignmentUnit);
        if (!STRATEGIES.containsKey(normalized)) {
            throw ApiException.badRequest("不支持的分流策略");
        }
        row.setAssignmentStrategy(normalized);
        row.setAssignmentUnit("user".equals(STRATEGIES.get(normalized).get("unit")) ? "user" : "session");
    }

    public Map<String, Object> dump(Experiment row) {
        String strategy = normalizeStrategy(row.getAssignmentStrategy(), row.getAssignmentUnit());
        List<Map<String, Object>> items = new ArrayList<>();
        int weightSum = 0;
        for (ExperimentVariant variant : variants.findByExperimentIdOrderByIdAsc(row.getId())) {
            Map<String, Object> item = dumpVariant(variant);
            items.add(item);
            weightSum += ((Number) item.get("weight")).intValue();
        }
        int denom = Math.max(1, weightSum);
        for (Map<String, Object> item : items) {
            item.put("share", Math.round(((Number) item.get("weight")).doubleValue() / denom * 1000.0) / 10.0);
        }
        Map<String, Object> data = Jsons.ordered(
                "id", row.getId(),
                "name", row.getName(),
                "description", row.getDescription() == null ? "" : row.getDescription(),
                "status", row.getStatus(),
                "assignment_unit", "user".equals(STRATEGIES.get(strategy).get("unit")) ? "user" : "session",
                "assignment_strategy", strategy,
                "assignment_strategy_label", STRATEGIES.get(strategy).get("label"),
                "assignment_strategy_hint", STRATEGIES.get(strategy).get("hint"),
                "traffic_percent", row.getTrafficPercent() <= 0 ? 100 : row.getTrafficPercent(),
                "started_at", Jsons.iso(row.getStartedAt()),
                "finished_at", Jsons.iso(row.getFinishedAt()),
                "created_at", Jsons.iso(row.getCreatedAt()),
                "updated_at", Jsons.iso(row.getUpdatedAt()),
                "variants", items,
                "variant_count", items.size(),
                "last_compare", row.getLastCompare());
        CurrentUser user = com.agentforge.controlplane.access.CurrentUserHolder.get();
        if (user != null) {
            data.put("editable", access.canEdit(user, ResourceKind.EXPERIMENT, row));
            data.put("tenant_id", row.getTenantId());
            data.put("owner_id", row.getOwnerId());
        }
        return data;
    }

    public Map<String, Object> dumpVariant(ExperimentVariant row) {
        Agent agent = agents.findById(row.getAgentId()).orElse(null);
        return Jsons.ordered(
                "id", row.getId(),
                "experiment_id", row.getExperimentId(),
                "key", row.getKey(),
                "name", row.getName(),
                "agent_id", row.getAgentId(),
                "agent_name", agent == null ? "" : agent.getName(),
                "weight", row.getWeight());
    }

    public List<Map<String, Object>> validateVariants(List<Map<String, Object>> items, CurrentUser user) {
        if (items == null || items.size() < 2) {
            throw ApiException.badRequest("至少需要两个分流变体");
        }
        if (items.size() > 6) {
            throw ApiException.badRequest("一次实验最多 6 个变体");
        }
        List<String> seen = new ArrayList<>();
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String key = Jsons.text(item.get("key"));
            if (key.isBlank()) {
                key = VARIANT_KEYS.get(i);
            }
            key = key.strip().toUpperCase();
            if (key.length() > 16) {
                key = key.substring(0, 16);
            }
            if (seen.contains(key)) {
                throw ApiException.badRequest("变体标识 " + key + " 重复");
            }
            seen.add(key);
            long agentId = ((Number) item.get("agent_id")).longValue();
            access.getRow(user, ResourceKind.AGENT, agentId);
            int weight = item.get("weight") instanceof Number n ? Math.max(1, n.intValue()) : 50;
            String name = Jsons.text(item.get("name"));
            if (name.isBlank()) {
                name = "变体 " + key;
            }
            cleaned.add(Jsons.ordered("key", key, "name", name.length() > 80 ? name.substring(0, 80) : name,
                    "agent_id", agentId, "weight", weight));
        }
        return cleaned;
    }

    @Transactional
    public void replaceVariants(Experiment experiment, List<Map<String, Object>> items, CurrentUser user) {
        variants.deleteByExperimentId(experiment.getId());
        variants.flush();
        for (Map<String, Object> item : items) {
            ExperimentVariant row = new ExperimentVariant();
            row.setExperimentId(experiment.getId());
            row.setKey(Jsons.text(item.get("key")));
            row.setName(Jsons.text(item.get("name")));
            row.setAgentId(((Number) item.get("agent_id")).longValue());
            row.setWeight(((Number) item.get("weight")).intValue());
            access.stampOwner(row, user);
            variants.save(row);
        }
    }

    @Transactional
    public Map<String, Object> assignUnit(Experiment experiment, String sessionId, String userKey, CurrentUser user) {
        List<ExperimentVariant> rows = variants.findByExperimentIdOrderByIdAsc(experiment.getId());
        if (rows.size() < 2) {
            throw ApiException.badRequest("实验变体不完整");
        }
        String strategy = normalizeStrategy(experiment.getAssignmentStrategy(), experiment.getAssignmentUnit());
        String algo = STRATEGIES.get(strategy).get("algo");
        String unitKey = unitKey(experiment, sessionId, userKey);
        int traffic = Math.max(1, Math.min(experiment.getTrafficPercent() <= 0 ? 100 : experiment.getTrafficPercent(), 100));
        if (!"random".equals(algo)) {
            var found = assignments.findByExperimentIdAndUnitKey(experiment.getId(), unitKey);
            if (found.isPresent()) {
                ExperimentVariant variant = variants.findById(found.get().getVariantId()).orElse(null);
                return assignmentPayload(experiment, found.get(), variant, unitKey);
            }
        }
        boolean holdout = "random".equals(algo)
                ? ThreadLocalRandom.current().nextInt(100) >= traffic
                : bucket("gate:" + experiment.getId() + ":" + unitKey, 100) >= traffic;
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperimentId(experiment.getId());
        assignment.setUnitKey(unitKey);
        assignment.setHoldout(holdout);
        access.stampOwner(assignment, user);
        ExperimentVariant picked = null;
        if (!holdout) {
            picked = "random".equals(algo) || "first".equals(algo) ? pickWeighted(rows) : pickVariant(experiment.getId(), unitKey, rows);
            assignment.setVariantId(picked.getId());
        } else {
            assignment.setVariantId(rows.get(0).getId());
        }
        assignments.save(assignment);
        ExperimentEvent event = new ExperimentEvent();
        event.setExperimentId(experiment.getId());
        event.setVariantId(holdout ? null : assignment.getVariantId());
        event.setSessionId(sessionId == null ? "" : sessionId);
        event.setUnitKey(unitKey);
        event.setKind("assign");
        event.setStatus(holdout ? "holdout" : "ok");
        access.stampOwner(event, user);
        events.save(event);
        return assignmentPayload(experiment, assignment, picked, unitKey);
    }

    public void recordRun(Experiment experiment, Map<String, Object> assignment, String sessionId,
                          String status, int latencyMs, int tokens, CurrentUser user) {
        if (assignment != null && Boolean.TRUE.equals(assignment.get("holdout"))) {
            return;
        }
        ExperimentEvent event = new ExperimentEvent();
        event.setExperimentId(experiment.getId());
        Object variantId = assignment == null ? null : assignment.get("variant_id");
        event.setVariantId(variantId instanceof Number n ? n.longValue() : null);
        event.setSessionId(sessionId == null ? "" : sessionId);
        event.setUnitKey(Jsons.text(assignment == null ? null : assignment.get("unit_key")));
        event.setKind("run");
        event.setStatus(status == null ? "ok" : status);
        event.setLatencyMs(latencyMs);
        event.setTokens(tokens);
        access.stampOwner(event, user);
        events.save(event);
    }

    public Map<String, Object> results(Experiment row) {
        Map<String, Object> data = dump(row);
        List<ExperimentAssignment> assigned = assignments.findByExperimentId(row.getId());
        List<ExperimentEvent> runEvents = events.findByExperimentIdAndKind(row.getId(), "run");
        data.put("total_assignments", assigned.size());
        int holdout = (int) assigned.stream().filter(ExperimentAssignment::isHoldout).count();
        data.put("holdout", holdout);
        List<Map<String, Object>> variantRows = Jsons.mapList(data.get("variants"));
        for (Map<String, Object> item : variantRows) {
            long id = ((Number) item.get("id")).longValue();
            long count = assigned.stream().filter(a -> !a.isHoldout() && id == a.getVariantId()).count();
            item.put("assignments", count);
            int enrolled = Math.max(1, assigned.size() - holdout);
            item.put("actual_share", Math.round(count * 1000.0 / enrolled) / 10.0);
            List<ExperimentEvent> mine = runEvents.stream()
                    .filter(e -> e.getVariantId() != null && e.getVariantId() == id)
                    .toList();
            item.put("runs", mine.size());
            item.put("error_rate", mine.isEmpty() ? 0 : Math.round(mine.stream().filter(e -> "error".equals(e.getStatus())).count() * 1000.0 / mine.size()) / 10.0);
            item.put("avg_latency_ms", mine.isEmpty() ? 0 : (int) mine.stream().mapToInt(ExperimentEvent::getLatencyMs).average().orElse(0));
        }
        data.put("variants", variantRows);
        return data;
    }

    @Transactional
    public Map<String, Object> compare(Experiment experiment, Long datasetId, List<String> prompts,
                                       String scorer, int caseLimit, CurrentUser user) {
        List<ExperimentVariant> rows = variants.findByExperimentIdOrderByIdAsc(experiment.getId());
        List<String> questions = new ArrayList<>();
        if (prompts != null) {
            prompts.stream().map(String::strip).filter(s -> !s.isEmpty()).forEach(questions::add);
        }
        if (datasetId != null) {
            for (DatasetCase item : cases.findByDatasetIdAndEnabledTrueOrderByIdAsc(datasetId)) {
                if (questions.size() >= Math.max(1, Math.min(caseLimit, 12))) {
                    break;
                }
                questions.add(item.getInput());
            }
        }
        if (questions.isEmpty()) {
            throw ApiException.badRequest("请提供对比问题或数据集");
        }
        String method = scorer == null || scorer.isBlank() || "llm".equals(scorer) ? "contains" : scorer;
        Map<String, Object> snapshot = Jsons.ordered(
                "scorer", method,
                "questions", questions,
                "compared_at", Jsons.iso(Instant.now()),
                "variants", rows.stream().map(this::dumpVariant).toList());
        experiment.setLastCompare(snapshot);
        experiments.save(experiment);
        ExperimentEvent event = new ExperimentEvent();
        event.setExperimentId(experiment.getId());
        event.setKind("compare");
        event.setStatus("ok");
        access.stampOwner(event, user);
        events.save(event);
        return snapshot;
    }

    private Map<String, Object> assignmentPayload(Experiment experiment, ExperimentAssignment assignment,
                                                  ExperimentVariant variant, String unitKey) {
        return Jsons.ordered(
                "experiment_id", experiment.getId(),
                "variant_id", assignment.getVariantId(),
                "variant_key", variant == null ? "" : variant.getKey(),
                "agent_id", variant == null ? null : variant.getAgentId(),
                "unit_key", unitKey,
                "holdout", assignment.isHoldout());
    }

    private static String unitKey(Experiment experiment, String sessionId, String userKey) {
        String strategy = normalizeStrategy(experiment.getAssignmentStrategy(), experiment.getAssignmentUnit());
        String unit = STRATEGIES.get(strategy).get("unit");
        if ("request".equals(unit)) {
            return "req:" + UUID.randomUUID().toString().replace("-", "");
        }
        if ("user".equals(unit)) {
            String key = (userKey == null || userKey.isBlank() ? sessionId : userKey).strip();
            return key.isEmpty() ? "anonymous" : key;
        }
        String key = (sessionId == null || sessionId.isBlank() ? userKey : sessionId);
        key = key == null ? "" : key.strip();
        return key.isEmpty() ? "anonymous" : key;
    }

    private static ExperimentVariant pickWeighted(List<ExperimentVariant> rows) {
        int total = rows.stream().mapToInt(item -> Math.max(1, item.getWeight())).sum();
        int slot = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int cursor = 0;
        for (ExperimentVariant item : rows) {
            cursor += Math.max(1, item.getWeight());
            if (slot < cursor) {
                return item;
            }
        }
        return rows.get(rows.size() - 1);
    }

    private static ExperimentVariant pickVariant(long experimentId, String unitKey, List<ExperimentVariant> rows) {
        int total = rows.stream().mapToInt(item -> Math.max(1, item.getWeight())).sum();
        int slot = bucket(experimentId + ":" + unitKey, total);
        int cursor = 0;
        for (ExperimentVariant item : rows) {
            cursor += Math.max(1, item.getWeight());
            if (slot < cursor) {
                return item;
            }
        }
        return rows.get(rows.size() - 1);
    }

    private static int bucket(String seed, int modulo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            int value = Integer.parseUnsignedInt(HexFormat.of().formatHex(digest).substring(0, 8), 16);
            return Math.floorMod(value, Math.max(1, modulo));
        } catch (Exception e) {
            return 0;
        }
    }
}
