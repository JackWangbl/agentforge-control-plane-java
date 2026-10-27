package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.repo.ExecutionCheckpointRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionRecorder {
    private final ExecutionRecordRepository executions;
    private final FailureCaseRepository cases;
    private final ExecutionCheckpointRepository checkpoints;
    private final FailureClassifier classifier;
    private final ObjectMapper mapper;

    public ExecutionRecorder(ExecutionRecordRepository executions, FailureCaseRepository cases,
                             ExecutionCheckpointRepository checkpoints, FailureClassifier classifier,
                             ObjectMapper mapper) {
        this.executions = executions;
        this.cases = cases;
        this.checkpoints = checkpoints;
        this.classifier = classifier;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionRecord start(CurrentUser user, Agent agent, Long workflowId, ExecutionRecord.EntryPoint entryPoint,
                                 String sessionId, Object request, Object logicalConfig, List<String> credentialRefs) {
        ExecutionRecord row = new ExecutionRecord();
        row.setTenantId(user.getTenantId());
        row.setOwnerId(user.getId());
        row.setAgentId(agent.getId());
        row.setAgentName(agent.getName());
        row.setWorkflowId(workflowId);
        row.setEntryPoint(entryPoint);
        row.setSessionId(sessionId);
        String requestJson = canonical(request);
        String configJson = canonical(logicalConfig);
        row.setRequestSnapshotJson(requestJson);
        row.setRequestHash(sha256(requestJson));
        row.setConfigSnapshotJson(configJson);
        row.setConfigHash(sha256(configJson));
        row.setCredentialRefsJson(canonical(credentialRefs == null ? List.of() : credentialRefs));
        return executions.saveAndFlush(row);
    }

    public static Map<String, Object> agentConfig(Agent agent) {
        if (agent == null) {
            return Map.of();
        }
        return com.agentforge.controlplane.util.Jsons.ordered(
                "name", agent.getName(),
                "description", agent.getDescription(),
                "model_name", agent.getModelName(),
                "system_prompt", agent.getSystemPrompt(),
                "skill_ids", agent.getSkillIds(),
                "mcp_ids", agent.getMcpIds(),
                "opencli_ids", agent.getOpencliIds(),
                "http_agent_ids", agent.getHttpAgentIds(),
                "knowledge_ids", agent.getKnowledgeIds(),
                "tool_flows", agent.getToolFlows(),
                "sandbox_id", agent.getSandboxId());
    }

    public static List<String> credentialRefs(Agent agent) {
        if (agent == null || agent.getModelName() == null || agent.getModelName().isBlank()) {
            return List.of();
        }
        return List.of("model:" + agent.getModelName().strip());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long executionId, String traceId) {
        ExecutionRecord row = executions.findById(executionId).orElseThrow();
        row.setTraceId(traceId);
        row.setStatus(ExecutionRecord.Status.SUCCEEDED);
        row.setFinishedAt(Instant.now());
        executions.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureCase fail(Long executionId, String traceId, String step, Throwable error) {
        ExecutionRecord row = executions.findById(executionId).orElseThrow();
        FailureClassifier.ClassifiedFailure failure = classifier.classify(error);
        row.setTraceId(traceId);
        row.setStatus(ExecutionRecord.Status.FAILED);
        row.setFailureCategory(failure.category());
        row.setFailureStep(step);
        row.setErrorSummary(failure.summary());
        row.setFinishedAt(Instant.now());
        executions.save(row);

        ExecutionCheckpoint latest = checkpoints
                .findFirstByExecutionRecordIdAndTenantIdAndSafeToResumeTrueOrderBySequenceNoDesc(row.getId(), row.getTenantId())
                .orElse(null);
        FailureCase item = new FailureCase();
        item.setTenantId(row.getTenantId());
        item.setOwnerId(row.getOwnerId());
        item.setExecutionRecordId(row.getId());
        item.setFailureCategory(failure.category());
        item.setFailureStep(step);
        item.setErrorSummary(failure.summary());
        item.setLatestCheckpointId(latest == null ? null : latest.getId());
        item.setResumable(latest != null);
        item.setStatus(latest == null ? FailureCase.Status.MANUAL_REQUIRED : FailureCase.Status.RESUMABLE);
        return cases.save(item);
    }

    public boolean verifySnapshot(String json, String expectedHash) {
        return expectedHash != null && MessageDigest.isEqual(
                sha256(json).getBytes(StandardCharsets.US_ASCII), expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public String canonical(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("请求快照无法序列化", error);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
