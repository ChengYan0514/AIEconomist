# 因果抽取采用薄壳 Python HTTP Sidecar 与 Java 事件驱动编排

- Status: accepted

`ClaimInEconomics` 的两阶段 LLM 抽取、规范化与 STW embedding 映射已通过真实模型回放与验收，但旧平台依赖多进程数据库轮询（`JobWorker` + 租约心跳）与 `ProcessBuilder` 本地文件 IPC，部署和状态机均过于沉重。我们决定**原样保留 `ClaimInEconomics` 算法源码、prompts、schemas 与词表**，将其封装为独立薄壳 FastAPI HTTP 服务（`causal-engine/`，对齐现有 MinerU Sidecar 架构，零数据库凭据），Java 后端通过领域端口 `CausalExtractor` 对接，采用**事件驱动的有界异步编排器 + SSE 实时推送**替代数据库轮询 Worker。

## Considered Options

- **沿用 `v2_job` 数据库轮询或拆分独立 Java Worker 进程**：会把长耗时 LLM 编排、取消语义、阶段产物与 `v2_job` 常规批处理强耦合，或重新引入多 JVM 运维与租约心跳表结构。
- **Java 进程内直接 `ProcessBuilder` 拉起 Python 子进程**：将 Python 虚拟环境、OpenBLAS 线程配额、文件目录隔离与进程树清理绑定在 Java 进程宿主机上，IPC 仍依赖磁盘文件轮询。
- **用 Java 重写 `ClaimInEconomics` 算法**：无法低成本保证提示词、清洗截断、无标题分支与向量相似度映射与已验证基线逐行为一致。

## Consequences

- Java 后端在运行时不再轮询数据库领取因果任务，仅在启动时执行一次恢复对账，核对遗留 `RUNNING` 记录的远端 `engine_run_id` 状态。
- 因果抽取数据模型精简为 `v2_causal_batch` 与 `v2_causal_run` 两张业务表，不保存 `created_by`、租约或产物元数据表；操作者由 `v2_audit_event` 统一记录，产物经固定白名单流式代理下载。
- 部署上新增一个无状态（仅持本地临时工作目录）的 `causal-engine` 服务，可通过 `compose.yaml` 或本地 venv 启动，并纳入 `/system-status` 的 `/v1/health` 依赖探针；抽取功能默认关闭，按部署开关启用。
