import { ArrowUpDown, Network, Search } from "lucide-react"
import { useMemo, useState } from "react"
import { useQuery } from "../api"
import { Empty, Loading, Notice, Pagination } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import {
  relationPath, variablePath,
  type CausalFieldAnalysis, type CausalGraphSummary,
} from "./types"

type SortField = "claimRecordCount" | "paperCount" | "variableCount"

export function CausalFieldsPage() {
  const fieldsRes = useQuery<CausalFieldAnalysis>("/causal/fields")
  const summaryRes = useQuery<CausalGraphSummary>("/causal/summary")
  const [subfieldQuery, setSubfieldQuery] = useState("")
  const [topicQuery, setTopicQuery] = useState("")
  const [sortBy, setSortBy] = useState<SortField>("claimRecordCount")
  const [sortAsc, setSortAsc] = useState(false)
  const [page, setPage] = useState(0)
  const pageSize = 20

  const filtered = useMemo(() => {
    const items = fieldsRes.data?.items ?? []
    const sf = subfieldQuery.trim().toLowerCase()
    const tp = topicQuery.trim().toLowerCase()
    return items
      .filter((item) => item.subfield.toLowerCase().includes(sf) && item.topic.toLowerCase().includes(tp))
      .sort((a, b) => sortAsc ? a[sortBy] - b[sortBy] : b[sortBy] - a[sortBy])
  }, [fieldsRes.data, subfieldQuery, topicQuery, sortBy, sortAsc])

  function toggleSort(field: SortField) {
    if (sortBy === field) setSortAsc((v) => !v)
    else { setSortBy(field); setSortAsc(false) }
    setPage(0)
  }

  const failure = fieldsRes.error ?? summaryRes.error
  const fields = fieldsRes.data
  const summary = summaryRes.data
  const pageRows = filtered.slice(page * pageSize, (page + 1) * pageSize)

  return <>
    <SectionHeader
      title="领域分析"
      description="从学科子领域、细分主题、高频因果关系与实证识别方法分布透视全库经济学因果知识结构。"
      actions={<Button asChild variant="outline"><a href="/causal-graph"><Network size={15} aria-hidden="true" />探索因果图谱</a></Button>}
    />
    {failure ? <Notice error>{failure.message}</Notice> : !fields || !summary ? <Loading label="正在加载领域分析…" /> : <>
      <section className="panel causal-kpi-strip causal-kpi-four" aria-label="领域分析全库统计">
        <div className="causal-kpi-cell"><span>因果主张</span><strong>{summary.overview.totalClaimRecords.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>因果关系</span><strong>{summary.overview.totalStandardClaims.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>覆盖论文</span><strong>{summary.overview.totalPapers.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>因果变量</span><strong>{summary.overview.totalNodes.toLocaleString()}</strong></div>
      </section>

      <div className="causal-insights-grid mt-5">
        <section className="panel">
          <div className="panel-bar"><strong>高频因果关系（Top {fields.insights.topRelations.length}）</strong></div>
          <div className="causal-insight-list">
            {fields.insights.topRelations.length === 0 ? <p className="muted m-0">暂无高频关系</p> :
              fields.insights.topRelations.map((rel) => (
                <div className="causal-insight-rel" key={`${rel.cause}-${rel.effect}`}>
                  <a className="inline-link font-medium" href={relationPath(rel.cause, rel.effect)}>
                    {rel.cause} → {rel.effect}
                  </a>
                  <span className="muted text-xs tabular-nums">
                    {rel.claimRecordCount.toLocaleString()} 条主张 · {rel.paperCount.toLocaleString()} 篇论文 · {rel.methodCount} 种方法
                  </span>
                </div>
              ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-bar"><strong>高频因果变量（Top {fields.insights.topVariables.length}）</strong></div>
          <div className="causal-insight-list">
            {fields.insights.topVariables.length === 0 ? <p className="muted m-0">暂无高频变量</p> :
              fields.insights.topVariables.map((v) => (
                <a className="causal-var-row" key={v.name} href={variablePath(v.name)}>
                  <span>{v.name}</span>
                  <strong className="tabular-nums">{v.count.toLocaleString()}</strong>
                </a>
              ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-bar"><strong>识别方法分布（Top 10 + 其他）</strong></div>
          <div className="causal-bars-body">
            {fields.insights.methodCounts.length === 0 ? <p className="muted m-0">暂无方法统计</p> :
              fields.insights.methodCounts.map((m) => {
                const pct = summary.overview.totalClaimRecords > 0
                  ? Math.round((m.count / summary.overview.totalClaimRecords) * 100) : 0
                return <div key={m.name} className="causal-bar-row">
                  <div className="causal-bar-meta">
                    <span>{m.name}</span>
                    <span className="muted tabular-nums">{m.count.toLocaleString()} · {pct}%</span>
                  </div>
                  <div className="mini-meter" aria-hidden="true">
                    <span className="mini-meter-fill is-blue" style={{ width: `${Math.max(2, pct)}%` }} />
                  </div>
                </div>
              })}
          </div>
        </section>
      </div>

      <section className="panel mt-5">
        <div className="panel-bar causal-fields-bar">
          <strong>学科子领域与主题明细（{filtered.length}）</strong>
          <div className="causal-fields-filters">
            <label className="search-box">
              <Search size={14} aria-hidden="true" />
              <input className="field" type="search" aria-label="筛选学科子领域" placeholder="筛选学科子领域…"
                value={subfieldQuery} onChange={(e) => { setSubfieldQuery(e.target.value); setPage(0) }} />
            </label>
            <label className="search-box">
              <Search size={14} aria-hidden="true" />
              <input className="field" type="search" aria-label="筛选细分主题" placeholder="筛选细分主题…"
                value={topicQuery} onChange={(e) => { setTopicQuery(e.target.value); setPage(0) }} />
            </label>
          </div>
        </div>
        {pageRows.length === 0 ? <Empty title="没有匹配的学科子领域或主题" /> : <>
          <div className="table-scroll" tabIndex={0} role="region" aria-label="学科子领域与主题明细表">
            <table className="data-table">
              <thead><tr>
                <th scope="col">学科子领域</th>
                <th scope="col">细分主题</th>
                <th scope="col" className="text-right">
                  <button type="button" className="causal-sort-btn" onClick={() => toggleSort("claimRecordCount")}>
                    因果主张数<ArrowUpDown size={12} aria-hidden="true" />
                  </button>
                </th>
                <th scope="col" className="text-right">
                  <button type="button" className="causal-sort-btn" onClick={() => toggleSort("paperCount")}>
                    论文数<ArrowUpDown size={12} aria-hidden="true" />
                  </button>
                </th>
                <th scope="col" className="text-right">
                  <button type="button" className="causal-sort-btn" onClick={() => toggleSort("variableCount")}>
                    因果变量数<ArrowUpDown size={12} aria-hidden="true" />
                  </button>
                </th>
              </tr></thead>
              <tbody>{pageRows.map((item) => <tr key={`${item.subfield}-${item.topic}`}>
                <td className="font-medium">
                  <a className="inline-link" href={"/causal-graph?" + new URLSearchParams({ minRecordCount: "1", minDiversity: "1", subfields: item.subfield }).toString()}>
                    {item.subfield}
                  </a>
                </td>
                <td>{item.topic}</td>
                <td className="text-right tabular-nums font-medium">{item.claimRecordCount.toLocaleString()}</td>
                <td className="text-right tabular-nums">{item.paperCount.toLocaleString()}</td>
                <td className="text-right tabular-nums">{item.variableCount.toLocaleString()}</td>
              </tr>)}</tbody>
            </table>
          </div>
          <Pagination page={page} size={pageSize} total={filtered.length} change={setPage} />
        </>}
      </section>
    </>}
  </>
}
