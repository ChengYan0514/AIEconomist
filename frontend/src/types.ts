export type PaperStatus = "UNPROCESSED" | "READY" | "PUBLISHED" | "FAILED" | "UNSUPPORTED"
export type JobKind = "ORIGINAL_IMPORT" | "PARSE" | "PUBLISH" | "MATCH" | "METADATA"
export type JobState = "READY" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "SKIPPED" | "FAILED" | "PARTIAL"

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
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
  username: string
  retryOf: string | null
  createdAt: string
  finishedAt: string | null
  confirmedAt: string | null
  scope: { sourceId?: string; filename?: string; yearFrom?: number; yearTo?: number }
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
  state: JobState
  message: string | null
  result: { candidates?: Work[]; matched?: boolean; imported?: number; paperId?: string } | null
}
