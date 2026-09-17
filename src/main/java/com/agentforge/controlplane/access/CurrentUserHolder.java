package com.agentforge.controlplane.access;

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
}
