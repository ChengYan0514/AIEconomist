import {
  BookOpen,
  Clock,
  Layers,
  MessageSquareText,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  SendHorizontal,
  Sparkles,
} from "lucide-react"
import { useState } from "react"
import { Button } from "../../components/ui/button"
import { JournalPicker } from "../journal-picker"
import type { ClientModule } from "../types"

interface QaSourcePreview {
  marker: number
  title: string
  authors: string
  venue: string
  year: number
  method: string
  excerpt: string
}

interface QaScenario {
  id: string
  title: string
  tag: string
  question: string
  summary: string
  paragraphs: { text: string; markers: number[] }[]
  methodNote: string
  sources: QaSourcePreview[]
}

const QA_SCENARIOS: QaScenario[] = [
  {
    id: "min-wage",
    title: "最低工资对就业的异质性证据汇总",
    tag: "劳动经济学",
    question: "近十年顶刊实证文献中，最低工资上调对低薪岗位就业与工资分布的异质性影响有哪些核心共识与分歧？",
    summary: "核心共识：州级温和上调主要压缩工资分布下尾，对总体低薪岗位数量的净影响接近零；分歧集中在高涨幅城市市场及可贸易行业。",
    paragraphs: [
      {
        text: "基于 1979–2016 年美国 138 次显著州级最低工资上调的事件研究与分布堆叠法表明，调薪后五年内低薪就业岗位总量基本未缩减，但底层分位工资显著抬升，形成明显的下尾压缩效应。",
        markers: [1],
      },
      {
        text: "相比之下，针对单一城市大幅度提薪的微观工时追踪研究发现，虽然在职低薪雇员小时工资显著上升，但小微企业工作时长与新入职岗位弹性呈现较强异质性，提示企业边际调整可能经由工时与价格传导而非直接裁员。",
        markers: [2],
      },
    ],
    methodNote: "主流识别策略：Bunching 分布法、事件研究（Event Study）、合成控制与双重差分（DID）。",
    sources: [
      {
        marker: 1,
        title: "The Effect of Minimum Wages on Low-Wage Jobs",
        authors: "Cengiz, Dube, Lindner & Zipperer",
        venue: "Quarterly Journal of Economics",
        year: 2019,
        method: "Event Study / Bunching",
        excerpt: "Overall number of low-wage jobs remained essentially unchanged over the five years following the increase, while wages at the bottom rose sharply.",
      },
      {
        marker: 2,
        title: "Minimum Wage Increases, Wages, and Low-Wage Employment: Evidence from Seattle",
        authors: "Jardim, Long, Plotnick & van Inwegen",
        venue: "American Economic Review",
        year: 2022,
        method: "Synthetic Control / Micro-hours",
        excerpt: "Hourly wage gains concentrated among incumbent workers below the threshold, with heterogeneous hours responses across establishment sizes.",
      },
    ],
  },
  {
    id: "monetary-investment",
    title: "货币政策传导中的企业融资异质性",
    tag: "宏观金融",
    question: "紧缩性货币政策冲击通过企业资本投资传导时，年轻企业与受融资约束企业的反应机制有何差异？",
    summary: "高频货币冲击与企业微观资产负债表匹配显示，企业年龄、杠杆率与违约风险共同决定了投资通道的传导强度。",
    paragraphs: [
      {
        text: "高违约风险企业面临更高的边际融资斜率，但在常规紧缩冲击下，低杠杆企业反而因无违约溢价阻隔而对无风险利率变化更敏感，展现出非单调的资产负债表传导结构。",
        markers: [1],
      },
      {
        text: "进一步按企业年龄与股利支付划分样本后发现，不支付股利的年轻企业在意外加息后资本支出收缩幅度最大且持续时间最长，而成熟无约束企业的资本投资几乎不变。",
        markers: [2],
      },
    ],
    methodNote: "主流识别策略：高频利率期货意外冲击（High-Frequency Identification）+ 局部投影（Local Projections）。",
    sources: [
      {
        marker: 1,
        title: "Financial Heterogeneity and the Investment Channel of Monetary Policy",
        authors: "Ottonello & Winberry",
        venue: "Econometrica",
        year: 2020,
        method: "High-Frequency Shock + Panel LP",
        excerpt: "Documenting substantial heterogeneity in capital investment responses across firms with differing default risk and leverage profiles.",
      },
      {
        marker: 2,
        title: "Monetary Policy, Corporate Finance, and Investment",
        authors: "Cloyne, Ferreira, Froemel & Surico",
        venue: "Review of Economic Studies",
        year: 2023,
        method: "Proxy SVAR / Firm Microdata",
        excerpt: "Younger non-dividend-paying firms exhibit the largest and most persistent contraction in capital expenditure following unexpected tightening.",
      },
    ],
  },
  {
    id: "did-industrial",
    title: "DID 在产业政策评估中的识别演进",
    tag: "计量方法",
    question: "交错双重差分（Staggered DID）在产业与区域政策评估中容易遇到哪些偏误，近期顶刊推荐采用哪些稳健估计量？",
    summary: "当处理效应存在动态异质性时，传统双向固定效应（TWFE）可能给已处理组赋予负权重，需改用群组时间平均处理效应或插补估计量。",
    paragraphs: [
      {
        text: "在多期交错政策试点场景下，传统 TWFE 回归隐含将早处理组作为晚处理组对照组的比较，若政策效应随时间累积演变，可能扭曲估计符号并夸大显著性。",
        markers: [1],
      },
      {
        text: "近年实证规范普遍建议报告 Callaway–Sant'Anna 群组-时间 ATT、Sun–Abraham 交互加权估计量以及平行趋势敏感度检验，并结合因果知识图谱核查同类政策识别设计。",
        markers: [2],
      },
    ],
    methodNote: "推荐稳健方案：CS-DID、Sun-Abraham IW、Borusyak–Jaravel–Spiess 插补估计与 Rambachan–Roth 敏感度边界。",
    sources: [
      {
        marker: 1,
        title: "Difference-in-Differences with Variation in Treatment Timing",
        authors: "Goodman-Bacon",
        venue: "Journal of Econometrics",
        year: 2021,
        method: "TWFE Decomposition",
        excerpt: "Decomposes two-way fixed effects DD into weighted averages of all two-group/two-period comparisons, identifying negative-weight bias.",
      },
      {
        marker: 2,
        title: "Difference-in-Differences with Multiple Time Periods",
        authors: "Callaway & Sant'Anna",
        venue: "Journal of Econometrics",
        year: 2021,
        method: "Group-Time ATT",
        excerpt: "Proposes identification and aggregation of group-time average treatment effects robust to dynamic treatment effect heterogeneity.",
      },
    ],
  },
]

export function QaPage({ moduleInfo }: { moduleInfo?: ClientModule }) {
  const [leftCollapsed, setLeftCollapsed] = useState(false)
  const [rightCollapsed, setRightCollapsed] = useState(false)
  const [activeId, setActiveId] = useState<string>(QA_SCENARIOS[0].id)
  const [highlightedMarker, setHighlightedMarker] = useState<number | null>(1)
  const [sourceIds, setSourceIds] = useState<string[]>([])
  const [yearMin, setYearMin] = useState("2015")
  const [yearMax, setYearMax] = useState("2025")
  const [onlyPublished, setOnlyPublished] = useState(true)
  const [draftQuestion, setDraftQuestion] = useState("")

  const scenario = QA_SCENARIOS.find((s) => s.id === activeId) ?? QA_SCENARIOS[0]
  const comingSoon = !moduleInfo || moduleInfo.status === "COMING_SOON" || moduleInfo.status === "UNAVAILABLE"

  return <div className={"qa-layout" + (leftCollapsed ? " is-left-collapsed" : "") + (rightCollapsed ? " is-right-collapsed" : "")}>
    <aside className={"qa-sessions" + (leftCollapsed ? " is-collapsed" : "")} aria-label="问答历史示例">
      {leftCollapsed ? (
        <Button type="button" variant="ghost" size="icon" onClick={() => setLeftCollapsed(false)} aria-label="展开历史会话">
          <PanelLeftOpen size={16} />
        </Button>
      ) : <>
        <div className="cit-history-head">
          <div className="cit-history-title"><Clock size={14} aria-hidden="true" /><strong>问答会话</strong></div>
          <Button type="button" variant="ghost" size="icon" onClick={() => setLeftCollapsed(true)} aria-label="收起历史会话">
            <PanelLeftClose size={15} />
          </Button>
        </div>
        <div className="qa-session-list">
          {QA_SCENARIOS.map((item) => (
            <button key={item.id} type="button"
              className={"qa-session-item" + (item.id === activeId ? " is-active" : "")}
              onClick={() => { setActiveId(item.id); setHighlightedMarker(1) }}>
              <div className="qa-session-top">
                <span className="cit-style-tag">{item.tag}</span>
                <span className="qa-demo-tag">示例</span>
              </div>
              <strong>{item.title}</strong>
            </button>
          ))}
        </div>
      </>}
    </aside>

    <section className="qa-stage" aria-label="学科知识问答主区">
      <div className="qa-status-banner" role="status">
        <div className="qa-status-copy">
          <Sparkles size={15} aria-hidden="true" />
          <div>
            <strong>学科知识问答 · {comingSoon ? "能力引擎待接入" : "已连接"}</strong>
            <span>当前展示标准科研问答与原文溯源交互原型；独立问答引擎项目按约定接入后将自动启用实时检索与多轮对话。</span>
          </div>
        </div>
        {rightCollapsed && (
          <Button type="button" variant="outline" size="sm" onClick={() => setRightCollapsed(false)}>
            <PanelRightOpen size={14} aria-hidden="true" />展开溯源与范围
          </Button>
        )}
      </div>

      <div className="qa-thread">
        <div className="qa-prompt-grid" role="group" aria-label="经济学示例研究问题">
          {QA_SCENARIOS.map((item) => (
            <button key={item.id} type="button"
              className={"qa-prompt-card" + (item.id === activeId ? " is-active" : "")}
              onClick={() => { setActiveId(item.id); setHighlightedMarker(1) }}>
              <span className="qa-prompt-tag">{item.tag}</span>
              <strong>{item.title}</strong>
            </button>
          ))}
        </div>

        <article className="qa-bubble is-user">
          <span className="qa-bubble-role">研究问题（示例）</span>
          <p>{scenario.question}</p>
        </article>

        <article className="qa-bubble is-assistant">
          <div className="qa-assistant-head">
            <span className="qa-bubble-role"><MessageSquareText size={14} aria-hidden="true" />AI Economist 文献综合回答（示例结构）</span>
            <span className="muted text-xs">点击文内角标可在右侧定位溯源证据</span>
          </div>
          <p className="qa-summary-lead">{scenario.summary}</p>
          {scenario.paragraphs.map((p, idx) => (
            <p key={idx} className="qa-answer-para">
              {p.text}
              {p.markers.map((marker) => (
                <button key={marker} type="button"
                  className={"qa-cite-marker" + (highlightedMarker === marker ? " is-active" : "")}
                  onClick={() => { setHighlightedMarker(marker); setRightCollapsed(false) }}>
                  [{marker}]
                </button>
              ))}
            </p>
          ))}
          <div className="qa-method-box">
            <Layers size={14} aria-hidden="true" />
            <span>{scenario.methodNote}</span>
          </div>
        </article>
      </div>

      <div className="qa-composer-wrap">
        <div className="qa-scope-chips">
          <span className="cit-tag">{sourceIds.length ? `限定 ${sourceIds.length} 种期刊` : "全部本地期刊"}</span>
          <span className="cit-tag tabular-nums">年份 {yearMin || "不限"}–{yearMax || "不限"}</span>
          {onlyPublished && <span className="cit-tag is-local">仅基于已入库全文</span>}
        </div>
        <div className="qa-composer">
          <textarea className="qa-composer-input" rows={2} value={draftQuestion}
            placeholder="输入经济学研究问题、因果机制或文献综述检索需求（问答引擎接入后可用）…"
            onChange={(e) => setDraftQuestion(e.target.value)} />
          <Button type="button" disabled title="学科知识问答引擎待接入">
            <SendHorizontal size={15} aria-hidden="true" />发送（待接入）
          </Button>
        </div>
      </div>
    </section>

    <aside className={"qa-inspector" + (rightCollapsed ? " is-collapsed" : "")} aria-label="检索范围与引用溯源">
      {!rightCollapsed && <>
        <div className="cit-evidence-head qa-inspector-head">
          <strong>知识范围与引用溯源</strong>
          <Button type="button" variant="ghost" size="icon" onClick={() => setRightCollapsed(true)} aria-label="收起溯源面板">
            <PanelRightClose size={15} />
          </Button>
        </div>

        <div className="qa-inspector-body">
          <section className="qa-scope-card">
            <h3>语料检索范围（复用本地期刊目录）</h3>
            <JournalPicker selectedIds={sourceIds} onChange={setSourceIds} compact label="目标期刊" />
            <div className="cit-year-inputs mt-2">
              <input className="field tabular-nums" type="number" value={yearMin} aria-label="问答起始年份"
                placeholder="起始年" onChange={(e) => setYearMin(e.target.value)} />
              <span className="muted">—</span>
              <input className="field tabular-nums" type="number" value={yearMax} aria-label="问答结束年份"
                placeholder="结束年" onChange={(e) => setYearMax(e.target.value)} />
            </div>
            <label className="qa-checkbox-row">
              <input type="checkbox" checked={onlyPublished} onChange={(e) => setOnlyPublished(e.target.checked)} />
              <span>仅检索已完成全文发布的结构化论文语料</span>
            </label>
          </section>

          <section className="qa-trace-section">
            <h3><BookOpen size={14} aria-hidden="true" />示例引用溯源（{scenario.sources.length}）</h3>
            <div className="qa-trace-list">
              {scenario.sources.map((src) => (
                <div key={src.marker}
                  className={"qa-trace-card" + (highlightedMarker === src.marker ? " is-active" : "")}
                  onClick={() => setHighlightedMarker(src.marker)}>
                  <div className="qa-trace-top">
                    <span className="qa-trace-num">[{src.marker}]</span>
                    <strong>{src.title}</strong>
                  </div>
                  <p className="muted qa-trace-meta">{src.authors} · {src.venue} ({src.year})</p>
                  <span className="cit-tag">{src.method}</span>
                  <blockquote>{src.excerpt}</blockquote>
                </div>
              ))}
            </div>
          </section>
        </div>
      </>}
    </aside>
  </div>
}
