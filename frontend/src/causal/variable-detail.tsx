import { ArrowLeft } from "lucide-react"
import { useMemo } from "react"
import { useQuery } from "../api"
import { Loading, Notice } from "../components/shared"
import { SectionHeader } from "../components/workspace"
import { CausalForceGraph, EdgeTable } from "./components"
import type { CausalGraphData, CausalNodeDetail } from "./types"

export function CausalVariableDetailPage({ variable }: { variable: string }) {
  const res = useQuery<CausalNodeDetail>("/causal/variables/" + encodeURIComponent(variable))
  const detail = res.data

  const localGraph = useMemo<CausalGraphData>(() => {
    if (!detail) return { nodes: [], edges: [] }
    const edges = [...detail.outgoing.slice(0, 30), ...detail.incoming.slice(0, 30)]
    const nodes = [detail.node]
    const seen = new Set([detail.node.id])
    edges.forEach((edge) => {
      if (!seen.has(edge.source)) {
        nodes.push({ id: edge.source, label: edge.source, occurrences: edge.recordCount, dominantSubfield: null, asCauseCount: edge.recordCount, asEffectCount: 0 })
        seen.add(edge.source)
      }
      if (!seen.has(edge.target)) {
        nodes.push({ id: edge.target, label: edge.target, occurrences: edge.recordCount, dominantSubfield: null, asCauseCount: 0, asEffectCount: edge.recordCount })
        seen.add(edge.target)
      }
    })
    return { nodes, edges }
  }, [detail])

  return <>
    <SectionHeader
      editorial
      title={variable}
      description="因果变量的一跳因果网络、学科子领域分布、发表年份趋势及出入边关系明细。"
      back={<a href="/causal-graph" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回因果图谱</a>}
    />
    {res.error ? <Notice error>{res.error.message}</Notice> : !detail ? <Loading label="正在加载变量详情…" /> : <>
      <section className="panel causal-kpi-strip causal-kpi-four">
        <div className="causal-kpi-cell"><span>关联主张总数</span><strong>{detail.totalClaims.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>作为原因（出边）</span><strong>{detail.node.asCauseCount.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>作为结果（入边）</span><strong>{detail.node.asEffectCount.toLocaleString()}</strong></div>
        <div className="causal-kpi-cell"><span>主学科子领域</span><strong className="causal-kpi-text" title={detail.node.dominantSubfield || "未标注"}>{detail.node.dominantSubfield || "未标注"}</strong></div>
      </section>

      <CausalForceGraph data={localGraph} height={460} highlightNode={detail.node.id} />

      <div className="causal-two-col mt-5">
        <DistributionPanel title="学科子领域分布" entries={Object.entries(detail.subfieldCounts).slice(0, 8)} />
        <DistributionPanel title="发表年份分布" entries={Object.entries(detail.yearCounts)} />
      </div>

      <div className="causal-two-col mt-5">
        <section className="panel">
          <div className="panel-bar">
            <strong>出边 · 作为原因（{detail.outgoing.length}）</strong>
          </div>
          <EdgeTable edges={detail.outgoing} emptyText="该变量暂无作为原因的因果关系" />
        </section>
        <section className="panel">
          <div className="panel-bar">
            <strong>入边 · 作为结果（{detail.incoming.length}）</strong>
          </div>
          <EdgeTable edges={detail.incoming} emptyText="该变量暂无作为结果的因果关系" />
        </section>
      </div>
    </>}
  </>
}

function DistributionPanel({ title, entries }: { title: string; entries: [string, number][] }) {
  const max = Math.max(...entries.map(([, count]) => count), 1)
  return <section className="panel">
    <div className="panel-bar"><strong>{title}</strong></div>
    {entries.length === 0 ? <p className="muted p-5 m-0">暂无分布统计</p> : <div className="causal-bars-body">
      {entries.map(([name, count]) => (
        <div key={name} className="causal-bar-row">
          <div className="causal-bar-meta">
            <span>{name}</span>
            <strong className="tabular-nums">{count.toLocaleString()}</strong>
          </div>
          <div className="mini-meter" aria-hidden="true">
            <span className="mini-meter-fill is-blue" style={{ width: `${Math.max(4, Math.round((count / max) * 100))}%` }} />
          </div>
        </div>
      ))}
    </div>}
  </section>
}
