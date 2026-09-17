package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.PublicEndpoint;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.Dataset;
import com.agentforge.controlplane.domain.DatasetCase;
import com.agentforge.controlplane.domain.EvaluationResult;
import com.agentforge.controlplane.domain.EvaluationRun;
import com.agentforge.controlplane.dto.EvalDtos;
import com.agentforge.controlplane.eval.DatasetImporter;
import com.agentforge.controlplane.eval.EvalScorers;
import com.agentforge.controlplane.eval.EvalService;
import com.agentforge.controlplane.repo.DatasetCaseRepository;
import com.agentforge.controlplane.repo.DatasetRepository;
import com.agentforge.controlplane.repo.EvaluationResultRepository;
import com.agentforge.controlplane.repo.EvaluationRunRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class EvalController {

    private final ResourceAccessService access;
    private final EvalService evals;
    private final DatasetRepository datasets;
    private final DatasetCaseRepository cases;
    private final EvaluationRunRepository runs;
    private final EvaluationResultRepository results;

    public EvalController(ResourceAccessService access, EvalService evals, DatasetRepository datasets,
                          DatasetCaseRepository cases, EvaluationRunRepository runs,
                          EvaluationResultRepository results) {
        this.access = access;
        this.evals = evals;
        this.datasets = datasets;
        this.cases = cases;
        this.runs = runs;
        this.results = results;
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/datasets")
    public List<EvalDtos.DatasetResponse> listDatasets(CurrentUser user,
                                                       @RequestParam(defaultValue = "") String kind,
                                                       @RequestParam(required = false) Long agent_id) {
        List<Dataset> rows = access.<Dataset>listRows(user, ResourceKind.DATASET);
        if (!kind.isBlank()) {
            String wanted = EvalService.normalizeKind(kind);
            rows = rows.stream().filter(row -> wanted.equals(row.getKind() == null ? "baseline" : row.getKind())).toList();
        }
        if (agent_id != null) {
            rows = rows.stream().filter(row -> evals.datasetVisibleToAgent(row, agent_id)).toList();
        }
        return rows.stream().map(row -> evals.dumpDataset(row, user)).toList();
    }

    @PublicEndpoint
    @GetMapping("/api/datasets/template.csv")
    public ResponseEntity<byte[]> template() {
        byte[] body = "id,input,expected,tags\n1,用户问退款要几天到账,三个工作日内原路退回,退款\n"
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dataset-template.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @RequirePermission("eval:run")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/datasets")
    public EvalDtos.DatasetResponse createDataset(CurrentUser user, @Valid @RequestBody EvalDtos.DatasetCreateRequest payload) {
        Dataset row = new Dataset();
        row.setName(evals.cleanDatasetName(user, payload.name()));
        row.setDescription(payload.description());
        row.setKind(EvalService.normalizeKind(payload.kind()));
        row.setAgentIds(evals.resolveAgentIds(user, payload.agent_ids()));
        access.stampOwner(row, user);
        datasets.save(row);
        return evals.dumpDataset(row, user);
    }

    @RequirePermission("eval:run")
    @PutMapping("/api/datasets/{datasetId}")
    public EvalDtos.DatasetResponse updateDataset(CurrentUser user, @PathVariable Long datasetId,
                                                  @RequestBody EvalDtos.DatasetUpdateRequest payload) {
        Dataset row = access.resolveForEdit(user, ResourceKind.DATASET, datasetId);
        if (payload.name() != null) {
            row.setName(evals.cleanDatasetName(user, payload.name()));
        }
        if (payload.description() != null) {
            row.setDescription(payload.description());
        }
        if (payload.kind() != null) {
            row.setKind(EvalService.normalizeKind(payload.kind()));
        }
        if (payload.agent_ids() != null) {
            row.setAgentIds(evals.resolveAgentIds(user, payload.agent_ids()));
        }
        datasets.save(row);
        return evals.dumpDataset(row, user);
    }

    @RequirePermission("eval:run")
    @PostMapping(value = "/api/datasets/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EvalDtos.DatasetResponse importDataset(CurrentUser user,
                                                  @RequestPart("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "") String name,
                                                  @RequestParam(required = false) Long dataset_id,
                                                  @RequestParam(defaultValue = "skip") String on_duplicate) throws Exception {
        byte[] raw = file.getBytes();
        DatasetImporter.Parsed parsed = DatasetImporter.parse(raw, file.getOriginalFilename());
        Dataset dataset = dataset_id == null ? null : access.getRow(user, ResourceKind.DATASET, dataset_id);
        if (dataset == null) {
            String filename = file.getOriginalFilename() == null ? "数据集" : file.getOriginalFilename();
            int cut = filename.lastIndexOf('.');
            String stem = (name == null || name.isBlank() ? (cut > 0 ? filename.substring(0, cut) : filename) : name).strip();
            dataset = new Dataset();
            dataset.setName(stem.isBlank() ? "未命名数据集" : stem);
            dataset.setKind("baseline");
            dataset.setAgentIds(List.of());
            access.stampOwner(dataset, user);
            datasets.save(dataset);
        }
        dataset.setSourceName(file.getOriginalFilename() == null ? dataset.getSourceName() : file.getOriginalFilename());
        Map<String, Integer> stats = insertCases(dataset, parsed.cases(), on_duplicate);
        Path folder = Path.of(System.getProperty("user.dir", ".")).resolve("workspaces").resolve("_datasets")
                .resolve(String.valueOf(dataset.getId()));
        Files.createDirectories(folder);
        Files.write(folder.resolve(file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename()), raw);
        datasets.save(dataset);
        return evals.dumpDataset(dataset, user).withImport(
                stats.get("added"), stats.get("updated"), stats.get("skipped"), parsed.errors(), parsed.count());
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/datasets/{datasetId}")
    public EvalDtos.DatasetResponse getDataset(CurrentUser user, @PathVariable Long datasetId) {
        Dataset row = access.getRow(user, ResourceKind.DATASET, datasetId);
        List<EvalDtos.CaseResponse> dumped = cases.findByDatasetIdOrderByIdAsc(datasetId).stream()
                .map(evals::dumpCase).toList();
        return evals.dumpDataset(row, user).withCases(dumped);
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/datasets/{datasetId}/cases")
    public List<EvalDtos.CaseResponse> listCases(CurrentUser user, @PathVariable Long datasetId,
                                                 @RequestParam(defaultValue = "") String q) {
        access.getRow(user, ResourceKind.DATASET, datasetId);
        List<DatasetCase> rows = cases.findByDatasetIdOrderByIdAsc(datasetId);
        if (!q.isBlank()) {
            String needle = q.strip().toLowerCase();
            rows = rows.stream().filter(row ->
                    row.getInput().toLowerCase().contains(needle)
                            || row.getExpected().toLowerCase().contains(needle)
                            || row.getCaseKey().toLowerCase().contains(needle)).toList();
        }
        return rows.stream().map(evals::dumpCase).toList();
    }

    @RequirePermission("eval:run")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/datasets/{datasetId}/cases")
    public EvalDtos.CaseResponse addCase(CurrentUser user, @PathVariable Long datasetId,
                                         @Valid @RequestBody EvalDtos.DatasetCaseCreateRequest payload) {
        Dataset dataset = access.resolveForEdit(user, ResourceKind.DATASET, datasetId);
        Map<String, Object> extra = new LinkedHashMap<>(payload.extra() == null ? Map.of() : payload.extra());
        if (payload.solution() != null && !payload.solution().isBlank()) {
            extra.put("solution", payload.solution().strip());
        }
        DatasetCase row = new DatasetCase();
        row.setDatasetId(datasetId);
        row.setCaseKey(payload.case_key());
        row.setInput(payload.input());
        row.setExpected(payload.expected());
        row.setTags(payload.tags());
        row.setExtra(extra);
        row.setTenantId(user.getTenantId());
        row.setOwnerId(user.getId());
        cases.save(row);
        if (row.getCaseKey() == null || row.getCaseKey().isBlank()) {
            row.setCaseKey(String.valueOf(row.getId()));
            cases.save(row);
        }
        evals.refreshCount(dataset);
        datasets.save(dataset);
        return evals.dumpCase(row);
    }

    @RequirePermission("eval:run")
    @DeleteMapping("/api/datasets/{datasetId}/cases/{caseId}")
    public Map<String, Object> deleteCase(CurrentUser user, @PathVariable Long datasetId, @PathVariable Long caseId) {
        Dataset dataset = access.resolveForEdit(user, ResourceKind.DATASET, datasetId);
        DatasetCase row = cases.findById(caseId).orElse(null);
        if (row == null || !datasetId.equals(row.getDatasetId())) {
            throw ApiException.notFound("Case not found");
        }
        cases.delete(row);
        evals.refreshCount(dataset);
        datasets.save(dataset);
        return Map.of("ok", true);
    }

    @RequirePermission("eval:run")
    @Transactional
    @DeleteMapping("/api/datasets/{datasetId}")
    public Map<String, Object> deleteDataset(CurrentUser user, @PathVariable Long datasetId) {
        access.resolveForEdit(user, ResourceKind.DATASET, datasetId);
        cases.deleteByDatasetId(datasetId);
        datasets.deleteById(datasetId);
        return Map.of("ok", true);
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/evaluations")
    public List<EvalDtos.RunResponse> listEvaluations(CurrentUser user) {
        List<EvaluationRun> rows = access.<EvaluationRun>listRows(user, ResourceKind.EVALUATION);
        return rows.stream().map(evals::dumpRun).toList();
    }

    @RequirePermission("eval:run")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/evaluations")
    public EvalDtos.RunResponse createEvaluation(CurrentUser user, @Valid @RequestBody EvalDtos.EvaluationLaunchRequest payload) {
        EvaluationRun run = evals.prepareRun(user, payload.agent_id(), payload.dataset_id(), payload.name(),
                payload.scorer(), payload.judge_model_id(), payload.case_ids(), "offline");
        return evals.dumpRun(run);
    }

    @RequirePermission("eval:run")
    @PostMapping("/api/evaluations/online")
    public EvalDtos.RunResponse runOnline(CurrentUser user, @Valid @RequestBody EvalDtos.EvaluationLaunchRequest payload) {
        EvaluationRun run = evals.prepareRun(user, payload.agent_id(), payload.dataset_id(), payload.name(),
                payload.scorer(), payload.judge_model_id(), payload.case_ids(), "online");
        evals.executeRun(run.getId());
        return evals.loadRun(run.getId());
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/evaluations/scoring-guide")
    public EvalDtos.ScoringGuideResponse scoringGuide(CurrentUser user) {
        return EvalScorers.scoringGuide();
    }

    @RequirePermission("eval:run")
    @PostMapping("/api/evaluations/performance")
    public EvalDtos.RunResponse runPerformance(CurrentUser user, @Valid @RequestBody EvalDtos.PerformanceLaunchRequest payload) {
        EvaluationRun run = evals.preparePerf(user, payload.agent_id(), payload.dataset_id(), payload.name(),
                payload.concurrency(), payload.requests());
        if (payload.requests() <= EvalService.PERF_INLINE_LIMIT) {
            evals.executePerfRun(run.getId());
            return evals.loadRun(run.getId());
        }
        return evals.dumpRun(run);
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/evaluations/{runId}")
    public EvalDtos.RunResponse getEvaluation(CurrentUser user, @PathVariable Long runId) {
        access.getRow(user, ResourceKind.EVALUATION, runId);
        return evals.loadRun(runId);
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/evaluations/{runId}/results")
    public List<EvalDtos.ResultResponse> listResults(CurrentUser user, @PathVariable Long runId,
                                                     @RequestParam(defaultValue = "") String status) {
        access.getRow(user, ResourceKind.EVALUATION, runId);
        List<EvaluationResult> rows = status.isBlank()
                ? results.findByRunIdOrderByIdAsc(runId)
                : results.findByRunIdAndStatusOrderByIdAsc(runId, status);
        return rows.stream().map(evals::dumpResult).toList();
    }

    @RequirePermission("eval:run")
    @Transactional
    @PostMapping("/api/evaluations/{runId}/resume")
    public EvalDtos.RunResponse resume(CurrentUser user, @PathVariable Long runId) {
        EvaluationRun run = access.resolveForEdit(user, ResourceKind.EVALUATION, runId);
        if ("queued".equals(run.getStatus()) || "running".equals(run.getStatus())) {
            throw ApiException.conflict("任务还在执行，请等待结束或先取消");
        }
        List<EvaluationResult> rows = results.findByRunIdOrderByIdAsc(runId);
        List<EvaluationResult> failed = rows.stream().filter(item -> !"passed".equals(item.getStatus())).toList();
        if (failed.isEmpty()) {
            throw ApiException.conflict("没有失败用例可以续跑");
        }
        failed.forEach(results::delete);
        int kept = (int) rows.stream().filter(item -> "passed".equals(item.getStatus())).count();
        run.setStatus("queued");
        run.setPassed(kept);
        run.setFailed(0);
        run.setSkipped(0);
        run.setScore(kept == 0 ? 0 : Math.round(kept * 1000.0 / Math.max(1, run.getTotal() == 0 ? run.getCases() : run.getTotal())) / 10.0);
        run.setErrorMessage("");
        run.setStartedAt(null);
        run.setFinishedAt(null);
        runs.save(run);
        if ("online".equals(run.getMode())) {
            evals.executeRun(run.getId());
            return evals.loadRun(runId);
        }
        return evals.dumpRun(run);
    }

    @RequirePermission("eval:run")
    @Transactional
    @PostMapping("/api/evaluations/{runId}/run")
    public EvalDtos.RunResponse rerun(CurrentUser user, @PathVariable Long runId) {
        EvaluationRun run = access.resolveForEdit(user, ResourceKind.EVALUATION, runId);
        results.deleteByRunId(runId);
        run.setStatus("queued");
        run.setPassed(0);
        run.setFailed(0);
        run.setSkipped(0);
        run.setScore(0);
        run.setErrorMessage("");
        run.setStartedAt(null);
        run.setFinishedAt(null);
        runs.save(run);
        int requests = 0;
        if (run.getMetrics() != null && run.getMetrics().get("requests") instanceof Number number) {
            requests = number.intValue();
        }
        if (requests == 0) {
            requests = run.getTotal();
        }
        if ("online".equals(run.getMode()) || ("performance".equals(run.getMode()) && requests <= EvalService.PERF_INLINE_LIMIT)) {
            if ("performance".equals(run.getMode())) {
                evals.executePerfRun(run.getId());
            } else {
                evals.executeRun(run.getId());
            }
            return evals.loadRun(runId);
        }
        return evals.dumpRun(run);
    }

    @RequirePermission("eval:run")
    @PostMapping("/api/evaluations/{runId}/cancel")
    public EvalDtos.RunResponse cancel(CurrentUser user, @PathVariable Long runId) {
        EvaluationRun run = access.resolveForEdit(user, ResourceKind.EVALUATION, runId);
        if ("completed".equals(run.getStatus()) || "failed".equals(run.getStatus())) {
            throw ApiException.conflict("任务已经结束");
        }
        run.setStatus("cancelled");
        run.setFinishedAt(Instant.now());
        runs.save(run);
        return evals.dumpRun(run);
    }

    @RequirePermission("eval:read")
    @GetMapping("/api/evaluations/{runId}/export.csv")
    public ResponseEntity<byte[]> export(CurrentUser user, @PathVariable Long runId) {
        access.getRow(user, ResourceKind.EVALUATION, runId);
        StringBuilder buf = new StringBuilder("case_key,status,score,input,expected,actual,reason,latency_ms,tokens,trace_id\n");
        for (EvaluationResult item : results.findByRunIdOrderByIdAsc(runId)) {
            buf.append(csv(item.getCaseKey())).append(',')
                    .append(csv(item.getStatus())).append(',')
                    .append(item.getScore()).append(',')
                    .append(csv(item.getInput())).append(',')
                    .append(csv(item.getExpected())).append(',')
                    .append(csv(item.getActual())).append(',')
                    .append(csv(item.getReason())).append(',')
                    .append(item.getLatencyMs()).append(',')
                    .append(item.getTokens()).append(',')
                    .append(csv(item.getTraceId())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=eval-" + runId + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(buf.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Integer> insertCases(Dataset dataset, List<Map<String, Object>> incoming, String onDuplicate) {
        Map<String, DatasetCase> existing = new LinkedHashMap<>();
        for (DatasetCase row : cases.findByDatasetIdOrderByIdAsc(dataset.getId())) {
            if (row.getCaseKey() != null && !row.getCaseKey().isBlank()) {
                existing.put(row.getCaseKey(), row);
            }
        }
        int added = 0, updated = 0, skipped = 0;
        for (Map<String, Object> item : incoming) {
            String key = String.valueOf(item.getOrDefault("case_key", ""));
            DatasetCase found = key.isBlank() ? null : existing.get(key);
            if (found != null && "skip".equals(onDuplicate)) {
                skipped++;
                continue;
            }
            if (found != null && "replace".equals(onDuplicate)) {
                found.setInput(String.valueOf(item.get("input")));
                found.setExpected(String.valueOf(item.getOrDefault("expected", "")));
                found.setTags(item.get("tags") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> extra = item.get("extra") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();
                found.setExtra(extra);
                found.setEnabled(true);
                cases.save(found);
                updated++;
                continue;
            }
            DatasetCase row = new DatasetCase();
            row.setDatasetId(dataset.getId());
            row.setCaseKey(key);
            row.setInput(String.valueOf(item.get("input")));
            row.setExpected(String.valueOf(item.getOrDefault("expected", "")));
            row.setTags(item.get("tags") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = item.get("extra") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            row.setExtra(extra);
            row.setTenantId(dataset.getTenantId());
            row.setOwnerId(dataset.getOwnerId());
            cases.save(row);
            if (!key.isBlank()) {
                existing.put(key, row);
            }
            added++;
        }
        evals.refreshCount(dataset);
        return Map.of("added", added, "updated", updated, "skipped", skipped);
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
