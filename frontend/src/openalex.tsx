import { CalendarRange, Database, Search } from "lucide-react"
import { useEffect, useState, type FormEvent } from "react"
import { api, errorMessage, jobPath, params, useQuery } from "./api"
import { Empty, Loading, Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { navigate, useLocation } from "./router"
import type { Job, Source } from "./types"

export function OpenAlex() {
  const loc = useLocation()
  const initialSource = new URLSearchParams(loc.search).get("sourceId") ?? ""
  const [input, setInput] = useState(initialSource)
  const [term, setTerm] = useState(initialSource)
  const [source, setSource] = useState<Source>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const result = useQuery<Source[]>("/sources" + params({ q: term }))

  useEffect(() => {
    const requested = new URLSearchParams(loc.search).get("sourceId") ?? ""
    if (requested) {
      setInput(requested)
      setTerm(requested)
    }
  }, [loc.search])

  useEffect(() => {
    const requested = new URLSearchParams(loc.search).get("sourceId")
    if (!requested || !result.data || source?.sourceId.toUpperCase() === requested.toUpperCase()) return
    const matched = result.data.find((item) => item.sourceId.toUpperCase() === requested.toUpperCase())
    if (matched) setSource(matched)
  }, [loc.search, result.data, source])

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
      navigate(jobPath(job.jobId))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <>
    <SectionHeader title="OpenAlex" description="从只读快照按出版来源导入作品元数据，供论文库 DOI 与书目匹配使用。" />
    <div className="detail-grid">
      <section className="panel">
        <div className="panel-bar"><div className="panel-title"><Database size={17} strokeWidth={1.7} aria-hidden="true" /><strong>出版来源目录</strong></div></div>
        <form className="source-search" onSubmit={(event) => { event.preventDefault(); setTerm(input) }}>
          <label className="search-box"><Search size={16} aria-hidden="true" /><input className="field" aria-label="搜索出版来源" placeholder="输入期刊名称或 OpenAlex 来源 ID" value={input} onChange={(event) => setInput(event.target.value)} /></label>
          <Button type="submit" variant="outline">搜索</Button>
        </form>
        {result.error ? <div className="p-4"><Notice error>{result.error.message}</Notice></div> : !result.data ? <Loading /> :
          result.data.length === 0 ? <Empty title="没有找到出版来源">可以尝试输入完整的 OpenAlex 来源 ID。</Empty> :
            <div className="table-scroll"><table className="data-table"><thead><tr><th>来源名称</th><th>OpenAlex ID</th><th>操作</th></tr></thead>
              <tbody>{result.data.map((item) => {
                const active = source?.sourceId === item.sourceId
                return <tr key={item.sourceId} className={active ? "row-selected" : ""}>
                  <td className="title-cell">{item.name}</td><td className="mono muted">{item.sourceId}</td>
                  <td><Button size="sm" variant={active ? "default" : "outline"} onClick={() => setSource(item)}>{active ? "已选择" : "选择"}</Button></td>
                </tr>
              })}</tbody></table></div>}
      </section>
      <aside className="detail-aside">
        <section className="panel detail-card">
          <div className="detail-card-title"><CalendarRange size={16} strokeWidth={1.7} aria-hidden="true" /><h2>导入元数据</h2></div>
          {source ? <div className="info-box"><strong>{source.name}</strong><code className="mono">{source.sourceId}</code></div> :
            <p className="muted text-sm leading-relaxed">从左侧目录选择要导入作品元数据的出版来源。</p>}
          <form className="form-stack mt-4" onSubmit={start}>
            {error && <Notice error>{error}</Notice>}
            <label>起始年份<input className="field" type="number" min={1000} max={9999} name="yearFrom" placeholder="不限" /></label>
            <label>结束年份<input className="field" type="number" min={1000} max={9999} name="yearTo" placeholder="不限" /></label>
            <Button type="submit" disabled={busy || !source}>{busy ? "正在提交…" : "提交导入"}</Button>
          </form>
        </section>
      </aside>
    </div>
  </>
}
