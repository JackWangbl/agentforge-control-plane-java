package com.agentforge.controlplane.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalTest {

    @Test
    void allMustPassFailsWhenOneGraderMisses() {
        AgentEval.TrialGrade grade = AgentEval.grade(List.of(
                Map.of("type", "contains", "expected", "三个工作日", "weight", 1),
                Map.of("type", "tool_calls", "required", List.of("get_order"), "weight", 1)
        ), "退款多久", "明天就到", List.of("get_order"), 1, 20, "all", 1, null);
        assertEquals("failed", grade.status());
        assertEquals(0.5, grade.score());
    }

    @Test
    void toolOrderDoesNotMatter() {
        List<Map<String, Object>> spans = List.of(
                Map.of("kind", "tool", "name", "mcp.lookup_user"),
                Map.of("kind", "tool", "name", "mcp.get_order"));
        AgentEval.TrialGrade grade = AgentEval.grade(List.of(
                Map.of("type", "tool_calls", "required", List.of("get_order", "lookup_user"))
        ), "查订单", "已查到", AgentEval.toolNames(spans), 1, 10, "all", 1, null);
        assertEquals("passed", grade.status());
        assertEquals(List.of("lookup_user", "get_order"), AgentEval.toolNames(spans));
    }

    @Test
    void weightedPartialCreditClearsTheThreshold() {
        AgentEval.TrialGrade pass = AgentEval.grade(List.of(
                Map.of("type", "contains", "expected", "退款", "weight", 1),
                Map.of("type", "exact", "expected", "OK", "weight", 1)
        ), "问", "可以退款", List.of(), 1, 10, "weighted", 0.5, null);
        assertEquals("passed", pass.status());
        assertEquals(0.5, pass.score());

        AgentEval.TrialGrade fail = AgentEval.grade(List.of(
                Map.of("type", "contains", "expected", "退款", "weight", 1),
                Map.of("type", "exact", "expected", "OK", "weight", 1)
        ), "问", "可以退款", List.of(), 1, 10, "weighted", 1, null);
        assertEquals("failed", fail.status());
    }

    @Test
    void unknownAssertionScoresZeroAndDoesNotPass() {
        AgentEval.TrialGrade grade = AgentEval.grade(List.of(
                Map.of("type", "llm_rubric", "assertions", List.of("先确认退款"))
        ), "要退款", "正在配送", List.of(), 1, 10, "all", 1,
                (input, actual, assertions) -> List.of(new AgentEval.AssertionVerdict("无法判断", "看不出意图")));
        assertEquals("failed", grade.status());
        assertEquals(0, grade.score());
        assertEquals("unknown", grade.graders().get(0).status());
        assertTrue(grade.reason().contains("无法判断"));
    }

    @Test
    void transcriptLimitsUseTurnsAndTokens() {
        List<Map<String, Object>> spans = List.of(
                Map.of("kind", "llm", "name", "chat"),
                Map.of("kind", "llm", "name", "chat"));
        AgentEval.TrialGrade grade = AgentEval.grade(List.of(
                Map.of("type", "transcript", "max_turns", 1, "max_tokens", 30)
        ), "问", "答", List.of(), AgentEval.modelTurns(spans), 80, "all", 1, null);
        assertEquals("failed", grade.status());
        assertTrue(grade.reason().contains("轮次 2"));
        assertTrue(grade.reason().contains("Token 80"));
    }

    @Test
    void emptyGradersFallBackToContainsUnlessExplicit() {
        List<Map<String, Object>> implied = AgentEval.effectiveGraders(List.of(), "三个工作日", false);
        assertEquals("contains", implied.get(0).get("type"));
        AgentEval.TrialGrade passed = AgentEval.grade(implied, "问", "预计三个工作日内", List.of(), 1, 1, "all", 1, null);
        assertEquals("passed", passed.status());

        assertTrue(AgentEval.effectiveGraders(List.of(), "三个工作日", true).isEmpty());
        AgentEval.TrialGrade skipped = AgentEval.grade(List.of(), "问", "任意", List.of(), 1, 1, "all", 1, null);
        assertEquals("skipped", skipped.status());
    }

    @Test
    void passAtKAndPassHatKDivergeWhenATrialFails() {
        AgentEval.SuiteScore capability = AgentEval.summarize("capability", 2, List.of(
                List.of("passed", "failed"),
                List.of("failed", "failed")
        ), 1, 3);
        assertEquals(0.5, capability.passAtK());
        assertEquals(0, capability.passHatK());
        assertEquals(50, capability.headline());

        AgentEval.SuiteScore regression = AgentEval.summarize("regression", 2, List.of(
                List.of("passed", "failed"),
                List.of("passed", "passed")
        ), 3, 1);
        assertEquals(1, regression.passAtK());
        assertEquals(0.5, regression.passHatK());
        assertEquals(50, regression.headline());
        assertTrue(regression.note().contains("pass^k"));
    }

    @Test
    void oneTrialMakesTheTwoRatesEqualAndSkipsDoNotCount() {
        AgentEval.SuiteScore score = AgentEval.summarize("", 1, List.of(
                List.of("passed"),
                List.of("skipped"),
                List.of("failed")
        ), 1, 1);
        assertEquals(2, score.tasks());
        assertEquals(0.5, score.passAtK());
        assertEquals(0.5, score.passHatK());
        assertEquals(50, score.headline());
    }

    @Test
    void normalizeRejectsTooManyAssertionsAndBadRegex() {
        assertThrows(IllegalArgumentException.class, () -> AgentEval.normalize(List.of(
                Map.of("type", "llm_rubric", "assertions", List.of("1", "2", "3", "4", "5", "6", "7")))));
        assertThrows(IllegalArgumentException.class, () -> AgentEval.normalize(List.of(
                Map.of("type", "regex", "expected", "("))));
        List<Map<String, Object>> cleaned = AgentEval.normalize(List.of(
                Map.of("type", "必调工具", "required", "get_order，lookup_user")));
        assertEquals(List.of("get_order", "lookup_user"), cleaned.get(0).get("required"));
    }

    @Test
    void rubricParserKeepsUnknown() {
        List<AgentEval.AssertionVerdict> verdicts = EvalScorers.parseRubric("""
                ```json
                [{"verdict":"pass","reason":"对齐"},{"verdict":"无法判断","reason":"证据不足"}]
                ```
                """, 2);
        assertEquals("pass", verdicts.get(0).verdict());
        assertEquals("unknown", verdicts.get(1).verdict());
        List<AgentEval.AssertionVerdict> broken = EvalScorers.parseRubric("不是 json", 1);
        assertEquals("unknown", broken.get(0).verdict());
    }
}
