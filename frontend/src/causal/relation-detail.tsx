import { ArrowLeft } from "lucide-react"
import { ApiError, useQuery } from "../api"
import { Empty, Loading, Notice } from "../components/shared"
import { SectionHeader } from "../components/workspace"
import { EvidenceTable, SignBadge, signColor } from "./components"
import { variablePath, type CausalEdgeDetail } from "./types"

export function CausalRelationDetailPage({ cause, effect, claimId }: { cause?: string; effect?: string; claimId?: string }) {
  const path = claimId
    ? "/causal/relations/" + encodeURIComponent(claimId)
    : cause && effect
      ? "/causal/relations?" + new URLSearchParams({ cause, effect }).toString()
      : null
  const res = useQuery<CausalEdgeDetail>(path)
  const missing = res.error instanceof ApiError && res.error.status === 404
  const detail = res.data

  const pos = detail?.edge.signBreakdown.positive ?? 0
  const neg = detail?.edge.signBreakdown.negative ?? 0
  const nul = detail?.edge.signBreakdown.null ?? 0
  const mix = detail?.edge.signBreakdown.mixed ?? 0
  const total = Math.max(pos + neg + nul + mix, 1)

  return <>
    <SectionHeader
      editorial
      title={detail ? `${detail.edge.source} → ${detail.edge.target}` : (cause && effect ? `${cause} → ${effect}` : "因果关系详情")}
      description="查看该因果关系在不同论文与识别方法中的方向共识、分歧程度与原文证据摘录。"
      back={<a href="/causal-graph" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回因果图谱</a>}
    />
    {!path || missing ? <section className="panel"><Empty title="暂无该因果关系的证据记录">请检查变量拼写，或回到因果图谱通过 STW 词表自动补全重新检索。</Empty></section> :
      res.error ? <Notice error>{res.error.message}</Notice> : !detail ? <Loading label="正在加载关系证据…" /> : <>
        <section className="panel causal-relation-hero">
          <div className="causal-relation-endpoints">
            <a href={variablePath(detail.edge.source)} className="inline-link">{detail.edge.source}</a>
            <span className="muted" aria-hidden="true">→</span>
            <a href={variablePath(detail.edge.target)} className="inline-link">{detail.edge.target}</a>
            <SignBadge value={detail.edge.dominantSignCategory} />
          </div>
          <div className="causal-ratio-bar" role="img" aria-label={`正向 ${pos}，负向 ${neg}，无显著 ${nul}，混合 ${mix}`}>
            {pos > 0 && <span style={{ width: `${(pos / total) * 100}%`, background: signColor("positive") }} />}
            {neg > 0 && <span style={{ width: `${(neg / total) * 100}%`, background: signColor("negative") }} />}
            {nul > 0 && <span style={{ width: `${(nul / total) * 100}%`, background: signColor("null") }} />}
            {mix > 0 && <span style={{ width: `${(mix / total) * 100}%`, background: signColor("mixed") }} />}
          </div>
          <div className="causal-ratio-legend">
            <span>正向 <strong>{pos}</strong> 条</span>
            <span>负向 <strong>{neg}</strong> 条</span>
            <span>无显著 <strong>{nul}</strong> 条</span>
            <span>混合 <strong>{mix}</strong> 条</span>
            <span>分歧度 <strong>{Math.round(detail.edge.disagreement * 100)}%</strong></span>
          </div>
        </section>

        <section className="panel causal-kpi-strip causal-kpi-five mt-4">
          <div className="causal-kpi-cell"><span>主张记录</span><strong>{detail.edge.recordCount.toLocaleString()}</strong></div>
          <div className="causal-kpi-cell"><span>验证论文</span><strong>{detail.edge.paperCount.toLocaleString()}</strong></div>
          <div className="causal-kpi-cell"><span>识别方法数</span><strong>{detail.edge.diversity}</strong></div>
          <div className="causal-kpi-cell"><span>覆盖子领域</span><strong>{detail.stats.subfieldCount}</strong></div>
          <div className="causal-kpi-cell"><span>发表年份跨度</span><strong>{detail.stats.yearSpan} 年</strong></div>
        </section>

        {detail.stats.methods.length > 0 && <div className="causal-method-strip mt-4">
          <span className="muted text-sm">涉及识别方法：</span>
          {detail.stats.methods.map((m) => <span key={m} className="role-pill">{m}</span>)}
        </div>}

        <section className="panel mt-4">
          <div className="panel-bar">
            <strong>因果主张证据记录（{detail.claims.length}）</strong>
            <span className="muted text-sm">点击行首箭头展开原文证据与原始主张</span>
          </div>
          <EvidenceTable claims={detail.claims} />
        </section>
      </>}
  </>
}
