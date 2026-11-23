# Paperflow

面向论文数据管理员的工作空间。管理员可以接续旧库中的论文和全文，也可以导入原件、发起 PDF 解析、发布结构化全文及关联 OpenAlex 作品。登录页提供管理端和客户端两个入口。

## 本地启动

需要 JDK 17、Maven、Node.js 22，以及支持 NVIDIA GPU 的 Docker Compose。复制 `.env.example` 到仓库根目录的 `.env`，将 `PAPERFLOW_DB_*` 填成旧项目的实际连接配置，将 `PAPERFLOW_LEGACY_STORAGE` 填成旧项目的 `DATA_ROOT`，并设置新版文件目录。示例里的 `127.0.0.1` 和 `/path/to/…` 要按部署替换。首次启动时填写管理员账号和至少 12 个字符的密码。账号建立后，可以从环境文件移除初始化密码。

从仓库根目录启动 Paperflow 的 MinerU 容器。Compose 使用本机预装模型的镜像 `nsfc-mineru-production:4.1.3-v2`，默认绑定宿主机 GPU 0。

```bash
docker compose up -d --wait --wait-timeout 600 mineru
curl --fail http://127.0.0.1:18002/v1/health
```

首次解析会加载模型，完成时间可能长于后续请求。

后端默认使用该回环地址。通过 `.env` 的 `PAPERFLOW_MINERU_GPU` 切换宿主机 GPU，通过 `PAPERFLOW_MINERU_PORT` 同步调整 Compose 映射和后端端口。沿用配置时，移除指向旧服务的 `PAPERFLOW_MINERU_URL`。等待在途解析任务完成后，重跑上面的 Compose 命令应用配置。调整端口后还需重启后端。可用 `docker compose logs -f mineru` 查看日志。

从仓库根目录运行以下两个命令，分别启动 API 和前端。

```bash
mvn -f backend/pom.xml spring-boot:run
```

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

在浏览器打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。前端开发服务默认代理到 `127.0.0.1:8095`，可用 `PAPERFLOW_API_TARGET` 覆盖。若通过 HTTPS 运行，设置 `PAPERFLOW_SECURE_COOKIE=true` 并在同一站点下提供前端与 API。

应用首次启动时在已存在的业务 schema 建立 `v2_` 表、读取视图和独立的 Flyway 历史表。旧论文、原件目录、正文和已有关联按需读取。生产切换时，先让旧 Worker 停止接收新任务。[数据库接续约定](docs/adr/0001-use-existing-paperflow-schema.md)

## 任务与输入

- 上传单篇接受 PDF。批量导入接受一个 ZIP，包内有一个 UTF-8 CSV 和对应的 PDF、XML 或 HTML 原件。CSV 必填列为 `source_id,year,paper_title,authors,file_path`。
- 批量导入 ZIP 上限 256 MiB，声明解压体积上限 3 GiB，单个原件上限 100 MiB。预检最多处理 3,000 条记录，列出问题和重复行，确认后导入通过预检的记录。
- 文件解析调用 MinerU 4.1 的 `/v1/tasks`，按文件上传 PDF、轮询并下载结果。网络中断后可重试并继续查询原任务。通过 `PAPERFLOW_MINERU_TIER` 可指定服务支持的解析档位，默认由服务选择。
- 解析完成后，从论文详情或任务中心发起全文导入。结构化 JSON 按页入库，保留标题、图表、公式及图片。历史解析产物仍可按旧格式读取。
- 处理目标可由所选论文或一个来源及年份范围确定。每个任务最多 10,000 篇，可用年份缩小范围。
- OpenAlex 元数据由来源选定，并可限制年份。匹配优先使用唯一 DOI，其次使用精确书目。多候选可在任务详情确认。
- 新版文件按原件修订和解析执行保存。任务中心保留预检、执行和重试记录。

[首版设计](docs/design.md) · [OpenAPI 契约](docs/openapi.yaml) · [术语表](GLOSSARY.md)

## 验证

```bash
mvn -f backend/pom.xml test
npm --prefix frontend run build
```

为 `PAPERFLOW_TEST_JDBC_URL` 提供独立 PostgreSQL 测试库可启用端到端测试。测试角色需能创建两个测试 schema，账号和密码用 `PAPERFLOW_TEST_DB_USER`、`PAPERFLOW_TEST_DB_PASSWORD` 配置。测试内启动 MinerU 测试服务，覆盖解析失败、重试和全文发布。
