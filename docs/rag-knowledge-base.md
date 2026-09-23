# Agent 知识库：用户上传文档、自动清洗、自动分块

状态：待批准。批准前不改代码。

本文是第一版的实现说明，已包含后续讨论的结论。范围是本仓库的 Java 控制面：Spring Boot 3.5、MySQL、静态页 `static/app.js`、AgentScope `ReActAgent`。

## 1. 要解决的问题

用户给某个 Agent 上传自己的业务文档（制度、产品说明、FAQ、表格）。上传后系统自己完成清洗和分块，对话时 Agent 按问题检索这些文档并引用原文，而不是把整份文件塞进系统提示词。

现在的 `search_knowledge` 只在已启用 Skill 的说明里做子串匹配，和用户文档无关。这个工具保持原样。知识库走新工具 `search_documents`。

## 2. 数据放在哪

三处各存一份不同的东西，分块原文不复制到 MySQL。

| 存放处 | 内容 |
| --- | --- |
| Milvus | 分块原文、标题、顺序、稠密向量。BM25 稀疏向量由 Milvus 从分块原文生成。检索、重排序和分块预览都读这里 |
| MySQL | 知识库配置、所选向量数据库、所选重排序服务、文档状态、清洗摘要、分块策略。不存分块，不存向量 |
| 磁盘 `rag-files/` | 用户上传的原始文件（PDF、Word 等），供失败后重试。原始文件不是分块 |

## 3. 第一版边界

做：

- 租户内的知识库，可绑定到一个或多个本平台 Agent。
- 上传 txt、md、html、csv、docx、pdf。
- 规则清洗，并记下清洗摘要。
- 按文档形态自动选出一种分块策略，界面展示选择理由，允许用户改策略后重建索引。
- 向量模型生成稠密向量，和分块原文一起写入该知识库选中的 Milvus。
- 关键词与向量混合检索：Milvus 内稠密向量一路、BM25 一路，RRF 合成候选。
- 向量数据库、重排序服务都可登记多套。每个知识库自己选择用哪一套。重排序可以不选。
- Agent 绑定了已就绪文档后，多一个检索工具。

不做：

- 不改外部 HTTP Agent。对方平台收不到这些文档。绑定知识库会被拒绝。
- 不用大模型改写原文。清洗只做确定性规则。
- 不在 MySQL 存分块或向量，也不在应用进程里做全表余弦。
- 第一版向量库类型只实现 Milvus。pgvector、Elasticsearch 不在本版实现，但连接表的 `type` 留着，避免以后加类型时改表。
- 不做跨文档去重、OCR、图片理解、增量段落编辑。扫描版 PDF 抽不出文字时失败，提示换成可复制文本的文件。
- 不把文档正文写进系统提示词。

## 4. 和现有结构怎么接

知识库按 Skill、MCP 同一套资源模型做。一份制度可以绑给多个 Agent。

| 现有机制 | 知识库怎么用 |
| --- | --- |
| `TenantOwnedEntity` | 知识库、文档、向量库连接、重排序连接都带 `tenant_id`、`owner_id` |
| `agents.skill_ids` 这类 JSON 列 | 新增 `agents.knowledge_ids` |
| `SchemaMigrator` | 启动时建表、补列。`ddl-auto` 仍是 `none` |
| `ResourceKind` + `Permissions` | 新增 `KNOWLEDGE`，权限 `knowledge:read` / `knowledge:write` |
| `BindingValidator` | 保存 Agent 时校验知识库属于同一租户且已启用 |
| `ToolRuntime.agentTools` | 有就绪文档才暴露 `search_documents` |
| `ToolRuntime.buildSystemPrompt` | 追加一句：回答文档中的事实前先检索 |
| `WorkspaceStore` 同级目录 | 原始文件放在 `rag-files/`，不放进 Agent 工作区 |
| 模型配置 | 对话模型不动。知识库另选向量模型、向量数据库、可选的重排序服务 |

HTTP 接入的 Agent（`http_agent_ids` 非空）继续不走本平台工具。

## 5. 数据模型

### 5.1 `vector_stores`

租户内可登记多套向量数据库。知识库创建时必选其一。第一版 `type` 只接受 `milvus`。

| 列 | 说明 |
| --- | --- |
| `name` | 租户内唯一，最长 100 |
| `type` | `milvus` |
| `uri` | 例如 `http://127.0.0.1:19530` |
| `database_name` | Milvus database，默认 `default` |
| `token` | 可空。有用户名密码时存 `user:password`。接口不回传原文，只回 `has_token` |
| `enabled` | 停用后不能被新知识库选中。已绑定的库在检索和入库时失败，并提示连接已停用 |

维度不记在连接上。同一套 Milvus 可以给多个知识库用，每个知识库一个 collection，维度跟该库的向量模型走。

Collection 名称：`kb_{tenantId}_{knowledgeId}`。

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `chunk_id` | Int64 主键 | 控制面生成的唯一值，只存在 Milvus |
| `document_id` | Int64 | 按文档删除、预览、检索过滤 |
| `knowledge_id` | Int64 | 过滤 |
| `tenant_id` | Int64 | 过滤，防止串租户 |
| `generation` | Int64 | 这一版分块的代次。重建时用来删掉旧块 |
| `ordinal` | Int64 | 文档内顺序 |
| `heading` | VarChar(512) | 标题路径 |
| `content` | VarChar(8192) | 分块原文，唯一副本。检索、重排序、界面预览都读它 |
| `dense` | FloatVector | 稠密向量，度量 COSINE，索引 HNSW |
| `sparse` | SparseFloatVector | Milvus BM25 Function 从 `content` 生成，Java 不计算稀疏向量 |

BM25 Function 定义在 collection 上：输入 `content`，输出 `sparse`。分析器使用 Milvus 的 `chinese`。稀疏索引为 `SPARSE_INVERTED_INDEX`，度量 `BM25`。

单块硬上限是目标长度的 1.6 倍，且不超过 8192 字符。超长块在分块阶段按句号、换行切开，因此 `content` 不会被截断后入库。

### 5.2 `rerank_stores`

重排序不是另一套向量库。它是混合检索之后的交叉编码器服务。知识库可选一套；不选则候选按 Milvus 的 RRF 名次截断到 `top_k`。

| 列 | 说明 |
| --- | --- |
| `name` | 租户内唯一，最长 100 |
| `type` | 第一版只接受 `http` |
| `base_url` | 服务根地址，必填，不含具体路径。不内置某一家的默认地址 |
| `model_id` | 传给重排序接口的模型名，例如 `gte-rerank`、`bge-reranker-v2-m3` |
| `api_key` | 可空。接口不回传原文，只回 `has_credential` |
| `enabled` | 停用后，已选中它的知识库检索失败并说明原因。不静默改成只做 RRF |

请求：

```http
POST {base_url}/rerank
```

```json
{ "model": "模型名", "query": "用户问题", "documents": ["候选块正文"], "top_n": 5 }
```

响应用 `results[].index` 和 `results[].relevance_score`。候选正文来自 Milvus 的 `content`，不来自 MySQL。

### 5.3 `knowledge_bases`

| 列 | 说明 |
| --- | --- |
| `id`, `tenant_id`, `owner_id`, `created_at`, `updated_at` | 与其他资源相同 |
| `name` | 租户内唯一，最长 100 |
| `description` | 最长 300 |
| `embedding_model_id` | 指向 `model_configs.id`，必须是 `purpose=embedding` |
| `vector_store_id` | 指向 `vector_stores.id`，必填 |
| `rerank_store_id` | 指向 `rerank_stores.id`，可空 |
| `embedding_dimension` | 可空。该库第一次嵌入成功后写入。之后拒绝不同维度的向量模型 |
| `enabled` | 停用后不再检索。Milvus 中的分块保留 |
| `top_k` | 最终返回条数，默认 5，范围 1–10 |
| `candidate_k` | 混合检索候选数，默认 20，范围 10–50。重排序只看这些候选 |
| `score_threshold` | 仅在配置了重排序时生效，默认 0.3 |

`model_configs` 增加 `purpose`：`chat`（默认，兼容旧数据）或 `embedding`。向量模型不出现在对话模型下拉里。

### 5.4 `knowledge_documents`

| 列 | 说明 |
| --- | --- |
| `knowledge_id` | 所属知识库 |
| `filename` | 展示名。做路径清洗，不直接当磁盘路径 |
| `media_type` | 识别后的类型 |
| `byte_size` | 原始字节 |
| `storage_path` | 相对 `rag-files/` 的路径，指向原始文件 |
| `status` | `queued` / `processing` / `ready` / `failed` |
| `error_message` | 失败原因 |
| `clean_summary` | JSON：删了多少空行、页眉、重复行 |
| `profile` | JSON：文档形态特征，见第 7 节 |
| `strategy` | 自动选中的策略名 |
| `strategy_reason` | 一两句中文理由 |
| `strategy_override` | 用户改过则为策略名，否则空。重建时优先用它 |
| `chunk_count` | 当前代次写入 Milvus 的块数 |
| `generation` | 当前已生效的分块代次。预览和检索只读这一代 |
| `content_hash` | 清洗后全文的 SHA-256。哈希、向量模型、向量库、策略都没变则跳过重建 |
| `embedding_model` | 本次入库使用的向量模型名 |

不建 `knowledge_chunks` 表。

`agents.knowledge_ids` 为 JSON 长整型数组，可空。旧行读取时当成空列表。

原始文件目录：`{rag-files-dir}/tenants/{tenantId}/knowledge/{knowledgeId}/{documentId}-{安全文件名}`。`rag-files-dir` 默认是进程工作目录下的 `rag-files`，可用 `agentforge.rag-files-dir` 覆盖。

## 6. 上传与处理流水线

上传接口立刻把原始文件落盘，文档行写成 `queued`，然后返回。索引在后台单线程执行。界面轮询文档状态。

单文件上限 15MB。`spring.servlet.multipart` 从 6MB 调到 16MB。允许的扩展名：`.txt` `.md` `.markdown` `.html` `.htm` `.csv` `.docx` `.pdf`。压缩包、可执行文件、带宏的 `.doc` 直接拒绝。

```text
queued → processing → ready
                    ↘ failed
```

`processing` 内部四步。任何一步失败都写成 `failed`，保留原始文件，已写入的半截分块留在 Milvus 但 `generation` 不前进，检索读不到。

1. **抽取**。txt/md 按 UTF-8 读，失败再试 GB18030。html 去脚本和样式后取正文。csv 转成 Markdown 表，首行当表头。docx 用 Apache POI 抽段落和表格，标题样式变成 Markdown 标题。pdf 用 PDFBox 按页抽文本，页与页之间插入分页标记供清洗使用。
2. **清洗**。见第 7 节。清洗后正文短于 20 个有效字符则失败。
3. **画像、选策略、分块**。见第 8 节。
4. **写入 Milvus**。调用所选向量模型的 `/embeddings`，每批最多 16 块。把分块原文、标题、顺序、代次和稠密向量 upsert 到所选 collection。稀疏向量由 BM25 Function 生成。MySQL 在整批成功后才把 `generation`、`chunk_count`、`content_hash`、`embedding_model` 和 `status=ready` 写上，并按 `document_id` 删掉 Milvus 里更旧的代次。

同一文档同时只允许一个处理任务。处理中再次重建返回 409。

删除文档时，先按 `document_id` 删除 Milvus 实体，再删原始文件和 MySQL 文档行。删除知识库时，空库直接删；非空需要 `force=true`，drop 对应 collection，并解除所有 Agent 的绑定。Milvus 不可达时删除返回 503，不先删 MySQL 行。

换向量模型、换向量数据库或改分块策略会重建。换重排序服务不重建，下一次查询生效。

## 7. 自动清洗

清洗不调用模型。结果写入 `clean_summary`。

按顺序执行：

1. Unicode 规范化（NFKC），去掉 BOM 和空字符。
2. 换行统一成 `\n`，去掉行尾空白。
3. HTML 来源去掉常见页眉页脚句（「版权所有」「All rights reserved」「Cookie」整行）。
4. PDF：同一行在超过一半的页首或页尾重复出现时，视为页眉页脚删除。单独成行的纯页码（`1`、`- 12 -`、`第 3 页`）删除。
5. 连续 3 个以上空行压成 1 个。行内连续空格压成 1 个。代码围栏内部不动。
6. 连续重复 4 次以上的相同非空行只留 1 次。
7. 保留 Markdown 标题、列表、表格和代码围栏。不合并标题与正文。

摘要字段：`removed_blank_lines`、`removed_headers`、`removed_page_numbers`、`collapsed_duplicate_lines`、`chars_before`、`chars_after`。

## 8. 自动选择分块策略

先做文档画像，再给 6 种策略打分，取得分最高者。分数和理由写入文档行。用户可以改成另一种策略并重建，此时 `strategy_override` 生效，画像仍保留。

### 8.1 画像

在清洗后的全文上统计：

- `chars`：字符数
- `cjk_ratio`：中日韩字符占比
- `heading_count`：Markdown 标题行数（docx 标题已转成 Markdown）
- `avg_paragraph_chars`：按空行切开的段落平均长度
- `code_ratio`：代码围栏内字符占比
- `table_ratio`：表格行字符占比
- `qa_pairs`：匹配 `^问[:：]` / `^Q[:：.]` 且紧跟答的次数
- `article_hits`：匹配 `第[一二三四五六七八九十0-9]+条` 的次数

目标块大小：

| 条件 | 目标长度 | 重叠 |
| --- | --- | --- |
| `cjk_ratio >= 0.3` | 450 字 | 60 字 |
| 其他 | 1000 字符 | 120 字符 |

`qa` 和 `table_row` 不做字符重叠。

### 8.2 策略

| 策略 | 切法 | 适合 |
| --- | --- | --- |
| `heading` | 按标题切段，段内超过目标长度再按段落合并或拆开。块首带标题路径 | 有目录结构的手册、制度 |
| `paragraph` | 按空行成段，相邻短段合并到目标长度 | 普通说明文 |
| `sentence` | 按 `。！？；` 或 `.?!` 切句再合并 | 几乎没有空行的长文 |
| `qa` | 一问一答一块，问题写进 `heading` | FAQ |
| `article` | 按「第 N 条」切开，条内再按目标长度拆 | 规章、合同条款 |
| `table_row` | 每个数据行一块，块内重复表头 | CSV、以表格为主的说明 |
| `recursive` | 分隔符优先级：空行、换行、句号、空格 | 以上都不明显时的兜底 |

`recursive` 不参与打分，只在所有专项策略得分都低于 1 时使用。

### 8.3 打分

分数是整数。并列时优先级为：`qa` > `article` > `table_row` > `heading` > `paragraph` > `sentence`。

- `qa`：`qa_pairs >= 3` 得 5，否则 0。
- `article`：`article_hits >= 5` 得 5，否则 0。
- `table_row`：`table_ratio >= 0.45` 得 5；`>= 0.25` 得 3。
- `heading`：`heading_count >= 4` 且标题之间平均距离小于目标长度的 4 倍，得 4；只有 `heading_count >= 2` 得 2。
- `paragraph`：`avg_paragraph_chars` 在目标长度的 0.2–1.2 倍之间得 3。
- `sentence`：`avg_paragraph_chars` 大于目标长度的 2 倍得 3。
- `code_ratio >= 0.4` 时，`heading` 额外 +1，`sentence` 置 0。

理由用模板生成，不调用模型。例如：「识别到 12 个标题，章节长度接近目标块大小，使用按标题分块。」

## 9. 嵌入与检索

查询分两段：

1. 在该知识库选中的 Milvus 里做混合检索。稠密向量一路，BM25 一路，RRF 合成候选。
2. 仅当知识库选了重排序服务时，用该服务对候选重新打分，再按 `score_threshold` 过滤。

### 9.1 向量模型

知识库创建时必选一个 `purpose=embedding` 的模型，以及一套 `type=milvus` 的向量数据库。调用方式与现有对话模型相同：`base_url` + `api_key`（或 `api_key_ref` 指向的环境变量），请求 `POST {base_url}/embeddings`，body 为 `{ "model": model_id, "input": [文本...] }`。

通义千问若 `base_url` 为空，走 `https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`。密钥缺失或 Milvus 不可达时，文档停在 `failed`。不回退到 MySQL 关键词扫描。

维度与 `knowledge_bases.embedding_dimension` 不一致时拒绝保存，返回 422。

### 9.2 查询

`search_documents` 只有参数 `query`。每个已绑定且已启用的知识库单独检索，再合并。

1. 从 MySQL 取出该库 `status=ready` 的文档 id 和当前 `generation`。没有就绪文档则跳过该库。
2. 对 query 做与清洗相同的空白规范化。
3. 调一次 embeddings，得到查询稠密向量。
4. 对 collection 做 Milvus hybrid search。过滤表达式为 `tenant_id == {租户} && knowledge_id == {库} && document_id in [就绪文档] && generation == 该文档的当前代次`。代次按文档不同，表达式按文档分组后合并结果。两路各取 `candidate_k` 条：
   - 稠密：`dense`，COSINE。
   - 稀疏：`sparse`，BM25，文本为规范化后的 query。
5. 融合用 Milvus `RRFRanker`，平滑常数 60。
6. 未选重排序：按 RRF 名次取 `top_k`。RRF 分不是 0–1 相似度，不做 `score_threshold`。
7. 选了重排序：候选 `content` 按 RRF 顺序交给 `/rerank`，`top_n` 为 `top_k`。丢掉 `relevance_score < score_threshold` 的命中。重排序调用失败、密钥缺失或连接被停用时，这一库失败并写入 trace，不改成只返回 RRF。
8. 多库命中按最终分从高到低合并，总数不超过 10。没有重排序分的库，用 `1 / (60 + rank)` 仅做跨库排序，不参与阈值。

调试检索额外返回 `vector_store`、`retriever`（固定 `dense+bm25`）、`reranker`（服务名或 `none`）。分块预览按 `document_id` 和当前 `generation` 从 Milvus 读前 20 块的 `ordinal`、`heading`、`content`。Milvus 不可达时预览返回 503。

返回给模型的文本：

```text
【1】知识库 / 文件名 / 标题
正文…
【2】…
```

没有命中时返回「知识库中没有与该问题足够相关的内容。」

有就绪文档时，系统提示词追加：

> 用户问题涉及已绑定知识库中的事实、数字、条款或流程时，必须先调用 search_documents，再根据工具返回的原文回答。知识库没有命中时就说明不知道，不要用常识补全这些事实。

调试台不把整库注入上下文。工具调用记入现有 trace。

## 10. 接口

读权限 `knowledge:read`，写权限 `knowledge:write`。Agent 绑定仍用 `agent:write`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/vector-stores` | 当前租户的向量数据库连接 |
| POST | `/api/vector-stores` | 登记。body：`name` `type=milvus` `uri` `database_name` `token` |
| POST | `/api/vector-stores/{id}/test` | 能列出 database 即成功 |
| PUT | `/api/vector-stores/{id}` | 改地址、库名、令牌、启用 |
| DELETE | `/api/vector-stores/{id}` | 仍被知识库引用时 409 |
| GET | `/api/rerank-stores` | 当前租户的重排序服务 |
| POST | `/api/rerank-stores` | 登记。body：`name` `type=http` `base_url` `model_id` `api_key` |
| POST | `/api/rerank-stores/{id}/test` | 用「检索探针」和两段短文本打一次 `/rerank` |
| PUT / DELETE | `/api/rerank-stores/{id}` | 删除时若仍被引用则 409 |
| GET | `/api/knowledge` | 知识库列表，带文档数、就绪块数、向量库名、重排序名 |
| POST | `/api/knowledge` | 创建。body：`name` `description` `embedding_model_id` `vector_store_id` `rerank_store_id` |
| PUT | `/api/knowledge/{id}` | 改名称、说明、启用、top_k、candidate_k、阈值、向量模型、向量库、重排序 |
| DELETE | `/api/knowledge/{id}` | 空库直接删；非空需要 `force=true`，并 drop collection |
| GET | `/api/knowledge/{id}/documents` | 文档列表和状态 |
| POST | `/api/knowledge/{id}/documents` | `multipart/form-data`，字段名 `file`，最多 10 个 |
| GET | `/api/knowledge/{id}/documents/{docId}` | 清洗摘要、画像、策略、理由；前 20 块从 Milvus 读取 |
| POST | `/api/knowledge/{id}/documents/{docId}/reindex` | body 可选 `strategy`。空则重新自动选择 |
| DELETE | `/api/knowledge/{id}/documents/{docId}` | 删除 Milvus 分块、原始文件和文档行 |
| POST | `/api/knowledge/{id}/search` | 调试检索。body：`query`。不经过 Agent |

Agent 的创建和更新 body 增加 `knowledge_ids`。列表接口增加 `bound_knowledge`：`[{id, name, ready_documents}]`。

错误沿用 `ApiException`：类型不对 415，超限 413，跨租户 404，处理中重建或连接仍被引用 409，没配向量模型、维度不一致、向量库类型不是 milvus 422，Milvus 或重排序不可达 503。

## 11. 界面

侧栏增加「知识库」，权限 `knowledge:read`。页面三个列表：

- **向量数据库**：登记 Milvus 并探测。卡片显示名称、地址、database、探测结果。
- **重排序**：登记 HTTP 重排序服务并探测。可以一条都不建。
- **知识库**：卡片显示名称、向量模型、向量数据库、重排序（或「未启用」）、文档数、就绪状态。创建表单三个下拉：向量模型、向量数据库（必选）、重排序（可选，含「不重排」）。

模型页增加用途字段。进入知识库后可上传文件。列表显示状态、策略名、块数、失败原因。详情抽屉显示清洗摘要、自动选择理由、策略下拉和分块预览。预览正文来自 Milvus。搜索框展示命中块、RRF 名次、重排序分，以及实际使用的向量数据库和重排序服务。

Agent 编辑表单增加知识库多选，交互对齐 Skill。卡片标签增加知识库名称。HTTP Agent 不显示该字段。调试台不新增页面。

## 12. 代码落点

批准后按这个包实现，不把逻辑堆进 `ResourceController`。

| 路径 | 职责 |
| --- | --- |
| `domain/KnowledgeBase.java` `KnowledgeDocument.java` `VectorStore.java` `RerankStore.java` | 实体。没有分块表 |
| `repo/KnowledgeBaseRepository.java` `KnowledgeDocumentRepository.java` `VectorStoreRepository.java` `RerankStoreRepository.java` | JPA |
| `config/SchemaMigrator.java` | 建表、`agents.knowledge_ids`、`model_configs.purpose` |
| `rag/DocumentExtractor.java` | 按类型抽文本 |
| `rag/DocumentCleaner.java` | 第 7 节规则 |
| `rag/ChunkStrategySelector.java` | 画像、打分、分块 |
| `rag/EmbeddingClient.java` | OpenAI 兼容 embeddings |
| `rag/MilvusVectorStore.java` | collection、BM25 Function、写入分块原文、按文档和代次预览、hybrid search、按文档删除旧代次 |
| `rag/HttpRerankClient.java` | `/rerank` |
| `rag/KnowledgeIndexService.java` | 状态机、后台队列、写入所选向量库 |
| `rag/KnowledgeSearchService.java` | 混合检索，再按所选重排序服务重排 |
| `web/KnowledgeController.java` | 第 10 节接口 |
| `agent/ToolRuntime.java` | 注册并执行 `search_documents`，提示词一句 |
| `agent/BindingValidator.java` | 绑定校验 |
| `static/app.js` | 知识库页、向量库与重排序登记、Agent 多选、模型用途 |

依赖增加：`org.apache.pdfbox:pdfbox`、`org.apache.poi:poi-ooxml`、`io.milvus:milvus-sdk-java`（2.5 及以上，需要 hybrid search 和 BM25 Function）。PDF 与 POI 能跟 Spring Boot BOM 就用 BOM，否则在 `pom.xml` 写明确版本。Milvus SDK 取实现时 Maven Central 上 2.5 线的最新稳定版，并写死在 `pom.xml`。

`search_knowledge`、Skill 注入、MCP 工具链路不改行为。

## 13. 验收

1. 上传一份带标题的 Markdown，状态变为就绪，策略为 `heading`，理由里出现标题个数。预览块来自 Milvus，MySQL 中没有分块表。
2. 上传一份「第 N 条」规章文本，策略为 `article`。
3. 上传一份问答列表，策略为 `qa`，预览里一块只含一组问答。
4. PDF 页眉在多数页重复时，Milvus 中的分块不再包含该页眉。
5. 扫描版 PDF 变为 `failed`，文案说明需要可复制文本。
6. 用户把策略改成 `paragraph` 后重建，新块按空行合并。检索只命中新代次。
7. 未绑定该知识库的 Agent 调用不到这些块；另一租户的 Agent 也调用不到。
8. 绑定后的 Agent 在调试台被问到文档中的原句时，trace 里出现 `search_documents`，回答能对上 Milvus 中的句子。
9. 知识库没有相关内容时，回答表明不知道。
10. 停用知识库后，已绑定 Agent 不再返回该库的检索结果。
11. 超过 15MB 或 `.exe` 上传被拒绝。
12. 向量模型密钥为空或 Milvus 不可达时，文档失败，对话不假装已经建好索引。
13. 同一租户登记两套 Milvus 时，分块只写入所选的那一套。调试检索的 `vector_store` 是所选连接的名称。
14. 未选重排序时，检索仍同时走稠密向量和 BM25，`reranker` 为 `none`。
15. 选了重排序后，最终顺序以重排序分为准；低于 `score_threshold` 的候选不返回。该服务停用或不可达时，这一库的检索失败，不静默退回 RRF。
16. 更换向量数据库后，查询打到新连接。重建完成前文档处于 `processing`，检索跳过它们。

## 14. 已确定的默认值

按下列实现。要改的话在批准时指出。

- 知识库是独立资源，用 `knowledge_ids` 绑到 Agent。
- 清洗不用大模型。
- 单文件 15MB，单次最多 10 个文件。
- 向量数据库第一版只有 Milvus。知识库必选一套已登记连接。稠密向量与 BM25 在 Milvus 内混合检索，RRF 的 k 为 60。
- 重排序是可选的 HTTP `/rerank` 服务。不选时不做 0–1 阈值过滤。选了但调用失败时，不静默降级。
- 分块原文只存在 Milvus。MySQL 不存分块，也不存向量。磁盘只留原始文件。
- 检索通过工具 `search_documents`，不把文档注入系统提示词。
