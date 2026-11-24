# 上游来源与适配范围

来源：[Paperflow PR #2](https://github.com/ChengYan0514/paperflow/pull/2)。原提交
`2b14a0755bc76a34a18eb13f4e786ced2b2a66b2`、
`20804eaa3c12862fb1ea1ca73ba6822a17b4e2ce`、
`6f822017cb513604c4a9b778b13f75697bafead2` 已保留原哈希并合入集成分支。

接入使用原始 Python 处理函数，新增 `platform_entry.py` 作为任务适配入口，
不替换 prompts、响应 schemas 或标准词表。与上游的修复差异：

- `insert_data/pipeline_db.py`：将提前 return 移出循环，定义空 CSV 返回。
- `insert_data/init_database.py`：修正连接模块，禁用默认删表重建；平台使用独立 Flyway 命令。
- `requirements.txt` 与 `requirements.lock.txt`：修复依赖文件格式，补齐 scikit-learn 并固定验证环境。
- `scripts/common.py`：允许平台关闭 SDK 内部重试、设置请求超时，离线原有默认行为保留。
- `scripts/map.py`：embedding 不完整时失败，不再以缺失向量产生看似有效的标准词。

适配器负责受控路径、请求计数、原始调用结果检查、阶段检查点和 typed JSON 产物；
任务领取与数据库写入属于 Java。旧的 CSV CLI 入口保留。

实际部署补齐了 release 软链接指纹、模型调用前零用量登记，并保留原 600 秒请求窗口。
受控配置可附加 chat 专用的 provider 参数，以兼容当前 Qwen 服务的 JSON 输出；不修改原
提示词、schema 或 embedding 请求。Python 子进程的科学计算线程池由 Java 限制，以适应
服务资源上限。历史库的重复关系与主键生成差异由 Java 发布及 Flyway 迁移处理，不在
Python 算法中清理或重写历史事实。

上游 dataset/ 和 llm/ 为样本及运行产物，保留于源码与历史，但不属于生产执行包。
部署、模式与验证命令以仓库 [运维手册](../docs/runbook.md#因果抽取部署与排障) 为准。
