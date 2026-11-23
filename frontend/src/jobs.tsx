import { ArrowLeft, CalendarDays, ClipboardList, FileArchive, RotateCcw, Workflow } from "lucide-react"
import { useEffect, useState } from "react"
import { api, dateLabel, errorMessage, jobPath, paperPath, params, useQuery } from "./api"
import { Empty, JobChip, JobProgress, Loading, Notice, Pagination, jobKinds, jobLabels } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { CandidateReview } from "./openalex-review"
import { ArchiveUpload, ProcessPapers } from "./papers/forms"
import { usePermissions } from "./permissions"
import { navigate } from "./router"
import type { Job, JobItem, JobItemState, JobKind, JobState, OriginalPreflight, Page } from "./types"

const size = 20
const states: JobState[] = ["READY", "QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED"]
const itemStates: JobItemState[] = ["READY", "QUEUED", "RUNNING", "SUCCEEDED", "SKIPPED", "FAILED"]
const preflightLabels: Record<OriginalPreflight["status"], [string, string]> = {
  IMPORTABLE: ["可导入", "green"], DUPLICATE: ["重复", "amber"], ERROR: ["有问题", "red"],
}

export function Jobs() {
  const { canJob, canProcess } = usePermissions()
  const [kind, setKind] = useState("")
  const [state, setState] = useState("")
  const [page, setPage] = useState(0)
  const [dialog, setDialog] = useState<"zip" | "process" | null>(null)
  const result = useQuery<Page<Job>>("/jobs" + params({ kind, state, page, size }))
  const rows = result.data?.items ?? []
  useRefresh(rows.some((row) => active(row)), result.reload)
  return <>
    <SectionHeader title="导入与处理" description="查看批量导入和各处理阶段的进度，定位和重试个别失败项。" actions={<>
      {canProcess && <Button variant="outline" onClick={() => setDialog("process")}><Workflow size={16} aria-hidden="true" />提交论文处理</Button>}
      {canJob("ORIGINAL_IMPORT") && <Button onClick={() => setDialog("zip")}><FileArchive size={16} aria-hidden="true" />导入原件</Button>}
    </>} />
    <section className="panel">
      <div className="panel-bar">
        <div className="panel-title"><ClipboardList size={17} strokeWidth={1.7} aria-hidden="true" /><strong>任务记录</strong></div>
        <div className="job-filters">
          <select className="field" aria-label="任务类型" value={kind} onChange={(event) => { setKind(event.target.value); setPage(0) }}>
            <option value="">全部类型</option>{Object.entries(jobKinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          <select className="field" aria-label="任务状态" value={state} onChange={(event) => { setState(event.target.value); setPage(0) }}>
            <option value="">全部状态</option>{states.map((value) => <option key={value} value={value}>{jobLabels[value][0]}</option>)}
          </select>
          <Button variant="outline" size="icon" onClick={result.reload} aria-label="刷新任务"><RotateCcw size={15} /></Button>
        </div>
      </div>
      <div className="job-quick-pills" role="group" aria-label="快捷筛选任务状态">
        <button type="button" className="filter-pill" aria-pressed={state === ""} onClick={() => { setState(""); setPage(0) }}>全部</button>
        {(["READY", "RUNNING", "PARTIAL", "FAILED", "SUCCEEDED"] as const).map((value) => (
          <button key={value} type="button" className="filter-pill" aria-pressed={state === value}
            onClick={() => { setState(value); setPage(0) }}>{jobLabels[value][0]}</button>
        ))}
      </div>
      {result.error ? <div className="p-5"><Notice error>{result.error.message}</Notice></div> : result.loading && !rows.length ? <Loading /> :
        rows.length === 0 ? <Empty title="没有符合条件的任务">上传 ZIP 或提交论文处理后，任务会出现在这里。</Empty> :
          <div className="table-scroll"><table className="data-table job-table"><thead><tr><th>任务</th><th>状态</th><th>进度</th><th>发起人</th><th>提交时间</th><th>操作</th></tr></thead>
            <tbody>{rows.map((job) => <tr key={job.jobId}>
              <td className="title-cell"><a href={jobPath(job.jobId)}>{jobKinds[job.kind]}</a><small className={job.scope?.sourceId && !job.scope?.filename ? "mono" : undefined}>{job.scope?.filename || job.scope?.sourceId || "所选论文"}</small></td>
              <td><JobChip status={job.state} /></td>
              <td><JobProgress job={job} /></td><td className="mono muted">{job.username}</td><td className="table-date">{dateLabel(job.createdAt)}</td>
              <td><Button asChild variant="ghost" size="sm"><a href={jobPath(job.jobId)}>查看任务</a></Button></td>
            </tr>)}</tbody></table></div>}
      {result.data && <Pagination total={result.data.total} page={page} size={size} change={setPage} />}
    </section>
    {canJob("ORIGINAL_IMPORT") && dialog === "zip" && <ArchiveUpload close={() => setDialog(null)} />}
    {canProcess && dialog === "process" && <ProcessPapers ids={[]} close={() => setDialog(null)} />}
  </>
}

export function JobDetail({ id }: { id: string }) {
  const { can, canJob } = usePermissions()
  const path = jobPath(id)
  const [state, setState] = useState("")
  const [page, setPage] = useState(0)
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  const overview = useQuery<Job>(path)
  const itemsPath = path + "/items" + params({ state, page, size })
  const details = useQuery<Page<JobItem>>(itemsPath)
  const itemPage = details.dataPath === itemsPath ? details.data : undefined
  const job = overview.data
  const pendingItems = itemPage?.items.some((item) => item.state === "QUEUED" || item.state === "RUNNING")
  useRefresh(Boolean((job && active(job)) || pendingItems), overview.reload, details.reload)
  if (!job) return overview.error ? <Notice error>{overview.error.message}</Notice> : <Loading />
  const zipImport = job.kind === "ORIGINAL_IMPORT"
  const reviewing = zipImport && !job.confirmedAt

  async function action(type: "confirm" | "retry") {
    setBusy(true); setError("")
    try {
      const response = await api<Job>(type === "confirm" ? "/imports/" + id + "/confirm" : path + "/retry", { method: "POST" })
      if (type === "retry") navigate(jobPath(response.jobId))
      else {
        setPage(0)
        setState("")
        overview.reload()
        details.reload()
      }
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }

  return <>
    <SectionHeader title={jobKinds[job.kind]} back={<div className="flex flex-wrap items-center gap-x-4">
      <a href="/jobs" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回导入与处理</a>
      {job.retryOf && <a href={jobPath(job.retryOf)} className="back-link">查看原任务</a>}
    </div>}
      description={job.scope?.filename || job.scope?.sourceId || "所选论文"} actions={<>
        {reviewing && job.ready > 0 && canJob("ORIGINAL_IMPORT") && <Button disabled={busy} onClick={() => action("confirm")}>确认导入 {job.ready} 条</Button>}
        {canJob(job.kind) && job.canRetry &&
          <Button disabled={busy} variant="outline" onClick={() => action("retry")}><RotateCcw size={15} aria-hidden="true" />重试失败项</Button>}
      </>} />
    {error && <Notice error>{error}</Notice>}
    <div className="panel job-overview">
      <div className="job-overview-cell"><span>当前状态</span><JobChip status={job.state} /></div>
      <div className="job-overview-cell"><span>处理进度</span><JobProgress job={job} /></div>
      {reviewing ? <div className="job-overview-cell"><span>预检结果</span><strong className="tabular-nums">{job.ready} 条可导入 · {job.skipped} 条重复 · {job.failed} 条有问题</strong></div>
        : <div className="job-overview-cell"><span>结果</span><strong className="tabular-nums">{job.succeeded} 成功 · {job.skipped} 跳过 · {job.failed} 失败</strong></div>}
      <div className="job-overview-cell"><span>提交时间</span><strong className="inline-flex items-center gap-1.5 tabular-nums"><CalendarDays size={14} className="muted" aria-hidden="true" />{dateLabel(job.createdAt)}</strong></div>
    </div>
    {reviewing && job.ready > 0 && <div className="mt-3"><Notice>确认后仅处理通过预检的记录。</Notice></div>}
    <section className="panel mt-4">
      <div className="panel-bar">
        <div className="panel-title"><ClipboardList size={17} strokeWidth={1.7} aria-hidden="true" /><strong>逐项结果</strong></div>
        <div className="job-filters">
          <select className="field" aria-label="任务项状态" value={state} onChange={(event) => { setState(event.target.value); setPage(0) }}>
            <option value="">全部任务项</option>{itemStates.map((value) => <option key={value} value={value}>{jobLabels[value][0]}</option>)}
          </select>
        </div>
      </div>
      {details.error ? <div className="p-5"><Notice error>{details.error.message}</Notice></div> : !itemPage ? <Loading /> :
        itemPage.items.length === 0 ? <Empty title="没有符合条件的任务项" /> :
        <div className="table-scroll" tabIndex={0} role="region" aria-label={zipImport ? "ZIP 逐项结果" : "任务逐项结果"}>
          <table className={zipImport ? "data-table zip-result-table" : "data-table"}><thead><tr>
            {zipImport && <th scope="col">CSV 数据记录位序</th>}
            <th scope="col">对象</th>
            {zipImport && <><th scope="col">包内原件</th><th scope="col">预检结论</th></>}
            {!reviewing && <><th scope="col">状态</th><th scope="col">结果</th></>}
          </tr></thead>
          <tbody>{itemPage.items.map((item) => {
            const candidates = job.kind === "MATCH" ? item.result?.candidates : undefined
            const importedPaper = item.result?.paperId ?? (item.state === "SUCCEEDED" ? item.paperId : null)
            const linkedPaper = can("PAPER_READ") && (zipImport ? item.preflight?.existingPaperId ?? importedPaper : item.paperId)
            return <tr key={item.sequence}>
              {zipImport && <td className="mono muted">{item.sequence}</td>}
              <td className="title-cell">{linkedPaper ? <a href={paperPath(linkedPaper)}>{item.target}</a> : item.target}</td>
              {zipImport && <>
                <td className="zip-entry mono">{item.preflight?.originalPath ?? "未记录"}</td>
                <td className="result-cell"><PreflightResult item={item} /></td>
              </>}
              {!reviewing && <>
                <td><div className="flex flex-wrap gap-1.5">
                  <JobChip status={item.state} />
                  {candidates && candidates.length > 1 &&
                    <span className="status-chip status-blue"><span className="status-dot" aria-hidden="true" />待确认匹配</span>}
                </div></td>
                <td className="result-cell"><span>{(item.message !== item.preflight?.reason && item.message) || "—"}</span>
                  {canJob("PUBLISH") && job.kind === "PARSE" && item.state === "SUCCEEDED" && item.paperId && <PublishParsed paperId={item.paperId} />}
                  {candidates && candidates.length > 1 && item.paperId && canJob("MATCH") && can("PAPER_READ") &&
                    <CandidateReview paperId={item.paperId} candidates={candidates}
                      source={item.result?.candidateSource} scopeSourceId={job.scope?.sourceId} />}
                </td>
              </>}
            </tr>
          })}</tbody>
        </table></div>}
      {itemPage && <Pagination total={itemPage.total} page={page} size={size} change={setPage} />}
    </section>
  </>
}

function PreflightResult({ item }: { item: JobItem }) {
  if (!item.preflight) return <>—</>
  const [label, color] = preflightLabels[item.preflight.status]
  return <div className="preflight-cell">
    <span className={"status-chip status-" + color}><span className="status-dot" aria-hidden="true" />{label}</span>
    {item.preflight.reason && <div className="mt-1">{item.preflight.reason}</div>}
    {item.inputError && <small className="block mt-1 muted">修正输入后重新上传 ZIP</small>}
  </div>
}

function PublishParsed({ paperId }: { paperId: string }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function submit() {
    setBusy(true)
    setError("")
    try {
      const job = await api<Job>("/jobs", { method: "POST", body: JSON.stringify({ kind: "PUBLISH", paperIds: [paperId] }) })
      navigate(jobPath(job.jobId))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <div className="mt-2 grid gap-2">
    <Button className="justify-self-start" type="button" size="sm" variant="outline" disabled={busy} onClick={submit}>导入全文</Button>
    {error && <Notice error>{error}</Notice>}
  </div>
}

function active(job: Job) {
  return job.queued + job.running > 0
}

function useRefresh(running: boolean, first: () => void, second?: () => void) {
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => { first(); second?.() }, 2500)
    return () => window.clearInterval(timer)
  }, [running, first, second])
}
