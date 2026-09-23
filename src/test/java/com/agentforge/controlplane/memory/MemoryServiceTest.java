package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.MemoryItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @Test
    void loginAndTrialUseDifferentSubjects() {
        CurrentUser member = user(false);
        CurrentUser trial = user(true);
        trial.setTrialKey("browser-a");
        assertEquals("user:7", MemoryService.subjectKey(member));
        assertEquals("trial:browser-a", MemoryService.subjectKey(trial));
        assertFalse(MemoryService.canReadHistory(trial, null, 7L));
        assertTrue(MemoryService.canReadHistory(member, null, 7L));
        assertFalse(MemoryService.canReadHistory(member, "user:8", 7L));
        assertTrue(MemoryService.canReadHistory(trial, "trial:browser-a", 6L));
    }

    @Test
    void windowKeepsTheLatestMessagesInsideTheBudget() {
        List<Map<String, Object>> prior = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            prior.add(Map.of("role", "user", "content", "第" + i + "条" + "字".repeat(400)));
        }
        List<Map<String, Object>> window = MemoryService.applyWindow(prior);
        assertTrue(window.size() <= 20);
        assertTrue(window.stream().map(row -> String.valueOf(row.get("content")).length()).reduce(0, Integer::sum) <= 6000);
        assertTrue(String.valueOf(window.get(window.size() - 1).get("content")).startsWith("第24条"));
    }

    @Test
    void promptStopsAtTwelveOrFifteenHundredCharacters() {
        List<MemoryItem> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            MemoryItem row = new MemoryItem();
            row.setKind("preference");
            row.setContent("记".repeat(500));
            rows.add(row);
        }
        String prompt = MemoryService.formatPrompt(rows);
        assertEquals(3, prompt.split("\n").length - 2);
        assertTrue(prompt.contains("自动总结"));
        assertTrue(prompt.contains("forget"));
        assertTrue(MemoryService.formatPrompt(List.of()).contains("目前没有已保存的事实"));
    }

    @Test
    void summaryJsonBecomesOperationsAndIgnoresChatter() {
        List<Map<String, Object>> ops = MemorySummarizer.parseOps("""
                好的。
                {"items":[{"op":"add","kind":"preference","content":"回答先给结论"},{"op":"delete","id":3}]}
                """);
        assertEquals(2, ops.size());
        assertEquals("add", ops.get(0).get("op"));
        assertEquals(List.of(), MemorySummarizer.parseOps("这轮没有需要记住的内容"));
    }

    private static CurrentUser user(boolean trial) {
        CurrentUser user = new CurrentUser(7L, "linmo", "林默", 1L, 1L, "默认租户", 1L, "管理员", List.of("*"));
        if (trial) {
            user.setTrialExpiresAt(Instant.parse("2026-09-23T08:00:00Z"));
        }
        return user;
    }
}
