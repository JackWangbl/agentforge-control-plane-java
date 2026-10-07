package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.util.Jsons;
import com.agentforge.controlplane.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * 多智能体画布的图。
 * <ul>
 *   <li>handoff — 场景移交（默认，对齐扣子多 Agent）</li>
 *   <li>supervisor — 主从：开始只连主智能体，主智能体把下游当作 SubAgent 工具调用</li>
 *   <li>pipeline — 工作流：按连线串行（或多路分支）跑多智能体</li>
 * </ul>
 */
public final class MultiAgentGraphs {

    public static final int MAX_JUMPS = 5;
    public static final int MAX_SUBAGENTS = 8;
    public static final int MAX_PIPELINE_STEPS = 8;

    public static final String PATTERN_HANDOFF = "handoff";
    public static final String PATTERN_SUPERVISOR = "supervisor";
    public static final String PATTERN_PIPELINE = "pipeline";

    private MultiAgentGraphs() {}

    public record Node(String id, String type, String label, Long agentId, String scenario, String prompt,
                       String condition) {}

    public record Edge(String source, String target) {}

    public record Graph(String mode, String pattern, String dispatch, String globalPrompt, List<Node> nodes,
                        List<Edge> edges) {
        public Node node(String id) {
            for (Node node : nodes) {
                if (node.id().equals(id)) {
                    return node;
                }
            }
            return null;
        }

        public Node start() {
            for (Node node : nodes) {
                if ("start".equals(node.type())) {
                    return node;
                }
            }
            return null;
        }

        public List<Node> outgoing(String sourceId, String type) {
            List<Node> found = new ArrayList<>();
            for (Edge edge : edges) {
                if (!edge.source().equals(sourceId)) {
                    continue;
                }
                Node target = node(edge.target());
                if (target != null && type.equals(target.type())) {
                    found.add(target);
                }
            }
            return found;
        }

        public List<Node> outgoingAgents(String sourceId) {
            return outgoing(sourceId, "agent");
        }
    }

    public static Graph read(Map<String, Object> raw) {
        Map<String, Object> graph = raw == null ? Map.of() : raw;
        String dispatch = "last".equals(Jsons.text(graph.get("dispatch"))) ? "last" : "start";
        List<Node> nodes = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(graph.get("nodes"))) {
            String type = normalizeType(Jsons.text(item.get("type")));
            if (type.isEmpty()) {
                continue;
            }
            String id = Jsons.text(item.get("id")).strip();
            if (id.isEmpty()) {
                continue;
            }
            nodes.add(new Node(
                    id,
                    type,
                    Jsons.text(item.get("label")).strip(),
                    Jsons.asLong(item.get("agent_id")),
                    Jsons.text(item.get("scenario")).strip(),
                    Jsons.text(item.get("prompt")).strip(),
                    Jsons.text(item.get("condition")).strip()));
        }
        List<Edge> edges = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(graph.get("edges"))) {
            String source = Jsons.text(item.get("source")).strip();
            String target = Jsons.text(item.get("target")).strip();
            if (!source.isEmpty() && !target.isEmpty()) {
                edges.add(new Edge(source, target));
            }
        }
        return new Graph(Jsons.text(graph.get("mode")).strip(), normalizePattern(Jsons.text(graph.get("pattern"))),
                dispatch, Jsons.text(graph.get("global_prompt")).strip(), nodes, edges);
    }

    public static void requirePublishable(Graph graph, LongPredicate agentVisible) {
        if (!"multi_agent".equals(graph.mode())) {
            throw ApiException.unprocessable("发布前请在多智能体画布中保存");
        }
        List<Node> starts = graph.nodes().stream().filter(node -> "start".equals(node.type())).toList();
        if (starts.size() != 1) {
            throw ApiException.unprocessable("画布上需要且只能有一个开始节点");
        }
        List<Node> jumps = graph.nodes().stream().filter(node -> "jump".equals(node.type())).toList();
        if (jumps.size() > MAX_JUMPS) {
            throw ApiException.unprocessable("全局跳转条件最多 5 个");
        }
        Map<Long, String> seenAgents = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            if (!"agent".equals(node.type())) {
                continue;
            }
            if (node.agentId() == null) {
                throw ApiException.unprocessable("有智能体节点没有绑定智能体");
            }
            if (seenAgents.containsKey(node.agentId())) {
                throw ApiException.unprocessable("同一个智能体在一张画布上只能出现一次");
            }
            if (agentVisible == null || !agentVisible.test(node.agentId())) {
                throw ApiException.unprocessable("智能体不存在或当前账号看不见：" + labelOf(node));
            }
            seenAgents.put(node.agentId(), node.id());
        }
        for (Edge edge : graph.edges()) {
            Node source = graph.node(edge.source());
            Node target = graph.node(edge.target());
            if (source == null || target == null) {
                throw ApiException.unprocessable("有连线指向已经不在画布上的节点");
            }
            boolean ok = ("start".equals(source.type()) && "agent".equals(target.type()))
                    || ("agent".equals(source.type()) && "agent".equals(target.type()))
                    || ("jump".equals(source.type()) && "agent".equals(target.type()));
            if (!ok) {
                throw ApiException.unprocessable("连线只能从开始节点或智能体指向智能体，全局跳转也只能指向智能体");
            }
        }
        for (int i = 0; i < graph.edges().size(); i++) {
            Edge edge = graph.edges().get(i);
            for (int j = 0; j < i; j++) {
                Edge earlier = graph.edges().get(j);
                if (earlier.source().equals(edge.source()) && earlier.target().equals(edge.target())) {
                    throw ApiException.unprocessable("存在重复连线");
                }
            }
        }
        for (Node jump : jumps) {
            if (jump.condition().isBlank()) {
                throw ApiException.unprocessable("全局跳转条件不能为空");
            }
            List<Node> targets = graph.outgoing(jump.id(), "agent");
            if (targets.size() != 1) {
                throw ApiException.unprocessable("每条全局跳转必须连到一个智能体");
            }
        }

        String pattern = graph.pattern();
        if (PATTERN_SUPERVISOR.equals(pattern)) {
            requireSupervisor(graph, starts.get(0));
        } else if (PATTERN_PIPELINE.equals(pattern)) {
            requirePipeline(graph, starts.get(0));
        } else {
            requireHandoff(graph, starts.get(0));
        }
    }

    private static void requireHandoff(Graph graph, Node start) {
        List<Node> roots = graph.outgoingAgents(start.id());
        if (roots.isEmpty()) {
            throw ApiException.unprocessable("请从开始节点至少连出一个智能体");
        }
        for (Node node : graph.nodes()) {
            if (!"agent".equals(node.type())) {
                continue;
            }
            boolean incoming = graph.edges().stream().anyMatch(edge -> edge.target().equals(node.id()));
            if (incoming && node.scenario().isBlank()) {
                throw ApiException.unprocessable("请为「" + labelOf(node) + "」填写适用场景");
            }
        }
    }

    private static void requireSupervisor(Graph graph, Node start) {
        List<Node> mains = graph.outgoingAgents(start.id());
        if (mains.size() != 1) {
            throw ApiException.unprocessable("主从模式下，开始节点必须只连一个主智能体");
        }
        Node main = mains.get(0);
        List<Node> subs = graph.outgoingAgents(main.id());
        if (subs.size() > MAX_SUBAGENTS) {
            throw ApiException.unprocessable("主智能体最多挂 " + MAX_SUBAGENTS + " 个子智能体");
        }
        for (Node sub : subs) {
            if (sub.scenario().isBlank()) {
                throw ApiException.unprocessable("请为子智能体「" + labelOf(sub) + "」填写适用场景（会作为工具说明）");
            }
        }
        for (Node node : graph.nodes()) {
            if (!"agent".equals(node.type()) || node.id().equals(main.id())) {
                continue;
            }
            boolean fromMain = graph.edges().stream()
                    .anyMatch(edge -> edge.source().equals(main.id()) && edge.target().equals(node.id()));
            boolean fromJump = graph.edges().stream().anyMatch(edge -> {
                if (!edge.target().equals(node.id())) {
                    return false;
                }
                Node source = graph.node(edge.source());
                return source != null && "jump".equals(source.type());
            });
            boolean fromStart = graph.edges().stream()
                    .anyMatch(edge -> edge.source().equals(start.id()) && edge.target().equals(node.id()));
            if (!fromMain && !fromJump && !fromStart && node.scenario().isBlank()) {
                // orphan agent nodes are ok in draft but publish requires them connected somehow
            }
        }
    }

    private static void requirePipeline(Graph graph, Node start) {
        List<Node> roots = graph.outgoingAgents(start.id());
        if (roots.isEmpty()) {
            throw ApiException.unprocessable("请从开始节点至少连出一个智能体，作为工作流第一步");
        }
        for (Node node : graph.nodes()) {
            if (!"agent".equals(node.type()) && !"start".equals(node.type())) {
                continue;
            }
            List<Node> next = graph.outgoingAgents(node.id());
            if (next.size() > 1) {
                for (Node target : next) {
                    if (target.scenario().isBlank()) {
                        throw ApiException.unprocessable("分支目标「" + labelOf(target) + "」需要填写适用场景");
                    }
                }
            }
        }
        for (Node node : graph.nodes()) {
            if (!"agent".equals(node.type())) {
                continue;
            }
            boolean incoming = graph.edges().stream().anyMatch(edge -> edge.target().equals(node.id()));
            if (incoming && node.scenario().isBlank()) {
                List<Node> peers = graph.edges().stream()
                        .filter(edge -> edge.target().equals(node.id()))
                        .map(edge -> graph.node(edge.source()))
                        .filter(source -> source != null)
                        .flatMap(source -> graph.outgoingAgents(source.id()).stream())
                        .toList();
                if (peers.size() > 1) {
                    throw ApiException.unprocessable("请为「" + labelOf(node) + "」填写适用场景");
                }
            }
        }
    }

    public static String subToolName(Node node) {
        String raw = node == null ? "agent" : node.id();
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.isBlank()) {
            safe = "agent";
        }
        return "call_sub_" + safe;
    }

    /** 模型返回的 choice。解析失败时返回 null，表示这次判断没有做成。 */
    public static Integer parseChoice(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String raw = text.strip();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        Object choice = Jsons.map(raw.substring(start, end + 1)).get("choice");
        if (choice instanceof Number number) {
            return number.intValue();
        }
        if (choice == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(choice).strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * @param allowNone    允许 choice 为负数，表示留下或都不符合
     * @param fallbackFirst 判断失败时用第一个候选人
     * @return 候选人下标，或 -1
     */
    public static int resolveChoice(Integer choice, int count, boolean allowNone, boolean fallbackFirst) {
        if (count <= 0) {
            return -1;
        }
        if (choice != null && choice >= 0 && choice < count) {
            return choice;
        }
        if (choice != null && choice < 0 && allowNone) {
            return -1;
        }
        if (fallbackFirst) {
            return 0;
        }
        return -1;
    }

    public static String labelOf(Node node) {
        if (node == null) {
            return "";
        }
        if (!node.label().isBlank()) {
            return node.label();
        }
        return node.id();
    }

    public static String normalizePattern(String raw) {
        String pattern = raw == null ? "" : raw.strip();
        if (PATTERN_SUPERVISOR.equals(pattern) || PATTERN_PIPELINE.equals(pattern)) {
            return pattern;
        }
        return PATTERN_HANDOFF;
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.strip();
        if ("start".equals(type) || "agent".equals(type) || "jump".equals(type)) {
            return type;
        }
        if ("condition".equals(type)) {
            return "jump";
        }
        return "";
    }
}
