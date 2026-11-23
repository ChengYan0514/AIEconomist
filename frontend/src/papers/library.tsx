import { Archive, BookOpenCheck, ChevronRight, Download, FileText, Plus, ScanText, Search, Upload, Workflow } from "lucide-react"
import { useState, type FormEvent } from "react"
import { dateLabel, paperPath, params, useQuery } from "../api"
import { Chip, Empty, Loading, Notice, Pagination, SourceField } from "../components/shared"
import { Button } from "../components/ui/button"
import { ArchiveIcon, SectionHeader } from "../components/workspace"
import type { Page, Paper, PaperStatus } from "../types"
import { ArchiveUpload, MetadataForm, ProcessPapers } from "./forms"

const size = 20
const initial = { q: "", sourceId: "", yearFrom: "", yearTo: "", status: "" }

export function Library() {
  const [draft, setDraft] = useState(initial)
  const [filter, setFilter] = useState(initial)
  const [archived, setArchived] = useState(false)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<"upload" | "archive" | "process" | null>(null)
  const query = params({ ...filter, archived })
  const result = useQuery<Page<Paper>>("/papers" + params({ ...filter, archived, page, size }))
  const rows = result.data?.items ?? []
  const available = rows.filter((item) => item.originalType === "PDF").length
  const published = rows.filter((item) => item.status === "PUBLISHED").length
  const live = !result.loading && !result.error

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPage(0)
    setSelected(new Set())
    setFilter(draft)
  }
  function switchArchive(value: boolean) {
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
  function update(key: keyof typeof initial, value: string) {
    setDraft((old) => ({ ...old, [key]: value }))
  }

  return <>
    <SectionHeader title="论文库" description="查找和整理原始文件，追踪论文从解析到全文发布的进度。" actions={<>
      <Button type="button" variant="outline" onClick={() => setDialog("upload")}><Plus size={17} />上传单篇</Button>
      <Button type="button" onClick={() => setDialog("archive")}><Upload size={17} />批量导入</Button>
    </>} />
    <div className="stat-grid">
      <Metric icon={<FileText size={20} />} label={archived ? "符合筛选的归档论文" : "符合筛选的论文"} value={live ? (result.data?.total ?? 0).toLocaleString() : "—"} color="blue" />
      <Metric icon={<ScanText size={20} />} label="本页 PDF 原件" value={live ? String(available) : "—"} color="violet" />
      <Metric icon={<BookOpenCheck size={20} />} label="本页已发布全文" value={live ? String(published) : "—"} color="green" />
    </div>
    <section className="panel library-panel">
      <div className="panel-bar">
        <div className="panel-tabs" role="group" aria-label="论文库视图">
          <button type="button" aria-pressed={!archived} onClick={() => switchArchive(false)}><ArchiveIcon archived={false} />论文列表</button>
          <button type="button" aria-pressed={archived} onClick={() => switchArchive(true)}><Archive size={16} />归档</button>
        </div>
        <div className="flex items-center flex-wrap gap-2">
          <Button asChild variant="outline" size="sm"><a href={"/api/papers/export" + query}><Download size={15} />导出 CSV</a></Button>
          {!archived && <Button type="button" variant="outline" size="sm" onClick={() => setDialog("process")}><Workflow size={15} />批量处理{selected.size ? " · " + selected.size : ""}</Button>}
        </div>
      </div>
      <form className="filter-bar" onSubmit={apply}>
        <label className="search-box"><Search size={17} /><input className="field" value={draft.q} placeholder="搜索标题、作者、DOI" aria-label="搜索论文" onChange={(e) => update("q", e.target.value)} /></label>
        <label><span className="sr-only">出版来源</span><SourceField value={draft.sourceId} change={(value) => update("sourceId", value)} placeholder="出版来源" /></label>
        <div className="year-filter"><input className="field" type="number" min={1000} max={9999} aria-label="起始年份" placeholder="起始年" value={draft.yearFrom} onChange={(e) => update("yearFrom", e.target.value)} />
          <span>–</span><input className="field" type="number" min={1000} max={9999} aria-label="结束年份" placeholder="结束年" value={draft.yearTo} onChange={(e) => update("yearTo", e.target.value)} /></div>
        <select className="field" aria-label="处理状态" value={draft.status} onChange={(e) => update("status", e.target.value as PaperStatus)}>
          <option value="">全部状态</option><option value="PUBLISHED">已发布</option><option value="READY">待导入全文</option><option value="UNPROCESSED">待解析</option><option value="FAILED">处理失败</option><option value="UNSUPPORTED">格式暂不支持</option>
        </select>
        <Button type="submit">查询</Button>
        {Object.values(filter).some(Boolean) && <Button type="button" variant="ghost" onClick={() => { setDraft(initial); setFilter(initial); setSelected(new Set()); setPage(0) }}>重置</Button>}
      </form>
      {result.error ? <div className="p-5"><Notice error>{result.error.message}</Notice><Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button></div> :
        result.loading && !rows.length ? <Loading /> : !rows.length ?
          <Empty title={archived ? "没有归档论文" : "没有找到论文"}>调整筛选条件，或通过批量导入添加原件。</Empty> :
          <div className="table-scroll">
            <table className="data-table paper-table">
              <thead><tr>{!archived && <th className="check-cell"><input type="checkbox" checked={allChecked} onChange={togglePage} aria-label="选择本页全部论文" /></th>}
                <th>论文</th><th>来源 / 年份</th><th>原件</th><th>全文状态</th><th>最近更新</th><th className="w-10"><span className="sr-only">详情</span></th></tr></thead>
              <tbody>{rows.map((paper) => <tr key={paper.paperId} className={selected.has(paper.paperId) ? "row-selected" : ""}>
                {!archived && <td className="check-cell"><input type="checkbox" checked={selected.has(paper.paperId)} onChange={() => toggle(paper.paperId)} aria-label={"选择 " + (paper.title || paper.paperId)} /></td>}
                <td className="title-cell"><a href={paperPath(paper.paperId)}><span title={paper.title ?? undefined}>{paper.title || paper.paperId}</span></a><small title={paper.authors ?? undefined}>{paper.authors?.replaceAll(";", " · ") || "作者未登记"}</small></td>
                <td className="source-cell"><span title={paper.sourceName || paper.sourceId || undefined}>{paper.sourceName || paper.sourceId || "—"}</span><small>{paper.year ?? "—"}</small></td>
                <td><span className="format-chip"><FileText size={13} />{paper.originalType || "—"}</span></td>
                <td><Chip status={paper.status} /></td>
                <td className="table-date">{dateLabel(paper.updatedAt)}</td>
                <td><Button asChild variant="ghost" size="icon"><a href={paperPath(paper.paperId)} aria-label={"查看 " + (paper.title || paper.paperId)}><ChevronRight size={17} /></a></Button></td>
              </tr>)}</tbody>
            </table>
          </div>}
      {result.data && !result.error && <Pagination page={page} size={size} total={result.data.total} change={setPage} />}
    </section>
    {dialog === "upload" && <MetadataForm close={() => setDialog(null)} done={(paper) => window.location.assign(paperPath(paper.paperId))} />}
    {dialog === "archive" && <ArchiveUpload close={() => setDialog(null)} />}
    {dialog === "process" && <ProcessPapers ids={[...selected]} close={() => setDialog(null)} />}
  </>
}

function Metric({ icon, label, value, color }: { icon: React.ReactNode; label: string; value: string; color: string }) {
  return <div className="metric"><span className={"metric-icon metric-" + color}>{icon}</span><div><span>{label}</span><strong>{value}</strong></div></div>
}
