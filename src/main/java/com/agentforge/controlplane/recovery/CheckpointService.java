package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.repo.ExecutionCheckpointRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CheckpointService {
    private final ExecutionCheckpointRepository checkpoints;
    private final ExecutionRecordRepository executions;
    private final ExecutionRecorder recorder;
    private final ObjectMapper mapper;

    public CheckpointService(ExecutionCheckpointRepository checkpoints, ExecutionRecordRepository executions,
                             ExecutionRecorder recorder, ObjectMapper mapper) {
        this.checkpoints = checkpoints;
        this.executions = executions;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionCheckpoint append(Long tenantId, Long executionId, Long caseId, String step, String nextStep,
                                      ExecutionCheckpoint.Outcome outcome, boolean safe,
                                      ExecutionCheckpoint.SideEffectSemantics semantics, String idempotencyKey,
                                      Map<String, Object> state, String errorCategory, String errorSummary) {
        int sequence = checkpoints.findFirstByExecutionRecordIdAndTenantIdOrderBySequenceNoDesc(executionId, tenantId)
                .map(item -> item.getSequenceNo() + 1).orElse(1);
        String snapshot = recorder.canonical(state == null ? Map.of() : state);
        ExecutionCheckpoint row = new ExecutionCheckpoint();
        row.setTenantId(tenantId);
        row.setExecutionRecordId(executionId);
        row.setFailureCaseId(caseId);
        row.setSequenceNo(sequence);
        row.setStepKey(step);
        row.setNextStepKey(nextStep);
        row.setOutcome(outcome);
        row.setSafeToResume(safe && semantics != ExecutionCheckpoint.SideEffectSemantics.UNKNOWN);
        row.setSideEffectSemantics(semantics);
        row.setIdempotencyKey(idempotencyKey);
        row.setStateSnapshotJson(snapshot);
        row.setStateHash(ExecutionRecorder.sha256(snapshot));
        row.setErrorCategory(errorCategory);
        row.setErrorSummary(errorSummary);
        return checkpoints.save(row);
    }

    @Transactional(readOnly = true)
    public ExecutionCheckpoint requireSafeCheckpoint(Long tenantId, Long executionId) {
        return requireCheckpoint(tenantId, executionId, false);
    }

    @Transactional(readOnly = true)
    public ExecutionCheckpoint requireCheckpoint(Long tenantId, Long executionId, boolean allowUnsafe) {
        ExecutionRecord execution = executions.findById(executionId)
                .filter(item -> tenantId.equals(item.getTenantId())).orElseThrow();
        if (!recorder.verifySnapshot(execution.getRequestSnapshotJson(), execution.getRequestHash())
                || !recorder.verifySnapshot(execution.getConfigSnapshotJson(), execution.getConfigHash())) {
            throw new IllegalStateException("请求或配置快照完整性校验失败");
        }
        ExecutionCheckpoint checkpoint = (allowUnsafe
                ? checkpoints.findFirstByExecutionRecordIdAndTenantIdOrderBySequenceNoDesc(executionId, tenantId)
                : checkpoints.findFirstByExecutionRecordIdAndTenantIdAndSafeToResumeTrueOrderBySequenceNoDesc(executionId, tenantId))
                .orElseThrow(() -> new IllegalStateException("没有可用检查点"));
        if (!recorder.verifySnapshot(checkpoint.getStateSnapshotJson(), checkpoint.getStateHash())) {
            throw new IllegalStateException("检查点完整性校验失败");
        }
        return checkpoint;
    }

    public Map<String, Object> state(ExecutionCheckpoint checkpoint) {
        try {
            return mapper.readValue(checkpoint.getStateSnapshotJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("检查点状态损坏", error);
        }
    }

    @Transactional(readOnly = true)
    public List<ExecutionCheckpoint> history(Long tenantId, Long executionId) {
        return checkpoints.findByExecutionRecordIdAndTenantIdOrderBySequenceNoAsc(executionId, tenantId);
    }
}
