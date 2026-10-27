package com.agentforge.controlplane.agent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReleasePinsTest {

    @Test
    void sameContentKeepsTheSameFingerprint() {
        Map<String, Object> left = sample("先问清工单号");
        Map<String, Object> right = sample("先问清工单号");
        assertTrue(AgentReleasePins.fingerprint(left).equals(AgentReleasePins.fingerprint(right)));
    }

    @Test
    void promptChangeIsADifferentRelease() {
        assertNotEquals(AgentReleasePins.fingerprint(sample("先问清工单号")),
                AgentReleasePins.fingerprint(sample("先问清手机号")));
    }

    @Test
    void newRequiredParameterBreaksThePinnedContract() {
        Map<String, Object> pinned = tool("ticket_id");
        Map<String, Object> live = tool("ticket_id", "phone");
        assertTrue(AgentReleasePins.breaking(pinned, live));
    }

    @Test
    void descriptionChangeDoesNotBreakTheCall() {
        Map<String, Object> pinned = tool("ticket_id");
        pinned.put("description", "旧说明");
        Map<String, Object> live = tool("ticket_id");
        live.put("description", "新说明");
        assertFalse(AgentReleasePins.breaking(pinned, live));
    }

    @Test
    void pinnedSkillTextIsWhatThePromptUses() {
        Map<String, Object> snapshot = AgentReleasePins.snapshot("你是客服", "Qwen",
                List.of(Map.of("id", 1, "name", "回复规范", "instruction", "先复述问题")),
                List.of(), List.of());
        String block = AgentReleasePins.skillBlock(snapshot);
        assertTrue(block.contains("先复述问题"));
        assertTrue(block.contains("回复规范"));
    }

    private static Map<String, Object> sample(String prompt) {
        return AgentReleasePins.snapshot(prompt, "Qwen",
                List.of(Map.of("id", 3, "name", "规范", "instruction", "用客服口吻")),
                List.of(tool("ticket_id")),
                List.of());
    }

    private static Map<String, Object> tool(String... required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : required) {
            properties.put(name, Map.of("type", "string"));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList(required));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "lookup_ticket");
        tool.put("description", "查工单");
        tool.put("parameters", parameters);
        return tool;
    }
}
