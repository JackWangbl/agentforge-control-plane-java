package com.agentforge.controlplane.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupervisorDispatchTest {

    private static final List<MultiAgentGraphs.Node> SUBS = List.of(
            new MultiAgentGraphs.Node("n2", "agent", "c端工单", 23L, "主站c端工单问题解决", "", ""),
            new MultiAgentGraphs.Node("n3", "agent", "b端工单", 22L, "主站b端问题解决", "", ""));

    @Test
    void categoryJsonRoutesToTheMatchingSubagent() {
        String reply = """
                ```json
                { "category": "B端", "reason": "我的帖子无法发布", "confidence": "高" }
                ```
                """;
        assertEquals("B端", SupervisorDispatch.categoryOf(reply));
        assertEquals("n3", SupervisorDispatch.match(SupervisorDispatch.categoryOf(reply), SUBS).id());
        assertEquals("n2", SupervisorDispatch.match("C端", SUBS).id());
    }

    @Test
    void uncertainOrAmbiguousCategoryStaysWithTheMainAgent() {
        assertNull(SupervisorDispatch.match("待确认", SUBS));
        assertNull(SupervisorDispatch.match("端", SUBS));
        assertNull(SupervisorDispatch.match("", SUBS));
        assertEquals("", SupervisorDispatch.categoryOf("您稍等 技术帮您查一下"));
    }
}
