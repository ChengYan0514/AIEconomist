import { ArrowRight, ArrowUpRight, BookMarked, ClipboardList } from "lucide-react"
import { dateLabel, jobPath, params, useQuery } from "./api"
import { Chip, Empty, JobChip, JobProgress, Loading, Notice, jobKinds, paperLabels } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { PipelineBar } from "./journals"
import { PaperCsvExport } from "./papers/export"
import { usePermissions } from "./permissions"
import type { Job, JournalDirectory, Page, Paper, PaperStatus } from "./types"

const statuses: PaperStatus[] = ["UNPROCESSED", "READY", "FAILED", "PUBLISHED"]

export function Overview() {
  const { can } = usePermissions()
  const canReadPapers = can("PAPER_READ")
  const canReadJobs = can("JOB_READ")
  const canExport = can("PAPER_CSV_EXPORT")
  return <>
    <SectionHeader title="总览" description="掌握未归档经济学文献的解析发布进度、重点期刊覆盖与近期后台处理记录。"
      actions={<>
        {canReadPapers && <Button asChild variant="outline"><a href="/journals"><BookMarked size={15} aria-hidden="true" />期刊目录</a></Button>}
        {canExport && <PaperCsvExport />}
      </>} />
    {canReadPapers && <section className="overview-section" aria-labelledby="overview-paper-status">
      <div className="overview-heading"><h2 id="overview-paper-status">全文处理状态</h2><span>未归档论文 · 点击进入对应筛选</span></div>
      <div className="panel overview-ledger">
        {statuses.map((status) => <PaperCount key={status} status={status} />)}
      </div>
    </section>}
    {(canReadJobs || canReadPapers) && <div className={"overview-bento" + (canReadJobs && canReadPapers ? " is-split" : "")}>
      {canReadJobs && <RecentJobs />}
      {canReadPapers && <TopJournals />}
    </div>}
    {!canReadPapers && !canReadJobs && !canExport && <div className="panel">
      <Empty title="暂无可查看的数据">{can("SYSTEM_MANAGE") && "可从侧栏打开系统管理页面。"}</Empty>
    </div>}
  </>
}

function RecentJobs() {
  const result = useQuery<Page<Job>>("/jobs?size=5")
  const jobs = result.data?.items ?? []
  return <section className="overview-bento-main" aria-labelledby="overview-jobs">
    <div className="panel">
      <div className="panel-bar">
        <div className="panel-title">
          <ClipboardList size={17} strokeWidth={1.7} aria-hidden="true" />
          <h2 id="overview-jobs" className="panel-heading">近期任务</h2>
        </div>
        <a href="/jobs" className="inline-link text-sm">查看全部任务<ArrowRight size={14} aria-hidden="true" /></a>
      </div>
      {result.error ? <div className="p-5">
        <Notice error>{result.error.message}</Notice>
        <Button className="mt-3" variant="outline" onClick={result.reload}>重新加载任务</Button>
      </div> : result.loading ? <Loading label="正在加载近期任务…" /> :
        jobs.length === 0 ? <Empty title="暂无任务" /> :
          <div className="table-scroll" tabIndex={0} role="region" aria-label="近期任务表格">
            <table className="data-table overview-job-table">
              <thead><tr><th scope="col">任务类型</th><th scope="col">范围</th><th scope="col">任务状态</th>
                <th scope="col">进度</th><th scope="col">创建时间</th><th scope="col">操作</th></tr></thead>
              <tbody>{jobs.map((job) => <tr key={job.jobId}>
                <td className="font-medium">{jobKinds[job.kind]}</td>
                <td className="overview-job-scope"><span className={job.scope?.sourceId && !job.scope?.filename ? "mono" : undefined}>{jobScope(job)}</span></td>
                <td><JobChip status={job.state} /></td>
                <td><JobProgress job={job} /></td>
                <td className="table-date">{dateLabel(job.createdAt)}</td>
                <td><Button asChild variant="ghost" size="sm"><a href={jobPath(job.jobId)}>查看详情</a></Button></td>
              </tr>)}</tbody>
            </table>
          </div>}
    </div>
  </section>
}

function TopJournals() {
  const result = useQuery<JournalDirectory>("/journals?size=5&sort=PAPERS_DESC")
  const items = result.data?.items ?? []
  const totalJournals = result.data?.summary.totalJournals ?? 0
  return <section className="overview-bento-side" aria-labelledby="overview-journals">
    <div className="panel">
      <div className="panel-bar">
        <div className="panel-title">
          <BookMarked size={17} strokeWidth={1.7} aria-hidden="true" />
          <h2 id="overview-journals" className="panel-heading">重点期刊快照</h2>
        </div>
        <a href="/journals" className="inline-link text-sm">全部 {totalJournals > 0 ? totalJournals : ""} 期刊<ArrowRight size={14} aria-hidden="true" /></a>
      </div>
      {result.error ? <div className="p-5">
        <Notice error>{result.error.message}</Notice>
        <Button className="mt-3" variant="outline" size="sm" onClick={result.reload}>重试</Button>
      </div> : result.loading ? <Loading label="正在汇总期刊快照…" /> :
        items.length === 0 ? <Empty title="暂无期刊数据">导入论文后将自动按出版来源聚合展示。</Empty> :
          <ul className="journal-snapshot-list">
            {items.map((journal) => {
              const pubPct = journal.totalPapers > 0 ? Math.round((journal.publishedCount / journal.totalPapers) * 100) : 0
              return <li key={journal.sourceId}>
                <a className="journal-snapshot-item" href={"/journals" + params({ journal: journal.sourceId })}>
                  <div className="journal-snapshot-head">
                    <span className="journal-snapshot-name">{journal.name}</span>
                    <strong className="tabular-nums">{journal.totalPapers.toLocaleString()} 篇</strong>
                  </div>
                  <PipelineBar journal={journal} />
                  <div className="journal-snapshot-meta">
                    <span className="mono">{journal.sourceId}</span>
                    <span className="tabular-nums">
                      已发布 {pubPct}%{journal.failedCount > 0 && <> · <span className="text-danger">{journal.failedCount} 失败</span></>}
                    </span>
                  </div>
                </a>
              </li>
            })}
          </ul>}
    </div>
  </section>
}

function jobScope(job: Job) {
  const { filename, sourceId, yearFrom, yearTo, selectedCount } = job.scope ?? {}
  if (filename) return filename
  if (sourceId) {
    const years = yearFrom && yearTo ? yearFrom + "–" + yearTo : yearFrom ? yearFrom + " 年起" : yearTo ? "截至 " + yearTo + " 年" : ""
    return years ? sourceId + " · " + years : sourceId
  }
  return selectedCount === undefined ? "所选论文" : selectedCount + " 篇论文"
}

function PaperCount({ status }: { status: PaperStatus }) {
  const result = useQuery<Pick<Page<Paper>, "total">>("/papers" + params({ status, archived: false, size: 1 }))
  const count = result.data?.total
  const label = <Chip status={status} />
  if (!result.loading && !result.error && count !== undefined) {
    return <a className="overview-cell" data-status={status} href={"/papers" + params({ status })}>
      <div className="overview-cell-top">{label}<ArrowUpRight size={16} className="overview-arrow" aria-hidden="true" /></div>
      <strong className="overview-figure" data-alert={status === "FAILED" && count > 0 ? "true" : undefined}>{count.toLocaleString()}</strong>
    </a>
  }
  return <div className="overview-cell" data-status={status}>
    <div className="overview-cell-top">{label}</div>
    <strong className="overview-figure muted" aria-hidden="true">—</strong>
    {result.error ? <div className="overview-count-feedback">
      <span role="alert">计数加载失败</span>
      <Button variant="ghost" size="sm" onClick={result.reload} aria-label={"重试" + paperLabels[status][0] + "计数"}>重试</Button>
    </div> : <span className="overview-loading-note" role="status">正在加载…</span>}
  </div>
}
