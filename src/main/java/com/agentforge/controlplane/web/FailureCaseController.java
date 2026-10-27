package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.domain.ExecutionRecord;
import com.agentforge.controlplane.domain.FailureCase;
import com.agentforge.controlplane.dto.FailureCaseDtos;
import com.agentforge.controlplane.recovery.FailureCaseService;
import com.agentforge.controlplane.recovery.RecoveryCoordinator;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class FailureCaseController {
    private final FailureCaseService service;
    private final RecoveryCoordinator coordinator;

    public FailureCaseController(FailureCaseService service, RecoveryCoordinator coordinator) {
        this.service = service;
        this.coordinator = coordinator;
    }

    @RequirePermission("trace:read")
    @GetMapping("/api/failure-cases")
    public FailureCaseDtos.Page list(CurrentUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "entry_point", required = false) ExecutionRecord.EntryPoint entryPoint,
            @RequestParam(name = "agent_id", required = false) Long agentId,
            @RequestParam(required = false) FailureCase.Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.list(user, from, to, entryPoint, agentId, status, page, size);
    }

    @RequirePermission("trace:read")
    @GetMapping("/api/failure-cases/{caseId}")
    public FailureCaseDtos.Detail detail(CurrentUser user, @PathVariable String caseId) {
        return service.detail(user, caseId);
    }

    @RequirePermission("session:write")
    @PostMapping("/api/failure-cases/{caseId}/recover")
    public ResponseEntity<FailureCaseDtos.RecoverResponse> recover(CurrentUser user, @PathVariable String caseId,
            @Valid @RequestBody FailureCaseDtos.RecoverRequest request) {
        RecoveryCoordinator.Submission result = coordinator.submit(user, caseId, request);
        return ResponseEntity.status(result.completed() ? 200 : 202).body(result.response());
    }

    @RequirePermission("trace:read")
    @GetMapping("/api/recovery-tasks/{taskId}")
    public FailureCaseDtos.TaskView task(CurrentUser user, @PathVariable String taskId) {
        return service.task(user, taskId);
    }
}
