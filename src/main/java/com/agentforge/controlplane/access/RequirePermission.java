package com.agentforge.controlplane.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 等价于 FastAPI 的 require_permission(...)：列出的权限里命中任意一个即可通过。
 * 不加这个注解表示只要登录就行。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {
    String[] value() default {};
}
