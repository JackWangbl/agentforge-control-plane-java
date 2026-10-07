package com.agentforge.controlplane.agent;

import java.util.function.Supplier;

/** 只在这一轮把全局人设和节点提示词叠到系统提示上，不写回 Agent。 */
public final class PromptOverlay {

    private static final ThreadLocal<String> PREFIX = new ThreadLocal<>();
    private static final ThreadLocal<String> SUFFIX = new ThreadLocal<>();

    private PromptOverlay() {}

    public static <T> T call(String prefix, String suffix, Supplier<T> action) {
        PREFIX.set(prefix == null ? "" : prefix);
        SUFFIX.set(suffix == null ? "" : suffix);
        try {
            return action.get();
        } finally {
            PREFIX.remove();
            SUFFIX.remove();
        }
    }

    public static String apply(String base) {
        String out = base == null ? "" : base;
        String prefix = PREFIX.get();
        String suffix = SUFFIX.get();
        if (prefix != null && !prefix.isBlank()) {
            out = prefix.strip() + "\n\n" + out;
        }
        if (suffix != null && !suffix.isBlank()) {
            out = out + "\n\n" + suffix.strip();
        }
        return out.strip();
    }
}
