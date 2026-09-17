package com.agentforge.controlplane.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测子系统的请求和响应体。
 *
 * 字段名一律写成下划线风格：前端 static/app.js 直接消费 Python 版的 JSON，键名不能变。
 */
public final class EvalDtos {

    private EvalDtos() {}

    // ---------- 请求 ----------

    public record EvaluationLaunchRequest(
            @NotNull Long agent_id,
            @NotNull Long dataset_id,
            String name,
            String scorer,
            Long judge_model_id,
            List<Long> case_ids) {

        public EvaluationLaunchRequest {
            name = name == null ? "" : name;
            scorer = scorer == null ? "contains" : scorer;
            case_ids = case_ids == null ? List.of() : case_ids;
        }
    }

    public record PerformanceLaunchRequest(
            @NotNull Long agent_id,
            @NotNull Long dataset_id,
            String name,
            @Min(1) @Max(8) Integer concurrency,
            @Min(1) @Max(80) Integer requests) {

        public PerformanceLaunchRequest {
            name = name == null ? "" : name;
            concurrency = concurrency == null ? 3 : concurrency;
            requests = requests == null ? 12 : requests;
        }
    }

    public record DatasetCreateRequest(
            @NotBlank @Size(max = 120) String name,
            String description,
            String kind,
            List<Long> agent_ids) {

        public DatasetCreateRequest {
            description = description == null ? "" : description;
            kind = kind == null ? "baseline" : kind;
            agent_ids = agent_ids == null ? List.of() : agent_ids;
        }
    }

    /** 字段为 null 表示这次没传，不要覆盖原值。 */
    public record DatasetUpdateRequest(
            @Size(min = 1, max = 120) String name,
            String description,
            String kind,
            List<Long> agent_ids) {
    }

    public record DatasetCaseCreateRequest(
            @NotBlank String input,
            String expected,
            String case_key,
            List<String> tags,
            Map<String, Object> extra,
            String solution) {

        public DatasetCaseCreateRequest {
            expected = expected == null ? "" : expected;
            case_key = case_key == null ? "" : case_key;
            tags = tags == null ? List.of() : tags;
            extra = extra == null ? Map.of() : extra;
            solution = solution == null ? "" : solution;
        }
    }

    // ---------- 响应 ----------

    public record DatasetResponse(
            Long id,
            String name,
            String description,
            String source_name,
            String kind,
            String kind_label,
            List<Long> agent_ids,
            List<String> agent_names,
            boolean bound,
            int case_count,
            Instant created_at,
            Instant updated_at,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<CaseResponse> cases,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer added,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer updated,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer skipped,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Map<String, Object>> errors,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer imported) {

        public DatasetResponse(Long id, String name, String description, String source_name, String kind,
                              String kind_label, List<Long> agent_ids, List<String> agent_names, boolean bound,
                              int case_count, Instant created_at, Instant updated_at) {
            this(id, name, description, source_name, kind, kind_label, agent_ids, agent_names, bound, case_count,
                    created_at, updated_at, null, null, null, null, null, null);
        }

        public DatasetResponse withCases(List<CaseResponse> rows) {
            return new DatasetResponse(id, name, description, source_name, kind, kind_label, agent_ids, agent_names,
                    bound, case_count, created_at, updated_at, rows, added, updated, skipped, errors, imported);
        }

        public DatasetResponse withImport(int addedCount, int updatedCount, int skippedCount,
                                         List<Map<String, Object>> importErrors, int importedCount) {
            return new DatasetResponse(id, name, description, source_name, kind, kind_label, agent_ids, agent_names,
                    bound, case_count, created_at, updated_at, cases, addedCount, updatedCount, skippedCount,
                    importErrors, importedCount);
        }
    }

    public record CaseResponse(
            Long id,
            Long dataset_id,
            String case_key,
            String input,
            String expected,
            List<String> tags,
            Map<String, Object> extra,
            boolean enabled,
            Instant created_at) {
    }

    public record RunResponse(
            Long id,
            String name,
            String dataset,
            Long dataset_id,
            String agent_name,
            Long agent_id,
            Long judge_model_id,
            String judge_model_name,
            String mode,
            String scorer,
            String scorer_label,
            String status,
            double score,
            int cases,
            List<Long> case_ids,
            int total,
            int passed,
            int failed,
            int skipped,
            int avg_latency_ms,
            int total_tokens,
            Map<String, Object> metrics,
            String error_message,
            int progress,
            int judged,
            Instant started_at,
            Instant finished_at,
            Instant created_at,
            Instant updated_at,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ResultResponse> results) {

        public RunResponse withResults(List<ResultResponse> rows) {
            return new RunResponse(id, name, dataset, dataset_id, agent_name, agent_id, judge_model_id,
                    judge_model_name, mode, scorer, scorer_label, status, score, cases, case_ids, total, passed,
                    failed, skipped, avg_latency_ms, total_tokens, metrics, error_message, progress, judged,
                    started_at, finished_at, created_at, updated_at, rows);
        }
    }

    public record ResultResponse(
            Long id,
            Long run_id,
            Long case_id,
            String case_key,
            String status,
            double score,
            String input,
            String expected,
            String actual,
            String reason,
            int latency_ms,
            int tokens,
            int input_tokens,
            int output_tokens,
            String model_name,
            String model_id,
            String bound_tools,
            List<Map<String, Object>> tools,
            List<Map<String, Object>> spans,
            String trace_id,
            String session_id,
            String error,
            Instant created_at) {
    }

    public record ScoringGuideResponse(List<String> scorers, List<Map<String, Object>> guides) {
    }

    public record OkResponse(boolean ok) {
    }

    /** 按插入顺序拼一个 map，用来输出 metrics 这种键名固定的自由结构。 */
    public static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    public static <T> List<T> orEmpty(List<T> value) {
        return value == null ? new ArrayList<>() : value;
    }
}
