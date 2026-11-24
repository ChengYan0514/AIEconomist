import { ArrowLeft, ChevronDown, ChevronRight, Download, Radio, RotateCcw, Square } from "lucide-react"
import { Fragment, useEffect, useState } from "react"
import { api, dateLabel, errorMessage, paperPath, useQuery } from "../api"
import { Empty, Loading, Notice, Pagination } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { BatchStatusChip } from "./extraction"
import { paperClaimsPath, type CausalArtifact, type CausalBatchDetail, type CausalRunView } from "./types"

const RUN_STATUS_FILTER = [
  ["", "全部"],
  ["RUNNING", "运行中"],
  ["PUBLISHING", "发布中"],
  ["QUEUED", "排队中"],
  ["SUCCEEDED", "已完成"],
  ["FAILED", "失败"],
  ["SKIPPED", "已跳过"],
  ["CANCELLED", "已取消"],
] as const

const STAGE_LABEL: Record<CausalRunView["stage"], string> = {
  PREPARE: "块清洗准备",
  STAGE0: "Stage 0 章节筛选",
  STAGE1: "Stage 1 主张抽取",
  NORMALIZE: "字段规范化",
  MAP: "STW 向量映射",
  PUBLISH: "受控发布",
}

function RunStatusChip({ run }: { run: CausalRunView }) {
  if (run.status === "SUCCEEDED" && run.metrics.claimCount === 0) {
    return <span className="status-chip status-amber"><span className="status-dot" aria-hidden="true" />已完成（未抽到关系）</span>
  }
  const map: Record<CausalRunView["status"], [string, string]> = {
    QUEUED: ["排队中", "neutral"],
    RUNNING: ["运行中", "blue"],
    PUBLISHING: ["发布中", "blue"],
    SUCCEEDED: ["已完成", "green"],
    SKIPPED: ["已跳过", "neutral"],
    FAILED: ["失败", "red"],
    CANCELLED: ["已取消", "neutral"],
  }
  const [label, tone] = map[run.status]
  return <span className={"status-chip status-" + tone}><span className="status-dot" aria-hidden="true" />{label}</span>
}

export function CausalExtractionDetailPage({ batchId }: { batchId: string }) {
  const { can } = usePermissions()
  const canExtract = can("CAUSAL_EXTRACT")
  const [statusFilter, setStatusFilter] = useState("")
  const [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [liveConnected, setLiveConnected] = useState(false)
  const [expandedRun, setExpandedRun] = useState<string | null>(null)
  const size = 20

  const query = `/causal/extractions/${encodeURIComponent(batchId)}?page=${page}&size=${size}`
    + (statusFilter ? `&status=${statusFilter}` : "")
  const res = useQuery<CausalBatchDetail>(query)
  const reload = res.reload
  const batch = res.data?.batch

  useEffect(() => {
    const source = new EventSource(`/api/causal/extractions/${encodeURIComponent(batchId)}/events`, { withCredentials: true })
    source.addEventListener("connected", () => setLiveConnected(true))
    source.addEventListener("run", () => reload())
    source.onerror = () => setLiveConnected(false)
    return () => { source.close(); setLiveConnected(false) }
  }, [batchId, reload])

  async function act(action: "cancel" | "retry") {
    setBusy(true); setError("")
    try {
      await api(`/causal/extractions/${encodeURIComponent(batchId)}/${action}`, { method: "POST" })
      reload()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return <>
    <SectionHeader
      title="因果抽取任务"
      description={batch ? `任务 ${batch.batchId} · 创建于 ${dateLabel(batch.createdAt)}` : batchId}
      back={<a href="/causal-extraction" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回任务列表</a>}
      actions={<>
        <span className={"causal-sse-pill" + (liveConnected ? " is-live" : "")}>
          <Radio size={13} aria-hidden="true" />{liveConnected ? "实时推送已连接" : "静态视图"}
        </span>
        {canExtract && batch?.canCancel && <Button type="button" variant="outline" disabled={busy} onClick={() => act("cancel")}>
          <Square size={14} aria-hidden="true" />取消任务
        </Button>}
        {canExtract && batch?.canRetry && <Button type="button" disabled={busy} onClick={() => act("retry")}>
          <RotateCcw size={14} aria-hidden="true" />重试失败项
        </Button>}
      </>}
    />
    {error && <div className="mb-4"><Notice error>{error}</Notice></div>}
    {res.error ? <Notice error>{res.error.message}</Notice> : !res.data || !batch ? <Loading label="正在加载任务详情…" /> : <>
      <section className="panel causal-kpi-strip causal-kpi-six mb-4">
        <div className="causal-kpi-cell"><span>状态</span><div><BatchStatusChip status={batch.status} zeroClaimPapers={batch.zeroClaimPapers} total={batch.total} /></div></div>
        <div className="causal-kpi-cell"><span>入库模式</span><strong>{batch.ingestionMode === "RAW" ? "原始变量（默认）" : "标准变量"}</strong></div>
        <div className="causal-kpi-cell"><span>完成 / 总计</span><strong>{batch.succeeded} / {batch.total}</strong></div>
        <div className="causal-kpi-cell"><span>因果关系</span><strong>{batch.totalClaims.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>失败 / 跳过</span><strong>{batch.failed} / {batch.skipped}</strong></div>
        <div className="causal-kpi-cell"><span>模型调用</span><strong>{batch.callsUsed.toLocaleString()}</strong></div>
      </section>

      {batch.zeroClaimPapers > 0 && <div className="mb-4"><Notice>
        {batch.zeroClaimPapers} 篇论文已完成处理，但模型未抽到因果关系。完成状态表示流程正常结束，不保证有关系产出。可展开论文行下载 <code>stage1_raw_responses.jsonl</code>，查看模型原始响应。
      </Notice></div>}

      <section className="panel">
        <div className="job-quick-pills" role="group" aria-label="按单篇运行状态筛选">
          {RUN_STATUS_FILTER.map(([value, label]) => (
            <button key={value || "ALL"} type="button" aria-pressed={statusFilter === value}
              className={"causal-chip-btn" + (statusFilter === value ? " is-selected" : "")}
              onClick={() => { setStatusFilter(value); setPage(0) }}>
              {label}
            </button>
          ))}
        </div>
        {res.data.runs.length === 0 ? <Empty title="当前筛选下没有单篇运行记录" /> : <>
          <div className="table-scroll" tabIndex={0} role="region" aria-label="单篇抽取记录表">
            <table className="data-table">
              <thead><tr>
                <th scope="col" className="w-9"><span className="sr-only">展开</span></th>
                <th scope="col">论文</th>
                <th scope="col">Work</th>
                <th scope="col">状态</th>
                <th scope="col">阶段</th>
                <th scope="col">因果关系 / 键冲突覆盖 / 调用</th>
                <th scope="col">原因</th>
              </tr></thead>
              <tbody>{res.data.runs.map((run) => {
                const open = expandedRun === run.runId
                return <Fragment key={run.runId}>
                  <tr>
                    <td className="w-9">
                      <button type="button" className="causal-expand-btn" aria-expanded={open}
                        aria-label="展开产物与错误详情" onClick={() => setExpandedRun(open ? null : run.runId)}>
                        {open ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                      </button>
                    </td>
                    <td className="title-cell">
                      <a href={paperPath(run.paperId)}>{run.paperTitle || run.paperId}</a>
                      <small className="mono">{run.paperId}</small>
                    </td>
                    <td className="mono">
                      {run.workId ? <a className="inline-link" href={paperClaimsPath(run.workId)}>{run.workId}</a> : "—"}
                    </td>
                    <td><RunStatusChip run={run} /></td>
                    <td><span className="role-pill">{STAGE_LABEL[run.stage] ?? run.stage}</span></td>
                    <td className="tabular-nums text-sm">
                      <strong>{run.metrics.claimCount}</strong> / {run.metrics.collisionCount} / {run.metrics.callsUsed}
                    </td>
                    <td className="text-sm text-danger">{run.errorCode || "—"}</td>
                  </tr>
                  {open && <tr className="causal-drawer-row">
                    <td colSpan={7} className="p-0"><RunArtifactDrawer run={run} canDownload={canExtract} /></td>
                  </tr>}
                </Fragment>
              })}</tbody>
            </table>
          </div>
          <Pagination page={page} size={size} total={res.data.totalRuns} change={setPage} />
        </>}
      </section>
    </>}
  </>
}

function RunArtifactDrawer({ run, canDownload }: { run: CausalRunView; canDownload: boolean }) {
  const artifacts = useQuery<CausalArtifact[]>(canDownload && run.hasArtifacts ? `/causal/runs/${encodeURIComponent(run.runId)}/artifacts` : null)
  return <div className="causal-evidence-drawer">
    {run.errorMessage && <p className="text-danger m-0 mb-2"><strong>说明：</strong>{run.errorMessage}</p>}
    <div className="causal-artifact-bar">
      <strong>阶段产物：</strong>
      {!canDownload ? <span className="muted">需要「执行因果抽取」权限方可下载产物</span> :
        !run.hasArtifacts ? <span className="muted">尚未产生引擎产物</span> :
          artifacts.error ? <span className="text-danger">{artifacts.error.message}</span> :
            !artifacts.data ? <span className="muted">正在读取产物清单…</span> :
              artifacts.data.length === 0 ? <span className="muted">暂无可下载产物（可能已按保留期清理）</span> :
                <div className="causal-artifact-links">
                  {artifacts.data.map((a) => (
                    <a key={a.name} className="causal-artifact-pill" download
                      href={`/api/causal/runs/${encodeURIComponent(run.runId)}/artifacts/${encodeURIComponent(a.name)}`}>
                      <Download size={13} aria-hidden="true" />
                      <span className="mono">{a.name}</span>
                      <small className="muted">({formatBytes(a.sizeBytes)})</small>
                    </a>
                  ))}
                </div>}
    </div>
  </div>
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
