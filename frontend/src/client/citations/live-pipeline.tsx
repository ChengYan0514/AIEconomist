import { AlertTriangle, CheckCircle2, Circle, LoaderCircle, Radio, RotateCcw, Square } from "lucide-react"
import { dateLabel } from "../../api"
import { Notice } from "../../components/shared"
import { Button } from "../../components/ui/button"
import { CITATION_STAGE_STEPS, CITATION_STATUS_LABEL, type CitationStage, type CitationTaskDetail } from "../types"

const STAGE_ORDER: CitationStage[] = [
  "QUEUED",
  "PARSE_TEXT",
  "PLAN_RETRIEVAL",
  "RETRIEVE_REFERENCES",
  "RANK_EVIDENCE",
  "ENRICH_METADATA",
  "FORMAT_OUTPUT",
  "COMPLETED",
]

function stageIndex(stage: CitationStage): number {
  const idx = STAGE_ORDER.indexOf(stage)
  return idx >= 0 ? idx : 0
}

export function LivePipeline({
  task,
  liveConnected,
  cancelling,
  error,
  onCancel,
  onRetryDraft,
}: {
  task: CitationTaskDetail
  liveConnected: boolean
  cancelling: boolean
  error: string
  onCancel: () => void
  onRetryDraft: () => void
}) {
  const isRunning = task.status === "QUEUED" || task.status === "RUNNING"
  const isFailed = task.status === "FAILED"
  const isCancelled = task.status === "CANCELLED"
  const [statusLabel, tone] = CITATION_STATUS_LABEL[task.status]
  const currentIdx = stageIndex(task.stage)
  const pct = Math.min(100, Math.max(0, Math.round(task.progress * 100)))

  return <div className="cit-live-wrap">
    <div className="cit-paper-card cit-live-card">
      <div className="cit-live-head">
        <div>
          <div className="cit-live-kicker">
            <span className={"status-chip status-" + tone}><span className="status-dot" aria-hidden="true" />{statusLabel}</span>
            {isRunning && (
              <span className={"causal-sse-pill" + (liveConnected ? " is-live" : "")}>
                <Radio size={12} aria-hidden="true" />{liveConnected ? "实时进度已连接" : "正在同步状态"}
              </span>
            )}
          </div>
          <h2>{task.title}</h2>
          <p className="muted">创建于 {dateLabel(task.createdAt)} · 引擎 <span className="mono">{task.engineProvider}</span></p>
        </div>
        <div className="cit-live-actions">
          {isRunning && <Button type="button" variant="outline" disabled={cancelling} onClick={onCancel}>
            <Square size={14} aria-hidden="true" />{cancelling ? "正在取消…" : "取消任务"}
          </Button>}
          {(isFailed || isCancelled) && <Button type="button" onClick={onRetryDraft}>
            <RotateCcw size={14} aria-hidden="true" />回到草稿修改重提
          </Button>}
        </div>
      </div>

      {error && <Notice error>{error}</Notice>}
      {isFailed && <Notice error>{task.errorMessage || "引文标注执行失败，请检查模型/检索引擎状态后重试。"}</Notice>}
      {isCancelled && <Notice>该引文标注任务已取消。你可以回到草稿调整文本或检索条件后重新提交。</Notice>}

      <div className="cit-progress-track-wrap">
        <div className="cit-progress-meta">
          <span>总体进度</span>
          <strong className="tabular-nums">{pct}%</strong>
        </div>
        <div className="cit-progress-track" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
          <div className={"cit-progress-fill" + (isFailed ? " is-red" : "")} style={{ width: `${pct}%` }} />
        </div>
      </div>

      <ol className="cit-stepper" aria-label="处理阶段">
        {CITATION_STAGE_STEPS.map((step) => {
          const stepIdx = stageIndex(step.stage)
          const done = currentIdx > stepIdx || task.status === "SUCCEEDED"
          const active = isRunning && currentIdx === stepIdx
          return <li key={step.stage} className={"cit-step" + (done ? " is-done" : active ? " is-active" : "")}>
            <span className="cit-step-icon" aria-hidden="true">
              {done ? <CheckCircle2 size={16} /> : active ? <LoaderCircle size={16} className="animate-spin" /> : <Circle size={15} />}
            </span>
            <span>{step.label}</span>
          </li>
        })}
      </ol>

      <div className="cit-timeline-box">
        <h3>执行事件流</h3>
        {task.events.length === 0 ? <p className="muted">等待引擎回报首批进度事件…</p> :
          <ol className="cit-event-list">
            {task.events.map((ev) => (
              <li key={ev.eventId} className={"cit-event-item" + (ev.eventType === "FAILED" ? " is-error" : ev.eventType === "WARNING" ? " is-warn" : "")}>
                <div className="cit-event-dot" aria-hidden="true">
                  {ev.eventType === "FAILED" || ev.eventType === "WARNING" ? <AlertTriangle size={13} /> : <span className="status-dot" />}
                </div>
                <div className="cit-event-copy">
                  <div className="cit-event-row">
                    <strong>{ev.message}</strong>
                    <span className="mono muted tabular-nums">{Math.round(ev.progress * 100)}%</span>
                  </div>
                  <time className="muted" dateTime={ev.occurredAt}>{dateLabel(ev.occurredAt)}</time>
                </div>
              </li>
            ))}
          </ol>}
      </div>

      <details className="cit-original-preview">
        <summary>查看提交原文（{task.originalText.length.toLocaleString()} 字符）</summary>
        <p>{task.originalText}</p>
      </details>
    </div>
  </div>
}
