# AI Economist 论文数据平台调研

AI Economist 采用单列浅色侧栏，保留「总览」「论文库」「导入与处理」「OpenAlex」四个业务入口。「系统管理」下设「系统状态」「用户管理」「角色管理」「审计日志」，有效配置在系统状态页内展示。首期原型用「开通账号 → 分配角色 → 核查审计」验证管理流程，同时走查论文处理和 OpenAlex 复核。调研依据产品官方文档及公开界面，查阅日期为 2026 年 11 月 23 日。 [修订规格](spec.md)、[首版设计](design.md)、[术语表](../GLOSSARY.md)、[OpenAPI 契约](openapi.yaml)

## 市场平台怎样组织系统管理

| 产品 | 官方文档显示的入口和操作 | AI Economist 可借鉴的设计 |
| --- | --- | --- |
| DataHub | `Settings → Users & Groups` 管用户和组，可邀请用户或在用户行上改角色。`Settings → Permissions → Roles` 展示内置 Admin、Editor、Reader，角色详情包含成员和对应策略。高级策略分平台权限与元数据权限，经选择权限、人员或组完成授权。 [用户邀请](https://docs.datahub.com/docs/authentication/guides/add-users)、[角色及界面截图](https://docs.datahub.com/docs/authorization/roles#using-roles)、[组管理](https://docs.datahub.com/docs/authorization/groups)、[访问策略](https://docs.datahub.com/docs/authorization/access-policies-guide) | 用户表和角色表双向可达。授权时在用户旁显示角色，角色详情中能查有哪些人受变更影响。内置角色覆盖初期常见职责。 |
| OpenMetadata | 管理指南覆盖用户、分层团队和角色策略。策略按资源、操作和条件判断访问。`Settings → Access Control → Permission Debugger` 选用户、资源、操作后检查允许或拒绝，并显示评估信息。 [管理指南](https://docs.open-metadata.org/v2.0.x/how-to-guides/admin-guide)、[角色策略示例](https://docs.open-metadata.org/v2.0.x/how-to-guides/admin-guide/roles-policies/use-cases)、[权限排查](https://docs.open-metadata.org/v2.0.x/how-to-guides/admin-guide/permission-debugger) | 在权限页用业务操作名称说明能做什么。用户详情可先显示实际可用的操作。团队层级和条件策略适合业务范围进一步细分后评估。 |
| Metabase | `Admin → People` 管邀请、账号编辑和停用，停用后保留该账号创建的内容，可从停用账号列表恢复。`Admin → Permissions` 按组或数据资源看权限，并把应用管理授权单列。`Admin → Settings → General` 提供实例设置。`Monitor` 集中后台任务、计划任务和应用日志，部分功能受版本限制。 [用户与组](https://www.metabase.com/docs/latest/people-and-groups/managing)、[权限概览](https://www.metabase.com/docs/latest/permissions/introduction)、[通用设置](https://www.metabase.com/docs/latest/configuring-metabase/settings)、[监控](https://www.metabase.com/docs/latest/monitor/start) | 账号使用启用和停用管理访问，任务历史仍可按原操作者查阅。配置按作用范围分组，后台任务异常能进入对应运行记录。 |
| Airbyte | 组织或工作区的 `Settings → Members` 管成员角色，工作区角色影响所属工作区。部分版本的 `Organization settings → Audit logs` 按时间、操作者、操作和结果查询管理行为。连接的 `Status` 和 `Timeline` 展示同步健康、历史及失败详情。 [成员管理](https://docs.airbyte.com/platform/access-management/manage-users)、[角色和授权范围](https://docs.airbyte.com/platform/access-management/rbac)、[审计日志](https://docs.airbyte.com/platform/access-management/audit-logs)、[状态](https://docs.airbyte.com/platform/cloud/managing-airbyte-cloud/review-connection-status)、[运行历史](https://docs.airbyte.com/platform/cloud/managing-airbyte-cloud/review-connection-timeline) | 明示角色覆盖范围。管理员可以从系统运行状态进入相关处理任务，从审计日志追查权限变更。 |
| Atlassian Administration | `Insights → Audit log` 支持按日期和活动搜索。选中日志后右侧展开详情，展示时间、动作和操作者，技术字段另有 JSON 视图。 [审计查询与详情](https://support.atlassian.com/security-and-access-policies/docs/view-audit-log-activities/) | 管理员可以连续浏览多条事件。列表保留筛选和当前页，详情先显示可读的变更摘要，按需展开技术标识。 |

Metabase 按组授权，多个组通常合并较宽权限。Airbyte 可将角色限定在组织或工作区。DataHub 内置角色和自定义策略共存。AI Economist 当前只有一个论文工作区，每个用户直接分配一个角色，可选固定内置角色或自定义业务角色。团队协作需求出现时再决定组和范围授权的规则。 [Metabase 组权限](https://www.metabase.com/docs/latest/permissions/introduction)、[Airbyte 组织和工作区角色](https://docs.airbyte.com/platform/access-management/rbac)、[DataHub 角色与策略](https://docs.datahub.com/docs/authorization/roles#how-do-roles-interact-with-policies)

## 界面截图对照

以下观察来自已查看的官方界面截图。布局和视觉建议用来指导 AI Economist 原型。

| 界面 | 截图里能看到的设计 | 应用于 AI Economist |
| --- | --- | --- |
| DataHub 用户与角色 | 白色列表用细横线分行。用户姓名和身份状态靠近，右侧为具名角色下拉和行菜单。角色详情先显示描述，再展示成员和策略。 [用户列表截图](https://raw.githubusercontent.com/datahub-project/static-assets/main/imgs/roles/user-list-roles.png)、[角色详情截图](https://raw.githubusercontent.com/datahub-project/static-assets/main/imgs/roles/view-roles-list.png) | 用户表保留姓名、登录名、角色、状态及行菜单，其余属性进入详情。把邀请或新增作为页面主操作，改角色时让管理员看到选中角色及变更后的行为。 |
| Metabase 权限 | 白底、少量横线和较多留白。顶部区分数据、内容和应用权限。资源走左列，右侧表格的权限结果带有文字与图标。 [权限页截图](https://cdn.metabase.com/learn/images/guide-to-data-permissions/automatic-data-settings.png)、[示例说明](https://www.metabase.com/learn/metabase-basics/administration/permissions/data-permissions) | 左列列出角色，右侧按业务模块列权限，用「查看论文」「发起解析」「管理用户」等操作动词。按角色展示时也可避免宽表难以阅读。 |
| Atlassian 审计详情 | 图中的左侧列表仍在页面内，选中行以浅蓝色标记，右侧白底详情按摘要、操作者、位置分组，字段用键和值两列对齐。 [详情截图](https://images.ctfassets.net/zsv3d0ugroxu/5rDJIxkaZxc4Q4jATqVy94/b39be249924ee9f0338e0df6090bad68/Detailed_panel_full_screen) | 保留当前审计筛选。详情按事件、操作者、目标和变更分组。用相同结构查看用户角色分配和角色权限调整。 |
| Airbyte 连接状态 | 浅灰底承托白色区域，标题和连接信息在前，状态与下一次同步集中显示。表格用小图标加文字表达同步结果，行尾只留一个菜单。 [连接状态截图](https://docs.airbyte.com/assets/images/cloud-status-page-88407100afc2debe348e30ef21f75959.png) | 运行状态页优先显示每项依赖的检测结果和检查时间。用链接跳转到相关任务。图表在有历史检测数据和排障需求时再引入。 |
| Cloudscape 资源表 | 浅色左侧导航与数据区域用一条分隔线隔开。标题和创建按钮在首行，检索、排序和分页紧邻数据。状态同时使用图标和文字。 [表格示例截图](https://cloudscape.design/__images/yvlrib0vb3vb/4OVOKqSJpvCkem9gnCcHHi/3cfb5054734f743a5fa3805b9cc22719/automated--example--table-external-light-desktop.png.png)、[表格模式指南](https://cloudscape.design/patterns/resource-management/view/table-view/) | 论文库把搜索和筛选收进一行，用户管理沿用相同工具栏结构。加载失败和查询无结果使用不同反馈。 |

旧 B 版同时用了深蓝顶栏和深蓝侧栏。高饱和蓝重复出现在导航、导入按钮、论文标题、批量选择和分页。同屏多个区域因此都在争夺注意力。新方向用单列浅色导航、近白工作区、白色表面、深灰正文与低饱和蓝绿品牌色。各页用品牌色突出主要操作，例如「导入论文」「新增用户」或「新建角色」。正文标题、表格行、未激活导航和常规按钮依赖字号、字重、留白与中性色区分。 [旧 B 版原型](prototypes/paperflow-b.png)、[Atlassian Design 配色角色与强调级别](https://atlassian.design/foundations/color)

- 侧栏顶部显示「AI Economist」，当前入口采用浅色底与深色文字，系统管理在业务入口下方。页面标题、表格工具栏和数据区依次排列，让搜索、筛选、清除筛选和批量操作有固定位置。批量栏只在选中时出现。少用全框分隔，保持行间线条细、文字对比清楚。 [IBM Carbon 数据表规范](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines)
- 表格标题和状态能直接读懂。发布日期、DOI 和邮箱用次要文字呈现，成功、待处理和失败用浅底配状态文字。分页和选中行保持较低视觉权重。普通正文按 WCAG AA 目标检查 4.5:1 对比度，状态附文字或图标，键盘焦点清晰可见。 [W3C 颜色使用](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html)、[W3C 最低对比度](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)、[W3C 可见焦点](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html)

## AI Economist 系统管理方案

仓库目前的账号契约提供登录、会话查询、退出和本人改密。认证对其他 API 统一要求已登录。数据模型有 `v2_admin`，MinerU 已有 Compose 健康检查。以下管理用例供原型与后续契约设计，正式使用前需补足服务端授权、存储和观测数据。 [当前接口](api.md)、[数据库模型](database.md)、[认证配置](../backend/src/main/java/com/paperflow/main/SecurityConfiguration.java#L58)、[MinerU Compose 配置](../compose.yaml#L25)

| 页面 | 用户能完成的事 | 首期原型建议 |
| --- | --- | --- |
| 系统状态 | 判断依赖是否可用，查看当前生效配置 | 同页展示 PostgreSQL、两套文件存储、MinerU 和 OpenAlex 快照的检查结果与时间。下方列出上传限制、任务目标上限等只读有效值。运行配置中的连接地址和凭据由部署侧维护。 |
| 用户管理 | 查找账号、开通访问、调整角色、停用或恢复账号 | 按姓名或登录名搜索，按角色和状态筛选。列表显示姓名、登录名、角色和状态。开通时指定一个角色并生成临时密码。改角色和停用时说明影响，停用后历史任务继续可查。 |
| 角色管理 | 查看内置权限，创建和维护自定义业务角色 | 列表区分内置与自定义角色，详情显示说明、成员数、成员入口和权限清单。自定义权限按论文库、导入与处理、OpenAlex 分组。CSV 导出独立授权，保存权限变更前显示受影响成员数。 |
| 审计日志 | 查出谁在何时变更了账号、角色或论文数据 | 按时间、操作者、动作、对象和结果筛选。列表展示时间、操作者、动作、对象、结果。侧栏详情显示变更摘要与关联对象，示例覆盖角色分配、账号停用、论文编辑和任务重试。事件只显示必要的对象和结果信息，排除密码、令牌、连接凭据和正文。 |

建议首版实现时用服务端权限判断覆盖列表和写操作，并定义账号停用后现有会话失效、只读与导出权限的关系、角色变更生效时间，以及至少保留一位有管理权限的启用账号。删除自定义角色前，可要求先给其成员重新分配角色，保证每名用户始终只有一个角色。账号组、SSO 同步和细粒度到单篇论文的授权取决于后续团队需求。正式上线前应先确定角色与审计的持久化和 HTTP 契约。 [现有 OpenAPI 契约](openapi.yaml)、[沿用现有 schema 的 ADR](adr/0001-use-existing-paperflow-schema.md)

## 论文业务工具有哪些页面

样本同时覆盖数据目录、任务运维、文献管理和数据匹配。

| 工具 | 典型页面 | 官方文档所示的操作 | 来源 |
| --- | --- | --- | --- |
| OpenMetadata | 搜索、Explore、资产详情 | 用关键字和面向资产类型的筛选缩小范围。资产详情按类型提供结构、活动与任务、数据样例、版本历史和血缘等信息。 | [资产发现](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-discovery/discover)、[资产详情](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-discovery/details) |
| DataHub | 搜索结果、资产档案 | 搜索结果支持侧栏筛选。资产档案用标题区展示名称、类型、容器位置和同步状态，标签页呈现结构、文档、血缘、质量等信息，侧栏提供归属和标签。 | [搜索](https://docs.datahub.com/docs/how/search)、[资产档案](https://docs.datahub.com/docs/features/feature-guides/asset-profiles) |
| Apache Airflow | 运维首页、工作流列表、运行详情、任务详情 | 首页汇总近期运行并提供按状态查看工作流的入口。用户从工作流进入某次运行，看到每个任务的状态、尝试次数与时间，再进入具体任务查看错误和日志。 | [界面概览](https://airflow.apache.org/docs/apache-airflow/stable/ui.html#home-page)、[运行详情](https://airflow.apache.org/docs/apache-airflow/stable/ui.html#dag-run-view)、[任务详情](https://airflow.apache.org/docs/apache-airflow/stable/ui.html#task-instance-view) |
| Zotero | 文献库、条目详情、PDF 阅读器、重复条目 | 文献条目和 PDF 附件各有入口。导入 PDF 后可自动补齐书目，找不到书目时可手动处理。重复条目集中显示，可比较字段后合并。 | [添加附件](https://www.zotero.org/support/attaching_files)、[PDF 元数据](https://www.zotero.org/support/retrieve_pdf_metadata)、[PDF 阅读器](https://www.zotero.org/support/pdf_reader)、[重复条目](https://www.zotero.org/support/duplicate_detection) |
| OpenRefine | 导入预览、数据表、匹配结果 | 从预览检查 CSV 的行、列和解析设置。外部关联完成后，可筛选未关联行，查看服务提供的候选信息，再逐项选择。 | [导入预览](https://openrefine.org/docs/manual/starting#project-preview)、[匹配与复核](https://openrefine.org/docs/manual/reconciling) |

数据目录把检索结果与单条记录的上下文连起来，任务运维把执行摘要与失败项连起来，文献工具让书目和附件分别可查，匹配工具把有歧义的记录留给人工复核。AI Economist 同时需要这些路径，建议让论文详情与任务结果可以相互跳转，使管理员既能从一篇论文追踪处理，也能从一个批次定位受影响的论文。 [OpenMetadata 资产详情](https://docs.open-metadata.org/v2.0.x/how-to-guides/data-discovery/details)、[Airflow 运行详情](https://airflow.apache.org/docs/apache-airflow/stable/ui.html#dag-run-view)、[Zotero 附件](https://www.zotero.org/support/attaching_files)、[OpenRefine 匹配](https://openrefine.org/docs/manual/reconciling)

## 论文工作区页面建议

| 页面 | 管理员要完成的事 | 建议呈现方式 |
| --- | --- | --- |
| 总览 | 登录后找到需要确认、继续处理或检查的工作 | 用全量筛选结果显示待解析、待导入全文、处理失败和已发布全文的论文数量。展示近期任务的类型、状态、时间和进度。计数链接到对应的论文筛选，近期任务直接进入详情。运行检查放在系统管理。 |
| 论文库 | 找到论文、勾选处理对象、导出当前筛选 | 标题、作者、DOI 检索与出版来源、年份、全文状态筛选相邻。并列显示原件和全文状态，明确勾选数量与处理范围。保留单篇 PDF 上传、ZIP 批量导入和归档视图。 |
| 论文详情 | 核对书目和原件、继续处理、查阅已发布全文 | 头部突出标题和作者，主体提供书目信息、原始文件和全文阅读。处理区显示当前原件对应的状态和可执行操作，可进入该论文的有关处理记录。单独显示 OpenAlex 关联。原件缺失时指向仍可查阅的历史全文。 |
| ZIP 预检 | 确认哪些文件会导入、定位需要修正的条目 | 显示待导入、重复跳过和有问题的数量。逐项显示 CSV 行号、文件、原因和已有论文链接，确认按钮直接写明将导入的条数。可复用任务详情承载这一阶段。 |
| 导入与处理 | 找到某次操作、判断进度、定位失败 | 列表按类型和状态筛选，显示输入范围、发起人、时间及进度。详情展示成功、跳过和失败数量，按项状态筛选后在全批次结果中分页。管理员可以打开受影响的论文、查看失败原因和重试失败项。 |
| OpenAlex | 搜索出版来源、导入外部元数据 | 选择来源和可选年份后提交元数据导入，任务结果进入「导入与处理」。作品匹配从论文详情或指定范围发起，关联状态独立于全文处理。 |
| 候选复核 | 确认有歧义的 OpenAlex 关联 | 从匹配任务打开本地论文和外部候选的字段对照，展示标题、有序作者、出版来源、年份、DOI 和作品 ID。选择一条候选后确认，离开页面时可保留待确认状态。 |

OpenAlex 官方 Work 文档列出作品标识符、DOI、发表年份、作者和来源位置等字段，可用于解释候选差异。AI Economist 在同一出版来源内先查规范化 DOI。查无 DOI 候选时，以发表年份、规范化标题及去重并排序后的规范化作者姓名比较书目。找到唯一候选时尝试关联，多条候选由管理员复核。作者原有顺序用于呈现书目和生成论文 ID。复核表示建立关联，本地书目信息继续由论文详情的编辑操作维护。 [OpenAlex 作品字段](https://help.openalex.org/data/works/attributes/)、[OpenAlex 作者关系](https://help.openalex.org/data/authorships/)、[匹配实现](../backend/src/main/java/com/paperflow/openalex/OpenAlexService.java#L75)、[论文 ID 实现](../backend/src/main/java/com/paperflow/paper/model/PaperMetadata.java#L39)

## 建议优先级

| 优先级 | 建议 | 判断依据 |
| --- | --- | --- |
| 首期原型 | 使用浅色导航和通用表格布局，覆盖账号开通、角色分配、自定义业务角色编辑、用户停用、审计查询及状态与有效配置。同步新增总览，增强任务项筛选、论文到处理记录的跳转、OpenAlex 候选对照。 | 看管理员能否在日常业务和系统管理之间找到对应入口。先从待办找到工作、从论文或批次定位失败，再从系统管理查出账号与角色变更。 |
| 首批平台实现 | 用户生命周期、固定内置角色和自定义业务角色、服务端授权、管理操作审计。同页接入只读有效配置和能实际检测的依赖状态。 | 管理能力上线后必须能够限制访问并追溯变更。使用真实配置和观测结果填充页面。 |
| 后续验证 | 组管理与 SSO、多范围授权、运行告警及历史趋势。论文侧评估原件与已发布全文并排核对。 | 从岗位职责差异、重复授权操作、实际排障分工和对照原文频次判断投入。 |
| 按需求评估 | 跨库血缘图、标签和收藏、PDF 批注。 | 出现实际的跨数据源追溯或协作阅读用例后，确定需要的页面与数据。 |

当前前端的匹配候选选择器显示标题、年份和作品 ID。现行论文列表和任务列表返回符合筛选条件的总数，任务列表支持按类型、状态查询。逐项结果接口支持分页。首期如要让跨页状态筛选和论文到任务查询生效，需要补充相应的 API 查询契约。若总览后续显示待确认匹配数，统计口径应以当前仍待确认的论文为准，同一论文的历史匹配任务需要去重。 [候选选择器](../frontend/src/jobs.tsx)、[OpenAPI 契约](openapi.yaml)

## 原型走查情景

1. 系统管理员创建自定义业务角色并选定权限，为新同事开通账号并分配这一角色。通过角色详情检查其权限和成员，随后到审计日志按操作者分别查出角色创建和分配记录。
2. 系统管理员停用账号后，仍能从任务详情看到该账号发起的任务。在正式功能验收中，用该用户原有会话访问 API，验证权限失效。
3. 系统管理员打开「系统状态」，发现 MinerU 不可用，在同页核对当前只读配置。其他依赖仍展示各自结果。使用尚无检查结果的示例，确认「未检查」与「不可用」容易区分。
4. 管理员上传含两篇有效论文、一条重复记录和一条缺文件记录的 ZIP。从「总览」进入待确认批次，指出四行各自的结果，打开重复论文，确认只导入两篇。
5. 管理员从论文详情打开有关的解析任务，按状态找到失败项，查看失败原因并重试。再对已成功解析的论文导入全文并返回论文详情查阅。
6. 管理员打开一篇已经发布全文的历史论文。原件缺失时仍能访问全文。替换原件后能识别新原件待处理，且能区分这与归档及 OpenAlex 状态。
7. 本地论文未登记 DOI，同一来源的两个候选书目相同但 DOI 不同。管理员从原件核对 DOI 后确认对应候选，也可以暂缓处理。确认后回到论文详情检查作品 ID。

此次调研使用官方文档、公开截图和仓库现状。任务完成率和操作耗时有待通过管理员试用验证。
