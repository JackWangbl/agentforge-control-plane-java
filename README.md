# AgentForge Control Plane（Java）

面向团队试用的 Agent 控制面。用浏览器管理 Agent、模型、知识库、工具和会话，调试时由 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 的 ReAct 运行时调用已绑定的模型和工具。

打开 http://127.0.0.1:8080 即可试用。未登录可以先用 5 分钟；到期后必须登录。

## 能做什么

| 模块 | 说明 |
| --- | --- |
| 运行概览 | 请求量、成功率、延迟和最近会话 |
| 会话查询 | 按 Session、Agent、状态检索真实对话 |
| AgentScope Studio | 嵌入本机 Studio，查看轨迹和 Token |
| 数据测试 | 数据集、回归测试和性能测试 |
| A/B 实验 | 按权重把流量分到不同 Agent |
| Agent 调试台 | 用 Agent 自己绑定的模型对话，右侧展示执行链路 |
| Agent / HTTP 接口 | 配置系统提示词、模型、技能、MCP 和知识库；也可接入外部 HTTP Agent |
| MCP / Skill | 登记工具服务和可复用技能 |
| 知识库 | 上传文档，自动清洗、选择分块策略，混合检索后交给 Agent |
| 向量数据库 | 维护租户默认的 Milvus 连接 |
| 模型配置 | 对话、向量、重排序三类模型。试用账号看不到这一页 |
| 沙箱 | 隔离执行代码和命令 |
| 权限管理 | 租户、角色和成员 |

调试台里的知识库检索出现在执行链路的「调用工具 search_documents」。返回给模型的是章节上下文，检索用的是更短的子块。

## 技术栈

- Java 21，Spring Boot 3.5.6，Maven
- AgentScope Java 2.0.3（ReActAgent、OpenAI 兼容接口、DashScope）
- MySQL 8（`utf8mb4`）
- Milvus 2.5（知识库向量检索，Java SDK 2.5.10）
- 文档解析：PDFBox、Apache POI
- 控制台是服务端渲染的静态页面，位于 `src/main/resources/static`

服务只监听 `127.0.0.1:8080`。

## 环境

- JDK 21 或更高
- Maven 3.9+
- MySQL 8.0+
- 要用知识库检索时，再准备 Milvus，默认地址 `http://127.0.0.1:19530`

确认 Java 版本：

```bash
java -version
```

如果系统默认还是 Java 8，先把 `JAVA_HOME` 指到 JDK 21，再把 `$JAVA_HOME/bin` 放到 `PATH` 前面。

## 准备数据库

1. 建库：

```sql
CREATE DATABASE agentforge CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'agentforge_app'@'%' IDENTIFIED BY '请换成你自己的密码';
GRANT ALL PRIVILEGES ON agentforge.* TO 'agentforge_app'@'%';
FLUSH PRIVILEGES;
```

2. 导入基础表。本仓库启动时**不会**创建用户、Agent、模型这些核心表，只会补齐后来增加的表和列（知识库、向量库、试用凭证等）。请先导入与控制面一致的基础结构，表包括：

`tenants`、`roles`、`users`、`auth_tokens`、`agents`、`mcp_servers`、`skills`、`model_configs`、`workflows`、`sandbox_policies`、`conversations`、`chat_messages`、`traces`、`datasets`、`dataset_cases`、`evaluation_runs`、`evaluation_results`

已有控制面库时，直接在那个库上启动即可，不必清空数据。启动器发现缺表或缺列会自己补上，已有行不会被覆盖。

3. 在项目根目录写 `.env`。这个文件已被 git 忽略，不要提交。

```properties
APP_ENV=development
DATABASE_URL=mysql+pymysql://agentforge_app:请换成你自己的密码@127.0.0.1:3306/agentforge?charset=utf8mb4
SECRET_KEY=请换成一段随机字符串
AGENTSCOPE_STUDIO_URL=
OTEL_EXPORTER_OTLP_ENDPOINT=
OPENAI_API_KEY=
DASHSCOPE_API_KEY=
```

`DATABASE_URL` 使用 SQLAlchemy 风格。程序会把它改写成 JDBC，并拆出用户名和密码。没有这一行时，数据源配置是空的，服务起不来。

也可以继续使用下面这些可选变量：

| 变量 | 作用 |
| --- | --- |
| `AGENTSCOPE_STUDIO_URL` | Studio 地址，例如 `http://127.0.0.1:3000`。留空则调试台里的 Studio 页提示未连接 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry 导出地址 |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_HOST` | Langfuse 追踪 |
| `AUTH_DEV_USER` | 开发期免登录，填已有用户名后，所有请求都算这个人。试用环境请留空 |
| `WORKSPACES_DIR` | Agent 工作空间。默认是项目下的 `workspaces/` |
| `RAG_FILES_DIR` | 上传原文。默认是项目下的 `rag-files/` |
| `ALLOW_UNSAFE_LOCAL_SANDBOX` | 默认 `false`。没有 Docker 时不要打开 |
| `SANDBOX_DEFAULT_IMAGE` | 默认 `python:3.11-slim` |
| `SANDBOX_EGRESS_NETWORK` | 沙箱出网网段。未配置时联网命令会失败 |
| `BROWSER_ALLOW_PRIVATE_NETWORK` | 默认 `false`，浏览器工具不访问内网地址 |

模型密钥不要写进 `.env` 再提交。对话、向量和重排序的密钥在控制台「模型配置」里填写，保存在数据库中。

## 启动

在项目根目录执行：

```bash
mvn spring-boot:run
```

看到 `Started AgentForgeApplication` 和 Hikari 的 `Start completed` 后，打开：

http://127.0.0.1:8080

停掉服务直接结束这个进程。如果端口仍被占用：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

结束列出的进程后再启动。

打包运行：

```bash
mvn -DskipTests package
java -jar target/agentforge-control-plane-1.0.0.jar
```

运行测试：

```bash
mvn test
```

## 未登录试用

第一次打开页面不必登录。服务会签发 5 分钟试用身份「试用访客」，右上角显示剩余时间。

- 刷新或关掉页面再打开，计时不重新开始。浏览器会记住这次试用，7 天内不能靠清本地 token 再领 5 分钟。
- 试用期内可以点「登录」，也可以点「继续试用」。
- 时间到后页面被挡住，必须登录。登录失败时，表单里会出现红色提示，右下角也会弹出同样的文字。
- 试用账号可以进入除「模型配置」以外的页面。模型列表接口会拒绝，避免未登录的人看到密钥。
- 向量数据库、知识库、调试台、权限管理都可以看。

登录之后按账号自己的角色显示菜单，模型配置会重新出现。

## 默认账号

库里还没有这些用户时，启动会自动创建。密码写在种子数据里，只适合本机试用。对外公开前请立刻改掉。

| 用户名 | 初始密码 | 角色 |
| --- | --- | --- |
| `linmo` | `admin123` | 平台管理员，权限为 `*` |
| `developer` | `dev123` | Agent 开发者 |
| `auditor` | `audit123` | 审计员，只能看会话和链路 |
| `demo` | `demo123` | 演示租户的租户管理员 |

`guest` 是试用身份，密码随机，不能拿来登录。

同一租户内，资源默认只给属主和被分享的人。知识库新建后是私有的；设成「租户公开」后，拥有 `knowledge:read` 的同租户成员可以检索。平台管理员能在管理界面看到别人的库，但不能仅凭管理员身份检索别人的私有文档。

## 接上模型和知识库

试用对话前，用管理员登录，在「模型配置」里新增模型。用途有三种：

- **对话**：调试台和 Agent 绑定用。填写 OpenAI 兼容的 Base URL、模型名和密钥。DeepSeek、Kimi、GLM、Qwen 都可以。
- **向量**：知识库建索引用。智谱上应填写 `embedding-3` 或 `embedding-2`，不要填对话模型名。
- **重排序**：可选。智谱上模型名填写 `rerank`，地址形如 `https://open.bigmodel.cn/api/paas/v4`。没配重排序时，检索使用混合检索自己的排序。

Agent 编辑页选择对话模型。调试台不再单独选模型，始终用这个 Agent 绑定的模型。

知识库：

1. 在「向量数据库」登记 Milvus，并设为默认。地址示例：`http://127.0.0.1:19530`，Database 填 `default`。
2. 新建知识库时选择向量模型、可见性和可选的重排序模型，不用手填 Milvus 地址。新建时会复制当时的默认连接；之后改默认连接，不会搬走已经建好的库。
3. 上传 `txt`、`md`、`html`、`csv`、`docx`、`pdf`，单文件上限 16MB。
4. 程序会清洗正文，并在问答、条款、表格、标题、段落、句子几种策略里自动选一种。原文在磁盘，分块在 Milvus，MySQL 只存配置和状态。
5. 把知识库绑到 Agent。调试时，检索范围是「Agent 绑定的库」和「当前登录人有权检索的库」的交集。

Milvus 未启动时，向量库探测会失败，文档会停在失败状态。先确认 `19530` 端口可访问，再在页面上点「探测」。

## 目录

```text
src/main/java/com/agentforge/controlplane
├── access          登录、权限、试用身份
├── agent           AgentScope 运行时和工具调用
├── rag             清洗、分块、向量检索、重排序
├── web             HTTP 接口
├── config          数据源改写、启动时补表
└── seed            默认租户、角色和账号
src/main/resources/static    控制台页面
workspaces/                  每个 Agent 的会话和执行链路
rag-files/                   上传的原始文件
```

执行链路保存在对应 Agent 的 `workspaces/` 里，刷新调试台可以看到同一次会话。

## 开源试用时请注意

- `.env`、数据库密码、模型密钥、`SECRET_KEY` 都不要提交。
- 种子账号密码是公开的。仓库一旦公开，请修改或关闭这些账号。
- `AUTH_DEV_USER` 会跳过登录，公开环境必须留空。
- 试用账号能改 Agent、知识库和向量库连接，只能用于可丢弃的试用库。
- 本仓库目前没有 `LICENSE`。公开分发前请补上许可证，否则别人默认不能随意使用你的代码。
