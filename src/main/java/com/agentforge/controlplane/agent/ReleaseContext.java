package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.AgentRelease;

import java.util.function.Supplier;

/** 这一轮正式调用钉住的发布版。调试台不设置，所以仍读当前草稿。 */
public final class ReleaseContext {

    private static final ThreadLocal<AgentRelease> CURRENT = new ThreadLocal<>();

    private ReleaseContext() {}

    public static AgentRelease current() {
        return CURRENT.get();
    }

    public static <T> T call(AgentRelease release, Supplier<T> action) {
        AgentRelease previous = CURRENT.get();
        CURRENT.set(release);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
