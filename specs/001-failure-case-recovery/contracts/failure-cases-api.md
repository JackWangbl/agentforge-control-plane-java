# Failure Case API Contract

Base path: `/api/failure-cases`

All endpoints require authentication and tenant isolation. List/detail require existing `trace:read`; recovery requires existing `session:write` (or the equivalent existing Agent execution permission). Forced recovery additionally requires `tenant:admin`.

All errors preserve the existing shape:

```json
{"detail":"safe user-facing message"}
```

Validation errors remain HTTP 422 with the existing `detail` array shape.

## GET `/api/failure-cases`

Returns filtered metrics and a page of Failure Cases.

### Query parameters

| Name | Required | Rules |
|---|---|---|
| `from` / `to` | No | ISO-8601 instants; default last 24 hours |
| `entry_point` | No | One supported entry enum |
| `agent_id` | No | Positive numeric Agent ID visible to the tenant |
| `status` | No | Case status enum |
| `page` | No | Zero-based, default 0 |
| `size` | No | Default 50, range 1–200 |

### 200 response

```json
{
  "summary": {
    "completed_original_executions": 100,
    "failed_original_executions": 20,
    "failure_rate": 20.0
  },
  "page": 0,
  "size": 50,
  "total": 20,
  "items": [
    {
      "case_id": "case_...",
      "execution_id": "exec_...",
      "entry_point": "PLAYGROUND",
      "agent_id": 12,
      "agent_name": "Customer Support",
      "status": "RESUMABLE",
      "failure_category": "UPSTREAM_UNAVAILABLE",
      "failure_step": "tool.crm.lookup",
      "error_summary": "上游服务暂时不可用",
      "resumable": true,
      "failed_at": "2026-10-27T10:00:00Z"
    }
  ]
}
```

Metrics and items use exactly the same tenant/time/entry/Agent/status filter set.

## GET `/api/failure-cases/{caseId}`

Returns the Case, redacted original input, checkpoint summary, configuration version references, attempts, and current task.

### 200 response fields

- `case`: identity, entry, Agent/workflow, status, failure category/step/summary and timestamps.
- `original_request`: recursively redacted, read-only view.
- `request_hash`: integrity identifier, not editable input.
- `configuration`: logical snapshot revision/fingerprint and credential reference names only.
- `checkpoint`: latest safe step, completed steps, pending step, outcome certainty and resumability.
- `attempts`: ordered audit list with actor, force metadata, timeouts, status and safe result.
- `active_task`: nullable queue/running status and timestamps.
- `actions`: server-calculated `can_recover` and `can_force_recover` booleans.

### Errors

- `404`: missing, cross-tenant, or inaccessible Case (do not reveal existence).

## POST `/api/failure-cases/{caseId}/recover`

Creates exactly one recovery attempt/task after state, permission, integrity and quota checks.

### Request

```json
{
  "force": false,
  "force_reason": "",
  "risk_acknowledged": false
}
```

Rules:

- Ordinary recovery requires `force=false`.
- `force=true` requires tenant administrator, nonblank `force_reason`, and `risk_acknowledged=true`.
- The API never accepts replacement request input, Agent, workflow, model, tool, checkpoint, timeout, retry, or concurrency values.

### 200 response

Used only if the same durable task completes within the configured 3-second synchronous wait.

```json
{
  "case_id": "case_...",
  "attempt_id": "attempt_...",
  "task_id": "task_...",
  "status": "SUCCEEDED",
  "result_execution_id": "exec_..."
}
```

### 202 response

Returned when the same task remains queued/running after the synchronous wait.

```json
{
  "case_id": "case_...",
  "attempt_id": "attempt_...",
  "task_id": "task_...",
  "status": "QUEUED",
  "poll_url": "/api/recovery-tasks/task_..."
}
```

The transition to 202 must not cancel, restart, or duplicate the task.

### Errors

| Status | Condition |
|---|---|
| 403 | Force requested without tenant-admin authority |
| 404 | Case inaccessible or absent |
| 409 | Case already recovering, already recovered, or not safely resumable |
| 422 | Invalid force request body |
| 429 | Tenant queue full; no attempt/task is created and Case is unchanged |
| 503 | Frozen configuration/credential reference is unavailable before task start |

## GET `/api/recovery-tasks/{taskId}`

Returns tenant-scoped task status for UI polling.

### 200 response

```json
{
  "task_id": "task_...",
  "case_id": "case_...",
  "attempt_id": "attempt_...",
  "status": "RUNNING",
  "queued_at": "2026-10-27T10:01:00Z",
  "started_at": "2026-10-27T10:01:01Z",
  "finished_at": null,
  "failure_category": "",
  "error_summary": "",
  "result_execution_id": null
}
```

Terminal statuses are `SUCCEEDED`, `FAILED`, `TIMED_OUT`, or `MANUAL_REQUIRED`.

## Compatibility: POST `/api/playground/resume`

- Retained during migration.
- Resolves the corresponding Failure Case and delegates to the same coordinator.
- Returns the existing playground success shape when completion is synchronous.
- May add task identifiers when returning background state, without changing the `detail` error envelope.
- `force_rerun_tools` no longer authorizes unsafe replay for ordinary users. Admin forced recovery must use the audited Failure Case operation.

