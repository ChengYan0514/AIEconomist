import {
  ArrowRight,
  BookCheck,
  CheckCircle2,
  FileText,
  GitCompareArrows,
  ListTree,
  PanelLeftClose,
  PanelLeftOpen,
  PenTool,
  SendHorizontal,
  Sparkles,
} from "lucide-react"
import { useEffect, useState } from "react"
import { Button } from "../../components/ui/button"
import { navigate } from "../../router"
import { clientCitationPath, WRITING_HANDOFF_KEY, type ClientModule, type WritingDraftHandoff } from "../types"

interface ManuscriptSection {
  id: string
  title: string
  hint: string
  body: string
}

type CopilotMode = "outline" | "expand" | "review" | "polish"

const STORAGE_KEY = "aie.client.writing.draft.v1"

const INITIAL_SECTIONS: ManuscriptSection[] = [
  {
    id: "abstract",
    title: "摘要",
    hint: "凝练研究问题、识别策略与核心实证发现",
    body: "本文利用中国及主要经合组织经济体的企业级微观面板数据，结合高频政策冲击与准自然实验，检验劳动成本上升与货币政策紧缩在异质性企业间的叠加传导机制。",
  },
  {
    id: "intro",
    title: "一、引言",
    hint: "交代现实背景、文献缺口与边际贡献",
    body: "Raising the statutory minimum wage in low-wage local labor markets does not necessarily reduce aggregate employment, and instead primarily compresses the lower tail of the wage distribution. Moreover, unexpected monetary policy tightening transmits asymmetrically across firms, with financially constrained and younger firms reducing capital investment substantially more than unconstrained peers.",
  },
  {
    id: "literature",
    title: "二、文献综述",
    hint: "梳理两支核心文献脉络，可直接送入「参考文献自动标注」补全引文",
    body: "既有关于最低工资劳动力市场效应的研究主要沿两条主线展开：其一关注低薪岗位总量的净变化及下尾工资分布压缩；其二探讨企业在成本冲击下的非就业边际调整，如价格转嫁与工时重配置。",
  },
  {
    id: "hypothesis",
    title: "三、理论机制与研究假说",
    hint: "推导可检验的核心假说与异质性推论",
    body: "假说 1：在市场势力较强的本地劳动力市场中，适度提高最低工资主要提升低分位劳动报酬，不会引发显著的总就业收缩。\n\n假说 2：当紧缩性货币政策叠加劳动力成本上升时，融资约束较强且成立年限较短的企业资本开支降幅显著更大。",
  },
  {
    id: "empirical",
    title: "四、实证策略与数据",
    hint: "界定核心变量、识别方程与稳健性检验设计",
    body: "为缓解内生性偏误，本文采用多期交错双重差分（Callaway & Sant'Anna, 2021）结合高频货币政策意外冲击作为工具变量，控制行业-年份与地区-年份高维固定效应。",
  },
  {
    id: "conclusion",
    title: "五、结论与政策启示",
    hint: "总结实证结论并提出政策协同建议",
    body: "研究结果表明，最低工资政策与宏观货币周期之间存在显著的企业资产负债表交互效应，政策制定应兼顾中小微年轻企业的流动性缓冲。",
  },
]

const COPILOT_MODES: { id: CopilotMode; label: string; plan: string[]; previewTitle: string; previewDiff: { removed: string; added: string } }[] = [
  {
    id: "outline",
    label: "大纲规划",
    plan: ["解析当前研究问题与核心因果链", "检索经管顶刊同类主题常见章节范式", "生成可展开的二级小节与识别策略检查表"],
    previewTitle: "建议补充的二级小节（示例预览）",
    previewDiff: {
      removed: "四、实证策略与数据（单节平铺）",
      added: "四、实证策略与数据 → 4.1 样本构造与指标定义 / 4.2 基准识别方程 / 4.3 平行趋势与异质性处理稳健性",
    },
  },
  {
    id: "expand",
    label: "段落扩写",
    plan: ["提取当前段落核心论断与经济学机制", "调用本地全文库检索支撑实证与反例", "生成带占位引文锚点的学术扩写建议"],
    previewTitle: "段落扩写 Diff 占位（示例预览）",
    previewDiff: {
      removed: "既有研究关注企业在成本冲击下的非就业边际调整。",
      added: "既有研究进一步识别了企业在成本冲击下的多维非就业边际调整，包括产品价格温和转嫁、内部工时结构重配置以及低效岗位向高生产率企业再分配。",
    },
  },
  {
    id: "review",
    label: "文献综述",
    plan: ["按主题聚类本地已入库期刊与因果图谱边", "对比不同识别方法（DID/IV/RDD）的结论分歧", "输出综述草稿并衔接「参考文献自动标注」"],
    previewTitle: "文献脉络整合建议（示例预览）",
    previewDiff: {
      removed: "按单篇论文顺序列举结论",
      added: "按「识别范式演进 → 异质性调节机制 → 尚未收敛的争论焦点」三段式组织综述，并自动关联本地全文证据。",
    },
  },
  {
    id: "polish",
    label: "润色审校",
    plan: ["检查因果措辞是否过度断言（相关 vs 因果）", "统一计量术语与顶刊学术表达风格", "标记缺少文献支撑的经验陈述"],
    previewTitle: "学术表达审校建议（示例预览）",
    previewDiff: {
      removed: "政策一定会显著导致企业投资崩塌。",
      added: "在控制行业-年份固定效应与企业前期趋势后，政策冲击与受约束企业资本投资增速下降呈统计显著负相关。",
    },
  },
]

function loadSavedState(): { docTitle: string; sections: ManuscriptSection[] } {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as { docTitle?: string; sections?: ManuscriptSection[] }
      if (parsed.docTitle && Array.isArray(parsed.sections) && parsed.sections.length) {
        return { docTitle: parsed.docTitle, sections: parsed.sections }
      }
    }
  } catch {}
  return { docTitle: "最低工资、融资约束与货币政策的企业投资传导", sections: INITIAL_SECTIONS }
}

export function WritingPage({ moduleInfo }: { moduleInfo?: ClientModule }) {
  const [outlineCollapsed, setOutlineCollapsed] = useState(false)
  const [docTitle, setDocTitle] = useState(() => loadSavedState().docTitle)
  const [sections, setSections] = useState<ManuscriptSection[]>(() => loadSavedState().sections)
  const [activeId, setActiveId] = useState<string>("intro")
  const [copilotMode, setCopilotMode] = useState<CopilotMode>("expand")
  const [instruction, setInstruction] = useState("")

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ docTitle, sections }))
    } catch {}
  }, [docTitle, sections])

  const activeSection = sections.find((s) => s.id === activeId) ?? sections[0]
  const activeCopilot = COPILOT_MODES.find((m) => m.id === copilotMode) ?? COPILOT_MODES[0]
  const totalChars = sections.reduce((sum, s) => sum + s.body.length, 0)
  const comingSoon = !moduleInfo || moduleInfo.status === "COMING_SOON" || moduleInfo.status === "UNAVAILABLE"

  function updateBody(nextBody: string) {
    setSections((prev) => prev.map((s) => (s.id === activeSection.id ? { ...s, body: nextBody } : s)))
  }

  function sendToCitations() {
    const text = activeSection.body.trim()
    if (!text) return
    const payload: WritingDraftHandoff = {
      title: `${docTitle.slice(0, 36)} · ${activeSection.title}`,
      text,
      createdAt: Date.now(),
    }
    try { window.localStorage.setItem(WRITING_HANDOFF_KEY, JSON.stringify(payload)) } catch {}
    navigate(clientCitationPath())
  }

  return <div className={"writing-layout" + (outlineCollapsed ? " is-outline-collapsed" : "")}>
    <aside className={"writing-outline" + (outlineCollapsed ? " is-collapsed" : "")} aria-label="论文大纲与章节树">
      {outlineCollapsed ? (
        <Button type="button" variant="ghost" size="icon" onClick={() => setOutlineCollapsed(false)} aria-label="展开论文大纲">
          <PanelLeftOpen size={16} />
        </Button>
      ) : <>
        <div className="cit-history-head">
          <div className="cit-history-title"><ListTree size={14} aria-hidden="true" /><strong>文稿大纲</strong></div>
          <Button type="button" variant="ghost" size="icon" onClick={() => setOutlineCollapsed(true)} aria-label="收起论文大纲">
            <PanelLeftClose size={15} />
          </Button>
        </div>

        <nav className="writing-tree" aria-label="论文章节">
          {sections.map((sec) => {
            const active = sec.id === activeSection.id
            return <button key={sec.id} type="button"
              className={"writing-tree-item" + (active ? " is-active" : "")}
              onClick={() => setActiveId(sec.id)}>
              <FileText size={14} aria-hidden="true" />
              <span className="writing-tree-name">{sec.title}</span>
              <span className="mono muted tabular-nums">{sec.body.length}</span>
            </button>
          })}
        </nav>

        <div className="writing-outline-foot muted tabular-nums">
          <span>全文累计 {totalChars.toLocaleString()} 字符</span>
          <span>自动暂存至本机浏览器</span>
        </div>
      </>}
    </aside>

    <section className="writing-canvas-pane" aria-label="学术文稿编辑画布">
      <div className="writing-toolbar">
        <div className="writing-toolbar-left">
          <span className="cit-style-tag">{activeSection.title}</span>
          <span className="muted tabular-nums text-xs">本节 {activeSection.body.length.toLocaleString()} 字符</span>
        </div>
        <div className="writing-toolbar-actions">
          <Button type="button" size="sm" disabled={!activeSection.body.trim()} onClick={sendToCitations}>
            <BookCheck size={14} aria-hidden="true" />送入参考文献自动标注
            <ArrowRight size={13} aria-hidden="true" />
          </Button>
        </div>
      </div>

      <div className="writing-paper-scroll">
        <div className="writing-paper">
          <label className="block">
            <span className="sr-only">论文标题</span>
            <input className="writing-doc-title" value={docTitle} onChange={(e) => setDocTitle(e.target.value)}
              placeholder="输入论文题目…" />
          </label>
          <div className="writing-section-heading">
            <h2>{activeSection.title}</h2>
            <p className="muted">{activeSection.hint}</p>
          </div>
          <label className="block">
            <span className="sr-only">{activeSection.title}正文编辑</span>
            <textarea className="writing-editor" rows={16} value={activeSection.body}
              onChange={(e) => updateBody(e.target.value)}
              placeholder="在此撰写或粘贴学术正文草稿；点击右上角「送入参考文献自动标注」即可一键为本节匹配真实参考文献……" />
          </label>
        </div>
      </div>
    </section>

    <aside className="writing-copilot" aria-label="论文写作智能体协作面板">
      <div className="cit-evidence-head">
        <div>
          <strong><PenTool size={14} aria-hidden="true" className="inline mr-1.5" />写作智能体协作</strong>
          <p className="muted">{comingSoon ? "能力引擎待接入 · 预留多步骤写作 Agent 协议" : "写作引擎已连接"}</p>
        </div>
      </div>

      <div className="writing-copilot-body">
        <div className="writing-mode-tabs" role="tablist" aria-label="写作协作模式">
          {COPILOT_MODES.map((m) => (
            <button key={m.id} type="button" role="tab" aria-selected={copilotMode === m.id}
              className={"writing-mode-tab" + (copilotMode === m.id ? " is-active" : "")}
              onClick={() => setCopilotMode(m.id)}>
              {m.label}
            </button>
          ))}
        </div>

        <section className="writing-plan-card">
          <h3><Sparkles size={14} aria-hidden="true" />Agent 执行流程（{activeCopilot.label}）</h3>
          <ol className="writing-plan-steps">
            {activeCopilot.plan.map((step, idx) => (
              <li key={idx}><CheckCircle2 size={14} aria-hidden="true" /><span>{step}</span></li>
            ))}
          </ol>
        </section>

        <section className="writing-diff-card">
          <h3><GitCompareArrows size={14} aria-hidden="true" />{activeCopilot.previewTitle}</h3>
          <div className="writing-diff-block is-removed">
            <span>原文</span>
            <p>{activeCopilot.previewDiff.removed}</p>
          </div>
          <div className="writing-diff-block is-added">
            <span>建议修订</span>
            <p>{activeCopilot.previewDiff.added}</p>
          </div>
        </section>

        <div className="writing-copilot-tip">
          提示：你现在就可以在中间画布直接编辑草稿，并点击右上角<strong>「送入参考文献自动标注」</strong>，立即体验跨模块的真实引文标注与审阅工作流。
        </div>
      </div>

      <div className="writing-copilot-input">
        <textarea className="field" rows={2} value={instruction}
          placeholder={`向「${activeCopilot.label}」下达写作指令（写作 Agent 接入后可用）…`}
          onChange={(e) => setInstruction(e.target.value)} />
        <Button type="button" className="w-full mt-2" disabled title="论文写作智能体后端引擎待接入">
          <SendHorizontal size={14} aria-hidden="true" />执行协作指令（待接入）
        </Button>
      </div>
    </aside>
  </div>
}
