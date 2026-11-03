package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectDiagnosticsTest {

    @TempDir
    Path temp;

    @Test
    void reportRedactsSecretsAndKeepsTheMatchedAction() throws Exception {
        ConversationRepository conversations = mock(ConversationRepository.class);
        FailureCaseRepository failures = mock(FailureCaseRepository.class);
        TraceRepository traces = mock(TraceRepository.class);
        Conversation conversation = new Conversation();
        conversation.setSessionId("sess-1");
        conversation.setAgentName("项目排查");
        conversation.setStatus("failed");
        conversation.setLatencyMs(12);
        conversation.setTitle("启动失败");
        FailureCase failure = new FailureCase();
        failure.setCaseKey("case_1");
        failure.setStatus(FailureCase.Status.MANUAL_REQUIRED);
        failure.setFailureCategory("UPSTREAM");
        failure.setFailureStep("model");
        failure.setErrorSummary("upstream password=secret-value");
        when(conversations.findTop5ByTenantIdOrderByUpdatedAtDescIdDesc(1L)).thenReturn(List.of(conversation));
        when(failures.findTop8ByTenantIdOrderByIdDesc(1L)).thenReturn(List.of(failure));
        when(traces.findTop8ByTenantIdOrderByStartedAtDescIdDesc(1L)).thenReturn(List.of());
        Path log = temp.resolve("agentforge.log");
        Files.writeString(log, "token: abcdef\nready\n");
        ProjectDiagnostics diagnostics = new ProjectDiagnostics(conversations, failures, traces, log);

        String report = diagnostics.report(1L, "all", "unknown flag: --registry-mirror");

        assertThat(report).contains("当前 Colima 版本不认识 --registry-mirror");
        assertThat(report).contains("sess-1").contains("case_1");
        assertThat(report).contains("password=***").doesNotContain("secret-value");
        assertThat(report).contains("token: ***").doesNotContain("abcdef");
    }

    @Test
    void missingLogFileDoesNotInventOutput() {
        ProjectDiagnostics diagnostics = new ProjectDiagnostics(
                mock(ConversationRepository.class), mock(FailureCaseRepository.class), mock(TraceRepository.class),
                temp.resolve("missing.log"));

        String report = diagnostics.report(null, "logs", "");

        assertThat(report).contains("缺少租户");
        assertThat(report).doesNotContain("ready");
    }
}
