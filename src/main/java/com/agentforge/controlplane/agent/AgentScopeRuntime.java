package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import com.agentforge.controlplane.memory.ContextCompressionService;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.recovery.CheckpointService;
import com.agentforge.controlplane.recovery.RecoveryTrace;
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
import io.agentscope.core.formatter.ResponseFormat;
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
    private final ContextCompressionService compression;
    private final CheckpointService checkpoints;

    public AgentScopeRuntime(ToolRuntime tools, WorkspaceStore workspaces, ContextCompressionService compression,
                             CheckpointService checkpoints) {
        this.tools = tools;
        this.workspaces = workspaces;
        this.compression = compression;
        this.checkpoints = checkpoints;
    }

    @Override
    public ChatTurnResult run(Agent agent, ModelConfig model, String message, String sessionId) {
        List<Map<String, Object>> history = new ArrayList<>();
        CurrentUser caller = CurrentUserHolder.get();
        if (caller != null) {
            history.addAll(compression.assemble(caller, agent, model, sessionId, message));
        }
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
        CurrentUser caller = CurrentUserHolder.get();
        String credential = resolveCredential(model);
        List<ToolSpec> specs = new ArrayList<>(tools.agentTools(agent));
        List<ToolOverlay.Extra> overlayTools = ToolOverlay.current();
        for (ToolOverlay.Extra extra : overlayTools) {
            specs.add(extra.spec());
        }
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

        RunState state = new RunState(agent, sessionId, lastUser);
        state.working.addAll(copyHistory(history));

        if (resume) {
            Map<String, Object> ckpt = sessionId == null ? null : workspaces.loadCheckpoint(agent, sessionId);
            if (ckpt == null || !(ckpt.get("working") instanceof List<?>)) {
                throw ApiException.conflict("没有可恢复的检查点");
            }
            String status = Jsons.text(ckpt.get("status"));
            if (!"failed".equals(status) && !"running".equals(status)) {
                throw ApiException.conflict("当前检查点已经结束，不能续跑");
            }
            state.working = copyHistory(Jsons.mapList(ckpt.get("working")));
            state.traces = new ArrayList<>(Jsons.mapList(ckpt.get("traces")));
            if (ckpt.get("usage") instanceof Map<?, ?> map) {
                map.forEach((key, value) -> state.usage.put(String.valueOf(key), value));
            }
            state.pending = new ArrayList<>(Jsons.mapList(ckpt.get("pending_tools")));
            if (ckpt.get("done_tool_ids") instanceof List<?> list) {
                list.forEach(item -> state.doneIds.add(String.valueOf(item)));
            }
            state.next = Jsons.text(ckpt.get("next")).isEmpty() ? "llm" : Jsons.text(ckpt.get("next"));
            state.step = ckpt.get("step") instanceof Number n ? n.intValue() : 0;
            String storedRunId = Jsons.text(ckpt.get("run_id"));
            if (!storedRunId.isEmpty()) {
                state.runId = storedRunId;
            }
            if (!Jsons.text(ckpt.get("last_user")).isEmpty()) {
                state.lastUser = Jsons.text(ckpt.get("last_user"));
            }
        }

        if (caller != null) {
            caller.setActiveSessionId(sessionId);
        }
        try {
            flush(state, "running", "");
            if ("tool".equals(state.next) && !state.pending.isEmpty()) {
                runPending(caller, state, forceRerunTools);
            }
            OpenAIChatModel chatModel = buildModel(model, credential);
            Toolkit toolkit = buildToolkit(agent, caller, specs, overlayTools, state);
            int maxIters = specs.stream().map(ToolSpec::name)
                    .anyMatch(name -> name.startsWith("browser_") || name.startsWith("opencli_")) ? 8 : 4;
            ReActAgent react = ReActAgent.builder()
                    .name(agent.getName())
                    .sysPrompt(withJsonInstruction(fillUserInput(PromptOverlay.apply(tools.buildSystemPrompt(agent)), state.lastUser), model))
                    .model(chatModel)
                    .toolkit(toolkit)
                    .maxIters(maxIters)
                    .enablePendingToolRecovery(true)
                    .generateOptions(chatOptions(model))
                    .build();
            List<Msg> msgs = toMsgs(state.working);
            Msg result = react.call(msgs, RuntimeContext.builder()
                            .userId("playground")
                            .sessionId(sessionId == null || sessionId.isBlank() ? agent.getName() : sessionId)
                            .build())
                    .block(Duration.ofMinutes(5));
            String reply = result == null || result.getTextContent() == null
                    ? previewReply(agent, state.lastUser, "")
                    : result.getTextContent();
            ChatUsage chatUsage = result == null ? null : result.getUsage();
            if (chatUsage != null) {
                state.usage.put("prompt_tokens", chatUsage.getInputTokens());
                state.usage.put("completion_tokens", chatUsage.getOutputTokens());
                state.usage.put("total_tokens", chatUsage.getTotalTokens());
            }
            if (sessionId != null && !sessionId.isBlank()) {
                workspaces.clearCheckpoint(agent, sessionId);
            }
            return new ChatReply(reply, "ready", state.traces, intUsage(state.usage), state.runId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!credential.isBlank()) {
                detail = detail.replace(credential, "****");
            }
            flush(state, "failed", detail);
            return new ChatReply(
                    "模型调用失败：" + detail + "\n\n" + previewReply(agent, state.lastUser, ""),
                    "error",
                    state.traces,
                    intUsage(state.usage),
                    state.runId);
        } finally {
            if (caller != null) {
                caller.setActiveSessionId(null);
            }
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

    private Toolkit buildToolkit(Agent agent, CurrentUser caller, List<ToolSpec> specs,
                                 List<ToolOverlay.Extra> overlayTools, RunState state) {
        Map<String, java.util.function.Function<Map<String, Object>, String>> overlayExecutors = new LinkedHashMap<>();
        if (overlayTools != null) {
            for (ToolOverlay.Extra extra : overlayTools) {
                overlayExecutors.put(extra.spec().name(), extra.executor());
            }
        }
        Toolkit toolkit = new Toolkit();
        for (ToolSpec spec : specs) {
            toolkit.registerAgentTool(new DelegatingTool(spec, args -> {
                if (state.doneIds.contains(spec.name())) {
                    return lastToolOutput(state.working, spec.name());
                }
                long started = System.nanoTime();
                java.util.function.Function<Map<String, Object>, String> overlay = overlayExecutors.get(spec.name());
                boolean allowed = overlay != null || tools.agentAllowsTool(agent, spec.name());
                String output = CurrentUserHolder.call(caller, () -> {
                    if (overlay != null) {
                        return overlay.apply(args == null ? Map.of() : args);
                    }
                    return allowed
                            ? tools.executeTool(spec.name(), args, agent)
                            : Jsons.json(Map.of("error", "Agent 未绑定工具 " + spec.name()));
                });
                int duration = Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000));
                String spanName = ToolOverlay.isOverlayTool(spec.name()) ? "subagent." + spec.name() : "mcp." + spec.name();
                String spanTitle = ToolOverlay.isOverlayTool(spec.name())
                        ? "通过 A2A 调用子智能体 " + spec.name()
                        : "调用工具 " + spec.name();
                state.traces.add(debugSpan(spanName, spanTitle, "tool",
                        allowed ? "ok" : "error", duration, output));
                if (allowed && FlowRuntime.isFlowTool(spec.name())) {
                    state.traces.addAll(FlowRuntime.flowStepSpans(spec.name(), output));
                }
                state.doneIds.add(spec.name());
                state.working.add(Jsons.ordered("role", "tool", "tool_call_id", spec.name(), "content", output));
                state.step += 1;
                state.next = "llm";
                flush(state, "running", "");
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
                .generateOptions(chatOptions(model))
                .build();
    }

    static GenerateOptions chatOptions(ModelConfig model) {
        GenerateOptions.Builder builder = GenerateOptions.builder().temperature(model.getTemperature());
        if (model.wantsJsonObject()) {
            builder.responseFormat(ResponseFormat.jsonObject());
        }
        return builder.build();
    }

    /** 直接拼 chat/completions 请求体时，打开开关就带上 json_object。 */
    public static void putJsonResponseFormat(Map<String, Object> body, ModelConfig model) {
        if (model != null && model.wantsJsonObject()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
    }

    static String fillUserInput(String prompt, String userText) {
        if (prompt == null || !prompt.contains("{{user_input}}")) {
            return prompt;
        }
        return prompt.replace("{{user_input}}", userText == null ? "" : userText);
    }

    private static String withJsonInstruction(String prompt, ModelConfig model) {
        if (model == null || !model.wantsJsonObject()) {
            return prompt;
        }
        String base = prompt == null ? "" : prompt;
        return base + "\n\n回复必须是一个 JSON 对象。不要输出 Markdown、代码围栏或解释。";
    }

    private void runPending(CurrentUser caller, RunState state, boolean forceRerun) {
        for (Map<String, Object> call : List.copyOf(state.pending)) {
            Map<String, Object> fn = call.get("function") instanceof Map<?, ?> map
                    ? castMap(map) : Map.of();
            String rawName = Jsons.text(fn.get("name"));
            String name = rawName.isEmpty() ? "unknown" : rawName;
            String rawCallId = Jsons.text(call.get("id"));
            String callId = rawCallId.isEmpty() ? name : rawCallId;
            if (!forceRerun && (state.doneIds.contains(callId) || state.doneIds.contains(name))) {
                state.pending.removeIf(item -> callId.equals(Jsons.text(item.get("id")))
                        || name.equals(Jsons.text(item.get("id"))));
                continue;
            }
            Map<String, Object> args = ToolRuntime.parseToolArguments(fn.get("arguments"));
            String toolName = name;
            boolean allowed = tools.agentAllowsTool(state.agent, toolName);
            String output = CurrentUserHolder.call(caller, () -> allowed
                    ? tools.executeTool(toolName, args, state.agent)
                    : Jsons.json(Map.of("error", "Agent 未绑定工具 " + toolName)));
            state.traces.add(debugSpan("mcp." + name, "调用工具 " + name, "tool", allowed ? "ok" : "error", 8, output));
            if (allowed && FlowRuntime.isFlowTool(name)) {
                state.traces.addAll(FlowRuntime.flowStepSpans(name, output));
            }
            state.working.add(Jsons.ordered("role", "tool", "tool_call_id", callId, "content", output));
            state.doneIds.add(callId);
            if (!state.doneIds.contains(name)) {
                state.doneIds.add(name);
            }
            String id = callId;
            state.pending.removeIf(item -> id.equals(Jsons.text(item.get("id")))
                    || id.equals(Jsons.text(castMap(item.get("function") instanceof Map<?, ?> m ? m : Map.of()).get("name"))));
            state.step += 1;
            state.next = state.pending.isEmpty() ? "llm" : "tool";
            flush(state, "running", "");
        }
    }

    private void flush(RunState state, String status, String error) {
        Map<String, Object> snapshot = Jsons.ordered(
                "run_id", state.runId,
                "status", status,
                "next", state.next,
                "step", state.step,
                "working", state.working,
                "pending_tools", state.pending,
                "done_tool_ids", state.doneIds,
                "traces", state.traces,
                "usage", state.usage,
                "error", error,
                "last_user", state.lastUser);
        persistDatabaseCheckpoint(state, status, error, snapshot);
        if (state.sessionId == null || state.sessionId.isBlank()) {
            return;
        }
        workspaces.saveCheckpoint(state.agent, state.sessionId, snapshot);
    }

    private void persistDatabaseCheckpoint(RunState state, String status, String error, Map<String, Object> snapshot) {
        RecoveryTrace.Binding binding = RecoveryTrace.current();
        if (binding == null || checkpoints == null) {
            return;
        }
        boolean failed = "failed".equals(status);
        boolean pendingTools = !state.pending.isEmpty();
        ExecutionCheckpoint.Outcome outcome = failed
                ? ExecutionCheckpoint.Outcome.FAILED
                : (!pendingTools && !state.doneIds.isEmpty()
                    ? ExecutionCheckpoint.Outcome.CONFIRMED
                    : ExecutionCheckpoint.Outcome.NOT_STARTED);
        ExecutionCheckpoint.SideEffectSemantics semantics = pendingTools
                ? ExecutionCheckpoint.SideEffectSemantics.UNKNOWN
                : (state.doneIds.isEmpty()
                    ? ExecutionCheckpoint.SideEffectSemantics.NONE
                    : ExecutionCheckpoint.SideEffectSemantics.SIDE_EFFECTING);
        String idempotency = state.doneIds.isEmpty() ? null : state.doneIds.get(state.doneIds.size() - 1);
        checkpoints.append(binding.tenantId(), binding.executionId(), binding.caseId(),
                stepKey(state), state.next, outcome, !pendingTools, semantics, idempotency, snapshot,
                failed ? "EXECUTION_FAILED" : null, failed ? "执行失败" : null);
    }

    private static String stepKey(RunState state) {
        if (!state.doneIds.isEmpty()) {
            return "tool." + state.doneIds.get(state.doneIds.size() - 1);
        }
        return state.next == null || state.next.isBlank() ? "llm" : state.next;
    }

    private static String lastToolOutput(List<Map<String, Object>> working, String toolId) {
        for (int i = working.size() - 1; i >= 0; i--) {
            Map<String, Object> item = working.get(i);
            if ("tool".equals(Jsons.text(item.get("role"))) && toolId.equals(Jsons.text(item.get("tool_call_id")))) {
                return Jsons.text(item.get("content"));
            }
        }
        return "";
    }

    private static final class RunState {
        final Agent agent;
        final String sessionId;
        String lastUser;
        String runId = WorkspaceStore.newTraceId();
        String next = "llm";
        int step;
        List<Map<String, Object>> working = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        List<String> doneIds = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> usage = new LinkedHashMap<>();

        private RunState(Agent agent, String sessionId, String lastUser) {
            this.agent = agent;
            this.sessionId = sessionId;
            this.lastUser = lastUser;
        }
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
