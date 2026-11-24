import { Clock, PanelLeftClose, PanelLeftOpen, Plus, Trash2 } from "lucide-react"
import { dateLabel } from "../../api"
import { Button } from "../../components/ui/button"
import {
  CITATION_STATUS_LABEL,
  CITATION_STYLES,
  clientCitationPath,
  type CitationTaskStatus,
  type CitationTaskSummary,
} from "../types"

const FILTER_OPTIONS: { value: CitationTaskStatus | ""; label: string }[] = [
  { value: "", label: "全部" },
  { value: "RUNNING", label: "运行中" },
  { value: "SUCCEEDED", label: "已完成" },
  { value: "FAILED", label: "失败" },
]

function styleShort(value: CitationTaskSummary["citationStyle"]): string {
  return CITATION_STYLES.find((s) => s.value === value)?.short ?? value.toUpperCase()
}

export function HistoryRail({
  collapsed,
  onToggleCollapse,
  tasks,
  loading,
  activeTaskId,
  statusFilter,
  onStatusFilter,
  onNewTask,
  onDeleteTask,
}: {
  collapsed: boolean
  onToggleCollapse: () => void
  tasks: CitationTaskSummary[]
  loading: boolean
  activeTaskId?: string
  statusFilter: CitationTaskStatus | ""
  onStatusFilter: (next: CitationTaskStatus | "") => void
  onNewTask: () => void
  onDeleteTask: (task: CitationTaskSummary) => void
}) {
  if (collapsed) {
    return <aside className="cit-history is-collapsed" aria-label="引文标注历史任务">
      <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapse} aria-label="展开历史任务栏" title="展开历史任务栏">
        <PanelLeftOpen size={16} />
      </Button>
      <Button type="button" size="icon" onClick={onNewTask} aria-label="新建标注任务" title="新建标注任务">
        <Plus size={16} />
      </Button>
    </aside>
  }

  return <aside className="cit-history" aria-label="引文标注历史任务">
    <div className="cit-history-head">
      <div className="cit-history-title">
        <Clock size={14} aria-hidden="true" />
        <strong>标注历史</strong>
      </div>
      <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapse} aria-label="收起历史任务栏" title="收起历史任务栏">
        <PanelLeftClose size={15} />
      </Button>
    </div>

    <div className="cit-history-actions">
      <Button type="button" className="w-full" onClick={onNewTask}>
        <Plus size={15} aria-hidden="true" />新建标注任务
      </Button>
      <div className="cit-history-filters" role="group" aria-label="按状态筛选历史任务">
        {FILTER_OPTIONS.map((opt) => (
          <button key={opt.value || "ALL"} type="button"
            aria-pressed={statusFilter === opt.value}
            className={"cit-filter-pill" + (statusFilter === opt.value ? " is-active" : "")}
            onClick={() => onStatusFilter(opt.value)}>
            {opt.label}
          </button>
        ))}
      </div>
    </div>

    <div className="cit-history-list">
      {loading && tasks.length === 0 ? <p className="muted cit-history-empty">正在读取历史记录…</p> :
        tasks.length === 0 ? <p className="muted cit-history-empty">暂无引文标注任务</p> :
        tasks.map((task) => {
          const active = task.taskId === activeTaskId
          const [statusLabel, tone] = CITATION_STATUS_LABEL[task.status]
          const deletable = task.status !== "QUEUED" && task.status !== "RUNNING"
          return <div key={task.taskId} className={"cit-history-card" + (active ? " is-active" : "")}>
            <a href={clientCitationPath(task.taskId)} className="cit-history-link">
              <div className="cit-history-card-top">
                <strong className="cit-history-card-title">{task.title}</strong>
                <span className="cit-style-tag">{styleShort(task.citationStyle)}</span>
              </div>
              <div className="cit-history-card-meta">
                <span className={"cit-dot-label is-" + tone}>
                  <span className="status-dot" aria-hidden="true" />{statusLabel}
                </span>
                <span className="muted tabular-nums">
                  {task.status === "SUCCEEDED"
                    ? `${task.statementCount} 陈述 · ${task.selectedReferenceCount} 文献`
                    : `${Math.round(task.progress * 100)}%`}
                </span>
              </div>
              <time className="cit-history-time muted" dateTime={task.updatedAt}>{dateLabel(task.updatedAt)}</time>
            </a>
            {deletable && (
              <button type="button" className="cit-history-delete" aria-label={"删除任务 " + task.title}
                title="删除任务" onClick={() => onDeleteTask(task)}>
                <Trash2 size={13} aria-hidden="true" />
              </button>
            )}
          </div>
        })}
    </div>
  </aside>
}
