# Phase 0 Research: 失败 Case 检查点重拾

## Existing project audit

| Area | Finding | Evidence |
|---|---|---|
| Java / Spring Boot | Java 21; Spring Boot parent 3.5.6 | `pom.xml` properties and parent |
| Agent runtime | AgentScope core, OpenAI, DashScope and A2A extensions at 2.0.3 | `pom.xml` AgentScope dependencies |
| HTTP clients | JDK `java.net.http.HttpClient`; no shared retry abstraction | `HttpAgentRuntime`, `McpStreamClient`, `EmbeddingClient`, `HttpRerankClient`, `GraphRagService`, `MemorySummarizer`, `MultiAgentDebugService`, `ObservabilityService`, `OpenCliRuntime`, `EvalScorers` |
| Direct external Agent | POST, 10-second connect timeout, configurable 5–120 second request timeout, no retry | `HttpAgentRuntime.call` and `clampTimeout` |
| MCP HTTP | JDK client, 10-second connect timeout, operation timeout supplied by caller, no general retry | `McpStreamClient.send` |
| A2A | AgentScope `JdkA2AHttpClient`; cached executors and 5-minute outer timeout | `A2aGateway` |
| Error envelope | `ApiException` mapped to `{"detail": ...}`; validation uses 422 detail array | `ApiException`, `GlobalExceptionHandler` |
| Error leakage risk | Unknown exceptions currently return raw `Exception#getMessage()` | `GlobalExceptionHandler.handleOther` |
| Async foundation | Scheduling enabled; evaluation worker and knowledge index provide existing patterns, but recovery needs durable atomic claims and bounded execution | `AgentForgeApplication`, `EvalService`, `KnowledgeIndexService` |
| Persistence | Spring Data JPA/MySQL plus manual startup schema migrator; workspace JSON checkpoints are file based | repositories, `SchemaMigrator`, `WorkspaceStore` |
| Tests | `spring-boot-starter-test`, mostly lightweight JUnit 5; no current WireMock/MockWebServer/Testcontainers | `pom.xml`, `src/test/java` |
| DB pool | Hikari maximum pool size is 2 | `application.yml` |

The audit found no Spring Retry, Resilience4j, Failsafe, `@Retryable`, `@Async`, shared limiter, or managed recovery executor. These are design inputs rather than unresolved clarifications.

## Decision 1: Retain the existing runtime stack

**Decision**: Use Java 21, Spring Boot 3.5.6, AgentScope 2.0.3, Spring MVC, Spring Data JPA, MySQL, Jackson, SLF4J, and JDK `java.net.http.HttpClient`.

**Rationale**: These are the versions and libraries already declared in `pom.xml`. The repository contains no common retry library, and its external integrations consistently use JDK `HttpClient`.

**Alternatives considered**:

- Spring Retry or Resilience4j: rejected for the production path because the required policy is small, must be explicitly gated by call semantics, and a generic annotation/interceptor could retry side effects accidentally.
- Replacing JDK HTTP with WebClient/OkHttp: rejected because it would broaden the migration without improving the required correctness guarantees.

## Decision 2: Separate transport retry from checkpoint recovery

**Decision**: Implement short, bounded third-party transport retry inside the third-party adapter layer. Implement Failure Case recovery separately as a durable workflow that resumes from a checkpoint.

**Rationale**: A network retry repeats one external step; a recovery continues a multi-step Agent execution. Treating them as the same mechanism would risk replaying previously completed tools or entire ReAct turns.

**Alternatives considered**:

- Retry the whole controller/service call: rejected because completed steps and side effects would be repeated.
- Retry the entire AgentScope `ReActAgent.call`: rejected because the call may already have invoked tools or sub-agents.

## Decision 3: Use a small explicit JDK HTTP retry executor

**Decision**: Add `ThirdPartyHttpExecutor` plus typed `RetryPolicy`, `HttpRequestSemantics`, and `ThirdPartyCallException`. Callers must declare operation semantics and policy; there is no transparent global retry interceptor.

**Rationale**: Explicit call-site semantics make accidental side-effect retries visible in code review. The component can reuse JDK `HttpClient`, respect time budgets, classify errors once, and provide consistent sanitized telemetry.

**Alternatives considered**:

- Copy retry loops into each client: rejected because status lists, backoff, logging, timeout accounting, and interruption handling would diverge.
- Global HTTP interception: rejected because the current clients are constructed locally and not all POST operations are safe.

## Decision 4: Retry only an allowlist of transient failures

**Decision**: Default HTTP allowlist is 408, 425, 429, 500, 502, 503, and 504. Default transport allowlist is connection establishment timeout/refusal, reset, and temporary network unavailability. `Retry-After` is honored within the remaining budget.

**Rationale**: These failures can be temporary. An allowlist satisfies the requirement that only named transient errors are retried and makes tests deterministic.

**Never retried**: interruption/cancellation, TLS/certificate errors, malformed URI, protocol/JSON parsing, 400/401/403/404/409/412/422, credential/configuration errors, explicit business failures, or any call whose step/overall time budget is exhausted.

**Alternatives considered**:

- Retry every 5xx/IOException: rejected because many are deterministic and an overly broad rule hides defects.
- Retry no 500 responses: considered safer but rejected as the default because compute-style upstreams commonly use 500 for temporary internal failures; idempotency gating still applies and the list remains configurable.

## Decision 5: Make idempotency a mandatory retry gate

**Decision**: Classify every third-party operation as `SAFE`, `IDEMPOTENT`, or `SIDE_EFFECTING_OR_UNKNOWN`.

- `SAFE`: read-only probes, MCP initialize/list, and pure compute calls may retry.
- `IDEMPOTENT`: POST/other calls retry only when the downstream contract accepts a stable idempotency key.
- `SIDE_EFFECTING_OR_UNKNOWN`: one attempt by default.

**Rationale**: An HTTP status or timeout cannot prove that a remote side effect did not occur. A stable idempotency key derived from execution/case/step identity is reused on every attempt.

**Alternatives considered**:

- Infer safety from HTTP method alone: rejected because current POST endpoints include both pure computation and side-effecting Agent/tool calls.
- Let administrators globally enable POST retry: rejected because it creates an unsafe cross-integration switch.

## Decision 6: Use bounded configurable retry parameters

**Decision**: Platform defaults are 3 total attempts, 200 ms initial delay, multiplier 2, 2 s maximum delay, 20% jitter, and 5 s maximum accepted `Retry-After`. `maxAttempts` is constrained to 1–5 and delays to safe ranges. HTTP Agent/MCP configuration may narrow or override within platform maxima.

**Rationale**: The values allow brief recovery without materially consuming the 5-minute step budget. Bounds prevent an endpoint configuration from turning a request into an unbounded worker.

**Alternatives considered**:

- Fixed constants: rejected because the user explicitly requires configurable parameters.
- Runtime changes affecting an active call: rejected because behavior would be non-reproducible; effective policy is frozen at call/task start.

## Decision 7: Persist recovery lifecycle in MySQL

**Decision**: Add normalized execution, failure case, checkpoint, recovery attempt, and recovery task records. Database rows and constraints are authoritative; workspace JSON is a projection.

**Rationale**: Failure-rate queries, tenant filtering, one-active-attempt guarantees, durable queueing, leases, restart recovery, and audit history require queryable transactional state. `WorkspaceStore` currently maps unreadable/missing JSON to the same `null` result and cannot coordinate concurrent workers.

**Alternatives considered**:

- Keep all state in workspace files: rejected because file-level atomic replace does not provide cross-record transactions, query indexes, leases, or safe multi-worker claims.
- Store the complete lifecycle in one JSON entity: rejected because atomic state transitions and reporting would be fragile.

## Decision 8: Reuse AgentScope checkpoint content through an adapter

**Decision**: Translate the existing AgentScope checkpoint fields (`working`, `pending_tools`, `done_tool_ids`, step, next, usage, traces, error) into append-only `ExecutionCheckpoint` records. The DB write occurs first; workspace projection follows best-effort.

**Rationale**: The current runtime already captures the minimum state required to skip completed tool calls. An adapter minimizes changes to the AgentScope loop while making state durable and auditable.

**Alternatives considered**:

- Replace AgentScope runtime with another graph engine: rejected as outside scope and contrary to stack reuse.
- Continue treating workspace as authoritative: rejected for concurrency/restart reasons.

## Decision 9: Use a persistent leased queue with bounded workers

**Decision**: `RecoveryWorker` uses the existing Spring scheduling capability to claim DB tasks using atomic state transitions and leases, then runs them in a Spring-managed bounded executor. It enforces DB-backed per-Agent/per-tenant limits.

**Rationale**: The repository already enables scheduling. A durable leased queue survives process restart, while a bounded executor prevents the unbounded-thread behavior of the existing A2A cached pools from spreading to recovery.

**Alternatives considered**:

- `@Async` or an in-memory queue only: rejected because queued/running state would be lost on restart and concurrency would not be safe across nodes.
- Add Redis/Kafka: rejected because MySQL already exists and the specified scale does not justify a new production service.

## Decision 10: Keep transactions short

**Decision**: Never hold a transaction or DB connection across external/model/tool work. Claim/read, checkpoint append, heartbeat, and terminal-state writes each use short transaction boundaries.

**Rationale**: The current Hikari pool maximum is 2. A long transaction per recovery would starve normal requests and magnify lock contention.

**Alternatives considered**:

- One transaction for the entire recovery: rejected because external calls can last minutes and cannot be rolled back atomically anyway.

## Decision 11: Freeze logical configuration, resolve current credentials

**Decision**: Store failure-time logical Agent/workflow/model/tool configuration and hashes, reusing `AgentRelease` where possible. Store only credential references; resolve their current protected values at recovery time. Operational timeout/retry/concurrency settings are frozen when a recovery task is created.

**Rationale**: This preserves execution semantics without copying secrets or letting later edits silently change a recovery.

**Alternatives considered**:

- Always use live configuration: rejected because the action would become a new request rather than recovery.
- Snapshot credential values: rejected as a secret-retention risk.

## Decision 12: Preserve the existing API error contract

**Decision**: Continue using `ApiException` and `GlobalExceptionHandler` with `{"detail": string|array}`. Add safe mappings for rate limit, gateway timeout, and upstream unavailable. Replace raw unknown-exception messages with a generic message and correlation identifier.

**Rationale**: `app.js` depends on the FastAPI-compatible `detail` shape. Sanitizing content fixes leakage without breaking the envelope.

**Alternatives considered**:

- Introduce a new RFC 7807 response: rejected because it would break current frontend expectations.

## Decision 13: Centralize sanitization and structured diagnostics

**Decision**: A shared sanitizer processes exception summaries and any diagnostic payload before persistence or logging. Logs contain identifiers, operation, host, status, attempt, elapsed time, category, and state transition—never raw request bodies, headers, full remote responses, model contents, or unsanitized exception text.

**Rationale**: Existing clients use inconsistent clipping/masking, and `GlobalExceptionHandler` currently exposes raw exception messages.

**Alternatives considered**:

- Rely on callers to redact: rejected because one missed caller can leak credentials.

## Decision 14: Test against real MySQL semantics

**Decision**: Use existing JUnit/Mockito/MockMvc and JDK `HttpServer` for most tests. Add test-scope Testcontainers MySQL for repository locks, JSON fields, unique constraints, leases, and concurrent claim integration tests.

**Rationale**: These are MySQL-specific correctness properties. H2 is not a reliable substitute, while Testcontainers does not change the production stack.

**Alternatives considered**:

- H2: rejected due to different locking, JSON, and SQL behavior.
- Shared developer MySQL only: rejected as non-isolated and unsuitable for repeatable CI.

