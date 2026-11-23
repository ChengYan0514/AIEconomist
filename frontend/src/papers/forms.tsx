import { FileArchive, FileText, UploadCloud } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, errorMessage, jobPath, paperPath, params } from "../api"
import { Modal, Notice, SourceField, jobKinds } from "../components/shared"
import { usePermissions } from "../permissions"
import { Button } from "../components/ui/button"
import type { Job, JobKind, Paper } from "../types"

export function MetadataForm({ paper, close, done }: { paper?: Paper; close: () => void; done: (paper: Paper) => void }) {
  const [source, setSource] = useState(paper?.sourceId ?? "")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    form.set("sourceId", source)
    setBusy(true); setError("")
    try {
      const result = paper ? await api<Paper>(paperPath(paper.paperId), {
        method: "PATCH", body: JSON.stringify({ ...Object.fromEntries(form), year: Number(form.get("year")), version: paper.version }),
      }) : await api<Paper>("/papers", { method: "POST", body: form })
      done(result)
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }

  return <Modal title={paper ? "编辑论文" : "上传单篇论文"} description={paper ? "保存后沿用已登记的论文 ID。" : "填写 4 项必填书目信息，上传 PDF 原件。"} onClose={close}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <label>论文标题<input className="field" name="title" defaultValue={paper?.title ?? ""} required maxLength={2000} /></label>
      <div className="field-grid">
        <label>出版来源<SourceField value={source} change={setSource} required /></label>
        <label>发表年份<input className="field" name="year" type="number" defaultValue={paper?.year ?? ""} min={1000} max={9999} required /></label>
      </div>
      <label>作者<input className="field" name="authors" defaultValue={paper?.authors ?? ""} placeholder="按原顺序输入，多名作者用英文分号隔开" required maxLength={2000} /></label>
      <div className="field-grid">
        <label>DOI<input className="field" name="doi" defaultValue={paper?.doi ?? ""} placeholder="选填" /></label>
        <label>采集平台<input className="field" name="provider" defaultValue={paper?.provider ?? ""} placeholder="选填" /></label>
      </div>
      <label>原始链接<input className="field" name="url" type="url" defaultValue={paper?.url ?? ""} placeholder="选填" /></label>
      {!paper && <UploadField name="file" accept=".pdf,application/pdf" label="选择 PDF 原件" description="单个文件最多 100 MiB" />}
      <div className="modal-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button disabled={busy}>{busy ? "正在保存…" : paper ? "保存变更" : "上传论文"}</Button></div>
    </form>
  </Modal>
}

export function ArchiveUpload({ close }: { close: () => void }) {
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setBusy(true); setError("")
    try {
      const job = await api<Job>("/imports", { method: "POST", body: data })
      window.location.assign(jobPath(job.jobId))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Modal title="批量导入原件" description="上传 ZIP，先核对预检结果，再确认导入。" onClose={close}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <UploadField name="file" accept=".zip,application/zip" label="选择 ZIP 导入包" description="包含一个 UTF-8 CSV 和对应原件，最大 256 MiB" zip />
      <div className="info-box"><strong>CSV 必填字段</strong><code>source_id, year, paper_title, authors, file_path</code><span>file_path 使用包内相对路径。PDF、XML 和 HTML 可以登记，解析目前支持 PDF。</span></div>
      <div className="modal-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button disabled={busy}>{busy ? "正在预检…" : "开始预检"}</Button></div>
    </form>
  </Modal>
}

export function ReplaceOriginal({ paper, close, done }: { paper: Paper; close: () => void; done: (paper: Paper) => void }) {
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setBusy(true); setError("")
    try {
      done(await api<Paper>(paperPath(paper.paperId) + "/file" + params({ version: paper.version }), { method: "PUT", body: data }))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Modal title="更换原件" description="新原件需要重新解析和导入全文。" onClose={close}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <UploadField name="file" accept=".pdf,application/pdf" label="选择新的 PDF" description="单个文件最多 100 MiB" />
      <div className="modal-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button disabled={busy}>{busy ? "正在替换…" : "保存新原件"}</Button></div>
    </form>
  </Modal>
}

export function ProcessPapers({ ids, close }: { ids: string[]; close: () => void }) {
  const { canJob } = usePermissions()
  const [byRange, setByRange] = useState(ids.length === 0)
  const [source, setSource] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    const kind = fields.get("kind") as JobKind
    const from = fields.get("yearFrom")
    const to = fields.get("yearTo")
    const scope = byRange ? { sourceId: source, yearFrom: from ? Number(from) : undefined, yearTo: to ? Number(to) : undefined } : { paperIds: ids }
    setBusy(true); setError("")
    try {
      const job = await api<Job>("/jobs", { method: "POST", body: JSON.stringify({ kind, ...scope }) })
      window.location.assign(jobPath(job.jobId))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Modal title="提交论文处理" description="选择阶段和目标。提交后，可在「导入与处理」查看逐项结果。" onClose={close}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <label>处理阶段<select className="field" name="kind">{(["PARSE", "PUBLISH", "MATCH"] as const).filter(canJob).map((kind) => <option key={kind} value={kind}>{jobKinds[kind]}</option>)}</select></label>
      <div className="segmented">
        <button type="button" aria-pressed={!byRange} onClick={() => setByRange(false)} disabled={!ids.length}>所选论文 {ids.length > 0 && <>({ids.length})</>}</button>
        <button type="button" aria-pressed={byRange} onClick={() => setByRange(true)}>按期刊处理</button>
      </div>
      {byRange && <>
        <label>出版来源<SourceField value={source} change={setSource} required /></label>
        <div className="field-grid">
          <label>起始年份<input className="field" type="number" name="yearFrom" min={1000} max={9999} placeholder="不限" /></label>
          <label>结束年份<input className="field" type="number" name="yearTo" min={1000} max={9999} placeholder="不限" /></label>
        </div>
      </>}
      <div className="modal-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button disabled={busy}>{busy ? "正在提交…" : "提交任务"}</Button></div>
    </form>
  </Modal>
}

function UploadField({ name, accept, label, description, zip }: { name: string; accept: string; label: string; description: string; zip?: boolean }) {
  const [filename, setFilename] = useState("")
  const Icon = filename ? (zip ? FileArchive : FileText) : UploadCloud
  return <label className="upload-field">
    <input name={name} type="file" accept={accept} onChange={(event) => setFilename(event.target.files?.[0]?.name ?? "")} required />
    <span className="upload-icon"><Icon size={23} /></span>
    <strong>{filename || label}</strong><span>{description}</span>
  </label>
}
