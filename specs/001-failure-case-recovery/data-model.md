# Data Model: 失败 Case 检查点重拾

## Design rules

- MySQL rows are authoritative for lifecycle, concurrency, leases, reporting, and audit.
- All tenant-owned rows carry `tenant_id`; query methods must require it rather than filtering after loading.
- IDs exposed by the API use opaque `*_key` values. Numeric primary keys remain internal.
- Request/config/checkpoint snapshots are immutable and hash-verified.
- Checkpoints and attempts are append-only. Terminal history is never overwritten.
- External work never runs inside a database transaction.
- JSON snapshots exclude credential values, HTTP authorization headers, cookies, and other secret material.

## Entity: ExecutionRecord

Represents one accepted execution after authentication and basic request validation.

| Field | Type | Rules |
|---|---|---|
| `id` | BIGINT | Internal primary key |
| `execution_key` | VARCHAR(80) | Globally unique opaque identifier |
| `tenant_id` / `owner_id` | BIGINT | Tenant required; owner nullable under existing conventions |
| `execution_kind` | VARCHAR(20) | `ORIGINAL` or `RECOVERY` |
| `entry_point` | VARCHAR(32) | `PLAYGROUND`, `AGENT_INVOKE`, `WORKFLOW_DEBUG`, `WORKFLOW_INVOKE`, `A2A` |
| `agent_id` / `agent_name` | BIGINT / VARCHAR(80) | Actual Agent that handled the execution |
| `workflow_id` | BIGINT nullable | Set for workflow entries |
| `session_id` / `trace_id` | VARCHAR | Existing correlation identifiers |
| `parent_execution_id` | BIGINT nullable | Recovery execution points to its failed original/recovery predecessor |
| `request_snapshot_json` | LONGTEXT | Canonical request body and required non-secret context |
| `request_hash` | CHAR(64) | SHA-256 over canonical snapshot bytes |
| `config_snapshot_json` | LONGTEXT | Failure-time logical Agent/model/tool/workflow configuration |
| `config_hash` | CHAR(64) | SHA-256 over canonical logical config |
| `credential_refs_json` | JSON | Reference names/IDs only, no values |
| `status` | VARCHAR(24) | `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `MANUAL_REQUIRED` |
| `failure_category` | VARCHAR(40) | Sanitized category from the common classifier |
| `failure_step` | VARCHAR(120) | Stable step key, not a free-form stack frame |
| `error_summary` | VARCHAR(500) | Sanitized user-safe message |
| `started_at` / `finished_at` | DATETIME(6) | UTC; finish nullable while active |
| `created_at` / `updated_at` | DATETIME(6) | Existing timestamp convention |

**Indexes/constraints**:

- Unique `execution_key`.
- Index `(tenant_id, execution_kind, status, finished_at)` for failure rate.
- Index `(tenant_id, agent_id, started_at)` and `(tenant_id, entry_point, started_at)` for filters.
- Index `trace_id`, `session_id`, and `parent_execution_id`.

Failure-rate queries count only `execution_kind=ORIGINAL` with terminal `SUCCEEDED|FAILED|TIMED_OUT|MANUAL_REQUIRED`; failures are all terminal states except `SUCCEEDED`.

## Entity: FailureCase

One-to-one operational view of a failed original execution.

| Field | Type | Rules |
|---|---|---|
| `id` | BIGINT | Internal primary key |
| `case_key` | VARCHAR(80) | Globally unique opaque identifier |
| `tenant_id` / `owner_id` | BIGINT | Tenant scope and original owner |
| `execution_record_id` | BIGINT | Unique reference to the original failed execution |
| `status` | VARCHAR(24) | `RESUMABLE`, `RECOVERING`, `MANUAL_REQUIRED`, `RECOVERED` |
| `resumable` | BOOLEAN | Derived/validated guard; never sufficient without status/checkpoint checks |
| `latest_checkpoint_id` | BIGINT nullable | Latest checkpoint evaluated as safe |
| `failure_category` / `failure_step` | VARCHAR | Current failure classification |
| `error_summary` | VARCHAR(500) | Sanitized current summary |
| `recovered_execution_id` | BIGINT nullable | Final successful recovery execution |
| `recovered_attempt_id` | BIGINT nullable | Attempt that reached the terminal state |
| `version` | BIGINT | Optimistic version for UI/update conflict detection |
| `created_at` / `updated_at` | DATETIME(6) | UTC |

**Atomic recovery claim**: a conditional update changes `RESUMABLE` (or admin-authorized `MANUAL_REQUIRED`) to `RECOVERING`. Only the caller receiving one updated row may create the active attempt/task. This is the authoritative same-Case concurrency guard.

**Indexes/constraints**:

- Unique `case_key` and unique `execution_record_id`.
- Index `(tenant_id, status, updated_at)` and `(tenant_id, agent_id via execution join)`.

## Entity: ExecutionCheckpoint

Append-only state at an execution boundary.

| Field | Type | Rules |
|---|---|---|
| `id` | BIGINT | Internal primary key |
| `checkpoint_key` | VARCHAR(80) | Unique opaque identifier |
| `tenant_id` | BIGINT | Must match execution/case tenant |
| `execution_record_id` / `failure_case_id` | BIGINT | Execution required; Case nullable until failure materializes |
| `sequence_no` | INT | Strictly increasing within an execution |
| `step_key` / `next_step_key` | VARCHAR(120) | Stable execution-boundary identifiers |
| `outcome` | VARCHAR(24) | `CONFIRMED`, `FAILED`, `NOT_STARTED`, `UNKNOWN` |
| `safe_to_resume` | BOOLEAN | False for unknown/unsafe side effects |
| `side_effect_semantics` | VARCHAR(32) | `NONE`, `IDEMPOTENT`, `SIDE_EFFECTING`, `UNKNOWN` |
| `idempotency_key` | VARCHAR(160) nullable | Stable downstream key where supported |
| `state_snapshot_json` | LONGTEXT | AgentScope working state, pending tools, completed call IDs, usage and minimal continuation state |
| `state_hash` | CHAR(64) | Integrity check over canonical state |
| `error_category` / `error_summary` | VARCHAR | Sanitized failure information |
| `created_at` | DATETIME(6) | UTC and immutable |

**Constraints**: unique `(execution_record_id, sequence_no)` and unique `checkpoint_key`; index `(failure_case_id, sequence_no)`.

`done_tool_ids` from the existing AgentScope checkpoint becomes part of the immutable snapshot and remains the primary guard against replaying confirmed tool calls.

## Entity: RecoveryAttempt

Audit record for one user-initiated recovery.

| Field | Type | Rules |
|---|---|---|
| `id` | BIGINT | Internal primary key |
| `attempt_key` | VARCHAR(80) | Unique opaque identifier |
| `tenant_id` / `failure_case_id` | BIGINT | Required |
| `initiated_by` | BIGINT | User ID |
| `forced` | BOOLEAN | True only for tenant administrator exception |
| `force_reason` | VARCHAR(500) nullable | Required when forced |
| `risk_acknowledged_at` | DATETIME(6) nullable | Required when forced |
| `start_checkpoint_id` | BIGINT | Checkpoint selected before execution |
| `status` | VARCHAR(24) | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `MANUAL_REQUIRED` |
| `sync_wait_ms` / `step_timeout_ms` / `overall_timeout_ms` | BIGINT | Effective values frozen at creation |
| `retry_policy_json` / `concurrency_policy_json` | JSON | Effective bounded settings frozen at creation |
| `result_execution_id` / `result_checkpoint_id` | BIGINT nullable | Result links |
| `failure_category` / `error_summary` | VARCHAR | Sanitized attempt result |
| `started_at` / `finished_at` | DATETIME(6) nullable | UTC |
| `created_at` / `updated_at` | DATETIME(6) | UTC |

Attempts are never deleted or rewritten into another attempt. A failed attempt may move its Case back to `RESUMABLE` only when the result checkpoint is confirmed safe.

## Entity: RecoveryTask

Durable queue/lease row for one RecoveryAttempt.

| Field | Type | Rules |
|---|---|---|
| `id` | BIGINT | Internal primary key |
| `task_key` | VARCHAR(80) | Unique opaque identifier |
| `attempt_id` | BIGINT | Unique one-to-one relationship |
| `tenant_id` / `agent_id` | BIGINT | Used for quota enforcement |
| `status` | VARCHAR(24) | Same execution states as attempt plus `CANCELLED` if future cancellation is added |
| `queue_sequence` | BIGINT | Stable FIFO order within tenant |
| `claimed_by` | VARCHAR(120) nullable | Worker instance identifier |
| `lease_until` / `heartbeat_at` | DATETIME(6) nullable | Crash/restart recovery |
| `available_at` | DATETIME(6) | Earliest claim time |
| `started_at` / `finished_at` | DATETIME(6) nullable | UTC |
| `created_at` / `updated_at` | DATETIME(6) | UTC |

**Constraints/indexes**:

- Unique `task_key` and unique `attempt_id`.
- Claim index `(status, available_at, queue_sequence)`.
- Quota indexes `(tenant_id, status)` and `(tenant_id, agent_id, status)`.
- Lease index `(status, lease_until)`.

Worker claim transactions lock rows in the stable order Tenant -> Agent -> RecoveryTask, count active rows, and claim only when both quotas permit. This prevents two workers from simultaneously passing a count check.

## Existing entity changes

### Agent and Workflow

Add nullable/default-empty `recovery_policy` JSON containing only bounded overrides:

- `sync_wait_ms`
- `step_timeout_ms`
- `overall_timeout_ms`
- `per_agent_concurrency`
- `per_tenant_concurrency`
- `tenant_queue_capacity`

Unknown keys are ignored for forward compatibility; invalid values fail write validation. These settings affect newly created recovery tasks only.

### Existing Trace and Workspace

- `Trace` remains the detailed display trace and is linked from `ExecutionRecord.trace_id`.
- Workspace session/checkpoint JSON remains readable for compatibility and debugging.
- A corrupted workspace file is reported as a diagnostic condition; it is not silently treated as an absent authoritative checkpoint.

## State transitions

### ExecutionRecord

```text
RUNNING -> SUCCEEDED
RUNNING -> FAILED
RUNNING -> TIMED_OUT
RUNNING -> MANUAL_REQUIRED
```

Terminal states never return to `RUNNING`; a recovery creates a new `ExecutionRecord` with `execution_kind=RECOVERY`.

### FailureCase

```text
RESUMABLE -------> RECOVERING -------> RECOVERED (terminal)
    ^                    |
    |                    +-----------> RESUMABLE
    |                    |
    |                    +-----------> MANUAL_REQUIRED
    |                                      |
    +---------- admin forced recovery -----+
```

- `RECOVERED` is irreversible.
- `MANUAL_REQUIRED -> RECOVERING` requires `tenant:admin`, a reason, and risk acknowledgement.
- Any illegal transition returns conflict and creates no attempt/task.

### RecoveryAttempt / RecoveryTask

```text
QUEUED -> RUNNING -> SUCCEEDED
                  -> FAILED
                  -> TIMED_OUT
                  -> MANUAL_REQUIRED
```

A queue-capacity rejection happens before attempt/task creation.

## Integrity and secret handling

- Canonical JSON serialization is used before SHA-256 calculation; verification occurs before every recovery.
- Request snapshots include the original business payload but exclude authentication headers/cookies and server credentials.
- Configuration snapshots contain logical values and credential references only.
- All API views are explicit DTO allowlists. The original snapshot is recursively redacted before display.
- Error summaries are sanitized before DB persistence; raw remote bodies and exception messages are not persisted in these entities.

