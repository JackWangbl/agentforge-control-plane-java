package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;

import java.util.List;
import java.util.Map;

/**
 * 一族工具的接入点。内置工具、沙箱、浏览器、OpenCLI、远端 MCP 各实现一个，
 * ToolkitFactory 会把所有实现汇总成 Agent 这一轮可用的工具集。
 */
public interface ToolProvider {

    /** 这个 Agent 在本次运行里能用到的工具；没绑定就返回空列表。 */
    List<ToolSpec> specsFor(Agent agent, ToolContext context);

    /** 该工具名是否由本 provider 负责执行。 */
    boolean handles(String toolName, Agent agent);

    /** 执行工具，返回给模型看的文本（约定失败时返回 {"error": "..."} 的 JSON）。 */
    String execute(String toolName, Map<String, Object> arguments, ToolContext context);

    /** 拼进系统提示的补充说明，不需要就返回空串。 */
    default String promptHint(Agent agent, ToolContext context) {
        return "";
    }
}
