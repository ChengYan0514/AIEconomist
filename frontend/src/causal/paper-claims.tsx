import { ArrowLeft, ExternalLink, FileText } from "lucide-react"
import { useMemo } from "react"
import { paperPath, useQuery } from "../api"
import { Empty, Loading, Notice } from "../components/shared"
import { Button } from "../components/ui/button"
import { SectionHeader } from "../components/workspace"
import { usePermissions } from "../permissions"
import { CausalForceGraph, EvidenceTable } from "./components"
import type { CausalPaperDetail } from "./types"

export function CausalPaperClaimsPage({ workId }: { workId: string }) {
  const { can } = usePermissions()
  const res = useQuery<CausalPaperDetail>("/causal/papers/" + encodeURIComponent(workId))
  const detail = res.data

  const sortedClaims = useMemo(() => {
    if (!detail) return []
    return [...detail.claims].sort((a, b) => Number(b.isMainContribution === true) - Number(a.isMainContribution === true))
  }, [detail])

  return <>
    <SectionHeader
      editorial
      title={detail?.paper.title || workId}
      description="论文因果声明 · 展示单篇论文的因果网络与结构化证据明细。"
      back={<a href="/causal-graph" className="back-link"><ArrowLeft size={14} aria-hidden="true" />返回因果知识图谱</a>}
      actions={detail?.paper.paperId && can("PAPER_READ") && <Button asChild variant="outline">
        <a href={paperPath(detail.paper.paperId)}><FileText size={15} aria-hidden="true" />打开本地论文详情<ExternalLink size={13} aria-hidden="true" /></a>
      </Button>}
    />
    {res.error ? <Notice error>{res.error.message}</Notice> : !detail ? <Loading label="正在加载论文因果声明…" /> : <>
      <div className="causal-paper-tags">
        <code className="id-plate mono">{workId}</code>
        {detail.paper.publicationYear && <span className="role-pill">{detail.paper.publicationYear} 年</span>}
        {detail.paper.sourceName && <span className="role-pill">{detail.paper.sourceName}</span>}
        {detail.paper.subfieldName && <span className="role-pill">{detail.paper.subfieldName}</span>}
        {detail.paper.topicName && <span className="role-pill">{detail.paper.topicName}</span>}
      </div>

      <section className="panel causal-kpi-strip causal-kpi-four mt-4">
        <div className="causal-kpi-cell"><span>声明记录</span><strong>{detail.claims.length}</strong></div>
        <div className="causal-kpi-cell"><span>图谱节点</span><strong>{detail.paperGraph.nodes.length}</strong></div>
        <div className="causal-kpi-cell"><span>图谱关系</span><strong>{detail.paperGraph.edges.length}</strong></div>
        <div className="causal-kpi-cell"><span>核心发现</span><strong>{detail.claims.filter((c) => c.isMainContribution).length}</strong></div>
      </section>

      {detail.claims.length === 0 ? <section className="panel mt-4">
        <Empty title="该作品暂无已入库的声明记录">可通过「因果抽取」对该论文发起抽取，或在任务详情查看模型原始响应。</Empty>
      </section> : <>
        <div className="mt-4"><CausalForceGraph data={detail.paperGraph} height={400} /></div>
        <section className="panel mt-5">
          <div className="panel-bar">
            <strong>声明明细</strong>
            <span className="muted text-sm">高亮行（核心发现） · 浅色行（非核心发现）</span>
          </div>
          <EvidenceTable claims={sortedClaims} showRelation />
        </section>
      </>}
    </>}
  </>
}
