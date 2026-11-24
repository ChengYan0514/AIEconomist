import { Calculator, Network, Play, RefreshCw, Sparkles } from "lucide-react"
import { useEffect, useMemo, useState } from "react"
import { api, dateLabel, errorMessage, useQuery } from "../api"
import { Empty, Loading, Notice, Pagination, SourceField } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { navigate, useLocation } from "../router"
import type { Page } from "../types"
import {
  extractionBatchPath,
  type CausalBatchView, type CausalEstimate, type CausalIngestionMode, type CausalReviewPolicy,
  type CausalTargetMode, type CausalTargetSpec,
} from "./types"

const BATCH_STATUS: Record<CausalBatchView["status"], [string, string]> = {
  QUEUED: ["排队中", "neutral"],
  RUNNING: ["执行中", "blue"],
  SUCCEEDED: ["已完成", "green"],
  PARTIAL: ["部分成功", "amber"],
  FAILED: ["失败", "red"],
  CANCELLED: ["已取消", "neutral"],
}

const SKIP_LABELS: Record<string, string> = {
  ARCHIVED: "已归档",
  UNMATCHED: "未关联 OpenAlex",
  NOT_PUBLISHED: "未发布全文",
  ACTIVE_EXTRACTION: "已有活跃抽取",
}

export function BatchStatusChip({ status, zeroClaimPapers = 0, total = 0 }: {
  status: CausalBatchView["status"]
  zeroClaimPapers?: number
  total?: number
}) {
  if (status === "SUCCEEDED" && zeroClaimPapers > 0) {
    const label = total > 0 && zeroClaimPapers === total ? "已完成（未抽到关系）" : "已完成（部分论文未抽到关系）"
    return <span className="status-chip status-amber"><span className="status-dot" aria-hidden="true" />{label}</span>
  }
  const [label, tone] = BATCH_STATUS[status] ?? [status, "neutral"]
  return <span className={"status-chip status-" + tone}><span className="status-dot" aria-hidden="true" />{label}</span>
}

function parsePrefill(search: string) {
  const sp = new URLSearchParams(search)
  const rawMode = sp.get("mode")
  const mode: CausalTargetMode = rawMode === "WORK_IDS" || rawMode === "SOURCE_IDS" || rawMode === "FILTER" ? rawMode : "PAPER_IDS"
  const ids = (sp.get("ids") ?? "").split(",").map((s) => s.trim()).filter(Boolean)
  const sourceId = sp.get("sourceId") ?? ""
  return {
    mode: sourceId && !rawMode ? "SOURCE_IDS" as CausalTargetMode : mode,
    idsText: (sourceId && !ids.length && mode !== "FILTER" ? [sourceId] : ids).join("\n"),
    sourceId,
    q: sp.get("q") ?? "",
    yearFrom: sp.get("yearFrom") ?? "",
    yearTo: sp.get("yearTo") ?? "",
  }
}

export function CausalExtractionPage() {
  const loc = useLocation()
  const { can } = usePermissions()
  const canExtract = can("CAUSAL_EXTRACT")
  const prefill = useMemo(() => parsePrefill(loc.search), [loc.search])

  const [mode, setMode] = useState<CausalTargetMode>(prefill.mode)
  const [idsText, setIdsText] = useState(prefill.idsText)
  const [q, setQ] = useState(prefill.q)
  const [sourceId, setSourceId] = useState(prefill.sourceId)
  const [yearFrom, setYearFrom] = useState(prefill.yearFrom)
  const [yearTo, setYearTo] = useState(prefill.yearTo)
  const [ingestionMode, setIngestionMode] = useState<CausalIngestionMode>("RAW")
  const [reviewPolicy, setReviewPolicy] = useState<CausalReviewPolicy>("MANUAL_REVIEW")
  const [estimate, setEstimate] = useState<CausalEstimate>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [page, setPage] = useState(0)
  const size = 20

  const batches = useQuery<Page<CausalBatchView>>(`/causal/extractions?page=${page}&size=${size}`)

  useEffect(() => {
    setMode(prefill.mode)
    setIdsText(prefill.idsText)
    setSourceId(prefill.sourceId)
    setQ(prefill.q)
    setYearFrom(prefill.yearFrom)
    setYearTo(prefill.yearTo)
    setEstimate(undefined)
  }, [prefill])

  const target = useMemo<CausalTargetSpec>(() => {
    const ids = idsText.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean)
    if (mode === "FILTER") {
      return {
        mode,
        filters: {
          q: q.trim() || undefined,
          sourceId: sourceId.trim() || undefined,
          yearFrom: yearFrom ? Number(yearFrom) : undefined,
          yearTo: yearTo ? Number(yearTo) : undefined,
        },
      }
    }
    if (mode === "SOURCE_IDS") return { mode, sourceIds: sourceId.trim() ? [sourceId.trim(), ...ids.filter((x) => x !== sourceId.trim())] : ids }
    return mode === "PAPER_IDS" ? { mode, paperIds: ids } : { mode, workIds: ids }
  }, [mode, idsText, q, sourceId, yearFrom, yearTo])

  useEffect(() => { setEstimate(undefined) }, [target, ingestionMode])

  async function runEstimate() {
    setBusy(true); setError("")
    try {
      setEstimate(await api<CausalEstimate>("/causal/extractions/estimate", {
        method: "POST", body: JSON.stringify({ target, ingestionMode }),
      }))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function createBatch() {
    setBusy(true); setError("")
    try {
      const created = await api<CausalBatchView>("/causal/extractions", {
        method: "POST", body: JSON.stringify({ target, ingestionMode, reviewPolicy }),
      })
      navigate(extractionBatchPath(created.batchId))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return <>
    <SectionHeader
      title="因果抽取"
      description="选择已匹配 Work 且已完成全文发布的论文。原始变量入库保持原流水线默认规则；标准变量入库使用映射结果。两种模式均保留原始证据与阶段产物。"
      actions={<>
        <Button type="button" variant="outline" onClick={batches.reload}><RefreshCw size={14} aria-hidden="true" />刷新列表</Button>
        <Button asChild variant="outline"><a href="/causal-graph"><Network size={15} aria-hidden="true" />因果知识图谱</a></Button>
      </>}
    />
    {error && <div className="mb-4"><Notice error>{error}</Notice></div>}

    {canExtract && <section className="panel causal-create-panel mb-5">
      <div className="panel-bar">
        <div className="panel-title"><Sparkles size={16} strokeWidth={1.7} aria-hidden="true" /><strong>创建因果抽取任务</strong></div>
        <span className="muted text-sm">单任务上限 500 篇 · 先预估确认可执行论文数</span>
      </div>
      <div className="causal-create-body">
        <div className="causal-create-row">
          <label>
            <span>目标范围</span>
            <select className="field" aria-label="目标范围" value={mode} onChange={(e) => setMode(e.target.value as CausalTargetMode)}>
              <option value="PAPER_IDS">指定论文</option>
              <option value="WORK_IDS">指定 Work</option>
              <option value="SOURCE_IDS">指定 Source</option>
              <option value="FILTER">筛选结果</option>
            </select>
          </label>
          <label>
            <span>入库模式</span>
            <select className="field" aria-label="入库模式" value={ingestionMode} onChange={(e) => setIngestionMode(e.target.value as CausalIngestionMode)}>
              <option value="RAW">原始变量入库（默认）</option>
              <option value="STANDARDIZED">标准变量入库</option>
            </select>
          </label>
          <label>
            <span>入库策略</span>
            <select className="field" aria-label="入库策略" value={reviewPolicy} onChange={(e) => setReviewPolicy(e.target.value as CausalReviewPolicy)}>
              <option value="MANUAL_REVIEW">人工审核后入库（默认）</option>
              <option value="AUTO_PUBLISH">抽取完成自动入库</option>
            </select>
          </label>
        </div>

        {mode === "FILTER" ? <div className="causal-filter-grid">
          <label><span>论文搜索</span><input className="field" placeholder="标题、作者或论文 ID" value={q} onChange={(e) => setQ(e.target.value)} /></label>
          <label><span>Source ID</span><SourceField value={sourceId} change={setSourceId} placeholder="搜索出版来源" /></label>
          <label><span>起始年份</span><input className="field" type="number" min={1000} max={9999} placeholder="起始年份" value={yearFrom} onChange={(e) => setYearFrom(e.target.value)} /></label>
          <label><span>结束年份</span><input className="field" type="number" min={1000} max={9999} placeholder="结束年份" value={yearTo} onChange={(e) => setYearTo(e.target.value)} /></label>
        </div> : mode === "SOURCE_IDS" ? <div className="causal-create-row">
          <label><span>选择出版来源</span><SourceField value={sourceId} change={setSourceId} placeholder="搜索并选择出版来源" /></label>
          <label><span>或追加多个 Source ID（可选，换行分隔）</span><textarea className="field" rows={2} placeholder="S123456" value={idsText} onChange={(e) => setIdsText(e.target.value)} /></label>
        </div> : <label>
          <span>{mode === "PAPER_IDS" ? "目标 ID（每行一个，最多 500 篇）" : "目标 Work ID（每行一个，如 W123456）"}</span>
          <textarea className="field mono" rows={3} placeholder="每行一个 ID，最多 500 篇" value={idsText} onChange={(e) => setIdsText(e.target.value)} />
        </label>}

        <div className="causal-filter-actions">
          <Button type="button" variant="outline" disabled={busy} onClick={runEstimate}>
            <Calculator size={14} aria-hidden="true" />预估目标
          </Button>
          <Button type="button" disabled={busy || !estimate?.enabled || !estimate.executableCount} onClick={createBatch}>
            <Play size={14} aria-hidden="true" />创建任务
          </Button>
        </div>

        {estimate && <div className={"causal-estimate-box" + (estimate.enabled ? "" : " is-warn")}>
          {estimate.enabled ? <>
            <strong>目标 {estimate.targetTotal} 篇，可执行 {estimate.executableCount} 篇</strong>
            <span className="muted text-sm">
              配置版本 {estimate.engineVersion} · 入库模式 {estimate.ingestionMode === "RAW" ? "原始变量（默认）" : "标准变量"} · 任务调用上限 {estimate.maxCalls} 次
            </span>
          </> : <>
            <strong>因果抽取尚未开启，请完成模型与引擎配置</strong>
            <span className="muted text-sm">请前往 <a className="inline-link" href="/service-settings">系统管理 → 服务配置</a> 配置大模型并启用因果抽取。</span>
          </>}
          {Object.keys(estimate.skipReasons).length > 0 && <div className="causal-skip-pills">
            {Object.entries(estimate.skipReasons).map(([code, count]) => (
              <span key={code} className="role-pill">{SKIP_LABELS[code] ?? code}：{count} 篇</span>
            ))}
          </div>}
        </div>}
      </div>
    </section>}

    <section className="panel">
      <div className="panel-bar">
        <strong>因果抽取任务列表</strong>
        {batches.data && <span className="muted text-sm tabular-nums">共 {batches.data.total.toLocaleString()} 个任务</span>}
      </div>
      {batches.error ? <div className="p-5"><Notice error>{batches.error.message}</Notice></div> :
        !batches.data ? <Loading label="正在加载任务列表…" /> :
          batches.data.items.length === 0 ? <Empty title="暂无因果抽取任务">可在上方创建新任务，或从论文详情、论文库勾选栏与期刊目录发起。</Empty> : <>
            <div className="table-scroll" tabIndex={0} role="region" aria-label="因果抽取任务列表">
              <table className="data-table">
                <thead><tr>
                  <th scope="col">任务</th>
                  <th scope="col">状态</th>
                  <th scope="col">入库模式</th>
                  <th scope="col">结果</th>
                  <th scope="col">模型调用</th>
                  <th scope="col">创建时间</th>
                </tr></thead>
                <tbody>{batches.data.items.map((batch) => <tr key={batch.batchId}>
                  <td className="mono"><a className="inline-link" href={extractionBatchPath(batch.batchId)}>{batch.batchId.slice(0, 8)}…</a></td>
                  <td><BatchStatusChip status={batch.status} zeroClaimPapers={batch.zeroClaimPapers} total={batch.total} /></td>
                  <td><span className="role-pill">{batch.ingestionMode === "RAW" ? "原始变量（默认）" : "标准变量"}</span></td>
                  <td className="tabular-nums text-sm">
                    <div>处理完成 {batch.succeeded} 篇 / 失败 {batch.failed} 篇 / 跳过 {batch.skipped} 篇 / 总计 {batch.total} 篇</div>
                    <div className="muted">因果关系 <strong>{batch.totalClaims}</strong> 条；未抽到关系 {batch.zeroClaimPapers} 篇</div>
                  </td>
                  <td className="tabular-nums">{batch.callsUsed.toLocaleString()}</td>
                  <td className="table-date">{dateLabel(batch.createdAt)}</td>
                </tr>)}</tbody>
              </table>
            </div>
            <Pagination page={page} size={size} total={batches.data.total} change={setPage} />
          </>}
    </section>
  </>
}
