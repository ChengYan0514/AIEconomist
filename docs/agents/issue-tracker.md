# Issue tracker

本项目的议题和规格记录在 `ChengYan0514/AIEconomist` 的 GitHub Issues 中。使用 `gh` 操作。首次发布前运行 `gh repo view --json nameWithOwner` 核对目标。下列议题和 PR 命令可加 `--repo ChengYan0514/AIEconomist` 显式指定目标。

## 议题操作

- 发布议题或规格时运行 `gh issue create --title "<标题>" --body-file <正文文件>`。
- 按编号读取议题时运行 `gh issue view <编号> --json number,title,body,labels,comments`。
- 列出议题时使用 `gh issue list`，按需指定 `--state` 和 `--label`。
- 评论议题时运行 `gh issue comment <编号> --body-file <正文文件>`。
- 应用或移除标签时使用 `gh issue edit <编号> --add-label "<标签>"` 或 `--remove-label "<标签>"`。标签映射见 `triage-labels.md`。
- 关闭议题前先发布说明，再运行 `gh issue close <编号>`。

## 子议题与阻塞关系

- 用 `gh api repos/ChengYan0514/AIEconomist/issues/<编号> --jq .id` 获取 API 所需的数字数据库 ID。
- 将议题添加为子议题时运行 `gh api --method POST repos/ChengYan0514/AIEconomist/issues/<父议题编号>/sub_issues -F sub_issue_id=<子议题ID>`。原生子议题功能不可用时，在子议题正文开头写 `Part of #<父议题编号>`。
- 登记阻塞关系时运行 `gh api --method POST repos/ChengYan0514/AIEconomist/issues/<被阻塞议题编号>/dependencies/blocked_by -F issue_id=<阻塞议题ID>`。原生依赖功能不可用时，在被阻塞议题正文开头写 `Blocked by: #<编号>, #<编号>`。
- 查询阻塞议题时，对同一个 `dependencies/blocked_by` 端点使用 `gh api --paginate`，检查返回结果的 `state`。全部阻塞议题关闭后，才能开始被阻塞的议题。

## Pull requests as a triage surface

**PRs as a request surface: no.**

设为 `yes` 时，按同一套标签处理外部 PR。用 `gh pr view <编号> --json number,title,body,labels,comments` 读取内容，用 `gh pr diff <编号>` 查看改动，用 `gh pr comment`、`gh pr edit` 和 `gh pr close` 更新。列出外部 PR 时，排除 `author_association` 为 `OWNER`、`MEMBER` 或 `COLLABORATOR` 的记录。

## Wayfinder

- 用带有 `wayfinder:map` 标签的议题记录探索进展。为每个问题创建一个子议题，用 `wayfinder:<type>` 记录类型。
- 按探索顺序查找开放、无阻塞且未分配的子议题。开工前用 `gh issue edit <编号> --add-assignee @me` 领取。
- 原生子议题功能不可用时，将子议题链接加入父议题的任务列表，并在子议题正文用 `Part of #<父议题编号>` 指向父议题。
