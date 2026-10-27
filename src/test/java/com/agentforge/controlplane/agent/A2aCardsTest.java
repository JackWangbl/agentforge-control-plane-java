package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.web.ApiException;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aCardsTest {

    @Test
    void editedNameAndSkillReplaceTheGeneratedCard() {
        Agent agent = new Agent();
        agent.setName("b端工单");
        agent.setDescription("处理商家问题");
        agent.setVersion("v1.0.0");
        agent.setA2aCard(A2aCards.normalize(Map.of(
                "name", "商家工单",
                "description", "只处理帖子发布失败",
                "skills", List.of(Map.of(
                        "name", "发布失败",
                        "description", "按固定话术回复",
                        "examples", List.of("我的帖子为什么无法发布"))))));
        ConfigurableAgentCard.Builder builder = new ConfigurableAgentCard.Builder()
                .url("http://127.0.0.1:8080/api/a2a/agents/22")
                .preferredTransport("JSONRPC");
        A2aCards.apply(builder, agent);
        ConfigurableAgentCard card = builder.build();
        assertEquals("商家工单", card.getName());
        assertEquals("只处理帖子发布失败", card.getDescription());
        assertEquals("http://127.0.0.1:8080/api/a2a/agents/22", card.getUrl());
        assertEquals(1, card.getSkills().size());
        assertEquals("发布失败", card.getSkills().get(0).name());
        assertEquals(List.of("我的帖子为什么无法发布"), card.getSkills().get(0).examples());
    }

    @Test
    void blankOverrideKeepsTheAgentName() {
        Agent agent = new Agent();
        agent.setName("c端工单");
        agent.setDescription("");
        agent.setVersion("v2");
        agent.setA2aCard(A2aCards.normalize(Map.of()));
        ConfigurableAgentCard.Builder builder = new ConfigurableAgentCard.Builder();
        A2aCards.apply(builder, agent);
        ConfigurableAgentCard card = builder.url("http://127.0.0.1/x").preferredTransport("JSONRPC").build();
        assertEquals("c端工单", card.getName());
        assertEquals("c端工单", card.getDescription());
        assertEquals("v2", card.getVersion());
        assertTrue(card.getSkills() == null || card.getSkills().isEmpty());
    }

    @Test
    void skillWithoutANameIsRejected() {
        ApiException error = assertThrows(ApiException.class, () -> A2aCards.normalize(Map.of(
                "skills", List.of(Map.of("description", "没有名称")))));
        assertTrue(error.getMessage().contains("名称"));
    }
}
