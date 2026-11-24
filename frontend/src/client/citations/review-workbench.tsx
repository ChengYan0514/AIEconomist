import {
  AlertTriangle,
  BookOpenCheck,
  Check,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  ExternalLink,
  FilePlus2,
  LoaderCircle,
  Quote,
} from "lucide-react"
import { useMemo, useState } from "react"
import { Notice } from "../../components/shared"
import { Button } from "../../components/ui/button"
import { buildAnnotatedHighlightSegments } from "../citation-format"
import {
  CITATION_STYLES,
  type CitationReference,
  type CitationStyle,
  type CitationTaskDetail,
  type ExportFormat,
} from "../types"

function normalizeDoiHref(doi: string | null, url: string | null): string | null {
  if (doi) {
    const clean = doi.trim().replace(/^https?:\/\/(dx\.)?doi\.org\//i, "").replace(/^doi:\s*/i, "")
    if (clean) return `https://doi.org/${clean}`
  }
  return url || null
}

function pctLabel(score: number | null): string {
  if (score === null || score === undefined) return "—"
  return `${Math.round(score * 100)}%`
}

export function ReviewWorkbench({
  task,
  saving,
  error,
  onChangeStyle,
  onToggleReference,
  onExport,
  onNewFromOriginal,
}: {
  task: CitationTaskDetail
  saving: boolean
  error: string
  onChangeStyle: (style: CitationStyle) => void
  onToggleReference: (statementId: string, referenceId: string, checked: boolean) => void
  onExport: (format: ExportFormat) => void
  onNewFromOriginal: () => void
}) {
  const [activeStatementId, setActiveStatementId] = useState<string | null>(
    task.statements[0]?.statementId ?? null,
  )
  const [expandedStatements, setExpandedStatements] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(task.statements.map((s, idx) => [s.statementId, idx === 0])),
  )
  const [expandedChunks, setExpandedChunks] = useState<Record<string, boolean>>({})
  const [copied, setCopied] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [mobileTab, setMobileTab] = useState<"paper" | "evidence">("paper")

  const annotated = task.annotatedText || task.originalText
  const segments = useMemo(
    () => buildAnnotatedHighlightSegments(annotated, task.statements),
    [annotated, task.statements],
  )
  const bibLines = useMemo(
    () => (task.bibliography || "").split("\n").map((l) => l.trim()).filter(Boolean),
    [task.bibliography],
  )

  const uniqueSelectedCount = useMemo(
    () => new Set(Object.values(task.selectedReferenceIds).flat()).size,
    [task.selectedReferenceIds],
  )

  function focusStatement(statementId: string) {
    setActiveStatementId(statementId)
    setExpandedStatements((prev) => ({ ...prev, [statementId]: true }))
  }

  async function copyAll() {
    const combined = `${annotated}\n\nReferences\n${task.bibliography || ""}`.trim()
    try {
      await navigator.clipboard.writeText(combined)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {}
  }

  return <div className="cit-workbench">
    <div className="cit-mobile-tabs" role="tablist" aria-label="结果与证据切换">
      <button type="button" role="tab" aria-selected={mobileTab === "paper"} onClick={() => setMobileTab("paper")}>
        带引文正文 ({bibLines.length})
      </button>
      <button type="button" role="tab" aria-selected={mobileTab === "evidence"} onClick={() => setMobileTab("evidence")}>
        引文陈述与证据 ({task.statements.length})
      </button>
    </div>

    <section className={"cit-paper-pane" + (mobileTab !== "paper" ? " is-mobile-hidden" : "")} aria-label="交付正文与参考文献">
      <div className="cit-toolbar">
        <div className="cit-toolbar-styles" role="group" aria-label="即时切换引文样式">
          {CITATION_STYLES.map((item) => (
            <button key={item.value} type="button"
              disabled={saving}
              aria-pressed={task.citationStyle === item.value}
              className={"cit-style-pill" + (task.citationStyle === item.value ? " is-active" : "")}
              onClick={() => onChangeStyle(item.value)}>
              {item.short}
            </button>
          ))}
        </div>

        <div className="cit-toolbar-actions">
          {saving && <span className="cit-saving-pill"><LoaderCircle size={13} className="animate-spin" />正在重排…</span>}
          <Button type="button" variant="outline" size="sm" onClick={copyAll}>
            {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            {copied ? "已复制" : "复制全文"}
          </Button>
          <div className="cit-export-wrap">
            <Button type="button" variant="outline" size="sm" aria-expanded={exportOpen}
              onClick={() => setExportOpen((v) => !v)}>
              <Download size={14} aria-hidden="true" />导出
              <ChevronDown size={13} aria-hidden="true" />
            </Button>
            {exportOpen && <div className="cit-export-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => { setExportOpen(false); onExport("txt") }}>
                纯文本 (.txt)
              </button>
              <button type="button" role="menuitem" onClick={() => { setExportOpen(false); onExport("md") }}>
                Markdown (.md)
              </button>
              <button type="button" role="menuitem" onClick={() => { setExportOpen(false); onExport("bibtex") }}>
                BibTeX 参考文献 (.bib)
              </button>
            </div>}
          </div>
          <Button type="button" variant="ghost" size="sm" onClick={onNewFromOriginal}>
            <FilePlus2 size={14} aria-hidden="true" />基于原文新建
          </Button>
        </div>
      </div>

      {error && <div className="mb-3"><Notice error>{error}</Notice></div>}

      {task.warnings.length > 0 && (
        <details className="cit-warnings-bar">
          <summary><AlertTriangle size={14} aria-hidden="true" />本次标注包含 {task.warnings.length} 条系统提示</summary>
          <ul>{task.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
        </details>
      )}

      <article className="cit-manuscript">
        <header className="cit-manuscript-head">
          <h2>{task.title}</h2>
          <p className="muted">
            点击正文高亮的引文陈述可定位右侧证据；在右侧勾选或取消任意备选文献，正文与参考文献列表会即时重排并自动保存。
          </p>
        </header>

        <div className="cit-annotated-body">
          {segments.map((seg, i) => seg.statementId ? (
            <mark key={i}
              className={"cit-span" + (activeStatementId === seg.statementId ? " is-active" : "")}
              onClick={() => { focusStatement(seg.statementId!); setMobileTab("evidence") }}>
              {seg.text}
            </mark>
          ) : <span key={i}>{seg.text}</span>)}
        </div>

        <section className="cit-bib-section" aria-label="参考文献列表">
          <div className="cit-bib-head">
            <h3>References</h3>
            <span className="muted tabular-nums">共 {bibLines.length} 篇去重文献</span>
          </div>
          {bibLines.length === 0 ? (
            <p className="muted">当前尚未采纳任何参考文献，请在右侧勾选通过证据评估的候选文献。</p>
          ) : (
            <ol className="cit-bib-list">
              {bibLines.map((line, i) => <li key={i}>{line}</li>)}
            </ol>
          )}
        </section>
      </article>
    </section>

    <aside className={"cit-evidence-pane" + (mobileTab !== "evidence" ? " is-mobile-hidden" : "")} aria-label="引文陈述与证据审阅">
      <div className="cit-evidence-head">
        <div>
          <strong>引文陈述与证据审阅</strong>
          <p className="muted tabular-nums">
            共 {task.statements.length} 条引文陈述 · 已采纳 {uniqueSelectedCount} 篇去重文献
          </p>
        </div>
      </div>

      <div className="cit-statement-list">
        {task.statements.length === 0 ? (
          <p className="muted p-4">未识别出需要配引文的引文陈述。</p>
        ) : task.statements.map((stmt, idx) => {
          const candidates = task.referencesByStatement[stmt.statementId] ?? []
          const selectedSet = new Set(task.selectedReferenceIds[stmt.statementId] ?? [])
          const stats = task.evidenceStats[stmt.statementId]
          const isOpen = Boolean(expandedStatements[stmt.statementId])
          const isActive = activeStatementId === stmt.statementId

          return <section key={stmt.statementId}
            className={"cit-stmt-card" + (isActive ? " is-active" : "")}>
            <button type="button" className="cit-stmt-toggle" aria-expanded={isOpen}
              onClick={() => {
                setActiveStatementId(stmt.statementId)
                setExpandedStatements((prev) => ({ ...prev, [stmt.statementId]: !prev[stmt.statementId] }))
              }}>
              <span className="cit-stmt-chevron" aria-hidden="true">
                {isOpen ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
              </span>
              <div className="cit-stmt-summary">
                <div className="cit-stmt-title-row">
                  <span className="cit-stmt-index">陈述 #{idx + 1}</span>
                  <span className="cit-stmt-counts tabular-nums">
                    已采纳 <strong>{selectedSet.size}</strong> / 候选 {candidates.length}
                  </span>
                </div>
                <p className="cit-stmt-text">{stmt.text}</p>
              </div>
            </button>

            {isOpen && <div className="cit-stmt-body">
              {stats && (
                <div className="cit-stats-strip">
                  <span>送审片段 <strong>{stats.judgedChunks}</strong></span>
                  <span>支持片段 <strong>{stats.supportedChunks}</strong></span>
                  <span>通过文献 <strong>{stats.supportedReferences}</strong></span>
                  {stats.hitHardRecallLimit && <span className="cit-limit-badge">召回触顶</span>}
                  {stats.hitHardJudgmentLimit && <span className="cit-limit-badge">送审触顶</span>}
                </div>
              )}

              {candidates.length === 0 ? (
                <p className="muted cit-no-ref">该引文陈述暂无通过双阈值证据筛选的候选文献。</p>
              ) : (
                <div className="cit-ref-list">
                  {candidates.map((ref) => (
                    <ReferenceReviewCard
                      key={ref.referenceId}
                      reference={ref}
                      checked={selectedSet.has(ref.referenceId)}
                      disabled={saving}
                      chunksOpen={Boolean(expandedChunks[`${stmt.statementId}:${ref.referenceId}`])}
                      onToggleChunks={() => setExpandedChunks((prev) => {
                        const key = `${stmt.statementId}:${ref.referenceId}`
                        return { ...prev, [key]: !prev[key] }
                      })}
                      onToggleChecked={(next) => onToggleReference(stmt.statementId, ref.referenceId, next)}
                    />
                  ))}
                </div>
              )}
            </div>}
          </section>
        })}
      </div>
    </aside>
  </div>
}

function ReferenceReviewCard({
  reference,
  checked,
  disabled,
  chunksOpen,
  onToggleChunks,
  onToggleChecked,
}: {
  reference: CitationReference
  checked: boolean
  disabled: boolean
  chunksOpen: boolean
  onToggleChunks: () => void
  onToggleChecked: (checked: boolean) => void
}) {
  const href = normalizeDoiHref(reference.doi, reference.url)
  const authorText = reference.authors.length > 3
    ? `${reference.authors.slice(0, 3).join(", ")} 等`
    : reference.authors.join(", ") || "未知作者"

  return <div className={"cit-ref-card" + (checked ? " is-selected" : "")}>
    <div className="cit-ref-top">
      <label className="cit-ref-check">
        <input type="checkbox" checked={checked} disabled={disabled}
          aria-label={(checked ? "取消采纳 " : "采纳 ") + reference.title}
          onChange={(e) => onToggleChecked(e.target.checked)} />
        <span className="cit-ref-title">{reference.title}</span>
      </label>
    </div>

    <div className="cit-ref-meta muted">
      <span>{authorText}</span>
      {reference.year && <span>· {reference.year}</span>}
      {reference.venue && <span>· {reference.venue}</span>}
    </div>

    <div className="cit-ref-badges">
      {checked ? <span className="cit-tag is-adopted">已写入正文</span> : <span className="cit-tag">备选文献</span>}
      {reference.paperId && <span className="cit-tag is-local"><BookOpenCheck size={11} aria-hidden="true" />本地全文库</span>}
      {reference.maxSupportScore !== null && (
        <span className="cit-tag tabular-nums">支持度 {pctLabel(reference.maxSupportScore)}</span>
      )}
      {reference.maxConfidence !== null && (
        <span className="cit-tag tabular-nums">置信 {pctLabel(reference.maxConfidence)}</span>
      )}
      {href && (
        <a className="cit-doi-link" href={href} target="_blank" rel="noreferrer">
          DOI <ExternalLink size={11} aria-hidden="true" />
        </a>
      )}
    </div>

    {reference.chunks.length > 0 && (
      <div className="cit-chunks-wrap">
        <button type="button" className="cit-chunks-toggle" aria-expanded={chunksOpen} onClick={onToggleChunks}>
          <Quote size={12} aria-hidden="true" />
          <span>{chunksOpen ? "收起原文证据" : `展开 ${reference.chunks.length} 条支持原文证据`}</span>
        </button>
        {chunksOpen && (
          <div className="cit-chunks-list">
            {reference.chunks.map((chunk) => (
              <div key={chunk.chunkId} className="cit-chunk-item">
                {chunk.sectionTitle && <div className="cit-chunk-section">{chunk.sectionTitle}</div>}
                <blockquote>{chunk.text}</blockquote>
                {chunk.reason && <p className="cit-chunk-reason"><strong>判定依据：</strong>{chunk.reason}</p>}
              </div>
            ))}
          </div>
        )}
      </div>
    )}
  </div>
}
