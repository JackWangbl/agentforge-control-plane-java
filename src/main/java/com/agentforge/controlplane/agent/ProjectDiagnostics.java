package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.repo.ConversationRepository;
import com.agentforge.controlplane.repo.FailureCaseRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 只读查看本租户的会话、失败记录和落盘日志，并对照项目启动报错。 */
@Service
public class ProjectDiagnostics {

    private final ConversationRepository conversations;
    private final FailureCaseRepository failures;
    private final TraceRepository traces;
    private final Path logFile;

    @Autowired
    public ProjectDiagnostics(ConversationRepository conversations, FailureCaseRepository failures, TraceRepository traces,
                              @Value("${logging.file.name:logs/agentforge.log}") String logFile) {
        this(conversations, failures, traces, Path.of(logFile == null || logFile.isBlank() ? "logs/agentforge.log" : logFile));
    }

    ProjectDiagnostics(ConversationRepository conversations, FailureCaseRepository failures, TraceRepository traces, Path logFile) {
        this.conversations = conversations;
        this.failures = failures;
        this.traces = traces;
        this.logFile = logFile;
    }

    public String report(Long tenantId, String focus, String symptom) {
        StringBuilder out = new StringBuilder();
        String pasted = symptom == null ? "" : symptom.strip();
        List<ProjectPlaybook.Entry> hits = ProjectPlaybook.match(pasted);
        if (!pasted.isEmpty()) {
            out.append("对照说明\n");
            if (hits.isEmpty()) {
                out.append("- 没有命中已知启动报错。请根据下面的会话、失败记录和日志继续判断。\n");
            } else {
                for (ProjectPlaybook.Entry hit : hits) {
                    out.append("- ").append(hit.title()).append("：").append(hit.action()).append('\n');
                }
            }
            out.append('\n');
        } else if (wants(focus, "setup")) {
            out.append("对照说明\n");
            out.append("把终端报错原文放到 symptom 后可以命中启动对照。当前没有原文。\n\n");
        }
        if (tenantId == null) {
            out.append("缺少租户，不能读取会话和失败记录。\n");
            return out.toString().strip();
        }
        if (wants(focus, "sessions")) {
            out.append("最近会话\n");
            List<Conversation> rows = conversations.findTop5ByTenantIdOrderByUpdatedAtDescIdDesc(tenantId);
            if (rows.isEmpty()) {
                out.append("- 暂无\n");
            }
            for (Conversation row : rows) {
                out.append("- ").append(row.getSessionId())
                        .append(" · ").append(row.getAgentName())
                        .append(" · ").append(row.getStatus())
                        .append(" · ").append(row.getLatencyMs()).append("ms")
                        .append(" · ").append(clip(row.getTitle(), 80))
                        .append('\n');
            }
            out.append('\n');
            out.append("最近链路\n");
            List<Trace> traceRows = traces.findTop8ByTenantIdOrderByStartedAtDescIdDesc(tenantId);
            if (traceRows.isEmpty()) {
                out.append("- 暂无\n");
            }
            for (Trace row : traceRows) {
                out.append("- ").append(row.getTraceId())
                        .append(" · ").append(row.getOperation())
                        .append(" · ").append(row.getStatus())
                        .append(" · ").append(row.getDurationMs()).append("ms")
                        .append('\n');
            }
            out.append('\n');
        }
        if (wants(focus, "failures")) {
            out.append("最近失败记录\n");
            List<FailureCase> cases = failures.findTop8ByTenantIdOrderByIdDesc(tenantId);
            if (cases.isEmpty()) {
                out.append("- 暂无\n");
            }
            for (FailureCase row : cases) {
                out.append("- ").append(row.getCaseKey())
                        .append(" · ").append(row.getStatus())
                        .append(" · ").append(blank(row.getFailureCategory()))
                        .append(" · ").append(blank(row.getFailureStep()))
                        .append(" · ").append(clip(redact(row.getErrorSummary()), 180))
                        .append('\n');
            }
            out.append('\n');
        }
        if (wants(focus, "logs")) {
            out.append("最近日志\n");
            out.append(tail(logFile)).append('\n');
        }
        return out.toString().strip();
    }

    static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?i)(password|passwd|secret|token|api[_-]?key)(\\s*[:=]\\s*)\\S+", "$1$2***")
                .replaceAll("(?i)(mysql://[^:]+:)[^@]+@", "$1***@");
    }

    static String tail(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "没有找到落盘日志。请把终端里的报错原文放进 symptom。";
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            int from = Math.max(0, lines.length - 40);
            StringBuilder out = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                out.append(clip(redact(lines[i]), 240)).append('\n');
            }
            return out.toString().strip();
        } catch (IOException e) {
            return "读取日志失败。";
        }
    }

    private static boolean wants(String focus, String section) {
        String value = focus == null ? "" : focus.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "all".equals(value)) {
            return true;
        }
        return switch (value) {
            case "sessions", "session", "会话" -> "sessions".equals(section);
            case "failures", "failure", "失败" -> "failures".equals(section);
            case "logs", "log", "日志" -> "logs".equals(section);
            case "setup", "启动" -> "setup".equals(section);
            default -> true;
        };
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String clip(String value, int limit) {
        String text = value == null ? "" : value.strip().replace('\n', ' ');
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…";
    }
}
