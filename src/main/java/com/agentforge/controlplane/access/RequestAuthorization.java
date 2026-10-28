package com.agentforge.controlplane.access;

/**
 * 当前请求的原始登录头。A2A 再调本机智能体时要原样带上，否则内层请求会 401。
 */
public final class RequestAuthorization {

    private static final ThreadLocal<String> AUTHORIZATION = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();

    private RequestAuthorization() {}

    public static void set(String authorization, String tenantId) {
        if (authorization == null || authorization.isBlank()) {
            AUTHORIZATION.remove();
        } else {
            AUTHORIZATION.set(authorization);
        }
        if (tenantId == null || tenantId.isBlank()) {
            TENANT.remove();
        } else {
            TENANT.set(tenantId.strip());
        }
    }

    public static String authorization() {
        return AUTHORIZATION.get();
    }

    public static String tenantId() {
        return TENANT.get();
    }

    public static void setClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            CLIENT_IP.remove();
        } else {
            CLIENT_IP.set(clientIp.strip());
        }
    }

    public static String clientIp() {
        return CLIENT_IP.get();
    }

    public static void clear() {
        AUTHORIZATION.remove();
        TENANT.remove();
        CLIENT_IP.remove();
    }
}
