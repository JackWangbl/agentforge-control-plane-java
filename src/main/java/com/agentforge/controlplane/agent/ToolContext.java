package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 一次工具执行的上下文，等价于 Python 版 execution_context.py。
 * scopeKey 用来隔离浏览器会话等有状态资源，保证不同租户/Agent 互不串。
 */
public record ToolContext(Long tenantId, Agent agent, String sessionId) {

    public Long agentId() {
        return agent == null ? null : agent.getId();
    }

    public String agentName() {
        return agent == null ? "" : agent.getName();
    }

    /** tenant-{租户}-agent-{Agent}-{会话摘要前 24 位}，与 Python 版格式一致。 */
    public String scopeKey() {
        String seed = (tenantId == null ? "0" : tenantId.toString())
                + ":" + (agentId() == null ? "0" : agentId().toString())
                + ":" + (sessionId == null ? "" : sessionId);
        return "tenant-" + tenantId + "-agent-" + agentId() + "-" + sha256(seed).substring(0, 24);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算隔离键失败", e);
        }
    }
}
