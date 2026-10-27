package com.agentforge.controlplane.recovery;

import java.util.function.Supplier;

/** 当前线程正在记录的原始/重拾执行。运行时在模型、工具边界把检查点写进数据库。 */
public final class RecoveryTrace {

    public record Binding(long tenantId, long executionId, Long caseId) {}

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private RecoveryTrace() {}

    public static Binding current() {
        return CURRENT.get();
    }

    public static <T> T call(Binding binding, Supplier<T> body) {
        Binding previous = CURRENT.get();
        CURRENT.set(binding);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
