# 客户端采用同进程 Java 宿主与外部异构能力引擎接入架构

- Status: accepted

AI Economist 需要同时面向论文数据管理员（数据管理端）与科研使用者（客户端，规划包含参考文献自动标注、学科知识问答与论文写作智能体），且客户端各 AI 模块可能由不同开发者或 Agent 使用 Python、TypeScript 等不同技术栈独立演进。我们决定**在现有 Java Spring Boot 后端（`backend/`）内以独立业务包 `com.paperflow.client.*` 和 `/api/client/*` 路由前缀承载客户端宿主框架**，直接复用同一套 `PAPERFLOW_SESSION` 会话、实时权限撤销、`v2_role` 角色体系与本地 PostgreSQL 书目/期刊库；各具体 AI 能力模块则延续 [ADR 0003](0003-causal-engine-http-sidecar.md) 的 Sidecar 模式，保持无数据库外部服务，通过领域端口和语言无关的 HTTP/SSE 协议接入 Java 宿主。

## Considered Options

- **独立部署第二个 Java Spring Boot 进程（`client-backend/`）**：可获得物理独立的 Maven 工程，但当前认证基于 JVM 内存 `HttpSession`，拆分进程必须额外引入 Spring Session（Redis/JDBC）或跨进程 Token 桥接，并重复维护一套本地 OpenAlex 与论文库查询，增加部署和运维复杂度。
- **使用 Python FastAPI 直接承担整个客户端后端**：虽与参考项目 `CitationsRetrievalSystem` 同栈，但不利于统一收敛后续可能用 TypeScript 等语言重写的多个 Agent 项目，也无法零成本复用现有 Java 认证、RBAC 与数据库访问基础设施。
- **在 Java 进程内重写或强耦合特定 AI 编排框架**：限制了各模块自由选用 LangGraph、TypeScript Agent SDK 等生态，导致算法迭代与宿主平台生命周期紧耦合。

## Consequences

- 平台新增内置角色 `RESEARCHER`（研究者）与权限 `CLIENT_ACCESS`；既有管理端内置角色默认附带 `CLIENT_ACCESS` 支持双端切换，纯研究者账号仅能进入客户端 `/client/*` 与 `/api/client/*`。
- 客户端宿主负责认证隔离、模块可用性目录、本地期刊目录、本地 OpenAlex/论文元数据补全、按用户持久化的任务历史以及人工审阅后的确定性引文重排与导出；下游外部引擎专注检索与 AI 推理，无需直连主业务数据库。
- 契约与目录在仓库内清晰隔离：客户端 HTTP 契约独立维护在 `docs/openapi-client.yaml`，Java 实现限定在 `com.paperflow.client.*`（独立 `@Configuration` 与作用域限定于该包的异常处理器），共享触点（`Permission`、`Role`、`SecurityConfiguration`、`V8` 迁移与配置占位）在并行开发基线提交中预先冻结，以支持通过 `git worktree` 并行开发后低冲突合并。
