import { ArrowRight, BookMarked, FileText, GitBranch, Layers, Network, Sparkles } from "lucide-react"
import { useQuery } from "./api"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { usePermissions } from "./permissions"
import type { JournalDirectory } from "./types"

const roadmap = [
  {
    step: "01",
    icon: Sparkles,
    title: "因果主张抽取（Causal Claim Extraction）",
    summary: "基于已发布结构化全文内容块，按期刊与年份批次抽取变量间因果关系、方向、识别策略及原文证据锚点。",
    status: "上游已就绪（依赖已发布全文）",
  },
  {
    step: "02",
    icon: Layers,
    title: "经济学词表标准化（STW / Concept Alignment）",
    summary: "将异构变量表述映射到标准经济学主题词表与概念节点，支持人工复核、同义合并与证据溯源。",
    status: "规划中",
  },
  {
    step: "03",
    icon: GitBranch,
    title: "图谱发布、版本修订与客户端检索",
    summary: "按数据集修订号原子发布因果图谱快照，连接 OpenAlex 书目与论文全文，向 AI Economist 客户端提供查询服务。",
    status: "规划中",
  },
]

export function CausalGraphPreview() {
  const { can } = usePermissions()
  const canReadPapers = can("PAPER_READ")
  const corpus = useQuery<JournalDirectory>(canReadPapers ? "/journals?size=1" : null)
  const summary = corpus.data?.summary
  const readyPct = summary && summary.totalPapers > 0
    ? Math.round((summary.publishedPapers / summary.totalPapers) * 100) : 0

  return <>
    <SectionHeader
      title="因果图谱"
      description="面向经济学实证文献的因果主张抽取、概念对齐与图谱治理工作区（规划扩展模块）。"
      actions={canReadPapers && <>
        <Button asChild variant="outline"><a href="/journals"><BookMarked size={15} aria-hidden="true" />查看期刊覆盖</a></Button>
        <Button asChild><a href="/papers?status=PUBLISHED"><FileText size={15} aria-hidden="true" />浏览已发布全文</a></Button>
      </>}
    />

    <section className="panel causal-hero">
      <div className="causal-hero-main">
        <span className="auth-step-badge"><Network size={13} aria-hidden="true" />知识图谱 · 模块规划中</span>
        <h2>以结构化全文与 OpenAlex 书目为底座构建经济学因果图谱</h2>
        <p className="muted">
          当前版本已完成前端导航域预留与上游语料治理闭环。接入因果抽取服务后，管理员可直接在本域发起抽取任务、核验主张词表并发布可追溯的图谱数据集修订。
        </p>
      </div>
      {summary && <div className="causal-readiness">
        <div className="causal-readiness-label">当前上游语料就绪度</div>
        <div className="causal-readiness-stats">
          <div>
            <strong className="tabular-nums">{summary.publishedPapers.toLocaleString()}</strong>
            <span>篇已发布全文（{readyPct}%）</span>
          </div>
          <div>
            <strong className="tabular-nums">{summary.matchedPapers.toLocaleString()}</strong>
            <span>篇已关联 OpenAlex</span>
          </div>
          <div>
            <strong className="tabular-nums">{summary.totalJournals.toLocaleString()}</strong>
            <span>种已覆盖期刊</span>
          </div>
        </div>
      </div>}
    </section>

    <section className="causal-roadmap-grid" aria-label="因果图谱能力路线">
      {roadmap.map((item) => {
        const Icon = item.icon
        return <article className="panel causal-stage-card" key={item.step}>
          <div className="causal-stage-top">
            <span className="causal-stage-num">{item.step}</span>
            <span className="role-pill">{item.status}</span>
          </div>
          <div className="causal-stage-icon" aria-hidden="true"><Icon size={18} strokeWidth={1.7} /></div>
          <h3>{item.title}</h3>
          <p className="muted">{item.summary}</p>
        </article>
      })}
    </section>

    {canReadPapers && <div className="panel causal-next-strip">
      <div>
        <strong>当前可提前完成的准备工作</strong>
        <p className="muted m-0">优先补齐核心期刊的全文发布与 OpenAlex 作品关联，可显著提升后续因果抽取的覆盖率与引文溯源质量。</p>
      </div>
      <Button asChild variant="outline"><a href="/journals">前往期刊目录排查积压<ArrowRight size={14} aria-hidden="true" /></a></Button>
    </div>}
  </>
}
