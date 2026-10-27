# Validation Quickstart: 失败 Case 检查点重拾

This guide defines the validation workflow for the future implementation. It does not contain implementation code.

## Prerequisites

- Java 21
- Maven 3.9+
- Docker for Testcontainers-based MySQL integration tests
- No production API keys are required; all third-party behavior uses local test stubs

## Test commands

```bash
mvn test
```

Expected: unit, retry-policy, sanitizer, state-machine, coordinator, worker, and MockMvc contract tests pass without external services.

```bash
mvn -Pintegration-test verify
```

Expected: a disposable MySQL container starts; repository locking, queue leases, entry-point recording, restart recovery, and cross-tenant security scenarios pass.

## Validation sequence

### 1. Error contract compatibility

Run `FailureCaseControllerTest` and existing controller tests.

Verify:

- API errors still contain exactly a `detail` string or validation array.
- Queue full maps to 429, active/recovered conflict to 409, unauthorized force to 403, and time budget exhaustion to the documented safe error.
- Unknown exceptions do not expose the injected secret or raw exception message.

### 2. Third-party transient retry

Run `ThirdPartyHttpExecutorTest` against a local JDK `HttpServer`.

Verify:

- A SAFE request receiving 503 then 200 succeeds on the second request.
- 429 honors bounded `Retry-After`.
- 400/401/403/404/409/412/422 and malformed JSON are attempted once.
- Interruption restores the interrupt flag and stops immediately.
- Backoff and all attempts remain within the step/overall deadline.

### 3. Side-effect protection

Use a stub that counts POST requests.

Verify:

- External Agent/A2A/MCP tool calls classified as unknown receive exactly one request even on 503 or read timeout.
- An explicitly idempotent POST retries only when a downstream-supported idempotency header is configured.
- Every attempt uses the same idempotency key.
- Retrying a whole AgentScope ReAct turn is impossible through the shared executor API.

### 4. Checkpoint correctness

Run `CheckpointServiceTest` and `FailureCaseStateMachineTest`.

Verify:

- Confirmed tool IDs are not executed again.
- Failed/not-started safe steps resume; unknown side-effect steps become manual-required.
- Request/config/state hash mismatches prevent execution.
- Recovered is terminal; failed recovery returns to resumable only with a new safe checkpoint.

### 5. Hybrid response and thread release

Run `RecoveryWorkerTest` with controllable time.

Verify:

- Completion before 3 seconds returns the terminal response.
- Work exceeding 3 seconds returns 202 and continues as the same task.
- No second submission or step restart occurs during the transition.
- External work executes outside transactions and does not retain a DB connection.

### 6. Concurrency and queue limits

Run `RecoveryConcurrencyIntegrationTest` against MySQL.

Verify:

- Concurrent recovery requests for one Case create one active attempt/task.
- Active counts never exceed Agent 2 or tenant 10 defaults.
- The 101st queued tenant task receives 429 and creates no attempt/task.
- Multiple worker instances cannot over-claim because Tenant -> Agent -> Task locks are acquired in a fixed order.

### 7. Restart and lease recovery

Run `RecoveryRestartIntegrationTest`.

Verify:

- An expired task with a confirmed safe checkpoint is requeued once.
- An expired task with unknown side-effect outcome becomes manual-required.
- A successfully recovered Case is never requeued or recoverable.

### 8. Entry-point coverage

Run `FailureCaseEntryPointIntegrationTest`.

Verify one failed execution from each source:

- Playground
- direct Agent invoke
- workflow debug
- workflow invoke
- A2A

Each must create a tenant-scoped original ExecutionRecord, one FailureCase, a sanitized failure reason, a correct entry-point label, and an appropriate checkpoint state.

### 9. Security and log inspection

Run `FailureCaseSecurityIntegrationTest` with sentinel API keys, Authorization values, nested tokens, tool arguments and remote response bodies.

Verify:

- Cross-tenant list/detail/task lookups return not found.
- Ordinary users cannot force unknown side effects.
- API responses, database safe-summary fields, captured logs and workspace projections contain none of the sentinels.
- Frontend rendering paths pass all user-controlled fields through `escapeHtml`.

## Manual UI acceptance

1. Open the Failure Case page with a user holding `trace:read`.
2. Filter by time, entry point, Agent and status; confirm the rate and page use the same filters.
3. Open a Case and inspect the redacted immutable input and attempt history.
4. Start recovery with a user holding `session:write`; observe immediate completion or task polling after 3 seconds.
5. Confirm a successful Case has no recovery action and direct repeat requests return conflict.
6. Sign in as tenant administrator and confirm forced recovery requires both a reason and explicit risk acknowledgement.

