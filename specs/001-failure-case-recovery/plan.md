# Implementation Plan: 失败 Case 检查点重拾

**Branch**: `001-failure-case-recovery` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-failure-case-recovery/spec.md`

## Summary

在现有 Java 21、Spring Boot 3.5.6、AgentScope 2.0.3、Spring Data JPA、MySQL 和 JDK `HttpClient` 技术栈上增加统一 Failure Case 管理与检查点重拾。MySQL 作为 Case、检查点、并发占有、持久任务和审计的权威状态；现有 workspace JSON 继续作为会话与调试投影。

“第三方传输重试”与“从失败检查点重拾”分层处理：前者仅在第三方调用适配层内，针对白名单临时错误和明确安全/幂等请求进行有限重试；后者通过检查点、持久队列、租约和状态机从最近安全步骤继续。带副作用或结果未知的调用默认不重试、不自动重放。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.6 (`spring-boot-starter-web`, `data-jpa`, `validation`), AgentScope Java 2.0.3, JDK `java.net.http.HttpClient`, Jackson, SLF4J

**Storage**: MySQL via Spring Data JPA/Hibernate; Agent workspace JSON remains a non-authoritative diagnostic projection

**Testing**: JUnit Jupiter, Mockito, AssertJ, MockMvc from `spring-boot-starter-test`; JDK `HttpServer` for HTTP stubs; Testcontainers MySQL as a test-scope addition for locking/JSON/transaction integration tests

**Target Platform**: Linux/macOS server process running Spring MVC and MySQL; Java 21 runtime

**Project Type**: Single Spring Boot web service with static HTML/JavaScript frontend

**Performance Goals**: recovery start request releases the servlet thread by 3 seconds; per-agent active recovery <= 2; per-tenant active recovery <= 10; per-tenant queued recovery <= 100; failure list reflects accepted failures within 1 minute

**Constraints**: step timeout 5 minutes by default; overall recovery timeout 30 minutes; do not hold a DB transaction/connection during model, tool, A2A, MCP, or HTTP calls; preserve `{"detail": ...}` error envelopes; never log raw input, full model content, credentials, or unsanitized remote errors

**Scale/Scope**: tenant-isolated console covering Playground, direct Agent invoke, workflow debug/invoke, and A2A entry points; evaluation-case resume remains separate

## Constitution Check

*GATE: Must pass before Phase 0 research and was re-checked after Phase 1 design.*

The repository constitution is still an unfilled template and contains no enforceable project-specific gates. The repository `AGENTS.md` requirements are therefore used as the active engineering gates:

- **PASS — Thin routes**: `FailureCaseController` only validates/maps HTTP requests; orchestration lives in `recovery` services.
- **PASS — Existing data style**: persistence uses Spring Data JPA repositories and the existing entity/converter conventions; no SQLAlchemy concern applies to this Java project.
- **PASS — Frontend safety**: all Failure Case fields rendered by `app.js` must pass through `escapeHtml`; JSON details are never interpolated as trusted HTML.
- **PASS — Secret safety**: configuration snapshots store credential references, never API keys/passwords; responses and logs use a shared sanitizer.
- **PASS — Tests required**: unit, MVC contract, HTTP integration, MySQL transaction/concurrency, restart/lease, and security scenarios are defined in [quickstart.md](quickstart.md).
- **PASS — Minimal production dependencies**: no Spring Retry/Resilience4j dependency is introduced; the existing JDK HTTP stack is reused. Testcontainers is test-only and justified by MySQL-specific locking, JSON, and unique-constraint behavior.

Post-design check: all gates remain satisfied. No constitution exception is required.

## Architecture

### Layer boundaries

1. **HTTP/UI layer**: exposes Failure Case list/detail/recover/task-status contracts and preserves the existing exception envelope.
2. **Recovery application layer**: records executions, classifies failures, controls Case lifecycle, creates durable recovery attempts/tasks, and resumes from checkpoints.
3. **Checkpoint/runtime adapters**: translate AgentScope `working`, `pending_tools`, `done_tool_ids`, step and error state into append-only database checkpoints; workspace files receive a diagnostic projection only.
4. **Third-party call layer**: centralizes transient-error classification, backoff, time budget, idempotency gating, safe exception shaping, and retry telemetry for JDK HTTP integrations.
5. **Persistence layer**: stores execution/case/checkpoint/attempt/task records, performs tenant-scoped queries, atomic state transitions, task leases, and concurrency counting.

### Third-party retry policy

- Default retryable HTTP statuses: `408`, `425`, `429`, `500`, `502`, `503`, `504`.
- Default retryable transport failures: connection establishment timeout/refusal, connection reset, and temporary network unavailability. `InterruptedException`, cancellation, TLS/certificate failure, protocol/JSON errors, authentication failures, validation failures, and deterministic business errors are never retried.
- A read timeout is retryable only for `SAFE` or explicitly `IDEMPOTENT` operations; it is not proof that a side-effecting request did not execute.
- Request semantics are explicit: `SAFE`, `IDEMPOTENT`, or `SIDE_EFFECTING_OR_UNKNOWN`.
- `IDEMPOTENT` POST retry requires a stable idempotency key accepted by the downstream service. The key is derived from execution/case/step identity and is reused for every transport attempt.
- External Agent chat, A2A messages, MCP `tools/call`, browser actions, OpenCLI mutations, and unknown POST requests default to `SIDE_EFFECTING_OR_UNKNOWN` and receive one attempt.
- Model-only computation, embedding/rerank, MCP handshake/list, and read-only probes may be marked safe. A complete AgentScope ReAct turn is never retried as one unit because it may already have executed tools.
- Defaults: 3 total attempts, 200 ms initial delay, exponential multiplier 2, 2 s maximum delay, 20% jitter, and `Retry-After` honored within the remaining step/overall time budget. Limits are validated and frozen at call/task start.

### Recovery execution and thread model

- `RecoveryCoordinator` validates permissions and immutable hashes, atomically moves a Case to recovering, creates an attempt/task, and signals the worker in a short transaction.
- `RecoveryWorker` polls/claims durable tasks with a lease using the existing scheduling capability. A bounded Spring-managed executor runs claimed work; no unbounded cached pool is introduced.
- The initiating controller waits for the same task for at most 3 seconds. Completion returns `200`; otherwise it returns `202` with task identity while the exact same task continues.
- External/model/tool work runs outside database transactions. Each checkpoint/heartbeat/status transition uses a separate short transaction, which is required because the current Hikari pool has only two connections.
- DB state, not in-memory semaphores, is authoritative for per-Agent/per-tenant concurrency. In-memory signals are an optimization for the 3-second response only.
- Expired running leases are scanned after restart. A task is requeued only from a confirmed safe checkpoint; otherwise it becomes manual-required.

### Configuration and effective-time rules

- Platform defaults are bound through the existing `AppSettings`/`application.yml`; environment/YAML changes take effect after application restart.
- Agent/workflow recovery overrides are stored as `recovery_policy` JSON and take effect only for newly created recovery tasks.
- HTTP Agent and MCP retry overrides reuse their existing `config` JSON. Invalid or out-of-range overrides fail validation rather than silently expanding retry risk.
- Effective timeout, concurrency, retry, and idempotency settings are copied into the attempt/task at creation. Later configuration changes never alter an in-flight task.
- Logical Agent/workflow configuration comes from the failure-time snapshot/release. Secrets are resolved from their current protected reference at execution time and are not copied into snapshots.

### Compatibility and migration

- Existing `/api/playground/resume` remains as a compatibility adapter that delegates to `RecoveryCoordinator`; it no longer permits ordinary users to bypass tool idempotency through `force_rerun_tools`.
- Existing success and validation responses are unchanged. New errors continue to use `ApiException` and `GlobalExceptionHandler` with the exact `{"detail": string|array}` envelope.
- Unknown internal errors return a generic sanitized detail plus a correlation identifier; raw `Exception#getMessage()` is not returned to users.
- Existing workspace checkpoints are readable during a transition period. New/updated executions write authoritative DB checkpoints first and then best-effort workspace projections.

## Project Structure

### Documentation (this feature)

```text
specs/001-failure-case-recovery/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── failure-cases-api.md
│   └── third-party-retry-policy.md
└── tasks.md                  # generated later by $speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/com/agentforge/controlplane/
├── config/                         # settings, managed worker executor, schema migration
├── domain/                         # execution/case/checkpoint/attempt/task entities
├── repo/                           # tenant queries, atomic claims, metrics
├── recovery/                       # recording, state machine, coordinator, worker, sanitizer
├── runtime/http/                   # shared JDK HttpClient retry adapter and policy types
├── dto/FailureCaseDtos.java
├── web/FailureCaseController.java
├── agent/                          # AgentScope, HTTP Agent, A2A, workflow integration points
├── playground/                     # common turn finalization integration
└── workspace/                      # non-authoritative diagnostic projection

src/main/resources/
├── application.yml
└── static/
    ├── index.html
    ├── app.js
    └── failure-case.css

src/test/java/com/agentforge/controlplane/
├── recovery/                       # state machine, coordinator, worker, sanitizer tests
├── runtime/http/                   # retry and idempotency tests
├── web/                            # MockMvc response compatibility tests
└── integration/                    # MySQL, entry-point, restart, concurrency, security tests
```

**Structure Decision**: retain the single-module Spring Boot layout. Add a focused `recovery` package for business orchestration and `runtime/http` for third-party transport behavior. Explicit DTOs are preferred over extending reflection-based `ResourceDumper` for sensitive Failure Case data.

## Proposed File Changes

### New production files

- `domain/ExecutionRecord.java`
- `domain/FailureCase.java`
- `domain/ExecutionCheckpoint.java`
- `domain/RecoveryAttempt.java`
- `domain/RecoveryTask.java`
- `repo/ExecutionRecordRepository.java`
- `repo/FailureCaseRepository.java`
- `repo/ExecutionCheckpointRepository.java`
- `repo/RecoveryAttemptRepository.java`
- `repo/RecoveryTaskRepository.java`
- `recovery/ExecutionRecorder.java`
- `recovery/CheckpointService.java`
- `recovery/FailureCaseService.java`
- `recovery/RecoveryCoordinator.java`
- `recovery/RecoveryWorker.java`
- `recovery/RecoveryStateMachine.java`
- `recovery/FailureClassifier.java`
- `recovery/SensitiveDataSanitizer.java`
- `runtime/http/ThirdPartyHttpExecutor.java`
- `runtime/http/RetryPolicy.java`
- `runtime/http/ThirdPartyCallException.java`
- `runtime/http/HttpRequestSemantics.java`
- `config/RecoveryExecutorConfig.java`
- `dto/FailureCaseDtos.java`
- `web/FailureCaseController.java`
- `resources/static/failure-case.css`

### Existing production files to modify

- `pom.xml`: add test-scope Testcontainers JUnit/MySQL dependencies only.
- `config/AppSettings.java`, `resources/application.yml`: recovery/retry settings and validation.
- `config/SchemaMigrator.java`: new tables, columns, unique constraints, filtering and lease indexes.
- `domain/Agent.java`, `domain/Workflow.java`, `dto/ApiDtos.java`, `web/ResourceController.java`: optional `recovery_policy` overrides.
- `agent/AgentScopeRuntime.java`: authoritative checkpoint adapter, step budgets, and removal of ordinary force-tool bypass.
- `workspace/WorkspaceStore.java`: write/read projection and explicit corrupted-checkpoint diagnostics.
- `playground/PlaygroundService.java`: execution lifecycle recording and failure-case creation.
- `web/PlaygroundController.java`: compatibility delegation for `/resume`.
- `agent/BoundAgentRuntime.java`: direct invoke lifecycle recording.
- `agent/MultiAgentDebugService.java`: workflow execution identity and node checkpoints.
- `agent/A2aGateway.java`: A2A entry identity propagation and non-idempotent retry declaration.
- `agent/HttpAgentRuntime.java`, `runtime/McpStreamClient.java`: use the shared third-party call executor with explicit semantics.
- Relevant safe-compute/read-only clients (`rag/EmbeddingClient.java`, `rag/HttpRerankClient.java`, `rag/GraphRagService.java`, `memory/MemorySummarizer.java`, `eval/EvalScorers.java`, `observability/ObservabilityService.java`, and read-only OpenCLI calls): adopt the shared executor incrementally; mutation calls remain one-shot unless explicitly idempotent.
- `web/ApiException.java`, `web/GlobalExceptionHandler.java`: new 429/504 helpers and sanitized internal-error detail while preserving the envelope.
- Existing permission strings are reused without catalog changes: `trace:read` for list/detail, `session:write` for recovery, and `tenant:admin` for forced recovery. Authorization is enforced explicitly by `FailureCaseService` because sensitive DTOs do not use generic `ResourceDumper` routing.
- `resources/static/index.html`, `resources/static/app.js`: module navigation, metrics, filters, escaped detail, task polling, recovery/force actions.

### New tests

- `recovery/FailureCaseStateMachineTest.java`
- `recovery/RecoveryCoordinatorTest.java`
- `recovery/RecoveryWorkerTest.java`
- `recovery/CheckpointServiceTest.java`
- `recovery/SensitiveDataSanitizerTest.java`
- `runtime/http/ThirdPartyHttpExecutorTest.java`
- `web/FailureCaseControllerTest.java`
- `integration/FailureRecoveryIntegrationTest.java`
- `integration/RecoveryConcurrencyIntegrationTest.java`
- `integration/RecoveryRestartIntegrationTest.java`
- `integration/FailureCaseSecurityIntegrationTest.java`
- `integration/FailureCaseEntryPointIntegrationTest.java`

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Test-only Testcontainers dependency | MySQL row locking, JSON columns, unique constraints, and lease races must be verified against MySQL semantics | H2 can produce false confidence for MySQL concurrency/JSON behavior; a shared external test DB is not isolated or repeatable |
| Five persisted lifecycle entities | Execution facts, Failure Case state, append-only checkpoints, audit attempts, and leased queue tasks have different lifecycle and uniqueness rules | Combining them into one JSON row makes filtering, atomic claims, audit history, and restart recovery unsafe |

