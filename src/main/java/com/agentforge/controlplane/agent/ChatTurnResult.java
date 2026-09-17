package com.agentforge.controlplane.agent;

/** 一轮对话的结果。mode 为 "error" 表示模型或工具出错，调用方要据此把结果标成失败。 */
public record ChatTurnResult(String reply, String mode, String traceId, String sessionId, int latencyMs, int promptTokens, int completionTokens, java.util.List<java.util.Map<String, Object>> spans, String error) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
