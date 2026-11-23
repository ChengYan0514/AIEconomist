import { CalendarRange, Database, Search } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, errorMessage, jobPath, params, useQuery } from "./api"
import { Empty, Loading, Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import type { Job, Source } from "./types"

export function OpenAlex() {
  const [input, setInput] = useState("")
  const [term, setTerm] = useState("")
  const [source, setSource] = useState<Source>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const result = useQuery<Source[]>("/sources" + params({ q: term }))

  async function start(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!source) return
    const values = new FormData(event.currentTarget)
    const yearFrom = values.get("yearFrom")
    const yearTo = values.get("yearTo")
    setBusy(true); setError("")
    try {
      const job = await api<Job>("/jobs", { method: "POST", body: JSON.stringify({
        kind: "METADATA", sourceId: source.sourceId, yearFrom: yearFrom ? Number(yearFrom) : undefined,
        yearTo: yearTo ? Number(yearTo) : undefined,
      }) })
      window.location.assign(jobPath(job.jobId))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <>
    <SectionHeader title="OpenAlex" description="从快照按出版来源导入作品元数据，供论文匹配使用。" />
    <div className="detail-grid">
      <section className="panel">
        <div className="panel-bar"><div className="panel-title"><Database size={19} /><strong>出版来源</strong></div></div>
        <form className="source-search" onSubmit={(event) => { event.preventDefault(); setTerm(input) }}>
          <label className="search-box"><Search size={17} /><input className="field" aria-label="搜索出版来源" placeholder="输入期刊名称或 OpenAlex 来源 ID" value={input} onChange={(event) => setInput(event.target.value)} /></label>
          <Button type="submit">搜索</Button>
        </form>
        {result.error ? <div className="p-4"><Notice error>{result.error.message}</Notice></div> : !result.data ? <Loading /> :
          result.data.length === 0 ? <Empty title="没有找到出版来源">可以尝试输入完整的 OpenAlex 来源 ID。</Empty> :
            <div className="table-scroll"><table className="data-table"><thead><tr><th>来源名称</th><th>OpenAlex ID</th><th>操作</th></tr></thead>
              <tbody>{result.data.map((item) => <tr key={item.sourceId}>
                <td className="title-cell">{item.name}</td><td className="mono">{item.sourceId}</td>
                <td><Button size="sm" variant={source?.sourceId === item.sourceId ? "default" : "outline"} onClick={() => setSource(item)}>{source?.sourceId === item.sourceId ? "已选择" : "选择"}</Button></td>
              </tr>)}</tbody></table></div>}
      </section>
      <aside className="panel detail-card">
        <div className="detail-card-title"><CalendarRange size={18} /><h2>导入元数据</h2></div>
        {source ? <div className="info-box"><strong>{source.name}</strong><code>{source.sourceId}</code></div> :
          <p className="muted text-sm">从来源列表选择要导入的期刊。</p>}
        <form className="form-stack mt-4" onSubmit={start}>
          {error && <Notice error>{error}</Notice>}
          <label>起始年份<input className="field" type="number" min={1000} max={9999} name="yearFrom" placeholder="不限" /></label>
          <label>结束年份<input className="field" type="number" min={1000} max={9999} name="yearTo" placeholder="不限" /></label>
          <Button type="submit" disabled={busy || !source}>{busy ? "正在提交…" : "提交导入"}</Button>
        </form>
      </aside>
    </div>
  </>
}
