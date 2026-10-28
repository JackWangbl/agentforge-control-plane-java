package com.agentforge.controlplane.access;

import com.agentforge.controlplane.web.ApiException;
import com.agentforge.controlplane.observability.ClientIpResolver;
import com.agentforge.controlplane.observability.IpBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "agentforge.currentUser";

    private final AuthService authService;
    private final IpBlacklistService ipBlacklist;

    public AuthInterceptor(AuthService authService, IpBlacklistService ipBlacklist) {
        this.authService = authService;
        this.ipBlacklist = ipBlacklist;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (isPublic(method)) {
            return true;
        }
        CurrentUser user = authService.resolve(
                request.getHeader("Authorization"),
                request.getHeader("X-Tenant-Id"));
        String clientIp = ClientIpResolver.resolve(request);
        if (ipBlacklist.isBlocked(user.getTenantId(), clientIp)) {
            throw ApiException.forbidden("当前 IP 已被拉黑");
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required != null && required.value().length > 0 && !user.has(required.value())) {
            throw ApiException.forbidden("没有权限执行该操作");
        }
        request.setAttribute(ATTRIBUTE, user);
        CurrentUserHolder.set(user);
        RequestAuthorization.set(request.getHeader("Authorization"), request.getHeader("X-Tenant-Id"));
        RequestAuthorization.setClientIp(clientIp);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUserHolder.clear();
        RequestAuthorization.clear();
    }

    private static boolean isPublic(HandlerMethod method) {
        return method.hasMethodAnnotation(PublicEndpoint.class)
                || method.getBeanType().isAnnotationPresent(PublicEndpoint.class);
    }
}
