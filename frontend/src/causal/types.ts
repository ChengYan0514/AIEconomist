export type SignCategory = "positive" | "negative" | "null" | "mixed"
export type CausalIngestionMode = "RAW" | "STANDARDIZED"
export type CausalTargetMode = "PAPER_IDS" | "WORK_IDS" | "SOURCE_IDS" | "FILTER"

export interface CausalOverview {
  totalClaimRecords: number
  totalStandardClaims: number
  totalPapers: number
  totalNodes: number
  totalEdges: number
  graphNodes: number
  graphEdges: number
  defaultMinRecordCount: number
}

export interface CausalDatasetVersion {
  revision: number
  label: string
  refreshedAt: string
}

export interface CausalGraphSummary {
  overview: CausalOverview
  subfields: string[]
  methods: string[]
  version: CausalDatasetVersion
}

export interface CausalGraphNode {
  id: string
  label: string
  occurrences: number
  dominantSubfield: string | null
  asCauseCount: number
  asEffectCount: number
}

export interface CausalGraphEdge {
  claimId: number
  source: string
  target: string
  recordCount: number
  paperCount: number
  diversity: number
  disagreement: number
  dominantSignCategory: SignCategory
  signBreakdown: Record<string, number>
}

export interface CausalGraphData {
  nodes: CausalGraphNode[]
  edges: CausalGraphEdge[]
}

export interface CausalNodeDetail {
  node: CausalGraphNode
  subfieldCounts: Record<string, number>
  yearCounts: Record<string, number>
  totalClaims: number
  outgoing: CausalGraphEdge[]
  incoming: CausalGraphEdge[]
}

export interface CausalEdgeStats {
  subfieldCount: number
  topicCount: number
  yearSpan: number
  methods: string[]
}

export interface CausalClaim {
  recordId: number
  workId: string
  paperId: string | null
  title: string | null
  publicationYear: number | null
  sourceId: string | null
  sourceName: string | null
  topicName: string | null
  subfieldName: string | null
  claimId: number
  claim: string
  cause: string
  effect: string
  causeStandard: string
  effectStandard: string
  signOfImpact: string
  signCategory: SignCategory
  typeOfRelationship: string
  causalInferenceMethod: string
  evidenceMethodOtherDescription: string | null
  isMainContribution: boolean | null
  levelOfTentativeness: string | null
  sourcesOfExogenousVariation: string | null
  statisticalSignificance: string | null
  causeScore: number | null
  effectScore: number | null
  evidence: string
}

export interface CausalEdgeDetail {
  edge: CausalGraphEdge
  stats: CausalEdgeStats
  claims: CausalClaim[]
}

export interface CausalPaperInfo {
  workId: string
  paperId: string | null
  title: string | null
  publicationYear: number | null
  sourceId: string | null
  sourceName: string | null
  topicName: string | null
  subfieldName: string | null
}

export interface CausalPaperDetail {
  paper: CausalPaperInfo
  claims: CausalClaim[]
  paperGraph: CausalGraphData
}

export interface CausalPaperSummary {
  workId: string
  claimRecordCount: number
  standardClaimCount: number
  variableCount: number
  hasClaims: boolean
}

export interface CausalFieldItem {
  subfield: string
  topic: string
  claimRecordCount: number
  paperCount: number
  variableCount: number
}

export interface CausalNamedCount {
  name: string
  count: number
}

export interface CausalGlobalRelation {
  cause: string
  effect: string
  claimRecordCount: number
  paperCount: number
  methodCount: number
}

export interface CausalFieldAnalysis {
  items: CausalFieldItem[]
  insights: {
    methodCounts: CausalNamedCount[]
    topVariables: CausalNamedCount[]
    topRelations: CausalGlobalRelation[]
  }
}

export interface CausalNodeSearchHit {
  variable: string
  occurrences: number
}

export interface CausalPaperSearchHit {
  workId: string
  paperId: string | null
  title: string | null
  publicationYear: number | null
  sourceName: string | null
  claimRecordCount: number
}

export interface CausalTargetSpec {
  mode: CausalTargetMode
  paperIds?: string[]
  workIds?: string[]
  sourceIds?: string[]
  filters?: { q?: string; sourceId?: string; yearFrom?: number; yearTo?: number }
}

export interface CausalEstimate {
  enabled: boolean
  targetTotal: number
  executableCount: number
  skipReasons: Record<string, number>
  engineVersion: string
  ingestionMode: CausalIngestionMode
  maxCalls: number
  maxBatchTargets: number
}

export interface CausalRunMetrics {
  claimCount: number
  collisionCount: number
  callsUsed: number
  tokensUsed: number
  modelVersions: string[]
}

export interface CausalRunView {
  runId: string
  batchId: string
  paperId: string
  workId: string | null
  sourceId: string | null
  paperTitle: string | null
  status: "QUEUED" | "RUNNING" | "PUBLISHING" | "SUCCEEDED" | "SKIPPED" | "FAILED" | "CANCELLED"
  stage: "PREPARE" | "STAGE0" | "STAGE1" | "NORMALIZE" | "MAP" | "PUBLISH"
  metrics: CausalRunMetrics
  errorCode: string | null
  errorMessage: string | null
  hasArtifacts: boolean
  updatedAt: string
  finishedAt: string | null
}

export interface CausalBatchView {
  batchId: string
  ingestionMode: CausalIngestionMode
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED"
  cancelled: boolean
  scope: Record<string, unknown>
  configSnapshot: Record<string, unknown>
  total: number
  queued: number
  running: number
  publishing: number
  succeeded: number
  zeroClaimPapers: number
  skipped: number
  failed: number
  cancelledCount: number
  totalClaims: number
  callsUsed: number
  canCancel: boolean
  canRetry: boolean
  createdAt: string
  finishedAt: string | null
}

export interface CausalBatchDetail {
  batch: CausalBatchView
  runs: CausalRunView[]
  page: number
  size: number
  totalRuns: number
}

export interface CausalArtifact {
  name: string
  sizeBytes: number
  sha256: string
}

export interface CausalRefreshResult {
  revision: number
  indexedEdges: number
  refreshedAt: string
}

export function variablePath(name: string) {
  return "/causal-graph/nodes/" + encodeURIComponent(name)
}

export function relationPath(cause: string, effect: string) {
  const sp = new URLSearchParams({ cause, effect })
  return "/causal-graph/edges?" + sp.toString()
}

export function paperClaimsPath(workId: string) {
  return "/causal-graph/papers/" + encodeURIComponent(workId)
}

export function extractionBatchPath(batchId: string) {
  return "/causal-extraction/" + encodeURIComponent(batchId)
}

export function extractionEntryHref(target: { mode: CausalTargetMode; ids?: string[]; sourceId?: string }) {
  const sp = new URLSearchParams({ mode: target.mode })
  if (target.ids?.length) sp.set("ids", target.ids.join(","))
  if (target.sourceId) sp.set("sourceId", target.sourceId)
  return "/causal-extraction?" + sp.toString()
}
