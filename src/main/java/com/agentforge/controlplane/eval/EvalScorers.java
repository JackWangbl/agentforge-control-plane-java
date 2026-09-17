package com.agentforge.controlplane.eval;

import com.agentforge.controlplane.dto.EvalDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 四种打分方式：包含 / 完全 / 正则 / LLM 裁判。对齐 Python 版 app/services/eval_scorer.py。 */
public final class EvalScorers {

    public static final List<String> SCORERS = List.of("contains", "exact", "regex", "llm");

    public static final String JUDGE_PROMPT = """
            你是评测裁判。根据「期望答案」判断「实际输出」是否达标。
            只返回 JSON，不要 markdown，不要解释：
            {"passed": true或false, "reason": "不超过40字的原因"}
            宽松原则：意思对齐即可，不必逐字相同；若期望为空，只要实际输出完整、切题就通过。""";

    private static final Map<String, Map<String, Object>> SCORER_GUIDES = guides();

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CODE_FENCE =
            Pattern.compile("^```(?:json)?\\s*|\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(45))
            .build();

    private EvalScorers() {}

    /** 打分结论。status 取 passed / failed / skipped / error。 */
    public record Judgement(String status, double score, String reason) {}

    public static EvalDtos.ScoringGuideResponse scoringGuide() {
        List<Map<String, Object>> rows = new ArrayList<>();
        SCORER_GUIDES.forEach((key, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", key);
            item.putAll(value);
            rows.add(item);
        });
        return new EvalDtos.ScoringGuideResponse(SCORERS, rows);
    }

    public static String normalizeText(String value) {
        return WHITESPACE.matcher(value == null ? "" : value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    public static Judgement scoreCase(String scorer, String expected, String actual) {
        String method = (scorer == null || scorer.isEmpty() ? "contains" : scorer).toLowerCase(Locale.ROOT);
        if (!SCORERS.contains(method)) {
            method = "contains";
        }
        String expect = expected == null ? "" : expected;
        String output = actual == null ? "" : actual;
        if (!"llm".equals(method) && expect.strip().isEmpty()) {
            return new Judgement("skipped", 0, "没有期望答案，仅记录输出");
        }
        boolean passed;
        String reason;
        switch (method) {
            case "exact" -> {
                passed = normalizeText(output).equals(normalizeText(expect));
                reason = passed ? "完全匹配：去空白且忽略大小写后一致" : "完全匹配失败：去空白且忽略大小写后仍不一致";
            }
            case "regex" -> {
                try {
                    passed = Pattern.compile(expect, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(output).find();
                } catch (PatternSyntaxException exc) {
                    return new Judgement("error", 0, "正则无效：" + exc.getDescription());
                }
                reason = passed ? "正则命中" : "正则未命中：/" + expect + "/";
            }
            case "contains" -> {
                passed = normalizeText(output).contains(normalizeText(expect));
                reason = passed ? "包含匹配：实际输出含有期望内容" : "包含匹配失败：实际输出不含期望内容";
            }
            default -> {
                return new Judgement("failed", 0, "LLM 判分需要调用 score_with_llm");
            }
        }
        return new Judgement(passed ? "passed" : "failed", passed ? 1 : 0, reason);
    }

    public static Judgement scoreWithLlm(String expected, String actual, String inputText,
                                        String modelId, String baseUrl, String apiKey) {
        String want = expected == null || expected.isEmpty() ? "（未提供，请按是否切题判断）" : expected;
        String user = "用户问题：" + (inputText == null ? "" : inputText)
                + "\n期望答案：" + want
                + "\n实际输出：" + (actual == null ? "" : actual);
        String raw = completeChat(modelId, baseUrl, apiKey, 0, JUDGE_PROMPT, user).strip();
        JsonNode parsed = parseJudge(raw);
        if (parsed == null) {
            return new Judgement("error", 0, "裁判返回无法解析：" + cut(raw, 160));
        }
        boolean passed = truthy(parsed.get("passed"));
        JsonNode reasonNode = parsed.get("reason");
        String reason = reasonNode == null || reasonNode.isNull() ? "" : reasonNode.asText("");
        if (reason.isEmpty()) {
            reason = passed ? "裁判判定通过" : "裁判判定未通过";
        }
        return new Judgement(passed ? "passed" : "failed", passed ? 1 : 0, reason);
    }

    /** 裁判可能裹着 ```json 代码块，也可能在解释文字里夹一段 JSON，两种都捞一下。 */
    private static JsonNode parseJudge(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            text = CODE_FENCE.matcher(text).replaceAll("");
        }
        JsonNode direct = readObjectWithPassed(text);
        if (direct != null) {
            return direct;
        }
        Matcher matcher = JSON_BLOCK.matcher(text);
        return matcher.find() ? readObjectWithPassed(matcher.group()) : null;
    }

    private static JsonNode readObjectWithPassed(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && node.isObject() && node.has("passed") ? node : null;
        } catch (Exception exc) {
            return null;
        }
    }

    /** 对齐 Python 的 bool()：非空字符串、非零数字都算真。 */
    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0;
        }
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        return node.size() > 0;
    }

    /** OpenAI 兼容的 chat/completions 调用，只给裁判用。 */
    private static String completeChat(String modelId, String baseUrl, String apiKey,
                                      double temperature, String systemPrompt, String userMessage) {
        String root = baseUrl == null || baseUrl.isEmpty() ? "https://api.openai.com/v1" : baseUrl;
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String key = apiKey == null ? "" : apiKey;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", normalizeChatModel(modelId, root));
        payload.put("temperature", temperature);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                            StandardCharsets.UTF_8))
                    .build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("裁判调用被中断");
        } catch (Exception exc) {
            throw new RuntimeException(exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage());
        }
        if (response.statusCode() >= 400) {
            String detail = httpErrorDetail(response);
            if (!key.isBlank()) {
                detail = detail.replace(key, "****");
            }
            throw new RuntimeException(response.statusCode() + " " + detail);
        }
        try {
            JsonNode data = MAPPER.readTree(response.body());
            return data.path("choices").path(0).path("message").path("content").asText("").strip();
        } catch (Exception exc) {
            return "";
        }
    }

    private static String normalizeChatModel(String modelId, String baseUrl) {
        String mid = modelId == null ? "" : modelId.strip();
        String key = mid.toLowerCase(Locale.ROOT);
        Map<String, String> aliases = Map.of(
                "deepseek-v4", "deepseek-v4-flash",
                "deepseek-v3", "deepseek-v4-flash",
                "deepseek-chat", "deepseek-v4-flash",
                "deepseek-reasoner", "deepseek-v4-pro");
        boolean deepseek = (baseUrl != null && baseUrl.contains("deepseek.com")) || key.startsWith("deepseek");
        return aliases.containsKey(key) && deepseek ? aliases.get(key) : mid;
    }

    private static String httpErrorDetail(HttpResponse<String> response) {
        try {
            JsonNode data = MAPPER.readTree(response.body());
            JsonNode error = data.get("error");
            if (error != null && error.isObject()) {
                JsonNode message = error.get("message");
                return message == null || message.isNull() ? error.toString() : message.asText("");
            }
            if (data.hasNonNull("message")) {
                return data.get("message").asText("");
            }
        } catch (Exception ignored) {
            // 不是 JSON 就退回原文
        }
        String body = response.body() == null ? "" : response.body();
        return cut(body.isEmpty() ? String.valueOf(response.statusCode()) : body, 500);
    }

    static String cut(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static Map<String, Map<String, Object>> guides() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        all.put("contains", guide(
                "label", "包含匹配",
                "rule", "去掉空白并忽略大小写后，只要实际输出里出现期望答案就算通过。",
                "pass_input", "退款多久到账？",
                "pass_expected", "三个工作日",
                "pass_actual", "预计三个工作日内到账。",
                "pass_why", "实际输出包含「三个工作日」",
                "fail_input", "退款多久到账？",
                "fail_expected", "三个工作日",
                "fail_actual", "明天就到。",
                "fail_why", "实际输出没有「三个工作日」",
                "use_when", "标准答案是关键词或短句，允许前后有客套话。"));
        all.put("exact", guide(
                "label", "完全匹配",
                "rule", "先去掉全部空白，再忽略大小写，然后要求实际输出和期望答案完全一致。多一个字、少一个标点都会失败。",
                "pass_input", "请只回复状态码",
                "pass_expected", "OK",
                "pass_actual", "ok",
                "pass_why", "去空白并忽略大小写后，两边都是「ok」",
                "fail_input", "请只回复状态码",
                "fail_expected", "OK",
                "fail_actual", "OK，已处理完成",
                "fail_why", "多了「已处理完成」，不是完全相等",
                "use_when", "只要固定短输出，例如状态码、枚举值、是/否。"));
        all.put("regex", guide(
                "label", "正则",
                "rule", "把期望栏当作正则表达式，在实际输出中搜索（忽略大小写，可跨行）。搜到就算通过，不必整段一模一样。",
                "pass_input", "订单 AC9001 到哪了？",
                "pass_expected", "配送中|运输中|已发货",
                "pass_actual", "订单 AC9001 正在配送中，预计明天到达。",
                "pass_why", "正则搜到了「配送中」",
                "fail_input", "订单 AC9001 到哪了？",
                "fail_expected", "配送中|运输中|已发货",
                "fail_actual", "已为您申请退款。",
                "fail_why", "三种合法状态都没出现，说明调错了能力",
                "use_when", "正确答案有几种说法，或要同时约束单号、状态等格式。"));
        all.put("llm", guide(
                "label", "LLM 判分",
                "rule", "默认用「评测裁判 · Qwen-Max」（模型 qwen-max，温度 0）阅读用户问题、期望标准和实际输出，只返回 {passed, reason}。意思对齐即可。裁判必须和被测 Agent 不是同一个模型，也不要用 Agent 自己给自己打分。",
                "recommended_model", "评测裁判 · Qwen-Max",
                "recommended_model_id", "qwen-max",
                "pass_input", "这单质量太差，我要退款。",
                "pass_expected", "先确认用户要退款，再说明下一步，不要直接查物流。",
                "pass_actual", "抱歉给您添麻烦了。请确认是否为订单 AC9001 申请退款？确认后我帮您提交。",
                "pass_why", "Qwen-Max 裁判认为意思对齐：确认退款而不是查物流",
                "fail_input", "这单质量太差，我要退款。",
                "fail_expected", "先确认用户要退款，再说明下一步，不要直接查物流。",
                "fail_actual", "您的订单正在配送中，预计明天送达。",
                "fail_why", "裁判判定未达标：当成查物流，没有处理退款意图",
                "use_when", "开放式回答、客服话术，无法用关键词或正则写死。"));
        return all;
    }

    private static Map<String, Object> guide(String... pairs) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            item.put(pairs[index], pairs[index + 1]);
        }
        return item;
    }
}
