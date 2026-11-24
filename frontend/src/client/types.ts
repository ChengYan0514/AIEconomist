export type ClientModuleId = "citations" | "qa" | "writing"
export type ClientModuleStatus = "AVAILABLE" | "STUB" | "COMING_SOON" | "UNAVAILABLE"

export interface ClientModule {
  id: ClientModuleId
  name: string
  description: string
  status: ClientModuleStatus
  provider: string | null
  message: string | null
}

export interface ClientJournal {
  sourceId: string
  name: string
  paperCount: number
  publishedCount: number
  yearMin: number | null
  yearMax: number | null
}

export interface ClientJournalList {
  items: ClientJournal[]
  total: number
}

export type CitationStyle = "apa" | "ieee" | "chicago-author-date" | "mla"
export type CitationTaskStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"
export type CitationStage =
  | "QUEUED"
  | "PARSE_TEXT"
  | "PLAN_RETRIEVAL"
  | "RETRIEVE_REFERENCES"
  | "RANK_EVIDENCE"
  | "ENRICH_METADATA"
  | "FORMAT_OUTPUT"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"

export type CitationEventType =
  | "STATUS_CHANGED"
  | "STAGE_STARTED"
  | "STAGE_COMPLETED"
  | "WARNING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"

export type ExportFormat = "txt" | "md" | "bibtex"

export interface RetrievalPolicy {
  sourceIds: string[]
  yearMin: number | null
  yearMax: number | null
}

export interface CreateCitationTaskRequest {
  title?: string | null
  text: string
  citationStyle: CitationStyle
  retrievalPolicy?: {
    sourceIds?: string[] | null
    yearMin?: number | null
    yearMax?: number | null
  }
}

export interface UpdateCitationReviewRequest {
  citationStyle?: CitationStyle
  selectedReferenceIds?: Record<string, string[]>
}

export interface CitationStatement {
  statementId: string
  text: string
  startChar: number
  endChar: number
  needsCitation: boolean
  query: string | null
  confidence: number | null
}

export interface EvidenceChunk {
  chunkId: string
  sectionTitle: string | null
  text: string
  retrievalScore: number | null
  supportScore: number | null
  confidence: number | null
  reason: string | null
}

export interface CitationReference {
  referenceId: string
  workId: string | null
  paperId: string | null
  doi: string | null
  title: string
  authors: string[]
  year: number | null
  venue: string | null
  sourceId: string | null
  url: string | null
  metadataSource: "LOCAL_DB" | "ENGINE" | "LOCAL_DB_AND_ENGINE"
  rankScore: number | null
  maxSupportScore: number | null
  maxConfidence: number | null
  chunks: EvidenceChunk[]
}

export interface EvidenceStats {
  judgedChunks: number
  supportedChunks: number
  supportedReferences: number
  rejected: {
    partial: number
    contradicts: number
    irrelevant: number
    lowScore: number
    lowConfidence: number
  }
  hitHardRecallLimit: boolean
  hitHardJudgmentLimit: boolean
}

export interface CitationProgressEvent {
  eventId: number
  stage: CitationStage
  eventType: CitationEventType
  progress: number
  message: string
  payload: Record<string, unknown>
  occurredAt: string
}

export interface CitationTaskSummary {
  taskId: string
  title: string
  status: CitationTaskStatus
  stage: CitationStage
  progress: number
  citationStyle: CitationStyle
  engineProvider: string
  statementCount: number
  selectedReferenceCount: number
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  errorMessage: string | null
}

export interface CitationTaskDetail extends CitationTaskSummary {
  originalText: string
  retrievalPolicy: RetrievalPolicy
  statements: CitationStatement[]
  referencesByStatement: Record<string, CitationReference[]>
  selectedReferenceIds: Record<string, string[]>
  evidenceStats: Record<string, EvidenceStats>
  annotatedText: string | null
  bibliography: string | null
  warnings: string[]
  errorCode: string | null
  events: CitationProgressEvent[]
}

export const CITATION_STYLES: { value: CitationStyle; label: string; short: string }[] = [
  { value: "apa", label: "APA (7th)", short: "APA" },
  { value: "ieee", label: "IEEE 编号", short: "IEEE" },
  { value: "chicago-author-date", label: "Chicago Author-Date", short: "Chicago" },
  { value: "mla", label: "MLA (9th)", short: "MLA" },
]

export const CITATION_STAGE_STEPS: { stage: CitationStage; label: string }[] = [
  { stage: "PARSE_TEXT", label: "解析陈述" },
  { stage: "PLAN_RETRIEVAL", label: "规划检索" },
  { stage: "RETRIEVE_REFERENCES", label: "向量召回" },
  { stage: "RANK_EVIDENCE", label: "证据评估" },
  { stage: "ENRICH_METADATA", label: "本地书目补全" },
  { stage: "FORMAT_OUTPUT", label: "引文排版" },
]

export const CITATION_STATUS_LABEL: Record<CitationTaskStatus, [string, string]> = {
  QUEUED: ["排队中", "neutral"],
  RUNNING: ["处理中", "blue"],
  SUCCEEDED: ["已完成", "green"],
  FAILED: ["失败", "red"],
  CANCELLED: ["已取消", "neutral"],
}

export function clientCitationPath(taskId?: string) {
  return taskId ? `/client/citations/${encodeURIComponent(taskId)}` : "/client/citations"
}

export const WRITING_HANDOFF_KEY = "aie.client.writing.handoff"

export interface WritingDraftHandoff {
  title: string
  text: string
  createdAt: number
}
