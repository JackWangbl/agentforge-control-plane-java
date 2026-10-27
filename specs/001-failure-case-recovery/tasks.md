---

description: "失败 Case 检查点重拾功能的依赖有序实施任务"
---

# Tasks: 失败 Case 检查点重拾

**Input**: Design documents from `/specs/001-failure-case-recovery/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Tests**: 本功能明确要求单元测试、MVC 契约测试、第三方 HTTP 集成测试和 MySQL 并发/重启/安全集成测试。每个故事的测试任务必须先编写并确认在实现前失败。

**Organization**: 任务按用户故事分组；共享持久化、安全、状态机和执行器能力放在基础阶段，完成后各故事可按依赖并行推进。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可在不同文件上并行执行，且不依赖同阶段尚未完成的任务
- **[Story]**: 对应 `spec.md` 中的用户故事
- 每个任务均包含明确文件路径

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 补齐测试依赖、统一配置入口及测试运行约定，不改变现有业务行为。

- [ ] T001 在 `pom.xml` 增加仅限 test scope 的 Testcontainers JUnit Jupiter 与 MySQL 模块，并保留 Java 21、Spring Boot 3.5.6、AgentScope 2.0.3 及现有 JDK HttpClient 技术栈
- [ ] T002 [P] 在 `src/main/java/com/agentforge/controlplane/config/AppSettings.java` 声明带边界校验的恢复/传输重试平台配置：同步等待默认 3000ms、步骤默认 5 分钟、整体默认 30 分钟、Agent 并发默认 2、租户并发默认 10、租户队列默认 100、传输尝试默认 3 且所有值均允许安全范围内覆盖
- [ ] T003 [P] 在 `src/main/resources/application.yml` 增加 T002 对应的非敏感默认值和环境变量覆盖项，注明 YAML/环境配置重启后生效且不得包含 API Key、密码或数据库凭证
- [ ] T004 [P] 在 `src/test/resources/application-test.yml` 配置隔离的恢复工作线程、快速轮询/租约参数和 Testcontainers 可覆盖的数据源占位，避免单元测试依赖生产凭证或外部服务

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 建立所有用户故事共同依赖的权威数据模型、租户查询、状态迁移、脱敏、异常分类和受限工作线程。

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事实现。

- [ ] T005 [P] 创建 `src/main/java/com/agentforge/controlplane/domain/ExecutionRecord.java`，逐字段实现这些原文约束：`id` “Internal primary key”；`execution_key` “Globally unique opaque identifier”；`tenant_id / owner_id` “Tenant required; owner nullable under existing conventions”；`execution_kind` “ORIGINAL or RECOVERY”；`entry_point` “PLAYGROUND, AGENT_INVOKE, WORKFLOW_DEBUG, WORKFLOW_INVOKE, A2A”；`agent_id / agent_name` “Actual Agent that handled the execution”；`workflow_id` “Set for workflow entries”；`session_id / trace_id` “Existing correlation identifiers”；`parent_execution_id` “Recovery execution points to its failed original/recovery predecessor”；`request_snapshot_json` “Canonical request body and required non-secret context”；`request_hash` “SHA-256 over canonical snapshot bytes”；`config_snapshot_json` “Failure-time logical Agent/model/tool/workflow configuration”；`config_hash` “SHA-256 over canonical logical config”；`credential_refs_json` “Reference names/IDs only, no values”；`status` “RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MANUAL_REQUIRED”；`failure_category` “Sanitized category from the common classifier”；`failure_step` “Stable step key, not a free-form stack frame”；`error_summary` “Sanitized user-safe message”；`started_at / finished_at` “UTC; finish nullable while active”；`created_at / updated_at` “Existing timestamp convention”，并采用 `data-model.md` 指定的 VARCHAR/CHAR 长度
- [ ] T006 [P] 创建 `src/main/java/com/agentforge/controlplane/domain/FailureCase.java`，逐字段实现这些原文约束：`id` “Internal primary key”；`case_key` “Globally unique opaque identifier”；`tenant_id / owner_id` “Tenant scope and original owner”；`execution_record_id` “Unique reference to the original failed execution”；`status` “RESUMABLE, RECOVERING, MANUAL_REQUIRED, RECOVERED”；`resumable` “Derived/validated guard; never sufficient without status/checkpoint checks”；`latest_checkpoint_id` “Latest checkpoint evaluated as safe”；`failure_category / failure_step` “Current failure classification”；`error_summary` “Sanitized current summary”；`recovered_execution_id` “Final successful recovery execution”；`recovered_attempt_id` “Attempt that reached the terminal state”；`version` “Optimistic version for UI/update conflict detection”；`created_at / updated_at` “UTC”，并采用 `data-model.md` 指定的 nullable 与 VARCHAR 长度
- [ ] T007 [P] 创建 `src/main/java/com/agentforge/controlplane/domain/ExecutionCheckpoint.java`，逐字段实现这些原文约束：`id` “Internal primary key”；`checkpoint_key` “Unique opaque identifier”；`tenant_id` “Must match execution/case tenant”；`execution_record_id / failure_case_id` “Execution required; Case nullable until failure materializes”；`sequence_no` “Strictly increasing within an execution”；`step_key / next_step_key` “Stable execution-boundary identifiers”；`outcome` “CONFIRMED, FAILED, NOT_STARTED, UNKNOWN”；`safe_to_resume` “False for unknown/unsafe side effects”；`side_effect_semantics` “NONE, IDEMPOTENT, SIDE_EFFECTING, UNKNOWN”；`idempotency_key` “Stable downstream key where supported”；`state_snapshot_json` “AgentScope working state, pending tools, completed call IDs, usage and minimal continuation state”；`state_hash` “Integrity check over canonical state”；`error_category / error_summary` “Sanitized failure information”；`created_at` “UTC and immutable”，并落实 append-only、唯一 `(execution_record_id, sequence_no)` 和 `done_tool_ids` 防重放
- [ ] T008 [P] 创建 `src/main/java/com/agentforge/controlplane/domain/RecoveryAttempt.java`，逐字段实现这些原文约束：`id` “Internal primary key”；`attempt_key` “Unique opaque identifier”；`tenant_id / failure_case_id` “Required”；`initiated_by` “User ID”；`forced` “True only for tenant administrator exception”；`force_reason` “Required when forced”；`risk_acknowledged_at` “Required when forced”；`start_checkpoint_id` “Checkpoint selected before execution”；`status` “QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MANUAL_REQUIRED”；三个 timeout 字段 “Effective values frozen at creation”；两个 policy JSON “Effective bounded settings frozen at creation”；结果字段 “Result links”；失败字段 “Sanitized attempt result”；开始/结束时间 “UTC”；创建/更新时间 “UTC”，并落实 attempt append-only 与失败后仅安全检查点可回到 RESUMABLE
- [ ] T009 [P] 创建 `src/main/java/com/agentforge/controlplane/domain/RecoveryTask.java`，逐字段实现这些原文约束：`id` “Internal primary key”；`task_key` “Unique opaque identifier”；`attempt_id` “Unique one-to-one relationship”；`tenant_id / agent_id` “Used for quota enforcement”；`status` “Same execution states as attempt plus CANCELLED if future cancellation is added”；`queue_sequence` “Stable FIFO order within tenant”；`claimed_by` “Worker instance identifier”；`lease_until / heartbeat_at` “Crash/restart recovery”；`available_at` “Earliest claim time”；`started_at / finished_at` “UTC”；`created_at / updated_at` “UTC”，并落实 `data-model.md` 指定的 nullable、VARCHAR 长度和 claim/quota/lease 索引
- [ ] T010 在 `src/main/java/com/agentforge/controlplane/config/SchemaMigrator.java` 创建五张表及 `data-model.md` 指定的唯一约束、外键和租户/状态/时间/Agent/入口/trace/session/parent/lease 索引，并给 `agents`、`workflows` 增加可空 `recovery_policy` JSON 列
- [ ] T011 [P] 创建 `src/main/java/com/agentforge/controlplane/repo/ExecutionRecordRepository.java`，提供强制 `tenant_id` 条件的详情、入口/Agent/时间查询与原始终态执行统计，失败率分母仅 `ORIGINAL` 的终态且分子排除 `SUCCEEDED`
- [ ] T012 [P] 创建 `src/main/java/com/agentforge/controlplane/repo/FailureCaseRepository.java`，提供租户隔离分页筛选，并用条件更新实现仅 `RESUMABLE` 或管理员授权的 `MANUAL_REQUIRED` 可原子迁移到 `RECOVERING`
- [ ] T013 [P] 创建 `src/main/java/com/agentforge/controlplane/repo/ExecutionCheckpointRepository.java` 和 `src/main/java/com/agentforge/controlplane/repo/RecoveryAttemptRepository.java`，提供租户隔离、append-only 历史及最新安全检查点查询
- [ ] T014 [P] 创建 `src/main/java/com/agentforge/controlplane/repo/RecoveryTaskRepository.java`，实现租户 FIFO 领取、租约续期/过期扫描、Agent/租户运行数与租户排队数统计，并确保所有锁定查询使用短事务
- [ ] T015 [P] 创建 `src/test/java/com/agentforge/controlplane/recovery/FailureCaseStateMachineTest.java`，先覆盖合法/非法迁移、失败后仅在安全检查点返回 `RESUMABLE`、成功后 `RECOVERED` 不可逆、同 Case 并发 claim 仅一方成功
- [ ] T016 [P] 创建 `src/test/java/com/agentforge/controlplane/recovery/SensitiveDataSanitizerTest.java`，先覆盖嵌套 password/API key/token/Authorization/cookie/自定义敏感字段、原始异常和二进制/超大输入的遮罩与不可恢复标记
- [ ] T017 创建 `src/main/java/com/agentforge/controlplane/recovery/RecoveryStateMachine.java`，实现 T015 的显式状态迁移、终态保护和版本冲突语义
- [ ] T018 创建 `src/main/java/com/agentforge/controlplane/recovery/SensitiveDataSanitizer.java`，实现 T016 的持久化、DTO、导出与日志前递归脱敏，且绝不返回原始 secret 或未清洗异常文本
- [ ] T019 [P] 创建 `src/main/java/com/agentforge/controlplane/recovery/FailureClassifier.java`，统一输出超时、上游依赖、模型、工具、配置/凭证、权限、完整性、并发冲突及未知内部错误类别和安全摘要
- [ ] T020 [P] 创建 `src/main/java/com/agentforge/controlplane/config/RecoveryExecutorConfig.java`，提供有界 Spring 执行器、调度器和优雅关闭；禁止使用无界线程池，且外部调用不得占用数据库事务/连接
- [ ] T021 修改 `src/main/java/com/agentforge/controlplane/domain/Agent.java`、`src/main/java/com/agentforge/controlplane/domain/Workflow.java`、`src/main/java/com/agentforge/controlplane/dto/ApiDtos.java` 和 `src/main/java/com/agentforge/controlplane/web/ResourceController.java`，读写并校验可选 `recovery_policy` JSON，使覆盖只影响新建任务且不允许超出平台安全边界
- [ ] T022 修改 `src/main/java/com/agentforge/controlplane/web/ApiException.java` 和 `src/main/java/com/agentforge/controlplane/web/GlobalExceptionHandler.java`，增加 429/504 等映射，未知内部错误仅返回通用脱敏描述和关联 ID，同时保持既有 `{"detail": string|array}` 格式

**Checkpoint**: 权威持久化、租户隔离查询、状态机、脱敏、异常映射和有界执行基础就绪；用户故事可以开始。

---

## Phase 3: User Story 1 - 查看和定位失败 Case (Priority: P1) 🎯 MVP

**Goal**: 提供失败 Case 模块、统一失败率和可按时间/入口/Agent/状态筛选的租户隔离列表。

**Independent Test**: 构造不同入口和 Agent 的 80 条成功、20 条失败及若干运行中执行；列表显示 20.0% 与 20 个 Case，组合筛选一致，跨租户访问无数据泄露。

### Tests for User Story 1

> **NOTE: 先完成 T023–T025 并确认测试失败，再开始实现。**

- [ ] T023 [P] [US1] 创建 `src/test/java/com/agentforge/controlplane/web/FailureCaseControllerTest.java`，先定义 `GET /api/failure-cases` 默认最近 24 小时、分页 0/50、size 1–200、ISO 时间/枚举校验、同筛选口径的 summary/items 和既有 detail 错误格式
- [ ] T024 [P] [US1] 创建 `src/test/java/com/agentforge/controlplane/integration/FailureCaseEntryPointIntegrationTest.java`，先覆盖 PLAYGROUND、AGENT_INVOKE、WORKFLOW_DEBUG、WORKFLOW_INVOKE、A2A 成功/失败记录、Agent/入口筛选和一分钟内可见性
- [ ] T025 [P] [US1] 创建 `src/test/java/com/agentforge/controlplane/integration/FailureCaseSecurityIntegrationTest.java` 的列表场景，先验证 `trace:read`、租户隔离、不可通过 Agent ID 或分页边界探测他租户数据

### Implementation for User Story 1

- [ ] T026 [P] [US1] 创建 `src/main/java/com/agentforge/controlplane/recovery/ExecutionRecorder.java`，在基本校验后分配执行标识，固化原入参/逻辑配置哈希与安全凭证引用，并在失败时于一分钟内原子物化一对一 Failure Case
- [ ] T027 [P] [US1] 创建 `src/main/java/com/agentforge/controlplane/dto/FailureCaseDtos.java` 的列表查询、统计、分页和条目 DTO，使用显式字段避免通过通用 ResourceDumper 暴露敏感列
- [ ] T028 [US1] 创建 `src/main/java/com/agentforge/controlplane/recovery/FailureCaseService.java` 的列表能力，统一默认 24 小时、UTC 边界、组合筛选、失败率口径、`trace:read` 和资源/租户授权
- [ ] T029 [US1] 创建 `src/main/java/com/agentforge/controlplane/web/FailureCaseController.java` 的精简列表路由，仅完成 HTTP 参数验证/映射并把业务逻辑委托给 FailureCaseService
- [ ] T030 [US1] 修改 `src/main/java/com/agentforge/controlplane/playground/PlaygroundService.java`、`src/main/java/com/agentforge/controlplane/agent/BoundAgentRuntime.java`、`src/main/java/com/agentforge/controlplane/agent/MultiAgentDebugService.java` 和 `src/main/java/com/agentforge/controlplane/agent/A2aGateway.java`，在五类入口接入统一执行生命周期记录且不改变既有成功响应
- [ ] T031 [P] [US1] 在 `src/main/resources/static/index.html` 增加“失败 Case”导航、失败率、时间/入口/Agent/状态筛选和列表的无脚本语义结构
- [ ] T032 [P] [US1] 创建 `src/main/resources/static/failure-case.css`，实现指标、筛选、分页、状态标识和窄屏布局且不引入新的前端依赖
- [ ] T033 [US1] 修改 `src/main/resources/static/app.js`，加载/筛选/分页展示失败率与 Case 列表，所有服务端或用户数据必须经现有 `escapeHtml` 后才写入 HTML

**Checkpoint**: US1 可独立演示和验收；用户能准确定位失败执行，但尚不依赖详情或重拾能力。

---

## Phase 4: User Story 2 - 审查原入参与失败原因 (Priority: P1)

**Goal**: 展示不可编辑且已脱敏的原始请求结构、失败诊断、检查点与历次重拾尝试。

**Independent Test**: 注入包含普通字段和多类敏感字段的失败请求；有权用户看到原结构、失败步骤和原因，敏感值在响应/页面/日志中均不可见，跨租户返回 404。

### Tests for User Story 2

> **NOTE: 先完成 T034–T035 并确认测试失败，再开始实现。**

- [ ] T034 [P] [US2] 扩展 `src/test/java/com/agentforge/controlplane/web/FailureCaseControllerTest.java`，先定义 `GET /api/failure-cases/{caseId}` 的 case/original_request/request_hash/configuration/checkpoint/attempts/active_task/actions 契约、只读语义和不可访问统一 404
- [ ] T035 [P] [US2] 扩展 `src/test/java/com/agentforge/controlplane/integration/FailureCaseSecurityIntegrationTest.java`，先验证详情、诊断和尝试历史的权限隔离，以及请求、配置、凭证引用、异常、日志中的秘密值零暴露

### Implementation for User Story 2

- [ ] T036 [P] [US2] 创建 `src/main/java/com/agentforge/controlplane/recovery/CheckpointService.java`，校验请求/配置/状态哈希，按执行递增序号追加检查点，并把确认完成步骤、pending step、outcome certainty、`done_tool_ids` 和安全可恢复性映射为详情视图
- [ ] T037 [US2] 扩展 `src/main/java/com/agentforge/controlplane/dto/FailureCaseDtos.java`，增加显式的详情、脱敏原入参、配置指纹/凭证引用名、检查点、尝试、活动任务和服务端计算 actions DTO
- [ ] T038 [US2] 扩展 `src/main/java/com/agentforge/controlplane/recovery/FailureCaseService.java`，实现详情查询、`trace:read` 与 Agent/工作流资源授权、跨租户统一 404、完整性异常的安全展示和按时间排序的尝试历史
- [ ] T039 [US2] 扩展 `src/main/java/com/agentforge/controlplane/web/FailureCaseController.java`，增加精简的详情路由并保持所有异常 envelope 与现有接口一致
- [ ] T040 [US2] 修改 `src/main/resources/static/app.js`，以不可编辑视图展示脱敏原入参、失败类别/步骤/摘要、检查点、配置指纹和尝试历史，所有动态文本与 JSON 键值均先执行 HTML 转义

**Checkpoint**: US2 可通过预置 Case 独立验收；详情具备诊断价值且不泄露秘密。

---

## Phase 5: User Story 3 - 从失败点重拾执行 (Priority: P1)

**Goal**: 使用失败时原入参/逻辑配置，从最近安全检查点继续同一请求；提供混合同步/后台执行、并发配额、超时、租约恢复和安全第三方传输重试。

**Independent Test**: 在多步执行第 3 步注入一次性失败；发起重拾后第 1、2 步不再执行，原入参与配置哈希不变，3 秒后原 servlet 线程释放而同一任务继续；重复 claim、超额队列、超时、服务重启和非幂等调用均符合策略。

### Tests for User Story 3

> **NOTE: 先完成 T041–T047 并确认测试失败，再开始实现。**

- [ ] T041 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/runtime/http/ThirdPartyHttpExecutorTest.java`，用 JDK HttpServer 先覆盖 408/425/429/500/502/503/504、连接临时错误、Retry-After、指数退避/抖动、步骤/整体预算、最多 1–5 次、有稳定下游幂等键的 IDEMPOTENT 请求和副作用未知请求仅一次
- [ ] T042 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/recovery/CheckpointServiceTest.java`，先覆盖哈希篡改、配置/凭证引用缺失、最近安全检查点选择、UNKNOWN 副作用默认人工处理及管理员强制审计条件
- [ ] T043 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/recovery/RecoveryCoordinatorTest.java`，先覆盖权限、原子 claim、队列满不创建尝试、冻结有效配置、3 秒内 200/超时后同任务 202、重复请求 409 和强制重拾 403/422/审计字段
- [ ] T044 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/recovery/RecoveryWorkerTest.java`，先覆盖 FIFO 领取、Agent/租户并发上限、短事务、心跳/租约、步骤 5 分钟和整体 30 分钟超时、安全停止及任何异常都进入明确状态
- [ ] T045 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/integration/FailureRecoveryIntegrationTest.java`，先覆盖中间步骤失败后从第 3 步继续、原入参逐字节/哈希一致、已确认工具零重复、失败时产生新安全检查点及外部工作不持有数据库连接
- [ ] T046 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/integration/RecoveryConcurrencyIntegrationTest.java`，使用 MySQL Testcontainers 先覆盖同 Case 仅一个活动尝试、Agent 2/租户 10/队列 100 默认上限、超额 429 无幽灵记录和锁顺序 Tenant→Agent→RecoveryTask
- [ ] T047 [P] [US3] 创建 `src/test/java/com/agentforge/controlplane/integration/RecoveryRestartIntegrationTest.java`，使用 MySQL Testcontainers 先覆盖服务重启后的过期租约扫描、安全检查点重排队、未知副作用转人工处理以及任务状态持久可查询

### Implementation for User Story 3

- [ ] T048 [P] [US3] 创建 `src/main/java/com/agentforge/controlplane/runtime/http/HttpRequestSemantics.java`、`src/main/java/com/agentforge/controlplane/runtime/http/RetryPolicy.java` 和 `src/main/java/com/agentforge/controlplane/runtime/http/ThirdPartyCallException.java`，实现 SAFE/IDEMPOTENT/SIDE_EFFECTING_OR_UNKNOWN 声明、1–5 次/0–5000ms/1.0–4.0/0–50%/Retry-After 0–30000ms 边界及仅含安全分类的类型化异常
- [ ] T049 [US3] 创建 `src/main/java/com/agentforge/controlplane/runtime/http/ThirdPartyHttpExecutor.java`，仅在第三方调用层对白名单临时错误重试，恢复中断标记，解析/TLS/鉴权/校验/确定业务错误不重试，且从不记录完整 URL、header、body、模型内容或原始异常
- [ ] T050 [US3] 修改 `src/main/java/com/agentforge/controlplane/agent/HttpAgentRuntime.java` 和 `src/main/java/com/agentforge/controlplane/runtime/McpStreamClient.java`，在 T049 完成后接入共享执行器并显式声明 HTTP Agent/MCP tools/call 默认副作用未知、MCP initialize/tools/list 为 SAFE，只有下游明确支持且稳定键不含用户输入/凭证时才声明 IDEMPOTENT
- [ ] T051 [US3] 修改 `src/main/java/com/agentforge/controlplane/agent/A2aGateway.java`、`src/main/java/com/agentforge/controlplane/rag/EmbeddingClient.java`、`src/main/java/com/agentforge/controlplane/rag/HttpRerankClient.java` 和 `src/main/java/com/agentforge/controlplane/memory/MemorySummarizer.java`，在 T049 完成后分别把 A2A 保持一次调用、纯计算请求标记 SAFE，并禁止把完整 ReAct turn 作为重试单元
- [ ] T052 [US3] 创建 `src/main/java/com/agentforge/controlplane/recovery/RecoveryCoordinator.java`，在短事务中完成授权、快照哈希/配置/凭证检查、强制风险校验、配额检查、Case 原子 claim 及 attempt/task 创建；随后等待同一任务最多冻结的 sync_wait_ms，完成返回 200，否则返回 202 且不中断/重启任务
- [ ] T053 [US3] 创建 `src/main/java/com/agentforge/controlplane/recovery/RecoveryWorker.java`，以 DB 状态为并发权威，按租户 FIFO 和 Tenant→Agent→RecoveryTask 锁顺序领取，外部工作在事务外运行，独立短事务维护心跳/检查点/状态，并扫描过期租约按安全性重排队或转人工处理
- [ ] T054 [US3] 修改 `src/main/java/com/agentforge/controlplane/agent/AgentScopeRuntime.java` 和 `src/main/java/com/agentforge/controlplane/workspace/WorkspaceStore.java`，从失败时逻辑配置与 DB 权威检查点恢复 AgentScope working/pending_tools/done_tool_ids，跳过 CONFIRMED 步骤，并仅在 DB 写成功后 best-effort 更新 workspace 诊断投影
- [ ] T055 [US3] 修改 `src/main/java/com/agentforge/controlplane/agent/MultiAgentDebugService.java` 和 `src/main/java/com/agentforge/controlplane/playground/PlaygroundService.java`，在模型/工具/工作流边界执行步骤超时预算、追加检查点、传播同一 execution/case/attempt/task 关联，配置不可用时不静默改用最新版
- [ ] T056 [US3] 扩展 `src/main/java/com/agentforge/controlplane/dto/FailureCaseDtos.java` 和 `src/main/java/com/agentforge/controlplane/web/FailureCaseController.java`，实现 `POST /api/failure-cases/{caseId}/recover` 与 `GET /api/recovery-tasks/{taskId}` 的 200/202/403/404/409/422/429/503 契约，控制器保持只做校验映射
- [ ] T057 [US3] 修改 `src/main/java/com/agentforge/controlplane/web/PlaygroundController.java`，将既有 `POST /api/playground/resume` 委托 RecoveryCoordinator，保留同步成功响应和 detail 错误格式，并禁止普通用户通过 `force_rerun_tools` 绕过幂等保护
- [ ] T058 [US3] 修改 `src/main/resources/static/app.js`，增加普通/管理员强制重拾确认、强制理由和风险确认、200 结果显示、202 同 task_id 轮询及 409/429/503 安全提示，且不得把原入参发送回恢复 API

**Checkpoint**: US3 可通过预置 Case 独立验收；重拾从安全失败点继续，线程、超时、并发、幂等、租约和异常行为可验证。

---

## Phase 6: User Story 4 - 关闭已成功重拾的 Case (Priority: P2)

**Goal**: 重拾成功后 Case 进入不可逆终态，UI 与直接 API 永久禁止再次重拾，同时保留完整原失败和恢复审计。

**Independent Test**: 完成一次成功重拾后刷新页面，并通过按钮和直接请求再次操作；状态始终为 RECOVERED、执行次数不增加、原 Case/attempt/result 关联完整；一次失败重拾则仍可从新安全点继续。

### Tests for User Story 4

> **NOTE: 先完成 T059–T060 并确认测试失败，再开始实现。**

- [ ] T059 [P] [US4] 扩展 `src/test/java/com/agentforge/controlplane/recovery/RecoveryCoordinatorTest.java`，先覆盖成功事务只允许一次写入 recovered_execution/attempt、RECOVERED 永久 409、响应丢失后查询仍为终态，以及失败尝试仅在新检查点安全时回到 RESUMABLE
- [ ] T060 [P] [US4] 扩展 `src/test/java/com/agentforge/controlplane/integration/FailureRecoveryIntegrationTest.java`，先覆盖页面等价流程和直接 API 重复重拾均零执行、刷新/重登/重启终态不变、原失败记录与成功结果审计均保留

### Implementation for User Story 4

- [ ] T061 [US4] 扩展 `src/main/java/com/agentforge/controlplane/recovery/RecoveryWorker.java`，在成功结束的短事务中原子写入 attempt/task SUCCEEDED、result links 和 Case RECOVERED，失败/超时时按最后已确认安全检查点选择 RESUMABLE 或 MANUAL_REQUIRED 且绝不覆盖历史
- [ ] T062 [US4] 扩展 `src/main/java/com/agentforge/controlplane/recovery/RecoveryCoordinator.java` 和 `src/main/java/com/agentforge/controlplane/recovery/FailureCaseService.java`，在任何 claim 前永久拒绝 RECOVERED，并使 actions.can_recover/can_force_recover 与服务端状态一致
- [ ] T063 [US4] 修改 `src/main/resources/static/app.js`，将 RECOVERED 显示为“重拾成功”、展示成功尝试时间和关联结果、永久移除/禁用普通与强制重拾入口，并以服务端终态覆盖过期浏览器状态

**Checkpoint**: US4 独立验收通过；成功 Case 无任何重复执行通道，失败 Case 仍遵循安全检查点规则。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 完成剩余适配器、安全日志、全量回归、性能目标和操作文档验证。

- [ ] T064 [P] 修改 `src/main/java/com/agentforge/controlplane/rag/GraphRagService.java`、`src/main/java/com/agentforge/controlplane/eval/EvalScorers.java` 和 `src/main/java/com/agentforge/controlplane/observability/ObservabilityService.java`，将纯计算/只读调用迁移到共享第三方执行器，写操作继续一次调用，且 observability 重试不得阻塞或使业务请求失败
- [ ] T065 [P] 在 `src/test/java/com/agentforge/controlplane/integration/FailureCaseLogSafetyIntegrationTest.java` 增加日志捕获测试，覆盖所有异常分类和关联 ID 可查，同时断言原始入参、完整模型输入输出、凭证明文、未脱敏工具参数/结果及原始异常文本零暴露
- [ ] T066 [P] 在 `src/test/java/com/agentforge/controlplane/integration/FailureCasePerformanceIntegrationTest.java` 增加指标/list 同筛选准确率、失败一分钟内可见、同步请求 3 秒释放、配额不超限及 Hikari 两连接条件下无线程/连接饥饿的验收测试
- [ ] T067 在 `src/main/java/com/agentforge/controlplane/recovery/ExecutionRecorder.java`、`src/main/java/com/agentforge/controlplane/recovery/RecoveryCoordinator.java`、`src/main/java/com/agentforge/controlplane/recovery/RecoveryWorker.java` 和 `src/main/java/com/agentforge/controlplane/runtime/http/ThirdPartyHttpExecutor.java` 统一结构化安全日志字段，仅记录租户/Case/attempt/task/trace/Agent/工作流/步骤/分类/状态/耗时/脱敏摘要
- [ ] T068 修改 `specs/001-failure-case-recovery/quickstart.md`，补充实际 Maven 测试命令、Testcontainers 前置条件、配置生效方式、故障注入步骤、200/202/409/429/503 预期及日志安全检查方法
- [ ] T069 依次执行 `pom.xml` 定义的单元测试、MockMvc 契约测试和 Testcontainers 集成测试，并按 `specs/001-failure-case-recovery/quickstart.md` 完成五入口、幂等/副作用、超时、并发、重启、终态和 HTML 转义自测，修复回归后将验证结果追加到 `specs/001-failure-case-recovery/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 — Setup**: 无依赖，可立即开始。
- **Phase 2 — Foundational**: 依赖 Phase 1；完成后才允许开发任何用户故事。
- **Phase 3 — US1**: 依赖 Phase 2；构成可单独交付的 MVP。
- **Phase 4 — US2**: 依赖 Phase 2；可用数据库种子 Case 独立测试，前端导航与 US1 集成但后端不依赖 US1 完成。
- **Phase 5 — US3**: 依赖 Phase 2；可用数据库种子 Case/检查点独立测试，完整端到端演示会复用 US1/US2 页面。
- **Phase 6 — US4**: 依赖 US3 的成功/失败恢复生命周期。
- **Phase 7 — Polish**: 依赖计划交付的全部用户故事。

### User Story Dependency Graph

```text
Setup → Foundation ┬→ US1 (MVP: 列表与失败率)
                   ├→ US2 (详情与失败原因)
                   └→ US3 (检查点重拾) → US4 (成功终态保护)

US1 + US2 + US3 + US4 → Polish / full acceptance
```

### Within Each User Story

- 测试任务必须先完成并确认在缺少实现时失败。
- 显式 DTO/实体和持久查询先于 service；service 先于 controller/UI。
- 外部/model/tool 工作不得在数据库事务中运行；每次状态和检查点提交使用短事务。
- 后端安全与终态判断是权威；前端禁用按钮仅是体验增强。
- 每个阶段结束时运行该故事对应测试，不能等到最终阶段才验证。

### Parallel Opportunities

- Setup 中 T002、T003、T004 可在 T001 之外并行。
- Foundation 中五个实体、各仓库、两类单元测试、状态机/脱敏/分类器/执行器可按文件并行；T010 需等待实体约束确认，T021/T022 与新文件工作可并行。
- Foundation 完成后，US1、US2 和 US3 可由不同开发者以预置数据并行；US4 必须等待 US3。
- 每个用户故事标记 `[P]` 的测试可同时编写，但实现开始前均须先看到预期失败。
- US3 的 HTTP 策略类型、适配器迁移、协调器测试、Worker 测试和 MySQL 场景可分工；共享文件任务按编号串行合并。

---

## Parallel Example: User Story 1

```text
Task T023: 定义失败列表 HTTP 契约
Task T024: 验证五类请求入口的记录与筛选
Task T025: 验证列表权限和租户隔离

测试先失败后：
Task T026: 实现统一 ExecutionRecorder
Task T027: 定义显式列表 DTO
Task T031: 增加静态页面结构
Task T032: 增加独立样式
```

## Parallel Example: User Story 2

```text
Task T034: 定义详情 HTTP 契约
Task T035: 验证详情/日志敏感信息零暴露

测试先失败后：
Task T036: 实现检查点详情映射
Task T037: 定义详情 DTO（在 T036 数据边界确认后合并）
```

## Parallel Example: User Story 3

```text
Task T041: 第三方传输重试单元测试
Task T042: 检查点安全与完整性单元测试
Task T043: Coordinator 单元测试
Task T044: Worker 单元测试
Task T045: 失败点续跑集成测试
Task T046: MySQL 并发/队列测试
Task T047: 重启与租约测试

测试先失败后：
Task T048: 定义传输策略类型
Task T050: 迁移 HTTP Agent/MCP 适配器（等待 T049 后运行验证）
Task T051: 迁移 A2A/纯计算适配器（等待 T049 后运行验证）
```

## Parallel Example: User Story 4

```text
Task T059: Coordinator 终态单元测试
Task T060: 成功后重复请求集成测试

测试先失败后：
Task T061: Worker 原子完成状态
Task T063: RECOVERED 前端终态（等待服务端 actions 契约确认后联调）
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup。
2. 完成 Phase 2 Foundation 并运行基础单元测试。
3. 完成 Phase 3 US1。
4. 停止扩展，独立验证 80 成功/20 失败、五入口筛选、20.0% 失败率与租户隔离。
5. 若需要先行交付，可部署只读失败 Case 模块；恢复入口保持不可见。

### Incremental Delivery

1. Setup + Foundation → 数据与安全基础就绪。
2. US1 → 可发现并定位失败 Case（MVP）。
3. US2 → 可安全审查原入参与失败原因。
4. US3 → 可从安全检查点重拾，并具备超时/并发/重启保护。
5. US4 → 成功后永久关闭重拾入口。
6. Polish → 扩展安全适配器、压力/日志测试和全量 quickstart 验收。

### Suggested Commit Boundaries

- Setup 与 Foundational schema/entities/repositories。
- Foundational state/sanitizer/error/executor。
- 每个用户故事的失败测试。
- 每个用户故事的后端实现。
- 每个用户故事的前端与集成验证。
- Cross-cutting 安全日志、性能验证和文档。

## Notes

- `[P]` 只表示文件和前置依赖允许并行，不表示可以跳过测试先失败的门禁。
- 所有 Java 公共方法使用明确参数/返回类型；控制器保持精简，业务逻辑位于 `recovery` 服务。
- 所有租户实体查询必须把 `tenant_id` 作为查询条件，不得加载后再过滤。
- 所有动态前端数据必须经过 `escapeHtml`，不得信任 JSON 字符串或错误摘要。
- 不得硬编码 API Key、密码或数据库凭证；快照只能保存安全引用。
- 完成任何新增或修改后必须运行相应自测，T069 是最终全量门禁而不是替代逐任务验证。
