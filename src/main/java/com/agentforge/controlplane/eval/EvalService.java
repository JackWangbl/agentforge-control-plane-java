package com.agentforge.controlplane.eval;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.agent.ChatReply;
import com.agentforge.controlplane.agent.ChatTurnResult;
import com.agentforge.controlplane.agent.ChatTurnRunner;
import com.agentforge.controlplane.agent.HttpAgentRuntime;
import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Dataset;
import com.agentforge.controlplane.domain.DatasetCase;
import com.agentforge.controlplane.domain.EvaluationResult;
import com.agentforge.controlplane.domain.EvaluationRun;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.dto.EvalDtos;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.DatasetCaseRepository;
import com.agentforge.controlplane.repo.EvaluationResultRepository;
import com.agentforge.controlplane.repo.EvaluationRunRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.TraceRepository;
import com.agentforge.controlplane.runtime.ExecutionContext;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EvalService {

    public static final int ONLINE_CASE_LIMIT = 10;
    public static final int PERF_INLINE_LIMIT = 16;
    private static final Map<String, String> KIND_LABELS = Map.of(
            "redteam", "安全红队集", "baseline", "能力基线", "golden", "黄金集");
    private static final Map<String, String> KIND_ALIASES = Map.ofEntries(
            Map.entry("安全红队集", "redteam"), Map.entry("红队", "redteam"), Map.entry("red-team", "redteam"),
            Map.entry("redteam", "redteam"), Map.entry("能力基线", "baseline"), Map.entry("能力基线集", "baseline"),
            Map.entry("基线", "baseline"), Map.entry("baseline", "baseline"), Map.entry("黄金集", "golden"),
            Map.entry("golden-set", "golden"), Map.entry("golden", "golden"));
    private static final Map<String, String> SCORER_LABELS = Map.of(
            "contains", "包含匹配", "exact", "完全匹配", "regex", "正则", "llm", "LLM 判分",
            "agent", "Anthropic 评测", "perf", "性能");
    private static final Map<String, String> SUITE_LABELS = Map.of(
            "capability", "能力集", "regression", "回归集");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final EvaluationRunRepository runs;
    private final EvaluationResultRepository results;
    private final DatasetCaseRepository cases;
    private final AgentRepository agents;
    private final ModelConfigRepository models;
    private final TraceRepository traces;
    private final ChatTurnRunner chat;
    private final HttpAgentRuntime httpAgents;
    private final ResourceAccessService access;
    private final AppSettings settings;

    public EvalService(EvaluationRunRepository runs, EvaluationResultRepository results,
                       DatasetCaseRepository cases, AgentRepository agents, ModelConfigRepository models,
                       TraceRepository traces, ChatTurnRunner chat, HttpAgentRuntime httpAgents,
                       ResourceAccessService access, AppSettings settings) {
        this.runs = runs;
        this.results = results;
        this.cases = cases;
        this.agents = agents;
        this.models = models;
        this.traces = traces;
        this.chat = chat;
        this.httpAgents = httpAgents;
        this.access = access;
        this.settings = settings;
    }

    public EvalDtos.DatasetResponse dumpDataset(Dataset row, CurrentUser user) {
        List<String> names = new ArrayList<>();
        List<Long> ids = row.getAgentIds() == null ? List.of() : row.getAgentIds();
        for (Long id : ids) {
            agents.findById(id).ifPresent(agent -> names.add(agent.getName()));
        }
        String kind = KIND_LABELS.containsKey(row.getKind()) ? row.getKind() : "baseline";
        String suite = SUITE_LABELS.containsKey(row.getSuite()) ? row.getSuite() : "";
        return new EvalDtos.DatasetResponse(
                row.getId(), row.getName(), row.getDescription(), row.getSourceName(), kind,
                KIND_LABELS.get(kind), suite, SUITE_LABELS.getOrDefault(suite, ""), ids, names, !ids.isEmpty(),
                row.getCaseCount(), row.getCreatedAt(), row.getUpdatedAt());
    }

    public EvalDtos.RunResponse dumpRun(EvaluationRun row) {
        String judgeName = "";
        if (row.getJudgeModelId() != null) {
            judgeName = models.findById(row.getJudgeModelId()).map(ModelConfig::getName).orElse("");
        } else if ("llm".equals(row.getScorer())) {
            judgeName = "评测裁判 · Qwen-Max";
        }
        int judged = row.getPassed() + row.getFailed() + row.getSkipped();
        int progress = row.getTotal() <= 0 ? 0 : Math.min(100, judged * 100 / row.getTotal());
        return new EvalDtos.RunResponse(
                row.getId(), row.getName(), row.getDataset(), row.getDatasetId(), row.getAgentName(), row.getAgentId(),
                row.getJudgeModelId(), judgeName, row.getMode(), row.getScorer(),
                SCORER_LABELS.getOrDefault(row.getScorer(), row.getScorer()),
                row.getStatus(), row.getScore(), row.getCases(), row.getCaseIds(), row.getTotal(), row.getPassed(),
                row.getFailed(), row.getSkipped(), row.getAvgLatencyMs(), row.getTotalTokens(),
                row.getMetrics() == null ? Map.of() : row.getMetrics(),
                row.getErrorMessage(), progress, judged, row.getStartedAt(), row.getFinishedAt(),
                row.getCreatedAt(), row.getUpdatedAt(), null);
    }

    public EvalDtos.RunResponse loadRun(Long runId) {
        EvaluationRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("资源不存在"));
        List<EvaluationResult> rows = results.findByRunIdOrderByIdAsc(runId);
        Map<String, Trace> byId = tracesFor(rows);
        List<EvalDtos.ResultResponse> dumped = rows.stream()
                .map(item -> dumpResult(item, byId.get(item.getTraceId())))
                .toList();
        return dumpRun(run).withResults(dumped);
    }

    public EvalDtos.CaseResponse dumpCase(DatasetCase row) {
        return new EvalDtos.CaseResponse(row.getId(), row.getDatasetId(), row.getCaseKey(), row.getInput(),
                row.getExpected(), row.getTags(), row.getExtra(), row.isEnabled(), row.getCreatedAt(),
                row.getGraders() == null ? List.of() : row.getGraders());
    }

    public EvalDtos.ResultResponse dumpResult(EvaluationResult row) {
        return dumpResult(row, row.getTraceId().isBlank() ? null : traces.findByTraceId(row.getTraceId()).orElse(null));
    }

    public EvalDtos.ResultResponse dumpResult(EvaluationResult row, Trace trace) {
        List<Map<String, Object>> spans = trace == null || trace.getSpans() == null ? List.of() : trace.getSpans();
        List<Map<String, Object>> toolSpans = spans.stream()
                .filter(item -> "tool".equals(item.get("kind"))).toList();
        Map<String, Object> bound = spans.stream()
                .filter(item -> "mcp.bind".equals(item.get("name"))).findFirst().orElse(Map.of());
        Map<String, Object> model = spans.stream()
                .filter(item -> "llm".equals(item.get("kind"))).findFirst().orElse(Map.of());
        String modelTitle = String.valueOf(model.getOrDefault("title", "")).replace("调用模型 · ", "").strip();
        return new EvalDtos.ResultResponse(row.getId(), row.getRunId(), row.getCaseId(), row.getCaseKey(),
                row.getStatus(), row.getScore(), row.getInput(), row.getExpected(), row.getActual(), row.getReason(),
                row.getLatencyMs() > 0 ? row.getLatencyMs() : (trace == null ? 0 : trace.getDurationMs()),
                row.getTokens(),
                trace == null ? 0 : trace.getInputTokens(),
                trace == null ? 0 : trace.getOutputTokens(),
                modelTitle,
                String.valueOf(model.getOrDefault("detail", "")),
                String.valueOf(bound.getOrDefault("detail", "")),
                toolSpans, spans,
                row.getTraceId(), row.getSessionId(), row.getError(), row.getCreatedAt(),
                row.getTrialIndex(),
                row.getGraderResults() == null ? List.of() : row.getGraderResults());
    }

    @Transactional
    public EvaluationRun prepareRun(CurrentUser user, Long agentId, Long datasetId, String name, String scorer,
                                    Long judgeModelId, List<Long> caseIds, String mode, Integer trials,
                                    String aggregation, Double passThreshold) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, agentId);
        Dataset dataset = access.getRow(user, ResourceKind.DATASET, datasetId);
        assertDatasetVisible(dataset, agent, user);
        String method = scorer == null || scorer.isBlank() ? "contains" : scorer.toLowerCase();
        boolean agentMode = AgentEval.SCORER.equals(method);
        if (!agentMode && !EvalScorers.SCORERS.contains(method)) {
            throw ApiException.badRequest("不支持的打分方式");
        }
        int trialCount = 1;
        String how = "all";
        double threshold = 1;
        if (agentMode) {
            try {
                trialCount = AgentEval.requireTrials(trials);
                how = AgentEval.aggregation(aggregation);
                threshold = AgentEval.threshold(how, passThreshold);
            } catch (IllegalArgumentException exc) {
                throw ApiException.badRequest(exc.getMessage());
            }
        }
        List<DatasetCase> selected = resolveCases(datasetId, caseIds);
        if (selected.isEmpty()) {
            throw ApiException.badRequest("没有可测试的用例");
        }
        int attempts = selected.size() * trialCount;
        if ("online".equals(mode) && attempts > ONLINE_CASE_LIMIT) {
            throw ApiException.badRequest("在线测试最多 " + ONLINE_CASE_LIMIT + " 次试跑（用例数 × 重复次数），请改走离线或减少勾选");
        }
        boolean needsJudge = "llm".equals(method) || (agentMode && selected.stream().anyMatch(this::caseNeedsRubric));
        Long judgeId = judgeModelId;
        if (needsJudge) {
            ModelConfig judge = resolveJudge(judgeModelId);
            if (judge == null) {
                throw ApiException.badRequest("LLM 判分需要裁判模型。请在模型配置中启用「评测裁判 · Qwen-Max」并填写密钥。");
            }
            judgeId = judge.getId();
        }
        EvaluationRun run = new EvaluationRun();
        run.setName(name == null || name.isBlank()
                ? agent.getName() + " · " + dataset.getName() + " · " + CLOCK.format(Instant.now().atOffset(ZoneOffset.UTC))
                : name.strip());
        run.setDataset(dataset.getName());
        run.setDatasetId(dataset.getId());
        run.setAgentName(agent.getName());
        run.setAgentId(agent.getId());
        run.setJudgeModelId(judgeId);
        run.setMode(mode == null ? "offline" : mode);
        run.setScorer(method);
        run.setTrials(trialCount);
        run.setAggregation(how);
        run.setPassThreshold(threshold);
        run.setStatus("queued");
        run.setCases(selected.size());
        run.setTotal(attempts);
        run.setCaseIds(selected.stream().map(DatasetCase::getId).toList());
        if (agentMode) {
            String suite = SUITE_LABELS.containsKey(dataset.getSuite()) ? dataset.getSuite() : "";
            run.setMetrics(EvalDtos.ordered(
                    "suite", suite,
                    "trials", trialCount,
                    "aggregation", how,
                    "pass_threshold", threshold));
        }
        access.stampOwner(run, user);
        return runs.save(run);
    }

    @Transactional
    public EvaluationRun preparePerf(CurrentUser user, Long agentId, Long datasetId, String name,
                                     int concurrency, int requests) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, agentId);
        Dataset dataset = access.getRow(user, ResourceKind.DATASET, datasetId);
        assertDatasetVisible(dataset, agent, user);
        List<DatasetCase> selected = resolveCases(datasetId, List.of());
        if (selected.isEmpty()) {
            throw ApiException.badRequest("没有可压测的用例");
        }
        EvaluationRun run = new EvaluationRun();
        run.setName(name == null || name.isBlank()
                ? agent.getName() + " · 性能测试 · " + CLOCK.format(Instant.now().atOffset(ZoneOffset.UTC))
                : name.strip());
        run.setDataset(dataset.getName());
        run.setDatasetId(dataset.getId());
        run.setAgentName(agent.getName());
        run.setAgentId(agent.getId());
        run.setMode("performance");
        run.setScorer("perf");
        run.setStatus("queued");
        run.setCaseIds(selected.stream().map(DatasetCase::getId).toList());
        run.setCases(requests);
        run.setTotal(requests);
        run.setMetrics(EvalDtos.ordered("concurrency", concurrency, "requests", requests));
        access.stampOwner(run, user);
        return runs.save(run);
    }

    @Scheduled(fixedDelay = 1200)
    public void workerTick() {
        if (!settings.isEvalWorker()) {
            return;
        }
        Long id = claim();
        if (id != null) {
            try {
                EvaluationRun run = runs.findById(id).orElse(null);
                if (run != null && "performance".equals(run.getMode())) {
                    executePerfRun(id);
                } else {
                    executeRun(id);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Transactional
    public Long claim() {
        Optional<EvaluationRun> found = runs.findFirstByStatusAndModeInOrderByIdAsc("queued", List.of("offline", "performance"));
        if (found.isEmpty()) {
            return null;
        }
        EvaluationRun row = found.get();
        row.setStatus("running");
        row.setStartedAt(Instant.now());
        runs.save(row);
        return row.getId();
    }

    public void executeRun(Long runId) {
        EvaluationRun run = runs.findById(runId).orElse(null);
        if (run == null || "cancelled".equals(run.getStatus())) {
            return;
        }
        run.setStatus("running");
        if (run.getStartedAt() == null) {
            run.setStartedAt(Instant.now());
        }
        runs.save(run);
        Agent agent = agents.findById(run.getAgentId()).orElse(null);
        ModelConfig model = resolveAgentModel(agent);
        List<DatasetCase> selected = resolveCases(run.getDatasetId(), run.getCaseIds());
        boolean agentMode = AgentEval.SCORER.equals(run.getScorer());
        int trialCount = agentMode ? run.getTrials() : 1;
        Map<String, EvaluationResult> kept = new LinkedHashMap<>();
        for (EvaluationResult existing : results.findByRunIdOrderByIdAsc(runId)) {
            if ("passed".equals(existing.getStatus())) {
                kept.put(existing.getCaseId() + ":" + existing.getTrialIndex(), existing);
            }
        }
        int passed = kept.size();
        int failed = 0;
        int skipped = 0;
        int tokens = 0;
        int latencySum = 0;
        try {
            for (DatasetCase item : selected) {
                for (int trial = 1; trial <= trialCount; trial++) {
                    EvaluationRun latest = runs.findById(runId).orElse(run);
                    if ("cancelled".equals(latest.getStatus())) {
                        return;
                    }
                    String key = item.getId() + ":" + trial;
                    if (kept.containsKey(key)) {
                        EvaluationResult existing = kept.get(key);
                        tokens += existing.getTokens();
                        latencySum += existing.getLatencyMs();
                    } else {
                        EvaluationResult result = runOne(latest, agent, model, item, trial);
                        if (agentMode && trialCount > 1 && !result.getReason().isBlank()) {
                            result.setReason("第 " + trial + " 次：" + result.getReason());
                        }
                        results.save(result);
                        switch (result.getStatus()) {
                            case "passed" -> passed++;
                            case "skipped" -> skipped++;
                            default -> failed++;
                        }
                        tokens += result.getTokens();
                        latencySum += result.getLatencyMs();
                    }
                    int judged = passed + failed + skipped;
                    latest.setPassed(passed);
                    latest.setFailed(failed);
                    latest.setSkipped(skipped);
                    latest.setTotalTokens(tokens);
                    latest.setAvgLatencyMs(judged == 0 ? 0 : latencySum / judged);
                    if (agentMode) {
                        applyAgentHeadline(latest, passed, failed);
                    } else {
                        latest.setScore(passed + failed == 0 ? 0 : Math.round(passed * 1000.0 / (passed + failed)) / 10.0);
                    }
                    runs.save(latest);
                }
            }
            EvaluationRun done = runs.findById(runId).orElse(run);
            if (agentMode) {
                applyAgentHeadline(done, passed, failed);
            }
            done.setStatus("completed");
            done.setFinishedAt(Instant.now());
            runs.save(done);
        } catch (Exception e) {
            EvaluationRun failedRun = runs.findById(runId).orElse(run);
            failedRun.setStatus("failed");
            failedRun.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            failedRun.setFinishedAt(Instant.now());
            runs.save(failedRun);
        }
    }

    public void executePerfRun(Long runId) {
        EvaluationRun run = runs.findById(runId).orElse(null);
        if (run == null || "cancelled".equals(run.getStatus())) {
            return;
        }
        run.setStatus("running");
        if (run.getStartedAt() == null) {
            run.setStartedAt(Instant.now());
        }
        runs.save(run);
        Agent agent = agents.findById(run.getAgentId()).orElse(null);
        ModelConfig model = resolveAgentModel(agent);
        List<DatasetCase> selected = resolveCases(run.getDatasetId(), run.getCaseIds());
        int total = Math.max(1, run.getTotal());
        int tokens = 0;
        int latencySum = 0;
        int errors = 0;
        try {
            for (int i = 0; i < total; i++) {
                EvaluationRun latest = runs.findById(runId).orElse(run);
                if ("cancelled".equals(latest.getStatus())) {
                    return;
                }
                DatasetCase item = selected.get(i % selected.size());
                EvaluationResult result = runOne(latest, agent, model, item, 1);
                results.save(result);
                tokens += result.getTokens();
                latencySum += result.getLatencyMs();
                if ("error".equals(result.getStatus())) {
                    errors++;
                }
                latest.setPassed(i + 1 - errors);
                latest.setFailed(errors);
                latest.setTotalTokens(tokens);
                latest.setAvgLatencyMs((i + 1) == 0 ? 0 : latencySum / (i + 1));
                Map<String, Object> metrics = latest.getMetrics() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(latest.getMetrics());
                metrics.put("error_rate", Math.round(errors * 1000.0 / (i + 1)) / 10.0);
                metrics.put("rps", latest.getStartedAt() == null ? 0
                        : Math.round((i + 1) * 1000.0 / Math.max(1, Instant.now().toEpochMilli() - latest.getStartedAt().toEpochMilli()) * 10.0) / 10.0);
                latest.setMetrics(metrics);
                runs.save(latest);
            }
            EvaluationRun done = runs.findById(runId).orElse(run);
            done.setStatus("completed");
            done.setFinishedAt(Instant.now());
            done.setScore(Math.max(0, 100 - errors * 100.0 / total));
            runs.save(done);
        } catch (Exception e) {
            EvaluationRun failedRun = runs.findById(runId).orElse(run);
            failedRun.setStatus("failed");
            failedRun.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            failedRun.setFinishedAt(Instant.now());
            runs.save(failedRun);
        }
    }

    private EvaluationResult runOne(EvaluationRun run, Agent agent, ModelConfig model, DatasetCase item, int trial) {
        EvaluationResult row = new EvaluationResult();
        row.setRunId(run.getId());
        row.setCaseId(item.getId());
        row.setTrialIndex(trial);
        row.setCaseKey(item.getCaseKey());
        row.setInput(item.getInput());
        row.setExpected(item.getExpected());
        row.setTenantId(run.getTenantId());
        row.setOwnerId(run.getOwnerId());
        if (agent == null || (model == null && (httpAgents == null || !httpAgents.isHttpBacked(agent)))) {
            row.setStatus("error");
            row.setError("找不到 Agent 或模型");
            return row;
        }
        String sessionId = "eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long started = System.nanoTime();
        if (httpAgents.isHttpBacked(agent)) {
            try {
                ChatReply reply = httpAgents.chat(agent, item.getInput(), sessionId);
                row.setLatencyMs(Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)));
                row.setActual(reply.reply());
                row.setTraceId(reply.traceId() == null ? "" : reply.traceId());
                row.setSessionId(sessionId);
                row.setTokens(reply.totalTokens());
                if ("error".equals(reply.mode())) {
                    row.setStatus("error");
                    row.setError(reply.reply());
                    return row;
                }
                return scoreReply(run, item, row, reply.reply(), model);
            } catch (Exception e) {
                row.setStatus("error");
                row.setError(e.getMessage());
                row.setLatencyMs(Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)));
                return row;
            }
        }
        ChatTurnResult turn;
        try {
            ExecutionContext.set(ExecutionContext.forAgent(agent, sessionId));
            turn = chat.run(agent, model, item.getInput(), sessionId);
        } catch (Exception e) {
            row.setStatus("error");
            row.setError(e.getMessage());
            row.setLatencyMs(Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)));
            return row;
        } finally {
            ExecutionContext.clear();
        }
        row.setLatencyMs(Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)));
        row.setActual(turn.reply());
        row.setTraceId(turn.traceId() == null ? "" : turn.traceId());
        row.setSessionId(sessionId);
        row.setTokens(turn.totalTokens());
        if ("error".equals(turn.mode())) {
            row.setStatus("error");
            row.setError(turn.error());
            return row;
        }
        return scoreReply(run, item, row, turn.reply(), model);
    }

    private EvaluationResult scoreReply(EvaluationRun run, DatasetCase item, EvaluationResult row,
                                        String actual, ModelConfig model) {
        if ("perf".equals(run.getScorer())) {
            row.setStatus("passed");
            row.setScore(1);
            row.setReason("性能测试只记录延迟");
            return row;
        }
        if (AgentEval.SCORER.equals(run.getScorer())) {
            return scoreAgent(run, item, row, actual);
        }
        EvalScorers.Judgement judgement;
        if ("llm".equals(run.getScorer())) {
            ModelConfig judge = run.getJudgeModelId() == null ? model : models.findById(run.getJudgeModelId()).orElse(model);
            if (judge == null) {
                row.setStatus("error");
                row.setError("LLM 判分需要可用的裁判模型");
                return row;
            }
            judgement = EvalScorers.scoreWithLlm(item.getExpected(), actual, item.getInput(),
                    judge.getModelId(), AgentScopeRuntime.modelEndpoint(judge), AgentScopeRuntime.resolveCredential(judge));
        } else {
            judgement = EvalScorers.scoreCase(run.getScorer(), item.getExpected(), actual);
        }
        row.setStatus(judgement.status());
        row.setScore(judgement.score());
        row.setReason(judgement.reason());
        return row;
    }

    private EvaluationResult scoreAgent(EvaluationRun run, DatasetCase item, EvaluationResult row, String actual) {
        boolean explicit = AgentEval.explicitGraders(item.getExtra());
        List<Map<String, Object>> graders = AgentEval.effectiveGraders(item.getGraders(), item.getExpected(), explicit);
        Trace trace = row.getTraceId().isBlank() ? null : traces.findByTraceId(row.getTraceId()).orElse(null);
        List<Map<String, Object>> spans = trace == null || trace.getSpans() == null ? List.of() : trace.getSpans();
        int turns = AgentEval.modelTurns(spans);
        if (turns <= 0) {
            turns = 1;
        }
        int tokens = row.getTokens();
        if (tokens <= 0 && trace != null) {
            tokens = trace.getInputTokens() + trace.getOutputTokens();
        }
        AgentEval.RubricJudge judge = (input, output, assertions) -> {
            ModelConfig judgeModel = run.getJudgeModelId() == null
                    ? null : models.findById(run.getJudgeModelId()).orElse(null);
            if (judgeModel == null) {
                throw new IllegalStateException("模型断言需要可用的裁判模型");
            }
            return EvalScorers.scoreRubric(input, output, assertions, judgeModel.getModelId(),
                    AgentScopeRuntime.modelEndpoint(judgeModel), AgentScopeRuntime.resolveCredential(judgeModel));
        };
        AgentEval.TrialGrade grade = AgentEval.grade(graders, item.getInput(), actual, AgentEval.toolNames(spans),
                turns, tokens, run.getAggregation(), run.getPassThreshold(), judge);
        row.setStatus(grade.status());
        row.setScore(grade.score());
        row.setReason(grade.reason());
        row.setGraderResults(grade.graders().stream().map(AgentEval.GraderOutcome::toMap).toList());
        return row;
    }

    private void applyAgentHeadline(EvaluationRun run, int passedTrials, int failedTrials) {
        Map<Long, List<String>> byCase = new LinkedHashMap<>();
        for (Long caseId : run.getCaseIds()) {
            byCase.put(caseId, new ArrayList<>());
        }
        for (EvaluationResult result : results.findByRunIdOrderByIdAsc(run.getId())) {
            byCase.computeIfAbsent(result.getCaseId(), ignored -> new ArrayList<>()).add(result.getStatus());
        }
        String suite = "";
        Map<String, Object> metrics = run.getMetrics() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(run.getMetrics());
        Object stored = metrics.get("suite");
        if (stored != null) {
            suite = String.valueOf(stored);
        }
        AgentEval.SuiteScore score = AgentEval.summarize(suite, run.getTrials(), new ArrayList<>(byCase.values()),
                passedTrials, failedTrials);
        run.setScore(score.headline());
        metrics.put("pass_at_k", score.passAtK());
        metrics.put("pass_hat_k", score.passHatK());
        metrics.put("suite", score.suite());
        metrics.put("suite_note", score.note());
        metrics.put("tasks", score.tasks());
        metrics.put("tasks_pass_at", score.tasksPassAt());
        metrics.put("tasks_pass_hat", score.tasksPassHat());
        metrics.put("trial_pass_rate", score.trialPassRate());
        metrics.put("trials", score.trials());
        metrics.put("aggregation", run.getAggregation());
        metrics.put("pass_threshold", run.getPassThreshold());
        run.setMetrics(metrics);
    }

    private boolean caseNeedsRubric(DatasetCase item) {
        return AgentEval.needsRubric(item.getGraders(), item.getExpected(), AgentEval.explicitGraders(item.getExtra()));
    }

    public List<DatasetCase> resolveCases(Long datasetId, List<Long> caseIds) {
        List<DatasetCase> all = cases.findByDatasetIdAndEnabledTrueOrderByIdAsc(datasetId);
        if (caseIds == null || caseIds.isEmpty()) {
            return all;
        }
        return all.stream().filter(item -> caseIds.contains(item.getId())).toList();
    }

    public void refreshCount(Dataset dataset) {
        dataset.setCaseCount((int) cases.countByDatasetId(dataset.getId()));
    }

    public void assertDatasetVisible(Dataset dataset, Agent agent, CurrentUser user) {
        List<Long> bound = dataset.getAgentIds() == null ? List.of() : dataset.getAgentIds();
        if (!bound.isEmpty() && !bound.contains(agent.getId())) {
            List<String> names = new ArrayList<>();
            for (Long id : bound) {
                agents.findById(id).ifPresent(item -> names.add(item.getName()));
            }
            throw ApiException.badRequest("该数据集已绑定到 " + (names.isEmpty() ? "指定 Agent" : String.join("、", names))
                    + "，不能用「" + agent.getName() + "」开测");
        }
    }

    public boolean datasetVisibleToAgent(Dataset row, Long agentId) {
        List<Long> bound = row.getAgentIds() == null ? List.of() : row.getAgentIds();
        return bound.isEmpty() || bound.contains(agentId);
    }

    public String cleanDatasetName(CurrentUser user, String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) {
            throw ApiException.badRequest("请填写数据集名称");
        }
        for (Agent agent : access.<Agent>listRows(user, ResourceKind.AGENT)) {
            if (cleaned.equals(agent.getName())) {
                throw ApiException.badRequest("数据集不能和 Agent 同名。请用测试内容命名，例如「日常问答」。");
            }
        }
        return cleaned;
    }

    public List<Long> resolveAgentIds(CurrentUser user, List<Long> agentIds) {
        List<Long> cleaned = new ArrayList<>();
        if (agentIds == null) {
            return cleaned;
        }
        for (Long id : agentIds) {
            if (id == null || cleaned.contains(id)) {
                continue;
            }
            access.getRow(user, ResourceKind.AGENT, id);
            cleaned.add(id);
        }
        return cleaned;
    }

    public static String normalizeSuite(String raw) {
        try {
            return AgentEval.suite(raw);
        } catch (IllegalArgumentException exc) {
            throw ApiException.badRequest(exc.getMessage());
        }
    }

    public static String normalizeKind(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return "baseline";
        }
        String alias = KIND_ALIASES.getOrDefault(value, value.toLowerCase().replace("-", "").replace("_", ""));
        alias = KIND_ALIASES.getOrDefault(alias, alias);
        if (!KIND_LABELS.containsKey(alias)) {
            throw ApiException.badRequest("数据集分类必须是安全红队集、能力基线或黄金集");
        }
        return alias;
    }

    public ModelConfig resolveJudge(Long judgeModelId) {
        if (judgeModelId != null) {
            return models.findById(judgeModelId).filter(ModelConfig::isEnabled).orElse(null);
        }
        return models.findByName("评测裁判 · Qwen-Max").filter(ModelConfig::isEnabled).orElse(null);
    }

    private ModelConfig resolveAgentModel(Agent agent) {
        if (agent == null) {
            return null;
        }
        Optional<ModelConfig> named = models.findByName(agent.getModelName());
        if (named.isPresent()) {
            return named.get();
        }
        List<ModelConfig> enabled = models.findByTenantIdAndEnabledTrue(agent.getTenantId());
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    private Map<String, Trace> tracesFor(List<EvaluationResult> rows) {
        List<String> ids = rows.stream().map(EvaluationResult::getTraceId).filter(id -> id != null && !id.isBlank()).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Trace> map = new LinkedHashMap<>();
        traces.findByTraceIdIn(ids).forEach(item -> map.put(item.getTraceId(), item));
        return map;
    }
}
