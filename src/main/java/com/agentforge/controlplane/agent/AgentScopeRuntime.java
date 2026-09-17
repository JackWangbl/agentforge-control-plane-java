package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.workspace.WorkspaceStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 AgentScope 的 ReActAgent + OpenAI 兼容模型跑一轮对话。
 * 工具真正执行仍走控制面 ToolRuntime（内置 / MCP / 沙箱 / 链路），这样和 Python 版语义对齐。
 */
@Service
public class AgentScopeRuntime implements ChatTurnRunner {

    private final ToolRuntime tools;
    private final WorkspaceStore workspaces;

    public AgentScopeRuntime(ToolRuntime tools, WorkspaceStore workspaces) {
        this.tools = tools;
        this.workspaces = workspaces;
    }

    @Override
    public ChatTurnResult run(Agent agent, ModelConfig model, String message, String sessionId) {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", message == null ? "" : message));
        ChatReply reply = generate(agent, model, history, sessionId, false, false);
        return new ChatTurnResult(
                reply.reply(),
                reply.mode(),
                reply.traceId(),
                sessionId,
                0,
                reply.promptTokens(),
                reply.completionTokens(),
                reply.spans(),
                "error".equals(reply.mode()) ? reply.reply() : "");
    }

    public ChatReply generate(Agent agent, ModelConfig model, List<Map<String, Object>> history,
                              String sessionId, boolean resume, boolean forceRerunTools) {
        String lastUser = lastUserContent(history);
        String credential = resolveCredential(model);
        List<ToolSpec> specs = tools.agentTools(agent);
        if (credential.isBlank()) {
            String extra = "当前模型没有密钥，这是预览回复。已绑定的 Skill 和 MCP 工具会在配置密钥后由模型调用。";
            List<Map<String, Object>> spans = new ArrayList<>();
            if ((lastUser.contains("几点") || lastUser.contains("时间") || lastUser.contains("日期"))
                    && tools.agentAllowsTool(agent, "get_current_time")) {
                extra = tools.executeTool("get_current_time", Map.of(), agent);
                spans.add(debugSpan("mcp.get_current_time", "调用工具 get_current_time", "tool", "ok", 6, extra));
            }
            if (sessionId != null && !sessionId.isBlank()) {
                workspaces.clearCheckpoint(agent, sessionId);
            }
            return new ChatReply(previewReply(agent, lastUser, extra), "preview", spans, Map.of(), WorkspaceStore.newTraceId());
        }

        String runId = WorkspaceStore.newTraceId();
        List<Map<String, Object>> working = copyHistory(history);
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> usage = new LinkedHashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        List<String> doneIds = new ArrayList<>();
        String next = "llm";
        int step = 0;

        if (resume) {
            Map<String, Object> ckpt = sessionId == null ? null : workspaces.loadCheckpoint(agent, sessionId);
            if (ckpt == null || !(ckpt.get("working") instanceof List<?>)) {
                throw ApiException.conflict("没有可恢复的检查点");
            }
            String status = Jsons.text(ckpt.get("status"));
            if (!"failed".equals(status) && !"running".equals(status)) {
                throw ApiException.conflict("当前检查点已经结束，不能续跑");
            }
            working = copyHistory(Jsons.mapList(ckpt.get("working")));
            traces = new ArrayList<>(Jsons.mapList(ckpt.get("traces")));
            if (ckpt.get("usage") instanceof Map<?, ?> map) {
                map.forEach((key, value) -> usage.put(String.valueOf(key), value));
            }
            pending = new ArrayList<>(Jsons.mapList(ckpt.get("pending_tools")));
            if (ckpt.get("done_tool_ids") instanceof List<?> list) {
                list.forEach(item -> doneIds.add(String.valueOf(item)));
            }
            next = Jsons.text(ckpt.get("next")).isEmpty() ? "llm" : Jsons.text(ckpt.get("next"));
            step = ckpt.get("step") instanceof Number n ? n.intValue() : 0;
            runId = Jsons.text(ckpt.get("run_id")).isEmpty() ? runId : Jsons.text(ckpt.get("run_id"));
            if (!Jsons.text(ckpt.get("last_user")).isEmpty()) {
                lastUser = Jsons.text(ckpt.get("last_user"));
            }
        }

        int stepHolder = step;
        try {
            flush(agent, sessionId, runId, "running", next, stepHolder, working, pending, doneIds, traces, usage, "", lastUser);
            if ("tool".equals(next) && !pending.isEmpty()) {
                stepHolder = runPending(agent, pending, doneIds, forceRerunTools, working, traces, stepHolder,
                        sessionId, runId, usage, lastUser);
            }
            OpenAIChatModel chatModel = buildModel(model, credential);
            Toolkit toolkit = buildToolkit(agent, specs, traces);
            int maxIters = specs.stream().map(ToolSpec::name)
                    .anyMatch(name -> name.startsWith("browser_") || name.startsWith("opencli_")) ? 8 : 4;
            ReActAgent react = ReActAgent.builder()
                    .name(agent.getName())
                    .sysPrompt(tools.buildSystemPrompt(agent))
                    .model(chatModel)
                    .toolkit(toolkit)
                    .maxIters(maxIters)
                    .enablePendingToolRecovery(true)
                    .generateOptions(GenerateOptions.builder().temperature(model.getTemperature()).build())
                    .build();
            List<Msg> msgs = toMsgs(working);
            Msg result = react.call(msgs, RuntimeContext.builder()
                            .userId("playground")
                            .sessionId(sessionId == null || sessionId.isBlank() ? agent.getName() : sessionId)
                            .build())
                    .block(Duration.ofMinutes(5));
            String reply = result == null || result.getTextContent() == null
                    ? previewReply(agent, lastUser, "")
                    : result.getTextContent();
            ChatUsage chatUsage = result == null ? null : result.getUsage();
            if (chatUsage != null) {
                usage.put("prompt_tokens", chatUsage.getInputTokens());
                usage.put("completion_tokens", chatUsage.getOutputTokens());
                usage.put("total_tokens", chatUsage.getTotalTokens());
            }
            if (sessionId != null && !sessionId.isBlank()) {
                workspaces.clearCheckpoint(agent, sessionId);
            }
            Map<String, Integer> usageInts = intUsage(usage);
            return new ChatReply(reply, "ready", traces, usageInts, runId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!credential.isBlank()) {
                detail = detail.replace(credential, "****");
            }
            flush(agent, sessionId, runId, "failed", next, stepHolder, working, pending, doneIds, traces, usage, detail, lastUser);
            return new ChatReply(
                    "模型调用失败：" + detail + "\n\n" + previewReply(agent, lastUser, ""),
                    "error",
                    traces,
                    intUsage(usage),
                    runId);
        }
    }

    public static String resolveCredential(ModelConfig model) {
        if (model.getApiKey() != null && !model.getApiKey().isBlank()) {
            return model.getApiKey();
        }
        if (model.getApiKeyRef() != null && !model.getApiKeyRef().isBlank()) {
            String env = System.getenv(model.getApiKeyRef());
            return env == null ? "" : env;
        }
        return "";
    }

    public static boolean modelHasCredential(ModelConfig model) {
        if (!resolveCredential(model).isBlank()) {
            return true;
        }
        return !model.getBaseUrl().isBlank() && model.getApiKey().isBlank() && model.getApiKeyRef().isBlank();
    }

    public static String modelEndpoint(ModelConfig model) {
        if (model.getBaseUrl() != null && !model.getBaseUrl().isBlank()) {
            return model.getBaseUrl().strip().replaceAll("/+$", "");
        }
        String provider = model.getProvider() == null ? "" : model.getProvider().toLowerCase();
        if (provider.contains("dashscope")) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return "https://api.openai.com/v1";
    }

    public static String previewReply(Agent agent, String message, String extra) {
        String duty = agent.getDescription() == null || agent.getDescription().isBlank()
                ? "通用问答与任务处理" : agent.getDescription();
        String prompt = agent.getSystemPrompt().strip();
        String hint = prompt.isEmpty() ? "" : "\n\n我的设定：" + prompt;
        String addon = extra == null || extra.isBlank() ? "" : "\n\n" + extra;
        return "我是「" + agent.getName() + "」，已经收到你的消息。\n\n"
                + message + "\n\n"
                + "我的职责是" + duty + "。你可以继续往下说，我会按同一段对话来回复。" + hint + addon;
    }

    public static Map<String, Object> debugSpan(String name, String title, String kind, String status,
                                                int durationMs, String detail) {
        String text = detail == null ? "" : detail;
        if (text.length() > 240) {
            text = text.substring(0, 240);
        }
        return Jsons.ordered(
                "name", name,
                "title", title,
                "kind", kind,
                "status", status,
                "duration_ms", durationMs,
                "detail", text);
    }

    private Toolkit buildToolkit(Agent agent, List<ToolSpec> specs, List<Map<String, Object>> traces) {
        Toolkit toolkit = new Toolkit();
        for (ToolSpec spec : specs) {
            toolkit.registerAgentTool(new DelegatingTool(spec, args -> {
                long started = System.nanoTime();
                boolean allowed = tools.agentAllowsTool(agent, spec.name());
                String output = allowed
                        ? tools.executeTool(spec.name(), args, agent)
                        : Jsons.json(Map.of("error", "Agent 未绑定工具 " + spec.name()));
                int duration = Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000));
                traces.add(debugSpan("mcp." + spec.name(), "调用工具 " + spec.name(), "tool",
                        allowed ? "ok" : "error", duration, output));
                if (allowed && FlowRuntime.isFlowTool(spec.name())) {
                    traces.addAll(FlowRuntime.flowStepSpans(spec.name(), output));
                }
                return output;
            }));
        }
        return toolkit;
    }

    private static OpenAIChatModel buildModel(ModelConfig model, String credential) {
        return OpenAIChatModel.builder()
                .apiKey(credential)
                .modelName(model.getModelId())
                .baseUrl(modelEndpoint(model))
                .stream(false)
                .generateOptions(GenerateOptions.builder().temperature(model.getTemperature()).build())
                .build();
    }

    private int runPending(Agent agent, List<Map<String, Object>> pending, List<String> doneIds,
                           boolean forceRerun, List<Map<String, Object>> working,
                           List<Map<String, Object>> traces, int step, String sessionId, String runId,
                           Map<String, Object> usage, String lastUser) {
        int current = step;
        for (Map<String, Object> call : List.copyOf(pending)) {
            Map<String, Object> fn = call.get("function") instanceof Map<?, ?> map
                    ? castMap(map) : Map.of();
            String name = Jsons.text(fn.get("name"));
            if (name.isEmpty()) {
                name = "unknown";
            }
            String rawCallId = Jsons.text(call.get("id"));
            String callId = rawCallId.isEmpty() ? name : rawCallId;
            if (doneIds.contains(callId) && !forceRerun) {
                pending.removeIf(item -> callId.equals(Jsons.text(item.get("id"))));
                continue;
            }
            Map<String, Object> args = ToolRuntime.parseToolArguments(fn.get("arguments"));
            boolean allowed = tools.agentAllowsTool(agent, name);
            String output = allowed
                    ? tools.executeTool(name, args, agent)
                    : Jsons.json(Map.of("error", "Agent 未绑定工具 " + name));
            traces.add(debugSpan("mcp." + name, "调用工具 " + name, "tool", allowed ? "ok" : "error", 8, output));
            if (allowed && FlowRuntime.isFlowTool(name)) {
                traces.addAll(FlowRuntime.flowStepSpans(name, output));
            }
            working.add(Jsons.ordered("role", "tool", "tool_call_id", callId, "content", output));
            doneIds.add(callId);
            String id = callId;
            pending.removeIf(item -> id.equals(Jsons.text(item.get("id")))
                    || id.equals(Jsons.text(castMap(item.get("function") instanceof Map<?, ?> m ? m : Map.of()).get("name"))));
            current += 1;
            flush(agent, sessionId, runId, "running", pending.isEmpty() ? "llm" : "tool",
                    current, working, pending, doneIds, traces, usage, "", lastUser);
        }
        return current;
    }

    private void flush(Agent agent, String sessionId, String runId, String status, String next, int step,
                       List<Map<String, Object>> working, List<Map<String, Object>> pending, List<String> doneIds,
                       List<Map<String, Object>> traces, Map<String, Object> usage, String error, String lastUser) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        workspaces.saveCheckpoint(agent, sessionId, Jsons.ordered(
                "run_id", runId,
                "status", status,
                "next", next,
                "step", step,
                "working", working,
                "pending_tools", pending,
                "done_tool_ids", doneIds,
                "traces", traces,
                "usage", usage,
                "error", error,
                "last_user", lastUser));
    }

    private static List<Msg> toMsgs(List<Map<String, Object>> history) {
        List<Msg> msgs = new ArrayList<>();
        for (Map<String, Object> item : history) {
            String role = Jsons.text(item.get("role"));
            String content = Jsons.text(item.get("content"));
            if ("user".equals(role)) {
                msgs.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                Object calls = item.get("tool_calls");
                if (calls instanceof List<?> list && !list.isEmpty()) {
                    List<ToolUseBlock> blocks = new ArrayList<>();
                    for (Object call : list) {
                        if (call instanceof Map<?, ?> map) {
                            Map<String, Object> fn = map.get("function") instanceof Map<?, ?> inner
                                    ? castMap(inner) : Map.of();
                            blocks.add(ToolUseBlock.builder()
                                    .id(Jsons.text(map.get("id")))
                                    .name(Jsons.text(fn.get("name")))
                                    .input(ToolRuntime.parseToolArguments(fn.get("arguments")))
                                    .build());
                        }
                    }
                    msgs.add(new AssistantMessage(content, blocks.toArray(ToolUseBlock[]::new)));
                } else {
                    msgs.add(new AssistantMessage(content));
                }
            } else if ("tool".equals(role)) {
                msgs.add(io.agentscope.core.message.ToolResultMessage.builder()
                        .result(ToolResultBlock.builder()
                                .id(Jsons.text(item.get("tool_call_id")))
                                .name("")
                                .output(List.of(io.agentscope.core.message.TextBlock.builder().text(content).build()))
                                .build())
                        .build());
            }
        }
        return msgs;
    }

    private static List<Map<String, Object>> copyHistory(List<Map<String, Object>> history) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (history == null) {
            return copy;
        }
        for (Map<String, Object> item : history) {
            copy.add(new LinkedHashMap<>(item));
        }
        return copy;
    }

    private static String lastUserContent(List<Map<String, Object>> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role"))) {
                return Jsons.text(history.get(i).get("content"));
            }
        }
        return "";
    }

    private static Map<String, Integer> intUsage(Map<String, Object> usage) {
        Map<String, Integer> out = new LinkedHashMap<>();
        usage.forEach((key, value) -> {
            if (value instanceof Number n) {
                out.put(key, n.intValue());
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
