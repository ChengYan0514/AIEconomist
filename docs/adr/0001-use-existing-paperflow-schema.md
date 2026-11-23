# 沿用现有 Paperflow schema

新版直接使用 `openalex` 数据库的 `widi_chengyan` schema，因为当前账号无法创建 schema，且已有论文和全文可在原地接续。接入时保留 `block*`、`causal*`、`*claim*`、`source`、`work*` 和 `openalex*` 表的现有数据，OpenAlex 快照 `dataset_20241125` 作为只读来源。

两套系统的写入边界以表为单位。新版读取历史论文登记、处理状态、文件路径和全文，所有新增及编辑写入 `v2_` 前缀表，独立记录 Flyway 版本。历史 ID 保持原样，新版第一次编辑或处理历史论文时才登记该论文的新版数据。这样可以直接查阅旧库正文，并保留已有全文、OpenAlex 绑定和因果引用。旧版 Worker 在正式切换时停止领取新任务。
