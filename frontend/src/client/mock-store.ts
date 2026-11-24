import { exportCitationsLocally, formatCitationsLocally } from "./citation-format"
import type {
  CitationProgressEvent,
  CitationReference,
  CitationStage,
  CitationStatement,
  CitationStyle,
  CitationTaskDetail,
  CitationTaskStatus,
  CitationTaskSummary,
  ClientJournal,
  ClientModule,
  CreateCitationTaskRequest,
  EvidenceStats,
  ExportFormat,
  UpdateCitationReviewRequest,
} from "./types"

export const SAMPLE_CITATIONS_DRAFT =
  "Raising the statutory minimum wage in low-wage local labor markets does not necessarily reduce aggregate employment, and instead primarily compresses the lower tail of the wage distribution. Moreover, unexpected monetary policy tightening transmits asymmetrically across firms, with financially constrained and younger firms reducing capital investment substantially more than unconstrained peers."

const MOCK_JOURNALS: ClientJournal[] = [
  { sourceId: "S23254222", name: "American Economic Review", paperCount: 4120, publishedCount: 3890, yearMin: 1995, yearMax: 2025 },
  { sourceId: "S203860005", name: "The Quarterly Journal of Economics", paperCount: 2480, publishedCount: 2310, yearMin: 1996, yearMax: 2025 },
  { sourceId: "S95464858", name: "Econometrica", paperCount: 2150, publishedCount: 1985, yearMin: 1995, yearMax: 2025 },
  { sourceId: "S95323914", name: "Journal of Political Economy", paperCount: 2340, publishedCount: 2205, yearMin: 1995, yearMax: 2025 },
  { sourceId: "S88935262", name: "The Review of Economic Studies", paperCount: 1860, publishedCount: 1710, yearMin: 1998, yearMax: 2025 },
  { sourceId: "S33323087", name: "Management Science", paperCount: 3560, publishedCount: 3120, yearMin: 2000, yearMax: 2025 },
  { sourceId: "S148304453", name: "Journal of Finance", paperCount: 2790, publishedCount: 2540, yearMin: 1998, yearMax: 2025 },
  { sourceId: "S175405714", name: "Journal of Monetary Economics", paperCount: 1940, publishedCount: 1820, yearMin: 1999, yearMax: 2025 },
]

const MOCK_MODULES: ClientModule[] = [
  {
    id: "citations",
    name: "参考文献自动标注",
    description: "识别草稿中的引文陈述，检索本地经管论文语料并基于原文证据完成引文审阅与格式化导出。",
    status: "STUB",
    provider: "frontend-contract-mock",
    message: "当前处于前端契约演示模式（客户端后端合入后自动切换真实接口）",
  },
  {
    id: "qa",
    name: "学科知识问答",
    description: "基于已入库经济学全文与因果知识网络开展带原文溯源的学术问答。",
    status: "COMING_SOON",
    provider: null,
    message: "能力引擎待接入",
  },
  {
    id: "writing",
    name: "论文写作智能体",
    description: "支持经济学论文大纲规划、文献综述协作、段落扩写与一键送审引文标注。",
    status: "COMING_SOON",
    provider: null,
    message: "能力引擎待接入",
  },
]

function buildEconomicsReferences(): Record<string, CitationReference[]> {
  return {
    s1: [
      {
        referenceId: "W2966824912",
        workId: "W2966824912",
        paperId: "aer-2019-cengiz-minwage",
        doi: "10.1093/qje/qjz014",
        title: "The Effect of Minimum Wages on Low-Wage Jobs",
        authors: ["Doruk Cengiz", "Arindrajit Dube", "Attila Lindner", "Ben Zipperer"],
        year: 2019,
        venue: "The Quarterly Journal of Economics",
        sourceId: "S203860005",
        url: "https://doi.org/10.1093/qje/qjz014",
        metadataSource: "LOCAL_DB",
        rankScore: 0.94,
        maxSupportScore: 0.95,
        maxConfidence: 0.92,
        chunks: [
          {
            chunkId: "chk_cengiz_14",
            sectionTitle: "IV. Main Results: Employment and Wage Distribution",
            text: "Using 138 prominent state-level minimum wage changes between 1979 and 2016, we find that the overall number of low-wage jobs remained essentially unchanged over the five years following the increase, while wages at the bottom of the distribution rose sharply.",
            retrievalScore: 0.89,
            supportScore: 0.95,
            confidence: 0.92,
            reason: "直接以 138 次州级最低工资变动为实证证据，支持「低薪岗位总量未显著萎缩且低端工资分布压缩」论断。",
          },
        ],
      },
      {
        referenceId: "W2102348811",
        workId: "W2102348811",
        paperId: "aer-1994-card-krueger",
        doi: "10.1257/aer.84.4.772",
        title: "Minimum Wages and Employment: A Case Study of the Fast-Food Industry in New Jersey and Pennsylvania",
        authors: ["David Card", "Alan B. Krueger"],
        year: 1994,
        venue: "American Economic Review",
        sourceId: "S23254222",
        url: "https://doi.org/10.1257/aer.84.4.772",
        metadataSource: "LOCAL_DB",
        rankScore: 0.88,
        maxSupportScore: 0.89,
        maxConfidence: 0.90,
        chunks: [
          {
            chunkId: "chk_ck_08",
            sectionTitle: "III. Employment Effects",
            text: "Contrary to the central prediction of the textbook competitive model, we find no indication that the rise in the New Jersey minimum wage reduced employment in fast-food restaurants relative to eastern Pennsylvania.",
            retrievalScore: 0.84,
            supportScore: 0.89,
            confidence: 0.90,
            reason: "经典的准自然实验结果，直接支撑最低工资提高未必降低总体就业的核心结论。",
          },
        ],
      },
      {
        referenceId: "W3125198221",
        workId: "W3125198221",
        paperId: null,
        doi: "10.1257/aer.20171445",
        title: "Minimum Wage Increases, Wages, and Low-Wage Employment: Evidence from Seattle",
        authors: ["Ekaterina Jardim", "Mark C. Long", "Robert Plotnick", "Emma van Inwegen"],
        year: 2022,
        venue: "American Economic Review",
        sourceId: "S23254222",
        url: "https://doi.org/10.1257/aer.20171445",
        metadataSource: "LOCAL_DB_AND_ENGINE",
        rankScore: 0.77,
        maxSupportScore: 0.78,
        maxConfidence: 0.80,
        chunks: [
          {
            chunkId: "chk_jardim_22",
            sectionTitle: "V. Distributional Effects",
            text: "Hourly wage gains concentrated among incumbent workers below the threshold compressed lower-tail wage dispersion, though hours worked showed heterogeneous responses across establishment sizes.",
            retrievalScore: 0.76,
            supportScore: 0.78,
            confidence: 0.80,
            reason: "补充城市级大幅提薪对低端工资分布压缩的细粒度证据（默认作为备选文献，可由研究者勾选换入正文）。",
          },
        ],
      },
    ],
    s2: [
      {
        referenceId: "W2791243011",
        workId: "W2791243011",
        paperId: "ecmt-2019-ottonello-winberry",
        doi: "10.3982/ECTA15949",
        title: "Financial Heterogeneity and the Investment Channel of Monetary Policy",
        authors: ["Pablo Ottonello", "Thomas Winberry"],
        year: 2020,
        venue: "Econometrica",
        sourceId: "S95464858",
        url: "https://doi.org/10.3982/ECTA15949",
        metadataSource: "LOCAL_DB",
        rankScore: 0.93,
        maxSupportScore: 0.94,
        maxConfidence: 0.91,
        chunks: [
          {
            chunkId: "chk_ow_19",
            sectionTitle: "2. Empirical Evidence on Heterogeneous Investment Responses",
            text: "Using high-frequency monetary policy surprises matched with Compustat firm-level balance sheets, we document substantial heterogeneity in capital investment responses across firms with differing default risk and leverage profiles.",
            retrievalScore: 0.91,
            supportScore: 0.94,
            confidence: 0.91,
            reason: "使用高频货币政策冲击与企业微观数据直接验证了资本投资传导的异质性。",
          },
        ],
      },
      {
        referenceId: "W3014122901",
        workId: "W3014122901",
        paperId: "jpe-2018-cloyne-ferreira",
        doi: "10.1093/restud/rdac075",
        title: "Monetary Policy, Corporate Finance, and Investment",
        authors: ["James Cloyne", "Clodomiro Ferreira", "Maren Froemel", "Paolo Surico"],
        year: 2023,
        venue: "The Review of Economic Studies",
        sourceId: "S88935262",
        url: "https://doi.org/10.1093/restud/rdac075",
        metadataSource: "LOCAL_DB",
        rankScore: 0.91,
        maxSupportScore: 0.92,
        maxConfidence: 0.90,
        chunks: [
          {
            chunkId: "chk_cloyne_31",
            sectionTitle: "4. Age, Financing Constraints, and Investment",
            text: "Younger firms that do not pay dividends exhibit the largest and most persistent contraction in capital expenditure following an unexpected monetary tightening, whereas older unconstrained firms barely adjust investment.",
            retrievalScore: 0.88,
            supportScore: 0.92,
            confidence: 0.90,
            reason: "明确识别年轻且受融资约束的企业在紧缩性货币政策冲击下投资缩减幅度显著高于无约束企业。",
          },
        ],
      },
      {
        referenceId: "W2155809422",
        workId: "W2155809422",
        paperId: "jfe-1996-bernanke-gertler",
        doi: "10.1257/jep.9.4.27",
        title: "The Financial Accelerator and the Flight to Quality",
        authors: ["Ben S. Bernanke", "Mark Gertler", "Simon Gilchrist"],
        year: 1996,
        venue: "The Review of Economics and Statistics",
        sourceId: "S175405714",
        url: "https://doi.org/10.2307/2109844",
        metadataSource: "ENGINE",
        rankScore: 0.81,
        maxSupportScore: 0.83,
        maxConfidence: 0.84,
        chunks: [
          {
            chunkId: "chk_bgg_04",
            sectionTitle: "II. Empirical Evidence on Small versus Large Firms",
            text: "Borrowers facing higher agency costs experience sharper declines in credit availability and real spending following monetary contractions—the financial accelerator mechanism.",
            retrievalScore: 0.79,
            supportScore: 0.83,
            confidence: 0.84,
            reason: "提供金融加速器理论基础与大小企业信贷传导差异证据（默认作为备选文献，可一键勾选加入）。",
          },
        ],
      },
    ],
  }
}

function splitSentences(text: string): CitationStatement[] {
  const regex = /[^.!?。！？\n]+[.!?。！？]?/g
  const statements: CitationStatement[] = []
  let match: RegExpExecArray | null
  while ((match = regex.exec(text)) !== null) {
    const raw = match[0]
    const trimmed = raw.trim()
    if (trimmed.length < 8) continue
    const offsetInMatch = raw.indexOf(trimmed)
    const startChar = match.index + offsetInMatch
    const endChar = startChar + trimmed.length
    const idx = statements.length + 1
    statements.push({
      statementId: `s${idx}`,
      text: trimmed,
      startChar,
      endChar,
      needsCitation: true,
      query: trimmed.slice(0, 120),
      confidence: Number((0.88 + (idx % 2) * 0.05).toFixed(2)),
    })
    if (statements.length >= 3) break
  }
  if (!statements.length) {
    statements.push({
      statementId: "s1",
      text: text.trim(),
      startChar: 0,
      endChar: text.trim().length,
      needsCitation: true,
      query: text.trim().slice(0, 120),
      confidence: 0.9,
    })
  }
  return statements
}

function summarize(detail: CitationTaskDetail): CitationTaskSummary {
  const uniqueSelected = new Set(Object.values(detail.selectedReferenceIds).flat())
  return {
    taskId: detail.taskId,
    title: detail.title,
    status: detail.status,
    stage: detail.stage,
    progress: detail.progress,
    citationStyle: detail.citationStyle,
    engineProvider: detail.engineProvider,
    statementCount: detail.statements.length,
    selectedReferenceCount: uniqueSelected.size,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    finishedAt: detail.finishedAt,
    errorMessage: detail.errorMessage,
  }
}

function makeSeedTask(): CitationTaskDetail {
  const now = new Date(Date.now() - 18 * 60_000).toISOString()
  const statements = splitSentences(SAMPLE_CITATIONS_DRAFT)
  const refs = buildEconomicsReferences()
  const selectedReferenceIds: Record<string, string[]> = {
    s1: ["W2966824912", "W2102348811"],
    s2: ["W2791243011", "W3014122901"],
  }
  const { annotatedText, bibliography } = formatCitationsLocally(
    SAMPLE_CITATIONS_DRAFT, statements, refs, selectedReferenceIds, "apa",
  )
  const evidenceStats: Record<string, EvidenceStats> = {
    s1: {
      judgedChunks: 42,
      supportedChunks: 9,
      supportedReferences: 3,
      rejected: { partial: 11, contradicts: 3, irrelevant: 12, lowScore: 4, lowConfidence: 3 },
      hitHardRecallLimit: false,
      hitHardJudgmentLimit: false,
    },
    s2: {
      judgedChunks: 56,
      supportedChunks: 11,
      supportedReferences: 3,
      rejected: { partial: 14, contradicts: 1, irrelevant: 19, lowScore: 6, lowConfidence: 5 },
      hitHardRecallLimit: false,
      hitHardJudgmentLimit: false,
    },
  }
  return {
    taskId: "11111111-2222-4333-8444-555555555551",
    title: "最低工资就业效应与货币政策投资传导",
    status: "SUCCEEDED",
    stage: "COMPLETED",
    progress: 1,
    citationStyle: "apa",
    engineProvider: "frontend-contract-mock",
    statementCount: statements.length,
    selectedReferenceCount: 4,
    createdAt: now,
    updatedAt: now,
    finishedAt: now,
    errorMessage: null,
    originalText: SAMPLE_CITATIONS_DRAFT,
    retrievalPolicy: { sourceIds: ["S23254222", "S203860005", "S95464858", "S88935262"], yearMin: 1994, yearMax: 2025 },
    statements,
    referencesByStatement: refs,
    selectedReferenceIds,
    evidenceStats,
    annotatedText,
    bibliography,
    warnings: [],
    errorCode: null,
    events: [
      { eventId: 1, stage: "PARSE_TEXT", eventType: "STAGE_COMPLETED", progress: 0.2, message: "识别出 2 条需要引文支撑的引文陈述", payload: { statementCount: 2 }, occurredAt: now },
      { eventId: 2, stage: "RETRIEVE_REFERENCES", eventType: "STAGE_COMPLETED", progress: 0.55, message: "从限定期刊语料召回 98 个候选原文片段", payload: {}, occurredAt: now },
      { eventId: 3, stage: "RANK_EVIDENCE", eventType: "STAGE_COMPLETED", progress: 0.78, message: "筛选出 6 篇高置信支撑文献，初始采纳 4 篇", payload: {}, occurredAt: now },
      { eventId: 4, stage: "ENRICH_METADATA", eventType: "STAGE_COMPLETED", progress: 0.9, message: "已通过本地 OpenAlex 与论文库补全全部 6 篇候选书目元数据", payload: {}, occurredAt: now },
      { eventId: 5, stage: "COMPLETED", eventType: "COMPLETED", progress: 1, message: "引文标注与格式化排版已完成", payload: {}, occurredAt: now },
    ],
  }
}

class ContractMockStore {
  private tasks = new Map<string, CitationTaskDetail>()
  private listeners = new Map<string, Set<(event: CitationProgressEvent, done: boolean) => void>>()
  private timers = new Map<string, number[]>()
  private nextEventId = 100

  constructor() {
    const seed = makeSeedTask()
    this.tasks.set(seed.taskId, seed)
  }

  listModules(): { modules: ClientModule[] } {
    return { modules: MOCK_MODULES }
  }

  listJournals(q?: string, limit = 100): { items: ClientJournal[]; total: number } {
    const needle = (q ?? "").trim().toLowerCase()
    const filtered = needle
      ? MOCK_JOURNALS.filter((j) => j.name.toLowerCase().includes(needle) || j.sourceId.toLowerCase().includes(needle))
      : MOCK_JOURNALS
    return { items: filtered.slice(0, limit), total: filtered.length }
  }

  listTasks(status?: CitationTaskStatus | "", page = 0, size = 20) {
    const all = [...this.tasks.values()]
      .filter((t) => !status || t.status === status)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(summarize)
    const start = page * size
    return { items: all.slice(start, start + size), page, size, total: all.length }
  }

  getTask(taskId: string): CitationTaskDetail | null {
    const task = this.tasks.get(taskId)
    return task ? structuredClone(task) : null
  }

  createTask(request: CreateCitationTaskRequest): CitationTaskDetail {
    const taskId = typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `mock-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
    const now = new Date().toISOString()
    const text = request.text.trim()
    const title = (request.title?.trim() || text.split(/[.!?。！？\n]/)[0]?.trim() || "引文标注任务").slice(0, 60)
    const detail: CitationTaskDetail = {
      taskId,
      title,
      status: "QUEUED",
      stage: "QUEUED",
      progress: 0.05,
      citationStyle: request.citationStyle,
      engineProvider: "frontend-contract-mock",
      statementCount: 0,
      selectedReferenceCount: 0,
      createdAt: now,
      updatedAt: now,
      finishedAt: null,
      errorMessage: null,
      originalText: text,
      retrievalPolicy: {
        sourceIds: request.retrievalPolicy?.sourceIds?.filter(Boolean) ?? [],
        yearMin: request.retrievalPolicy?.yearMin ?? null,
        yearMax: request.retrievalPolicy?.yearMax ?? null,
      },
      statements: [],
      referencesByStatement: {},
      selectedReferenceIds: {},
      evidenceStats: {},
      annotatedText: null,
      bibliography: null,
      warnings: [],
      errorCode: null,
      events: [{
        eventId: ++this.nextEventId,
        stage: "QUEUED",
        eventType: "STATUS_CHANGED",
        progress: 0.05,
        message: "引文标注任务已入队，准备解析草稿文本",
        payload: {},
        occurredAt: now,
      }],
    }
    this.tasks.set(taskId, detail)
    this.scheduleSimulation(taskId)
    return structuredClone(detail)
  }

  cancelTask(taskId: string): CitationTaskDetail | null {
    const task = this.tasks.get(taskId)
    if (!task) return null
    if (task.status !== "QUEUED" && task.status !== "RUNNING") return structuredClone(task)
    this.clearTimers(taskId)
    const now = new Date().toISOString()
    task.status = "CANCELLED"
    task.stage = "CANCELLED"
    task.updatedAt = now
    task.finishedAt = now
    const ev: CitationProgressEvent = {
      eventId: ++this.nextEventId,
      stage: "CANCELLED",
      eventType: "CANCELLED",
      progress: task.progress,
      message: "任务已由用户取消",
      payload: {},
      occurredAt: now,
    }
    task.events.push(ev)
    this.emit(taskId, ev, true)
    return structuredClone(task)
  }

  updateReview(taskId: string, request: UpdateCitationReviewRequest): CitationTaskDetail | null {
    const task = this.tasks.get(taskId)
    if (!task || task.status !== "SUCCEEDED") return null
    if (request.citationStyle) task.citationStyle = request.citationStyle
    if (request.selectedReferenceIds) {
      for (const [statementId, ids] of Object.entries(request.selectedReferenceIds)) {
        const allowed = new Set((task.referencesByStatement[statementId] ?? []).map((r) => r.referenceId))
        task.selectedReferenceIds[statementId] = [...new Set(ids)].filter((id) => allowed.has(id))
      }
    }
    const rendered = formatCitationsLocally(
      task.originalText, task.statements, task.referencesByStatement, task.selectedReferenceIds, task.citationStyle,
    )
    task.annotatedText = rendered.annotatedText
    task.bibliography = rendered.bibliography
    task.selectedReferenceCount = new Set(Object.values(task.selectedReferenceIds).flat()).size
    task.updatedAt = new Date().toISOString()
    return structuredClone(task)
  }

  exportTask(taskId: string, format: ExportFormat, style?: CitationStyle) {
    const task = this.tasks.get(taskId)
    if (!task || task.status !== "SUCCEEDED") return null
    return exportCitationsLocally(
      task.title, task.originalText, task.statements, task.referencesByStatement,
      task.selectedReferenceIds, style ?? task.citationStyle, format,
    )
  }

  deleteTask(taskId: string): boolean {
    const task = this.tasks.get(taskId)
    if (!task || task.status === "QUEUED" || task.status === "RUNNING") return false
    this.clearTimers(taskId)
    this.tasks.delete(taskId)
    return true
  }

  subscribe(taskId: string, listener: (event: CitationProgressEvent, done: boolean) => void): () => void {
    const set = this.listeners.get(taskId) ?? new Set()
    set.add(listener)
    this.listeners.set(taskId, set)
    return () => {
      set.delete(listener)
      if (set.size === 0) this.listeners.delete(taskId)
    }
  }

  private emit(taskId: string, event: CitationProgressEvent, done: boolean) {
    for (const listener of this.listeners.get(taskId) ?? []) listener(event, done)
  }

  private clearTimers(taskId: string) {
    for (const id of this.timers.get(taskId) ?? []) window.clearTimeout(id)
    this.timers.delete(taskId)
  }

  private scheduleSimulation(taskId: string) {
    const steps: { delay: number; stage: CitationStage; progress: number; message: string }[] = [
      { delay: 450, stage: "PARSE_TEXT", progress: 0.2, message: "已完成切句与引文陈述识别" },
      { delay: 950, stage: "PLAN_RETRIEVAL", progress: 0.34, message: "已生成混合检索查询计划并应用期刊/年份筛选" },
      { delay: 1550, stage: "RETRIEVE_REFERENCES", progress: 0.56, message: "已召回候选文献原文片段并聚合文献级候选" },
      { delay: 2250, stage: "RANK_EVIDENCE", progress: 0.76, message: "已完成证据支持度与置信度双阈值评审" },
      { delay: 2850, stage: "ENRICH_METADATA", progress: 0.9, message: "已通过本地 OpenAlex 与论文库补全全部候选书目元数据" },
      { delay: 3350, stage: "FORMAT_OUTPUT", progress: 1, message: "已完成初始引文插入与参考文献排版" },
    ]
    const handles = steps.map((step, index) => window.setTimeout(() => {
      const task = this.tasks.get(taskId)
      if (!task || task.status === "CANCELLED") return
      const now = new Date().toISOString()
      task.status = "RUNNING"
      task.stage = step.stage
      task.progress = step.progress
      task.updatedAt = now
      const isFinal = index === steps.length - 1
      if (isFinal) {
        const statements = splitSentences(task.originalText)
        const baseRefs = buildEconomicsReferences()
        const refsByStmt: Record<string, CitationReference[]> = {}
        const selectedIds: Record<string, string[]> = {}
        const stats: Record<string, EvidenceStats> = {}
        statements.forEach((stmt, i) => {
          const pool = i % 2 === 0 ? baseRefs.s1 : baseRefs.s2
          refsByStmt[stmt.statementId] = structuredClone(pool)
          selectedIds[stmt.statementId] = pool.slice(0, 2).map((r) => r.referenceId)
          stats[stmt.statementId] = {
            judgedChunks: 38 + i * 11,
            supportedChunks: 8 + i * 2,
            supportedReferences: pool.length,
            rejected: { partial: 9, contradicts: 2, irrelevant: 14, lowScore: 4, lowConfidence: 3 },
            hitHardRecallLimit: false,
            hitHardJudgmentLimit: false,
          }
        })
        const rendered = formatCitationsLocally(task.originalText, statements, refsByStmt, selectedIds, task.citationStyle)
        task.statements = statements
        task.referencesByStatement = refsByStmt
        task.selectedReferenceIds = selectedIds
        task.evidenceStats = stats
        task.annotatedText = rendered.annotatedText
        task.bibliography = rendered.bibliography
        task.statementCount = statements.length
        task.selectedReferenceCount = new Set(Object.values(selectedIds).flat()).size
        task.status = "SUCCEEDED"
        task.stage = "COMPLETED"
        task.finishedAt = now
      }
      const ev: CitationProgressEvent = {
        eventId: ++this.nextEventId,
        stage: isFinal ? "COMPLETED" : step.stage,
        eventType: isFinal ? "COMPLETED" : "STAGE_COMPLETED",
        progress: step.progress,
        message: step.message,
        payload: {},
        occurredAt: now,
      }
      task.events.push(ev)
      this.emit(taskId, ev, isFinal)
      if (isFinal) this.clearTimers(taskId)
    }, step.delay))
    this.timers.set(taskId, handles)
  }
}

export const contractMockStore = new ContractMockStore()
