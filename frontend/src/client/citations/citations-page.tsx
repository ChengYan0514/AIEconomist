import { useCallback, useEffect, useRef, useState } from "react"
import { errorMessage } from "../../api"
import { Empty, Loading, Notice } from "../../components/shared"
import { Button } from "../../components/ui/button"
import { navigate } from "../../router"
import {
  cancelCitationTask,
  createCitationTask,
  deleteCitationTask,
  downloadCitationExport,
  getCitationTask,
  listCitationTasks,
  subscribeCitationEvents,
  updateCitationReview,
  type ClientBackendMode,
} from "../api"
import {
  clientCitationPath,
  WRITING_HANDOFF_KEY,
  type CitationStyle,
  type CitationTaskDetail,
  type CitationTaskStatus,
  type CitationTaskSummary,
  type ClientModule,
  type CreateCitationTaskRequest,
  type ExportFormat,
  type WritingDraftHandoff,
} from "../types"
import { DraftStudio } from "./draft-studio"
import { HistoryRail } from "./history-rail"
import { LivePipeline } from "./live-pipeline"
import { ReviewWorkbench } from "./review-workbench"

interface DraftSeed {
  key: number
  title: string
  text: string
  style: CitationStyle
  sourceIds: string[]
  yearMin: number | null
  yearMax: number | null
  notice?: string
}

function consumeWritingHandoff(): WritingDraftHandoff | null {
  try {
    const raw = window.localStorage.getItem(WRITING_HANDOFF_KEY)
    if (!raw) return null
    window.localStorage.removeItem(WRITING_HANDOFF_KEY)
    const parsed = JSON.parse(raw) as Partial<WritingDraftHandoff>
    if (typeof parsed.text === "string" && parsed.text.trim()) {
      return {
        title: typeof parsed.title === "string" ? parsed.title : "",
        text: parsed.text,
        createdAt: typeof parsed.createdAt === "number" ? parsed.createdAt : Date.now(),
      }
    }
  } catch {}
  return null
}

export function CitationsPage({
  taskId,
  moduleInfo,
  mode,
}: {
  taskId?: string
  moduleInfo?: ClientModule
  mode: ClientBackendMode
}) {
  const [historyCollapsed, setHistoryCollapsed] = useState(false)
  const [statusFilter, setStatusFilter] = useState<CitationTaskStatus | "">("")
  const [history, setHistory] = useState<CitationTaskSummary[]>([])
  const [historyLoading, setHistoryLoading] = useState(true)
  const [task, setTask] = useState<CitationTaskDetail | null>(null)
  const [taskLoading, setTaskLoading] = useState(Boolean(taskId))
  const [taskError, setTaskError] = useState("")
  const [actionError, setActionError] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [savingReview, setSavingReview] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [liveConnected, setLiveConnected] = useState(false)
  const [draftSeed, setDraftSeed] = useState<DraftSeed>(() => {
    const handoff = !taskId ? consumeWritingHandoff() : null
    return {
      key: 1,
      title: handoff?.title ?? "",
      text: handoff?.text ?? "",
      style: "apa",
      sourceIds: [],
      yearMin: null,
      yearMax: null,
      notice: handoff ? "已从「论文写作智能体」带入当前草稿，你可以直接配置期刊范围与引文样式后开始标注。" : undefined,
    }
  })

  const refreshHistory = useCallback(async () => {
    setHistoryLoading(true)
    try {
      const page = await listCitationTasks({ status: statusFilter, page: 0, size: 30 })
      setHistory(page.items)
    } catch {
      // History list remains graceful on transient failure
    } finally {
      setHistoryLoading(false)
    }
  }, [statusFilter])

  useEffect(() => {
    refreshHistory()
  }, [refreshHistory, mode])

  useEffect(() => {
    if (taskId) return
    const handoff = consumeWritingHandoff()
    if (handoff) {
      setDraftSeed({
        key: Date.now(),
        title: handoff.title,
        text: handoff.text,
        style: "apa",
        sourceIds: [],
        yearMin: null,
        yearMax: null,
        notice: "已从「论文写作智能体」带入当前草稿，你可以直接配置期刊范围与引文样式后开始标注。",
      })
    }
  }, [taskId])

  const loadTask = useCallback(async (id: string, silent = false) => {
    if (!silent) {
      setTaskLoading(true)
      setTaskError("")
    }
    try {
      const detail = await getCitationTask(id)
      setTask(detail)
      setTaskError("")
    } catch (reason) {
      if (!silent) {
        setTask(null)
        setTaskError(errorMessage(reason))
      }
    } finally {
      if (!silent) setTaskLoading(false)
    }
  }, [])

  useEffect(() => {
    setActionError("")
    if (!taskId) {
      setTask(null)
      setTaskLoading(false)
      setTaskError("")
      return
    }
    loadTask(taskId)
  }, [taskId, mode, loadTask])

  const fallbackTimerRef = useRef<number | null>(null)
  const isActive = task?.taskId === taskId && (task?.status === "QUEUED" || task?.status === "RUNNING")

  useEffect(() => {
    if (!taskId || !isActive) {
      setLiveConnected(false)
      return
    }
    const stopFallback = () => {
      if (fallbackTimerRef.current !== null) {
        window.clearInterval(fallbackTimerRef.current)
        fallbackTimerRef.current = null
      }
    }
    const unsubscribe = subscribeCitationEvents(taskId, {
      onConnected: () => {
        setLiveConnected(true)
        stopFallback()
      },
      onProgress: (event) => {
        setTask((prev) => {
          if (!prev || prev.taskId !== taskId) return prev
          const exists = prev.events.some((e) => e.eventId === event.eventId)
          const events = exists ? prev.events : [...prev.events, event]
          return {
            ...prev,
            status: prev.status === "QUEUED" ? "RUNNING" : prev.status,
            stage: event.stage,
            progress: Math.max(prev.progress, event.progress),
            events,
          }
        })
      },
      onComplete: () => {
        setLiveConnected(false)
        stopFallback()
        loadTask(taskId, true)
        refreshHistory()
      },
      onError: () => {
        setLiveConnected(false)
        if (fallbackTimerRef.current === null) {
          fallbackTimerRef.current = window.setInterval(() => {
            loadTask(taskId, true)
            refreshHistory()
          }, 2500)
        }
      },
    })
    return () => {
      unsubscribe()
      stopFallback()
      setLiveConnected(false)
    }
  }, [taskId, isActive, loadTask, refreshHistory])

  async function handleSubmit(request: CreateCitationTaskRequest) {
    setSubmitting(true)
    setActionError("")
    try {
      const created = await createCitationTask(request)
      setTask(created)
      refreshHistory()
      navigate(clientCitationPath(created.taskId))
    } catch (reason) {
      setActionError(errorMessage(reason))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleCancel() {
    if (!task) return
    setCancelling(true)
    setActionError("")
    try {
      const updated = await cancelCitationTask(task.taskId)
      setTask(updated)
      refreshHistory()
    } catch (reason) {
      setActionError(errorMessage(reason))
    } finally {
      setCancelling(false)
    }
  }

  async function handleChangeStyle(style: CitationStyle) {
    if (!task || task.citationStyle === style) return
    setSavingReview(true)
    setActionError("")
    try {
      const updated = await updateCitationReview(task.taskId, { citationStyle: style })
      setTask(updated)
      refreshHistory()
    } catch (reason) {
      setActionError(errorMessage(reason))
    } finally {
      setSavingReview(false)
    }
  }

  async function handleToggleReference(statementId: string, referenceId: string, checked: boolean) {
    if (!task) return
    const current = task.selectedReferenceIds[statementId] ?? []
    const candidates = task.referencesByStatement[statementId] ?? []
    const nextSet = new Set(current)
    if (checked) nextSet.add(referenceId)
    else nextSet.delete(referenceId)
    const orderedNext = candidates.map((r) => r.referenceId).filter((id) => nextSet.has(id))

    setSavingReview(true)
    setActionError("")
    try {
      const updated = await updateCitationReview(task.taskId, {
        selectedReferenceIds: { ...task.selectedReferenceIds, [statementId]: orderedNext },
      })
      setTask(updated)
      refreshHistory()
    } catch (reason) {
      setActionError(errorMessage(reason))
    } finally {
      setSavingReview(false)
    }
  }

  async function handleExport(format: ExportFormat) {
    if (!task) return
    setActionError("")
    try {
      await downloadCitationExport(task.taskId, format, task.citationStyle)
    } catch (reason) {
      setActionError(errorMessage(reason))
    }
  }

  async function handleDelete(item: CitationTaskSummary) {
    setActionError("")
    try {
      await deleteCitationTask(item.taskId)
      if (taskId === item.taskId) navigate(clientCitationPath())
      refreshHistory()
    } catch (reason) {
      setActionError(errorMessage(reason))
    }
  }

  function openNewDraft(fromTask?: CitationTaskDetail) {
    setActionError("")
    setDraftSeed({
      key: Date.now(),
      title: fromTask ? `${fromTask.title}（修订）` : "",
      text: fromTask?.originalText ?? "",
      style: fromTask?.citationStyle ?? "apa",
      sourceIds: fromTask?.retrievalPolicy.sourceIds ?? [],
      yearMin: fromTask?.retrievalPolicy.yearMin ?? null,
      yearMax: fromTask?.retrievalPolicy.yearMax ?? null,
    })
    navigate(clientCitationPath())
  }

  return <div className={"cit-layout" + (historyCollapsed ? " is-history-collapsed" : "")}>
    <HistoryRail
      collapsed={historyCollapsed}
      onToggleCollapse={() => setHistoryCollapsed((v) => !v)}
      tasks={history}
      loading={historyLoading}
      activeTaskId={taskId}
      statusFilter={statusFilter}
      onStatusFilter={setStatusFilter}
      onNewTask={() => openNewDraft()}
      onDeleteTask={handleDelete}
    />

    <div className="cit-main">
      {!taskId ? (
        <DraftStudio
          key={draftSeed.key}
          initialTitle={draftSeed.title}
          initialText={draftSeed.text}
          initialStyle={draftSeed.style}
          initialSourceIds={draftSeed.sourceIds}
          initialYearMin={draftSeed.yearMin}
          initialYearMax={draftSeed.yearMax}
          handoffNotice={draftSeed.notice}
          moduleInfo={moduleInfo}
          submitting={submitting}
          error={actionError}
          onSubmit={handleSubmit}
        />
      ) : taskLoading ? (
        <Loading label="正在加载引文标注任务…" />
      ) : taskError ? (
        <div className="cit-error-wrap">
          <Notice error>{taskError}</Notice>
          <div className="mt-4">
            <Button type="button" variant="outline" onClick={() => openNewDraft()}>返回新建引文标注</Button>
          </div>
        </div>
      ) : !task ? (
        <Empty title="找不到对应的引文标注任务">
          <Button type="button" variant="outline" onClick={() => openNewDraft()}>新建标注任务</Button>
        </Empty>
      ) : task.status === "SUCCEEDED" ? (
        <ReviewWorkbench
          key={task.taskId}
          task={task}
          saving={savingReview}
          error={actionError}
          onChangeStyle={handleChangeStyle}
          onToggleReference={handleToggleReference}
          onExport={handleExport}
          onNewFromOriginal={() => openNewDraft(task)}
        />
      ) : (
        <LivePipeline
          task={task}
          liveConnected={liveConnected}
          cancelling={cancelling}
          error={actionError}
          onCancel={handleCancel}
          onRetryDraft={() => openNewDraft(task)}
        />
      )}
    </div>
  </div>
}
