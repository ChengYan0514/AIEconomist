import { api, ApiError, params } from "../api"
import type { Page } from "../types"
import { contractMockStore } from "./mock-store"
import type {
  CitationProgressEvent,
  CitationStyle,
  CitationTaskDetail,
  CitationTaskStatus,
  CitationTaskSummary,
  ClientJournalList,
  ClientModule,
  CreateCitationTaskRequest,
  ExportFormat,
  UpdateCitationReviewRequest,
} from "./types"

const MOCK_MODE_KEY = "aie.client.mock"
const MODE_EVENT = "aie:client-mode"

export type ClientBackendMode = "live" | "mock-manual" | "mock-fallback"

let fallbackMock = false

function readManualMock(): boolean {
  try { return window.localStorage.getItem(MOCK_MODE_KEY) === "1" }
  catch { return false }
}

export function getClientMode(): ClientBackendMode {
  if (readManualMock()) return "mock-manual"
  if (fallbackMock) return "mock-fallback"
  return "live"
}

export function isUsingClientMock(): boolean {
  return getClientMode() !== "live"
}

export function setManualClientMock(enabled: boolean) {
  fallbackMock = false
  try { window.localStorage.setItem(MOCK_MODE_KEY, enabled ? "1" : "0") } catch {}
  window.dispatchEvent(new Event(MODE_EVENT))
}

export function subscribeClientMode(listener: () => void): () => void {
  window.addEventListener(MODE_EVENT, listener)
  return () => window.removeEventListener(MODE_EVENT, listener)
}

function activateFallbackIfMissingBackend(error: unknown): boolean {
  if (error instanceof ApiError && (error.status === 404 || error.status === 501)) {
    if (!fallbackMock) {
      fallbackMock = true
      window.dispatchEvent(new Event(MODE_EVENT))
    }
    return true
  }
  return false
}

export async function listClientModules(): Promise<ClientModule[]> {
  if (isUsingClientMock()) return contractMockStore.listModules().modules
  try {
    const res = await api<{ modules: ClientModule[] }>("/client/modules")
    return res.modules
  } catch (error) {
    if (activateFallbackIfMissingBackend(error)) return contractMockStore.listModules().modules
    throw error
  }
}

export async function listClientJournals(q?: string, limit = 100): Promise<ClientJournalList> {
  if (isUsingClientMock()) return contractMockStore.listJournals(q, limit)
  try {
    return await api<ClientJournalList>("/client/journals" + params({ q: q?.trim(), limit }))
  } catch (error) {
    if (activateFallbackIfMissingBackend(error)) return contractMockStore.listJournals(q, limit)
    throw error
  }
}

export async function listCitationTasks(
  filter: { status?: CitationTaskStatus | ""; page?: number; size?: number } = {},
): Promise<Page<CitationTaskSummary>> {
  if (isUsingClientMock()) {
    return contractMockStore.listTasks(filter.status ?? "", filter.page ?? 0, filter.size ?? 20)
  }
  try {
    return await api<Page<CitationTaskSummary>>("/client/citations/tasks" + params({
      status: filter.status || undefined,
      page: filter.page ?? 0,
      size: filter.size ?? 20,
    }))
  } catch (error) {
    if (activateFallbackIfMissingBackend(error)) {
      return contractMockStore.listTasks(filter.status ?? "", filter.page ?? 0, filter.size ?? 20)
    }
    throw error
  }
}

export async function getCitationTask(taskId: string): Promise<CitationTaskDetail> {
  if (isUsingClientMock()) {
    const task = contractMockStore.getTask(taskId)
    if (!task) throw new ApiError(404, "未找到对应的引文标注任务。", "CITATION_TASK_NOT_FOUND")
    return task
  }
  return api<CitationTaskDetail>(`/client/citations/tasks/${encodeURIComponent(taskId)}`)
}

export async function createCitationTask(request: CreateCitationTaskRequest): Promise<CitationTaskDetail> {
  if (isUsingClientMock()) return contractMockStore.createTask(request)
  return api<CitationTaskDetail>("/client/citations/tasks", {
    method: "POST",
    body: JSON.stringify(request),
  })
}

export async function cancelCitationTask(taskId: string): Promise<CitationTaskDetail> {
  if (isUsingClientMock()) {
    const task = contractMockStore.cancelTask(taskId)
    if (!task) throw new ApiError(404, "未找到对应的引文标注任务。", "CITATION_TASK_NOT_FOUND")
    return task
  }
  return api<CitationTaskDetail>(`/client/citations/tasks/${encodeURIComponent(taskId)}/cancel`, { method: "POST" })
}

export async function updateCitationReview(
  taskId: string,
  request: UpdateCitationReviewRequest,
): Promise<CitationTaskDetail> {
  if (isUsingClientMock()) {
    const task = contractMockStore.updateReview(taskId, request)
    if (!task) throw new ApiError(409, "当前任务尚未完成，暂不可调整审阅结果。", "CITATION_TASK_NOT_READY")
    return task
  }
  return api<CitationTaskDetail>(`/client/citations/tasks/${encodeURIComponent(taskId)}/review`, {
    method: "PATCH",
    body: JSON.stringify(request),
  })
}

export async function deleteCitationTask(taskId: string): Promise<void> {
  if (isUsingClientMock()) {
    if (!contractMockStore.deleteTask(taskId)) {
      throw new ApiError(409, "运行中任务请先取消后再删除。", "CITATION_TASK_ACTIVE")
    }
    return
  }
  await api<void>(`/client/citations/tasks/${encodeURIComponent(taskId)}`, { method: "DELETE" })
}

export async function downloadCitationExport(taskId: string, format: ExportFormat, style?: CitationStyle) {
  if (isUsingClientMock()) {
    const file = contractMockStore.exportTask(taskId, format, style)
    if (!file) throw new ApiError(409, "当前任务尚未完成，无法导出。", "CITATION_TASK_NOT_READY")
    triggerBrowserDownload(file.filename, file.content, file.mime)
    return
  }
  const url = `/api/client/citations/tasks/${encodeURIComponent(taskId)}/export` + params({ format, style })
  const response = await fetch(url, { credentials: "same-origin" })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new ApiError(response.status, error?.message || "导出失败，请稍后重试。", error?.code)
  }
  const blob = await response.blob()
  const disposition = response.headers.get("Content-Disposition") ?? ""
  const nameMatch = disposition.match(/filename="?([^";]+)"?/i)
  const fallbackName = `citation-${taskId.slice(0, 8)}.${format === "bibtex" ? "bib" : format}`
  const filename = nameMatch?.[1] || fallbackName
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = href
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(href)
}

function triggerBrowserDownload(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = href
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(href)
}

export function subscribeCitationEvents(
  taskId: string,
  handlers: {
    onConnected?: () => void
    onProgress: (event: CitationProgressEvent) => void
    onComplete: () => void
    onError?: () => void
  },
): () => void {
  if (isUsingClientMock()) {
    handlers.onConnected?.()
    return contractMockStore.subscribe(taskId, (event, done) => {
      handlers.onProgress(event)
      if (done) handlers.onComplete()
    })
  }
  const source = new EventSource(`/api/client/citations/tasks/${encodeURIComponent(taskId)}/events`, {
    withCredentials: true,
  })
  source.onopen = () => handlers.onConnected?.()
  source.addEventListener("progress", (raw) => {
    try {
      const event = JSON.parse((raw as MessageEvent<string>).data) as CitationProgressEvent
      handlers.onProgress(event)
    } catch {}
  })
  source.addEventListener("complete", () => {
    handlers.onComplete()
    source.close()
  })
  source.onerror = () => {
    handlers.onError?.()
  }
  return () => source.close()
}
