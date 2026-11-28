import { Archive, ArrowLeft, BookOpen, Check, ClipboardList, Copy, Database, Download, ExternalLink, FileText, ListTree, Network, Pencil, Play, RotateCcw, ScanText, Sparkles, UploadCloud } from "lucide-react"
import { useState } from "react"
import { api, dateLabel, errorMessage, jobPath, paperPath, params, useQuery } from "../api"
import { extractionEntryHref, paperClaimsPath, type CausalPaperSummary, type PaperCausalStatusInfo, type RunReviewDraft } from "../causal/types"
import { ReviewWorkbenchModal } from "../causal/review-workbench"
import { Chip, Empty, JobChip, Loading, Modal, Notice, Pagination, jobKinds } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { navigate } from "../router"
import type { Job, JobKind, Page, Paper, PaperBlock, PaperJobRecord } from "../types"
import { MetadataForm, ReplaceOriginal } from "./forms"

export function PaperDetail({ id }: { id: string }) {
  const { can, canJob } = usePermissions()
  const canWrite = can("PAPER_WRITE")
  const canReadContent = can("CONTENT_READ")
  const canReadHistory = can("PAPER_READ") && can("JOB_READ")
  const path = paperPath(id)
  const result = useQuery<Paper>(path)
  const [tab, setTab] = useState<"metadata" | "file" | "text" | "history">("metadata")
  const activeTab = (tab === "history" ? canReadHistory : tab === "metadata" || canReadContent) ? tab : "metadata"
  const [dialog, setDialog] = useState<"edit" | "replace" | "archive" | null>(null)
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  const paper = result.data

  if (!paper) return result.error ? <Notice error>{result.error.message}</Notice> : <Loading />

  async function statusChange() {
    if (!paper) return
    setBusy(true); setError("")
    try {
      await api(path + "/archive" + params({ version: paper.version }), { method: paper.archived ? "DELETE" : "POST" })
      setDialog(null)
      result.reload()
    } catch (cause) { setError(errorMessage(cause)) }
    finally { setBusy(false) }
  }

  async function start(kind: JobKind) {
    setBusy(true); setError("")
    try {
      const job = await api<Job>("/jobs", { method: "POST", body: JSON.stringify({ kind, paperIds: [id] }) })
      navigate(jobPath(job.jobId))
    } catch (cause) { setError(errorMessage(cause)) }
    finally { setBusy(false) }
  }

  const changed = () => {
    result.reload()
    setDialog(null)
  }
  const canPublish = paper.status === "READY" || (paper.latestParseId && paper.latestParseId !== paper.publishedParseId)
  const safeUrl = paper.url && /^https?:\/\//i.test(paper.url) ? paper.url : null
  return <>
    <SectionHeader editorial title={paper.title || paper.paperId} description={paper.authors?.replaceAll(";", " · ") || "作者未登记"}
      back={<div className="flex flex-wrap items-center gap-x-4">
        <a href="/papers" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回论文库</a>
        {paper.sourceId && <a href={"/journals" + params({ journal: paper.sourceId })} className="back-link">查看所属期刊</a>}
      </div>}
      actions={<>
        {canWrite && !paper.archived && <Button type="button" variant="outline" onClick={() => setDialog("edit")}><Pencil size={15} aria-hidden="true" />编辑书目</Button>}
        {canWrite && !paper.archived && !canReadContent && <Button type="button" variant="outline" onClick={() => setDialog("replace")}><UploadCloud size={15} aria-hidden="true" />更换原件</Button>}
        {canWrite && <Button type="button" variant="outline" onClick={() => setDialog("archive")}>{paper.archived ? <RotateCcw size={15} aria-hidden="true" /> : <Archive size={15} aria-hidden="true" />}{paper.archived ? "恢复论文" : "归档"}</Button>}
      </>} />
    {error && dialog !== "archive" && <Notice error>{error}</Notice>}
    {paper.archived && <Notice>此论文已归档。恢复后可以继续处理。</Notice>}
    <div className="detail-grid">
      <section className="panel detail-body">
        <div className="underline-tabs detail-tabs" role="group" aria-label="论文详情">
          <button type="button" aria-pressed={activeTab === "metadata"} onClick={() => setTab("metadata")}><FileText size={15} aria-hidden="true" />书目信息</button>
          {canReadContent && <button type="button" aria-pressed={activeTab === "file"} onClick={() => setTab("file")}><Download size={15} aria-hidden="true" />原始文件</button>}
          {canReadContent && <button type="button" aria-pressed={activeTab === "text"} onClick={() => setTab("text")}><BookOpen size={15} aria-hidden="true" />全文阅读</button>}
          {canReadHistory && <button type="button" aria-pressed={activeTab === "history"} onClick={() => setTab("history")}><ClipboardList size={15} aria-hidden="true" />处理记录</button>}
        </div>
        {activeTab === "metadata" && <dl className="metadata-grid">
          <div><dt>出版来源</dt><dd>
            {paper.sourceId ? <a className="inline-link" href={"/journals" + params({ journal: paper.sourceId })}>{paper.sourceName || paper.sourceId}</a> : "—"}
            {paper.sourceName && paper.sourceId && <small className="mono">{paper.sourceId}</small>}
          </dd></div>
          <div><dt>发表年份</dt><dd className="tabular-nums">{paper.year ?? "—"}</dd></div>
          <div><dt>DOI</dt><dd className="copyable-value">{paper.doi ? <><span className="mono">{paper.doi}</span><CopyButton value={paper.doi} label="复制 DOI" /></> : "—"}</dd></div>
          <div><dt>采集平台</dt><dd>{paper.provider || "—"}</dd></div>
          <div className="span-two"><dt>论文 ID</dt><dd className="copyable-value"><code className="id-plate mono break-all">{paper.paperId}</code><CopyButton value={paper.paperId} label="复制论文 ID" /></dd></div>
          <div className="span-two"><dt>原始链接</dt><dd>{safeUrl ? <a href={safeUrl} target="_blank" rel="noreferrer" className="inline-link">访问原始页面 <ExternalLink size={14} aria-hidden="true" /></a> : "—"}</dd></div>
        </dl>}
        {activeTab === "file" && <div className="original-panel">
          <div className="original-heading"><div><FileText size={17} aria-hidden="true" /><span title={paper.originalName || undefined}>{paper.originalName || "未登记原件"}</span></div><div className="flex flex-wrap gap-2">
            {paper.hasOriginal && <Button asChild variant="outline" size="sm"><a href={"/api" + path + "/file"} target="_blank" rel="noreferrer"><Download size={14} aria-hidden="true" />打开原件</a></Button>}
            {canWrite && !paper.archived && <Button type="button" variant="outline" size="sm" onClick={() => setDialog("replace")}><UploadCloud size={14} aria-hidden="true" />更换原件</Button>}
          </div></div>
          {!paper.hasOriginal ? <Empty title="原件当前不可读取">历史正文仍可在全文阅读中查阅。</Empty> :
            paper.originalType === "PDF" ? <iframe key={paper.revision} className="pdf-preview" src={"/api" + path + "/file"} title="论文 PDF 原件" /> :
            <Empty title={paper.originalType + " 原件"}>使用「打开原件」下载原始文件。</Empty>}
        </div>}
        {activeTab === "text" && <Reader path={path} revision={paper.revision} />}
        {activeTab === "history" && <PaperHistory path={path} legacy={paper.legacy} />}
      </section>
      <aside className="detail-aside">
        <section className="panel detail-card">
          <div className="detail-card-title"><ScanText size={16} strokeWidth={1.7} aria-hidden="true" /><h2>全文处理</h2><Chip status={paper.status} /></div>
          <div className="small-facts">
            <div><span>当前原件</span><strong className="mono">{paper.originalType || "—"}</strong></div>
            <div><span>原件修订</span><strong className="tabular-nums">Rev. {paper.revision}</strong></div>
            <div><span>最近更新</span><strong className="tabular-nums">{dateLabel(paper.updatedAt)}</strong></div>
          </div>
          {!paper.archived && (canJob("PARSE") || canJob("PUBLISH")) && <div className="card-actions">
            {canJob("PARSE") && <Button type="button" variant={canPublish ? "outline" : "default"} disabled={busy || !paper.hasOriginal || paper.originalType !== "PDF"} onClick={() => start("PARSE")}><ScanText size={15} aria-hidden="true" />{paper.status === "PUBLISHED" || paper.latestParseId ? "重新解析" : "开始解析"}</Button>}
            {canJob("PUBLISH") && <Button type="button" variant={canPublish ? "default" : "outline"} disabled={busy || !canPublish} onClick={() => start("PUBLISH")}><BookOpen size={15} aria-hidden="true" />导入全文</Button>}
          </div>}
        </section>
        <section className="panel detail-card">
          <div className="detail-card-title"><Database size={16} strokeWidth={1.7} aria-hidden="true" /><h2>OpenAlex 关联</h2></div>
          {paper.matchedWorkId ? <div className="info-box"><strong>已关联</strong><code className="mono">{paper.matchedWorkId}</code></div> :
            <p className="muted text-sm leading-relaxed">可以使用所选期刊的作品元数据匹配此论文。</p>}
          {canJob("MATCH") && !paper.archived && !paper.matchedWorkId && <Button className="mt-3 w-full" type="button" variant="outline" disabled={busy} onClick={() => start("MATCH")}>匹配论文</Button>}
        </section>
        {can("CAUSAL_READ") && <CausalPaperCard paper={paper} canExtract={can("CAUSAL_EXTRACT")} />}
      </aside>
    </div>
    {canWrite && dialog === "edit" && <MetadataForm paper={paper} close={() => setDialog(null)} done={changed} />}
    {canWrite && dialog === "replace" && <ReplaceOriginal paper={paper} close={() => setDialog(null)} done={changed} />}
    {canWrite && dialog === "archive" && <Modal title={paper.archived ? "恢复这篇论文？" : "归档这篇论文？"}
      description={paper.archived ? "论文会重新出现在论文库。" : "归档后仍可在归档视图查看和恢复，原件与全文会保留。"}
      onClose={() => { setError(""); setDialog(null) }}>
      <div className="modal-body form-stack">{error && <Notice error>{error}</Notice>}
        <div className="modal-actions"><Button type="button" variant="outline" onClick={() => { setError(""); setDialog(null) }}>取消</Button><Button type="button" disabled={busy} onClick={statusChange}>{paper.archived ? "恢复" : "确认归档"}</Button></div>
      </div>
    </Modal>}
  </>
}

function CausalPaperCard({ paper, canExtract }: { paper: Paper; canExtract: boolean }) {
  const workId = paper.matchedWorkId
  const summary = useQuery<CausalPaperSummary>(workId ? "/causal/papers/" + encodeURIComponent(workId) + "/summary" : null)
  const causalStatusRes = useQuery<PaperCausalStatusInfo>("/causal/papers/status/" + encodeURIComponent(paper.paperId))
  const cInfo = causalStatusRes.data
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [loadingReview, setLoadingReview] = useState(false)
  const [reviewDraft, setReviewDraft] = useState<RunReviewDraft | null>(null)

  const canStart = canExtract && !paper.archived && paper.status === "PUBLISHED" && Boolean(workId)

  async function openReview() {
    if (!cInfo?.activeRunId) return
    setLoadingReview(true)
    setError("")
    try {
      const draft = await api<RunReviewDraft>(`/causal/runs/${encodeURIComponent(cInfo.activeRunId)}/review`)
      setReviewDraft(draft)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoadingReview(false)
    }
  }

  async function handleDirectExtract() {
    setBusy(true)
    setError("")
    try {
      await api("/causal/extractions", {
        method: "POST",
        body: JSON.stringify({
          target: { mode: "PAPER_IDS", paperIds: [paper.paperId] },
          ingestionMode: "RAW",
          reviewPolicy: "MANUAL_REVIEW",
        }),
      })
      causalStatusRes.reload()
      summary.reload()
    } catch (e: any) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const status = cInfo?.status ?? (canStart ? "NOT_EXTRACTED" : "PREREQUISITE_MISSING")

  return <section className="panel detail-card">
    <div className="detail-card-title">
      <Network size={16} strokeWidth={1.7} aria-hidden="true" />
      <h2>因果声明 (04 LLM因果抽取)</h2>
    </div>

    {error && <Notice error>{error}</Notice>}

    {status === "PREREQUISITE_MISSING" && (
      <p className="muted text-sm leading-relaxed">
        前置未完成：需先关联 OpenAlex 作品并完成全文解析导入发布，方可进行因果声明抽取。
      </p>
    )}

    {status === "NOT_EXTRACTED" && (
      <div>
        <p className="muted text-sm leading-relaxed mb-3">
          该论文已完成全文发布与书目关联，尚未进行 LLM 因果声明抽取。
        </p>
        <div className="card-actions">
          {canExtract && (
            <Button type="button" disabled={busy} onClick={handleDirectExtract}>
              <Sparkles size={14} aria-hidden="true" />抽取
            </Button>
          )}
        </div>
      </div>
    )}

    {status === "EXTRACTING" && (
      <div>
        <div className="status-chip status-blue mb-2">
          <span className="status-dot" aria-hidden="true" />
          LLM 抽取因果知识中...
        </div>
        <p className="muted text-sm leading-relaxed">
          正在调用大模型进行因果声明抽取与分析，请稍候...
        </p>
      </div>
    )}

    {status === "PENDING_REVIEW" && (
      <div>
        <div className="status-chip status-amber mb-2">
          <span className="status-dot" aria-hidden="true" />
          抽取完成待人工审核
        </div>
        <p className="muted text-sm leading-relaxed mb-3">
          模型已完成阶段抽取，等待人工审核确认后入库。
        </p>
        <div className="card-actions">
          {canExtract && cInfo?.activeRunId && (
            <Button type="button" disabled={loadingReview} onClick={openReview}>
              {loadingReview ? "正在加载审核草稿…" : "打开审核表单"}
            </Button>
          )}
        </div>
      </div>
    )}

    {status === "FAILED" && (
      <div>
        <div className="status-chip status-red mb-2">
          <span className="status-dot" aria-hidden="true" />
          抽取失败
        </div>
        {cInfo?.causalLog && (
          <div className="p-2 bg-red-50 text-red-700 text-xs rounded border border-red-200 mb-3 font-mono break-all">
            {cInfo.causalLog}
          </div>
        )}
        <div className="card-actions">
          {canExtract && (
            <Button type="button" disabled={busy} onClick={handleDirectExtract}>
              <RotateCcw size={14} aria-hidden="true" />重新抽取
            </Button>
          )}
        </div>
      </div>
    )}

    {status === "EXTRACTED_NO_CAUSAL" && (
      <div>
        <div className="status-chip status-neutral mb-2">
          <span className="status-dot" aria-hidden="true" />
          已抽取（无因果）
        </div>
        <p className="muted text-sm leading-relaxed mb-3">
          模型已完成抽取分析，未从该论文中提取出有效的因果声明。
        </p>
        <div className="card-actions">
          {canExtract && (
            <Button type="button" variant="outline" disabled={busy} onClick={handleDirectExtract}>
              <RotateCcw size={14} aria-hidden="true" />重新抽取
            </Button>
          )}
        </div>
      </div>
    )}

    {status === "EXTRACTED" && (
      <div>
        <div className="small-facts mb-3">
          <div><span>声明记录</span><strong className="tabular-nums">{summary.data?.claimRecordCount ?? cInfo?.claimCount ?? 0}</strong></div>
          <div><span>标准关系</span><strong className="tabular-nums">{summary.data?.standardClaimCount ?? 0}</strong></div>
          <div><span>变量数</span><strong className="tabular-nums">{summary.data?.variableCount ?? 0}</strong></div>
        </div>
        <div className="card-actions">
          {workId && (
            <Button asChild variant="outline">
              <a href={paperClaimsPath(workId)}><Network size={14} aria-hidden="true" />查看论文因果声明</a>
            </Button>
          )}
          {canExtract && (
            <Button type="button" variant="outline" disabled={busy} onClick={handleDirectExtract}>
              <RotateCcw size={14} aria-hidden="true" />重新抽取
            </Button>
          )}
        </div>
      </div>
    )}

    {reviewDraft && cInfo?.activeRunId && (
      <ReviewWorkbenchModal
        drafts={[reviewDraft]}
        onClose={() => setReviewDraft(null)}
        onSubmit={async (drafts) => {
          if (drafts[0]) {
            await api(`/causal/runs/${encodeURIComponent(cInfo.activeRunId!)}/review`, {
              method: "POST",
              body: JSON.stringify({ claims: drafts[0].claims }),
            })
            causalStatusRes.reload()
            summary.reload()
          }
        }}
      />
    )}
  </section>
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {}
  }
  return <button type="button" className="copy-icon-btn" aria-label={label} title={copied ? "已复制" : label} onClick={copy}>
    {copied ? <Check size={13} aria-hidden="true" /> : <Copy size={13} aria-hidden="true" />}
  </button>
}

function PaperHistory({ path, legacy }: { path: string; legacy: boolean }) {
  const [page, setPage] = useState(0)
  const size = 20
  const query = path + "/jobs" + params({ page, size })
  const result = useQuery<Page<PaperJobRecord>>(query)
  if (result.error) return <div className="p-5">
    <Notice error>{result.error.message}</Notice>
    <Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button>
  </div>
  if (!result.data || result.dataPath !== query) return <Loading label="正在加载处理记录…" />
  if (!result.data.total) return <Empty title={legacy ? "没有可查阅的新版任务" : "暂无处理记录"} />

  return <>
    <div className="table-scroll" tabIndex={0} role="region" aria-label="论文处理记录">
      <table className="data-table"><thead><tr>
        <th scope="col">任务</th><th scope="col">提交时间</th><th scope="col">状态</th><th scope="col">结果</th>
      </tr></thead>
        <tbody>{result.data.items.map((record) => <tr key={`${record.jobId}-${record.sequence}`}>
          <td className="title-cell"><a href={jobPath(record.jobId)}>{jobKinds[record.kind]}</a></td>
          <td className="table-date"><time dateTime={record.createdAt}>{new Date(record.createdAt).toLocaleString("zh-CN", { hour12: false })}</time></td>
          <td><JobChip status={record.state} /></td>
          <td className="result-cell">{record.message || "—"}</td>
        </tr>)}</tbody>
      </table>
    </div>
    <Pagination page={page} size={size} total={result.data.total} change={setPage} />
  </>
}

function Reader({ path, revision }: { path: string; revision: number }) {
  const { canJob } = usePermissions()
  const [page, setPage] = useState(0)
  const size = 40
  const blocks = useQuery<Page<PaperBlock>>(path + "/blocks" + params({ page, size, revision }))
  if (blocks.error) return <div className="p-5"><Notice error>{blocks.error.message}</Notice></div>
  if (!blocks.data) return <Loading label="正在加载全文…" />
  if (!blocks.data.total) return <Empty title="还没有可阅读的全文">{canJob("PUBLISH") && "解析完成后，使用「导入全文」发布内容。"}</Empty>

  const items = blocks.data.items
  const headings = items.filter((block) => block.type === "title" && block.text)

  return <>
    <div className="reader-toolbar">
      <span>已发布结构化全文</span>
      <span className="mono">共 {blocks.data.total.toLocaleString()} 个内容块 · 本页 {items.length} 块</span>
    </div>
    <div className={"reader-shell" + (headings.length > 0 ? " has-outline" : "")}>
      {headings.length > 0 && <nav className="reader-outline" aria-label="本页章节目录">
        <div className="reader-outline-head"><ListTree size={14} aria-hidden="true" /><span>章节大纲</span></div>
        <ul>
          {headings.map((heading) => {
            const level = Number(heading.attributes?.title_level ?? heading.attributes?.text_level ?? 1)
            return <li key={heading.sequence} data-level={Math.min(Math.max(level, 1), 3)}>
              <a href={"#block-" + heading.sequence} onClick={(event) => {
                event.preventDefault()
                document.getElementById("block-" + heading.sequence)?.scrollIntoView({ behavior: "smooth", block: "start" })
              }}>{heading.text}</a>
            </li>
          })}
        </ul>
      </nav>}
      <article className="reader-content">
        {items.map((block) => <ReaderBlock key={block.sequence} path={path} block={block} />)}
      </article>
    </div>
    <Pagination page={page} size={size} total={blocks.data.total} change={setPage} />
  </>
}

function ReaderBlock({ path, block }: { path: string; block: PaperBlock }) {
  const attrs = block.attributes ?? {}
  const caption = textAttr(attrs, "table_caption", "image_caption", "caption")
  const footnote = textAttr(attrs, "table_footnote", "image_footnote", "footnote")
  const footnoteLabel = textAttr(attrs, "footnote_label")
  const eqFormat = textAttr(attrs, "format")
  const level = Number(attrs.title_level ?? attrs.text_level ?? 1)

  return <section id={"block-" + block.sequence} className={"reader-block block-" + block.type}>
    <span className="page-mark">页 {block.page + 1}</span>
    {block.type === "title" ? (
      level >= 2 ? <h4 className="reader-subheading">{block.text}</h4> : <h3>{block.text}</h3>
    ) : block.type === "equation" ? (
      <div className="block-equation-frame">
        {eqFormat && <span className="block-kind-tag">{eqFormat}</span>}
        {block.text && <code className="mono">{block.text}</code>}
      </div>
    ) : block.type === "footnote" ? (
      <p className="block-footnote-text">
        {footnoteLabel && <strong className="mono mr-1.5">[{footnoteLabel}]</strong>}
        {block.text}
      </p>
    ) : block.type === "reference" ? (
      <p className="block-reference-text">{block.text}</p>
    ) : (
      block.text && <p>{block.text}</p>
    )}
    {block.hasAsset && <figure className="reader-figure">
      {caption && <figcaption className="reader-caption">{caption}</figcaption>}
      <img loading="lazy" src={"/api" + path + "/blocks/" + block.sequence + "/asset"} alt={block.type === "table" ? "论文表格" : "论文图像"} />
      {footnote && <div className="reader-figure-note">{footnote}</div>}
    </figure>}
  </section>
}

function textAttr(attrs: Record<string, unknown>, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = attrs[key]
    if (typeof value === "string" && value.trim()) return value.trim()
    if (Array.isArray(value)) {
      const joined = value.filter((item): item is string => typeof item === "string" && Boolean(item.trim())).join(" ")
      if (joined) return joined
    }
  }
  return null
}
