package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.access.RequestAuthorization;
import com.agentforge.controlplane.domain.Conversation;
import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;

/** 解析访问者 IP；仅接受可信内网代理提供的转发头。 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static void stamp(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        String ip = RequestAuthorization.clientIp();
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            return;
        }
        conversation.setIpAddress(ip.length() <= 45 ? ip : ip.substring(0, 45));
    }

    public static String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (isTrustedProxy(remote)) {
            String forwarded = forwardedAddress(request.getHeader("Forwarded"));
            if (!forwarded.isBlank()) {
                return forwarded;
            }
            String xForwardedFor = firstAddress(request.getHeader("X-Forwarded-For"));
            if (!xForwardedFor.isBlank()) {
                return xForwardedFor;
            }
            String realIp = normalize(request.getHeader("X-Real-IP"));
            if (!realIp.isBlank()) {
                return realIp;
            }
        }
        return remote.isBlank() ? "unknown" : remote;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip().replace("\"", "");
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            value = end > 0 ? value.substring(1, end) : value;
        } else if (value.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$")) {
            value = value.substring(0, value.lastIndexOf(':'));
        }
        int zone = value.indexOf('%');
        if (zone > 0) {
            value = value.substring(0, zone);
        }
        if (isIpv4(value)) {
            return value;
        }
        if (!value.contains(":")) {
            return "";
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet6Address ? address.getHostAddress() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String forwardedAddress(String header) {
        if (header == null || header.isBlank()) {
            return "";
        }
        for (String part : header.split("[,;]")) {
            String item = part.strip();
            if (item.regionMatches(true, 0, "for=", 0, 4)) {
                return normalize(item.substring(4));
            }
        }
        return "";
    }

    private static String firstAddress(String header) {
        if (header == null || header.isBlank()) {
            return "";
        }
        return normalize(header.split(",", 2)[0]);
    }

    private static boolean isTrustedProxy(String ip) {
        if (ip.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (part.isBlank() || Integer.parseInt(part) > 255 || Integer.parseInt(part) < 0) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }
}
