import { AlertCircle, ChevronLeft, ChevronRight, FolderOpen, LoaderCircle, X } from "lucide-react"
import { useEffect, useId, useRef, useState, type ReactNode } from "react"
import { params, useQuery } from "../api"
import type { Job, JobItemState, JobKind, JobState, PaperStatus, Source } from "../types"
import { Button } from "./ui/button"

export const paperLabels: Record<PaperStatus, [string, string]> = {
  PUBLISHED: ["已发布全文", "green"], READY: ["待导入全文", "green"], UNPROCESSED: ["待解析", "neutral"],
  FAILED: ["处理失败", "red"], UNSUPPORTED: ["格式暂不支持解析", "neutral"],
}

export const jobLabels: Record<JobState | JobItemState, [string, string]> = {
  READY: ["待确认", "blue"], QUEUED: ["排队中", "neutral"], RUNNING: ["处理中", "blue"],
  SUCCEEDED: ["已完成", "green"], SKIPPED: ["已跳过", "neutral"], FAILED: ["失败", "red"], PARTIAL: ["部分完成", "amber"],
}

export const jobKinds: Record<JobKind, string> = {
  ORIGINAL_IMPORT: "原始文件导入", PARSE: "文件解析", PUBLISH: "全文导入", MATCH: "OpenAlex 匹配", METADATA: "OpenAlex 元数据导入",
}

export function Chip({ status }: { status: PaperStatus | JobState }) {
  const [label, color] = (status in paperLabels ? paperLabels[status as PaperStatus] : jobLabels[status as JobState]) ?? ["待处理", "neutral"]
  return <span className={"status-chip status-" + color}><span className="status-dot" />{label}</span>
}

export function JobChip({ status }: { status: JobState | JobItemState }) {
  const [label, color] = jobLabels[status]
  return <span className={"status-chip status-" + color}><span className="status-dot" />{label}</span>
}

export function JobProgress({ job }: { job: Job }) {
  const complete = job.succeeded + job.skipped + job.failed
  return <div className="job-progress">
    <progress aria-label="处理进度" max={Math.max(job.total, 1)} value={complete} /><small>{complete} / {job.total}</small>
  </div>
}

export function Loading({ label = "正在加载…" }: { label?: string }) {
  return <div className="empty-state" role="status"><LoaderCircle size={23} className="animate-spin" /><p>{label}</p></div>
}

export function Notice({ error, children }: { error?: boolean; children: ReactNode }) {
  return <div className={"notice " + (error ? "notice-error" : "")} role={error ? "alert" : "status"}><AlertCircle size={17} />{children}</div>
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon"><FolderOpen size={23} /></span><strong>{title}</strong>{children && <p>{children}</p>}</div>
}

export function Pagination({ page, size, total, change }: { page: number; size: number; total: number; change: (next: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  return <div className="pagination">
    <span>共 {total.toLocaleString()} 条{total > 0 && <> · 第 {page + 1} / {pages} 页</>}</span>
    <div className="flex items-center gap-2">
      <Button type="button" variant="outline" size="icon" disabled={page === 0} onClick={() => change(page - 1)} aria-label="上一页"><ChevronLeft size={17} /></Button>
      <Button type="button" variant="outline" size="icon" disabled={page + 1 >= pages} onClick={() => change(page + 1)} aria-label="下一页"><ChevronRight size={17} /></Button>
    </div>
  </div>
}

export function Modal({ title, description, children, onClose }: { title: string; description?: string; children: ReactNode; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const id = useId()
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="modal" aria-labelledby={id} onCancel={(event) => { event.preventDefault(); onClose() }}>
    <div className="modal-heading">
      <div><h2 id={id}>{title}</h2>{description && <p>{description}</p>}</div>
      <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label="关闭"><X size={20} /></Button>
    </div>
    {children}
  </dialog>
}

export function SourceField({ value, change, required, placeholder = "搜索期刊或输入来源 ID" }:
  { value: string; change: (value: string) => void; required?: boolean; placeholder?: string }) {
  const id = useId()
  const [search, setSearch] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(value), 250)
    return () => window.clearTimeout(timer)
  }, [value])
  const options = useQuery<Source[]>(search.length >= 2 ? "/sources" + params({ q: search }) : null)
  return <>
    <input className="field" list={id} value={value} placeholder={placeholder} required={required}
      onChange={(event) => change(event.target.value)} autoComplete="off" />
    <datalist id={id}>{options.data?.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.name}</option>)}</datalist>
  </>
}
