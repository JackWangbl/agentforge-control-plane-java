# Third-Party Retry Policy Contract

This contract applies only inside third-party integration adapters. Controllers and business services do not run transport retry loops.

## Required call declaration

Every call declares:

- operation name and remote host (never the full secret-bearing URL);
- `SAFE`, `IDEMPOTENT`, or `SIDE_EFFECTING_OR_UNKNOWN` semantics;
- total step deadline and remaining overall recovery deadline;
- effective retry policy;
- stable idempotency key and downstream header/field name when semantics are `IDEMPOTENT`;
- response parser that runs only after transport success.

Missing semantics default to `SIDE_EFFECTING_OR_UNKNOWN`.

## Default policy

| Parameter | Default | Bounds |
|---|---:|---:|
| Total attempts including first | 3 | 1–5 |
| Initial delay | 200 ms | 0–5,000 ms |
| Multiplier | 2.0 | 1.0–4.0 |
| Maximum delay | 2,000 ms | 0–5,000 ms |
| Jitter | 20% | 0–50% |
| Maximum accepted `Retry-After` | 5,000 ms | 0–30,000 ms |
| HTTP statuses | 408, 425, 429, 500, 502, 503, 504 | Explicit allowlist only |

The next attempt is skipped if its delay plus expected request budget would exceed the remaining step or overall deadline.

## Retry decision matrix

| Failure | SAFE | IDEMPOTENT with stable downstream key | SIDE_EFFECTING/UNKNOWN |
|---|---|---|---|
| Connection refused/connect timeout before request | Retry | Retry | No retry by default |
| Connection reset/temporary unavailable | Retry | Retry | No retry |
| Read/request timeout | Retry within budget | Retry within budget | No retry |
| Allowed HTTP status | Retry | Retry | No retry |
| 400/401/403/404/409/412/422 | No retry | No retry | No retry |
| TLS/certificate/protocol/parse error | No retry | No retry | No retry |
| Interrupted/cancelled | Stop immediately and restore interrupt | Same | Same |
| Explicit business failure | No retry | No retry | No retry |

## Existing adapter classification

| Adapter/operation | Default semantics | Notes |
|---|---|---|
| HTTP Agent chat/tool | SIDE_EFFECTING_OR_UNKNOWN | Enable only when endpoint configuration names a supported idempotency header |
| A2A message | SIDE_EFFECTING_OR_UNKNOWN | Existing call/task ID is correlation only until downstream idempotency is guaranteed |
| MCP initialize, tools/list | SAFE | May retry transient transport failures |
| MCP tools/call | SIDE_EFFECTING_OR_UNKNOWN | Per-tool idempotency declaration required for retry |
| Embedding and rerank | SAFE | Pure computation; duplicate billing is bounded by attempts |
| Model-only route classification/summary | SAFE | Retry the isolated model request, never a whole ReAct turn |
| AgentScope ReAct turn | Not eligible as a retry unit | It may already have executed tools |
| Observability export | SAFE best-effort | Retry must not block or fail the business request |
| OpenCLI/browser query | SAFE only for declared read operations | Navigation, click, eval and command execution default one-shot |

## Idempotency key rules

- Derived deterministically from tenant, execution/case, checkpoint step and logical operation.
- Identical across all transport attempts for the same step.
- Never contains user input or credentials.
- Sent only through a configured downstream-supported header/field.
- Recorded as a hash/reference in checkpoints and logs, not as sensitive request data.
- A configured key without an explicit downstream support declaration does not upgrade a call to `IDEMPOTENT`.

## Exhaustion and exception mapping

The call layer emits a typed failure containing only:

- safe category;
- final HTTP status when present;
- transient classification;
- attempt count;
- elapsed time;
- whether retry was blocked by idempotency semantics;
- sanitized summary and original cause retained for internal diagnostics only.

Callers preserve existing outward behavior:

- HTTP Agent conversation returns `ChatReply` with `mode=error`.
- Tool adapters return their existing error JSON structure.
- REST boundaries convert to `ApiException` while keeping `{"detail": ...}`.
- No raw headers, body, full response, credential, model content, or unsanitized exception text is logged or returned.

