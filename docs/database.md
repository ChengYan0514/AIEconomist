# AI Economist 数据库现状

本文以 v2 的 Flyway SQL、应用配置和运行时查询为准。表结构分为 v2 自有对象、旧库兼容读取和 OpenAlex 快照。旧表的键和用途根据相邻的 `paperflow-admin-platform` 仓库整理。实际部署上的对象、约束、授权、行数和迁移版本有待数据库核对。 [架构决策](adr/0001-use-existing-paperflow-schema.md) · [数据库迁移](../backend/src/main/resources/db/migration/V1__paperflow_v2.sql) · [应用配置](../backend/src/main/resources/application.yml)

## 连接与迁移

| 对象范围 | 默认位置 | v2 的访问方式 |
| --- | --- | --- |
| v2 业务表及历史兼容对象 | PostgreSQL 数据库 `openalex` 的 `widi_chengyan` schema | JDBC 的 `currentSchema` 用于解析业务表、视图和旧表。`PAPERFLOW_JDBC_URL` 可覆盖整条连接，缺省时由 `PAPERFLOW_DB_HOST`、`PAPERFLOW_DB_PORT`、`PAPERFLOW_DB_NAME` 和 `PAPERFLOW_DB_SCHEMA` 组成。 |
| v2 迁移历史 | 同一业务 schema 的 `v2_flyway_schema_history` | 由 Flyway 管理，记录版本、脚本、校验和及应用结果。已有非空 schema 可从版本 `0` 建立基线，随后执行 v2 迁移。配置禁用创建 schema 和 `clean`。 |
| OpenAlex 快照 | 同一 JDBC 数据库的 `dataset_20241125` schema | SQL 带 schema 名执行 `SELECT`。`PAPERFLOW_OPENALEX_SCHEMA` 优先于 `OPENALEX_DB_SCHEMA`。配置校验 schema 名为小写 PostgreSQL 标识符。 |

`V1` 创建七张业务表和 `v2_paper_read` 视图。`V2` 增加审计事件表。`V3` 接续 v2 账号并增加用户资料、内置角色和凭据版本。旧库和快照通过 `SELECT` 访问。创建视图时，`source`、`original_file`、`original_file_job` 及视图使用的列必须已存在。历史文本和书目表在相关查询时访问。历史论文从兼容视图即时读取，首次写入时按篇登记。旧平台主库和因果库分别使用自己的 `flyway_schema_history` 和 `flyway_causal_schema_history`。 [数据库迁移](../backend/src/main/resources/db/migration/V1__paperflow_v2.sql) · [配置校验](../backend/src/main/java/com/paperflow/main/WorkspaceProperties.java)

## v2 表结构

下表中的 `必填` 对应数据库的 `NOT NULL` 或主键。索引按迁移 SQL 列出，主键与唯一约束同时生成各自的索引。

### `v2_admin`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `username` | `varchar(50)`，主键，符合 `^[a-z0-9_.-]{3,50}$` | 平台用户的固定用户名，也是任务的提交人引用。 |
| `password_hash` | `varchar(100)`，必填 | 当前密码的 BCrypt 散列。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 账号登记时间。 |
| `display_name` | `varchar(100)`，必填 | 可编辑的显示名称。旧账号迁移时取原用户名。 |
| `role` | `varchar(32)`，必填 | 用户的单个角色。迁移时旧账号保留为系统管理员。 |
| `enabled` | `boolean`，必填，默认 `true` | 账号是否启用。 |
| `must_change_password` | `boolean`，必填，默认 `false` | 新建或重置临时密码后需要设置个人密码。 |
| `credential_version` | `bigint`，必填，默认 `0` | 启停、重置或个人改密时递增，旧会话登录时保存的版本会失效。 |

启动时可由 `PAPERFLOW_ADMIN_USERNAME` 和 `PAPERFLOW_ADMIN_PASSWORD` 创建指定的缺失系统管理员。V3 在同一表登记所有账号，保留既有用户的散列、用户名和任务外键。角色、启停状态及凭据版本在业务请求时读取。用户资料变更、启停和重置密码均与对应审计成功事件在同一事务写入。停用和角色调整共用 PostgreSQL 事务锁，确保并发修改后仍有启用的系统管理员。 [账号迁移](../backend/src/main/resources/db/migration/V3__platform_users.sql) · [账号查询](../backend/src/main/java/com/paperflow/adapters/persistence/AccountMapper.java) · [安全配置](../backend/src/main/java/com/paperflow/main/SecurityConfiguration.java)

### `v2_audit_event`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `event_id` | `bigint`，自增主键 | 审计事件 ID。 |
| `occurred_at` | `timestamptz`，必填，默认为数据库的当前时间 | 操作结果的记录时间。 |
| `actor` | `varchar(50)`，必填 | 事件发生时的操作者用户名。 |
| `action` | `varchar(64)`，必填 | 操作代码。 |
| `object_type`、`object_id` | `varchar(64)`、`varchar(255)`，必填 | 操作对象的类型和稳定标识。 |
| `outcome` | `varchar(16)`，必填 | `SUCCEEDED`、`FAILED` 或 `DENIED`。 |
| `summary` | `jsonb` 对象，必填 | 根据动作选取的摘要字段。 |

应用仅追加或读取事件。操作者与对象标识随事件保留，便于在关联对象变更后追溯。时间和事件 ID 的联合索引支持倒序分页。 [审计迁移](../backend/src/main/resources/db/migration/V2__audit_events.sql)

### `v2_paper`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `paper_id` | `varchar(255)`，主键 | 固定论文 ID。新论文取规范化来源、年份、标题和有序作者的 SHA-256 小写十六进制值。历史论文沿用原 `file_id`。 |
| `legacy` | `boolean`，必填 | 标识论文从旧库接续。替换原件后仍保留该标记。 |
| `source_id` | `varchar(255)` | OpenAlex 出版来源 ID。新论文及元数据编辑在应用层通过快照校验。 |
| `publication_year` | `integer` | 论文发表年份。 |
| `title` | `text` | 论文标题。 |
| `authors` | `text` | 按登记顺序以半角分号串联的作者名单。 |
| `doi` | `text` | DOI。新版录入与编辑时进行规范化，初次登记历史记录时沿用旧值。 |
| `landing_url` | `text` | 原始采集页面或下载链接。 |
| `provider` | `text` | 原件采集平台。 |
| `original_name` | `text` | 当前原件登记名称。 |
| `original_path` | `text` | 当前原件路径，相对于对应的文件存储根目录。 |
| `original_type` | `varchar(10)`，非空时为 `PDF`、`XML` 或 `HTML` | 当前原件格式，v2 解析任务接受 PDF。 |
| `original_size` | `bigint`，非空时大于等于 `0` | 当前原件字节数。 |
| `file_revision` | `bigint`，必填且大于等于 `0` | 当前原件修订号。历史原件为 `0`，新论文从 `1` 开始。 |
| `record_version` | `bigint`，必填且大于 `0`，默认 `1` | 论文记录的并发控制版本。 |
| `archived_at` | `timestamptz` | 非空时论文处于归档状态。首次登记历史论文时沿用 `deleted_at`。 |
| `matched_work_id` | `varchar(255)`，非空值全表唯一 | 已关联的 OpenAlex 作品 ID。关联结果也可来自旧库。 |
| `latest_parse_id` | `uuid`，复合外键的一部分 | 当前原件最近一次完成的解析产物。 |
| `published_parse_id` | `uuid`，复合外键的一部分 | 论文详情当前可读的已发布全文对应的解析产物。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 论文登记时间，登记历史论文时优先沿用旧创建时间。 |
| `updated_at` | `timestamptz`，必填，默认 `now()` | 记录变更时间，包括解析及全文发布指针更新。 |

`legacy = false` 的行必须有 64 位小写十六进制 ID、正的 `file_revision`、非空来源、四位年份和长度为 1 至 2,000 字符的标题及作者。两个解析引用都以 `(<parse_id 列>, paper_id, file_revision)` 指向 `v2_parse_artifact` 的复合唯一键。`source_id` 和 `matched_work_id` 由业务查询关联。

索引包括 `(source_id, publication_year, paper_id)`、仅涵盖非空 `doi` 的索引，以及仅涵盖非空 `matched_work_id` 的唯一索引。 [论文查询与写入](../backend/src/main/resources/mapper/PaperMapper.xml) · [论文 ID 与书目校验](../backend/src/main/java/com/paperflow/paper/model/PaperMetadata.java)

### `v2_parse_artifact`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `parse_id` | `uuid`，主键 | 一份解析产物的 ID。 |
| `paper_id` | `varchar(255)`，必填，外键指向 `v2_paper.paper_id` | 产物所属论文。 |
| `file_revision` | `bigint`，必填且大于等于 `0` | 生成产物时对应的原件修订号。 |
| `legacy` | `boolean`，必填 | 产物目录和文件应在旧存储根目录中读取。v2 重新解析历史原件所生成的产物使用新版根目录。 |
| `storage_path` | `text`，必填 | 产物目录的相对路径，作为内容块资源的路径基准。 |
| `content_path` | `text`，必填 | 结构化内容 JSON 的相对路径，用于导入已发布全文。 |
| `markdown_path` | `text` | 存在时登记 Markdown 的相对路径。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 产物登记时间。 |

`(parse_id, paper_id, file_revision)` 唯一，供论文表的两个复合外键引用。一篇论文的同一原件修订可有多份解析产物。 [全文持久化](../backend/src/main/resources/mapper/FullTextMapper.xml) · [MinerU 产物登记](../backend/src/main/java/com/paperflow/adapters/files/MineruBundle.java)

### `v2_content_block`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `parse_id` | `uuid`，外键指向 `v2_parse_artifact.parse_id`，复合主键之一 | 内容块所属解析产物。 |
| `sequence` | `integer`，复合主键之一，取值大于等于 `0` | 从 `0` 开始的块顺序。 |
| `block_type` | `text`，必填 | 应用识别标题、正文、图表、公式、参考文献、脚注和丢弃块等类型。 |
| `body` | `text` | 正文、标题、图表题注等可展示内容。 |
| `page` | `integer`，必填且大于等于 `0` | 来源页索引，从 `0` 开始。 |
| `image_path` | `text` | 相对于产物目录的图像资源路径。 |
| `attributes` | `jsonb`，必填，默认空对象 | 保存边界框、标题级别、题注、脚注、表格 HTML 等结构化属性。 |

主键为 `(parse_id, sequence)`。一篇的内容块写入与发布指针切换在同一事务内完成。查询、计数和资源读取统一排除 `discarded`。 [全文持久化](../backend/src/main/resources/mapper/FullTextMapper.xml) · [解析内容导入](../backend/src/main/java/com/paperflow/fulltext/FullTextService.java) · [内容映射](../backend/src/main/java/com/paperflow/adapters/files/MineruContent.java)

### `v2_openalex_work`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `source_id` | `varchar(255)`，复合主键之一 | 导入这条作品元数据的出版来源 ID。 |
| `work_id` | `varchar(255)`，复合主键之一 | OpenAlex 作品 ID。同一个作品可对应多个来源。 |
| `year` | `integer` | 作品发表年份。 |
| `title` | `text` | OpenAlex 作品标题。 |
| `doi` | `text` | 导入时规范化的 DOI。 |
| `authors` | `text`，必填 | 按作者 ID 排序，以半角分号连接的姓名串。没有可用姓名时保存空串。 |
| `imported_at` | `timestamptz`，必填，默认 `now()` | 最近一次写入这条来源作品记录的时间。 |

主键为 `(source_id, work_id)`。重导时按主键更新书目和 `imported_at`。索引为 `(source_id, year)` 与仅涵盖非空 `doi` 的 `(source_id, doi)`。 [OpenAlex 查询与写入](../backend/src/main/resources/mapper/OpenAlexMapper.xml)

### `v2_job`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `job_id` | `uuid`，主键 | 一次异步操作的 ID。 |
| `kind` | `varchar(20)`，必填，枚举见下 | 操作类型，也是逐项记录的复合外键组成部分。 |
| `scope` | `jsonb`，必填，默认空对象 | ZIP 文件名、选中论文数，或来源与年份范围等任务级输入。 |
| `username` | `varchar(50)`，必填，外键指向 `v2_admin.username` | 提交操作的平台用户。 |
| `retry_of` | `uuid`，外键指向同表 `job_id` | 重试时被引用的上一次任务。 |
| `created_at` | `timestamptz`，必填，默认 `now()` | 任务创建时间。 |
| `confirmed_at` | `timestamptz` | ZIP 原件导入通过预检后的确认时间。 |

`kind` 可为 `ORIGINAL_IMPORT`（原件导入）、`PARSE`（文件解析）、`PUBLISH`（解析内容导入）、`METADATA`（OpenAlex 元数据导入）或 `MATCH`（OpenAlex 匹配）。`(job_id, kind)` 唯一，近期任务列表使用 `(created_at DESC, job_id DESC)` 索引。任务状态、进度计数及完成时间通过 `v2_job_item` 实时聚合。 [任务查询与写入](../backend/src/main/resources/mapper/JobMapper.xml)

### `v2_job_item`

| 列 | 类型与约束 | 用途 |
| --- | --- | --- |
| `job_id` | `uuid`，复合主键及复合外键之一 | 所属任务。 |
| `kind` | `varchar(20)`，必填，复合外键之一 | 操作类型，与所属任务一致。 |
| `sequence` | `integer`，复合主键之一且大于 `0` | 任务中的输入行或论文顺序。 |
| `target` | `text`，必填 | 任务项展示名称，例如论文标题、预检行号或来源 ID。 |
| `paper_id` | `varchar(255)` | 目标论文 ID，也可在原件预检时提前记录将来登记的 ID。 |
| `file_revision` | `bigint` | 提交时的原件修订，解析和全文导入据此复核。 |
| `record_version` | `bigint` | 提交时的论文记录版本，匹配据此复核。 |
| `input` | `jsonb`，必填，默认空对象 | 任务项固定输入，例如 ZIP 成员与书目、来源导入请求、待发布的 `parseId`。 |
| `state` | `varchar(20)`，必填，枚举见下 | 任务项执行状态。 |
| `message` | `text` | 预检、执行结果、跳过原因或错误信息。 |
| `result` | `jsonb` | 结构化结果。解析过程中记录 MinerU 的 `taskId`、`fileId` 和 `uploaded` 作为检查点。 |
| `claim_until` | `timestamptz` | Worker 领取的过期时间，仅 `RUNNING` 项有值。 |
| `finished_at` | `timestamptz` | 执行完成或在预检、提交阶段确定失败与跳过的时间。 |

主键为 `(job_id, sequence)`，`(job_id, kind)` 引用 `v2_job`。`paper_id` 是逻辑关联。状态可为 `READY`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`SKIPPED` 或 `FAILED`。预检通过的 `READY` 项在确认后进入 `QUEUED`。Worker 通过 `FOR UPDATE SKIP LOCKED` 领取，过期的执行中任务项标记为失败。重试创建新任务并复制可重试的失败项，解析项沿用 MinerU 检查点。

部分唯一索引限制同一 `(kind, paper_id)` 在 `QUEUED` 和 `RUNNING` 中最多有一项，条件限定非空论文 ID 且类型排除 `ORIGINAL_IMPORT`。另有待领取项 `(job_id, sequence)`、执行中项 `claim_until` 和失败解析或全文导入项 `(paper_id, file_revision, kind)` 三个部分索引。 [任务查询与写入](../backend/src/main/resources/mapper/JobMapper.xml) · [任务提交与重试](../backend/src/main/java/com/paperflow/job/JobService.java) · [Worker](../backend/src/main/java/com/paperflow/job/JobWorker.java)

## `v2_paper_read` 与版本语义

视图供论文详情、分页、条件筛选、导出及按范围选取任务目标使用。相同论文 ID 优先取 `v2_paper`。余下历史论文从 `original_file` 取得书目和文件字段，并连接 `original_file_job`。两部分都通过 `source.source_id` 取 `source_name`，本地缺少来源行时名称为空。历史未登记行的 `revision` 和 `version` 为 `0`，解析与发布指针为空，更新时间取 `updated_at`、`created_at` 中首个非空值。新版行将 `file_revision`、`record_version` 投影为 `revision`、`version`，并从 `archived_at` 推导 `archived`。 [读取视图](../backend/src/main/resources/db/migration/V1__paperflow_v2.sql) · [论文查询](../backend/src/main/resources/mapper/PaperMapper.xml)

视图按顺序判定 `status`。对于已有新版登记的历史论文，旧处理标记只在当前原件修订仍为 `0` 时生效。

| 优先级 | 状态 | 判定条件 |
| --- | --- | --- |
| 1 | `PUBLISHED` | 有 `published_parse_id` 或可沿用的 `flag_block = 1`。 |
| 2 | `READY` | 有 `latest_parse_id` 或可沿用的 `flag_text = 2`。 |
| 3 | `FAILED` | 有当前原件修订的失败 `PARSE` 或 `PUBLISH` 项，或可沿用的 `flag_text = -1`、`flag_block = -1`。 |
| 4 | `UNSUPPORTED` | 原件类型比较结果为非 PDF。旧库视图分支也接受 `flag_text = -2`。 |
| 5 | `UNPROCESSED` | 以上条件均未命中。 |

- `record_version` 用于编辑、归档及恢复、替换原件、匹配并发校验。历史行在首次需要写入时以原 ID 登记，保留书目、当前原件和旧绑定，置 `file_revision = 0`、`record_version = 1`。登记后的实际业务更新可继续递增版本。解析成功或全文发布只更新相应指针和 `updated_at`。
- `file_revision` 只在替换原件时递增，同时清空两个解析指针。旧解析记录、内容块和文件保留。同一原件重解析只更新最近解析指针，已发布全文保持可读，直至新的全文成功发布。
- 论文归档通过 `archived_at` 改变可见性，任务执行前也会复核归档状态。论文 ID 在编辑、原件替换和归档恢复中始终固定。重复导入先查计算得到的 ID，再按同一来源和年份直接查新版与旧库书目，使用规范化的标题和有序作者判断重复。

[论文业务规则](../backend/src/main/java/com/paperflow/paper/manage/PaperService.java) · [论文写入](../backend/src/main/resources/mapper/PaperMapper.xml) · [全文发布](../backend/src/main/java/com/paperflow/fulltext/FullTextService.java)

## 旧库兼容读取

下表列出 v2 运行时涉及的旧库表。旧表键按旧平台 `docs/database/db_design.md` 记录，兼容字段按 v2 的视图与查询列出。

| 旧表 | 键 | v2 读取的字段与用途 |
| --- | --- | --- |
| `source` | `source_id` | 经视图取 `source_id`、`source_name`，用于已登记论文的来源名称。 |
| `original_file` | `file_id` | 经视图读取论文及当前原件。直接查询用于首次登记和导入重复检查。读取 `source_id`、`year`、`paper_title`、`authors`、`doi`、`url`、`provider`、`original_file_name`、`original_file_path`、`original_file_type`、`file_size`、`deleted_at`、`created_at`、`updated_at`。 |
| `original_file_job` | `file_id`，非空 `matched_work_id` 唯一 | 经视图读取 `flag_text`、`flag_block` 和 `matched_work_id`。直接查询用于登记时复制已有绑定及检查候选作品是否已被另一篇历史论文关联。 |
| `text_file` | `(file_id, file_type)` | 按 `file_id` 查询 `file_type` 和 `file_path`，定位历史 JSON 及图表资源目录。 |
| `block` | `block_id`，`(file_id, block_seq)` 唯一 | 按 `file_id` 读取历史正文，依 `block_seq` 排序。使用 `block_type`、`block_text`、`pdf_page`、`pdf_bbox`、`title_level`，并用 `block_id` 关联扩展。 |
| `block_image` | `block_id` | 图片路径 `image_path`、图题 `image_caption` 与脚注 `image_footnote`。 |
| `block_table` | `block_id` | 表格图片路径 `image_path`、表题 `table_caption` 与脚注 `table_footnote`。 |
| `block_equation` | `block_id` | 公式图片路径 `image_path` 与公式格式 `format`。 |
| `block_footnote` | `block_id` | 脚注标签 `footnote_label` 与正文 `footnote_text`。 |
| `block_reference` | `(block_id, reference_seq)` | 参考文献正文 `reference_text`，按 `reference_seq` 排序并以换行合并。 |
| `work` | `work_id` | 历史 OpenAlex 作品的 `publication_year`、`title`、`doi`，供匹配读取。 |
| `work_source` | `(work_id, source_id)` | 以 `source_id` 限定历史候选作品。 |
| `work_author` | `(work_id, author_id)` | 以 `work_id` 查作者并按 `author_id` 排序，将 `author_name` 合为匹配字段。 |

原件为历史版本的条件是 `legacy = true` 且 `revision = 0`，文件按 `PAPERFLOW_LEGACY_STORAGE`（缺省回退到 `DATA_ROOT` 或 `var/legacy`）读取。已有 `published_parse_id` 时，正文和图片只从它对应的新版内容块读取。指针为空且使用历史原件时，正文回退到旧 `block` 及扩展表。历史正文可以在原件文件缺失时查阅。历史图表路径以 `openalex/` 或 `paperflow/` 开头时直接使用，其余相对路径尝试拼接该论文的解析文件目录。历史论文需要导入旧解析产物时，读取 `text_file` 中的 JSON 路径，在新版登记产物及新内容块，产物文件继续从旧目录读取。新写入原件和新生成解析产物使用 `PAPERFLOW_STORAGE`，默认 `var/paperflow`。 [文件访问](../backend/src/main/java/com/paperflow/adapters/files/FileStorage.java) · [全文服务](../backend/src/main/java/com/paperflow/fulltext/FullTextService.java) · [全文查询](../backend/src/main/resources/mapper/FullTextMapper.xml)

OpenAlex 匹配在同一来源内合并 `v2_openalex_work` 和旧作品候选。相同 `(source_id, work_id)` 优先使用新版元数据。先查 DOI，结果为空时才按年份、规范化标题和作者集合筛选。匹配写入 `v2_paper`，旧绑定检查与新版的唯一索引共同约束已关联的作品。 [OpenAlex 查询](../backend/src/main/resources/mapper/OpenAlexMapper.xml) · [匹配规则](../backend/src/main/java/com/paperflow/openalex/OpenAlexService.java)

## OpenAlex 快照读取

| 快照表 | 查询使用的列 | 用途 |
| --- | --- | --- |
| `sources` | `id`、`display_name` | 来源校验和名称搜索。 |
| `works` | `id`、`publication_year`、`title`、`doi` | 待读取的作品书目，支持可选年份范围。 |
| `works_locations` | `source_id`、`work_id` | 将作品限定在指定出版来源。 |
| `works_authorships` | `work_id`、`author_id` | 取作品的作者关联。 |
| `authors` | `id`、`display_name` | 提供作品作者姓名。 |

快照查询按作品 ID 分批，去重后每批最多 500 条。应用将书目写入 `v2_openalex_work`。来源选择器直接查询 `sources`，旧 `openalex_source_search` 是旧平台维护的搜索投影。快照表的完整 DDL 与索引由快照提供方维护。 [来源校验](../backend/src/main/resources/mapper/PaperMapper.xml) · [快照查询](../backend/src/main/resources/mapper/OpenAlexMapper.xml) · [快照测试结构](../backend/src/test/resources/snapshot.sql)

## 旧平台的其他数据库对象

下列对象来自旧平台文档、迁移、初始化脚本或运行时查询。v2 的 Flyway SQL、视图和业务查询均未引用它们。该清单描述源码依赖，删除或下线判断还需核实部署中的引用、外部使用者及保留要求。

| 旧平台职责 | 对象 | 源码中的用途 |
| --- | --- | --- |
| 本地 OpenAlex 投影 | `work_topic`、`openalex_source_search` | 旧作品主题、因果图谱的学科筛选和旧来源搜索投影。旧数据库设计文档另提到 `work_keyword`，当前旧平台明示将其排除在处理流程外。 |
| 文件生命周期 | `original_file_version`、`file_cleanup_operation` | 旧原件版本历史及文件操作补偿。v2 从 `original_file` 获取旧当前原件，文件修订号在 v2 单独管理。 |
| 批量原件导入 | `original_file_import_batch`、`original_file_import_item` | 旧 ZIP 导入批次及各行状态。 |
| 来源导入及处理 | `openalex_journal_import_task`、`processing_run`、`processing_run_item` | 旧来源元数据导入任务、处理目标和逐项执行结果。 |
| 来源删除 | `source_guard`、`source_deletion_task`、`source_deletion_item` | 旧来源并发保护、删除任务及目标清单。 |
| 论文去重 | `paper_dedup_scan`、`paper_dedup_control`、`paper_dedup_input`、`paper_dedup_group`、`paper_dedup_member`、`paper_dedup_resolution` | 旧去重扫描、输入快照、候选证据和人工处置记录。`paperflow_dedup_snapshot` 是旧平台的 SQL 函数。 |
| 管理账号与审计 | `admin_user`、`admin_audit_log` | 旧账号、角色和审计记录。 |
| 因果抽取与发布授权 | `causal_extraction_task`、`causal_extraction_item`、`causal_extraction_artifact`、`causal_publication` | 旧抽取任务、输入与进度、产物登记、待投递的授权。 |
| 因果数据与查询 | `claim_table`、`paper_claim_table`、`stw_label`、`causal_publication_receipt`、`causal_dataset_revision` | 旧因果事实、论文证据、词表、发布回执和数据集修订。旧平台可通过 `CAUSAL_DB_*` 使用其他数据库或 schema。 |
| 旧迁移历史 | `flyway_schema_history`、`flyway_causal_schema_history` | 旧主库和旧因果库各自的 Flyway 版本历史。 |
| 仅见于旧迁移历史 | `java_background_task`、`java_background_task_item`、`alembic_version`、`processing_worker_heartbeat` | 旧 `V9` 移除前三张表，旧 `V11` 移除心跳表。残留与否取决于实际应用的旧迁移版本。 |

旧平台依据来自 `paperflow-admin-platform` 的 `docs/database/db_design.md`、`docs/database/admin_user_init.sql`、`java-admin/src/main/resources/db/`、`java-admin/src/main/resources/mapper/`、`java-processing-worker/src/main/resources/mapper/` 及 `ClaimInEconomics/insert_data/init_database.py`。v2 测试夹具也放入部分因果和旧搜索表，供前后内容比对。 [v2 历史测试结构](../backend/src/test/resources/legacy.sql) · [测试比对](../backend/src/test/java/com/paperflow/integration/WorkspaceFixture.java)
