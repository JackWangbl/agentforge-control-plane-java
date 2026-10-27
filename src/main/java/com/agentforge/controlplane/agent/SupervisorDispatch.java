package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.util.Jsons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主智能体若只返回分类 JSON，按 category 找到唯一对应的子智能体。
 * 待确认、对不上或同时对上多个时，留在主智能体。
 */
public final class SupervisorDispatch {

    private SupervisorDispatch() {}

    public static String categoryOf(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String raw = reply.strip();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        Map<String, Object> parsed = Jsons.map(raw.substring(start, end + 1));
        return Jsons.text(parsed.get("category")).strip();
    }

    public static MultiAgentGraphs.Node match(String category, List<MultiAgentGraphs.Node> subs) {
        if (subs == null || subs.isEmpty()) {
            return null;
        }
        String wanted = normalize(category);
        if (wanted.isEmpty() || uncertain(wanted)) {
            return null;
        }
        MultiAgentGraphs.Node hit = unique(wanted, subs);
        if (hit != null) {
            return hit;
        }
        String head = leadingKind(wanted);
        if (!head.equals(wanted)) {
            return unique(head, subs);
        }
        return null;
    }

    private static MultiAgentGraphs.Node unique(String needle, List<MultiAgentGraphs.Node> subs) {
        List<MultiAgentGraphs.Node> hits = new ArrayList<>();
        for (MultiAgentGraphs.Node sub : subs) {
            if (haystack(sub).contains(needle)) {
                hits.add(sub);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static boolean uncertain(String normalized) {
        return normalized.contains("待确认")
                || normalized.contains("无法判断")
                || normalized.contains("不确定")
                || normalized.equals("unknown")
                || normalized.equals("unsure");
    }

    private static String leadingKind(String wanted) {
        if (wanted.startsWith("b端")) {
            return "b端";
        }
        if (wanted.startsWith("c端")) {
            return "c端";
        }
        return wanted;
    }

    private static String haystack(MultiAgentGraphs.Node node) {
        return normalize(MultiAgentGraphs.labelOf(node) + (node.scenario() == null ? "" : node.scenario()));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT).replace(" ", "").replace("\u3000", "");
    }
}
