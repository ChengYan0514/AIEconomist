import { BarChart3, Filter, RefreshCw, RotateCcw, Sparkles } from "lucide-react"
import { useEffect, useMemo, useState, type FormEvent } from "react"
import { api, dateLabel, errorMessage, useQuery } from "../api"
import { Empty, Loading, Notice } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { navigate, useLocation } from "../router"
import { CausalForceGraph, EdgeTable, StwAutocomplete } from "./components"
import {
  relationPath, type CausalGraphData, type CausalGraphSummary, type CausalRefreshResult,
} from "./types"

const DEFAULTS = { minRecordCount: 20, minDiversity: 5, maxNodes: 300, maxEdges: 500 }

interface NodeFilters {
  q: string
  minRecordCount: number
  minDiversity: number
  maxNodes: number
  maxEdges: number
  subfields: string[]
}

function readFilters(search: string): NodeFilters {
  const sp = new URLSearchParams(search)
  return {
    q: sp.get("q") ?? "",
    minRecordCount: Number(sp.get("minRecordCount") || DEFAULTS.minRecordCount),
    minDiversity: Number(sp.get("minDiversity") || DEFAULTS.minDiversity),
    maxNodes: Number(sp.get("maxNodes") || DEFAULTS.maxNodes),
    maxEdges: Number(sp.get("maxEdges") || DEFAULTS.maxEdges),
    subfields: sp.getAll("subfields").filter(Boolean),
  }
}

function buildSearch(filters: NodeFilters) {
  const sp = new URLSearchParams()
  sp.set("minRecordCount", String(filters.minRecordCount))
  sp.set("minDiversity", String(filters.minDiversity))
  sp.set("maxNodes", String(filters.maxNodes))
  sp.set("maxEdges", String(filters.maxEdges))
  if (filters.q.trim()) sp.set("q", filters.q.trim())
  filters.subfields.forEach((sf) => sp.append("subfields", sf))
  return sp.toString()
}

export function CausalGraphPage() {
  const loc = useLocation()
  const { can } = usePermissions()
  const canRefresh = can("SYSTEM_MANAGE") || can("CAUSAL_EXTRACT")
  const active = useMemo(() => readFilters(loc.search), [loc.search])
  const [draft, setDraft] = useState<NodeFilters>(active)
  const [mode, setMode] = useState<"variable" | "relation">("variable")
  const [cause, setCause] = useState("")
  const [effect, setEffect] = useState("")
  const [refreshing, setRefreshing] = useState(false)
  const [notice, setNotice] = useState("")
  const [actionError, setActionError] = useState("")

  useEffect(() => { setDraft(active) }, [active])

  const graphQuery = "/causal/graph?" + buildSearch(active)
  const summaryRes = useQuery<CausalGraphSummary>("/causal/summary")
  const graphRes = useQuery<CausalGraphData>(graphQuery)

  async function refreshIndex() {
    setRefreshing(true); setActionError(""); setNotice("")
    try {
      const res = await api<CausalRefreshResult>("/causal/read-model/refresh", { method: "POST" })
      setNotice(`图谱预聚合索引已刷新（修订 ${res.revision}，共 ${res.indexedEdges.toLocaleString()} 条因果关系）。`)
      summaryRes.reload()
      graphRes.reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setRefreshing(false)
    }
  }

  function submitNodeSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    navigate("/causal-graph?" + buildSearch(draft))
  }

  function submitRelationSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (cause.trim() && effect.trim()) navigate(relationPath(cause.trim(), effect.trim()))
  }

  function relaxThresholds() {
    const next = { ...draft, minRecordCount: 1, minDiversity: 1 }
    setDraft(next)
    navigate("/causal-graph?" + buildSearch(next))
  }

  function toggleSubfield(sf: string) {
    setDraft((cur) => ({
      ...cur,
      subfields: cur.subfields.includes(sf) ? cur.subfields.filter((x) => x !== sf) : [...cur.subfields, sf],
    }))
  }

  const summary = summaryRes.data
  const graph = graphRes.data
  const failure = summaryRes.error ?? graphRes.error

  return <>
    <SectionHeader
      title="因果图谱"
      description={summary
        ? `基于预聚合索引探索经济学文献因果网络 · 数据修订 ${summary.version.label} · 索引更新 ${dateLabel(summary.version.refreshedAt)}`
        : "面向经济学实证文献的因果网络探索、变量下钻与原文证据溯源。"}
      actions={<>
        {canRefresh && <Button type="button" variant="outline" disabled={refreshing} onClick={refreshIndex}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} aria-hidden="true" />
          {refreshing ? "刷新索引…" : "刷新图谱索引"}
        </Button>}
        <Button asChild variant="outline"><a href="/causal-fields"><BarChart3 size={15} aria-hidden="true" />领域分析</a></Button>
        {can("CAUSAL_EXTRACT") && <Button asChild><a href="/causal-extraction"><Sparkles size={15} aria-hidden="true" />因果抽取</a></Button>}
      </>}
    />
    {notice && <div className="mb-4"><Notice>{notice}</Notice></div>}
    {actionError && <div className="mb-4"><Notice error>{actionError}</Notice></div>}
    {failure ? <Notice error>{failure.message}</Notice> : !summary || !graph ? <Loading label="正在载入因果图谱…" /> : <>
      <section className="panel causal-kpi-strip" aria-label="因果图谱概览统计">
        <div className="causal-kpi-cell"><span>因果主张</span><strong>{summary.overview.totalClaimRecords.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>因果关系</span><strong>{summary.overview.totalStandardClaims.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>覆盖论文</span><strong>{summary.overview.totalPapers.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>因果变量</span><strong>{summary.overview.totalNodes.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell is-accent"><span>当前节点</span><strong>{graph.nodes.length.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell is-accent"><span>当前边</span><strong>{graph.edges.length.toLocaleString()}</strong></div>
      </section>

      <section className="panel causal-search-panel">
        <div className="causal-search-tabs-bar">
          <div className="underline-tabs" role="group" aria-label="图谱检索模式">
            <button type="button" aria-pressed={mode === "variable"} onClick={() => setMode("variable")}>按变量探索网络</button>
            <button type="button" aria-pressed={mode === "relation"} onClick={() => setMode("relation")}>按因果关系检索</button>
          </div>
          <span className="muted text-sm">{mode === "variable" ? "按变量、验证度与学科子领域筛选子图" : "直接定位特定原因变量到结果变量的证据记录"}</span>
        </div>

        {mode === "variable" ? <form className="causal-filter-form" onSubmit={submitNodeSearch}>
          <div className="causal-filter-grid">
            <label>
              <span>变量关键词（STW 词表自动补全）</span>
              <StwAutocomplete ariaLabel="变量关键词" placeholder="如：Monetary Policy, Inflation" value={draft.q}
                onChange={(q) => setDraft((c) => ({ ...c, q }))} />
            </label>
            <label>
              <span>最小主张记录数</span>
              <input className="field" type="number" min={1} max={1000000} required value={draft.minRecordCount}
                onChange={(e) => setDraft((c) => ({ ...c, minRecordCount: Number(e.target.value || 1) }))} />
            </label>
            <label>
              <span>最小识别方法数</span>
              <input className="field" type="number" min={1} max={1000} required value={draft.minDiversity}
                onChange={(e) => setDraft((c) => ({ ...c, minDiversity: Number(e.target.value || 1) }))} />
            </label>
            <label>
              <span>预览最大节点数</span>
              <input className="field" type="number" min={1} max={1000} required value={draft.maxNodes}
                onChange={(e) => setDraft((c) => ({ ...c, maxNodes: Number(e.target.value || 1) }))} />
            </label>
            <label>
              <span>预览最大边数</span>
              <input className="field" type="number" min={1} max={2000} required value={draft.maxEdges}
                onChange={(e) => setDraft((c) => ({ ...c, maxEdges: Number(e.target.value || 1) }))} />
            </label>
          </div>
          {summary.subfields.length > 0 && <div className="causal-subfield-picker">
            <span className="causal-subfield-label">学科子领域筛选（可多选）</span>
            <div className="causal-subfield-chips">
              {summary.subfields.map((sf) => {
                const selected = draft.subfields.includes(sf)
                return <button key={sf} type="button" aria-pressed={selected}
                  className={"causal-chip-btn" + (selected ? " is-selected" : "")}
                  onClick={() => toggleSubfield(sf)}>{sf}</button>
              })}
            </div>
          </div>}
          <div className="causal-filter-actions">
            <Button type="button" variant="outline" onClick={() => {
              const reset = { ...DEFAULTS, q: "", subfields: [] }
              setDraft(reset)
              navigate("/causal-graph?" + buildSearch(reset))
            }}><RotateCcw size={14} aria-hidden="true" />重置默认</Button>
            <Button type="submit"><Filter size={14} aria-hidden="true" />查询子图</Button>
          </div>
        </form> : <form className="causal-filter-form" onSubmit={submitRelationSearch}>
          <div className="causal-relation-search-grid">
            <label>
              <span>原因变量（Cause）</span>
              <StwAutocomplete ariaLabel="原因变量" placeholder="如：Fiscal Policy, Minimum Wage" value={cause} onChange={setCause} />
            </label>
            <label>
              <span>结果变量（Effect）</span>
              <StwAutocomplete ariaLabel="结果变量" placeholder="如：Economic Growth, Inflation" value={effect} onChange={setEffect} />
            </label>
          </div>
          <div className="causal-filter-actions">
            <Button type="button" variant="outline" onClick={() => { setCause(""); setEffect("") }}><RotateCcw size={14} aria-hidden="true" />重置</Button>
            <Button type="submit" disabled={!cause.trim() || !effect.trim()}><Filter size={14} aria-hidden="true" />查看因果关系证据</Button>
          </div>
        </form>}
      </section>

      {graph.edges.length === 0 ? <section className="panel">
        <Empty title="当前筛选条件下没有命中的因果关系">
          {summary.overview.totalStandardClaims > 0 && (active.minRecordCount > 1 || active.minDiversity > 1)
            ? <div className="mt-2">
                <p className="m-0 mb-3">全库共有 {summary.overview.totalStandardClaims.toLocaleString()} 条因果关系，当前最小记录数/方法数阈值可能偏高。</p>
                <Button type="button" variant="outline" onClick={relaxThresholds}>放宽阈值（最小记录=1、方法=1）查看全部</Button>
              </div>
            : "调整变量关键词或子领域筛选条件后重试。"}
        </Empty>
      </section> : <>
        <CausalForceGraph data={graph} height={600} />
        <section className="panel mt-5">
          <div className="panel-bar">
            <strong>当前网络高频因果关系（Top 20）</strong>
            <span className="muted text-sm tabular-nums">共展示 {Math.min(20, graph.edges.length)} / {graph.edges.length} 条关系</span>
          </div>
          <EdgeTable edges={graph.edges.slice(0, 20)} />
        </section>
      </>}
    </>}
  </>
}
