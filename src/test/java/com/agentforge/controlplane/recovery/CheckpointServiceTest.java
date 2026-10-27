package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.repo.ExecutionCheckpointRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckpointServiceTest {

    @Mock ExecutionCheckpointRepository checkpoints;
    @Mock ExecutionRecordRepository executions;
    @Mock FailureCaseRepository cases;

    private ExecutionRecorder recorder;
    private CheckpointService service;
    private final List<ExecutionCheckpoint> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recorder = new ExecutionRecorder(executions, cases, checkpoints,
                new FailureClassifier(new SensitiveDataSanitizer()), new ObjectMapper());
        service = new CheckpointService(checkpoints, executions, recorder, new ObjectMapper());
        stored.clear();
        lenient().when(checkpoints.save(any(ExecutionCheckpoint.class))).thenAnswer(invocation -> {
            ExecutionCheckpoint row = invocation.getArgument(0);
            stored.add(row);
            return row;
        });
        lenient().when(checkpoints.findFirstByExecutionRecordIdAndTenantIdOrderBySequenceNoDesc(1L, 3L))
                .thenAnswer(invocation -> stored.isEmpty()
                        ? Optional.empty()
                        : Optional.of(stored.get(stored.size() - 1)));
    }

    @Test
    void appendIncrementsSequenceWithinAnExecution() {
        ExecutionCheckpoint first = service.append(3L, 1L, null, "llm", "tool",
                ExecutionCheckpoint.Outcome.NOT_STARTED, true, ExecutionCheckpoint.SideEffectSemantics.NONE,
                null, Map.of("step", 0), null, null);
        ExecutionCheckpoint second = service.append(3L, 1L, null, "tool.search", "llm",
                ExecutionCheckpoint.Outcome.CONFIRMED, true, ExecutionCheckpoint.SideEffectSemantics.SIDE_EFFECTING,
                "search", Map.of("step", 1, "done_tool_ids", List.of("search")), null, null);
        assertThat(first.getSequenceNo()).isEqualTo(1);
        assertThat(second.getSequenceNo()).isEqualTo(2);
        assertThat(second.getStateHash()).isEqualTo(ExecutionRecorder.sha256(second.getStateSnapshotJson()));
    }

    @Test
    void unknownSideEffectsAreNotSafeToResume() {
        ExecutionCheckpoint row = service.append(3L, 1L, null, "tool.pending", "tool",
                ExecutionCheckpoint.Outcome.NOT_STARTED, true, ExecutionCheckpoint.SideEffectSemantics.UNKNOWN,
                null, Map.of("pending_tools", List.of(Map.of("id", "call-1"))), null, null);
        assertThat(row.isSafeToResume()).isFalse();
    }

    @Test
    void requireCheckpointRejectsTamperedRequestHash() {
        ExecutionRecord execution = new ExecutionRecord();
        execution.setId(1L);
        execution.setTenantId(3L);
        execution.setRequestSnapshotJson("{\"message\":\"hi\"}");
        execution.setRequestHash("deadbeef");
        execution.setConfigSnapshotJson("{}");
        execution.setConfigHash(ExecutionRecorder.sha256("{}"));
        when(executions.findById(1L)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.requireCheckpoint(3L, 1L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    @Test
    void requireCheckpointRejectsTamperedStateHash() {
        ExecutionRecord execution = new ExecutionRecord();
        execution.setId(1L);
        execution.setTenantId(3L);
        String request = "{\"message\":\"hi\"}";
        String config = "{\"model_name\":\"qwen\"}";
        execution.setRequestSnapshotJson(request);
        execution.setRequestHash(ExecutionRecorder.sha256(request));
        execution.setConfigSnapshotJson(config);
        execution.setConfigHash(ExecutionRecorder.sha256(config));
        ExecutionCheckpoint checkpoint = new ExecutionCheckpoint();
        checkpoint.setTenantId(3L);
        checkpoint.setExecutionRecordId(1L);
        checkpoint.setSequenceNo(1);
        checkpoint.setStepKey("llm");
        checkpoint.setOutcome(ExecutionCheckpoint.Outcome.FAILED);
        checkpoint.setSafeToResume(true);
        checkpoint.setStateSnapshotJson("{\"working\":[]}");
        checkpoint.setStateHash("cafebabe");
        when(executions.findById(1L)).thenReturn(Optional.of(execution));
        when(checkpoints.findFirstByExecutionRecordIdAndTenantIdAndSafeToResumeTrueOrderBySequenceNoDesc(1L, 3L))
                .thenReturn(Optional.of(checkpoint));

        assertThatThrownBy(() -> service.requireSafeCheckpoint(3L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检查点完整性");
    }
}
