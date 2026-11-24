import { Archive, BookOpen, BookOpenCheck, Database, ExternalLink, Eye, Play, Plus, ScanText, Search, Sparkles, Upload, Workflow, X } from "lucide-react"
import { useEffect, useState, type FormEvent } from "react"
import { api, dateLabel, errorMessage, jobPath, paperPath, params, useQuery } from "../api"
import { extractionBatchPath, extractionEntryHref, type PaperCausalStatusInfo, type PaperCausalStatusType } from "../causal/types"
import { Drawer } from "../components/drawer"
import { Chip, Empty, Loading, Notice, Pagination, SourceField, paperLabels } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { navigate, useLocation } from "../router"
import type { Job, JobKind, Page, Paper, PaperStatus } from "../types"
import { PaperCsvExport } from "./export"
import { ArchiveUpload, MetadataForm, ProcessPapers } from "./forms"

const size = 20
const initial = { q: "", sourceId: "", yearFrom: "", yearTo: "", status: "", causalStatus: "" }
type FilterState = typeof initial

export const CAUSAL_STATUS_LABELS: Record<string, [string, string]> = {
  NOT_EXTRACTED: ["未抽取", "neutral"],
  EXTRACTING: ["抽取中", "blue"],
  PENDING_REVIEW: ["待审核", "amber"],
  EXTRACTED: ["已抽取", "green"],
  EXTRACTED_NO_CAUSAL: ["已抽取（无因果）", "neutral"],
  FAILED: ["抽取失败", "red"],
  PREREQUISITE_MISSING: ["前置未完成", "neutral"],
}

function parseUrl(search: string): { filter: FilterState; archived: boolean } {
  const sp = new URLSearchParams(search)
  const rawStatus = sp.get("status") ?? ""
  const rawCausal = sp.get("causalStatus") ?? ""
  return {
    filter: {
      q: sp.get("q") ?? "",
      sourceId: sp.get("sourceId") ?? "",
      yearFrom: sp.get("yearFrom") ?? "",
      yearTo: sp.get("yearTo") ?? "",
      status: Object.hasOwn(paperLabels, rawStatus) ? rawStatus : "",
      causalStatus: Object.hasOwn(CAUSAL_STATUS_LABELS, rawCausal) ? rawCausal : "",
    },
    archived: sp.get("archived") === "true",
  }
}

export function Library() {
  const loc = useLocation()
  const { can, canJob, canProcess } = usePermissions()
  const canWrite = can("PAPER_WRITE")
  const canCausalExtract = can("CAUSAL_EXTRACT")
  const initialState = parseUrl(loc.search)
  const [filter, setFilter] = useState<FilterState>(initialState.filter)
  const [draft, setDraft] = useState<FilterState>(initialState.filter)
  const [archived, setArchived] = useState(initialState.archived)
  const canSelect = !archived && (canProcess || canCausalExtract)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<"upload" | "archive" | "process" | null>(null)
  const [previewPaper, setPreviewPaper] = useState<Paper | null>(null)

  const [causalStatusMap, setCausalStatusMap] = useState<Record<string, PaperCausalStatusInfo>>({})
  const [causalLaunchNotice, setCausalLaunchNotice] = useState<string>("")
  const [extractingDirectly, setExtractingDirectly] = useState(false)

  const query = params({ ...filter, archived })
  const result = useQuery<Page<Paper>>("/papers" + params({ ...filter, archived, page, size }))
  const rows = result.data?.items ?? []

  useEffect(() => {
    if (!rows.length) return
    let active = true
    rows.forEach(async (p) => {
      try {
        const info = await api<PaperCausalStatusInfo>("/causal/papers/status/" + encodeURIComponent(p.paperId))
        if (active) {
          setCausalStatusMap((prev) => ({ ...prev, [p.paperId]: info }))
        }
      } catch (e) {
        // ignore
      }
    })
    return () => { active = false }
  }, [rows])

  async function launchDirectCausalExtraction() {
    if (selected.size === 0) return
    setExtractingDirectly(true)
    setCausalLaunchNotice("")
    const selectedIds = [...selected]
    const executable: string[] = []
    let skippedCount = 0

    for (const id of selectedIds) {
      const paper = rows.find((r) => r.paperId === id)
      const st = causalStatusMap[id]?.status
      const eligible = paper && !paper.archived && paper.status === "PUBLISHED" && Boolean(paper.matchedWorkId)
      if (eligible && (st === "NOT_EXTRACTED" || st === "FAILED" || !st)) {
        executable.push(id)
      } else {
        skippedCount++
      }
    }

    if (executable.length === 0) {
      setCausalLaunchNotice(`所选 ${selected.size} 篇论文均不满足抽取条件（需已关联 OpenAlex、已发布全文且未处于抽取中），已跳过。`)
      setExtractingDirectly(false)
      return
    }

    try {
      const created = await api<{ batchId: string }>("/causal/extractions", {
        method: "POST",
        body: JSON.stringify({
          target: { mode: "PAPER_IDS", paperIds: executable },
          ingestionMode: "RAW",
          reviewPolicy: "MANUAL_REVIEW",
        }),
      })
      const notice = skippedCount > 0
        ? `正在对 ${executable.length} 篇论文发起因果抽取（自动跳过 ${skippedCount} 篇不可抽取论文）。`
        : `正在对 ${executable.length} 篇论文发起因果抽取。`
      setCausalLaunchNotice(notice)
      setSelected(new Set())
      executable.forEach((id) => {
        setCausalStatusMap((prev) => {
          const cur = prev[id]
          if (!cur) return prev
          return { ...prev, [id]: { ...cur, status: "EXTRACTING" } }
        })
      })
    } catch (err: any) {
      setCausalLaunchNotice("发起因果抽取失败：" + (err?.message || "网络或服务端错误"))
    } finally {
      setExtractingDirectly(false)
    }
  }

  function syncUrl(nextFilter: FilterState, nextArchived: boolean) {
    const search = params({
      q: nextFilter.q || undefined,
      sourceId: nextFilter.sourceId || undefined,
      yearFrom: nextFilter.yearFrom || undefined,
      yearTo: nextFilter.yearTo || undefined,
      status: nextFilter.status || undefined,
      causalStatus: nextFilter.causalStatus || undefined,
      archived: nextArchived ? "true" : undefined,
    })
    const target = "/papers" + search
    if (target !== window.location.pathname + window.location.search) {
      window.history.replaceState(window.history.state, "", target)
    }
  }

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    applyFilter(draft)
  }

  function applyFilter(next: FilterState, nextArchived = archived) {
    syncUrl(next, nextArchived)
    setPage(0)
    setSelected(new Set())
    setFilter(next)
    setDraft(next)
    setArchived(nextArchived)
  }

  function switchArchive(value: boolean) {
    syncUrl(filter, value)
    setArchived(value)
    setPage(0)
    setSelected(new Set())
  }

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  const allChecked = rows.length > 0 && rows.every((paper) => selected.has(paper.paperId))
  function togglePage() {
    setSelected((current) => {
      const next = new Set(current)
      rows.forEach((paper) => allChecked ? next.delete(paper.paperId) : next.add(paper.paperId))
      return next
    })
  }
  function update(key: keyof FilterState, value: string) {
    setDraft((old) => ({ ...old, [key]: value }))
  }

  const activeChips: { key: string; label: string; clear: () => void }[] = []
  if (filter.q) activeChips.push({ key: "q", label: `关键词：${filter.q}`, clear: () => applyFilter({ ...filter, q: "" }) })
  if (filter.sourceId) activeChips.push({ key: "source", label: `出版来源：${filter.sourceId}`, clear: () => applyFilter({ ...filter, sourceId: "" }) })
  if (filter.yearFrom || filter.yearTo) {
    const label = filter.yearFrom && filter.yearTo ? `年份：${filter.yearFrom}–${filter.yearTo}` :
      filter.yearFrom ? `年份：${filter.yearFrom} 起` : `年份：截至 ${filter.yearTo}`
    activeChips.push({ key: "year", label, clear: () => applyFilter({ ...filter, yearFrom: "", yearTo: "" }) })
  }
  if (filter.status) {
    const statusLabel = paperLabels[filter.status as PaperStatus]?.[0] ?? filter.status
    activeChips.push({ key: "status", label: `状态：${statusLabel}`, clear: () => applyFilter({ ...filter, status: "" }) })
  }
  if (filter.causalStatus) {
    const causalLabel = CAUSAL_STATUS_LABELS[filter.causalStatus]?.[0] ?? filter.causalStatus
    activeChips.push({ key: "causalStatus", label: `因果抽取状态：${causalLabel}`, clear: () => applyFilter({ ...filter, causalStatus: "" }) })
  }

  return <>
    <SectionHeader title="论文库" description="按书目、出版来源、发表年份与全文阶段检索典藏文献，支持行级快览与批量处理。" actions={<>
      {can("PAPER_CSV_EXPORT") && <PaperCsvExport query={query} />}
      {canWrite && <Button type="button" variant="outline" onClick={() => setDialog("upload")}><Plus size={16} aria-hidden="true" />上传单篇</Button>}
      {canJob("ORIGINAL_IMPORT") && <Button type="button" onClick={() => setDialog("archive")}><Upload size={16} aria-hidden="true" />批量导入</Button>}
    </>} />
    <form className="panel filter-deck filter-bar" onSubmit={apply}>
      <label className="search-box"><Search size={16} aria-hidden="true" /><input className="field" type="search" value={draft.q} placeholder="搜索标题、作者、DOI 或论文 ID" aria-label="搜索论文" onChange={(e) => update("q", e.target.value)} /></label>
      <label><span className="sr-only">出版来源</span><SourceField value={draft.sourceId} change={(value) => update("sourceId", value)} placeholder="出版来源" /></label>
      <div className="year-filter">
        <input className="field" type="number" min={1000} max={9999} aria-label="起始年份" placeholder="起始年" value={draft.yearFrom} onChange={(e) => update("yearFrom", e.target.value)} />
        <span aria-hidden="true">–</span>
        <input className="field" type="number" min={1000} max={9999} aria-label="结束年份" placeholder="结束年" value={draft.yearTo} onChange={(e) => update("yearTo", e.target.value)} />
      </div>
      <select className="field" aria-label="处理状态" value={draft.status} onChange={(e) => update("status", e.target.value)}>
        <option value="">全文状态</option><option value="PUBLISHED">已发布全文</option><option value="READY">待导入全文</option><option value="UNPROCESSED">待解析</option><option value="FAILED">处理失败</option><option value="UNSUPPORTED">格式暂不支持</option>
      </select>
      <select className="field" aria-label="因果抽取状态" value={draft.causalStatus} onChange={(e) => update("causalStatus", e.target.value)}>
        <option value="">因果抽取状态</option>
        <option value="NOT_EXTRACTED">未抽取</option>
        <option value="EXTRACTED">已抽取（有因果）</option>
        <option value="EXTRACTED_NO_CAUSAL">已抽取（无因果）</option>
        <option value="FAILED">抽取失败</option>
        <option value="PREREQUISITE_MISSING">前置未完成</option>
        <option value="EXTRACTING">抽取中</option>
        <option value="PENDING_REVIEW">待审核</option>
      </select>
      <div className="filter-actions">
        <Button type="submit" variant="outline">查询</Button>
        {activeChips.length > 0 && <Button type="button" variant="ghost" onClick={() => applyFilter(initial)}>重置</Button>}
      </div>
    </form>
    {activeChips.length > 0 && <div className="active-filters" aria-label="当前生效筛选">
      {activeChips.map((chip) => (
        <span key={chip.key} className="active-filter-chip">
          <span>{chip.label}</span>
          <button type="button" aria-label={"移除" + chip.label} onClick={chip.clear}><X size={13} aria-hidden="true" /></button>
        </span>
      ))}
    </div>}
    {causalLaunchNotice && (
      <div className="mb-4">
        <Notice>{causalLaunchNotice}</Notice>
      </div>
    )}
    <section className="panel library-panel">
      <div className="panel-bar library-bar">
        <div className="panel-tabs" role="group" aria-label="论文库视图">
          <button type="button" aria-pressed={!archived} onClick={() => switchArchive(false)}><BookOpenCheck size={15} aria-hidden="true" />论文列表</button>
          <button type="button" aria-pressed={archived} onClick={() => switchArchive(true)}><Archive size={15} aria-hidden="true" />归档</button>
        </div>
        {canProcess && !archived && selected.size === 0 && <Button type="button" variant="outline" size="sm" onClick={() => setDialog("process")}><Workflow size={15} aria-hidden="true" />批量处理</Button>}
      </div>
      {result.error ? <div className="p-5"><Notice error>{result.error.message}</Notice><Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button></div> :
        result.loading && !rows.length ? <Loading /> : !rows.length ?
          <Empty title={archived ? "没有归档论文" : "没有找到论文"}>{canJob("ORIGINAL_IMPORT") ? "调整筛选条件，或通过批量导入添加原件。" : "调整筛选条件后重试。"}</Empty> :
          <div className="table-scroll" tabIndex={0} role="region" aria-label="论文列表表格">
            <table className="data-table paper-table">
              <thead><tr>{canSelect && <th className="check-cell"><input type="checkbox" checked={allChecked} onChange={togglePage} aria-label="选择本页全部论文" /></th>}
                <th scope="col">论文</th><th scope="col">出版来源</th><th scope="col">年份</th><th scope="col">全文状态</th><th scope="col">因果抽取状态</th><th scope="col">OpenAlex</th><th scope="col" className="preview-col"><span className="sr-only">快览</span></th></tr></thead>
              <tbody>{rows.map((paper) => {
                const cInfo = causalStatusMap[paper.paperId]
                const cStatus = cInfo?.status ?? (paper.status === "PUBLISHED" && paper.matchedWorkId ? "NOT_EXTRACTED" : "PREREQUISITE_MISSING")
                const [cLabel, cTone] = CAUSAL_STATUS_LABELS[cStatus] ?? [cStatus, "neutral"]
                const displayLabel = cStatus === "EXTRACTED" && cInfo && cInfo.claimCount > 0
                  ? `已抽取（${cInfo.claimCount}条）`
                  : cLabel

                return <tr key={paper.paperId} className={selected.has(paper.paperId) ? "row-selected" : ""}>
                  {canSelect && <td className="check-cell"><input type="checkbox" checked={selected.has(paper.paperId)} onChange={() => toggle(paper.paperId)} aria-label={"选择 " + (paper.title || paper.paperId)} /></td>}
                  <td className="title-cell"><a href={paperPath(paper.paperId)}>{paper.title || paper.paperId}</a><small>{paper.authors?.replaceAll(";", " · ") || "作者未登记"}</small></td>
                  <td className="source-cell">
                    {paper.sourceId ? <a className="source-jump" href={"/journals" + params({ journal: paper.sourceId })}>{paper.sourceName || paper.sourceId}</a> : "—"}
                  </td>
                  <td className="year-cell">{paper.year ?? "—"}</td>
                  <td><Chip status={paper.status} /></td>
                  <td>
                    <span className={"status-chip status-" + cTone}>
                      <span className="status-dot" aria-hidden="true" />
                      {displayLabel}
                    </span>
                  </td>
                  <td className="association-cell">{paper.matchedWorkId ? <><span className="assoc-linked">已关联</span><small className="mono">{paper.matchedWorkId}</small></> : <span className="muted">未关联</span>}</td>
                  <td className="preview-col">
                    <Button type="button" variant="ghost" size="icon" className="preview-trigger"
                      aria-label={"快览 " + (paper.title || paper.paperId)} title="快速预览"
                      onClick={() => setPreviewPaper(paper)}>
                      <Eye size={15} aria-hidden="true" />
                    </Button>
                  </td>
                </tr>
              })}</tbody>
            </table>
          </div>}
      {result.data && !result.error && <Pagination page={page} size={size} total={result.data.total} change={setPage} />}
    </section>
    {canSelect && selected.size > 0 && <div className="selection-dock">
      <span role="status" className="selection-dock-count">已选择 {selected.size} 篇</span>
      <div className="selection-dock-actions">
        {canProcess && <Button type="button" variant="default" size="sm" className="dock-primary" onClick={() => setDialog("process")}><Workflow size={14} aria-hidden="true" />批量处理</Button>}
        {canCausalExtract && <Button type="button" variant={canProcess ? "outline" : "default"} size="sm" disabled={extractingDirectly} onClick={launchDirectCausalExtraction}>
          <Sparkles size={14} aria-hidden="true" />发起因果抽取
        </Button>}
        {canCausalExtract && <Button asChild variant="outline" size="sm">
          <a href={extractionEntryHref({ mode: "PAPER_IDS", ids: [...selected] })}><Play size={14} aria-hidden="true" />高级抽取</a>
        </Button>}
        <Button type="button" variant="ghost" size="sm" className="dock-ghost" onClick={() => setSelected(new Set())}>取消选择</Button>
      </div>
    </div>}
    {previewPaper && <PaperQuickDrawer paper={previewPaper} onClose={() => setPreviewPaper(null)} />}
    {canWrite && dialog === "upload" && <MetadataForm close={() => setDialog(null)} done={(paper) => { setDialog(null); navigate(paperPath(paper.paperId)) }} />}
    {canJob("ORIGINAL_IMPORT") && dialog === "archive" && <ArchiveUpload close={() => setDialog(null)} />}
    {canProcess && dialog === "process" && <ProcessPapers ids={[...selected]} defaultSourceId={filter.sourceId} close={() => setDialog(null)} />}
  </>
}

function PaperQuickDrawer({ paper, onClose }: { paper: Paper; onClose: () => void }) {
  const { canJob } = usePermissions()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const canPublish = paper.status === "READY" || Boolean(paper.latestParseId && paper.latestParseId !== paper.publishedParseId)

  async function run(kind: JobKind) {
    setBusy(true)
    setError("")
    try {
      const job = await api<Job>("/jobs", { method: "POST", body: JSON.stringify({ kind, paperIds: [paper.paperId] }) })
      onClose()
      navigate(jobPath(job.jobId))
    } catch (reason) {
      setError(errorMessage(reason))
    } finally {
      setBusy(false)
    }
  }

  return <Drawer
    title={paper.title || paper.paperId}
    subtitle={paper.authors?.replaceAll(";", " · ") || "作者未登记"}
    onClose={onClose}
    footer={<div className="drawer-action-row">
      <Button asChild><a href={paperPath(paper.paperId)} onClick={onClose}>打开完整详情页<ExternalLink size={14} aria-hidden="true" /></a></Button>
      {!paper.archived && canJob("PARSE") && paper.hasOriginal && paper.originalType === "PDF" &&
        <Button type="button" variant="outline" disabled={busy} onClick={() => run("PARSE")}>
          <ScanText size={14} aria-hidden="true" />{paper.status === "PUBLISHED" || paper.latestParseId ? "重新解析" : "开始解析"}
        </Button>}
      {!paper.archived && canJob("PUBLISH") && canPublish &&
        <Button type="button" variant="outline" disabled={busy} onClick={() => run("PUBLISH")}>
          <BookOpen size={14} aria-hidden="true" />导入全文
        </Button>}
      {!paper.archived && canJob("MATCH") && !paper.matchedWorkId &&
        <Button type="button" variant="outline" disabled={busy} onClick={() => run("MATCH")}>
          <Database size={14} aria-hidden="true" />匹配 OpenAlex
        </Button>}
    </div>}
  >
    <div className="drawer-stack">
      {error && <Notice error>{error}</Notice>}
      <div className="drawer-chip-row">
        <Chip status={paper.status} />
        {paper.archived && <span className="role-pill">已归档</span>}
        {paper.legacy && <span className="role-pill">历史接续</span>}
      </div>
      <dl className="drawer-facts">
        <div>
          <dt>出版来源</dt>
          <dd>{paper.sourceName || paper.sourceId || "—"}{paper.sourceName && paper.sourceId && <small className="block mono muted">{paper.sourceId}</small>}</dd>
        </div>
        <div><dt>发表年份</dt><dd className="tabular-nums">{paper.year ?? "—"}</dd></div>
        <div><dt>DOI</dt><dd className={paper.doi ? "mono" : undefined}>{paper.doi || "—"}</dd></div>
        <div><dt>原件格式</dt><dd className="mono">{paper.originalType || "—"} · Rev. {paper.revision}</dd></div>
        <div>
          <dt>OpenAlex 关联</dt>
          <dd>{paper.matchedWorkId ? <code className="id-plate mono">{paper.matchedWorkId}</code> : <span className="muted">未关联</span>}</dd>
        </div>
        <div><dt>最近更新</dt><dd className="tabular-nums">{dateLabel(paper.updatedAt)}</dd></div>
        <div className="drawer-fact-wide"><dt>论文 ID</dt><dd><code className="id-plate mono break-all">{paper.paperId}</code></dd></div>
      </dl>
    </div>
  </Drawer>
}
