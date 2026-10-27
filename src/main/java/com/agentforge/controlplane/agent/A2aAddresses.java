package com.agentforge.controlplane.agent;

/** 每个智能体一份 A2A 地址。卡片和 JSON-RPC 都挂在这下面，不占用网站根路径。 */
public final class A2aAddresses {

    public static final String CALL_HEADER = "X-AgentForge-Call";

    private A2aAddresses() {}

    public static String rpcPath(Long agentId) {
        return "/api/a2a/agents/" + agentId;
    }

    public static String cardPath(Long agentId) {
        return rpcPath(agentId) + "/.well-known/agent-card.json";
    }

    public static String endpoint(int port, Long agentId) {
        return "http://127.0.0.1:" + port + rpcPath(agentId);
    }

    /**
     * 卡片地址要按目录拼接。相对路径如果以 / 开头，会被解析成网站根路径。
     */
    public static String cardBase(int port, Long agentId) {
        return endpoint(port, agentId) + "/";
    }

    public static String cardRelativePath() {
        return ".well-known/agent-card.json";
    }

    public static String header(java.util.Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
