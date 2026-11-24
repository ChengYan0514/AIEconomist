# AI Economist 数据库现状

本文以当前部署的 PostgreSQL 数据库现状、v2 Flyway 迁移 SQL、应用配置与运行时查询为准，说明数据库中的所有保留对象及其读写边界。[数据库接续 ADR](adr/0001-use-existing-paperflow-schema.md) · [因果写入边界 ADR](adr/0002-causal-fact-write-boundary.md) · [表结构收口 ADR](adr/0004-consolidate-paper-and-openalex-tables.md) · [应用配置](../backend/src/main/resources/application.yml)

## 连接与迁移

| 对象范围 | 默认位置 | 访问方式 |
| --- | --- | --- |
| 业务表、OpenAlex 本地作品库与历史全文 | PostgreSQL 数据库 `openalex` 的 `widi_chengyan` schema | JDBC 的 `currentSchema` 用于解析业务表、视图与保留表。`PAPERFLOW_JDBC_URL` 可覆盖整条连接，缺省时由 `PAPERFLOW_DB_HOST`、`PAPERFLOW_DB_PORT`、`PAPERFLOW_DB_NAME` 和 `PAPERFLOW_DB_SCHEMA` 组成。 |
| v2 迁移历史 | `widi_chengyan.v2_flyway_schema_history` | 由 Flyway 管理，记录版本、脚本、校验和及应用结果。已有非空 schema 从版本 `0` 建立基线后顺序执行 v2 迁移；配置禁用创建 schema 和 `clean`。 |
| 因果事实与发布控制表 | 默认同 `widi_chengyan` schema | 遵循 [ADR 0002](adr/0002-causal-fact-write-boundary.md) 原地共享读写，可通过 `CAUSAL_DB_*`（或 `PAPERFLOW_CAUSAL_JDBC_URL`）覆盖为独立数据源。表结构由独立脚本按 schema owner 维护，应用运行时不执行 DDL。 |
| OpenAlex 快照 | 同一数据库的 `dataset_20241125` schema | SQL 显式带 schema 名执行只读 `SELECT`。`PAPERFLOW_OPENALEX_SCHEMA` 优先于 `OPENALEX_DB_SCHEMA`，配置校验 schema 名为小写 PostgreSQL 标识符。 |

v2 迁移演进：`V1` 创建基础业务表与 `v2_paper_read` 视图；`V2` 增加审计事件表；`V3` 扩展用户资料、角色与凭据版本；`V4` 引入内置与自定义角色表及外键；`V5` 增加论文处理记录索引；`V6` 扩展因果权限并新增因果抽取控制表与预聚合读模型；`V7` 按 [ADR 0004](adr/0004-consolidate-paper-and-openalex-tables.md) 将历史论文全量收口至 `v2_paper`、改指 `block`/`text_file` 外键、原地复用 `work*` 4 表并移除 `v2_openalex_work`、`v2_openalex_work_topic` 与 `source` 表。[V1 迁移](../backend/src/main/resources/db/migration/V1__paperflow_v2.sql) · [V6 迁移](../backend/src/main/resources/db/migration/V6__causal_workspace.sql) · [V7 迁移](../backend/src/main/resources/db/migration/V7__consolidate_papers_and_openalex.sql) · [因果库 DDL](../backend/src/main/resources/db/causal/V1__causal_facts_and_receipts.sql)

## 当前数据库对象总览

当前 `widi_chengyan` schema 共包含 **30 张表（含 2 张已停用待删的历史参考表）、1 个视图（`v2_paper_read`）、3 个序列**（`v2_audit_event_event_id_seq`、`claim_table_claim_id_seq`、`paper_claim_table_record_id_seq`），无自定义函数与触发器。按职责与读写边界分为五类：

| 归属与边界 | 业务域 | 对象清单 | 职责说明 |
| --- | --- | --- | --- |
| **v2 自有（读写，13 表 + 1 视图）** | 账号、角色与审计（3 表） | `v2_admin`、`v2_role`、`v2_audit_event` | 平台用户、内置/自定义角色权限与不可变审计事件 |
| | 论文与新版全文（3 表 + 1 视图） | `v2_paper`、`v2_parse_artifact`、`v2_content_block`、`v2_paper_read`（视图） | 全部论文（含已全量迁入的历史论文）主数据、新版解析产物与内容块，以及单表读视图 |
| | 常规处理任务（2 表） | `v2_job`、`v2_job_item` | 原件导入、文件解析、全文发布、OpenAlex 元数据导入与匹配任务 |
| | 因果工作区（4 表） | `v2_causal_batch`、`v2_causal_run`、`v2_causal_edge_index`、`v2_causal_snapshot` | 因果抽取批次与单篇运行记录、因果图谱与领域分析预聚合读模型 |
| | 迁移历史（1 表） | `v2_flyway_schema_history` | v2 Flyway 版本管理表 |
| **OpenAlex 本地作品库（原地读写，4 表）** | 作品、来源、作者与主题（4 表） | `work`、`work_source`、`work_author`、`work_topic` | 从 OpenAlex 快照导入并维护的规范化作品池，供书目匹配、期刊统计与因果学科筛选直接读写 |
| **因果共享（原地读写，4 表）** | 因果事实与发布控制（4 表） | `claim_table`、`paper_claim_table`、`causal_publication_receipt`、`causal_dataset_revision` | 标准因果关系、论文级因果主张与原文证据、幂等发布回执及数据集版本号 |
| **历史 MinerU 全文（只读保留，7 表）** | 历史解析与内容块（7 表） | `text_file`、`block`、`block_image`、`block_table`、`block_equation`、`block_footnote`、`block_reference` | 旧版 MinerU 生成的解析路径与已发布结构化正文（外键已改指 `v2_paper`） |
| **待删除历史归档（已停用，2 表）** | 旧论文登记参考（2 表） | `original_file`、`original_file_job` | 数据已全量迁入 `v2_paper`，系统不再读写，已标注 `DEPRECATED` 暂留参考，确认后可直接删除 |

## v2 自有表结构

以下各表中，`必填` 对应数据库的 `NOT NULL` 或主键约束；主键与唯一约束均自带对应索引。

### 账号、角色与审计

#### `v2_admin`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `username` | `varchar(50)`，主键，符合 `^[a-z0-9_.-]{3,50}$` | 平台用户的固定用户名，也是任务提交人引用。 |
| `password_hash` | `varchar(100)`，必填 | 当前密码的 BCrypt 散列。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 账号登记时间。 |
| `display_name` | `varchar(100)`，必填 | 可编辑的显示名称。 |
| `role` | `varchar(43)`，必填，外键引用 `v2_role.role_id` | 用户分配的单个内置或自定义角色。 |
| `enabled` | `boolean`，必填，默认 `true` | 账号是否启用。 |
| `must_change_password` | `boolean`，必填，默认 `false` | 新建或重置临时密码后是否要求本人改密。 |
| `credential_version` | `bigint`，必填，默认 `0` | 启停、重置或改密时递增，用于即时失效旧会话。 |

索引：`v2_admin_role_username (role, username)`。启动时可由 `PAPERFLOW_ADMIN_USERNAME` / `PAPERFLOW_ADMIN_PASSWORD` 初始化缺失的系统管理员；用户资料变更、启停和重置密码均与审计事件在同一事务内提交，并通过 PostgreSQL 事务锁确保始终保留至少一名启用的系统管理员。[用户迁移](../backend/src/main/resources/db/migration/V3__platform_users.sql) · [账号查询](../backend/src/main/java/com/paperflow/adapters/persistence/AccountMapper.java) · [安全配置](../backend/src/main/java/com/paperflow/main/SecurityConfiguration.java)

#### `v2_role`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `role_id` | `varchar(43)`，主键 | 固定内置角色 ID（`SYSTEM_ADMIN`、`DATA_MAINTAINER`、`READ_ONLY`）或 `CUSTOM_<uuid>`。 |
| `name` | `varchar(100)`，必填，忽略大小写唯一（`v2_role_name`） | 用户管理与角色管理中展示的角色名称。 |
| `description` | `varchar(500)`，必填 | 角色说明。 |
| `permissions` | `text[]`，必填，默认 `'{}'` | 自定义角色的权限集合（允许范围见 `V6` 约束，含常规及因果权限）；内置角色为空数组，权限由应用代码固定定义。 |

外键 `v2_admin_role_fk` 保护所有已分配给用户的角色不被误删。[角色迁移](../backend/src/main/resources/db/migration/V4__platform_roles.sql) · [角色查询](../backend/src/main/java/com/paperflow/adapters/persistence/RoleMapper.java)

#### `v2_audit_event`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `event_id` | `bigint`，自增主键（`v2_audit_event_event_id_seq`） | 审计事件 ID。 |
| `occurred_at` | `timestamptz`，必填，默认 `clock_timestamp()` | 事件发生时间。 |
| `actor` | `varchar(50)`，必填 | 操作者用户名。 |
| `action` | `varchar(64)`，必填 | 操作代码。 |
| `object_type`、`object_id` | `varchar(64)`、`varchar(255)`，必填 | 操作对象的类型与稳定标识。 |
| `outcome` | `varchar(16)`，必填（`SUCCEEDED` / `FAILED` / `DENIED`） | 操作结果。 |
| `summary` | `jsonb` 对象，必填 | 按操作类型记录的摘要信息。 |

索引：`v2_audit_event_time_idx (occurred_at DESC, event_id DESC)`。应用仅追加和查询，不修改或删除历史审计记录。[审计迁移](../backend/src/main/resources/db/migration/V2__audit_events.sql) · [审计查询](../backend/src/main/resources/mapper/AuditMapper.xml)

### 论文、解析与新版全文

#### `v2_paper`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `paper_id` | `varchar(255)`，主键 | 固定论文 ID。新论文由规范化来源、年份、标题和有序作者的 SHA-256 小写十六进制值确定；迁入的历史论文沿用原 `file_id`。 |
| `legacy` | `boolean`，必填 | 标识论文来自历史数据迁入；替换原件后仍保留该标记。 |
| `source_id` | `varchar(255)` | OpenAlex 出版来源 ID（期刊名称由查询时关联 `${schema}.sources` 获取）。 |
| `publication_year` | `integer` | 发表年份。 |
| `title` | `text` | 论文标题。 |
| `authors` | `text` | 按登记顺序以半角分号分隔的作者名单。 |
| `doi` | `text` | DOI（新版写入时规范化，历史迁入记录沿用旧值）。 |
| `landing_url` | `text` | 原始采集页面或下载链接。 |
| `provider` | `text` | 原件采集平台。 |
| `original_name` | `text` | 当前原件登记文件名。 |
| `original_path` | `text` | 当前原件相对路径（按 `legacy` 与 `file_revision` 对应历史或新版存储根目录）。 |
| `original_type` | `varchar(10)`，非空时为 `PDF` / `XML` / `HTML` | 当前原件格式。 |
| `original_size` | `bigint`，非空时 `>= 0` | 当前原件字节数。 |
| `file_revision` | `bigint`，必填且 `>= 0` | 当前原件修订号；历史原件为 `0`，新论文从 `1` 开始，替换原件时递增。 |
| `record_version` | `bigint`，必填且 `> 0`，默认 `1` | 论文记录并发控制版本。 |
| `archived_at` | `timestamptz` | 非空表示论文已归档；历史迁入记录沿用原 `deleted_at`。 |
| `matched_work_id` | `varchar(255)`，非空唯一 | 已关联的 OpenAlex 作品 ID（全库唯一）。 |
| `latest_parse_id` | `uuid`，复合外键 | 当前原件最近一次完成的解析产物 ID。 |
| `published_parse_id` | `uuid`，复合外键 | 当前已发布新版全文对应的解析产物 ID。 |
| `legacy_status` | `varchar(20)`，允许值见下 | `V7` 新增；保存历史论文在 `file_revision = 0` 时从旧状态推导出的基线状态（`PUBLISHED` / `READY` / `FAILED` / `UNSUPPORTED` / `UNPROCESSED`）。 |
| `created_at`、`updated_at` | `timestamptz`，必填 | 登记与最近变更时间。 |

约束与索引：`legacy = false` 的行受 64 位十六进制 ID、正修订号及非空核心书目校验约束；`latest_parse_id` 与 `published_parse_id` 均以 `(parse_id, paper_id, file_revision)` 引用 `v2_parse_artifact`。索引包括 `v2_paper_source_year (source_id, publication_year, paper_id)`、非空 `doi` 索引 `v2_paper_normalized_doi` 与非空 `matched_work_id` 唯一索引 `v2_paper_matched_work`。[论文查询与写入](../backend/src/main/resources/mapper/PaperMapper.xml) · [论文模型](../backend/src/main/java/com/paperflow/paper/model/PaperMetadata.java)

#### `v2_parse_artifact`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `parse_id` | `uuid`，主键 | 解析产物 ID。 |
| `paper_id` | `varchar(255)`，必填，外键指向 `v2_paper.paper_id` | 所属论文。 |
| `file_revision` | `bigint`，必填且 `>= 0` | 生成产物时对应的原件修订号。 |
| `legacy` | `boolean`，必填 | 产物路径是否位于历史存储目录。 |
| `storage_path` | `text`，必填 | 产物目录相对路径，作为图表资源的路径基准。 |
| `content_path` | `text`，必填 | 结构化内容 JSON 相对路径。 |
| `markdown_path` | `text` | Markdown 产物相对路径（可选）。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 产物登记时间。 |

`(parse_id, paper_id, file_revision)` 唯一，供 `v2_paper` 两个解析指针复合外键引用。[全文持久化](../backend/src/main/resources/mapper/FullTextMapper.xml)

#### `v2_content_block`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `parse_id` | `uuid`，外键指向 `v2_parse_artifact.parse_id`，复合主键 | 所属解析产物。 |
| `sequence` | `integer`，复合主键且 `>= 0` | 从 `0` 开始的内容块顺序。 |
| `block_type` | `text`，必填 | 块类型（标题、正文、图表、公式、参考文献、脚注、丢弃块等）。 |
| `body` | `text` | 正文、标题或题注文本。 |
| `page` | `integer`，必填且 `>= 0` | 来源页索引（从 `0` 开始）。 |
| `image_path` | `text` | 相对产物目录的图像路径。 |
| `attributes` | `jsonb`，必填，默认 `'{}'` | 边界框、标题级别、题注、脚注、表格 HTML 等扩展属性。 |

主键为 `(parse_id, sequence)`；读取与计数统一排除 `discarded` 块。[全文持久化](../backend/src/main/resources/mapper/FullTextMapper.xml)

### 常规处理任务

#### `v2_job`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `job_id` | `uuid`，主键 | 任务 ID。 |
| `kind` | `varchar(20)`，必填 | 任务类型：`ORIGINAL_IMPORT`、`PARSE`、`PUBLISH`、`METADATA`、`MATCH`。 |
| `scope` | `jsonb`，必填，默认 `'{}'` | 任务级输入范围（如 ZIP 文件名、来源与年份范围）。 |
| `username` | `varchar(50)`，必填，外键指向 `v2_admin.username` | 提交任务的平台用户。 |
| `retry_of` | `uuid`，外键指向同表 `job_id` | 重试来源任务 ID。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 创建时间。 |
| `confirmed_at` | `timestamptz` | ZIP 原件导入预检确认时间。 |

唯一约束 `(job_id, kind)` 供 `v2_job_item` 复合外键引用；索引 `v2_job_recent (created_at DESC, job_id DESC)`。任务整体状态由 `v2_job_item` 实时聚合得出。[任务查询与写入](../backend/src/main/resources/mapper/JobMapper.xml)

#### `v2_job_item`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `job_id`、`kind` | `uuid`、`varchar(20)`，复合外键指向 `v2_job (job_id, kind)` | 所属任务与类型（`job_id` 同时为复合主键之一）。 |
| `sequence` | `integer`，复合主键且 `> 0` | 任务项顺序号。 |
| `target` | `text`，必填 | 任务项展示目标（论文标题、预检行号或来源 ID）。 |
| `paper_id` | `varchar(255)` | 关联论文 ID（逻辑关联）。 |
| `file_revision`、`record_version` | `bigint` | 提交时的原件修订与记录版本，执行前用于并发复核。 |
| `input` | `jsonb`，必填，默认 `'{}'` | 任务项固定输入；ZIP 预检通过项记录包内 `entry`。 |
| `state` | `varchar(20)`，必填 | 状态：`READY`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`SKIPPED`、`FAILED`。 |
| `message` | `text` | 执行结果、跳过原因或错误说明。 |
| `result` | `jsonb` | 结构化结果（如 MinerU 解析检查点、匹配候选、预检路径 `preflightPath`）。 |
| `claim_until` | `timestamptz` | 领取租约截止时间，约束与 `state = 'RUNNING'` 同步非空。 |
| `finished_at` | `timestamptz` | 终态完成时间。 |

核心索引：非 `ORIGINAL_IMPORT` 的活跃任务项唯一索引 `v2_job_one_active_paper_stage (kind, paper_id)`、待领取索引 `v2_job_items_queued`、租约过期索引 `v2_job_items_expiring`、失败解析/发布索引 `v2_job_failed_paper`，以及供论文处理记录查询的 `v2_job_items_by_paper (paper_id)`。[任务查询与写入](../backend/src/main/resources/mapper/JobMapper.xml) · [处理记录索引](../backend/src/main/resources/db/migration/V5__paper_job_history.sql)

### 因果工作区

#### `v2_causal_batch`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `batch_id` | `uuid`，主键 | 因果抽取批次 ID。 |
| `ingestion_mode` | `varchar(20)`，必填（`RAW` / `STANDARDIZED`） | 入库模式。 |
| `scope` | `jsonb`，必填，默认 `'{}'` | 目标选择范围快照。 |
| `config_snapshot` | `jsonb`，必填，默认 `'{}'` | 提交时的引擎配置快照。 |
| `cancelled` | `boolean`，必填，默认 `false` | 批次取消标记。 |
| `created_at`、`finished_at` | `timestamptz` | 创建与完成时间。 |

索引：`v2_causal_batch_recent (created_at DESC, batch_id DESC)`。操作者由 `v2_audit_event` 统一记录。[因果批次持久化](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalBatchStore.java)

#### `v2_causal_run`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `run_id` | `uuid`，主键 | 单篇抽取运行 ID，同时作为 `causal_publication_receipt.publication_id` 的幂等键。 |
| `batch_id` | `uuid`，必填，外键指向 `v2_causal_batch (batch_id) ON DELETE CASCADE` | 所属抽取批次。 |
| `paper_id`、`work_id`、`source_id` | `varchar(255)`（`paper_id` 必填） | 目标论文、关联作品及出版来源 ID。 |
| `input_hash` | `varchar(64)` | 提交给引擎的全文输入摘要。 |
| `engine_run_id` | `varchar(128)` | `causal-engine` Sidecar 返回的运行标识，用于启动恢复对账与产物代理下载。 |
| `status` | `varchar(20)`，必填 | 状态：`QUEUED`、`RUNNING`、`PUBLISHING`、`SUCCEEDED`、`SKIPPED`、`FAILED`、`CANCELLED`。 |
| `stage` | `varchar(20)`，必填，默认 `PREPARE` | 当前阶段：`PREPARE`、`STAGE0`、`STAGE1`、`NORMALIZE`、`MAP`、`PUBLISH`。 |
| `metrics` | `jsonb`，必填，默认 `'{}'` | 调用次数、抽取主张数等运行指标。 |
| `error_code`、`error_message` | `varchar(64)`、`text` | 失败或跳过原因。 |
| `updated_at`、`finished_at` | `timestamptz` | 更新与结束时间。 |

约束与索引：`(batch_id, paper_id)` 唯一；部分唯一索引 `v2_causal_run_active_work (work_id)` 保证同一作品同时最多一条活跃运行；另有 `v2_causal_run_batch_status` 与 `v2_causal_run_paper` 索引。

#### `v2_causal_edge_index`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `claim_id` | `bigint`，主键 | 对应 `claim_table.claim_id`。 |
| `cause_standard`、`effect_standard` | `varchar(255)`，必填 | 标准原因变量与结果变量。 |
| `record_count`、`paper_count`、`diversity` | `bigint`，必填且 `>= 0` | 主张记录数、覆盖论文数与因果推断方法多样性计数。 |
| `positive_count`、`negative_count`、`null_count`、`mixed_count` | `bigint`，必填且 `>= 0` | 四类影响方向统计。 |
| `subfields` | `text[]`，必填，默认 `'{}'` | 关联作品所属的学科子领域数组。 |
| `revision` | `bigint`，必填 | 构建该读模型时的 `causal_dataset_revision.revision`。 |

索引：排序索引 `v2_causal_edge_rank (record_count DESC, paper_count DESC, claim_id ASC)`、子领域 `GIN` 索引 `v2_causal_edge_subfields`，以及 `lower(cause_standard)` / `lower(effect_standard)` 检索索引。[因果读模型存储](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalReadModelStore.java)

#### `v2_causal_snapshot`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `snapshot_key` | `varchar(32)`，主键（`SUMMARY` / `DEFAULT_GRAPH` / `FIELDS`） | 预聚合快照键。 |
| `revision` | `bigint`，必填 | 对应的数据集版本号。 |
| `payload` | `jsonb`，必填 | 预聚合后的概览、默认图谱或领域分析结果。 |
| `refreshed_at` | `timestamptz`，必填，默认 `now()` | 最近刷新时间。 |

每次发布递增 `causal_dataset_revision` 后后台异步重算 `v2_causal_edge_index` 与 `v2_causal_snapshot`，重启后直接命中持久化快照。[因果读模型存储](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalReadModelStore.java)

## `v2_paper_read` 与版本语义

`V7` 之后，`v2_paper_read` 为基于 `v2_paper` 单表的投影视图，供论文查询、CSV 导出、期刊聚合及任务选目标统一使用（期刊名称在查询侧通过 `LEFT JOIN ${schema}.sources` 实时关联）：

- `status` 按以下优先级推导（`legacy_status` 仅在 `file_revision = 0` 时生效）：

| 优先级 | 状态 | 判定条件 |
| --- | --- | --- |
| 1 | `PUBLISHED` | `published_parse_id IS NOT NULL`，或 `file_revision = 0 AND legacy_status = 'PUBLISHED'`。 |
| 2 | `READY` | `latest_parse_id IS NOT NULL`，或 `file_revision = 0 AND legacy_status = 'READY'`。 |
| 3 | `FAILED` | 当前修订存在失败的 `PARSE` / `PUBLISH` 任务项，或 `file_revision = 0 AND legacy_status = 'FAILED'`。 |
| 4 | `UNSUPPORTED` | `file_revision = 0 AND legacy_status = 'UNSUPPORTED'`，或原件格式非 `PDF`。 |
| 5 | `UNPROCESSED` | 以上均未命中。 |

- **修订语义**：仅替换原件时递增 `file_revision` 并清空两个解析指针；同一原件重新解析仅更新 `latest_parse_id`，原有 `published_parse_id` 全文在新版本发布前保持可读。

[V7 视图定义](../backend/src/main/resources/db/migration/V7__consolidate_papers_and_openalex.sql) · [论文服务](../backend/src/main/java/com/paperflow/paper/manage/PaperService.java) · [全文服务](../backend/src/main/java/com/paperflow/fulltext/FullTextService.java)

## OpenAlex 本地作品库（原地读写）

按 [ADR 0004](adr/0004-consolidate-paper-and-openalex-tables.md)，系统直接原地读写下列 4 张规范化 OpenAlex 表，不再维护 `v2_openalex_*` 副本。[OpenAlex 查询与写入](../backend/src/main/resources/mapper/OpenAlexMapper.xml) · [期刊查询](../backend/src/main/resources/mapper/JournalMapper.xml) · [因果作品元数据查询](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalWorkMetaQuery.java)

| 表名 | 主键 / 外键与索引 | 核心字段与用途 |
| --- | --- | --- |
| `work` | 主键 `work_id`；索引 `ix_work_doi (doi)`、`ix_work_publication_year (publication_year)` | 作品书目主表：`work_id`、`publication_year`、`title`（`text`）、`doi`（`text`），保留历史 `publication_date`、`type`、`language`。匹配按 DOI 同时兼容历史 `https://doi.org/` 前缀与规范化纯 DOI。 |
| `work_source` | 主键 `(work_id, source_id)`；外键 `work_id -> work(work_id) ON DELETE CASCADE`；索引 `idx_work_source_source_work (source_id, work_id)` | 作品与出版来源多对多关联，用于按来源筛选候选作品与统计期刊已导入作品数。 |
| `work_author` | 主键 `(work_id, author_id)`；外键 `work_id -> work(work_id) ON DELETE CASCADE`；索引 `ix_work_author_author_name (author_name)` | 作品作者明细：`work_id`、`author_id`、`author_name`（`text`）、`author_position`。书目匹配时按 `author_id` 排序拼接 `author_name`。 |
| `work_topic` | 主键 `(work_id, topic_id)`；外键 `work_id -> work(work_id) ON DELETE CASCADE`；部分索引 `idx_work_topic_subfield (subfield_name)` | 作品学科与主题表：`topic_name`、`field_name`、`subfield_name`、`domain_name`（均为 `text`），保留历史 `keywords`、`description`，供因果图谱和领域分析筛选使用。 |

导入任务按 `work_id` 每批最多 500 条，在来源级事务锁内按 `work` → `work_source` → `work_author` → `work_topic` 顺序 UPSERT；出版来源列表由 `v2_paper.source_id` 聚合，来源名称直接关联 `${schema}.sources` 获取。

## 因果事实与发布控制表（原地共享读写）

按 [ADR 0002](adr/0002-causal-fact-write-boundary.md)，下列 4 张表原地承载历史与新增因果知识，由 `JdbcCausalFactReader` 读取、`JdbcCausalFactWriter` 以事务幂等方式写入。[因果读取](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalFactReader.java) · [因果写入](../backend/src/main/java/com/paperflow/adapters/persistence/JdbcCausalFactWriter.java)

| 表名 | 主键 / 关键约束 | 核心字段与用途 |
| --- | --- | --- |
| `claim_table` | `claim_id`（自增主键，序列 `claim_table_claim_id_seq`）；`claim_hash` 唯一 | 标准因果关系表，记录 `cause_standard`、`effect_standard`、`claim_hash`、`created_at`。 |
| `paper_claim_table` | `record_id`（自增主键，序列 `paper_claim_table_record_id_seq`）；外键 `claim_id -> claim_table(claim_id)` | 论文级因果主张与原文证据表，记录 `paper_id`（OpenAlex 作品 ID）、`claim_id`、`sign_of_impact`、`type_of_relationship`、`claim`、`cause`、`cause_score`、`effect`、`effect_score`、`evidence`、`causal_inference_method`、`evidence_method_other_description`、`is_main_contribution`、`level_of_tentativeness`、`sources_of_exogenous_variation`、`statistical_significance`、`created_at`。发布前会检查 `(paper_id, cause, effect)` 历史重复键防护。 |
| `causal_publication_receipt` | `publication_id`（主键，对应 `v2_causal_run.run_id`） | 因果发布幂等回执表，记录 `payload_hash`、`work_id`、`row_count`、`applied_at`，防止重复提交或摘要冲突。 |
| `causal_dataset_revision` | `id`（主键且 `CHECK (id = 1)`） | 单行数据集版本号表，记录 `revision`、`updated_at`；每次发布递增，用于驱动 `v2_causal_edge_index` 与 `v2_causal_snapshot` 刷新。 |

> 说明：代码另兼容可选的经济学主题词表 `stw_label`（见 [因果库 DDL](../backend/src/main/resources/db/causal/V1__causal_facts_and_receipts.sql)）。当前 `widi_chengyan` schema 中尚未建立该表，词表检索已通过 `to_regclass('stw_label')` 做存在性兜底，未建表时返回空列表。

## 历史 MinerU 全文表（只读保留）

由于 MinerU 升级导致结构化解析格式发生变化，以下 7 张历史全文表继续以只读方式保留，与新版 `v2_parse_artifact` / `v2_content_block` 分表并存。`block.file_id` 与 `text_file.file_id` 的外键已改指向 `v2_paper(paper_id)`。

| 表名 | 键 / 外键 | v2 读取的字段与用途 |
| --- | --- | --- |
| `text_file` | 主键 `(file_id, file_type)`；外键 `file_id -> v2_paper(paper_id)` | 按 `file_id` 读取 `file_type`、`file_path`，定位历史解析 JSON 文件及图表相对路径基准目录。 |
| `block` | 主键 `block_id`；`(file_id, block_seq)` 唯一；外键 `file_id -> v2_paper(paper_id)` | 按 `file_id` 依 `block_seq` 读取历史正文块（`block_type`、`block_text`、`pdf_page`、`pdf_bbox`、`title_level`），供全文浏览和因果抽取导出使用。 |
| `block_image` | 主键 `block_id` | 历史图片块的 `image_path`、`image_caption`、`image_footnote`。 |
| `block_table` | 主键 `block_id` | 历史表格块的 `image_path`、`table_caption`、`table_footnote`。 |
| `block_equation` | 主键 `block_id` | 历史公式块的 `image_path`、`format`。 |
| `block_footnote` | 主键 `block_id` | 历史脚注块的 `footnote_label`、`footnote_text`。 |
| `block_reference` | `(block_id, reference_seq)` | 历史参考文献块的 `reference_text`，按 `reference_seq` 排序后换行合并。 |

当 `legacy = true` 且 `revision = 0` 时，原件从 `PAPERFLOW_LEGACY_STORAGE` 读取；若 `published_parse_id` 为空，正文与图片回退读取 `block` 及 5 张扩展表，图表相对路径以 `openalex/` 或 `paperflow/` 开头时直接使用，其余路径尝试拼接 `text_file` 所在目录。新写入原件与新解析产物统一存放在 `PAPERFLOW_STORAGE`。[文件访问](../backend/src/main/java/com/paperflow/adapters/files/FileStorage.java) · [全文查询](../backend/src/main/resources/mapper/FullTextMapper.xml)

## 待删除历史归档表（已停用，仅留参考）

以下 2 张旧表的数据已在 `V7` 全量迁入 `v2_paper`，系统运行时不再读写，数据库表注释已标记 `DEPRECATED`。待后续业务确认稳定后可直接执行 `DROP TABLE original_file_job, original_file;` 清理：

| 表名 | 状态 | 说明 |
| --- | --- | --- |
| `original_file` | `DEPRECATED`（待删除） | 原历史论文与原件登记表（182,977 行已全量迁入 `v2_paper`；`block` / `text_file` 外键已迁走）。 |
| `original_file_job` | `DEPRECATED`（待删除） | 原历史处理标记与 OpenAlex 绑定表（状态与 `matched_work_id` 已全量迁入 `v2_paper`）。 |

## OpenAlex 快照读取（`dataset_20241125`）

以下快照表由外部维护，v2 仅执行只读查询：

| 快照表 | 查询使用的列 | 用途 |
| --- | --- | --- |
| `sources` | `id`、`display_name` | 出版来源校验、搜索，以及论文/期刊/因果查询的出版来源名称字典。 |
| `works` | `id`、`publication_year`、`title`、`doi` | 按来源与可选年份范围拉取作品书目。 |
| `works_locations` | `source_id`、`work_id` | 将作品限定在指定出版来源。 |
| `works_authorships` | `work_id`、`author_id` | 获取作品与作者的关联关系。 |
| `authors` | `id`、`display_name` | 提供作品作者姓名写入 `work_author`。 |
| `works_topics` | `work_id`、`topic_id` | 获取作品关联的 OpenAlex 主题 ID。 |
| `topics` | `id`、`display_name`、`field_display_name`、`subfield_display_name`、`domain_display_name` | 同步作品主题与学科层级信息写入 `work_topic`。 |

快照导入按作品 ID 分批（去重后每批最多 500 条），依次写入 `work`、`work_source`、`work_author` 与 `work_topic`。[来源校验](../backend/src/main/resources/mapper/PaperMapper.xml) · [快照查询](../backend/src/main/resources/mapper/OpenAlexMapper.xml) · [快照测试结构](../backend/src/test/resources/snapshot.sql)
