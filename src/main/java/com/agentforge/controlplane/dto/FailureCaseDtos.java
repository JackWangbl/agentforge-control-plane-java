package com.agentforge.controlplane.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class FailureCaseDtos {
    private FailureCaseDtos() {}

    public record Summary(long completed_original_executions, long failed_original_executions, double failure_rate) {}
    public record ListItem(String case_id, String execution_id, String entry_point, Long agent_id, String agent_name,
                           String status, String failure_category, String failure_step, String error_summary,
                           boolean resumable, Instant failed_at) {}
    public record Page(Summary summary, int page, int size, long total, List<ListItem> items) {}
    public record CaseView(String case_id, String execution_id, String entry_point, Long agent_id, String agent_name,
                           Long workflow_id, String status, String failure_category, String failure_step,
                           String error_summary, Instant failed_at, Instant updated_at) {}
    public record ConfigurationView(String fingerprint, Map<String, Object> logical_snapshot,
                                    List<String> credential_references) {}
    public record CheckpointView(String checkpoint_id, Integer sequence, String latest_safe_step,
                                 List<String> completed_steps, String pending_step, String outcome,
                                 boolean resumable, String side_effect_semantics) {}
    public record AttemptView(String attempt_id, Long initiated_by, boolean forced, String force_reason,
                              Instant risk_acknowledged_at, String status, long sync_wait_ms,
                              long step_timeout_ms, long overall_timeout_ms, String failure_category,
                              String error_summary, Instant started_at, Instant finished_at, String result_execution_id) {}
    public record TaskView(String task_id, String case_id, String attempt_id, String status, Instant queued_at,
                           Instant started_at, Instant finished_at, String failure_category, String error_summary,
                           String result_execution_id) {}
    public record Actions(boolean can_recover, boolean can_force_recover) {}
    public record Detail(@JsonProperty("case") CaseView case_view, Object original_request, String request_hash,
                         ConfigurationView configuration, CheckpointView checkpoint, List<AttemptView> attempts,
                         TaskView active_task, Actions actions) {}

    public record RecoverRequest(boolean force, @Size(max = 500) String force_reason, boolean risk_acknowledged) {
        @AssertTrue(message = "强制重拾必须填写理由并确认重复副作用风险")
        public boolean isValidForceRequest() {
            return !force || (force_reason != null && !force_reason.isBlank() && risk_acknowledged);
        }
    }

    public record RecoverResponse(String case_id, String attempt_id, String task_id, String status,
                                  String result_execution_id, String poll_url) {}
}
