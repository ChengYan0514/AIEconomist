import { X } from "lucide-react"
import { useEffect, useRef, useState, type FormEvent } from "react"
import { dateLabel, jobPath, paperPath, rolePath, useQuery } from "./api"
import { Chip, Empty, jobKinds, jobLabels, Loading, Notice, Pagination } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { roles, usePermissions } from "./permissions"
import type { JobItemState, JobKind, Page, PaperStatus } from "./types"

type Outcome = "SUCCEEDED" | "FAILED" | "DENIED"
type AuditEvent = {
  eventId: number
  occurredAt: string
  actor: string
  action: string
  objectType: string
  objectId: string
  outcome: Outcome
}
type AuditDetail = AuditEvent & { summary: Record<string, string | number | boolean | string[]> }

const actions: Record<string, string> = {
  PAPER_CSV_EXPORT: "论文 CSV 导出", USER_CREATE: "创建用户", USER_UPDATE: "编辑用户",
  USER_DISPLAY_NAME_CHANGE: "修改显示名称", USER_ROLE_CHANGE: "变更用户角色",
  USER_DISABLE: "停用用户", USER_ENABLE: "恢复用户", USER_PASSWORD_RESET: "重置用户密码", USER_STATUS_CHANGE: "修改用户状态",
  ROLE_CREATE: "创建角色", ROLE_UPDATE: "修改角色", ROLE_DELETE: "删除角色",
  API_ACCESS: "访问 API", JOB_PREFLIGHT: "ZIP 预检", JOB_SUBMIT: "提交任务", JOB_RETRY: "重试任务",
  JOB_ITEM_CHECK: "检查任务项", JOB_EXECUTE: "执行任务项",
  PAPER_IMPORT: "导入论文", PAPER_EDIT: "编辑论文", PAPER_REPLACE: "更换原件",
  PAPER_ARCHIVE: "归档论文", PAPER_RESTORE: "恢复论文", PAPER_PUBLISH: "发布全文", PAPER_MATCH_CONFIRM: "确认匹配",
}
const objects: Record<string, string> = {
  PAPER: "论文", PAPER_COLLECTION: "论文库", USER: "用户", USER_COLLECTION: "用户列表",
  ROLE: "角色", PERMISSION: "权限", JOB: "任务", JOB_COLLECTION: "任务列表",
}
const outcomes: Record<Outcome, [string, string]> = {
  SUCCEEDED: ["成功", "green"], FAILED: ["失败", "red"], DENIED: ["拒绝", "amber"],
}
const summaryLabels: Record<string, string> = {
  displayName: "显示名称", previousDisplayName: "原显示名称", role: "角色", previousRole: "原角色", permission: "所需权限",
  previousAccountStatus: "原账号状态", accountStatus: "账号状态", mustChangePassword: "需设置个人密码",
  roleName: "角色名称", previousRoleName: "原角色名称", permissions: "业务权限", previousPermissions: "原业务权限",
  assignedUsers: "受影响人数", descriptionChanged: "说明已修改",
  exportedRows: "导出行数", searchApplied: "应用了搜索词", archived: "归档视图",
  sourceId: "来源 ID", yearFrom: "起始年份", yearTo: "结束年份", status: "处理状态", code: "结果代码",
  version: "论文版本", previousRevision: "原件原修订号", revision: "原件修订号", changedFields: "变更字段", workId: "作品 ID",
  jobKind: "任务类型", totalItems: "总项数", readyItems: "待确认项数", queuedItems: "排队项数",
  skippedItems: "跳过项数", failedItems: "失败项数", itemSequence: "任务项序号", itemState: "任务项状态",
  jobId: "任务 ID", paperId: "论文 ID", retryOf: "原任务 ID",
}
const filterNames = ["from", "to", "actor", "action", "objectType", "objectId", "outcome"] as const

export function AuditLog() {
  const [search, setSearch] = useState(window.location.search)
  const query = new URLSearchParams(search)
  const selected = query.get("event")
  query.delete("event")
  const page = Number(query.get("page") || 0)
  const filterQuery = new URLSearchParams(query)
  filterQuery.delete("page")
  const opener = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    function restore() { setSearch(window.location.search) }
    window.addEventListener("popstate", restore)
    return () => window.removeEventListener("popstate", restore)
  }, [])

  function navigate(next: URLSearchParams) {
    const suffix = next.size ? "?" + next.toString() : ""
    if (suffix === window.location.search) return
    window.history.pushState(null, "", window.location.pathname + suffix)
    setSearch(suffix)
  }

  return <>
    <SectionHeader title="审计日志" />
    <AuditFilters key={filterQuery.toString()} filters={filterQuery} apply={navigate} />
    <div className="audit-layout" data-event-open={Boolean(selected)}>
      <AuditResults key={query.toString()} query={query} page={page} selected={selected}
        open={(eventId, button) => {
          opener.current = button
          const next = new URLSearchParams(query)
          next.set("event", String(eventId))
          navigate(next)
        }}
        turn={(page) => {
          const next = new URLSearchParams(query)
          next.set("page", String(page))
          navigate(next)
        }} />
      {selected && <EventDetail key={selected} id={selected}
        close={() => {
          navigate(query)
          opener.current?.focus()
        }} />}
    </div>
  </>
}

function AuditFilters({ filters, apply }: { filters: URLSearchParams; apply: (query: URLSearchParams) => void }) {
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    const query = new URLSearchParams()
    for (const field of filterNames) {
      const value = String(fields.get(field) ?? "").trim()
      if (value) query.set(field, field === "from" || field === "to" ? new Date(value).toISOString() : value)
    }
    apply(query)
  }

  return <form className="audit-filters" onSubmit={submit}>
    <label>开始时间<input className="field" name="from" type="datetime-local" step="1" defaultValue={localTime(filters.get("from"))} /></label>
    <label>结束时间<input className="field" name="to" type="datetime-local" step="1" defaultValue={localTime(filters.get("to"))} /></label>
    <label>操作者<input className="field" name="actor" maxLength={50} placeholder="用户名" defaultValue={filters.get("actor") ?? ""} /></label>
    <label>动作<select className="field" name="action" aria-label="动作" defaultValue={filters.get("action") ?? ""}>
      <option value="">全部动作</option>
      {Object.entries(actions).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
    </select></label>
    <label>对象类型<select className="field" name="objectType" aria-label="对象类型" defaultValue={filters.get("objectType") ?? ""}>
      <option value="">全部对象</option>
      {Object.entries(objects).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
    </select></label>
    <label>对象 ID<input className="field" name="objectId" maxLength={255} defaultValue={filters.get("objectId") ?? ""} /></label>
    <label>结果<select className="field" name="outcome" aria-label="结果" defaultValue={filters.get("outcome") ?? ""}>
      <option value="">全部结果</option>
      {Object.entries(outcomes).map(([code, [label]]) => <option key={code} value={code}>{label}</option>)}
    </select></label>
    <div className="audit-filter-actions">
      <Button type="submit" variant="outline">查询</Button>
      {filters.size > 0 && <Button variant="ghost" type="button" onClick={() => apply(new URLSearchParams())}>重置</Button>}
    </div>
  </form>
}

function localTime(value: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19)
}

function AuditResults({ query, page, selected, open, turn }: {
  query: URLSearchParams; page: number; selected: string | null
  open: (id: number, button: HTMLButtonElement) => void; turn: (page: number) => void
}) {
  const result = useQuery<Page<AuditEvent>>("/audit-events" + (query.size ? "?" + query.toString() : ""))
  const items = result.data?.items ?? []
  return <div className="panel">
    {result.loading ? <Loading label="正在读取审计日志…" /> :
      result.error ? <Notice error>{result.error.message}</Notice> :
        items.length === 0 ? <Empty title="没有找到审计事件" /> :
          <div className="table-scroll" role="region" aria-label="审计事件表格" tabIndex={0}>
            <table className="data-table audit-table">
              <thead><tr><th scope="col">时间</th><th scope="col">操作者</th><th scope="col">动作</th><th scope="col">对象</th><th scope="col">结果</th></tr></thead>
              <tbody>{items.map((event) => <tr key={event.eventId} className={String(event.eventId) === selected ? "row-selected" : ""}>
                <td className="table-date"><time dateTime={event.occurredAt} title={event.occurredAt}>{dateLabel(event.occurredAt)}</time></td>
                <td>{event.actor}</td>
                <td><button type="button" className="inline-link audit-event-button" aria-expanded={String(event.eventId) === selected}
                  onClick={(click) => open(event.eventId, click.currentTarget)}>{actions[event.action] ?? event.action}</button></td>
                <td>{event.objectType === "PAPER_COLLECTION" ? objects[event.objectType] : event.objectId}</td>
                <td><OutcomeChip outcome={event.outcome} /></td>
              </tr>)}</tbody>
            </table>
          </div>}
    {result.data && <Pagination page={page} size={result.data.size} total={result.data.total} change={turn} />}
  </div>
}

function EventDetail({ id, close }: { id: string; close: () => void }) {
  const result = useQuery<AuditDetail>("/audit-events/" + encodeURIComponent(id))
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [])
  const event = result.data
  return <section className="panel audit-detail" aria-labelledby="audit-detail-title">
    <div className="audit-detail-heading">
      <h2 ref={heading} tabIndex={-1} id="audit-detail-title">事件详情</h2>
      <Button variant="ghost" size="icon" type="button" aria-label="关闭事件详情" onClick={close}><X size={19} /></Button>
    </div>
    {result.loading ? <Loading label="正在读取事件详情…" /> :
      result.error ? <Notice error>{result.error.message}</Notice> :
        event && <>
          <dl>
            <dt>时间</dt><dd><time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString("zh-CN", { hour12: false })}</time></dd>
            <dt>操作者</dt><dd>{event.actor}</dd>
            <dt>动作</dt><dd>{actions[event.action] ?? event.action}</dd>
            <dt>对象</dt><dd>{objects[event.objectType] ?? event.objectType} <span className="mono muted">{event.objectId}</span></dd>
            <dt>结果</dt><dd><OutcomeChip outcome={event.outcome} /></dd>
          </dl>
          <h3>变更摘要</h3>
          <dl>
            {Object.entries(event.summary).map(([key, value]) => <SummaryItem key={key} name={key} value={value} />)}
          </dl>
          <RelatedLinks event={event} />
        </>}
  </section>
}

function RelatedLinks({ event }: { event: AuditDetail }) {
  const { can } = usePermissions()
  const { objectType, objectId, action, summary } = event
  const paperId = objectType === "PAPER" ? objectId : summary.paperId
  const jobId = objectType === "JOB" ? objectId : summary.jobId
  const userPath = objectType === "USER" && can("SYSTEM_MANAGE") ? "/users/" + encodeURIComponent(objectId) : null
  const role = objectType === "ROLE" && action !== "ROLE_DELETE" && can("SYSTEM_MANAGE") ? rolePath(objectId) : null
  const paper = typeof paperId === "string" && can("PAPER_READ") ? paperPath(paperId) : null
  const job = typeof jobId === "string" && /^[0-9a-f-]{36}$/i.test(jobId) && can("JOB_READ") ? jobPath(jobId) : null

  return <>
    {userPath && <RelatedLink path={userPath} href={"/users?q=" + encodeURIComponent(objectId)} label="查看用户" />}
    {role && <RelatedLink path={role} label="查看角色" />}
    {paper && <RelatedLink path={paper} label="查看论文" />}
    {job && <RelatedLink path={job} label="查看任务" />}
    {objectType === "PAPER_COLLECTION" && objectId === "papers" && can("PAPER_READ") &&
      <div className="audit-detail-link"><a className="inline-link" href="/papers">查看论文库</a></div>}
  </>
}

function RelatedLink({ path, href = path, label }: { path: string; href?: string; label: string }) {
  const related = useQuery<unknown>(path)
  return related.dataPath === path && !related.error &&
    <div className="audit-detail-link"><a className="inline-link" href={href}>{label}</a></div>
}

function SummaryItem({ name, value }: { name: string; value: AuditDetail["summary"][string] }) {
  if (name === "accountStatus" || name === "previousAccountStatus") {
    return <><dt>{summaryLabels[name]}</dt><dd>{value === "ACTIVE" ? "启用" : "停用"}</dd></>
  }
  if (name === "jobKind") return <><dt>{summaryLabels[name]}</dt><dd>{jobKinds[value as JobKind] ?? value}</dd></>
  if (name === "itemState") return <><dt>{summaryLabels[name]}</dt><dd>{jobLabels[value as JobItemState]?.[0] ?? value}</dd></>
  const text = Array.isArray(value) ? value.join("、") || "—" : typeof value === "boolean" ? value ? "是" : "否" : value
  return <><dt>{summaryLabels[name] ?? name}</dt><dd>
    {name === "status" ? <Chip status={value as PaperStatus} /> : (name === "role" || name === "previousRole") ? roles[String(value)] ?? text : text}
  </dd></>
}

function OutcomeChip({ outcome }: { outcome: Outcome }) {
  const [label, color] = outcomes[outcome]
  return <span className={"status-chip status-" + color}><span className="status-dot" aria-hidden="true" />{label}</span>
}
