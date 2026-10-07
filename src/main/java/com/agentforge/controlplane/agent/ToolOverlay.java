package com.agentforge.controlplane.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 在一轮生成里临时注入额外工具（例如主智能体可调用的 SubAgent），
 * 不写回 Agent 绑定。嵌套生成（子智能体自己跑一轮）会清空注入，防止递归互调。
 */
public final class ToolOverlay {

    public record Extra(ToolSpec spec, Function<Map<String, Object>, String> executor) {}

    private static final ThreadLocal<List<Extra>> EXTRAS = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ToolOverlay() {}

    public static <T> T call(List<Extra> extras, Supplier<T> action) {
        List<Extra> previous = EXTRAS.get();
        EXTRAS.set(extras == null || extras.isEmpty() ? List.of() : List.copyOf(extras));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                EXTRAS.remove();
            } else {
                EXTRAS.set(previous);
            }
        }
    }

    /** 子智能体 / 流水线内层调用：本层看不到外层注入的 SubAgent 工具。 */
    public static <T> T nest(Supplier<T> action) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
            return action.get();
        } finally {
            if (depth <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }

    public static List<Extra> current() {
        if (DEPTH.get() > 0) {
            return List.of();
        }
        List<Extra> extras = EXTRAS.get();
        return extras == null ? List.of() : extras;
    }

    public static boolean isOverlayTool(String name) {
        return name != null && name.startsWith("call_sub_");
    }
}
