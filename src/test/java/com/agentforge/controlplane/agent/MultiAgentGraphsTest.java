package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiAgentGraphsTest {

    @Test
    void publishRequiresScenarioOnIncomingAgents() {
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "dispatch", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start", "label", "开始"),
                        Map.of("id", "a", "type", "agent", "label", "售前", "agent_id", 1, "scenario", ""),
                        Map.of("id", "b", "type", "agent", "label", "售后", "agent_id", 2, "scenario", "用户要退货")),
                "edges", List.of(
                        Map.of("source", "start", "target", "a"),
                        Map.of("source", "a", "target", "b"))));
        ApiException error = assertThrows(ApiException.class,
                () -> MultiAgentGraphs.requirePublishable(graph, id -> true));
        assertEquals(true, error.getMessage().contains("售前"));
    }

    @Test
    void publishAcceptsAStartAgentAndOneJump() {
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "dispatch", "last",
                "global_prompt", "先用简体中文",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "a", "type", "agent", "label", "售前", "agent_id", 1, "scenario", "咨询产品"),
                        Map.of("id", "j", "type", "jump", "condition", "用户说转人工")),
                "edges", List.of(
                        Map.of("source", "start", "target", "a"),
                        Map.of("source", "j", "target", "a"))));
        MultiAgentGraphs.requirePublishable(graph, id -> id == 1L);
        assertEquals("last", graph.dispatch());
        assertEquals("先用简体中文", graph.globalPrompt());
        assertEquals(MultiAgentGraphs.PATTERN_HANDOFF, graph.pattern());
    }

    @Test
    void supervisorRequiresSingleMainAndSubScenarios() {
        MultiAgentGraphs.Graph bad = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "pattern", "supervisor",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "main", "type", "agent", "label", "主控", "agent_id", 1, "scenario", "总控"),
                        Map.of("id", "sub", "type", "agent", "label", "调研", "agent_id", 2, "scenario", "")),
                "edges", List.of(
                        Map.of("source", "start", "target", "main"),
                        Map.of("source", "main", "target", "sub"))));
        ApiException error = assertThrows(ApiException.class,
                () -> MultiAgentGraphs.requirePublishable(bad, id -> true));
        assertEquals(true, error.getMessage().contains("调研"));

        MultiAgentGraphs.Graph ok = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "pattern", "supervisor",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "main", "type", "agent", "label", "主控", "agent_id", 1, "scenario", "总控"),
                        Map.of("id", "sub", "type", "agent", "label", "调研", "agent_id", 2, "scenario", "搜集资料")),
                "edges", List.of(
                        Map.of("source", "start", "target", "main"),
                        Map.of("source", "main", "target", "sub"))));
        MultiAgentGraphs.requirePublishable(ok, id -> true);
        assertEquals(MultiAgentGraphs.PATTERN_SUPERVISOR, ok.pattern());
        assertEquals("call_sub_sub", MultiAgentGraphs.subToolName(ok.node("sub")));
    }

    @Test
    void supervisorRejectsMultipleMainsFromStart() {
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "pattern", "supervisor",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "a", "type", "agent", "label", "A", "agent_id", 1, "scenario", "a"),
                        Map.of("id", "b", "type", "agent", "label", "B", "agent_id", 2, "scenario", "b")),
                "edges", List.of(
                        Map.of("source", "start", "target", "a"),
                        Map.of("source", "start", "target", "b"))));
        ApiException error = assertThrows(ApiException.class,
                () -> MultiAgentGraphs.requirePublishable(graph, id -> true));
        assertEquals(true, error.getMessage().contains("主智能体"));
    }

    @Test
    void pipelineRequiresBranchScenarios() {
        MultiAgentGraphs.Graph graph = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "pattern", "pipeline",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "a", "type", "agent", "label", "收集", "agent_id", 1, "scenario", "收集"),
                        Map.of("id", "b", "type", "agent", "label", "分析", "agent_id", 2, "scenario", ""),
                        Map.of("id", "c", "type", "agent", "label", "总结", "agent_id", 3, "scenario", "")),
                "edges", List.of(
                        Map.of("source", "start", "target", "a"),
                        Map.of("source", "a", "target", "b"),
                        Map.of("source", "a", "target", "c"))));
        ApiException error = assertThrows(ApiException.class,
                () -> MultiAgentGraphs.requirePublishable(graph, id -> true));
        assertEquals(true, error.getMessage().contains("适用场景"));

        MultiAgentGraphs.Graph linear = MultiAgentGraphs.read(Map.of(
                "mode", "multi_agent",
                "pattern", "pipeline",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "a", "type", "agent", "label", "收集", "agent_id", 1, "scenario", "收集"),
                        Map.of("id", "b", "type", "agent", "label", "分析", "agent_id", 2, "scenario", "分析")),
                "edges", List.of(
                        Map.of("source", "start", "target", "a"),
                        Map.of("source", "a", "target", "b"))));
        MultiAgentGraphs.requirePublishable(linear, id -> true);
        assertEquals(MultiAgentGraphs.PATTERN_PIPELINE, linear.pattern());
    }

    @Test
    void choiceParsingAndFallback() {
        assertEquals(1, MultiAgentGraphs.parseChoice("```json\n{\"choice\": 1}\n```"));
        assertEquals(-1, MultiAgentGraphs.parseChoice("{\"choice\":-1}"));
        assertEquals(null, MultiAgentGraphs.parseChoice("不知道"));
        assertEquals(0, MultiAgentGraphs.resolveChoice(null, 2, false, true));
        assertEquals(-1, MultiAgentGraphs.resolveChoice(null, 2, true, false));
        assertEquals(1, MultiAgentGraphs.resolveChoice(1, 2, true, false));
    }
}
