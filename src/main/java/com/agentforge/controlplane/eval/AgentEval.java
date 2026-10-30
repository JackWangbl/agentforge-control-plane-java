package com.agentforge.controlplane.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Anthropic 智能体评测的第一版：一条用例挂多种评分器，多次试跑后汇总 pass@k 和 pass^k。
 * 代码、工具和轨迹在这里算完。模型断言由调用方提供裁判，方便单测不打网络。
 */
public final class AgentEval {

    public static final String SCORER = "agent";
    public static final int MAX_TRIALS = 3;
    public static final int MAX_GRADERS = 8;
    public static final int MAX_ASSERTIONS = 6;

    private AgentEval() {}

    /** 一条评分器的结论。unknown 记 0 分，不算通过。 */
    public record GraderOutcome(String type, String name, String status, double score, double weight, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("name", name);
            row.put("status", status);
            row.put("score", score);
            row.put("weight", weight);
            row.put("reason", reason);
            return row;
        }
    }

    public record TrialGrade(String status, double score, String reason, List<GraderOutcome> graders) {}

    public record AssertionVerdict(String verdict, String reason) {}

    /** 模型断言的裁判。返回的条数应和断言一致，缺的按无法判断。 */
    @FunctionalInterface
    public interface RubricJudge {
        List<AssertionVerdict> judge(String input, String actual, List<String> assertions);
    }

    public record SuiteScore(
            double passAtK,
            double passHatK,
            double headline,
            double trialPassRate,
            String suite,
            String note,
            int tasks,
            int tasksPassAt,
            int tasksPassHat,
            int trials) {}

    public static int requireTrials(Integer raw) {
        if (raw == null) {
            return 1;
        }
        if (raw < 1 || raw > MAX_TRIALS) {
            throw new IllegalArgumentException("每条重复次数只能是 1 到 3");
        }
        return raw;
    }

    public static String aggregation(String raw) {
        String value = raw == null ? "" : raw.strip();
        String key = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "all".equals(key) || "全部通过".equals(value)) {
            return "all";
        }
        if ("weighted".equals(key) || "加权".equals(value) || "加权部分分".equals(value)) {
            return "weighted";
        }
        throw new IllegalArgumentException("汇总方式必须是全部通过或加权部分分");
    }

    /** 全部通过时及格线固定为 1。加权未传时也按 1，避免悄悄放宽。 */
    public static double threshold(String aggregation, Double raw) {
        if (!"weighted".equals(aggregation)) {
            return 1;
        }
        if (raw == null) {
            return 1;
        }
        if (raw <= 0 || raw > 1) {
            throw new IllegalArgumentException("加权及格线必须大于 0 且不超过 1");
        }
        return raw;
    }

    /** 空字符串表示不指定。能力集看 pass@k，回归集看 pass^k。 */
    public static String suite(String raw) {
        String value = raw == null ? "" : raw.strip();
        String key = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "none".equals(key) || "不指定".equals(value)) {
            return "";
        }
        if ("capability".equals(key) || "能力集".equals(value) || "能力".equals(value)) {
            return "capability";
        }
        if ("regression".equals(key) || "回归集".equals(value) || "回归".equals(value)) {
            return "regression";
        }
        throw new IllegalArgumentException("套件用途必须是不指定、能力集或回归集");
    }

    public static boolean explicitGraders(Map<String, Object> extra) {
        if (extra == null) {
            return false;
        }
        Object flag = extra.get("graders_explicit");
        if (flag instanceof Boolean value) {
            return value;
        }
        if (flag instanceof Number value) {
            return value.intValue() != 0;
        }
        if (flag instanceof String value) {
            return "true".equalsIgnoreCase(value) || "1".equals(value);
        }
        return false;
    }

    /**
     * 没写评分器、也没声明「就这些」时，有期望答案就退回包含匹配。
     * 声明过之后，空列表就是不打分，只记录输出。
     */
    public static List<Map<String, Object>> effectiveGraders(List<Map<String, Object>> stored, String expected,
                                                            boolean explicit) {
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        if (explicit) {
            return List.of();
        }
        String text = expected == null ? "" : expected.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        Map<String, Object> implied = new LinkedHashMap<>();
        implied.put("type", "contains");
        implied.put("expected", text);
        implied.put("weight", 1);
        return List.of(implied);
    }

    public static boolean needsRubric(List<Map<String, Object>> stored, String expected, boolean explicit) {
        for (Map<String, Object> grader : effectiveGraders(stored, expected, explicit)) {
            if ("llm_rubric".equals(String.valueOf(grader.get("type")))) {
                return true;
            }
        }
        return false;
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        if (raw.size() > MAX_GRADERS) {
            throw new IllegalArgumentException("一条用例最多 " + MAX_GRADERS + " 个评分器");
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String type = canonicalType(item.get("type"));
            double weight = weightOf(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("weight", weight);
            switch (type) {
                case "contains", "exact", "regex" -> {
                    String expect = text(item.get("expected"));
                    if (expect.isEmpty()) {
                        throw new IllegalArgumentException(label(type) + "需要填写期望");
                    }
                    if ("regex".equals(type)) {
                        try {
                            Pattern.compile(expect);
                        } catch (PatternSyntaxException exc) {
                            throw new IllegalArgumentException("正则无效：" + exc.getDescription());
                        }
                    }
                    row.put("expected", expect);
                }
                case "llm_rubric" -> {
                    List<String> assertions = stringList(item.get("assertions"));
                    if (assertions.isEmpty()) {
                        throw new IllegalArgumentException("模型断言至少写一条");
                    }
                    if (assertions.size() > MAX_ASSERTIONS) {
                        throw new IllegalArgumentException("模型断言最多 " + MAX_ASSERTIONS + " 条");
                    }
                    row.put("assertions", assertions);
                }
                case "tool_calls" -> {
                    List<String> required = stringList(item.get("required"));
                    if (required.isEmpty()) {
                        throw new IllegalArgumentException("必调工具至少写一个");
                    }
                    row.put("required", required);
                }
                case "transcript" -> {
                    Integer maxTurns = positiveInt(item.get("max_turns"), "最多轮次");
                    Integer maxTokens = positiveInt(item.get("max_tokens"), "最多 Token");
                    if (maxTurns == null && maxTokens == null) {
                        throw new IllegalArgumentException("轨迹限制需要填写最多轮次或最多 Token");
                    }
                    if (maxTurns != null) {
                        row.put("max_turns", maxTurns);
                    }
                    if (maxTokens != null) {
                        row.put("max_tokens", maxTokens);
                    }
                }
                default -> throw new IllegalArgumentException("不支持的评分器：" + type);
            }
            cleaned.add(row);
        }
        return cleaned;
    }

    public static TrialGrade grade(List<Map<String, Object>> graders, String input, String actual,
                                   List<String> toolNames, int turns, int tokens,
                                   String aggregation, double passThreshold, RubricJudge judge) {
        if (graders == null || graders.isEmpty()) {
            return new TrialGrade("skipped", 0, "没有评分器，仅记录输出", List.of());
        }
        String how = "weighted".equals(aggregation) ? "weighted" : "all";
        List<GraderOutcome> outcomes = new ArrayList<>();
        for (Map<String, Object> grader : graders) {
            outcomes.add(gradeOne(grader, input, actual, toolNames, turns, tokens, judge));
        }
        double weightSum = 0;
        double earned = 0;
        boolean allPass = true;
        boolean anyFail = false;
        boolean anyError = false;
        List<String> lines = new ArrayList<>();
        for (GraderOutcome outcome : outcomes) {
            weightSum += outcome.weight();
            if ("passed".equals(outcome.status())) {
                earned += outcome.weight();
            } else {
                allPass = false;
                if ("error".equals(outcome.status())) {
                    anyError = true;
                } else {
                    anyFail = true;
                }
            }
            lines.add(outcome.name() + outcomeWord(outcome.status()) + "：" + outcome.reason());
        }
        double score = weightSum == 0 ? 0 : round3(earned / weightSum);
        String status;
        if ("weighted".equals(how)) {
            if (anyError) {
                status = "error";
            } else {
                status = score + 1e-9 >= passThreshold ? "passed" : "failed";
            }
        } else if (allPass) {
            status = "passed";
        } else if (anyFail) {
            status = "failed";
        } else if (anyError) {
            status = "error";
        } else {
            status = "failed";
        }
        String reason = String.join("；", lines);
        if ("weighted".equals(how)) {
            reason = "加权 " + score + " / 及格线 " + round3(passThreshold) + "。" + reason;
        }
        return new TrialGrade(status, score, reason, outcomes);
    }

    public static SuiteScore summarize(String suite, int trials, List<List<String>> taskStatuses,
                                       int passedTrials, int failedTrials) {
        int k = Math.max(1, trials);
        String kind = "regression".equals(suite) ? "regression" : ("capability".equals(suite) ? "capability" : "");
        int tasks = 0;
        int passAt = 0;
        int passHat = 0;
        if (taskStatuses != null) {
            for (List<String> statuses : taskStatuses) {
                if (statuses == null || statuses.isEmpty()) {
                    continue;
                }
                boolean decided = false;
                int successes = 0;
                int bad = 0;
                for (String status : statuses) {
                    if ("passed".equals(status)) {
                        decided = true;
                        successes++;
                    } else if ("failed".equals(status) || "error".equals(status)) {
                        decided = true;
                        bad++;
                    }
                }
                if (!decided) {
                    continue;
                }
                tasks++;
                if (successes > 0) {
                    passAt++;
                }
                if (successes >= k && bad == 0) {
                    passHat++;
                }
            }
        }
        double at = tasks == 0 ? 0 : round3(passAt * 1.0 / tasks);
        double hat = tasks == 0 ? 0 : round3(passHat * 1.0 / tasks);
        double headline = round1(("regression".equals(kind) ? hat : at) * 100);
        double trialRate = passedTrials + failedTrials == 0
                ? 0
                : round1(passedTrials * 100.0 / (passedTrials + failedTrials));
        String note = switch (kind) {
            case "regression" -> "回归集看 pass^k：同一条要连续 " + k + " 次都通过，应当接近 100%。";
            case "capability" -> "能力集看 pass@k：同一条 " + k + " 次里至少通过一次，通过率可以偏低。";
            default -> "未指定套件用途，头条分数用 pass@k（至少一次通过）。";
        };
        return new SuiteScore(at, hat, headline, trialRate, kind, note, tasks, passAt, passHat, k);
    }

    public static List<String> toolNames(List<Map<String, Object>> spans) {
        List<String> names = new ArrayList<>();
        if (spans == null) {
            return names;
        }
        for (Map<String, Object> span : spans) {
            if (span == null || !"tool".equals(String.valueOf(span.get("kind")))) {
                continue;
            }
            String name = text(span.get("name"));
            if (name.startsWith("mcp.")) {
                name = name.substring(4);
            }
            if (name.isEmpty()) {
                name = text(span.get("title")).replaceFirst("^调用工具\\s*", "");
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    public static int modelTurns(List<Map<String, Object>> spans) {
        if (spans == null) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> span : spans) {
            if (span != null && "llm".equals(String.valueOf(span.get("kind")))) {
                count++;
            }
        }
        return count;
    }

    private static GraderOutcome gradeOne(Map<String, Object> grader, String input, String actual,
                                          List<String> toolNames, int turns, int tokens, RubricJudge judge) {
        String type = String.valueOf(grader.get("type"));
        double weight = weightOf(grader);
        String name = label(type);
        try {
            return switch (type) {
                case "contains", "exact", "regex" -> fromCode(type, name, weight,
                        EvalScorers.scoreCase(type, text(grader.get("expected")), actual));
                case "llm_rubric" -> gradeRubric(name, weight, input, actual, stringList(grader.get("assertions")), judge);
                case "tool_calls" -> gradeTools(name, weight, stringList(grader.get("required")), toolNames);
                case "transcript" -> gradeTranscript(name, weight, grader, turns, tokens);
                default -> new GraderOutcome(type, name, "error", 0, weight, "不支持的评分器");
            };
        } catch (RuntimeException exc) {
            String message = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
            return new GraderOutcome(type, name, "error", 0, weight, message);
        }
    }

    private static GraderOutcome fromCode(String type, String name, double weight, EvalScorers.Judgement judgement) {
        String status = judgement.status();
        double score = "passed".equals(status) ? 1 : 0;
        return new GraderOutcome(type, name, status, score, weight, judgement.reason());
    }

    private static GraderOutcome gradeRubric(String name, double weight, String input, String actual,
                                             List<String> assertions, RubricJudge judge) {
        if (assertions.isEmpty()) {
            return new GraderOutcome("llm_rubric", name, "error", 0, weight, "模型断言至少写一条");
        }
        if (judge == null) {
            return new GraderOutcome("llm_rubric", name, "error", 0, weight, "模型断言需要裁判模型");
        }
        List<AssertionVerdict> verdicts = judge.judge(input, actual, assertions);
        boolean allPass = true;
        boolean anyFail = false;
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < assertions.size(); index++) {
            AssertionVerdict verdict = verdicts != null && index < verdicts.size() ? verdicts.get(index) : null;
            String kind = verdict == null ? "unknown" : normalizeVerdict(verdict.verdict());
            String why = verdict == null || verdict.reason() == null || verdict.reason().isBlank()
                    ? verdictWord(kind) : verdict.reason().strip();
            if ("fail".equals(kind)) {
                allPass = false;
                anyFail = true;
            } else if (!"pass".equals(kind)) {
                allPass = false;
            }
            lines.add((index + 1) + ". " + verdictWord(kind) + " " + why);
        }
        String status = allPass ? "passed" : (anyFail ? "failed" : "unknown");
        return new GraderOutcome("llm_rubric", name, status, allPass ? 1 : 0, weight, String.join(" ", lines));
    }

    private static GraderOutcome gradeTools(String name, double weight, List<String> required, List<String> called) {
        List<String> seen = called == null ? List.of() : called;
        List<String> missing = new ArrayList<>();
        for (String tool : required) {
            if (!toolCalled(seen, tool)) {
                missing.add(tool);
            }
        }
        if (missing.isEmpty()) {
            return new GraderOutcome("tool_calls", name, "passed", 1, weight,
                    "已调用：" + String.join("、", seen.isEmpty() ? required : seen));
        }
        return new GraderOutcome("tool_calls", name, "failed", 0, weight,
                "缺少工具：" + String.join("、", missing));
    }

    private static GraderOutcome gradeTranscript(String name, double weight, Map<String, Object> grader,
                                                 int turns, int tokens) {
        Integer maxTurns = positiveInt(grader.get("max_turns"), "最多轮次");
        Integer maxTokens = positiveInt(grader.get("max_tokens"), "最多 Token");
        List<String> problems = new ArrayList<>();
        if (maxTurns != null && turns > maxTurns) {
            problems.add("轮次 " + turns + " 超过 " + maxTurns);
        }
        if (maxTokens != null && tokens > maxTokens) {
            problems.add("Token " + tokens + " 超过 " + maxTokens);
        }
        if (problems.isEmpty()) {
            return new GraderOutcome("transcript", name, "passed", 1, weight,
                    "轮次 " + turns + (maxTokens == null ? "" : "，Token " + tokens));
        }
        return new GraderOutcome("transcript", name, "failed", 0, weight, String.join("；", problems));
    }

    private static boolean toolCalled(List<String> called, String required) {
        String want = required.strip().toLowerCase(Locale.ROOT);
        for (String name : called) {
            String got = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
            if (got.equals(want) || got.endsWith("." + want) || want.endsWith("." + got)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeVerdict(String raw) {
        String text = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (text.equals("pass") || text.equals("passed") || text.equals("true") || text.equals("通过")) {
            return "pass";
        }
        if (text.equals("unknown") || text.equals("无法判断") || text.equals("uncertain") || text.equals("n/a")
                || text.isEmpty()) {
            return "unknown";
        }
        return "fail";
    }

    private static String canonicalType(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).strip();
        String key = value.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "contains", "包含", "包含匹配" -> "contains";
            case "exact", "完全", "完全匹配" -> "exact";
            case "regex", "正则" -> "regex";
            case "llm_rubric", "llm", "rubric", "模型断言" -> "llm_rubric";
            case "tool_calls", "tools", "工具", "必调工具" -> "tool_calls";
            case "transcript", "轨迹", "轨迹限制" -> "transcript";
            default -> value.isEmpty() ? "" : key;
        };
    }

    private static String label(String type) {
        return switch (type) {
            case "contains" -> "包含匹配";
            case "exact" -> "完全匹配";
            case "regex" -> "正则";
            case "llm_rubric" -> "模型断言";
            case "tool_calls" -> "必调工具";
            case "transcript" -> "轨迹限制";
            default -> type;
        };
    }

    private static String outcomeWord(String status) {
        return switch (status) {
            case "passed" -> "通过";
            case "unknown" -> "无法判断";
            case "error" -> "出错";
            case "skipped" -> "跳过";
            default -> "未过";
        };
    }

    private static String verdictWord(String verdict) {
        return switch (verdict) {
            case "pass" -> "通过";
            case "unknown" -> "无法判断";
            default -> "未过";
        };
    }

    private static double weightOf(Map<String, Object> raw) {
        Object value = raw.get("weight");
        if (value == null || (value instanceof String text && text.isBlank())) {
            return 1;
        }
        double weight;
        if (value instanceof Number number) {
            weight = number.doubleValue();
        } else {
            try {
                weight = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException exc) {
                throw new IllegalArgumentException("评分器权重必须是数字");
            }
        }
        if (weight <= 0 || weight > 100) {
            throw new IllegalArgumentException("评分器权重必须大于 0 且不超过 100");
        }
        return weight;
    }

    private static Integer positiveInt(Object value, String label) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        int number;
        if (value instanceof Number raw) {
            number = raw.intValue();
        } else {
            try {
                number = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException exc) {
                throw new IllegalArgumentException(label + "必须是正整数");
            }
        }
        if (number <= 0) {
            return null;
        }
        return number;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = text(item);
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return out;
        }
        if (value instanceof String raw) {
            for (String part : raw.split("[,，\\n]")) {
                String text = part.strip();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
