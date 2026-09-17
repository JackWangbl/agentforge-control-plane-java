package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.dto.ApiDtos;
import com.agentforge.controlplane.experiment.ExperimentService;
import com.agentforge.controlplane.repo.ExperimentAssignmentRepository;
import com.agentforge.controlplane.repo.ExperimentEventRepository;
import com.agentforge.controlplane.repo.ExperimentRepository;
import com.agentforge.controlplane.repo.ExperimentVariantRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ExperimentController {

    private final ResourceAccessService access;
    private final ExperimentService experiments;
    private final ExperimentRepository experimentRows;
    private final ExperimentVariantRepository variants;
    private final ExperimentAssignmentRepository assignments;
    private final ExperimentEventRepository events;

    public ExperimentController(ResourceAccessService access, ExperimentService experiments,
                                ExperimentRepository experimentRows, ExperimentVariantRepository variants,
                                ExperimentAssignmentRepository assignments, ExperimentEventRepository events) {
        this.access = access;
        this.experiments = experiments;
        this.experimentRows = experimentRows;
        this.variants = variants;
        this.assignments = assignments;
        this.events = events;
    }

    @RequirePermission("experiment:read")
    @GetMapping("/api/experiments")
    public List<Map<String, Object>> list(CurrentUser user) {
        return access.<Experiment>listRows(user, ResourceKind.EXPERIMENT).stream()
                .map(experiments::dump).toList();
    }

    @RequirePermission("experiment:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/experiments")
    public Map<String, Object> create(CurrentUser user, @Valid @RequestBody ApiDtos.ExperimentCreate payload) {
        String strategy = ExperimentService.normalizeStrategy(payload.assignment_strategy(), payload.assignment_unit());
        if (!ExperimentService.STRATEGIES.containsKey(strategy)) {
            throw ApiException.badRequest("不支持的分流策略");
        }
        List<Map<String, Object>> items = experiments.validateVariants(payload.variants(), user);
        Experiment row = new Experiment();
        row.setName(payload.name().strip());
        row.setDescription(payload.description());
        row.setStatus("draft");
        experiments.applyStrategy(row, strategy, payload.assignment_unit());
        row.setTrafficPercent(payload.traffic_percent());
        access.stampOwner(row, user);
        experimentRows.save(row);
        experiments.replaceVariants(row, items, user);
        return experiments.dump(experimentRows.findById(row.getId()).orElse(row));
    }

    @RequirePermission("experiment:read")
    @GetMapping("/api/experiments/{experimentId}")
    public Map<String, Object> get(CurrentUser user, @PathVariable Long experimentId) {
        return experiments.results(access.getRow(user, ResourceKind.EXPERIMENT, experimentId));
    }

    @RequirePermission("experiment:write")
    @PutMapping("/api/experiments/{experimentId}")
    public Map<String, Object> update(CurrentUser user, @PathVariable Long experimentId,
                                      @RequestBody ApiDtos.ExperimentUpdate payload) {
        Experiment row = access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        if ("completed".equals(row.getStatus())) {
            throw ApiException.conflict("已结束的实验不能再改");
        }
        if (payload.name() != null) {
            row.setName(payload.name().strip());
        }
        if (payload.description() != null) {
            row.setDescription(payload.description());
        }
        if (payload.assignment_strategy() != null || payload.assignment_unit() != null) {
            if ("running".equals(row.getStatus())) {
                throw ApiException.conflict("进行中的实验不能改分流策略，请先暂停");
            }
            experiments.applyStrategy(row, payload.assignment_strategy() == null
                    ? payload.assignment_unit() : payload.assignment_strategy(), payload.assignment_unit());
        }
        if (payload.traffic_percent() != null) {
            row.setTrafficPercent(payload.traffic_percent());
        }
        if (payload.variants() != null) {
            if ("running".equals(row.getStatus())) {
                throw ApiException.conflict("进行中的实验不能改变体，请先暂停");
            }
            experiments.replaceVariants(row, experiments.validateVariants(payload.variants(), user), user);
        }
        experimentRows.save(row);
        return experiments.dump(row);
    }

    @RequirePermission("experiment:write")
    @Transactional
    @DeleteMapping("/api/experiments/{experimentId}")
    public Map<String, Object> delete(CurrentUser user, @PathVariable Long experimentId) {
        access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        events.deleteByExperimentId(experimentId);
        assignments.deleteByExperimentId(experimentId);
        variants.deleteByExperimentId(experimentId);
        experimentRows.deleteById(experimentId);
        return Map.of("ok", true);
    }

    @RequirePermission("experiment:write")
    @PostMapping("/api/experiments/{experimentId}/start")
    public Map<String, Object> start(CurrentUser user, @PathVariable Long experimentId) {
        Experiment row = access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        if ("completed".equals(row.getStatus())) {
            throw ApiException.conflict("已结束的实验不能重新开启");
        }
        if (variants.findByExperimentIdOrderByIdAsc(row.getId()).size() < 2) {
            throw ApiException.badRequest("至少需要两个分流变体");
        }
        row.setStatus("running");
        if (row.getStartedAt() == null) {
            row.setStartedAt(Instant.now());
        }
        row.setFinishedAt(null);
        experimentRows.save(row);
        return experiments.dump(row);
    }

    @RequirePermission("experiment:write")
    @PostMapping("/api/experiments/{experimentId}/pause")
    public Map<String, Object> pause(CurrentUser user, @PathVariable Long experimentId) {
        Experiment row = access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        if (!"running".equals(row.getStatus())) {
            throw ApiException.conflict("只有进行中的实验可以暂停");
        }
        row.setStatus("paused");
        experimentRows.save(row);
        return experiments.dump(row);
    }

    @RequirePermission("experiment:write")
    @PostMapping("/api/experiments/{experimentId}/complete")
    public Map<String, Object> complete(CurrentUser user, @PathVariable Long experimentId) {
        Experiment row = access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        if ("completed".equals(row.getStatus())) {
            return experiments.dump(row);
        }
        row.setStatus("completed");
        row.setFinishedAt(Instant.now());
        experimentRows.save(row);
        return experiments.dump(row);
    }

    @RequirePermission({"experiment:read", "session:write"})
    @PostMapping("/api/experiments/{experimentId}/assign")
    public Map<String, Object> assign(CurrentUser user, @PathVariable Long experimentId,
                                      @RequestBody ApiDtos.ExperimentAssign payload) {
        Experiment row = access.getRow(user, ResourceKind.EXPERIMENT, experimentId);
        if (!"running".equals(row.getStatus())) {
            throw ApiException.conflict("只有进行中的实验才会分流");
        }
        String sessionId = (payload.session_id() == null || payload.session_id().isBlank()
                ? payload.unit_key() : payload.session_id()).strip();
        String userKey = payload.user_key() == null || payload.user_key().isBlank()
                ? user.getUsername() : payload.user_key().strip();
        if (sessionId.isEmpty() && userKey.isEmpty()) {
            throw ApiException.badRequest("请提供 session_id 或 user_key");
        }
        return experiments.assignUnit(row, sessionId.isEmpty() ? "sess_" + userKey : sessionId, userKey, user);
    }

    @RequirePermission("experiment:write")
    @PostMapping("/api/experiments/{experimentId}/compare")
    public Map<String, Object> compare(CurrentUser user, @PathVariable Long experimentId,
                                       @RequestBody ApiDtos.ExperimentCompare payload) {
        Experiment row = access.resolveForEdit(user, ResourceKind.EXPERIMENT, experimentId);
        Map<String, Object> snapshot = experiments.compare(row, payload.dataset_id(), payload.prompts(),
                payload.scorer(), payload.case_limit(), user);
        Map<String, Object> data = new LinkedHashMap<>(experiments.results(row));
        data.put("last_compare", snapshot);
        return data;
    }
}
