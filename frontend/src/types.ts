export type PaperStatus = "UNPROCESSED" | "READY" | "PUBLISHED" | "FAILED" | "UNSUPPORTED"
export type JobKind = "ORIGINAL_IMPORT" | "PARSE" | "PUBLISH" | "MATCH" | "METADATA"
export type JobItemState = "READY" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "SKIPPED" | "FAILED"
export type JobState = Exclude<JobItemState, "SKIPPED"> | "PARTIAL"

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export type JournalFilterMode = "ALL" | "HAS_FAILED" | "HAS_PENDING" | "ALL_PUBLISHED"
export type JournalSort = "PAPERS_DESC" | "PUBLISHED_RATE_DESC" | "FAILED_DESC" | "PENDING_DESC" | "UPDATED_DESC" | "NAME_ASC"

export interface CorpusJournalOverview {
  totalJournals: number
  totalPapers: number
  publishedPapers: number
  matchedPapers: number
  failedPapers: number
  yearMin: number | null
  yearMax: number | null
}

export interface JournalSummary {
  sourceId: string
  name: string
  totalPapers: number
  archivedPapers: number
  publishedCount: number
  readyCount: number
  unprocessedCount: number
  failedCount: number
  unsupportedCount: number
  matchedCount: number
  yearMin: number | null
  yearMax: number | null
  latestUpdatedAt: string | null
}

export interface JournalDirectory extends Page<JournalSummary> {
  summary: CorpusJournalOverview
}

export interface JournalYearStats {
  year: number
  total: number
  published: number
  ready: number
  unprocessed: number
  failed: number
  unsupported: number
  matched: number
}

export interface JournalDetail {
  journal: JournalSummary
  importedWorksCount: number
  years: JournalYearStats[]
}

export interface Paper {
  paperId: string
  sourceId: string | null
  sourceName: string | null
  year: number | null
  title: string | null
  authors: string | null
  doi: string | null
  url: string | null
  provider: string | null
  originalName: string | null
  originalType: string | null
  originalSize: number | null
  version: number
  revision: number
  archived: boolean
  legacy: boolean
  hasOriginal: boolean
  matchedWorkId: string | null
  latestParseId: string | null
  publishedParseId: string | null
  status: PaperStatus
  updatedAt: string | null
}

export interface PaperBlock {
  sequence: number
  type: string
  text: string | null
  page: number
  hasAsset: boolean
  attributes: Record<string, unknown>
}

export interface Source {
  sourceId: string
  name: string
}

export interface Job {
  jobId: string
  kind: JobKind
  state: JobState
  total: number
  ready: number
  queued: number
  running: number
  succeeded: number
  skipped: number
  failed: number
  canRetry: boolean
  username: string
  retryOf: string | null
  createdAt: string
  finishedAt: string | null
  confirmedAt: string | null
  scope: { sourceId?: string; filename?: string; yearFrom?: number; yearTo?: number; selectedCount?: number }
}

export interface Work {
  workId: string
  year: number | null
  title: string | null
  doi: string | null
  authors: string
}

export interface JobItem {
  sequence: number
  target: string
  paperId: string | null
  state: JobItemState
  inputError: boolean
  message: string | null
  result: {
    candidates?: Work[]
    candidateSource?: { sourceId: string; name: string | null } | null
    matched?: boolean
    imported?: number
    paperId?: string
  } | null
  preflight: OriginalPreflight | null
}

export interface PaperJobRecord {
  jobId: string
  sequence: number
  kind: JobKind
  state: JobItemState
  message: string | null
  createdAt: string
}

export interface OriginalPreflight {
  status: "IMPORTABLE" | "DUPLICATE" | "ERROR"
  originalPath: string | null
  reason: string | null
  existingPaperId: string | null
}
