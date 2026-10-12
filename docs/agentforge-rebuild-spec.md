# AgentForge Control Plane 重建规格

本文是给实现者用的功能规格。另一个 AI 或工程团队按本文实现，应得到一个行为等价的 Agent 控制面。推荐技术栈写在第 2 节；若更换语言，第 4 节以后的行为和数据契约仍然有效。

不要把演示账号、数据库密码或模型密钥写进仓库、种子数据说明或日志。

## 1. 产品

这是一个给团队使用的 Agent 控制面。浏览器里配置 Agent、模型、知识库、工具和多智能体编排；对话时由 ReAct 运行时调用已绑定的模型和工具。控制面负责身份、权限、配置、知识、记忆、评测和观测。一轮推理交给 AgentScope Java 2.0.3 的 `ReActAgent`。工具执行仍回到控制面，这样租户、试用身份和知识库权限不会丢在模型线程上。

服务只监听 `127.0.0.1:8080`。打开根路径就是控制台。未登录可以先试用 5 分钟，到期后必须登录。

控制台是单页静态页面，不是前后端分离的独立工程。页面文件放在服务的静态资源目录，由同一个进程提供。

## 2. 技术栈与运行约束

- Java 21，Spring Boot 3.5.6，Maven。
- AgentScope Java 2.0.3：`agentscope-core`、`agentscope-extensions-model-openai`、`agentscope-extensions-model-dashscope`、`agentscope-extensions-a2a-client`、`agentscope-extensions-a2a-server`。
- 不要引入 `agentscope-a2a-spring-boot-starter`。它会占用 `POST /` 和 `GET /.well-known/agent-card.json`，并且要求一个本项目没有的 `ReActAgent` Bean。
- MySQL 8，字符集 `utf8mb4`。JPA `ddl-auto` 设为 `none`。核心表由已有库提供；启动时只补齐后来增加的表和列，不覆盖已有行。
- 连接池最大 2、最小空闲 0。这是为了适配连接数上限为 80 的共用库。任何嵌套调用都不能在外层事务还占着一条连接时再占第二条。
- Milvus 2.5，Java SDK 2.5.10。知识库向量检索用它。默认地址 `http://127.0.0.1:19530`。
- 文档解析：PDFBox、Apache POI。上传上限 16MB。支持 txt、md、html、csv、docx、pdf。
- 数据库连接从环境变量 `DATABASE_URL` 读取。格式是 SQLAlchemy 风格，例如 `mysql+pymysql://用户:密码@主机:3306/agentforge?charset=utf8mb4`。启动时改写成 JDBC，并拆出用户名和密码。没有这一行时服务不能启动。
- 模型密钥存在数据库的模型配置里，不放进 `.env`。

## 3. 页面

左侧导航，右侧内容。页面和所需权限：

| 页面 | 权限 | 作用 |
| --- | --- | --- |
| 概览 | 已登录 | 请求量、成功率、延迟、最近会话 |
| 会话查询 | `session:read` | 按会话、Agent、状态检索对话 |
| AgentScope Studio | `trace:read` | 查看本系统保存的运行步骤；若配置了 Studio 地址则嵌入 |
| 数据测试 | `eval:read` | 数据集、回归、性能、报告、追踪 |
| A/B 实验 | `experiment:read` | 按权重把流量分到不同 Agent |
| Agent 调试台 | `agent:write` | 用 Agent 自己的模型对话，右侧展示执行步骤 |
| Agent 管理 | `agent:read` | 配置提示词、模型、技能、工具、知识库、A2A 名片 |
| 多智能体 | `workflow:read` | 场景移交、主从、工作流三种画布 |
| HTTP 接口 | `agent:read` | 登记外部平台的 HTTP Agent |
| MCP 工具 | `mcp:read` | 登记 MCP 服务 |
| Skill 管理 | `skill:read` | 可复用技能 |
| 知识库 | `knowledge:read` | 上传、分块、检索、成员 |
| 模型配置 | `model:read` | 对话、向量、重排序。试用账号看不到 |
| 向量数据库 | `tenant:admin` | 租户默认 Milvus |
| 沙箱管理 | `sandbox:read` | 隔离执行代码和命令 |
| 权限管理 | `role:read` | 租户、角色、成员 |
| 记忆 | 已登录 | 只看自己的长期记忆，可改正、置顶、删除 |

静态资源改了 JS 或 CSS 后，要在 `index.html` 上把缓存参数改掉，否则浏览器继续用旧文件。

## 4. 身份、租户、权限

登录成功后生成一枚随机令牌，URL-safe Base64，32 字节随机数、无填充。写入 `auth_tokens`，有效期 7 天。浏览器把令牌存在 `localStorage` 的 `af_token`，之后每个请求带：

```http
Authorization: Bearer <令牌>
X-Tenant-Id: <租户编号>
```

`X-Tenant-Id` 只在平台管理员切换租户时出现。普通用户不带。

试用：`POST /api/auth/trial`。这个浏览器还没有试用记录时，发一张 5 分钟令牌，并种下 HttpOnly Cookie `af_trial`，用来记住「这个浏览器已经领过试用」。有效期内再次进入，沿用原来的到期时间。过期后必须登录，不能靠清掉本地令牌再领一次。试用身份是共用的 guest 用户，长期记忆用 `trial:{凭证}` 把不同浏览器分开。登录后不自动合并试用记忆。

每次请求由拦截器解析令牌，得到当前用户，放进请求属性和线程变量。请求结束必须清掉。同时把原始 `Authorization` 和 `X-Tenant-Id` 记在线程变量里，供本机再发的 A2A 请求原样抄走。

权限名：`platform:admin`、`tenant:admin`、`agent:read`、`agent:write`、`workflow:read`、`workflow:write`、`eval:read`、`eval:run`、`experiment:read`、`experiment:write`、`mcp:read`、`mcp:write`、`skill:read`、`skill:write`、`model:read`、`knowledge:read`、`knowledge:write`、`sandbox:read`、`sandbox:write`、`session:read`、`session:write`、`trace:read`、`role:read`、`user:read`、`vector:read`。`*` 或 `platform:admin` 视为全部权限。写权限包含对应的读权限。接口上列出的多个权限，具备其中一项即可。

资源按租户隔离。平台管理员可以用 `X-Tenant-Id` 切换到其他租户。非平台管理员带了别的租户编号则拒绝。

启动时若缺少租户、角色或内置工具，补齐它们。已有行不覆盖。不要在规格或种子说明里写死账号密码。

## 5. 数据

所有业务 JSON 使用蛇形字段名。更新接口按「请求体里出现的字段」做部分更新，未出现的字段保持原值。

核心表（已有库提供，启动不重建）：`tenants`、`roles`、`users`、`auth_tokens`、`agents`、`mcp_servers`、`skills`、`model_configs`、`workflows`、`sandbox_policies`、`conversations`、`chat_messages`、`traces`、`datasets`、`dataset_cases`、`evaluation_runs`、`evaluation_results`。

启动时若缺失则创建或补列：

| 对象 | 要点 |
| --- | --- |
| `visitor_days` | 访问次数。按天索引，不去重到人 |
| `memory_items` | 长期记忆。`subject_key`、`kind`、`pinned`、`deleted_at` |
| `conversations.subject_key` | `user:{id}` 或 `trial:{凭证}` |
| `conversations.workflow_id` / `current_node_id` | 多智能体停在哪个节点 |
| `http_agents` | 外部 HTTP Agent |
| `agents.http_agent_ids` / `knowledge_ids` | JSON 数组 |
| `agents.published_release_id` | 当前发布版 |
| `agents.a2a_card` | 可手改的名片 JSON |
| `agent_releases` | 发布快照：`revision`、`fingerprint`、`snapshot_json` |
| `auth_tokens.trial_key` | 试用凭证 |
| `model_configs.purpose` | `chat` / `embedding` / `rerank`，默认 `chat` |
| `model_configs.json_output` | 对话模型是否要求 JSON 输出 |
| `vector_stores` | Milvus 连接，`is_default` |
| `rerank_stores` | 重排序连接 |
| `knowledge_bases` | `visibility`、`retrieval_mode`、`rerank_model_id`、`graph_model_id` |
| `knowledge_documents` | 文件、分块策略、世代号 |
| `knowledge_members` | 知识库分享 |
| `kg_document_graphs` | GraphRAG 的图，按文档和世代号 |

`agents` 主要字段：名称、说明、模型名、状态、版本、系统提示词、技能、MCP、OpenCLI、HTTP Agent、工具链路、知识库、沙箱、工作空间、发布版、A2A 名片。

`workflows.graph` 是一张画布 JSON：`pattern`、`dispatch`、`global_prompt`、`nodes`、`edges`。节点有 `id`、`type`、`label`、`agent_id`、`scenario`、`prompt`、`condition`。边是 `source` 到 `target`。

`traces`：`trace_id`、会话、Agent、操作、状态、耗时、输入输出 Token、`spans` JSON、`langfuse_url`、开始时间、租户、主人。

## 6. 接口一览

除健康检查和登录、试用外，都要登录。

身份：`POST /api/auth/login`、`POST /api/auth/trial`、`POST /api/auth/logout`、`GET /api/auth/me`。

租户和用户：`GET/POST /api/tenants`，`GET/POST /api/users`，`PUT /api/users/{id}`。

通用资源：`GET/POST /api/{resource}`，`PUT/DELETE /api/{resource}/{id}`，`PATCH /api/{resource}/{id}/status`。资源名包括 agents、http-agents、workflows、mcp、skills、models、sandboxes、roles。

Agent：复制、改名、发布、接口说明、`POST /api/agents/{id}/invoke`、工作空间及其中的会话。

模型、MCP、Skill、沙箱、HTTP Agent 都有测试接口。MCP 另有 query。

调试台：`POST /api/playground/run`、`POST /api/playground/resume`、`GET /api/playground/sessions/{sessionId}`。

多智能体：`POST /api/workflows/{id}/debug`、`GET /api/workflows/{id}/interface`、`POST /api/workflows/{id}/invoke`。

A2A：`GET /api/a2a/agents/{id}/.well-known/agent-card.json`，`POST /api/a2a/agents/{id}`。两者都要 `session:write` 或 `agent:write`，并且当前用户必须能使用这个 Agent。

知识库：列表、创建、更新、删除；文档上传、详情、重建索引、删除；检索；成员的读、改、删。向量库另有列表、创建、测试、更新、删除。

记忆：列表、新增、修改、删除。只操作当前身份自己的记忆。

会话和轨迹：`GET /api/sessions`、`GET /api/sessions/{sessionId}`、`GET /api/traces/{id}`。

评测：数据集、用例、导入、模板 CSV、任务、在线抽检、评分说明、性能测试、结果、续跑、重跑、取消、导出 CSV。

实验：列表、创建、详情、更新、删除、开始、暂停、完成、分流、对比。

观测：`GET /api/health`、`GET /api/observability`、`GET /api/dashboard`。

## 7. Agent 运行时

正式调用和调试都走绑定了配置的运行时。

- 若 Agent 绑定了外部 HTTP Agent，整轮对话改由 `HttpAgentRuntime` 把消息 POST 到对方，不再走本机 ReAct。
- 否则用 AgentScope 的 `ReActAgent`。模型按配置走 OpenAI 兼容接口或 DashScope。
- 系统提示词里的 `{{user_input}}` 用本轮用户消息替换。编排里的用户消息是拼好的「用户原话」加「主智能体交给你的任务」，占位符替换的是这整段文字。
- 对话模型打开 JSON 输出开关时，请求要求模型返回 JSON。这个开关只对 `purpose=chat` 生效。
- 绑定的技能注入提示词。绑定的 MCP、内置工具、工具链路和知识库检索暴露成工具。
- 内置工具至少包括 `get_current_time`。知识库有已索引文档时自动暴露 `search_documents`。沙箱工具是 `sandbox_run_python` 和 `sandbox_run_shell`，在 Docker 容器里执行，不是 AgentScope 自带的沙箱包。默认不允许本机不安全沙箱。未配置出网时，需要联网的命令失败。浏览器工具默认不访问内网。
- 工具链路：JSON 数组。每条以 `flow_名称` 暴露给模型，一次调用按 `steps` 顺序跑完。步骤参数可用 `{{input.字段}}` 和 `{{steps.步骤id.output.字段}}`。步骤引用的工具必须已经绑定。
- 模型没有密钥时不调用真实模型，返回预览回复，模式标成 preview 或 error，不能假装成线上可用。
- 调试台右侧的步骤是本系统自己记的 span，不是 OpenTelemetry SDK。

`invoke(agent, message, sessionId)` 等价于钉住发布版并记录会话。另有一个重载，两个开关：

- `pinRelease`：为真时确保已有发布版，并在发布版上下文里跑，草稿修改不影响这次调用。为假时用当前草稿和当前模型。
- `recordExchange`：为真时写入会话记忆、做总结、落运行记录。为假时只用这一轮消息，不写记忆、不总结、不另开数据库连接。

对外的 `POST /api/agents/{id}/invoke` 和外部 A2A 调用都是钉住发布版并记录。编排内部的子智能体跳转按场景决定是否钉住，并且不另记一条会话。

## 8. 版本钉扎

发布把当前提示词、技能和工具清单抄成一份快照，算指纹。指纹与当前发布版相同则不新建。否则修订号加一，并把 `published_release_id` 指到新行。

正式调用读发布版。调试台默认看草稿，这样改提示词能立刻试。工作流对外 invoke 使用发布版。还没有任何发布版时，第一次正式调用现场发布一份。

启动时给还没有发布版的 Agent 补一份，不覆盖已有发布版。

## 9. 多智能体

画布有三种模式，缺省是场景移交。打开编辑时必须保留用户已保存的模式，不能把主从或工作流显示成场景移交。未保存的草稿在重新进入编辑时也要留住，直到用户自己保存或放弃。

限制：跳转最多 5 次，子智能体最多 8 个，工作流步骤最多 8 步。

### 场景移交 handoff

从开始节点进入当前 Agent。模型决定交给哪一个下游。交出去之后，下一轮从那个节点继续。失败要在步骤里标成错误。

### 主从 supervisor

开始只连主智能体。主智能体的生成留在本进程。它把下游当成子智能体工具。真正调用子智能体时走第 10 节的 A2A，不在进程内直接再跑一遍生成。

子智能体收到的用户文字由两段组成：用户原话，以及主智能体交给它的任务。画布节点上的提示词和全局提示词通过进程内的调用盒带到子智能体那一侧，在执行那一轮时套上，不写进 A2A 协议正文。会话编号是 `原会话::sub::节点id`。

若主智能体只返回分类 JSON，平台读取 `category`，在子智能体的名称和场景说明里找唯一匹配。对上唯一一个就自动调用它。待确认、无法判断、不确定、unknown、unsure、对不上、同时对上多个，都留在主智能体，不自动转发。分类以「b端」或「c端」开头时，可以退到这个前缀再匹配一次，仍然必须唯一。

子智能体没有返回内容时，回复写「子智能体没有返回内容」。调用失败时，回复写「子智能体调用失败：」加上原因。不要在缺少登录头时悄悄改回进程内调用。

工具步骤的名称仍是 `subagent.` 加工具名，这样「调用了哪个子智能体」还能识别。展示标题写「通过 A2A 调用子智能体 {名称}」。另外记一条 `a2a.call`，详情是 `POST /api/a2a/agents/{id}`。

主从内部跳转：`pinRelease` 在调试时为假、在工作流 invoke 时为真；`recordExchange` 为假，避免外层事务占着连接时再占一条。

### 工作流 pipeline

按连线从起点串行执行。一条边有多条出线时做分支判断。出现环路记一条错误步骤并停止。每一步记 `pipeline.step`。节点提示词同样套在该步的 Agent 上。

## 10. A2A

每个 Agent 有自己的地址，不占用网站根路径。

- JSON-RPC：`POST /api/a2a/agents/{id}`
- 名片：`GET /api/a2a/agents/{id}/.well-known/agent-card.json`
- 本机调用时的基址是 `http://127.0.0.1:{端口}/api/a2a/agents/{id}/`，相对路径是 `.well-known/agent-card.json`，前面不要加斜杠。相对路径若以 `/` 开头，会把基址的路径替换掉，名片会 404。

服务端使用 AgentScope 的 A2A Server，自己处理请求，不绑定新端口，也不向注册中心登记。阻塞的 `message/send` 返回 JSON-RPC 响应。流式返回 SSE。执行器线程上看不到原来的登录线程变量，所以要在构建执行器时抓住当前用户。

阻塞结果优先取最终消息事件；没有时，把流式文本增量拼起来。流式过程要同时发出文本增量事件和最终结果事件。文本增量的参数顺序是回复编号、块编号、增量文本。

外部调用的消息必须带 `kind: "message"`。缺少时协议层返回 `-32602 Invalid parameters`。官方客户端会自己带。

执行时：

- 没有调用盒的外部请求：钉住发布版，并记录会话。
- 有调用盒的内部跳转：按调用盒里的钉扎开关和记录开关执行，并套上节点提示词。执行完把带步骤的回复放回调用盒。A2A 协议本身只返回文本，步骤靠这个调用盒留在本进程。
- 不要从 A2A 执行器再去调用主从的「跑一个子智能体」，否则会递归。

客户端使用 `A2aAgent`，名片解析用 well-known，传输用 JSON-RPC，流式关闭。`call().block()` 不能跑在 Reactor 线程上，因为工具本身已经在 ReAct 的阻塞调用里。把客户端调用提交到单独的守护线程池，工具线程等待 Future。超时 5 分钟。服务端构建 A2A Server 时也使用共享的守护线程池，不要每次请求新建缓存线程池。

内部请求抄走三样头：`Authorization`、`X-Tenant-Id`（有才抄）、`X-AgentForge-Call`（本次调用的 UUID）。名片请求和消息请求都带这些头。

### 名片

存在 `agents.a2a_card` 里的只是可编辑子集：`name`、`description`、`version`、`documentation_url`、`skills`。技能最多 8 条。每条必须有 `name`。`id` 只能是字母、数字、下划线和连字符，否则用 `skill-1` 这种序号。示例最多 5 条。标签可选，构建技能时若没有标签则用 `agent`。长度上限：名称 80，说明 500，版本 40，文档地址 300，技能名 80，技能说明 400，单条例子 160，标签 6 个、每个 40。文档地址留空，或必须以 `http://` 或 `https://` 开头。技能没有名称时返回 400，详情是「能力需要填写名称」。

对外名片始终由系统覆盖：`url`、`preferredTransport=JSONRPC`、能力、输入输出模式 `text`。库里的名片为空时，名称、说明、版本回退到 Agent 自己的字段。

编辑弹窗在 Agent 表单底部，HTTP Agent 模式也要能看见。左边是可编辑 JSON，右边是调用方会看到的名片。打开已保存的 Agent 时，先用本地内容预览，再拉取线上名片；用户已经改过左边则不要用线上结果覆盖。保存时解析 JSON，非法则提示，不提交。「按名称重填」用表单里的名称、说明和版本重填，技能清空。「复制地址」复制 well-known 地址。还没保存、没有编号时没有地址。

## 11. 知识库

新建知识库选择检索方式，默认 `hybrid`。

### 混合检索 hybrid

上传后文件落到 `rag-files/tenants/{租户}/knowledge/{知识库id}/`。清洗后按文档形态选一种分块策略，调 embedding，写入 Milvus。

自动策略按优先级取最高分：`qa`、`article`、`table_row`、`heading`、`paragraph`、`sentence`。最高分小于 1 时用 `recursive`。用户也可以指定其中一种，指定了非法名称则拒绝。

- `qa`：至少 3 组「问：」或「Q:」及其答案。每组一块，问题当标题。
- `article`：至少 5 个「第 N 条」。按条款切开。
- `table_row`：竖线表格占比高。表头加每一行一块。
- `heading`：Markdown `#` 到 `######`。标题路径用 ` / ` 连起来，例如「总则 / 适用范围」。标题不少于 2 个才给分；不少于 4 个且每节长度小于目标块的 4 倍时给更高分。找不到标题则退回按段落。
- `paragraph`：段落长度接近目标块。按空行分段。
- `sentence`：段落很长且缺少空行。按 `。！？；.?!` 分句。代码块占比高时不选句子策略。
- `recursive`：结构不明显时的兜底。先按空行分段，超长再按句子切，句子仍超长则按字数硬切。

中文占比达到 0.3 时，目标块约 450 字、重叠约 60 字；否则目标约 1000、重叠约 120。问答和表格行不重叠。单块上限 8192，打包时不超过目标的 1.6 倍。同一标题下的子块拼成章节上下文，最长 2000 字。没有标题时上下文就是子块本身。

检索时，向量和 BM25 用较短的 `content`。交给模型的是 `context`。调试步骤里这一步的标题是「调用工具 search_documents」。可选重排序模型。

可见性默认私有。对话时再按当前用户过滤，私人库不会被其他对话者检索到。成员表记录被分享的人。

### GraphRAG

`retrieval_mode=graphrag` 时，在混合检索之外为每个文档抽取实体和关系，写入 `kg_document_graphs`，世代号必须和文档一致。抽取使用该知识库绑定的、已启用的对话模型。没有可用对话模型则失败并说明需要一个已启用的对话模型。

回答分局部和全局两种。局部沿实体关系找附近证据。全局使用文档社区摘要。删除文档或知识库时删掉对应的图。重建索引后旧世代的图不算就绪。

## 12. 记忆

短期记忆是同一会话的 `chat_messages`，换会话即清空。

长期记忆在一轮结束后由总结器写入。只收偏好、资料、决定、更正。已有相同正文则不重复添加。用户否定则删除。页面可以改正、置顶、删除。每条最长 500 字。身份键是 `user:{id}` 或 `trial:{凭证}`。试用账号都是 guest，不能只用用户编号区分两个人。

编排内部的 A2A 跳转不写长期记忆，也不触发总结。

## 13. 评测、实验、链路

评测先选定 Agent，再管理它的数据集和回归。支持离线回归、在线抽检、性能测试、报告、失败续跑、取消、CSV 导入导出。评分说明要给出通过例和失败例。

A/B 实验按权重把流量分到不同 Agent，记录分配和对比指标：延迟、失败率、回复质量。状态有开始、暂停、完成。

链路不是 Jaeger、Zipkin 或 OpenTelemetry 客户端。每一步是一个 JSON 对象：`name`、`title`、`kind`、`status`、`duration_ms`、`detail`。说明最长 240 字。状态是 `ok`、`error` 或 `skip`。一整轮存进 `traces.spans`。会话详情和 Studio 页读这张表。

配置了 `AGENTSCOPE_STUDIO_URL` 时，调试台可以把这次运行登记到 Studio 的 `/trpc/registerRun`，并向 `/v1/traces` 推一份简化记录。导出失败不影响对话。`OTEL_EXPORTER_OTLP_ENDPOINT` 和 Langfuse 的地址、密钥只保存配置并在观测状态里显示是否已填写，当前不向这两处发送链路。

## 14. 实现时必须保持的行为

1. A2A 不使用 Spring Boot starter，不占用 `/`。
2. 名片相对路径不以 `/` 开头，基址以 `/` 结尾。
3. 主从的主生成留在进程内，子智能体走 A2A。节点提示词和钉扎开关走进程内调用盒。
4. 内部 A2A 不写会话、不总结，避免连接池只有 2 条时死锁。客户端调用不跑在 Reactor 线程上。
5. 分类 JSON 只有唯一匹配才自动转发。
6. 画布重新打开时保留已选模式和未保存草稿。
7. 正式调用读发布版，调试读草稿。`{{user_input}}` 来自本轮用户消息。
8. 对话模型的 JSON 输出开关不影响向量和重排序模型。
9. 知识库检索把章节上下文交给模型，用短子块做召回。
10. 试用 5 分钟只发一次。登录令牌 7 天。A2A 使用同一枚令牌，不另发密钥。
11. 启动迁移只补表和列，不覆盖业务数据。
12. 模型无密钥时走预览回复，并标明不是真实调用。

## 15. 验收

实现完成后至少确认这些事：

- 未登录打开首页得到页面；试用接口第一次成功，过期后拒绝。
- 登录后能创建 Agent、绑定模型、在调试台对话，并在右侧看到步骤。
- 无密钥的模型返回预览回复，模式不是成功的真实调用。
- 发布后修改草稿，对外 invoke 仍用发布版；调试台看到草稿。
- 主从编排里，子智能体步骤的详情是 `POST /api/a2a/agents/{id}`。分类唯一匹配时自动进入对应子智能体。
- 直接 A2A 调用必须带登录令牌和 `message.kind=message`。名片地址包含 `/api/a2a/agents/{id}/.well-known/agent-card.json`。改名片名称后，线上名片变，`url` 仍是系统生成的地址。技能没有名称时返回 400。
- 上传一份带 Markdown 标题的文档，策略为 `heading`，检索命中的上下文含章节。一份没有结构的文档策略为 `recursive`。
- GraphRAG 知识库在绑定对话模型后能建图，并按局部或全局返回。
- 知识库私有文档不会出现在其他用户的检索里。
- 同一会话能记住短期对话；长期记忆只属于当前身份；否定后删除。
- 静态资源更新后，页面请求的脚本地址带有新的缓存参数。
