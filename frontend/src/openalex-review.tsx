import { useRef, useState } from "react"
import { api, ApiError, errorMessage, paperPath, useQuery } from "./api"
import { Loading, Modal, Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import type { Paper, Work } from "./types"

interface ReviewProps {
  paperId: string
  candidates: Work[]
  source?: { sourceId: string; name: string | null } | null
  scopeSourceId?: string
}

export function CandidateReview(props: ReviewProps) {
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  function close() {
    setOpen(false)
    window.requestAnimationFrame(() => trigger.current?.focus())
  }
  return <div className="mt-2">
    <Button ref={trigger} type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>复核候选</Button>
    {open && <ReviewDialog {...props} close={close} />}
  </div>
}

function ReviewDialog({ paperId, candidates, source, scopeSourceId, close }: ReviewProps & { close: () => void }) {
  const path = paperPath(paperId)
  const local = useQuery<Paper>(path)
  const [selected, setSelected] = useState(candidates[0])
  const [linked, setLinked] = useState<Paper>()
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const paper = linked ?? local.data
  const conflict = failure instanceof ApiError && failure.status === 409 ? failure : undefined
  const stale = conflict?.code === "PAPER_CHANGED"
  const rerun = conflict?.code === "CANDIDATE_MISSING" || conflict?.code === "WORK_ALREADY_BOUND"
  const candidateSource = source ? sourceLabel(source.sourceId, source.name) :
    scopeSourceId ? `${scopeSourceId}（任务范围）` : "旧任务未记录出版来源"

  async function confirm() {
    if (!paper || busy) return
    setBusy(true)
    setFailure(undefined)
    try {
      setLinked(await api<Paper>(path + "/match", {
        method: "POST", body: JSON.stringify({ workId: selected.workId, version: paper.version }),
      }))
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        const current = await api<Paper>(path).catch(() => null)
        if (current?.matchedWorkId) {
          setLinked(current)
          return
        }
      }
      setFailure(reason)
    } finally {
      setBusy(false)
    }
  }

  return <Modal title="OpenAlex 候选复核" onClose={close} closeDisabled={busy}>
    <div className="modal-body match-review form-stack">
      {local.error && <Notice error>{local.error.message}</Notice>}
      {local.loading && <Loading label="正在读取当前论文…" />}
      {paper && !local.loading && <>
        <label>OpenAlex 候选作品<select className="field" value={selected.workId} disabled={busy} onChange={(event) => {
          const work = candidates.find((candidate) => candidate.workId === event.target.value)
          if (work) setSelected(work)
          if (!stale) setFailure(undefined)
        }}>
          {candidates.map((work) => <option key={work.workId} value={work.workId}>{work.workId} · {work.title || "未登记标题"}</option>)}
        </select></label>
        <Comparison paper={paper} candidate={selected} candidateSource={candidateSource} />
      </>}
      {paper?.matchedWorkId && <Notice>已关联 OpenAlex 作品 {paper.matchedWorkId}</Notice>}
      {paper?.archived && !paper.matchedWorkId && <Notice error>此论文已归档。恢复论文后可以继续复核。</Notice>}
      {failure instanceof Error && <Notice error>{stale ? "论文已更新，请刷新论文并重新核对。" :
        conflict?.code === "WORK_ALREADY_BOUND" ? "该作品已有论文关联，请选择其他候选或重新匹配。" : errorMessage(failure)}</Notice>}
      <div className="modal-actions flex-wrap">
        {(paper?.matchedWorkId || paper?.archived || rerun) && <Button asChild variant="ghost"><a href={path}>查看论文</a></Button>}
        <Button type="button" variant="outline" disabled={busy} onClick={close}>{paper?.matchedWorkId ? "返回任务" : "稍后处理"}</Button>
        {(stale || local.error) && <Button type="button" disabled={local.loading} onClick={() => { setFailure(undefined); local.reload() }}>刷新论文</Button>}
        {paper && !paper.matchedWorkId && !paper.archived && !stale && !local.error &&
          <Button type="button" disabled={busy || local.loading || !!conflict} onClick={confirm}>{busy ? "正在确认…" : "确认关联"}</Button>}
      </div>
    </div>
  </Modal>
}

function Comparison({ paper, candidate, candidateSource }: { paper: Paper; candidate: Work; candidateSource: string }) {
  const rows = [
    ["标题", paper.title, candidate.title],
    ["作者", authorLabel(paper.authors), authorLabel(candidate.authors)],
    ["出版来源", sourceLabel(paper.sourceId, paper.sourceName), candidateSource],
    ["发表年份", paper.year, candidate.year],
    ["DOI", paper.doi, candidate.doi],
    ["OpenAlex 作品 ID", paper.matchedWorkId || "未关联", candidate.workId],
  ]
  return <table className="data-table match-review-table">
    <thead><tr><th scope="col">字段</th><th scope="col">当前本地书目</th><th scope="col">匹配候选快照</th></tr></thead>
    <tbody>{rows.map(([field, local, work]) => <tr key={field}>
      <th scope="row">{field}</th><td>{local || "未登记"}</td><td>{work || "未登记"}</td>
    </tr>)}</tbody>
  </table>
}

function authorLabel(authors: string | null) {
  return authors?.split(";").map((name) => name.trim()).filter(Boolean).join(" · ")
}

function sourceLabel(id: string | null, name: string | null) {
  return id && name && id !== name ? `${name}（${id}）` : name || id || "未登记"
}
