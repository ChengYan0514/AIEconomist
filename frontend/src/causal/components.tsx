import { ChevronDown, ChevronRight, Layers, Palette, Split } from "lucide-react"
import { Fragment, useEffect, useId, useMemo, useRef, useState, type ComponentType } from "react"
import { api, paperPath } from "../api"
import { navigate } from "../router"
import {
  paperClaimsPath, relationPath, variablePath,
  type CausalClaim, type CausalGraphData, type CausalGraphEdge, type SignCategory,
} from "./types"

const SIGN_META: Record<SignCategory, { label: string; color: string; tone: string }> = {
  positive: { label: "正向效应", color: "#1f6b47", tone: "green" },
  negative: { label: "负向效应", color: "#a62626", tone: "red" },
  null: { label: "无显著效应", color: "#64748b", tone: "neutral" },
  mixed: { label: "混合方向", color: "#9a5b13", tone: "amber" },
}

export function signColor(category?: string | null) {
  return SIGN_META[(category as SignCategory) ?? "mixed"]?.color ?? SIGN_META.mixed.color
}

export function SignBadge({ value }: { value?: string | null }) {
  const info = SIGN_META[(value as SignCategory) ?? "mixed"] ?? SIGN_META.mixed
  return <span className={"status-chip status-" + info.tone}>
    <span className="status-dot" aria-hidden="true" />
    {info.label}
  </span>
}

export function MethodBadge({ method, other }: { method?: string | null; other?: string | null }) {
  const label = method?.trim() || "未标注方法"
  return <span className="causal-method-cell" title={other || undefined}>
    <span className="role-pill">{label}</span>
    {other && <small className="block muted mt-1">{other}</small>}
  </span>
}

export function StwAutocomplete({
  value, onChange, placeholder, ariaLabel, id,
}: {
  value: string
  onChange: (next: string) => void
  placeholder: string
  ariaLabel: string
  id?: string
}) {
  const listId = useId()
  const [options, setOptions] = useState<string[]>([])
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const q = value.trim()
    if (!q) { setOptions([]); return }
    let active = true
    const timer = window.setTimeout(() => {
      api<string[]>("/causal/search/terms?q=" + encodeURIComponent(q) + "&limit=12")
        .then((terms) => { if (active) setOptions(terms) })
        .catch(() => { if (active) setOptions([]) })
    }, 180)
    return () => { active = false; window.clearTimeout(timer) }
  }, [value])

  return <div className="causal-autocomplete">
    <input
      id={id}
      className="field"
      type="text"
      role="combobox"
      aria-label={ariaLabel}
      aria-expanded={open && options.length > 0}
      aria-controls={listId}
      placeholder={placeholder}
      value={value}
      onFocus={() => setOpen(true)}
      onBlur={() => window.setTimeout(() => setOpen(false), 160)}
      onChange={(e) => { onChange(e.target.value); setOpen(true) }}
    />
    {open && options.length > 0 && <ul id={listId} role="listbox" className="causal-autocomplete-list">
      {options.map((term) => <li key={term} role="option">
        <button type="button" onMouseDown={(e) => { e.preventDefault(); onChange(term); setOpen(false) }}>
          {term}
        </button>
      </li>)}
    </ul>}
  </div>
}

export function EdgeTable({ edges, emptyText = "暂无因果关系记录" }: { edges: CausalGraphEdge[]; emptyText?: string }) {
  if (!edges.length) return <p className="muted p-5 m-0">{emptyText}</p>
  return <div className="table-scroll" tabIndex={0} role="region" aria-label="因果关系列表">
    <table className="data-table">
      <thead><tr>
        <th scope="col">因果关系</th>
        <th scope="col">主张数</th>
        <th scope="col">论文数</th>
        <th scope="col">方法数</th>
        <th scope="col">主导方向</th>
        <th scope="col">详情</th>
      </tr></thead>
      <tbody>{edges.map((edge) => <tr key={`${edge.claimId}-${edge.source}-${edge.target}`}>
        <td className="title-cell">
          <a href={variablePath(edge.source)}>{edge.source}</a>
          <span className="muted mx-1.5" aria-hidden="true">→</span>
          <a href={variablePath(edge.target)}>{edge.target}</a>
        </td>
        <td className="tabular-nums font-medium">{edge.recordCount.toLocaleString()}</td>
        <td className="tabular-nums">{edge.paperCount.toLocaleString()}</td>
        <td className="tabular-nums">{edge.diversity}</td>
        <td><SignBadge value={edge.dominantSignCategory} /></td>
        <td><a className="inline-link" href={relationPath(edge.source, edge.target)}>查看证据</a></td>
      </tr>)}</tbody>
    </table>
  </div>
}

export function EvidenceTable({ claims, showRelation = false }: { claims: CausalClaim[]; showRelation?: boolean }) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  if (!claims.length) return <p className="muted p-5 m-0">暂无因果主张证据记录。</p>

  function toggle(id: number) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return <div className="table-scroll" tabIndex={0} role="region" aria-label="因果主张证据表">
    <table className="data-table causal-evidence-table">
      <thead><tr>
        <th scope="col" className="w-9"><span className="sr-only">展开</span></th>
        <th scope="col">{showRelation ? "因果关系" : "来源论文"}</th>
        <th scope="col">方向</th>
        <th scope="col">识别方法</th>
        <th scope="col">显著性</th>
        <th scope="col">{showRelation ? "原始主张" : "学科子领域"}</th>
      </tr></thead>
      <tbody>{claims.map((claim) => {
        const isOpen = expanded.has(claim.recordId)
        const isAux = showRelation && claim.isMainContribution === false
        return <Fragment key={claim.recordId}>
          <tr className={isAux ? "causal-row-auxiliary" : "causal-row-main"}>
            <td className="w-9">
              <button type="button" className="causal-expand-btn" aria-expanded={isOpen}
                aria-label={isOpen ? "收起原文证据" : "展开原文证据"} onClick={() => toggle(claim.recordId)}>
                {isOpen ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
              </button>
            </td>
            <td className="title-cell">
              {showRelation ? <>
                <a className="font-medium inline-link" href={relationPath(claim.causeStandard, claim.effectStandard)}>
                  {claim.causeStandard} → {claim.effectStandard}
                </a>
                {claim.isMainContribution && <span className="nav-badge ml-2">核心发现</span>}
              </> : <>
                <a className="font-medium inline-link" href={paperClaimsPath(claim.workId)}>
                  {claim.title || claim.workId}
                </a>
                <small className="block muted">
                  {[claim.sourceName || claim.sourceId, claim.publicationYear].filter(Boolean).join(" · ") || claim.workId}
                  {claim.paperId && <> · <a className="inline-link" href={paperPath(claim.paperId)}>本地论文</a></>}
                </small>
              </>}
            </td>
            <td><SignBadge value={claim.signCategory} /></td>
            <td><MethodBadge method={claim.causalInferenceMethod} other={claim.evidenceMethodOtherDescription} /></td>
            <td className="text-sm">{claim.statisticalSignificance || "—"}</td>
            <td className="text-sm">
              {showRelation ? <span className="causal-claim-snippet">{claim.claim}</span> : (claim.subfieldName || "未标注")}
            </td>
          </tr>
          {isOpen && <tr className="causal-drawer-row">
            <td colSpan={6} className="p-0">
              <div className="causal-evidence-drawer">
                <div className="causal-evidence-quote">
                  <strong>原文证据</strong>
                  <p>{claim.evidence || "暂无原文证据摘录"}</p>
                </div>
                <dl className="causal-evidence-meta">
                  <div><dt>原始主张</dt><dd>{claim.claim || "—"}</dd></div>
                  <div><dt>原始变量</dt><dd>{claim.cause} → {claim.effect}</dd></div>
                  <div><dt>映射相似度</dt><dd className="mono">{scoreLabel(claim.causeScore)} / {scoreLabel(claim.effectScore)}</dd></div>
                  {claim.sourcesOfExogenousVariation && <div><dt>外生变异来源</dt><dd>{claim.sourcesOfExogenousVariation}</dd></div>}
                  {claim.levelOfTentativeness && <div><dt>结论确定性</dt><dd>{claim.levelOfTentativeness}</dd></div>}
                </dl>
              </div>
            </td>
          </tr>}
        </Fragment>
      })}</tbody>
    </table>
  </div>
}

function scoreLabel(score: number | null) {
  return score === null || score === undefined ? "—" : score.toFixed(3)
}

type ForceGraphComponent = ComponentType<Record<string, unknown>>

function shortLabel(value: string, max = 26) {
  return value.length > max ? value.slice(0, max - 1) + "…" : value
}

export function CausalForceGraph({
  data, height = 560, highlightNode,
}: {
  data: CausalGraphData
  height?: number
  highlightNode?: string
}) {
  const boxRef = useRef<HTMLDivElement>(null)
  const graphRef = useRef<any>(null)
  const [width, setWidth] = useState(880)
  const [Graph, setGraph] = useState<ForceGraphComponent>()
  const [legendOpen, setLegendOpen] = useState(true)

  useEffect(() => {
    let alive = true
    import("react-force-graph-2d").then((mod) => {
      if (alive) setGraph(() => mod.default as ForceGraphComponent)
    })
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (!boxRef.current) return
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) setWidth(Math.max(300, Math.floor(entry.contentRect.width)))
    })
    observer.observe(boxRef.current)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!graphRef.current) return
    graphRef.current.d3Force("charge")?.strength(-230)
    graphRef.current.d3Force("link")?.distance((link: any) => Math.max(82, 168 - Number(link.width) * 10))
    graphRef.current.d3VelocityDecay?.(0.35)
  }, [Graph, data])

  const graphData = useMemo(() => ({
    nodes: data.nodes.map((node) => ({
      id: node.id,
      label: node.label,
      val: Math.min(22, Math.max(5, Math.sqrt(node.occurrences || 1) * 0.78)),
      subfield: node.dominantSubfield,
    })),
    links: data.edges.map((edge) => ({
      source: edge.source,
      target: edge.target,
      claimId: edge.claimId,
      width: Math.max(1.5, Math.log(1 + edge.recordCount) * 1.25),
      color: signColor(edge.dominantSignCategory),
      dashed: edge.disagreement > 0.4,
    })),
  }), [data])

  return <div ref={boxRef} className="panel causal-graph-stage" style={{ height }}>
    {Graph ? <Graph
      ref={graphRef}
      graphData={graphData}
      width={width}
      height={height}
      backgroundColor="#faf8f4"
      nodeRelSize={5}
      nodeLabel={(node: any) => `${node.label}${node.subfield && node.subfield !== "未标注" ? ` · ${node.subfield}` : ""}`}
      nodeCanvasObject={(node: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
        const radius = node.val * 1.12
        const active = node.id === highlightNode
        const fontSize = Math.max(9, Math.min(13.5, 11.5 / Math.max(globalScale, 0.9)))
        const text = shortLabel(node.label, Math.max(12, Math.floor(24 / Math.max(globalScale, 0.9))))
        ctx.beginPath()
        ctx.arc(node.x, node.y, radius, 0, 2 * Math.PI)
        ctx.fillStyle = active ? "#a62626" : "#1e4477"
        ctx.fill()
        if (active) {
          ctx.lineWidth = 2.5
          ctx.strokeStyle = "#f7f5f0"
          ctx.stroke()
        }
        ctx.font = `600 ${fontSize}px Inter, "PingFang SC", sans-serif`
        ctx.textAlign = "center"
        ctx.textBaseline = "middle"
        const tw = ctx.measureText(text).width
        const ly = node.y - radius - fontSize * 0.95
        ctx.fillStyle = "rgba(255, 255, 255, 0.92)"
        ctx.fillRect(node.x - tw / 2 - 5, ly - fontSize / 2 - 2.5, tw + 10, fontSize + 5)
        ctx.fillStyle = "#181a1e"
        ctx.fillText(text, node.x, ly)
      }}
      linkColor={(link: any) => link.color}
      linkWidth={(link: any) => link.width}
      linkLineDash={(link: any) => (link.dashed ? [6, 4] : null)}
      linkDirectionalArrowLength={8}
      linkDirectionalArrowRelPos={0.88}
      linkDirectionalArrowColor={(link: any) => link.color}
      cooldownTicks={140}
      onNodeClick={(node: any) => navigate(variablePath(String(node.id)))}
      onLinkClick={(link: any) => {
        const s = String(link.source.id ?? link.source)
        const t = String(link.target.id ?? link.target)
        navigate(relationPath(s, t))
      }}
    /> : <div className="causal-graph-loading">正在渲染因果网络画布…</div>}
    <aside className="causal-legend" aria-label="图谱线色图例">
      <button type="button" className="causal-legend-toggle" aria-expanded={legendOpen}
        onClick={() => setLegendOpen((v) => !v)}>
        <Palette size={14} aria-hidden="true" />
        <strong>线色与结构图例</strong>
        <span className="muted text-xs ml-auto">{legendOpen ? "收起" : "展开"}</span>
      </button>
      {legendOpen && <div className="causal-legend-body">
        <div className="causal-legend-grid">
          {(Object.entries(SIGN_META) as [SignCategory, typeof SIGN_META.positive][]).map(([key, item]) => (
            <div key={key} className="causal-legend-item">
              <span className="causal-legend-line" style={{ background: item.color }} />
              <span>{item.label}</span>
            </div>
          ))}
        </div>
        <div className="causal-legend-notes">
          <span><Split size={12} aria-hidden="true" />虚线表示方向分歧度超过 40%</span>
          <span><Layers size={12} aria-hidden="true" />边宽表示重复验证程度，点击节点或边下钻</span>
        </div>
      </div>}
    </aside>
  </div>
}
