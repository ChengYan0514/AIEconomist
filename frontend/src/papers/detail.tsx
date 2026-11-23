import { Archive, ArrowLeft, BookOpen, Database, Download, ExternalLink, FileText, Pencil, RotateCcw, ScanText, UploadCloud } from "lucide-react"
import { useState } from "react"
import { api, dateLabel, errorMessage, jobPath, paperPath, params, useQuery } from "../api"
import { Chip, Empty, Loading, Modal, Notice, Pagination } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import type { Job, JobKind, Page, Paper, PaperBlock } from "../types"
import { MetadataForm, ReplaceOriginal } from "./forms"

export function PaperDetail({ id }: { id: string }) {
  const { can, canJob } = usePermissions()
  const canWrite = can("PAPER_WRITE")
  const canReadContent = can("CONTENT_READ")
  const path = paperPath(id)
  const result = useQuery<Paper>(path)
  const [tab, setTab] = useState<"metadata" | "file" | "text">("metadata")
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
      window.location.assign(jobPath(job.jobId))
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
    <SectionHeader title={paper.title || paper.paperId} description={paper.authors?.replaceAll(";", " · ") || "作者未登记"}
      back={<a href="/papers" className="back-link"><ArrowLeft size={15} />返回论文库</a>}
      actions={<>
        {canWrite && !paper.archived && <Button type="button" variant="outline" onClick={() => setDialog("edit")}><Pencil size={16} />编辑书目</Button>}
        {canWrite && <Button type="button" variant="outline" onClick={() => setDialog("archive")}>{paper.archived ? <RotateCcw size={16} /> : <Archive size={16} />}{paper.archived ? "恢复论文" : "归档"}</Button>}
      </>} />
    {error && dialog !== "archive" && <Notice error>{error}</Notice>}
    {paper.archived && <Notice>此论文已归档。恢复后可以继续处理。</Notice>}
    <div className="detail-grid">
      <section className="panel detail-body">
        <div className="panel-tabs detail-tabs" role="group" aria-label="论文详情">
          <button type="button" aria-pressed={(tab === "metadata" || !canReadContent)} onClick={() => setTab("metadata")}><FileText size={16} />书目信息</button>
          {canReadContent && <button type="button" aria-pressed={canReadContent && tab === "file"} onClick={() => setTab("file")}><Download size={16} />原始文件</button>}
          {canReadContent && <button type="button" aria-pressed={canReadContent && tab === "text"} onClick={() => setTab("text")}><BookOpen size={16} />全文阅读</button>}
        </div>
        {(tab === "metadata" || !canReadContent) && <dl className="metadata-grid">
          <div><dt>出版来源</dt><dd>{paper.sourceName || paper.sourceId || "—"}{paper.sourceName && <small>{paper.sourceId}</small>}</dd></div>
          <div><dt>发表年份</dt><dd>{paper.year ?? "—"}</dd></div>
          <div><dt>DOI</dt><dd>{paper.doi || "—"}</dd></div>
          <div><dt>采集平台</dt><dd>{paper.provider || "—"}</dd></div>
          <div className="span-two"><dt>论文 ID</dt><dd className="mono break-all">{paper.paperId}</dd></div>
          <div className="span-two"><dt>原始链接</dt><dd>{safeUrl ? <a href={safeUrl} target="_blank" rel="noreferrer" className="inline-link">访问原始页面 <ExternalLink size={14} /></a> : "—"}</dd></div>
        </dl>}
        {canReadContent && tab === "file" && <div className="original-panel">
          <div className="original-heading"><div><FileText size={19} /><span title={paper.originalName || undefined}>{paper.originalName || "未登记原件"}</span></div><div className="flex flex-wrap gap-2">
            {paper.hasOriginal && <Button asChild variant="outline" size="sm"><a href={"/api" + path + "/file"} target="_blank" rel="noreferrer"><Download size={15} />打开原件</a></Button>}
            {canWrite && !paper.archived && <Button type="button" variant="outline" size="sm" onClick={() => setDialog("replace")}><UploadCloud size={15} />更换原件</Button>}
          </div></div>
          {!paper.hasOriginal ? <Empty title="原件当前不可读取">历史正文仍可在全文阅读中查阅。</Empty> :
            paper.originalType === "PDF" ? <iframe key={paper.revision} className="pdf-preview" src={"/api" + path + "/file"} title="论文 PDF 原件" /> :
            <Empty title={paper.originalType + " 原件"}>使用「打开原件」下载原始文件。</Empty>}
        </div>}
        {canReadContent && tab === "text" && <Reader path={path} revision={paper.revision} />}
      </section>
      <aside className="detail-aside">
        <section className="panel detail-card">
          <div className="detail-card-title"><ScanText size={18} /><h2>全文处理</h2><Chip status={paper.status} /></div>
          <div className="small-facts"><div><span>当前原件</span><strong>{paper.originalType || "—"}</strong></div><div><span>最近更新</span><strong>{dateLabel(paper.updatedAt)}</strong></div></div>
          {!paper.archived && (canJob("PARSE") || canJob("PUBLISH")) && <div className="card-actions">
            {canJob("PARSE") && <Button type="button" variant={canPublish ? "outline" : "default"} disabled={busy || !paper.hasOriginal || paper.originalType !== "PDF"} onClick={() => start("PARSE")}><ScanText size={16} />{paper.status === "PUBLISHED" || paper.latestParseId ? "重新解析" : "开始解析"}</Button>}
            {canJob("PUBLISH") && <Button type="button" variant={canPublish ? "default" : "outline"} disabled={busy || !canPublish} onClick={() => start("PUBLISH")}><BookOpen size={16} />导入全文</Button>}
          </div>}
        </section>
        <section className="panel detail-card">
          <div className="detail-card-title"><Database size={18} /><h2>OpenAlex 关联</h2></div>
          {paper.matchedWorkId ? <div className="info-box"><strong>已关联</strong><code>{paper.matchedWorkId}</code></div> :
            <p className="muted text-sm leading-relaxed">可以使用所选期刊的作品元数据匹配此论文。</p>}
          {canJob("MATCH") && !paper.archived && !paper.matchedWorkId && <Button className="mt-3 w-full" type="button" variant="outline" disabled={busy} onClick={() => start("MATCH")}>匹配论文</Button>}
        </section>
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

function Reader({ path, revision }: { path: string; revision: number }) {
  const { canJob } = usePermissions()
  const [page, setPage] = useState(0)
  const size = 40
  const blocks = useQuery<Page<PaperBlock>>(path + "/blocks" + params({ page, size, revision }))
  if (blocks.error) return <div className="p-5"><Notice error>{blocks.error.message}</Notice></div>
  if (!blocks.data) return <Loading label="正在加载全文…" />
  if (!blocks.data.total) return <Empty title="还没有可阅读的全文">{canJob("PUBLISH") && "解析完成后，使用「导入全文」发布内容。"}</Empty>
  return <>
    <article className="reader-content">
      {blocks.data.items.map((block) => <section key={block.sequence} className={"reader-block block-" + block.type}>
        <span className="page-mark">页 {block.page + 1}</span>
        {block.type === "title" ? <h3>{block.text}</h3> : block.text && <p>{block.text}</p>}
        {block.hasAsset && <img loading="lazy" src={"/api" + path + "/blocks/" + block.sequence + "/asset"} alt={block.type === "table" ? "论文表格" : "论文图像"} />}
      </section>)}
    </article>
    <Pagination page={page} size={size} total={blocks.data.total} change={setPage} />
  </>
}
