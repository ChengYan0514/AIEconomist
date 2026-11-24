# 旧平台下线后收口论文主表与 OpenAlex 作品表

- Status: accepted（局部修订 [ADR 0001](0001-use-existing-paperflow-schema.md)）

## 背景

[ADR 0001](0001-use-existing-paperflow-schema.md) 制定于旧平台（`paperflow-admin-platform`）与新版共库并行运行期间，采用「旧表全部只读、所有新增和编辑写入 `v2_` 前缀表」的边界以隔离两套系统。随着旧平台服务彻底下线与旧任务/用户/去重表清理完成，继续维护 `v2_paper` / `original_file`、`v2_openalex_*` / `work*` 双套表与 `source` 静态副本只会增加查询复杂度、重复存储与新期刊名称缺失问题。

## 决策

1. **论文主表统一收口至 `v2_paper`**：在 `v2_paper` 增加 `legacy_status` 字段保存历史原件修订（`file_revision = 0`）的处理状态，将全部历史论文一次性从 `original_file` + `original_file_job` 灌入 `v2_paper`；`v2_paper_read` 改为基于 `v2_paper` 的单表视图，`block.file_id` 与 `text_file.file_id` 外键改指向 `v2_paper(paper_id)`。`original_file` 与 `original_file_job` 停止运行时读写，暂留数据库并标记为 `DEPRECATED`，待业务全面验证后再删除。
2. **OpenAlex 本地作品库原地复用 `work`、`work_source`、`work_author`、`work_topic`**：删除空表 `v2_openalex_work` 与 `v2_openalex_work_topic`，消除全部双表 `UNION` 查询；保持 3NF 规范化结构（不将 `work*` 合并为扁平单表，避免搬迁既有数据并保留 `author_id` 与多来源扩展能力），`work_topic` 不新增未消费的 `imported_at` 列，仅放宽文本列类型为 `text` 并补充 `subfield_name` 索引。
3. **移除本地 `source` 表**：删除 `work_source` 与 `original_file` 指向 `source` 的旧外键后删除 `source` 表。当前论文库涉及的出版来源集合以 `v2_paper.source_id` 聚合为准，期刊名称统一从只读 OpenAlex 快照 `${schema}.sources`（默认 `dataset_20241125.sources`）关联获取。
4. **旧 MinerU 全文只读包保持独立**：因 MinerU 升级导致结构化解析格式变化，历史 `text_file` 及 6 张 `block*` 表继续只读保留，与新版 `v2_parse_artifact` / `v2_content_block` 分表并存。

## 影响

- Flyway `V7__consolidate_papers_and_openalex.sql` 负责结构收口、历史论文全量回填、外键改指与视图简化。
- OpenAlex 导入任务改为按批事务顺序写入 `work` → `work_source` → `work_author` → `work_topic`，DOI 匹配同时兼容历史 `https://doi.org/` 前缀与规范化纯 DOI。
- 论文列表/详情、期刊目录及因果图谱元数据统一从 `${schema}.sources` 获取出版来源名称，彻底解决新增期刊 `source_name` 为空的问题。
