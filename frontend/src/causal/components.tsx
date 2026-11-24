import { ChevronDown, ChevronRight, HelpCircle, Info, Layers, Palette, Search, Split, X } from "lucide-react"
import { Fragment, useEffect, useId, useMemo, useRef, useState, type ComponentType, type KeyboardEvent, type ReactNode } from "react"
import { api, paperPath } from "../api"
import { navigate } from "../router"
import {
  paperClaimsPath, relationPath, variablePath,
  type CausalClaim, type CausalGraphData, type CausalGraphEdge, type CausalNodeSearchHit, type SignCategory,
} from "./types"

const SIGN_META: Record<SignCategory, { label: string; legend: string; color: string; tone: string }> = {
  positive: { label: "正向", legend: "正向因果效应", color: "#1f6b47", tone: "green" },
  negative: { label: "负向", legend: "负向因果效应", color: "#a62626", tone: "red" },
  null: { label: "无显著影响", legend: "无显著效应", color: "#64748b", tone: "neutral" },
  mixed: { label: "混合/不确定", legend: "混合因果符号", color: "#9a5b13", tone: "amber" },
}

export function signColor(category?: string | null) {
  return SIGN_META[(category as SignCategory) ?? "mixed"]?.color ?? SIGN_META.mixed.color
}

export function HelpTip({ tip, label, placement = "top" }: { tip: string; label?: string; placement?: "top" | "bottom" }) {
  const id = useId()
  return <span className={"causal-tip-wrap" + (placement === "bottom" ? " is-bottom" : "")}>
    <button type="button" className="causal-tip-trigger" aria-label={label ?? tip} aria-describedby={id}
      onClick={(e) => e.preventDefault()}>
      <HelpCircle size={13.5} aria-hidden="true" />
    </button>
    <span id={id} role="tooltip" className="causal-tip-bubble">{tip}</span>
  </span>
}

export function TooltipText({
  tip, children, underlined = true, placement = "top",
}: {
  tip: string
  children: ReactNode
  underlined?: boolean
  placement?: "top" | "bottom"
}) {
  const id = useId()
  return <span className={"causal-tip-wrap" + (placement === "bottom" ? " is-bottom" : "")}>
    <span tabIndex={0} aria-describedby={id} className={underlined ? "causal-tip-text" : undefined}>{children}</span>
    <span id={id} role="tooltip" className="causal-tip-bubble">{tip}</span>
  </span>
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
  const desc = other?.trim()
  const tipId = useId()
  return <span className="causal-method-cell">
    <span className="role-pill">{label}</span>
    {desc && <span className="causal-tip-wrap">
      <button type="button" className="causal-tip-trigger is-info" aria-label={`方法描述：${desc}`} aria-describedby={tipId}>
        <Info size={14} aria-hidden="true" />
      </button>
      <span id={tipId} role="tooltip" className="causal-tip-bubble">
        <strong>方法描述：</strong>{desc}
      </span>
    </span>}
  </span>
}

function HighlightMatch({ text, query }: { text: string; query: string }) {
  const q = query.trim()
  if (!q) return <>{text}</>
  const idx = text.toLowerCase().indexOf(q.toLowerCase())
  if (idx < 0) return <>{text}</>
  return <>
    {text.slice(0, idx)}
    <mark className="causal-match-mark">{text.slice(idx, idx + q.length)}</mark>
    {text.slice(idx + q.length)}
  </>
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
  const wrapRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const skipNextFetchRef = useRef(false)
  const [options, setOptions] = useState<CausalNodeSearchHit[]>([])
  const [loading, setLoading] = useState(false)
  const [fetchedQuery, setFetchedQuery] = useState("")
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)

  useEffect(() => {
    function onDocMouseDown(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onDocMouseDown)
    return () => document.removeEventListener("mousedown", onDocMouseDown)
  }, [])

  useEffect(() => {
    const q = value.trim()
    if (!q) {
      setOptions([])
      setLoading(false)
      setFetchedQuery("")
      setActiveIndex(-1)
      return
    }
    if (skipNextFetchRef.current) {
      skipNextFetchRef.current = false
      return
    }
    let active = true
    setLoading(true)
    const timer = window.setTimeout(() => {
      api<CausalNodeSearchHit[]>("/causal/search/variables?q=" + encodeURIComponent(q) + "&limit=12")
        .then((hits) => {
          if (!active) return
          setOptions(hits)
          setFetchedQuery(q)
          setActiveIndex(-1)
        })
        .catch(() => {
          if (!active) return
          setOptions([])
          setFetchedQuery(q)
          setActiveIndex(-1)
        })
        .finally(() => { if (active) setLoading(false) })
    }, 180)
    return () => { active = false; window.clearTimeout(timer) }
  }, [value])

  function pick(variable: string) {
    skipNextFetchRef.current = true
    onChange(variable)
    setOpen(false)
    setActiveIndex(-1)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      if (open) { event.preventDefault(); setOpen(false); setActiveIndex(-1) }
      return
    }
    if (event.key === "ArrowDown") {
      if (!options.length) return
      event.preventDefault()
      setOpen(true)
      setActiveIndex((cur) => (cur + 1) % options.length)
      return
    }
    if (event.key === "ArrowUp") {
      if (!options.length) return
      event.preventDefault()
      setOpen(true)
      setActiveIndex((cur) => (cur <= 0 ? options.length - 1 : cur - 1))
      return
    }
    if (event.key === "Enter" && open && activeIndex >= 0 && options[activeIndex]) {
      event.preventDefault()
      pick(options[activeIndex].variable)
    }
  }

  const trimmed = value.trim()
  const showDropdown = open && trimmed.length > 0 && (loading || options.length > 0 || fetchedQuery === trimmed)

  return <div ref={wrapRef} className="causal-autocomplete">
    <div className="causal-autocomplete-input-wrap">
      <input
        ref={inputRef}
        id={id}
        className={"field" + (value ? " has-clear" : "")}
        type="text"
        autoComplete="off"
        role="combobox"
        aria-autocomplete="list"
        aria-label={ariaLabel}
        aria-expanded={showDropdown}
        aria-controls={listId}
        aria-activedescendant={showDropdown && activeIndex >= 0 ? `${listId}-opt-${activeIndex}` : undefined}
        placeholder={placeholder}
        value={value}
        onFocus={() => { if (trimmed) setOpen(true) }}
        onKeyDown={handleKeyDown}
        onChange={(e) => { onChange(e.target.value); setOpen(true) }}
      />
      {value && <button
        type="button"
        className="causal-input-clear"
        aria-label={`清空${ariaLabel}`}
        onClick={() => { onChange(""); setOptions([]); setOpen(false); inputRef.current?.focus() }}
      ><X size={14} aria-hidden="true" /></button>}
    </div>
    {showDropdown && <ul id={listId} role="listbox" aria-label={`${ariaLabel}候选项`} className="causal-autocomplete-list">
      {loading && options.length === 0 ? (
        <li className="causal-autocomplete-empty" role="status">正在检索图谱变量…</li>
      ) : options.length === 0 ? (
        <li className="causal-autocomplete-empty">未匹配到已有节点，可直接按关键词模糊查询</li>
      ) : options.map((item, idx) => <li
        key={item.variable}
        id={`${listId}-opt-${idx}`}
        role="option"
        aria-selected={idx === activeIndex}
      >
        <button
          type="button"
          className={idx === activeIndex ? "is-active" : undefined}
          onMouseEnter={() => setActiveIndex(idx)}
          onMouseDown={(e) => { e.preventDefault(); pick(item.variable) }}
        >
          <span className="causal-autocomplete-term"><HighlightMatch text={item.variable} query={trimmed} /></span>
          <span className="causal-autocomplete-count tabular-nums">{item.occurrences.toLocaleString()} 条</span>
        </button>
      </li>)}
    </ul>}
  </div>
}

export function SubfieldMultiSelect({
  options, selected, onChange, id,
}: {
  options: string[]
  selected: string[]
  onChange: (next: string[]) => void
  id?: string
}) {
  const popoverId = useId()
  const wrapRef = useRef<HTMLDivElement>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")

  useEffect(() => {
    if (!open) { setQuery(""); return }
    window.requestAnimationFrame(() => searchRef.current?.focus())
  }, [open])

  useEffect(() => {
    function onDocMouseDown(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false)
    }
    function onKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") setOpen(false)
    }
    document.addEventListener("mousedown", onDocMouseDown)
    document.addEventListener("keydown", onKeyDown)
    return () => {
      document.removeEventListener("mousedown", onDocMouseDown)
      document.removeEventListener("keydown", onKeyDown)
    }
  }, [])

  const selectedSet = useMemo(() => new Set(selected), [selected])
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? options.filter((sf) => sf.toLowerCase().includes(q)) : options
  }, [options, query])

  function toggle(sf: string) {
    onChange(selectedSet.has(sf) ? selected.filter((item) => item !== sf) : [...selected, sf])
  }

  const visibleTags = selected.slice(0, 3)
  const hiddenCount = Math.max(0, selected.length - visibleTags.length)

  return <div ref={wrapRef} className="causal-multiselect">
    <div
      className={"field causal-multiselect-trigger" + (open ? " is-open" : "")}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest("button")) return
        setOpen((v) => !v)
      }}
    >
      {visibleTags.length > 0 && <div className="causal-multiselect-tags">
        {visibleTags.map((sf) => <span key={sf} className="causal-selected-tag">
          <span className="causal-selected-tag-text" title={sf}>{sf}</span>
          <button
            type="button"
            className="causal-selected-tag-remove"
            aria-label={`移除领域 ${sf}`}
            onClick={() => toggle(sf)}
          ><X size={12} aria-hidden="true" /></button>
        </span>)}
        {hiddenCount > 0 && <span className="causal-selected-more">+{hiddenCount}</span>}
      </div>}
      <button
        id={id}
        type="button"
        className="causal-multiselect-toggle"
        aria-expanded={open}
        aria-controls={popoverId}
        aria-label={selected.length > 0 ? `领域筛选，已选 ${selected.length} 项` : `选择领域，共 ${options.length} 个`}
        onClick={() => setOpen((v) => !v)}
      >
        {selected.length === 0
          ? <span className="causal-multiselect-placeholder">全部领域（共 {options.length} 个）</span>
          : <span className="causal-multiselect-placeholder is-compact">继续选择…</span>}
      </button>
      {selected.length > 0 && <button
        type="button"
        className="causal-multiselect-clear"
        aria-label="清空已选领域"
        onClick={() => onChange([])}
      ><X size={14} aria-hidden="true" /></button>}
      <ChevronDown size={15} className={"causal-multiselect-chevron" + (open ? " is-open" : "")} aria-hidden="true" />
    </div>

    {open && <div id={popoverId} className="causal-multiselect-popover" role="dialog" aria-label="选择领域">
      <div className="causal-multiselect-search">
        <Search size={14} aria-hidden="true" />
        <input
          ref={searchRef}
          type="search"
          className="field"
          aria-label="搜索领域"
          placeholder="搜索领域名称…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <div className="causal-multiselect-bar">
        <span className="muted text-xs tabular-nums">已选 {selected.length} / 共 {options.length} 项</span>
        {selected.length > 0 && <button type="button" className="causal-link-btn" onClick={() => onChange([])}>清空已选</button>}
      </div>
      <div className="causal-multiselect-list" role="group" aria-label="领域选项列表">
        {filtered.length === 0 ? (
          <div className="causal-autocomplete-empty">未找到匹配的领域</div>
        ) : filtered.map((sf) => {
          const checked = selectedSet.has(sf)
          return <label key={sf} className={"causal-multiselect-option" + (checked ? " is-selected" : "")}>
            <input type="checkbox" checked={checked} onChange={() => toggle(sf)} />
            <span><HighlightMatch text={sf} query={query} /></span>
          </label>
        })}
      </div>
    </div>}
  </div>
}

export function EdgeTable({
  edges, emptyText = "暂无因果关系记录", signHeader = "主导方向",
}: {
  edges: CausalGraphEdge[]
  emptyText?: string
  signHeader?: string
}) {
  if (!edges.length) return <p className="muted p-5 m-0">{emptyText}</p>
  return <div className="table-scroll" tabIndex={0} role="region" aria-label="因果关系列表">
    <table className="data-table">
      <thead><tr>
        <th scope="col">关系</th>
        <th scope="col"><TooltipText tip="聚合关系数量" placement="bottom">记录数</TooltipText></th>
        <th scope="col"><TooltipText tip="该关系被多少篇论文验证" placement="bottom">论文数</TooltipText></th>
        <th scope="col"><TooltipText tip="该关系被多少种方法验证" placement="bottom">方法数</TooltipText></th>
        <th scope="col">{signHeader}</th>
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
        <td><a className="inline-link" href={relationPath(edge.source, edge.target)}>查看</a></td>
      </tr>)}</tbody>
    </table>
  </div>
}

export function EvidenceTable({ claims, showRelation = false }: { claims: CausalClaim[]; showRelation?: boolean }) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  if (!claims.length) return <p className="muted p-5 m-0">暂无证据记录。</p>

  function toggle(id: number) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return <div className="table-scroll" tabIndex={0} role="region" aria-label="证据记录表">
    <table className="data-table causal-evidence-table">
      <thead><tr>
        <th scope="col" className="w-9"><span className="sr-only">展开</span></th>
        <th scope="col">{showRelation ? "标准关系" : "论文"}</th>
        <th scope="col">方向</th>
        <th scope="col">方法</th>
        <th scope="col">显著性</th>
        <th scope="col">{showRelation ? "原始主张" : "领域"}</th>
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
                  <p>{claim.evidence || "暂无证据文本"}</p>
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
    <aside className="causal-legend" aria-label="线色图例">
      <button type="button" className="causal-legend-toggle" aria-expanded={legendOpen}
        onClick={() => setLegendOpen((v) => !v)}>
        <Palette size={14} aria-hidden="true" />
        <strong>线色图例</strong>
        <span className="muted text-xs ml-auto">{legendOpen ? "点击隐藏" : "点击显示"}</span>
      </button>
      {legendOpen && <div className="causal-legend-body">
        <div className="causal-legend-grid">
          {(Object.entries(SIGN_META) as [SignCategory, typeof SIGN_META.positive][]).map(([key, item]) => (
            <div key={key} className="causal-legend-item">
              <span className="causal-legend-line" style={{ background: item.color }} />
              <span>{item.legend}</span>
            </div>
          ))}
        </div>
        <div className="causal-legend-notes">
          <span><Split size={12} aria-hidden="true" />虚线表示主张的因果影响方向分歧超过 40%</span>
          <span><Layers size={12} aria-hidden="true" />边的粗细代表主张在数据库中的重复验证程度</span>
        </div>
      </div>}
    </aside>
  </div>
}
