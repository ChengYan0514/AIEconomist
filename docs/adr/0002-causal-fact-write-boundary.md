# 因果事实表原地共享写入与控制表分离

- Status: accepted（补充并局部修订 [ADR 0001](0001-use-existing-paperflow-schema.md)）

ADR 0001 约定新版只写入 `v2_` 前缀表，但既有因果图谱的事实数据（`claim_table`、`paper_claim_table`）以及发布控制表（`causal_publication_receipt`、`causal_dataset_revision`）已承载历史因果知识并可能供外部查询共享。我们决定对 ADR 0001 增设明确例外：**因果事实与发布控制表原地接续读写**，同时保留可选 `CAUSAL_DB_*` 独立数据源覆盖（缺省回退 `PAPERFLOW_DB_*`）；**STW 经济学主题词表为固定静态数据，不导入数据库**，由后端与 `causal-engine` 共用项目内的 `causal-engine/engine/scripts/stw_labels.csv`；而**新版因果抽取批次、单篇运行记录及图谱预聚合读模型**全部写入主库 `v2_` 前缀表（`v2_causal_batch`、`v2_causal_run`、`v2_causal_edge_index`、`v2_causal_snapshot`），由 `v2_flyway_schema_history` 管理。

## Considered Options

- **另建隔离的 `v2_claim` / `v2_paper_claim` 表**：保持 `v2_` 边界纯粹，但割裂历史因果数据与外部读侧，需额外迁移或双写合并。
- **直接原地复用旧 `causal_extraction_*` 任务表**：虽可沿用旧任务记录，但强耦合旧版 `bigint created_by`、租约心跳及 4 表调度结构，阻碍新版极简模型设计。
- **引入 Redis 缓存解决图谱与领域分析慢加载**：增加独立有状态基础设施运维负担，且无法消除冷启动穿透与任意筛选组合的全表扫描；改为在主库维护持久化 `v2_` 预聚合读模型并辅以 JVM 进程内缓存。

## Consequences

- 因果库继续使用独立 schema-owner 迁移脚本，应用运行账号不执行运行时 DDL；发布时必须保留 `LEGACY_DUPLICATE_RELATION` 历史重复键防护与以 `run_id` 为幂等键的 `causal_publication_receipt` 校验，同库与跨库走同一条幂等提交路径。
- 正式切换前停用旧平台因果 Worker，避免两套系统同时向因果事实表写入；历史旧抽取任务留在旧表归档，不再于新版续跑。
- 每次发布递增 `causal_dataset_revision` 后后台异步刷新 `v2_causal_edge_index` 与 `v2_causal_snapshot`，重启不丢失预聚合结果，同时提供手动刷新入口兼容外部 ETL 写入。
