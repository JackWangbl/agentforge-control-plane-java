package com.agentforge.controlplane.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRagGraphsTest {

    @Test
    void parseKeepsRicherDescriptionAndMatchesNamedEntity() {
        GraphRagGraphs.Extract first = GraphRagGraphs.parse("""
                {"entities":[{"name":"扣子","type":"产品","description":"短"}],
                 "relations":[{"source":"扣子","target":"用户","description":"提供智能体"}]}
                """);
        GraphRagGraphs.Extract second = GraphRagGraphs.parse("""
                说明 {"entities":[{"name":"扣子","type":"产品","description":"字节的智能体平台"}],"relations":[]}
                """);
        GraphRagGraphs.Extract merged = GraphRagGraphs.merge(List.of(first, second));
        assertEquals(1, merged.entities().size());
        assertEquals("字节的智能体平台", merged.entities().get(0).description());
        assertEquals(1, merged.relations().size());
        assertEquals(1, GraphRagGraphs.match("扣子怎么用", merged.entities()).size());
        assertTrue(GraphRagGraphs.match("今天天气", merged.entities()).isEmpty());
        assertTrue(GraphRagGraphs.parse("不是 json").entities().isEmpty());
    }
}
