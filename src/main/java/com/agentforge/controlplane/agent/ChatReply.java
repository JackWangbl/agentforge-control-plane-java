package com.agentforge.controlplane.agent;

import java.util.List;
import java.util.Map;

/**
 * 一次对话的完整结果。
 *
 * @param reply    给用户看的回复文本
 * @param mode     ready（真跑通了）/ preview（没配密钥，返回预览话术）/ error（执行出错）
 * @param spans    调试链路的步骤明细，含模型轮次和每次工具调用
 * @param usage    token 用量，键为 prompt_tokens / completion_tokens / total_tokens
 * @param traceId  本次链路 id
 */
public record ChatReply(
        String reply,
        String mode,
        List<Map<String, Object>> spans,
        Map<String, Integer> usage,
        String traceId) {

    public int promptTokens() {
        return usage == null ? 0 : usage.getOrDefault("prompt_tokens", 0);
    }

    public int completionTokens() {
        return usage == null ? 0 : usage.getOrDefault("completion_tokens", 0);
    }

    public int totalTokens() {
        if (usage == null) {
            return 0;
        }
        Integer total = usage.get("total_tokens");
        return total != null ? total : promptTokens() + completionTokens();
    }
}
