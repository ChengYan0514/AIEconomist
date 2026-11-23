import { ArrowRight, ArrowUpRight } from "lucide-react"
import { dateLabel, jobPath, params, useQuery } from "./api"
import { Chip, Empty, JobChip, JobProgress, Loading, Notice, jobKinds, paperLabels } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { PaperCsvExport } from "./papers/export"
import { usePermissions } from "./permissions"
import type { Job, Page, Paper, PaperStatus } from "./types"

const statuses: PaperStatus[] = ["UNPROCESSED", "READY", "FAILED", "PUBLISHED"]

export function Overview() {
  const { can } = usePermissions()
  const canReadPapers = can("PAPER_READ")
  const canReadJobs = can("JOB_READ")
  const canExport = can("PAPER_CSV_EXPORT")
  return <>
    <SectionHeader title="总览" actions={canExport && <PaperCsvExport />} />
    {canReadPapers && <section className="overview-section" aria-labelledby="overview-paper-status">
      <div className="overview-heading"><h2 id="overview-paper-status">全文处理状态</h2><span>未归档论文</span></div>
      <div className="overview-counts">
        {statuses.map((status) => <PaperCount key={status} status={status} />)}
      </div>
    </section>}
    {canReadJobs && <RecentJobs />}
    {!canReadPapers && !canReadJobs && !canExport && <div className="panel">
      <Empty title="暂无可查看的数据">{can("SYSTEM_MANAGE") && "可从侧栏打开系统管理页面。"}</Empty>
    </div>}
  </>
}

function RecentJobs() {
  const result = useQuery<Page<Job>>("/jobs?size=5")
  const jobs = result.data?.items ?? []
  return <section className="overview-section" aria-labelledby="overview-jobs">
    <div className="overview-heading overview-jobs-heading">
      <h2 id="overview-jobs">近期任务</h2>
      <a href="/jobs" className="inline-link">查看全部任务<ArrowRight size={15} aria-hidden="true" /></a>
    </div>
    <div className="panel">
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
                <td className="overview-job-scope">{jobScope(job)}</td>
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
    return <a className="panel overview-count" href={"/papers" + params({ status })}>
      <div className="overview-count-label">{label}<ArrowUpRight size={18} aria-hidden="true" /></div>
      <strong>{count.toLocaleString()}</strong>
    </a>
  }
  return <div className="panel overview-count">
    {label}<strong aria-hidden="true">—</strong>
    {result.error ? <div className="overview-count-feedback">
      <span role="alert">计数加载失败</span>
      <Button variant="ghost" size="sm" onClick={result.reload} aria-label={"重试" + paperLabels[status][0] + "计数"}>重试</Button>
    </div> : <span className="muted" role="status">正在加载…</span>}
  </div>
}
