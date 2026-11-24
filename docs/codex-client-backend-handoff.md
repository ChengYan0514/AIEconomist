# Codex 施工任务书：AI Economist 客户端 Java 后端大框架与参考文献自动标注

本文面向在独立 `git worktree` 中开发客户端后端的 Coding Agent（Codex）。请严格遵守本文划定的**目录边界、契约定义、数据库表结构与验证命令**，确保合并回主分支时零冲突且一次联调通过。

---

## 1. 背景与必须遵守的仓库规则

请先阅读仓库根目录的 [AGENTS.md](../AGENTS.md)、[GLOSSARY.md](../GLOSSARY.md) 和 [ADR 0005](adr/0005-client-host-and-external-capability-engines.md)。关键硬约束：

1. **技术栈**：Java 17、Spring Boot 3.3.13、`JdbcTemplate` / PostgreSQL，**禁止修改 `backend/pom.xml` 引入新第三方依赖**（本项目要求离线可编译，4 种引文样式与 BibTeX 请用纯 Java 确定性格式化器实现）。
2. **代码边界与体积**：
   - 依赖方向：`adapters → 用例 → model`。领域模型保持纯 Java（无 Spring / SQL 依赖）。
   - **每个 Java 类不超过 200 行，每个方法不超过 40 行**；超过时按职责拆分。
   - 不要创建 `Utils`、`Helper`、`Tools` 类；按具体职责命名（如 `CitationFormatter`、`BibtexExporter`）。
3. **术语一致**：遵循 [GLOSSARY.md](../GLOSSARY.md)——使用「引文陈述」（`CitationStatement`）和「引文标注任务」（`CitationTask`），不与管理端的「因果主张」混用。
4. **TDD**：每个核心业务规则（格式化器、元数据补全、状态机与用户隔离、引擎适配器）先写失败测试，实现后跑通测试。

---

## 2. 严格的目录写边界（禁止越界修改！）

基线提交已经一次性完成了所有共享文件改动（`Permission`、`Role`、`RoleMapper`、`SecurityConfiguration`、`WorkspaceAccess`、`V8__client_workspace.sql`、`application.yml`、`.env.example`、`docs/openapi-client.yaml`）。

### 允许新增/修改的目录（仅限以下两处）
- `backend/src/main/java/com/paperflow/client/**`
- `backend/src/test/java/com/paperflow/client/**`

### 严禁修改的文件与目录
- `frontend/**`（由主工作区并行开发）
- `backend/pom.xml`
- `backend/src/main/resources/db/migration/**`（`V8__client_workspace.sql` 已锁定所需全部表与索引，禁止新增或改动迁移）
- `backend/src/main/resources/application.yml`（已预留 `paperflow.client.*` 全量配置项）
- `backend/src/main/java/com/paperflow/main/**`（`Paperflow.java` 已启用 `scanBasePackages = "com.paperflow"`，请在 `com.paperflow.client.ClientConfiguration` 上使用 `@Configuration(proxyBeanMethods = false)` 与 `@EnableConfigurationProperties(ClientProperties.class)` 自动装配！）
- `backend/src/main/java/com/paperflow/adapters/web/ApiErrors.java`（请在 `com.paperflow.client.adapters.web.ClientApiErrors` 上使用 `@RestControllerAdvice(basePackages = "com.paperflow.client")` + `@Order(Ordered.HIGHEST_PRECEDENCE)` 独立翻译客户端领域异常，返回与 `ApiErrors.Error` 一致的 `{"code": "...", "message": "..."}` JSON！）
- 管理端既有业务包（`account`、`audit`、`paper`、`original`、`fulltext`、`openalex`、`job`、`systemstatus`、`causal`）及 `docs/openapi.yaml`、`docs/openapi-client.yaml`

---

## 3. 已锁定的配置、安全与数据库基线

### 3.1 安全上下文与用户信息获取
`SecurityConfiguration` 已将 `/api/client` 与 `/api/client/**` 绑定为 `access.require(CLIENT_ACCESS)`。
在任意客户端 `@RestController` 方法中，直接通过以下参数获取当前已认证且实时校验有效的平台用户：
```java
@RequestAttribute(WorkspaceAccess.CURRENT_ACCOUNT) AccountAccess account
```
使用 `account.username()` 作为任务归属标识；禁止信任前端传入的用户名。

### 3.2 已预留的 `application.yml` 配置项
请在 `com.paperflow.client.ClientProperties` 中绑定 `@ConfigurationProperties("paperflow.client")`：
```yaml
paperflow:
  client:
    citations:
      provider: stub               # stub | citations-retrieval-v1 | standard-v1 | disabled
      engine-url: http://127.0.0.1:8000
      request-timeout: PT10M
      poll-interval: PT1.5S
      max-active-per-user: 3
      max-concurrency: 4
    qa:
      enabled: false
      engine-url: ""
    writing:
      enabled: false
      engine-url: ""
```
读取 OpenAlex 快照 schema 时，请直接注入已有的 `WorkspaceProperties` Bean，使用 `workspace.openalexSchema()`（已做合法标识符校验），参考 [JdbcCausalWorkMetaQuery.java](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalWorkMetaQuery.java)。

### 3.3 已建好的数据库表（见 `V8__client_workspace.sql`）

1. **`v2_client_citation_task`**：
   - `task_id uuid PRIMARY KEY`
   - `username varchar(50) NOT NULL REFERENCES v2_admin(username)`
   - `title varchar(160) NOT NULL`
   - `status varchar(20) NOT NULL` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`)
   - `stage varchar(32) NOT NULL DEFAULT 'QUEUED'`
   - `progress double precision NOT NULL DEFAULT 0`
   - `original_text text NOT NULL`
   - `citation_style varchar(32) NOT NULL` (`apa`, `ieee`, `chicago-author-date`, `mla`)
   - `retrieval_policy jsonb NOT NULL DEFAULT '{}'::jsonb`
   - `engine_provider varchar(32) NOT NULL`
   - `engine_run_id varchar(128)`
   - `result_payload jsonb`
   - `annotated_text text`
   - `bibliography text`
   - `error_code varchar(64)`
   - `error_message text`
   - `created_at timestamptz NOT NULL DEFAULT now()`
   - `updated_at timestamptz NOT NULL DEFAULT now()`
   - `finished_at timestamptz`
2. **`v2_client_citation_event`**：
   - `event_id bigserial PRIMARY KEY`
   - `task_id uuid NOT NULL REFERENCES v2_client_citation_task(task_id) ON DELETE CASCADE`
   - `stage varchar(32) NOT NULL`
   - `event_type varchar(32) NOT NULL`
   - `progress double precision NOT NULL DEFAULT 0`
   - `message text NOT NULL`
   - `payload jsonb NOT NULL DEFAULT '{}'::jsonb`
   - `occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()`

只读复用的现有表（参考 [docs/database.md](database.md)）：
- 期刊目录：`v2_paper_read` + `${openalexSchema}.sources`
- 本地书目补全：`work`、`work_author`、`work_source`、`v2_paper` + `${openalexSchema}.sources`

---

## 4. 必须实现的全部功能与契约对齐

请逐条严格对齐 [docs/openapi-client.yaml](openapi-client.yaml) 中的所有路径、字段名、状态码和错误码。

### 4.1 客户端模块目录 (`GET /api/client/modules`)
固定顺序返回 3 个模块：
1. `citations`（「参考文献自动标注」）：
   - `provider = stub` → `status = STUB`，`message = "当前运行在内置演示模式，可体验完整标注与审阅流程"`
   - `provider = disabled` → `status = UNAVAILABLE`，`message = "参考文献自动标注引擎未启用"`
   - `provider = citations-retrieval-v1` 或 `standard-v1` → 通过短超时（2s）探测引擎健康接口（`/health` 或 `/v1/health`），健康返回 `AVAILABLE`，不可达返回 `UNAVAILABLE`（安全短提示，不泄露内网 URL 或异常堆栈）
2. `qa`（「学科知识问答」）：`enabled = false` 时返回 `status = COMING_SOON`，`provider = null`
3. `writing`（「论文写作智能体」）：`enabled = false` 时返回 `status = COMING_SOON`，`provider = null`

### 4.2 公共本地期刊目录 (`GET /api/client/journals`)
从 `v2_paper_read`（`WHERE NOT archived AND source_id IS NOT NULL AND btrim(source_id) <> ''`）按 `source_id` 聚合，关联 `${openalexSchema}.sources` 获取期刊名（缺失时回退 `source_id`），返回 `sourceId`、`name`、`paperCount`、`publishedCount`、`yearMin`、`yearMax` 与满足过滤条件的总期刊数 `total`。支持可选 `q`（大小写不敏感子串匹配 `name` 或 `source_id`）和 `limit`（1..200，默认 100）。

### 4.3 参考文献自动标注用例 (`com.paperflow.client.citation.*`)

建议包划分（保持每个类 < 200 行）：
```text
com.paperflow.client/
├── ClientConfiguration.java
├── ClientProperties.java
├── model/               # 领域异常 (ClientNotFound, ClientConflict, ClientUnavailable) 与值对象
├── catalog/             # ClientModuleService, ClientJournalService, ClientJournalStore
├── citation/
│   ├── model/           # CitationStyle, CitationTaskStatus, CitationStage, CitationStatement, CitationReference, EvidenceChunk, EvidenceStats, RetrievalPolicy...
│   ├── CitationTaskService.java      # 创建、列表、详情、取消、审阅重排、导出、删除（强制 username 隔离）
│   ├── CitationTaskRunner.java       # 有界线程池异步调度 + 启动对账（遗留 QUEUED/RUNNING 标为 FAILED）
│   ├── CitationEventHub.java         # 按 taskId 管理 SseEmitter 广播（参考 CausalEventHub）
│   ├── CitationTaskStore.java        # 持久化端口
│   ├── CitationEngine.java           # 语言无关的外部引文引擎端口
│   ├── LocalMetadataEnricher.java    # 本地 OpenAlex/v2_paper 批量书目补全端口与编排
│   ├── CitationFormatter.java        # 纯 Java 确定性格式化（apa / ieee / chicago-author-date / mla）
│   └── BibtexExporter.java           # 纯 Java BibTeX / Markdown / PlainText 导出器
└── adapters/
    ├── web/             # ClientModuleController, ClientJournalController, CitationTaskController, ClientApiErrors
    ├── persistence/     # JdbcClientJournalStore, JdbcCitationTaskStore, JdbcLocalMetadataEnricher
    └── engine/          # StubCitationEngine, LegacyCitationsRetrievalEngine, StandardHttpCitationEngine
```

#### 核心业务规则明细

1. **输入校验与活跃上限**：
   - `text` 必填非空且长度 <= 20,000；`citationStyle` 必须在 4 种白名单内；`yearMin`/`yearMax` 在 1000..9999 且 `yearMin <= yearMax`；`sourceIds` 最多 100 项，逐项 trim 去重，空数组视为不限来源。
   - `title` 省略或空白时，取 `text` 首句/前 60 字符自动生成标题。
   - 单用户活跃任务数（`QUEUED` + `RUNNING`）达到 `maxActivePerUser`（默认 3）时抛出 `ClientConflict("CITATION_CONFLICT", ...)`（HTTP 409）。
   - `provider = disabled` 时抛出 `ClientUnavailable("CITATION_ENGINE_UNAVAILABLE", ...)`（HTTP 503）。

2. **用户数据隔离**：
   - 列表仅查 `WHERE username = ?`；详情、SSE、取消、审阅、导出、删除全部校验归属；不存在或非本人任务一律返回 `404 CITATION_TASK_NOT_FOUND`（避免探测他人任务 ID）。

3. **本地书目元数据补全（`JdbcLocalMetadataEnricher`）**：
   - 引擎返回 `statements` 与各陈述下的全部 `supported_references`（不只是前 5 篇！）后，Java 宿主收集全部 `workId` 与规范化 `doi`（小写且去除 `https://doi.org/`、`http://dx.doi.org/`、`doi:` 前缀），批量查询：
     - `work`（`work_id`, `title`, `publication_year`, `doi`）
     - `work_author`（按 `work_id`, `author_position` / `author_id` 排序取作者姓名列表）
     - `work_source` + `${openalexSchema}.sources`（取 `source_id` 与期刊 `display_name`）
     - `v2_paper`（按 `matched_work_id` 或规范化 `doi` 查 `paper_id`, `title`, `authors`, `publication_year`, `source_id`, `landing_url`）
   - 合并优先级：本地库非空字段优先覆盖引擎原始字段（彻底消除原 `CitationsRetrievalSystem` 中备选文献标题为 `"Untitled Milvus Document"` 的缺陷），本地缺失字段回退引擎原始值；据此准确标注 `metadataSource`（`LOCAL_DB` / `ENGINE` / `LOCAL_DB_AND_ENGINE`）及本地 `paperId`。

4. **纯 Java 确定性引文重排与导出（`CitationFormatter` & `BibtexExporter`）**：
   - 支持白名单 4 种样式，针对每个 `needsCitation = true` 且 `selectedReferenceIds[statementId]` 非空的陈述，在 `endChar` 处的句末标点（`.!?。！？`）之前插入文内引文（若偏移失效则回退在 `originalText` 中按 `statement.text` 定位；按倒序插入避免偏移漂移）：
     - **`apa`**：文内 `(Author, 2023)` / `(Author A & Author B, 2023)` / `(Author et al., 2023)`，多篇以 `; ` 分隔；参考文献按作者姓氏+年份排序，格式 `Author, A. (Year). Title. *Venue*. https://doi.org/...`。
     - **`ieee`**：按正文首次出现顺序自动编号 `[1]`、`[2]`，同句多篇合并如 `[1, 2]` 或 `[1–3]`；参考文献按编号顺序输出 `[1] A. Author, "Title," Venue, Year. doi: ...`。
     - **`chicago-author-date`**：文内 `(Author 2023)` / `(Author A and Author B 2023)` / `(Author et al. 2023)`；参考文献按作者+年份排序。
     - **`mla`**：文内 `(Author)` / `(Author A and Author B)` / `(Author et al.)`；参考文献按作者排序。
   - **审核接口（`PATCH /api/client/citations/tasks/{taskId}/review`）**：
     - 仅允许 `status == SUCCEEDED`（否则 `409 CITATION_TASK_NOT_READY`）。
     - 校验 `selectedReferenceIds` 中所有 `statementId` 和 `referenceId` 合法，保留未传入的陈述原选结果，允许传入空数组取消某条陈述的所有引文。
     - 立即用 `CitationFormatter` 重算 `annotatedText` 与 `bibliography`，持久化更新 `citation_style`、`result_payload`、`annotated_text`、`bibliography`、`updated_at`，返回最新 `CitationTaskDetail`。
   - **导出接口（`GET /api/client/citations/tasks/{taskId}/export?format=txt|md|bibtex&style=...`）**：
     - `txt`：`annotatedText + "\n\nReferences\n" + bibliography`
     - `md`：含标题、`## 正文`、`## 参考文献`
     - `bibtex`：对所有陈述已采纳文献去重后生成规范 `@article{<citeKey>, author={...}, title={...}, journal={...}, year={...}, doi={...}, url={...}}`（转义大括号等特殊字符，`citeKey` 保证唯一）。

5. **SSE 进度流与取消语义**：
   - 每个任务阶段推进时先追加写入 `v2_client_citation_event` 并更新 `v2_client_citation_task.stage/progress/updated_at`，再通过 `CitationEventHub` 推送事件名 `progress`。
   - 终态（`SUCCEEDED`/`FAILED`/`CANCELLED`）追加终态事件后发送事件名 `complete` 并结束 emitter。
   - 客户端在任意时刻连接 `/events`：先顺序重放已落库的 `v2_client_citation_event`；若已终态则紧接着发 `complete` 并关闭；否则注册 live listener（注意去重 `eventId` 防止竞态重复）。
   - `POST /cancel`：把任务标为 `CANCELLED`（同时取消运行中的 `Future` 并尽力通知外部引擎取消），忽略取消后引擎晚到的回调结果，禁止覆盖已取消状态。
   - 应用启动时通过 `ApplicationRunner` 执行一次性对账：将上次进程异常退出遗留的 `QUEUED`/`RUNNING` 任务标记为 `FAILED`（`error_code = "INTERRUPTED"`）。

---

## 5. `CitationEngine` 端口与三种内置驱动

请定义清晰领域端口 `CitationEngine`（支持 `String providerId()`, `boolean isHealthy()`, `EngineResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled)`），并提供 3 种实现由 `paperflow.client.citations.provider` 选择：

### 5.1 `stub`（默认内置离线演示引擎）
- 无需任何外部服务即可端到端跑通。
- 对输入文本按句切分，识别 1–3 条代表性引文陈述；分阶段发出真实节奏的进度事件（每阶段延迟约 150–300ms，支持中途响应取消）；返回 4–6 篇经济学经典/示例候选文献（含 `workId`/`doi`/证据片段/评分/理由与 `evidenceStats`），默认前 2–3 篇入选 `initialSelectedReferenceIds`，其余留在 `supported_references` 供前端测试人工勾选换入。
- 若本地 `work`/`v2_paper` 有匹配记录，仍正常走 `LocalMetadataEnricher` 补全，以验证真实补全链路。

### 5.2 `citations-retrieval-v1`（对接现有 `CitationsRetrievalSystem`）
参考项目 `/data/bak/code/CitationsRetrievalSystem` 暴露以下 HTTP 接口（使用 JDK 内置 `java.net.http.HttpClient`，无需新依赖）：
- 健康检查：`GET {engineUrl}/health` → `200 {"status": "ok"}`
- 提交异步任务：`POST {engineUrl}/api/citation-jobs`
  - 请求体：`{"text": "...", "citation_style": "apa|ieee|chicago-author-date|mla", "retrieval_policy": {"source_ids": [...] | null, "year_min": ... | null, "year_max": ... | null}}`
  - 注意：`source_ids` 为空时必须传 `null` 或省略，**不能传空数组 `[]`**（原项目 Pydantic 校验会报 422！）。
  - 返回 `201 {"job_id": "job_..."}`
- 轮询进度：`GET {engineUrl}/api/citation-jobs/{job_id}/events` 与 `GET {engineUrl}/api/citation-jobs/{job_id}`（按 `pollInterval` 轮询直到 `status` 为 `succeeded` 或 `failed`，或被本地取消/超时）。
- 阶段映射：
  - `parse_text` → `PARSE_TEXT` (0.20)
  - `plan_retrieval` → `PLAN_RETRIEVAL` (0.30)
  - `retrieve_references` → `RETRIEVE_REFERENCES` (0.55)
  - `rank_evidence` → `RANK_EVIDENCE` (0.75)
  - `enrich_metadata` → `ENRICH_METADATA` (0.85)
  - `insert_citations` / `format_output` → `FORMAT_OUTPUT` (0.95)
- 数据映射：
  - `claims[]` → `CitationStatement[]`（`claim_id` → `statementId`）
  - `supported_references[claimId]` → 完整候选文献列表（补齐 `work_id = metadata.work_id`、`chunks[].metadata.evidence_score / evidence_confidence / evidence_reason`）
  - `selected_references[claimId]` → 初始 `selectedReferenceIds[statementId]`
  - 注意：最终 `annotatedText` 与 `bibliography` 由 Java 宿主在本地书目补全完成后使用 `CitationFormatter` 统一生成，以确保前后一致且支持后续审阅重排。

### 5.3 `standard-v1`（面向未来 TypeScript Agent 等自研引文引擎的标准 HTTP 协议）
预留给用户后续重写的 TypeScript Agent 项目，协议简洁、与语言无关：
- `GET {engineUrl}/v1/health` → `200 {"status": "ok"}`
- `POST {engineUrl}/v1/citation-runs`
  - 请求体：`{"runId": "<uuid>", "text": "...", "citationStyle": "...", "retrievalPolicy": {"sourceIds": [...], "yearMin": null, "yearMax": null}}`
  - 返回：`202 {"engineRunId": "..."}`
- `GET {engineUrl}/v1/citation-runs/{engineRunId}`
  - 返回：`{"engineRunId": "...", "status": "QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED", "stage": "...", "progress": 0.0..1.0, "events": [...], "result": {...}, "errorCode": null, "errorMessage": null}`
  - 其中 `result` 直接采用与 `openapi-client.yaml` 同构的 camelCase 结构：`{statements, referencesByStatement, initialSelectedReferenceIds, evidenceStats, warnings}`
- `DELETE {engineUrl}/v1/citation-runs/{engineRunId}` → `200/204` 协作取消。

---

## 6. 验收测试清单与执行命令

请在 `backend/src/test/java/com/paperflow/client/**` 中补全以下测试：

1. **纯单元测试（无需外部数据库，默认 `mvn test` 必跑）**：
   - `CitationFormatterTest`：覆盖 `apa`、`ieee`、`chicago-author-date`、`mla` 的单作者/双作者/三作者以上、同句多引文、中文句号与英文句号前插入、空选中取消引文、非法位置回退。
   - `BibtexExporterTest`：覆盖去重、citeKey 唯一性、特殊字符转义、Markdown 与纯文本导出格式。
   - `LegacyCitationsRetrievalEngineTest`：使用 JDK `com.sun.net.httpserver.HttpServer` 模拟 `CitationsRetrievalSystem`（含 422 空数组防护、轮询进度映射、失败错误映射与取消中断）。
   - `StandardHttpCitationEngineTest`：模拟 `standard-v1` 引擎提交、状态轮询与 `DELETE` 取消。
2. **HTTP 集成测试（复用 `WorkspaceFixture` / `WorkspaceHttp` 模式，`@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", ...)`）**：
   - `ClientWorkspaceHttpTest`：
     - 验证 `RESEARCHER` 账号可访问 `/api/client/modules`、`/api/client/journals`、`/api/client/citations/tasks`，访问 `/api/papers` 返回 `403`。
     - 验证无 `CLIENT_ACCESS` 的自定义角色访问 `/api/client/**` 返回 `403`。
     - 验证使用 `stub` 驱动的端到端流程：提交任务 → SSE 收到 `progress` 与 `complete` → 本地元数据补全命中测试库论文 → `PATCH /review` 增删备选文献并切换样式重排 → `GET /export` 下载 `txt`/`md`/`bibtex` → 跨用户隔离返回 `404` → 取消运行中任务 → 删除终态任务。

### 提交前必跑验证命令
```bash
mvn -f backend/pom.xml test
```
若有测试数据库环境变量，请同时运行完整集成测试确认全部通过。不要提交任何未跟踪临时文件，确保 `git diff --name-only <baseline-commit>` 中所有改动文件全部位于 `backend/src/main/java/com/paperflow/client/**` 与 `backend/src/test/java/com/paperflow/client/**`。
