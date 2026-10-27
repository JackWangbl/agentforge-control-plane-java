package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.repo.ExecutionCheckpointRepository;
import com.agentforge.controlplane.repo.ExecutionRecordRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionRecorderTest {

    @Mock ExecutionRecordRepository executions;
    @Mock FailureCaseRepository cases;
    @Mock ExecutionCheckpointRepository checkpoints;

    @Test
    void storesRawPasswordAndHashesCanonicalSnapshot() {
        when(executions.saveAndFlush(any(ExecutionRecord.class))).thenAnswer(invocation -> {
            ExecutionRecord row = invocation.getArgument(0);
            row.setId(11L);
            return row;
        });
        ExecutionRecorder recorder = new ExecutionRecorder(executions, cases, checkpoints,
                new FailureClassifier(new SensitiveDataSanitizer()), new ObjectMapper());

        Agent agent = new Agent();
        agent.setId(9L);
        agent.setName("助手");
        agent.setModelName("qwen");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", "登录");
        request.put("password", "secret-password");
        CurrentUser user = new CurrentUser(1L, "dev", "开发", 3L, 3L, "", 1L, "admin", List.of());

        ExecutionRecord saved = recorder.start(user, agent, null, ExecutionRecord.EntryPoint.PLAYGROUND,
                "debug_1", request, ExecutionRecorder.agentConfig(agent), ExecutionRecorder.credentialRefs(agent));

        ArgumentCaptor<ExecutionRecord> captor = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(executions).saveAndFlush(captor.capture());
        ExecutionRecord persisted = captor.getValue();
        assertThat(persisted.getRequestSnapshotJson()).contains("secret-password");
        assertThat(persisted.getRequestSnapshotJson()).doesNotContain(SensitiveDataSanitizer.MASK);
        assertThat(persisted.getRequestHash()).isEqualTo(ExecutionRecorder.sha256(persisted.getRequestSnapshotJson()));
        assertThat(recorder.verifySnapshot(persisted.getRequestSnapshotJson(), persisted.getRequestHash())).isTrue();
        assertThat(persisted.getCredentialRefsJson()).contains("model:qwen");
        assertThat(saved.getId()).isEqualTo(11L);
    }
}
