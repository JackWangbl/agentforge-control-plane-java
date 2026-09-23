package com.agentforge.controlplane.access;

import java.util.function.Supplier;

/**
 * 等价于 Python 版的 current_user_var ContextVar。序列化资源时要判断
 * "editable"、要不要脱敏密钥，但那些代码拿不到 Controller 的入参，所以放在这里。
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {}

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 工具可能在另一条线程执行，把请求线程上的登录人带过去，结束后恢复原值。 */
    public static <T> T call(CurrentUser user, Supplier<T> action) {
        CurrentUser previous = HOLDER.get();
        HOLDER.set(user);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
