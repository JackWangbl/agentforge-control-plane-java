package com.agentforge.controlplane.workspace;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceStoreTest {

    @TempDir
    Path temp;

    @Test
    void deleteSessionFilesRemovesSessionAndTraceButLeavesTheWorkspace() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setWorkspacesDir(temp.toString());
        WorkspaceStore store = new WorkspaceStore(settings);
        Agent agent = new Agent();
        agent.setId(3L);
        agent.setTenantId(1L);
        agent.setName("demo");
        agent.setWorkspace("workspaces/tenants/1/agents/3-demo");
        Path root = store.workspaceDir(agent);
        Files.createDirectories(root.resolve("sessions"));
        Files.createDirectories(root.resolve("traces"));
        Files.writeString(root.resolve("agent.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("sessions").resolve("debug_1.json"), """
                {"session_id":"debug_1","traces":[{"trace_id":"traceA"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("traces").resolve("traceA.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("traces").resolve("traceB.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("traces").resolve("keep.json"), "{}", StandardCharsets.UTF_8);

        store.deleteSessionFiles(agent, "debug_1", List.of("traceB", "../agent", "keep/../../agent"));

        assertFalse(Files.exists(root.resolve("sessions").resolve("debug_1.json")));
        assertFalse(Files.exists(root.resolve("traces").resolve("traceA.json")));
        assertFalse(Files.exists(root.resolve("traces").resolve("traceB.json")));
        assertTrue(Files.exists(root.resolve("traces").resolve("keep.json")));
        assertTrue(Files.exists(root.resolve("agent.json")));
    }
}
