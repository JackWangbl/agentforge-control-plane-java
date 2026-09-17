package com.agentforge.controlplane.access;

import com.agentforge.controlplane.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "agentforge.currentUser";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
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
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required != null && required.value().length > 0 && !user.has(required.value())) {
            throw ApiException.forbidden("没有权限执行该操作");
        }
        request.setAttribute(ATTRIBUTE, user);
        CurrentUserHolder.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUserHolder.clear();
    }

    private static boolean isPublic(HandlerMethod method) {
        return method.hasMethodAnnotation(PublicEndpoint.class)
                || method.getBeanType().isAnnotationPresent(PublicEndpoint.class);
    }
}
