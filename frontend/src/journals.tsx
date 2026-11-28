import {
  Archive, ArrowRight, ArrowUpRight, BookMarked, Database, ExternalLink,
  LayoutGrid, List, Search, Sparkles, Workflow,
} from "lucide-react"
import { useEffect, useState, type FormEvent } from "react"
import { dateLabel, params, useQuery } from "./api"
import { extractionEntryHref } from "./causal/types"
import { Drawer } from "./components/drawer"
import { Empty, Loading, Notice, Pagination } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { ProcessPapers } from "./papers/forms"
import { usePermissions } from "./permissions"
import { navigate, useLocation } from "./router"
import type {
  JournalDetail, JournalDirectory, JournalFilterMode, JournalSort, JournalSummary, JournalYearStats,
} from "./types"

const size = 18

const filterOptions: { value: JournalFilterMode; label: string }[] = [
  { value: "ALL", label: "全部期刊" },
  { value: "HAS_FAILED", label: "存在失败" },
  { value: "HAS_PENDING", label: "有待处理" },
  { value: "ALL_PUBLISHED", label: "已全部发布" },
]

const sortOptions: { value: JournalSort; label: string }[] = [
  { value: "PAPERS_DESC", label: "按论文数从高到低" },
  { value: "PUBLISHED_RATE_DESC", label: "按全文发布率最高" },
  { value: "CAUSAL_RATE_DESC", label: "按因果抽取率最高" },
  { value: "FAILED_DESC", label: "按失败数优先" },
  { value: "PENDING_DESC", label: "按待处理积压优先" },
  { value: "UPDATED_DESC", label: "按最近更新" },
  { value: "NAME_ASC", label: "按期刊名称 A–Z" },
]

export function Journals() {
  const loc = useLocation()
  const [draft, setDraft] = useState("")
  const [q, setQ] = useState("")
  const [mode, setMode] = useState<JournalFilterMode>("ALL")
  const [sort, setSort] = useState<JournalSort>("PAPERS_DESC")
  const [view, setView] = useState<"cards" | "table">("cards")
  const [page, setPage] = useState(0)
  const [inspecting, setInspecting] = useState<string | null>(() => new URLSearchParams(loc.search).get("journal"))

  useEffect(() => {
    const fromUrl = new URLSearchParams(loc.search).get("journal")
    if (fromUrl) setInspecting(fromUrl)
  }, [loc.search])

  const query = "/journals" + params({ q, filter: mode === "ALL" ? undefined : mode, sort, page, size })
  const result = useQuery<JournalDirectory>(query)
  const items = result.data?.items ?? []
  const summary = result.data?.summary

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setQ(draft.trim())
    setPage(0)
  }

  function openInspector(sourceId: string) {
    setInspecting(sourceId)
    const next = new URLSearchParams(window.location.search)
    next.set("journal", sourceId)
    navigate("/journals?" + next.toString(), { replace: true })
  }

  function closeInspector() {
    setInspecting(null)
    const next = new URLSearchParams(window.location.search)
    next.delete("journal")
    navigate("/journals" + (next.size ? "?" + next.toString() : ""), { replace: true })
  }

  const publishedPct = summary ? pct(summary.publishedPapers, summary.totalPapers) : 0
  const matchedPct = summary ? pct(summary.matchedPapers, summary.totalPapers) : 0
  const causalCompleted = summary ? summary.causalExtractedPapers + summary.causalNoCausalPapers : 0
  const causalPct = summary ? pct(causalCompleted, summary.totalPapers) : 0
  const yearSpan = summary?.yearMin && summary?.yearMax
    ? (summary.yearMin === summary.yearMax ? `${summary.yearMin} 年` : `${summary.yearMin}–${summary.yearMax} 年`)
    : "年份未登记"

  return <>
    <SectionHeader
      title="期刊目录"
      description="按出版来源总览论文库收录范围、年度覆盖、全文发布完成度、OpenAlex 关联与因果知识抽取进度。"
      actions={<Button asChild variant="outline"><a href="/papers">进入全部论文库<ArrowRight size={15} aria-hidden="true" /></a></Button>}
    />

    {summary && <section className="panel journal-overview-strip" aria-label="全库期刊统计概览">
      <div className="journal-kpi">
        <span className="journal-kpi-label">已收录期刊</span>
        <strong className="journal-kpi-value">{summary.totalJournals.toLocaleString()}</strong>
        <span className="journal-kpi-sub">{yearSpan}</span>
      </div>
      <div className="journal-kpi">
        <span className="journal-kpi-label">未归档论文</span>
        <strong className="journal-kpi-value">{summary.totalPapers.toLocaleString()}</strong>
        <span className="journal-kpi-sub" data-alert={summary.failedPapers > 0 ? "true" : undefined}>
          {summary.failedPapers > 0 ? `${summary.failedPapers.toLocaleString()} 篇处理失败` : "无处理失败积压"}
        </span>
      </div>
      <div className="journal-kpi">
        <span className="journal-kpi-label">全文发布率</span>
        <strong className="journal-kpi-value">{publishedPct}%</strong>
        <div className="mini-meter" aria-hidden="true"><span className="mini-meter-fill is-green" style={{ width: publishedPct + "%" }} /></div>
        <span className="journal-kpi-sub">{summary.publishedPapers.toLocaleString()} / {summary.totalPapers.toLocaleString()} 篇</span>
      </div>
      <div className="journal-kpi">
        <span className="journal-kpi-label">OpenAlex 覆盖率</span>
        <strong className="journal-kpi-value">{matchedPct}%</strong>
        <div className="mini-meter" aria-hidden="true"><span className="mini-meter-fill is-blue" style={{ width: matchedPct + "%" }} /></div>
        <span className="journal-kpi-sub">{summary.matchedPapers.toLocaleString()} / {summary.totalPapers.toLocaleString()} 篇</span>
      </div>
      <div className="journal-kpi">
        <span className="journal-kpi-label">因果抽取完成率</span>
        <strong className="journal-kpi-value">{causalPct}%</strong>
        <div className="mini-meter" aria-hidden="true"><span className="mini-meter-fill is-teal" style={{ width: causalPct + "%" }} /></div>
        <span className="journal-kpi-sub" data-alert={summary.causalFailedPapers > 0 ? "true" : undefined}>
          {causalCompleted.toLocaleString()} 篇完成 · {summary.causalClaims.toLocaleString()} 条主张
          {summary.causalPendingReviewPapers > 0 ? ` · ${summary.causalPendingReviewPapers} 待审` : ""}
          {summary.causalFailedPapers > 0 ? ` · ${summary.causalFailedPapers} 失败` : ""}
        </span>
      </div>
    </section>}

    <div className="panel filter-deck journal-controls">
      <form className="journal-search-row" onSubmit={submitSearch}>
        <label className="search-box">
          <Search size={16} aria-hidden="true" />
          <input className="field" type="search" value={draft} aria-label="搜索期刊"
            placeholder="搜索期刊名称或 OpenAlex 来源 ID（如 S23254222）"
            onChange={(event) => setDraft(event.target.value)} />
        </label>
        <select className="field journal-sort" aria-label="排序方式" value={sort}
          onChange={(event) => { setSort(event.target.value as JournalSort); setPage(0) }}>
          {sortOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
        <Button type="submit" variant="outline">查询</Button>
        {q && <Button type="button" variant="ghost" onClick={() => { setDraft(""); setQ(""); setPage(0) }}>重置</Button>}
      </form>
      <div className="journal-subcontrols">
        <div className="pill-filter-group" role="group" aria-label="按健康度筛选期刊">
          {filterOptions.map((option) => (
            <button key={option.value} type="button" className="filter-pill" aria-pressed={mode === option.value}
              onClick={() => { setMode(option.value); setPage(0) }}>{option.label}</button>
          ))}
        </div>
        <div className="panel-tabs" role="group" aria-label="期刊目录视图">
          <button type="button" aria-pressed={view === "cards"} onClick={() => setView("cards")}><LayoutGrid size={14} aria-hidden="true" />卡片</button>
          <button type="button" aria-pressed={view === "table"} onClick={() => setView("table")}><List size={14} aria-hidden="true" />列表</button>
        </div>
      </div>
    </div>

    {result.error ? <section className="panel p-5">
      <Notice error>{result.error.message}</Notice>
      <Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button>
    </section> : result.loading && !items.length ? <section className="panel"><Loading label="正在聚合期刊数据…" /></section> :
      items.length === 0 ? <section className="panel">
        <Empty title="没有符合条件的期刊">调整搜索词或筛选状态，或先向论文库导入文献原件。</Empty>
      </section> : view === "cards" ? <>
        <div className="journal-grid" role="region" aria-label="期刊目录卡片">
          {items.map((journal) => <JournalCard key={journal.sourceId} journal={journal}
            selected={inspecting === journal.sourceId} onOpen={() => openInspector(journal.sourceId)} />)}
        </div>
        {result.data && <div className="panel mt-4"><Pagination page={page} size={size} total={result.data.total} change={setPage} /></div>}
      </> : <section className="panel">
        <div className="table-scroll" tabIndex={0} role="region" aria-label="期刊目录表格">
          <table className="data-table journal-table">
            <thead><tr>
              <th scope="col">出版来源</th><th scope="col">年份跨度</th><th scope="col">论文数</th>
              <th scope="col">全文发布进度</th><th scope="col">OpenAlex 关联</th><th scope="col">因果抽取进度</th><th scope="col">操作</th>
            </tr></thead>
            <tbody>{items.map((journal) => {
              const pubPct = pct(journal.publishedCount, journal.totalPapers)
              const matchPct = pct(journal.matchedCount, journal.totalPapers)
              const causalDone = journal.causalExtractedCount + journal.causalNoCausalCount
              const causalPctVal = pct(causalDone, journal.totalPapers)
              return <tr key={journal.sourceId} className={inspecting === journal.sourceId ? "row-selected" : ""}>
                <td className="title-cell">
                  <button type="button" className="journal-name-button" onClick={() => openInspector(journal.sourceId)}>{journal.name}</button>
                  <small className="mono">{journal.sourceId}</small>
                </td>
                <td className="year-cell">{yearLabel(journal.yearMin, journal.yearMax)}</td>
                <td className="tabular-nums font-medium">{journal.totalPapers.toLocaleString()}</td>
                <td className="journal-pipeline-cell">
                  <PipelineBar journal={journal} />
                  <small className="journal-bar-caption">
                    已发布 {pubPct}%{journal.failedCount > 0 && <> · <span className="text-danger">{journal.failedCount} 失败</span></>}
                    {journal.readyCount + journal.unprocessedCount > 0 && <> · {journal.readyCount + journal.unprocessedCount} 待处理</>}
                  </small>
                </td>
                <td className="journal-openalex-cell">
                  <div className="mini-meter" aria-hidden="true"><span className="mini-meter-fill is-blue" style={{ width: matchPct + "%" }} /></div>
                  <small className="journal-bar-caption tabular-nums">
                    {matchPct}% · {journal.matchedCount.toLocaleString()} / {journal.totalPapers.toLocaleString()}
                  </small>
                </td>
                <td className="journal-pipeline-cell">
                  <CausalPipelineBar journal={journal} />
                  <small className="journal-bar-caption tabular-nums">
                    已抽取 {causalPctVal}%（{journal.causalExtractedCount.toLocaleString()} 有因果）
                    {journal.causalPendingReviewCount > 0 && <> · {journal.causalPendingReviewCount} 待审</>}
                    {journal.causalFailedCount > 0 && <> · <span className="text-danger">{journal.causalFailedCount} 失败</span></>}
                  </small>
                </td>
                <td><div className="row-actions">
                  <Button type="button" variant="ghost" size="sm" onClick={() => openInspector(journal.sourceId)}>详情</Button>
                  <Button asChild variant="ghost" size="sm"><a href={"/papers" + params({ sourceId: journal.sourceId })}>论文</a></Button>
                </div></td>
              </tr>
            })}</tbody>
          </table>
        </div>
        {result.data && <Pagination page={page} size={size} total={result.data.total} change={setPage} />}
      </section>}

    {inspecting && <JournalDrawer sourceId={inspecting} onClose={closeInspector} />}
  </>
}

function JournalCard({ journal, selected, onOpen }: { journal: JournalSummary; selected: boolean; onOpen: () => void }) {
  const pubPct = pct(journal.publishedCount, journal.totalPapers)
  const matchPct = pct(journal.matchedCount, journal.totalPapers)
  const causalDone = journal.causalExtractedCount + journal.causalNoCausalCount
  const causalPctVal = pct(causalDone, journal.totalPapers)
  const pending = journal.readyCount + journal.unprocessedCount
  const causalActive = journal.causalExtractingCount + journal.causalPendingReviewCount

  return <article className={"panel journal-card" + (selected ? " is-selected" : "")}>
    <div className="journal-card-head">
      <div className="min-w-0">
        <h2 className="journal-card-title">
          <button type="button" onClick={onOpen}>{journal.name}</button>
        </h2>
        <div className="journal-card-meta">
          <span className="mono">{journal.sourceId}</span>
          <span aria-hidden="true">·</span>
          <span className="tabular-nums">{yearLabel(journal.yearMin, journal.yearMax)}</span>
        </div>
      </div>
      <div className="journal-card-total">
        <strong>{journal.totalPapers.toLocaleString()}</strong>
        <span>篇</span>
      </div>
    </div>

    <div className="journal-progress-stack">
      <div className="journal-card-bars">
        <div className="journal-metric-row">
          <span>全文发布进度</span>
          <strong className="tabular-nums">{pubPct}%</strong>
        </div>
        <PipelineBar journal={journal} />
        <div className="journal-stage-tags">
          <span className="stage-dot-label is-green">已发布 {journal.publishedCount.toLocaleString()}</span>
          {journal.readyCount > 0 && <span className="stage-dot-label is-amber">待导入 {journal.readyCount.toLocaleString()}</span>}
          {journal.unprocessedCount > 0 && <span className="stage-dot-label is-neutral">待解析 {journal.unprocessedCount.toLocaleString()}</span>}
          {journal.failedCount > 0 && <span className="stage-dot-label is-red">失败 {journal.failedCount.toLocaleString()}</span>}
          {pending === 0 && journal.failedCount === 0 && journal.publishedCount === journal.totalPapers &&
            <span className="stage-dot-label is-green">全部就绪</span>}
        </div>
      </div>

      <div className="journal-card-bars">
        <div className="journal-metric-row">
          <span>OpenAlex 关联进度</span>
          <strong className="tabular-nums">{matchPct}% <span className="muted font-normal">({journal.matchedCount.toLocaleString()}/{journal.totalPapers.toLocaleString()})</span></strong>
        </div>
        <div className="pipeline-bar" role="img" aria-label={`OpenAlex 已关联 ${journal.matchedCount} / ${journal.totalPapers} 篇`}>
          {journal.matchedCount > 0 && <span className="pipeline-seg seg-matched" style={{ width: `${(journal.matchedCount / Math.max(journal.totalPapers, 1)) * 100}%` }} />}
        </div>
      </div>

      <div className="journal-card-bars">
        <div className="journal-metric-row">
          <span>因果抽取进度</span>
          <strong className="tabular-nums">{causalPctVal}%</strong>
        </div>
        <CausalPipelineBar journal={journal} />
        <div className="journal-stage-tags">
          <span className="stage-dot-label is-teal">有因果 {journal.causalExtractedCount.toLocaleString()}</span>
          {journal.causalNoCausalCount > 0 && <span className="stage-dot-label is-slate">无因果 {journal.causalNoCausalCount.toLocaleString()}</span>}
          {journal.causalPendingReviewCount > 0 && <span className="stage-dot-label is-amber">待审核 {journal.causalPendingReviewCount.toLocaleString()}</span>}
          {journal.causalExtractingCount > 0 && <span className="stage-dot-label is-blue">抽取中 {journal.causalExtractingCount.toLocaleString()}</span>}
          {journal.causalFailedCount > 0 && <span className="stage-dot-label is-red">失败 {journal.causalFailedCount.toLocaleString()}</span>}
          {journal.causalNotExtractedCount > 0 && <span className="stage-dot-label is-neutral">待抽取 {journal.causalNotExtractedCount.toLocaleString()}</span>}
          {causalDone === 0 && causalActive === 0 && journal.causalFailedCount === 0 && journal.causalNotExtractedCount === 0 &&
            <span className="stage-dot-label is-neutral">前置未完成</span>}
        </div>
      </div>
    </div>

    <div className="journal-card-footer">
      <div className="journal-openalex-stat">
        <Sparkles size={13} aria-hidden="true" />
        <span>因果主张 <strong className="tabular-nums">{journal.causalClaimCount.toLocaleString()}</strong> 条</span>
      </div>
      <div className="journal-card-links">
        <button type="button" className="inline-link text-sm" onClick={onOpen}>数据详情</button>
        <a className="inline-link text-sm" href={"/papers" + params({ sourceId: journal.sourceId })}>
          论文<ArrowUpRight size={13} aria-hidden="true" />
        </a>
      </div>
    </div>
  </article>
}

export function PipelineBar({ journal }: { journal: Pick<JournalSummary, "totalPapers" | "publishedCount" | "readyCount" | "unprocessedCount" | "failedCount" | "unsupportedCount"> }) {
  const total = Math.max(journal.totalPapers, 1)
  const segments = [
    { key: "published", count: journal.publishedCount, cls: "seg-published", label: "已发布全文" },
    { key: "ready", count: journal.readyCount, cls: "seg-ready", label: "待导入全文" },
    { key: "unprocessed", count: journal.unprocessedCount, cls: "seg-unprocessed", label: "待解析" },
    { key: "failed", count: journal.failedCount, cls: "seg-failed", label: "处理失败" },
    { key: "unsupported", count: journal.unsupportedCount, cls: "seg-unsupported", label: "暂不支持" },
  ].filter((segment) => segment.count > 0)
  const summary = segments.map((s) => `${s.label} ${s.count}`).join(" · ")
  return <div className="pipeline-bar" role="img" aria-label={summary || "暂无数据"} title={summary}>
    {segments.map((segment) => (
      <span key={segment.key} className={"pipeline-seg " + segment.cls}
        style={{ width: `${(segment.count / total) * 100}%` }} />
    ))}
  </div>
}

export function CausalPipelineBar({ journal }: {
  journal: Pick<JournalSummary, "totalPapers" | "causalExtractedCount" | "causalNoCausalCount" | "causalPendingReviewCount" | "causalExtractingCount" | "causalFailedCount" | "causalNotExtractedCount">
}) {
  const total = Math.max(journal.totalPapers, 1)
  const segments = [
    { key: "extracted", count: journal.causalExtractedCount, cls: "seg-causal-extracted", label: "已抽取（有因果）" },
    { key: "noCausal", count: journal.causalNoCausalCount, cls: "seg-causal-none", label: "已抽取（无因果）" },
    { key: "pendingReview", count: journal.causalPendingReviewCount, cls: "seg-ready", label: "待审核" },
    { key: "extracting", count: journal.causalExtractingCount, cls: "seg-matched", label: "抽取中" },
    { key: "failed", count: journal.causalFailedCount, cls: "seg-failed", label: "抽取失败" },
    { key: "notExtracted", count: journal.causalNotExtractedCount, cls: "seg-unprocessed", label: "未抽取（可抽取）" },
  ].filter((segment) => segment.count > 0)
  const summary = segments.map((s) => `${s.label} ${s.count}`).join(" · ")
  return <div className="pipeline-bar" role="img" aria-label={summary || "暂无因果抽取数据"} title={summary || "前置未完成"}>
    {segments.map((segment) => (
      <span key={segment.key} className={"pipeline-seg " + segment.cls}
        style={{ width: `${(segment.count / total) * 100}%` }} />
    ))}
  </div>
}

function JournalDrawer({ sourceId, onClose }: { sourceId: string; onClose: () => void }) {
  const { can, canJob, canProcess } = usePermissions()
  const [processing, setProcessing] = useState(false)
  const result = useQuery<JournalDetail>("/journals/" + encodeURIComponent(sourceId))
  const detail = result.data
  const journal = detail?.journal
  const prereqMissing = journal
    ? Math.max(0, journal.totalPapers - journal.causalExtractedCount - journal.causalNoCausalCount
      - journal.causalPendingReviewCount - journal.causalExtractingCount - journal.causalFailedCount
      - journal.causalNotExtractedCount)
    : 0
  const causalDone = journal ? journal.causalExtractedCount + journal.causalNoCausalCount : 0

  return <>
    <Drawer
      title={journal?.name ?? sourceId}
      subtitle={<span className="mono">{sourceId}{journal && <> · {yearLabel(journal.yearMin, journal.yearMax)} · 共 {journal.totalPapers.toLocaleString()} 篇</>}</span>}
      width="lg"
      onClose={onClose}
      footer={journal && <div className="drawer-action-row">
        <Button asChild size="sm"><a href={"/papers" + params({ sourceId: journal.sourceId })} onClick={onClose}>
          查看全部 {journal.totalPapers.toLocaleString()} 篇论文<ExternalLink size={13} aria-hidden="true" />
        </a></Button>
        {canProcess && <Button type="button" variant="outline" size="sm" onClick={() => setProcessing(true)}>
          <Workflow size={14} aria-hidden="true" />按期刊批量处理
        </Button>}
        {canJob("METADATA") && <Button asChild variant="outline" size="sm">
          <a href={"/openalex" + params({ sourceId: journal.sourceId })} onClick={onClose}>
            <Database size={14} aria-hidden="true" />同步 OpenAlex
          </a>
        </Button>}
        {can("CAUSAL_EXTRACT") && <Button asChild variant="outline" size="sm">
          <a href={extractionEntryHref({ mode: "SOURCE_IDS", sourceId: journal.sourceId })} onClick={onClose}>
            <Sparkles size={14} aria-hidden="true" />发起因果抽取
          </a>
        </Button>}
      </div>}
    >
      {result.error ? <Notice error>{result.error.message}</Notice> :
        !detail || !journal ? <Loading label="正在读取期刊年度分布与快照状态…" /> : <div className="drawer-stack">
          <section>
            <div className="drawer-section-title">
              <h3>全文流水线分布</h3>
              <span className="muted text-xs">已发布 {pct(journal.publishedCount, journal.totalPapers)}% · 点击任一阶段筛查</span>
            </div>
            <PipelineBar journal={journal} />
            <div className="drawer-status-grid is-four">
              <a className="drawer-stat-tile" data-tone="green" href={"/papers" + params({ sourceId: journal.sourceId, status: "PUBLISHED" })} onClick={onClose}>
                <span>已发布全文</span><strong>{journal.publishedCount.toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="amber" href={"/papers" + params({ sourceId: journal.sourceId, status: "READY" })} onClick={onClose}>
                <span>待导入全文</span><strong>{journal.readyCount.toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="neutral" href={"/papers" + params({ sourceId: journal.sourceId, status: "UNPROCESSED" })} onClick={onClose}>
                <span>待解析</span><strong>{journal.unprocessedCount.toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="red" href={"/papers" + params({ sourceId: journal.sourceId, status: "FAILED" })} onClick={onClose}>
                <span>处理失败</span><strong>{journal.failedCount.toLocaleString()}</strong>
              </a>
            </div>
          </section>

          <section>
            <div className="drawer-section-title">
              <h3>因果知识抽取概况</h3>
              <span className="muted text-xs">
                完成 {pct(causalDone, journal.totalPapers)}% · 共 {journal.causalClaimCount.toLocaleString()} 条主张
              </span>
            </div>
            <CausalPipelineBar journal={journal} />
            <div className="drawer-status-grid is-four">
              <a className="drawer-stat-tile" data-tone="green" href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "EXTRACTED" })} onClick={onClose}>
                <span>已抽取(有因果)</span><strong>{journal.causalExtractedCount.toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="neutral" href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "NOT_EXTRACTED" })} onClick={onClose}>
                <span>未抽取(可抽取)</span><strong>{journal.causalNotExtractedCount.toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="amber" href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: journal.causalPendingReviewCount > 0 ? "PENDING_REVIEW" : "EXTRACTING" })} onClick={onClose}>
                <span>{journal.causalPendingReviewCount > 0 ? "待审核" : "抽取中"}</span>
                <strong>{(journal.causalPendingReviewCount > 0 ? journal.causalPendingReviewCount : journal.causalExtractingCount).toLocaleString()}</strong>
              </a>
              <a className="drawer-stat-tile" data-tone="red" href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "FAILED" })} onClick={onClose}>
                <span>抽取失败</span><strong>{journal.causalFailedCount.toLocaleString()}</strong>
              </a>
            </div>
            <div className="drawer-sub-links">
              <a href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "EXTRACTED_NO_CAUSAL" })} onClick={onClose}>
                无因果 {journal.causalNoCausalCount.toLocaleString()}
              </a>
              {journal.causalPendingReviewCount > 0 && journal.causalExtractingCount > 0 && (
                <a href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "EXTRACTING" })} onClick={onClose}>
                  抽取中 {journal.causalExtractingCount.toLocaleString()}
                </a>
              )}
              <a href={"/papers" + params({ sourceId: journal.sourceId, causalStatus: "PREREQUISITE_MISSING" })} onClick={onClose}>
                前置未完成 {prereqMissing.toLocaleString()}
              </a>
            </div>
          </section>

          <section>
            <div className="drawer-section-title">
              <h3>年度文献分布</h3>
              <span className="muted text-xs">悬停查看明细 · 点击年份柱筛查当年论文</span>
            </div>
            <YearHistogram sourceId={journal.sourceId} years={detail.years} onSelectYear={onClose} />
          </section>

          <section>
            <div className="drawer-section-title"><h3>书目关联与典藏摘要</h3></div>
            <dl className="drawer-facts">
              <div>
                <dt>OpenAlex 已关联</dt>
                <dd className="tabular-nums">
                  {journal.matchedCount.toLocaleString()} / {journal.totalPapers.toLocaleString()} 篇（{pct(journal.matchedCount, journal.totalPapers)}%）
                  <div className="mini-meter mt-1.5" aria-hidden="true">
                    <span className="mini-meter-fill is-blue" style={{ width: pct(journal.matchedCount, journal.totalPapers) + "%" }} />
                  </div>
                </dd>
              </div>
              <div>
                <dt>本地已导入 OpenAlex 作品</dt>
                <dd className="tabular-nums">
                  {detail.importedWorksCount.toLocaleString()} 条
                  {detail.importedWorksCount === 0 && <small className="block muted">尚未同步该期刊元数据快照</small>}
                </dd>
              </div>
              <div>
                <dt>已归档论文</dt>
                <dd className="tabular-nums">
                  {journal.archivedPapers > 0 ? (
                    <a className="inline-link" href={"/papers" + params({ sourceId: journal.sourceId, archived: true })} onClick={onClose}>
                      <Archive size={13} aria-hidden="true" />{journal.archivedPapers.toLocaleString()} 篇（查看归档）
                    </a>
                  ) : "0 篇"}
                </dd>
              </div>
              <div>
                <dt>最近数据更新</dt>
                <dd className="tabular-nums">{dateLabel(journal.latestUpdatedAt)}</dd>
              </div>
            </dl>
          </section>
        </div>}
    </Drawer>
    {processing && journal && <ProcessPapers ids={[]} defaultSourceId={journal.sourceId} close={() => setProcessing(false)} />}
  </>
}

function YearHistogram({ sourceId, years, onSelectYear }: { sourceId: string; years: JournalYearStats[]; onSelectYear: () => void }) {
  const [hovered, setHovered] = useState<JournalYearStats | null>(null)
  if (years.length === 0) {
    return <div className="info-box"><BookMarked size={15} aria-hidden="true" /><span>该期刊暂无带年份登记的论文。</span></div>
  }
  const maxTotal = Math.max(...years.map((y) => y.total), 1)
  const focus = hovered ?? years[years.length - 1]

  return <div className="year-chart-box">
    <div className="year-chart-readout">
      <strong className="tabular-nums">{focus.year} 年</strong>
      <span className="tabular-nums">
        共 {focus.total} 篇 · 已发布 {focus.published} · 待处理 {focus.ready + focus.unprocessed}
        {focus.failed > 0 && <> · <span className="text-danger">失败 {focus.failed}</span></>} · OpenAlex 关联 {focus.matched}
      </span>
    </div>
    <div className="year-chart-bars" role="list" aria-label="按发表年份分布">
      {years.map((item) => {
        const totalHeight = Math.max(8, Math.round((item.total / maxTotal) * 100))
        const pubPct = (item.published / Math.max(item.total, 1)) * 100
        const readyPct = (item.ready / Math.max(item.total, 1)) * 100
        const failedPct = (item.failed / Math.max(item.total, 1)) * 100
        const restPct = Math.max(0, 100 - pubPct - readyPct - failedPct)
        const isActive = focus.year === item.year
        return <a
          key={item.year}
          role="listitem"
          href={"/papers" + params({ sourceId, yearFrom: item.year, yearTo: item.year })}
          className={"year-bar-col" + (isActive ? " is-active" : "")}
          aria-label={`${item.year} 年共 ${item.total} 篇，已发布 ${item.published} 篇`}
          onMouseEnter={() => setHovered(item)}
          onFocus={() => setHovered(item)}
          onClick={onSelectYear}
        >
          <span className="year-bar-track">
            <span className="year-bar-stack" style={{ height: totalHeight + "%" }}>
              {failedPct > 0 && <span className="pipeline-seg seg-failed" style={{ height: failedPct + "%" }} />}
              {restPct > 0 && <span className="pipeline-seg seg-unprocessed" style={{ height: restPct + "%" }} />}
              {readyPct > 0 && <span className="pipeline-seg seg-ready" style={{ height: readyPct + "%" }} />}
              {pubPct > 0 && <span className="pipeline-seg seg-published" style={{ height: pubPct + "%" }} />}
            </span>
          </span>
          <span className="year-bar-label">{String(item.year).slice(-2)}</span>
        </a>
      })}
    </div>
  </div>
}

function pct(part: number, total: number) {
  return total > 0 ? Math.round((part / total) * 100) : 0
}

function yearLabel(min: number | null, max: number | null) {
  if (!min && !max) return "—"
  if (min === max) return String(min)
  return `${min ?? "?"}–${max ?? "?"}`
}
