import { BookOpen, Sparkles, Wand2 } from "lucide-react"
import { useState, type FormEvent } from "react"
import { Notice } from "../../components/shared"
import { Button } from "../../components/ui/button"
import { JournalPicker } from "../journal-picker"
import { SAMPLE_CITATIONS_DRAFT } from "../mock-store"
import {
  CITATION_STYLES,
  type CitationStyle,
  type ClientModule,
  type CreateCitationTaskRequest,
} from "../types"

const MAX_CHARS = 20_000

export function DraftStudio({
  initialTitle = "",
  initialText = "",
  initialStyle = "apa",
  initialSourceIds = [],
  initialYearMin = null,
  initialYearMax = null,
  handoffNotice,
  moduleInfo,
  submitting,
  error,
  onSubmit,
}: {
  initialTitle?: string
  initialText?: string
  initialStyle?: CitationStyle
  initialSourceIds?: string[]
  initialYearMin?: number | null
  initialYearMax?: number | null
  handoffNotice?: string
  moduleInfo?: ClientModule
  submitting: boolean
  error: string
  onSubmit: (request: CreateCitationTaskRequest) => void
}) {
  const [title, setTitle] = useState(initialTitle)
  const [text, setText] = useState(initialText)
  const [style, setStyle] = useState<CitationStyle>(initialStyle)
  const [sourceIds, setSourceIds] = useState<string[]>(initialSourceIds)
  const [yearMin, setYearMin] = useState(initialYearMin ? String(initialYearMin) : "")
  const [yearMax, setYearMax] = useState(initialYearMax ? String(initialYearMax) : "")
  const [validationError, setValidationError] = useState("")

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setValidationError("")
    const trimmed = text.trim()
    if (!trimmed) {
      setValidationError("请输入需要自动标注参考文献的学术草稿文本。")
      return
    }
    if (trimmed.length > MAX_CHARS) {
      setValidationError(`单次提交文本上限为 ${MAX_CHARS.toLocaleString()} 字符。`)
      return
    }
    const parsedMin = yearMin.trim() ? Number(yearMin.trim()) : null
    const parsedMax = yearMax.trim() ? Number(yearMax.trim()) : null
    if (parsedMin !== null && (!Number.isInteger(parsedMin) || parsedMin < 1000 || parsedMin > 9999)) {
      setValidationError("起始年份须为 1000 至 9999 之间的整数。")
      return
    }
    if (parsedMax !== null && (!Number.isInteger(parsedMax) || parsedMax < 1000 || parsedMax > 9999)) {
      setValidationError("结束年份须为 1000 至 9999 之间的整数。")
      return
    }
    if (parsedMin !== null && parsedMax !== null && parsedMin > parsedMax) {
      setValidationError("起始年份不能大于结束年份。")
      return
    }
    onSubmit({
      title: title.trim() || null,
      text: trimmed,
      citationStyle: style,
      retrievalPolicy: {
        sourceIds: sourceIds.length ? sourceIds : null,
        yearMin: parsedMin,
        yearMax: parsedMax,
      },
    })
  }

  function fillSample() {
    setTitle("最低工资就业效应与货币政策投资传导")
    setText(SAMPLE_CITATIONS_DRAFT)
    setValidationError("")
  }

  const engineUnavailable = moduleInfo?.status === "UNAVAILABLE"

  return <div className="cit-draft-wrap">
    <div className="cit-draft-header">
      <div>
        <h1>参考文献自动标注</h1>
        <p className="muted">粘贴论文段落或研究草稿，系统将识别其中的引文陈述，检索经管语料并基于原文证据生成可审阅的文内引文与参考文献列表。</p>
      </div>
      <div className="cit-engine-pill">
        <BookOpen size={14} aria-hidden="true" />
        <span>
          {moduleInfo?.status === "STUB" ? "演示引擎" :
            moduleInfo?.status === "UNAVAILABLE" ? "引擎未连接" :
            "标准接入引擎"}
        </span>
      </div>
    </div>

    {handoffNotice && <div className="mb-3"><Notice>{handoffNotice}</Notice></div>}
    {engineUnavailable && <div className="mb-3"><Notice error>{moduleInfo?.message || "当前参考文献自动标注引擎暂不可用，请检查配置或切换契约演示模式。"}</Notice></div>}
    {(validationError || error) && <div className="mb-3"><Notice error>{validationError || error}</Notice></div>}

    <form className="cit-paper-card" onSubmit={handleSubmit}>
      <div className="cit-draft-top-row">
        <label className="cit-title-input-wrap">
          <span className="sr-only">任务标题（可选）</span>
          <input className="cit-title-input" value={title} maxLength={160}
            placeholder="任务标题（可选，留空自动按正文首句生成）"
            onChange={(e) => setTitle(e.target.value)} />
        </label>
        <Button type="button" variant="outline" size="sm" onClick={fillSample}>
          <Wand2 size={14} aria-hidden="true" />填入经济学示例
        </Button>
      </div>

      <label className="cit-editor-wrap">
        <span className="sr-only">待标注学术草稿文本</span>
        <textarea className="cit-editor-textarea" rows={10} value={text} maxLength={MAX_CHARS}
          placeholder="在此输入或粘贴英文/中文学术论文段落、文献综述或研究假设……"
          onChange={(e) => setText(e.target.value)} />
      </label>

      <div className="cit-editor-meter">
        <span className="muted">支持多句段落自动拆解引文陈述；完成后可在结果页自由勾选替换备选文献与切换引文样式。</span>
        <span className="mono muted tabular-nums">{text.length.toLocaleString()} / {MAX_CHARS.toLocaleString()}</span>
      </div>

      <div className="cit-policy-deck">
        <div className="cit-policy-style">
          <span className="client-field-label">引文样式</span>
          <div className="cit-segmented" role="radiogroup" aria-label="引文样式">
            {CITATION_STYLES.map((item) => (
              <button key={item.value} type="button" role="radio" aria-checked={style === item.value}
                className={"cit-seg-btn" + (style === item.value ? " is-active" : "")}
                onClick={() => setStyle(item.value)}>
                {item.label}
              </button>
            ))}
          </div>
        </div>

        <div className="cit-policy-grid">
          <JournalPicker selectedIds={sourceIds} onChange={setSourceIds} />
          <div className="cit-year-group">
            <span className="client-field-label">发表年份范围（可选）</span>
            <div className="cit-year-inputs">
              <input className="field tabular-nums" type="number" min={1000} max={9999}
                placeholder="起始年，如 2010" aria-label="起始年份"
                value={yearMin} onChange={(e) => setYearMin(e.target.value)} />
              <span className="muted">—</span>
              <input className="field tabular-nums" type="number" min={1000} max={9999}
                placeholder="结束年，如 2025" aria-label="结束年份"
                value={yearMax} onChange={(e) => setYearMax(e.target.value)} />
            </div>
          </div>
        </div>
      </div>

      <div className="cit-draft-submit-bar">
        <span className="muted text-sm">
          {sourceIds.length > 0 ? `已限定 ${sourceIds.length} 种期刊` : "检索范围：全部本地语料期刊"}
          {yearMin || yearMax ? ` · 年份 ${yearMin || "不限"}–${yearMax || "不限"}` : ""}
        </span>
        <Button type="submit" disabled={submitting || engineUnavailable}>
          <Sparkles size={15} aria-hidden="true" />
          {submitting ? "正在提交任务…" : "开始自动标注"}
        </Button>
      </div>
    </form>
  </div>
}
