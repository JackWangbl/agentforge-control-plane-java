package com.agentforge.controlplane.agent;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 运行时按 JSON Schema 挂到 AgentScope Toolkit 上的动态工具，真正执行交给控制面的 ToolRuntime。 */
public class DelegatingTool extends ToolBase {

    private final Function<Map<String, Object>, String> executor;

    public DelegatingTool(ToolSpec spec, Function<Map<String, Object>, String> executor) {
        super(ToolBase.builder()
                .name(spec.name())
                .description(spec.description() == null ? "" : spec.description())
                .inputSchema(spec.parameters() == null ? Map.of("type", "object", "properties", Map.of()) : spec.parameters())
                .concurrencySafe(true));
        this.executor = executor;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("控制面已绑定的工具默认放行"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String text;
        ToolResultState state = ToolResultState.SUCCESS;
        try {
            text = executor.apply(input);
            if (text == null) {
                text = "";
            }
        } catch (Exception e) {
            text = "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            state = ToolResultState.ERROR;
        }
        String id = "";
        if (param.getToolUseBlock() != null && param.getToolUseBlock().getId() != null) {
            id = param.getToolUseBlock().getId();
        }
        return Mono.just(ToolResultBlock.builder()
                .id(id)
                .name(getName())
                .output(List.of(TextBlock.builder().text(text).build()))
                .state(state)
                .build());
    }
}
