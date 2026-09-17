package com.agentforge.controlplane.workspace;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 每个 Agent 一块磁盘工作区：会话、检查点、链路文件。对齐 Python 版 agent_workspace.py。 */
@Service
public class WorkspaceStore {

    private static final Pattern UNSAFE = Pattern.compile("[^\\w\\u4e00-\\u9fff]+");
    private static final Pattern MULTI_DASH = Pattern.compile("-{2,}");
    private static final Pattern SESSION_SAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final AppSettings settings;

    public WorkspaceStore(AppSettings settings) {
        this.settings = settings;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public Path workspacesRoot() {
        String configured = settings.getWorkspacesDir() == null ? "" : settings.getWorkspacesDir().strip();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).resolve("workspaces").toAbsolutePath().normalize();
    }

    public static String slugify(String name) {
        String text = MULTI_DASH.matcher(UNSAFE.matcher(name == null ? "agent" : name.strip()).replaceAll("-"))
                .replaceAll("-")
                .replaceAll("^-|-$", "");
        if (text.isEmpty()) {
            text = "agent";
        }
        return text.length() > 48 ? text.substring(0, 48) : text;
    }

    public String workspaceRelpath(Agent agent) {
        long tenantId = agent.getTenantId() == null ? 0L : agent.getTenantId();
        String stored = (agent.getWorkspace() == null ? "" : agent.getWorkspace()).replace("\\", "/").strip();
        while (stored.startsWith("/")) {
            stored = stored.substring(1);
        }
        String expectedPrefix = "workspaces/tenants/" + tenantId + "/agents/";
        if (stored.startsWith(expectedPrefix) && Path.of(stored).getFileName().toString().startsWith(agent.getId() + "-")) {
            return stored;
        }
        return expectedPrefix + agent.getId() + "-" + slugify(agent.getName());
    }

    public Path workspaceDir(Agent agent) {
        String rel = workspaceRelpath(agent);
        if (rel.startsWith("workspaces/")) {
            return workspacesRoot().resolve(rel.substring("workspaces/".length()));
        }
        return workspacesRoot().resolve(Path.of(rel).getFileName());
    }

    public Path ensureWorkspace(Agent agent) {
        Path path = workspaceDir(agent);
        try {
            Files.createDirectories(path.resolve("sessions"));
            Files.createDirectories(path.resolve("traces"));
            Files.createDirectories(path.resolve("files"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 Agent 工作区", e);
        }
        agent.setWorkspace(workspaceRelpath(agent));
        writeManifest(agent);
        return path;
    }

    public void removeWorkspace(Agent agent) {
        Path root = workspacesRoot().normalize();
        Path path = workspaceDir(agent).normalize();
        if (Files.isDirectory(path) && path.startsWith(root)) {
            try {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(item -> {
                            try {
                                Files.deleteIfExists(item);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    public void writeManifest(Agent agent) {
        Path path = workspaceDir(agent);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {
        }
        writeJson(path.resolve("agent.json"), Jsons.ordered(
                "id", agent.getId(),
                "tenant_id", agent.getTenantId() == null ? 0L : agent.getTenantId(),
                "name", agent.getName(),
                "description", agent.getDescription() == null ? "" : agent.getDescription(),
                "model_name", agent.getModelName() == null ? "" : agent.getModelName(),
                "version", agent.getVersion() == null ? "" : agent.getVersion(),
                "system_prompt", agent.getSystemPrompt(),
                "skill_ids", agent.getSkillIds(),
                "mcp_ids", agent.getMcpIds(),
                "opencli_ids", agent.getOpencliIds(),
                "http_agent_ids", agent.getHttpAgentIds(),
                "updated_at", Jsons.iso(Instant.now())));
    }

    public Path sessionPath(Agent agent, String sessionId) {
        String safe = SESSION_SAFE.matcher(sessionId == null ? "" : sessionId).replaceAll("_");
        return workspaceDir(agent).resolve("sessions").resolve(safe + ".json");
    }

    public Map<String, Object> loadSession(Agent agent, String sessionId) {
        Path path = sessionPath(agent, sessionId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Jsons.MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> checkpointPublic(Map<String, Object> ckpt) {
        if (ckpt == null || ckpt.isEmpty()) {
            return null;
        }
        String status = Jsons.text(ckpt.get("status"));
        return Jsons.ordered(
                "run_id", Jsons.text(ckpt.get("run_id")),
                "status", status,
                "next", Jsons.text(ckpt.get("next")),
                "step", ckpt.get("step") instanceof Number n ? n.intValue() : 0,
                "error", Jsons.text(ckpt.get("error")),
                "resumable", "failed".equals(status) || "running".equals(status));
    }

    public void saveCheckpoint(Agent agent, String sessionId, Map<String, Object> payload) {
        ensureWorkspace(agent);
        String now = Jsons.iso(Instant.now());
        Map<String, Object> data = loadSession(agent, sessionId);
        if (data == null) {
            data = Jsons.ordered(
                    "session_id", sessionId,
                    "tenant_id", agent.getTenantId() == null ? 0L : agent.getTenantId(),
                    "agent_id", agent.getId(),
                    "agent_name", agent.getName(),
                    "messages", new ArrayList<>(),
                    "traces", new ArrayList<>(),
                    "created_at", now);
        }
        Map<String, Object> ckpt = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        ckpt.put("session_id", sessionId);
        ckpt.put("agent_id", agent.getId());
        ckpt.put("updated_at", now);
        data.put("checkpoint", ckpt);
        data.put("agent_id", agent.getId());
        data.put("agent_name", agent.getName());
        data.put("updated_at", now);
        Object messages = data.get("messages");
        boolean empty = !(messages instanceof List<?> list) || list.isEmpty();
        if (empty && !Jsons.text(ckpt.get("last_user")).isEmpty()) {
            data.put("messages", List.of(Jsons.ordered(
                    "role", "user",
                    "content", ckpt.get("last_user"),
                    "agent_name", "我",
                    "created_at", now)));
            data.put("title", Jsons.text(ckpt.get("last_user")));
            String title = Jsons.text(data.get("title"));
            if (title.length() > 80) {
                data.put("title", title.substring(0, 80));
            }
        }
        writeJson(sessionPath(agent, sessionId), data);
    }

    public Map<String, Object> loadCheckpoint(Agent agent, String sessionId) {
        Map<String, Object> data = loadSession(agent, sessionId);
        if (data == null) {
            return null;
        }
        Object ckpt = data.get("checkpoint");
        if (ckpt instanceof Map<?, ?> map && !Jsons.text(map.get("status")).isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return null;
    }

    public void clearCheckpoint(Agent agent, String sessionId) {
        Map<String, Object> data = loadSession(agent, sessionId);
        if (data == null || data.get("checkpoint") == null) {
            return;
        }
        data.put("checkpoint", null);
        data.put("updated_at", Jsons.iso(Instant.now()));
        writeJson(sessionPath(agent, sessionId), data);
    }

    public Map<String, Object> persistRun(Agent agent, String sessionId, String title, String message, String reply,
                                          String mode, String modelName, String traceId,
                                          List<Map<String, Object>> spans, Map<String, ?> usage,
                                          int latencyMs, boolean includeUser) {
        ensureWorkspace(agent);
        String now = Jsons.iso(Instant.now());
        Map<String, Object> data = loadSession(agent, sessionId);
        if (data == null) {
            data = Jsons.ordered(
                    "session_id", sessionId,
                    "tenant_id", agent.getTenantId() == null ? 0L : agent.getTenantId(),
                    "agent_id", agent.getId(),
                    "agent_name", agent.getName(),
                    "title", title == null ? "" : (title.length() > 80 ? title.substring(0, 80) : title),
                    "channel", "Playground",
                    "messages", new ArrayList<>(),
                    "traces", new ArrayList<>(),
                    "created_at", now);
        }
        data.put("agent_id", agent.getId());
        data.put("agent_name", agent.getName());
        if (Jsons.text(data.get("title")).isEmpty() && title != null) {
            data.put("title", title.length() > 80 ? title.substring(0, 80) : title);
        }
        data.put("updated_at", now);
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) data.computeIfAbsent("messages", key -> new ArrayList<>());
        Object last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        boolean skipUser = false;
        if (last instanceof Map<?, ?> map) {
            skipUser = "user".equals(map.get("role")) && Jsons.text(map.get("content")).equals(message);
        }
        if (includeUser && message != null && !message.isEmpty() && !skipUser) {
            messages.add(Jsons.ordered("role", "user", "content", message, "agent_name", "我", "created_at", now));
        }
        messages.add(Jsons.ordered(
                "role", "assistant",
                "content", reply,
                "agent_name", agent.getName(),
                "created_at", now,
                "error", "error".equals(mode)));
        if (!"error".equals(mode)) {
            data.put("checkpoint", null);
        }
        Map<String, Object> trace = Jsons.ordered(
                "trace_id", traceId,
                "session_id", sessionId,
                "agent_id", agent.getId(),
                "agent_name", agent.getName(),
                "model", modelName,
                "status", "error".equals(mode) ? "error" : "ok",
                "duration_ms", latencyMs,
                "spans", spans == null ? List.of() : spans,
                "usage", usage == null ? Map.of() : usage,
                "created_at", now);
        @SuppressWarnings("unchecked")
        List<Object> traces = (List<Object>) data.computeIfAbsent("traces", key -> new ArrayList<>());
        traces.add(trace);
        writeJson(sessionPath(agent, sessionId), data);
        writeJson(workspaceDir(agent).resolve("traces").resolve(traceId + ".json"), trace);
        return data;
    }

    public Map<String, Object> workspaceStatus(Agent agent) {
        ensureWorkspace(agent);
        List<Map<String, Object>> sessions = listSessions(agent);
        int traces = 0;
        Path traceDir = workspaceDir(agent).resolve("traces");
        if (Files.isDirectory(traceDir)) {
            try (var stream = Files.list(traceDir)) {
                traces = (int) stream.filter(path -> path.getFileName().toString().endsWith(".json")).count();
            } catch (IOException ignored) {
            }
        }
        return Jsons.ordered(
                "path", agent.getWorkspace() == null || agent.getWorkspace().isEmpty()
                        ? workspaceRelpath(agent) : agent.getWorkspace(),
                "session_count", sessions.size(),
                "trace_count", traces,
                "sessions", sessions,
                "latest_session_id", sessions.isEmpty() ? "" : sessions.get(0).get("session_id"));
    }

    public List<Map<String, Object>> listSessions(Agent agent) {
        Path folder = workspaceDir(agent).resolve("sessions");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    Map<String, Object> data = Jsons.MAPPER.readValue(
                            Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
                    rows.add(sessionSummary(data));
                } catch (Exception ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        rows.sort(Comparator.comparing((Map<String, Object> item) -> Jsons.text(item.get("updated_at"))).reversed());
        return rows;
    }

    public Map<String, Object> sessionSummary(Map<String, Object> data) {
        List<Map<String, Object>> messages = Jsons.mapList(data.get("messages"));
        List<Map<String, Object>> traces = Jsons.mapList(data.get("traces"));
        Object ckpt = data.get("checkpoint");
        Map<String, Object> publicCkpt = null;
        if (ckpt instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            publicCkpt = checkpointPublic(copy);
        }
        return Jsons.ordered(
                "session_id", Jsons.text(data.get("session_id")),
                "title", Jsons.text(data.get("title")),
                "message_count", messages.size(),
                "trace_count", traces.size(),
                "updated_at", Jsons.text(data.get("updated_at")).isEmpty()
                        ? Jsons.text(data.get("created_at")) : data.get("updated_at"),
                "messages", messages,
                "traces", traces,
                "checkpoint", publicCkpt);
    }

    private void writeJson(Path path, Object payload) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, Jsons.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                    StandardCharsets.UTF_8);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入工作区文件失败：" + path, e);
        }
    }
}
