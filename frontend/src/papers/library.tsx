import { Archive, BookOpenCheck, Download, Plus, Search, Upload, Workflow } from "lucide-react"
import { useState, type FormEvent } from "react"
import { paperPath, params, useQuery } from "../api"
import { Chip, Empty, Loading, Notice, Pagination, SourceField } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
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
    <SectionHeader title="论文库" actions={<>
      <Button asChild variant="outline"><a href={"/api/papers/export" + query}><Download size={17} />导出 CSV</a></Button>
      <Button type="button" variant="outline" onClick={() => setDialog("upload")}><Plus size={17} />上传单篇</Button>
      <Button type="button" onClick={() => setDialog("archive")}><Upload size={17} />批量导入</Button>
    </>} />
    <form className="filter-bar" onSubmit={apply}>
      <label className="search-box"><Search size={17} /><input className="field" type="search" value={draft.q} placeholder="搜索标题、作者、DOI 或论文 ID" aria-label="搜索论文" onChange={(e) => update("q", e.target.value)} /></label>
      <label><span className="sr-only">出版来源</span><SourceField value={draft.sourceId} change={(value) => update("sourceId", value)} placeholder="出版来源" /></label>
      <div className="year-filter"><input className="field" type="number" min={1000} max={9999} aria-label="起始年份" placeholder="起始年" value={draft.yearFrom} onChange={(e) => update("yearFrom", e.target.value)} />
        <span>–</span><input className="field" type="number" min={1000} max={9999} aria-label="结束年份" placeholder="结束年" value={draft.yearTo} onChange={(e) => update("yearTo", e.target.value)} /></div>
      <select className="field" aria-label="处理状态" value={draft.status} onChange={(e) => update("status", e.target.value as PaperStatus)}>
        <option value="">全文状态</option><option value="PUBLISHED">已发布全文</option><option value="READY">待导入全文</option><option value="UNPROCESSED">待解析</option><option value="FAILED">处理失败</option><option value="UNSUPPORTED">格式暂不支持</option>
      </select>
      <Button type="submit" variant="outline">查询</Button>
      {Object.values(filter).some(Boolean) && <Button type="button" variant="ghost" onClick={() => { setDraft(initial); setFilter(initial); setSelected(new Set()); setPage(0) }}>重置</Button>}
    </form>
    <div className="library-toolbar">
      <div className="panel-tabs" role="group" aria-label="论文库视图">
        <button type="button" aria-pressed={!archived} onClick={() => switchArchive(false)}><BookOpenCheck size={16} />论文列表</button>
        <button type="button" aria-pressed={archived} onClick={() => switchArchive(true)}><Archive size={16} />归档</button>
      </div>
      {!archived && selected.size === 0 && <Button type="button" variant="outline" size="sm" onClick={() => setDialog("process")}><Workflow size={15} />批量处理</Button>}
    </div>
    {selected.size > 0 && <div className="selection-bar">
      <span role="status">已选择 {selected.size} 篇</span>
      <Button type="button" variant="ghost" size="sm" onClick={() => setDialog("process")}><Workflow size={15} />批量处理</Button>
      <Button type="button" variant="ghost" size="sm" onClick={() => setSelected(new Set())}>取消选择</Button>
    </div>}
    <section className="panel library-panel">
      {result.error ? <div className="p-5"><Notice error>{result.error.message}</Notice><Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button></div> :
        result.loading && !rows.length ? <Loading /> : !rows.length ?
          <Empty title={archived ? "没有归档论文" : "没有找到论文"}>调整筛选条件，或通过批量导入添加原件。</Empty> :
          <div className="table-scroll" tabIndex={0} role="region" aria-label="论文列表表格">
            <table className="data-table paper-table">
              <thead><tr>{!archived && <th className="check-cell"><input type="checkbox" checked={allChecked} onChange={togglePage} aria-label="选择本页全部论文" /></th>}
                <th scope="col">论文</th><th scope="col">出版来源</th><th scope="col">年份</th><th scope="col">全文状态</th><th scope="col">OpenAlex</th></tr></thead>
              <tbody>{rows.map((paper) => <tr key={paper.paperId} className={selected.has(paper.paperId) ? "row-selected" : ""}>
                {!archived && <td className="check-cell"><input type="checkbox" checked={selected.has(paper.paperId)} onChange={() => toggle(paper.paperId)} aria-label={"选择 " + (paper.title || paper.paperId)} /></td>}
                <td className="title-cell"><a href={paperPath(paper.paperId)}>{paper.title || paper.paperId}</a><small>{paper.authors?.replaceAll(";", " · ") || "作者未登记"}</small></td>
                <td className="source-cell">{paper.sourceName || paper.sourceId || "—"}</td>
                <td>{paper.year ?? "—"}</td>
                <td><Chip status={paper.status} /></td>
                <td className="association-cell">{paper.matchedWorkId ? <>已关联<small>{paper.matchedWorkId}</small></> : "未关联"}</td>
              </tr>)}</tbody>
            </table>
          </div>}
    </section>
    {result.data && !result.error && <Pagination page={page} size={size} total={result.data.total} change={setPage} />}
    {dialog === "upload" && <MetadataForm close={() => setDialog(null)} done={(paper) => window.location.assign(paperPath(paper.paperId))} />}
    {dialog === "archive" && <ArchiveUpload close={() => setDialog(null)} />}
    {dialog === "process" && <ProcessPapers ids={[...selected]} close={() => setDialog(null)} />}
  </>
}
