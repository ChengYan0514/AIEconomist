# AI Economist 管理 API

本文记录当前版本的管理端 HTTP API。路径均以 `/api` 开头，默认后端地址为 `http://127.0.0.1:8095`。请求和响应默认使用 JSON。上传使用 `multipart/form-data`，原始文件、图片和 CSV 使用各自的媒体类型。[OpenAPI 契约](openapi.yaml) · [本地启动](../README.md#本地启动) · [术语表](../GLOSSARY.md)

## 会话和 CSRF

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/csrf` | 可匿名调用 | `200`，`{headerName, token}` |
| `POST /api/session` | JSON `username`、`password`，需要 CSRF | `200`，当前账号和权限，切换会话 ID |
| `GET /api/session` | 发送会话 Cookie | `200`，当前账号和权限 |
| `DELETE /api/session` | 发送会话 Cookie 和 CSRF | `204`，结束当前会话 |
| `POST /api/session/password` | JSON `currentPassword`、`newPassword`，需要会话和 CSRF | `204`，密码更新后撤销使用旧凭据建立的会话 |

先取 CSRF，再用同一 Cookie 容器登录。登录成功后继续保存响应更新的 `PAPERFLOW_SESSION`。除获取 CSRF 和登录外，下列 API 均需要有效的平台会话。每次发送 `POST`、`PUT`、`PATCH` 或 `DELETE` 前，可以按管理前端的方式刷新 CSRF，将返回的 `token` 放进 `headerName` 指定的请求头。注销或改密后，重新从获取 CSRF 开始。会话超时配置为 8 小时。新密码至少 12 个字符，UTF-8 编码最多 72 字节。

会话返回 `username`、`displayName`、`role`、`mustChangePassword` 和 `permissions`。使用临时密码登录时，业务请求返回 `403 PASSWORD_CHANGE_REQUIRED`，可查看会话、获取 CSRF、改密或退出。修改角色后，已有会话的后续请求使用新权限。权限代码与各内置角色的能力见 [OpenAPI 权限说明](openapi.yaml)。

以下 Bash 示例需要 `curl` 和 `jq`。在同一个 shell 中继续执行后续示例，复用会话和 `write_api`。

```bash
base=http://127.0.0.1:8095/api
cookie=$(mktemp)
trap 'rm -f "$cookie"' EXIT

write_api() {
  local csrf header token
  csrf=$(curl --fail-with-body -sS -b "$cookie" -c "$cookie" "$base/csrf") || return
  header=$(jq -er '.headerName' <<< "$csrf") || return
  token=$(jq -er '.token' <<< "$csrf") || return
  curl --fail-with-body -sS -b "$cookie" -c "$cookie" -H "$header: $token" "$@"
}

read -r -p '管理员用户名 ' username
read -rs -p '密码 ' password
printf '\n'
jq -n --arg username "$username" --arg password "$password" '{username: $username, password: $password}' |
  write_api -X POST -H 'Content-Type: application/json' --data-binary @- "$base/session"
unset password
curl --fail-with-body -sS -b "$cookie" "$base/session"
```

## 论文和原始文件

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/papers` | 下表的论文筛选字段及 `page`、`size` | `200`，论文分页 |
| `GET /api/papers/export` | 与列表相同的筛选字段 | `200`，全部命中行的 CSV 附件 |
| `POST /api/papers` | multipart `file` 和书目字段，上传 PDF | `201`，论文对象 |
| `GET /api/papers/{paperId}` | 论文 ID | `200`，论文对象 |
| `PATCH /api/papers/{paperId}` | JSON 书目字段加 `version` | `200`，更新后的论文对象 |
| `POST /api/papers/{paperId}/archive` | 查询参数 `version`，无请求体 | `200`，归档后的论文对象 |
| `DELETE /api/papers/{paperId}/archive` | 查询参数 `version`，无请求体 | `200`，恢复后的论文对象 |
| `GET /api/papers/{paperId}/file` | 论文 ID | `200`，PDF 在线预览，其他类型使用 `application/octet-stream` 下载 |
| `PUT /api/papers/{paperId}/file` | 查询参数 `version`，multipart `file`，上传 PDF | `200`，替换后的论文对象 |

分页结构为 `{items, page, size, total}`。`page` 从 `0` 开始，默认 `0`。`size` 默认为 `20`，范围为 `1` 至 `100`。`total` 为筛选后总条数。论文按 `updatedAt` 倒序排列，无更新时间的论文排在末尾，同时间按 `paperId` 升序排列。

| 列表和导出参数 | 含义 |
| --- | --- |
| `q` | 去掉首尾空白后最多 200 个字符，忽略大小写，在标题、论文 ID、作者或 DOI 中查找子串 |
| `sourceId` | OpenAlex 出版来源 ID，按规范化后的 ID 精确筛选 |
| `yearFrom`、`yearTo` | 可分别省略，范围为 `1000` 至 `9999`，包含边界，起始年须小于或等于结束年 |
| `status` | `UNPROCESSED` 待处理、`READY` 有可导入的解析产物、`PUBLISHED` 有已发布全文、`FAILED` 解析或全文导入失败、`UNSUPPORTED` 当前格式暂不支持解析 |
| `archived` | 默认 `false`，只返回未归档论文。`true` 只返回已归档论文 |

导出文件名为 `paperflow-papers.csv`，使用带 BOM 的 UTF-8，列为 `paper_id,source_id,year,paper_title,authors,doi,status`。可能被电子表格解释为公式的文本值会带前导单引号。

上传及编辑必填 `sourceId`、`year`、`title`、`authors`。`sourceId` 规范化为大写，格式为 `S` 加 1 至 24 位数字，并须能在配置的 OpenAlex 快照中找到。年份为四位数字。标题和作者规范化后各最多 2,000 个字符，作者按顺序使用英文分号分隔。可选字段为 `doi`、`url`、`provider`。DOI 规范化为小写并移除常见 DOI 前缀，最长 500 个字符。`url` 最长 2,000 个字符，须为带主机名的 HTTP 或 HTTPS 地址。`provider` 最长 255 个字符。单个原始文件上限为 100 MiB。新论文 ID 由出版来源、年份、标题及有序作者确定，历史论文沿用原有 ID。

`PATCH` 要求重新提交四个必填书目字段。可选书目字段省略、设为 `null` 或置空会清除原值。编辑、归档、恢复、替换原件及确认匹配使用最新论文响应的 `version`。`revision` 表示原件修订，替换原件后递增，当前论文需要重新解析和导入全文。历史论文的初始版本可能为 `0`。收到 `PAPER_CHANGED` 后，重新读取论文再决定如何提交。

论文对象字段如下。历史记录的书目及文件字段可能为 `null`。

| 字段 | 含义 |
| --- | --- |
| `paperId` | 稳定的论文 ID，路径中应进行 URL 编码 |
| `sourceId`、`sourceName`、`year`、`title`、`authors`、`doi`、`url`、`provider` | 书目信息 |
| `originalName`、`originalType`、`originalSize` | 原件文件名、格式和字节数。常见格式为 `PDF`、`XML`、`HTML` |
| `hasOriginal` | 列表检查是否登记路径。详情和写操作响应检查实际文件是否可读 |
| `version`、`revision` | 记录并发版本、原件修订 |
| `archived`、`legacy` | 是否归档、是否为接续的历史论文 |
| `matchedWorkId` | 已关联的 OpenAlex 作品 ID，可为 `null` |
| `latestParseId`、`publishedParseId` | 最新解析产物和当前已发布解析产物的 UUID，可为 `null`。历史全文可以在 `publishedParseId` 为空时继续查阅 |
| `status`、`updatedAt` | 处理状态和带时区的更新时间，更新时间可为 `null` |

下例选择快照中的一个实际出版来源，并上传本地 PDF。

```bash
curl --fail-with-body -sS -b "$cookie" --get "$base/sources" --data-urlencode 'q=Nature'
read -r -p '使用上一步返回的出版来源 ID ' source_id
paper_id=$(write_api -F "sourceId=$source_id" -F 'year=2024' -F 'title=Example paper' \
  -F 'authors=Alice;Bob' -F 'file=@paper.pdf;type=application/pdf' "$base/papers" | jq -er '.paperId')
```

## 已发布全文

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/papers/{paperId}/blocks` | `page`、`size` | `200`，按 `sequence` 排序的内容块分页。暂无可读全文时 `items` 为空 |
| `GET /api/papers/{paperId}/blocks/{sequence}/asset` | 内容块的 `sequence` | `200`，PNG、JPEG、GIF 或 WebP 图片 |

内容块包含 `sequence`、`type`、`text`、`page`、`hasAsset`、`attributes`。`sequence` 是文档顺序编号，`page` 是内容所在的原始文档页码。`text` 可为 `null`，`attributes` 按块类型携带结构化属性。`hasAsset=true` 表示已登记资源，图片请求仍可能因文件缺失返回 `404`。

发起 `PARSE` 生成解析产物，任务完成后发起 `PUBLISH` 导入解析内容。两步都使用 `POST /api/jobs`，正文分别可用 `{"kind":"PARSE","paperIds":["论文 ID"]}` 和 `{"kind":"PUBLISH","paperIds":["论文 ID"]}`。每步查询任务详情及逐项结果。全文发布成功后读取内容块。同一原件重新解析期间，已发布全文可继续查阅。替换原件后，需要为当前原件完成这两个步骤才能发布对应的全文。

## ZIP 批量导入

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `POST /api/imports` | multipart `file`，上传 ZIP | `201`，`kind=ORIGINAL_IMPORT` 的预检任务摘要 |
| `POST /api/imports/{jobId}/confirm` | 预检返回的任务 UUID，无请求体 | `202`，同一任务摘要，将通过预检的项加入队列 |

ZIP 内须有一个 UTF-8 CSV，支持 BOM。必填列为 `source_id,year,paper_title,authors,file_path`，可选列为 `doi,url,provider`。`file_path` 是相对 ZIP 根目录的路径，引用包内 PDF、XML 或 HTML 原件，HTML 接受 `.html` 和 `.htm`。ZIP 上限 256 MiB，整包声明的解压体积上限 3 GiB，最多 10,000 个条目。CSV 上限 8 MiB，最多 3,000 条数据记录，单个原件上限 100 MiB。

```csv
source_id,year,paper_title,authors,file_path
S123,2024,Example paper,Alice;Bob,files/paper.pdf
```

用实际的出版来源 ID 替换示例中的 `S123`。上传响应的 `ready` 给出全批通过条数。`GET /api/jobs/{jobId}/items` 的 `sequence` 从 1 开始，表示不含表头的 CSV 数据记录位序。`preflight` 返回通过（`IMPORTABLE`）、重复（`DUPLICATE`）或有问题（`ERROR`）的分类，以及包内路径 `originalPath`、原因 `reason` 和重复的既有论文 ID `existingPaperId`。旧任务未保存的路径返回 `null`。

仅有 `READY` 项会进入队列，执行时复核重复。若无 `READY` 项，确认返回 `409`。确认后查询同一任务 UUID，重复确认会返回同一任务的当前摘要。任务项的 `state`、`message` 和 `result` 反映当前结果，仍可通过 `preflight` 查阅预检。预检失败的行需要修正后重新上传，执行阶段失败的行可在任务结束后重试。

## 任务查询和处理

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `POST /api/jobs` | JSON `kind` 和处理范围 | `202`，新任务摘要 |
| `GET /api/jobs` | 可选 `kind`、`state`、`page`、`size` | `200`，按创建时间倒序的任务分页 |
| `GET /api/jobs/{jobId}` | 任务 UUID | `200`，任务摘要 |
| `GET /api/jobs/{jobId}/items` | 任务 UUID，可选 `state`、`page`、`size` | `200`，按 `sequence` 排序的任务项分页 |
| `GET /api/papers/{paperId}/jobs` | 论文 ID，可选 `page`、`size` | `200`，该论文的处理记录分页 |
| `POST /api/jobs/{jobId}/retry` | 已结束且存在可重试失败项的任务 UUID，无请求体 | `202`，只包含可重试失败项的新任务摘要 |

| `kind` | 提交时的范围与用途 |
| --- | --- |
| `PARSE` | `paperIds` 或 `sourceId`，解析当前 PDF 原件 |
| `PUBLISH` | `paperIds` 或 `sourceId`，按提交时选定的解析产物导入全文 |
| `MATCH` | `paperIds` 或 `sourceId`，匹配 OpenAlex 作品 |
| `METADATA` | `sourceId`，从配置的 OpenAlex 快照导入该来源的作品元数据 |
| `ORIGINAL_IMPORT` | 由 `POST /api/imports` 创建，可在任务列表按此类型筛选 |

`paperIds` 和 `sourceId` 二选一。`paperIds` 至少包含一个论文 ID，每个 ID 最长 255 个字符，服务端对重复 ID 去重。通过 `sourceId` 提交时，可加 `yearFrom`、`yearTo`，规则与论文筛选相同。每个论文处理任务最多接收 10,000 篇论文，提交时固定目标集合。归档论文、已有匹配或不符合当前处理阶段的输入可被跳过，具体见逐项 `message`。

任务摘要返回 `jobId`、`kind`、`state`、`username`、`retryOf`、`createdAt`、`finishedAt`、`confirmedAt`、`scope`，以及 `total`、`ready`、`queued`、`running`、`succeeded`、`skipped`、`failed` 计数和 `canRetry`。`retryOf`、`finishedAt`、`confirmedAt` 可为 `null`。`scope` 包含来源范围、`selectedCount` 或 ZIP 的 `filename`。`canRetry` 表示整个任务已经结束并有可重试的失败项，调用者还需相应任务类型的执行权限。重试范围包含整个任务中符合条件的失败项，重试响应的 `retryOf` 指向原任务。

任务摘要状态为 `READY`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`PARTIAL`。`READY` 表示有项目等待确认。`queued + running` 大于零时可继续轮询。终态中，有成功项和失败项时为 `PARTIAL`，有失败项且无成功项时为 `FAILED`，无失败项时为 `SUCCEEDED`，包括全部跳过的情况。

任务项返回 `sequence`、`target`、`paperId`、`state`、`message`、`result` 和 `inputError`。`inputError` 为真时，需修正 ZIP 输入并重新上传。项状态为 `READY`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`SKIPPED`、`FAILED`。`state` 按任务项状态精确筛选，空值返回所有状态，其他非空值返回空列表。任务项的分页总数按筛选结果计算，任务摘要继续统计整个任务。`paperId`、`message`、`result` 可为 `null`。`result` 随类型变化，例如导入原件的 `{paperId}`、OpenAlex 元数据导入的 `{imported}`、匹配的 `{message, candidates, matched, candidateSource}`，以及解析执行时登记的 `{taskId, fileId, uploaded}`。当 `succeeded`、`skipped` 或 `failed` 非零时，可在任务项中进一步判断每个目标的结果。

论文处理记录需要 `PAPER_READ` 和 `JOB_READ`，支持历史和已归档的论文 ID。记录包含 `jobId`、`sequence`、`kind`、任务项的 `state` 和 `message`，以及任务提交时间 `createdAt`。先按论文 ID 筛选，再按提交时间和任务 ID 降序以及 `sequence` 升序分页。ZIP 行在导入成功或预检、执行时找到已登记论文时，按实际论文 ID 纳入记录。未知论文返回 `404 PAPER_NOT_FOUND`，仅有历史处理状态的论文返回空分页。

## OpenAlex 出版来源和匹配

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/sources` | 可选 `q`，去掉首尾空白后最多 200 个字符 | `200`，至多 25 个 `{sourceId, name}` |
| `POST /api/papers/{paperId}/match` | JSON `workId`、`version` | `200`，更新后的论文对象 |

来源搜索按 ID 精确匹配或按名称查找子串，忽略大小写。指定实际来源后提交 `METADATA` 可扩充该来源下的本地作品数据。`MATCH` 在同一来源内先查 DOI，无 DOI 候选时再按年份、规范化标题和作者集合查找。同一作品的候选对象包含 `workId`、`year`、`title`、`doi`、`authors`。

唯一候选可自动关联。多候选项以 `SKIPPED` 返回，`result.candidates` 保留本次匹配的候选书目，`result.matched` 为 `false`。`result.candidateSource` 保存匹配时的 `{sourceId, name}`，名称可能为空。旧结果可能缺少该字段，界面显示已记录的任务范围来源或提示来源缺失。

打开候选复核时读取当前论文和 `version`，确认提交该版本与所选 `workId`。服务端复核版本、当前候选及作品唯一性。成功响应的 `matchedWorkId` 为实际关联作品。若确认遇到冲突，可读取论文核对是否已有实际关联。未关联时，`PAPER_CHANGED` 需要刷新并核对当前论文，`CANDIDATE_MISSING` 需要重新匹配，`WORK_ALREADY_BOUND` 可尝试其他候选。

## 用户管理

| 请求 | 参数 | 成功响应 |
| --- | --- | --- |
| `GET /api/users` | 可选 `q`、`role`、`status`、`page`、`size` | `200`，用户分页，用户名升序 |
| `POST /api/users` | JSON `username`、`displayName`、`role` | `201`，`{user, temporaryPassword}` |
| `PATCH /api/users/{username}` | JSON `displayName`、`role` | `200`，更新后的用户 |
| `PUT /api/users/{username}/status` | JSON `status` | `200`，当前用户 |
| `POST /api/users/{username}/reset-password` | 无 | `200`，`{user, temporaryPassword}` |

这些接口需要系统管理员权限。搜索同时匹配用户名和显示名称，忽略大小写。每个账号有一个已登记的角色，取值来自 `GET /api/roles`。状态为 `ACTIVE` 或 `DISABLED`。用户名在创建时规范化，之后保持固定。

停用后旧会话返回 `401 AUTH_REQUIRED`，恢复后需要重新登录。停用保留该账号的任务记录，已提交的任务继续处理。将状态设置为当前值时直接返回当前用户。停用或降级最后一个启用的系统管理员会返回 `409 LAST_SYSTEM_ADMIN`。

临时密码仅在成功创建或重置的响应中展示，并设置 `Cache-Control: no-store`。重置会撤销旧密码和会话。启用的用户需要用临时密码登录并设置个人密码，随后重新登录。停用期间依然可以生成临时密码，用户在账号恢复后登录。

## 角色管理

| 请求 | 参数 | 成功响应 |
| --- | --- | --- |
| `GET /api/roles` | 无 | `200`，角色列表 `items` 和分组业务权限 `permissionGroups` |
| `GET /api/roles/{roleId}` | 角色 ID | `200`，角色及权限、成员 |
| `POST /api/roles` | JSON `name`、`description`、`permissions` | `201`，自定义角色详情 |
| `PUT /api/roles/{roleId}` | 同创建，另含编辑所依据的 `expectedUserCount` | `200`，更新后的角色详情 |
| `DELETE /api/roles/{roleId}` | 角色 ID | `204`，未分配的自定义角色已删除 |

这些接口需要系统管理员权限。内置角色 ID 和权限固定，自定义角色 ID 由服务端生成。自定义角色可包含业务权限，权限依赖由目录的 `requires` 字段提供且保存时校验。CSV 可独立授权。出版来源搜索也向有文件解析或解析内容导入权限的用户开放，以便按出版来源提交处理任务。

人数及成员包含启用和停用用户。编辑期间分配人数变化时返回 `409 ROLE_ASSIGNMENTS_CHANGED`。将所有成员重新分配后方可删除，否则返回 `409 ROLE_IN_USE`。角色调配或权限变更后，已有会话的后续请求立即使用新权限。

## 系统状态

仅系统管理员可请求 `GET /api/system-status`。每次请求重新检查 PostgreSQL 应用查询、MinerU 健康接口、新版文件存储的读写、旧版文件存储的读取，以及配置的 OpenAlex 快照必需表。每项检查最多等待 2 秒。单项故障仍返回 `200` 和其他检查结果。响应禁止缓存。

`checks` 中的每项包含 `dependency`、`state`、`checkedAt` 和固定的简短 `message`。状态可为 `HEALTHY`、`UNAVAILABLE` 或 `NOT_CHECKED`。已检查的结果包含 ISO 8601 时间。有效配置 `configuration` 包含应用构建版本 `version`、有效的单个原件和 ZIP 上传上限 `maxOriginalBytes`、`maxZipBytes`（以字节计），以及任务论文目标上限 `maxJobTargets`。上传上限取表单请求、文件和业务限制的最小值。表单字段和边界也计入请求大小。

## 审计日志

| 方法和路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /api/audit-events` | 可选 `from`、`to`、`actor`、`action`、`objectType`、`objectId`、`outcome`、`page`、`size` | `200`，审计事件分页 |
| `GET /api/audit-events/{eventId}` | 审计事件 ID | `200`，同一事件及 `summary` |

仅系统管理员可读取。时间按 ISO 8601 含时区格式填写，起止边界均包含。标识按去除首尾空白后的值精确匹配。所有筛选条件同时生效。时间相同时按事件 ID 倒序。每个事件包含 `eventId`、`occurredAt`、`actor`、`action`、`objectType`、`objectId` 和 `outcome`。

论文 CSV 导出的动作是 `PAPER_CSV_EXPORT`，对象是 `PAPER_COLLECTION` 类型的 `papers`。服务器完成 CSV 写入与刷新后记录 `SUCCEEDED`，摘要包含导出行数、是否使用搜索词、归档视图以及已应用的来源 ID、年份范围和处理状态。请求字段无效或写入中断时记录 `FAILED`，摘要只包含错误代码。未登录的请求返回 `401`，已登录但未获准的导出请求返回 `403` 并记录 `DENIED`。审计存储不可用时，原有导出错误或权限拒绝仍会返回。审计摘要排除原始搜索词、正文、CSV 内容、密码、密钥和会话标识。

论文导入、编辑、更换原件、归档、恢复、全文发布和手动确认匹配均可按 `PAPER` 及论文 ID 查找。摘要按操作保存版本、原件修订号、关联作品 ID 或发生变化的字段名。按 `JOB` 及任务 ID 可查 ZIP 预检、提交、重试、输入校验结果以及逐项执行结果。原件导入及全文发布事件同时保存任务 ID 和项序号。执行事件使用登记任务时的用户名，`itemState` 标明实际项状态。已跳过的项使用 `SUCCEEDED` 任务审计结果，并标明 `SKIPPED` 项状态。跳过时无论文导入或发布成功事件。失败事件仅保存固定错误代码和可用的关联标识。

事件详情可打开当前账号能读取且仍存在的对象。用户存在性通过 `GET /api/users/{username}` 按用户名检查，系统管理员可读取该接口。

用户创建、显示名称修改和角色变更分别记录为 `USER_CREATE`、`USER_DISPLAY_NAME_CHANGE` 和 `USER_ROLE_CHANGE`。停用、恢复和密码重置分别记录为 `USER_DISABLE`、`USER_ENABLE` 和 `USER_PASSWORD_RESET`。对象类型为 `USER`，对象 ID 为该用户的固定用户名。启停摘要记录前后状态，重置摘要只记录需要设置个人密码。

自定义角色创建、修改和删除分别记录为 `ROLE_CREATE`、`ROLE_UPDATE` 和 `ROLE_DELETE`。对象类型为 `ROLE`，对象 ID 为固定角色 ID。摘要记录角色名称、权限和受影响人数，编辑事件同时记录此前的名称、权限以及说明是否变化。

## 错误处理

应用错误返回 `{code, message}`。用 HTTP 状态和 `code` 决定恢复方式，`message` 可展示给管理员。异步工作返回 `202` 后，其执行错误通过任务项的 `state` 和 `message` 读取。

| HTTP 状态 | 常见 `code` | 含义 |
| --- | --- | --- |
| `400` | `INVALID_INPUT` | 字段、分页、文件格式或范围无效，也涵盖应用层单文件 100 MiB 上限 |
| `401` | `AUTH_REQUIRED`、`INVALID_LOGIN` | 需要有效会话，或登录凭据有误 |
| `403` | `ACCESS_DENIED`、`PASSWORD_CHANGE_REQUIRED` | 权限不足、需要先改密，或请求未通过 CSRF 校验 |
| `404` | `PAPER_NOT_FOUND`、`JOB_NOT_FOUND`、`FILE_MISSING`、`USER_NOT_FOUND`、`AUDIT_EVENT_NOT_FOUND` | 论文、任务、文件、用户或审计事件不可读取 |
| `409` | `PAPER_CHANGED`、`PAPER_ARCHIVED`、`DUPLICATE_PAPER`、`JOB_CONFLICT`、`CANDIDATE_MISSING`、`WORK_ALREADY_BOUND`、`USERNAME_TAKEN`、`LAST_SYSTEM_ADMIN`、`CONCURRENT_CHANGE` | 版本冲突、业务状态冲突或并发更新 |
| `413` | `FILE_TOO_LARGE` | 超过 multipart 请求配置上限，单上传文件 256 MiB，请求体 257 MiB |
| `500` | `INTERNAL` | 服务暂时无法完成操作 |
