import { Check, Plus, Trash2, X } from "lucide-react"
import { useEffect, useId, useRef, useState } from "react"
import { Notice } from "../components/shared"
import { Button } from "../components/ui/button"
import { HelpTip } from "./components"
import type { EditableClaimItem, RunReviewDraft } from "./types"

export const SIGN_OF_IMPACT_OPTIONS = [
  "positive",
  "negative",
  "null_effect",
  "u_shaped",
  "ambiguous",
]

export const TYPE_OF_RELATIONSHIP_OPTIONS = [
  "direct effect",
  "indirect effect",
  "confounding",
  "mediation",
  "collider",
  "ancestor",
  "descendant",
  "bidirectional",
  "association",
  "other",
]

export const CAUSAL_INFERENCE_METHOD_OPTIONS = [
  "RDD",
  "DID",
  "RCT",
  "IV",
  "Structural Estimation",
  "Fixed Effects Models",
  "Event Study",
  "Simulation",
  "Matching Methods",
  "Synthetic Controls",
  "VAR",
  "Theoretical/Non-Empirical",
  "Machine Learning Methods",
  "Bayesian Methods",
  "Other",
  "Do not know",
]

export const LEVEL_OF_TENTATIVENESS_OPTIONS = [
  "certain",
  "tentative",
]

export const STATISTICAL_SIGNIFICANCE_OPTIONS = [
  "p<0.01",
  "0.01<=p<0.05",
  "0.05<=p<0.1",
  "p>0.1",
  "NA",
]

function optionsWithCurrent(options: string[], current: string | null | undefined) {
  const val = (current ?? "").trim()
  return val && !options.includes(val) ? [val, ...options] : options
}

export function ReviewWorkbenchModal({
  drafts,
  onClose,
  onSubmit,
  isBatch = false,
}: {
  drafts: RunReviewDraft[]
  onClose: () => void
  onSubmit: (updatedDrafts: RunReviewDraft[]) => Promise<void>
  isBatch?: boolean
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const [data, setData] = useState<RunReviewDraft[]>(() => JSON.parse(JSON.stringify(drafts)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])

  function updateClaim<K extends keyof EditableClaimItem>(
    runIndex: number,
    claimIndex: number,
    field: K,
    value: EditableClaimItem[K],
  ) {
    setData((prev) => prev.map((draft, rIdx) => {
      if (rIdx !== runIndex) return draft
      return {
        ...draft,
        claims: draft.claims.map((item, cIdx) => (cIdx === claimIndex ? { ...item, [field]: value } : item)),
      }
    }))
  }

  function removeClaim(runIndex: number, claimIndex: number) {
    setData((prev) => prev.map((draft, rIdx) => (
      rIdx === runIndex ? { ...draft, claims: draft.claims.filter((_, idx) => idx !== claimIndex) } : draft
    )))
  }

  function addClaim(runIndex: number) {
    const newItem: EditableClaimItem = {
      claimId: "custom-" + Date.now() + "-" + Math.random().toString(36).substring(2, 6),
      cause: "",
      effect: "",
      typeOfRelationship: "direct effect",
      signOfImpact: "positive",
      isMainContribution: true,
      statisticalSignificance: "NA",
      causalInferenceMethod: "Other",
      sourcesOfExogenousVariation: "",
      levelOfTentativeness: "certain",
      evidenceMethodOtherDescription: "",
      evidence: "",
    }
    setData((prev) => prev.map((draft, rIdx) => (
      rIdx === runIndex ? { ...draft, claims: [...draft.claims, newItem] } : draft
    )))
  }

  async function handleConfirm() {
    setBusy(true)
    setError("")
    try {
      await onSubmit(data)
      onClose()
    } catch (err: any) {
      setError(err?.message || "提交审核入库失败")
    } finally {
      setBusy(false)
    }
  }

  const totalClaims = data.reduce((acc, cur) => acc + cur.claims.length, 0)

  return (
    <dialog
      ref={dialogRef}
      className="modal review-modal"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        if (!busy) onClose()
      }}
    >
      <div className="modal-heading">
        <div className="min-w-0">
          <h2 id={titleId}>因果主张人工审核</h2>
          <p>
            {isBatch
              ? `共 ${data.length} 篇待审核论文，当前累计 ${totalClaims} 条因果主张。可逐条核对、编辑、新增或删除，确认后正式写入因果知识库。`
              : `论文：${data[0]?.paperTitle || data[0]?.paperId || data[0]?.workId || "—"} · 当前共 ${totalClaims} 条因果主张`}
          </p>
        </div>
        <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label="关闭" disabled={busy}>
          <X size={18} />
        </Button>
      </div>

      <div className="review-modal-body">
        {error && <div className="mb-3"><Notice error>{error}</Notice></div>}

        {data.map((draft, runIdx) => (
          <section key={draft.runId} className="review-paper-section">
            <div className="review-paper-head">
              <div className="min-w-0">
                <h3 className="review-paper-title">{draft.paperTitle || draft.paperId}</h3>
                <div className="review-paper-meta mono">
                  <span>Work: {draft.workId || "—"}</span>
                  <span aria-hidden="true">·</span>
                  <span>Paper: {draft.paperId}</span>
                  <span aria-hidden="true">·</span>
                  <span>{draft.claims.length} 条主张</span>
                </div>
              </div>
              <Button type="button" size="sm" variant="outline" onClick={() => addClaim(runIdx)} disabled={busy}>
                <Plus size={14} aria-hidden="true" />添加因果主张
              </Button>
            </div>

            {draft.claims.length === 0 ? (
              <div className="review-empty">
                未提取到因果主张记录（或已全部删除）。审核通过入库后，该篇论文状态将记录为「已抽取（无因果）」。
              </div>
            ) : (
              <div className="review-claim-list">
                {draft.claims.map((claim, claimIdx) => {
                  const preview = claim.cause || claim.effect
                    ? `${claim.cause || "未填原因"} → ${claim.effect || "未填结果"}`
                    : "新因果主张"
                  const methodDesc = (claim.evidenceMethodOtherDescription ?? "").trim()
                  const hasMethodDescTip = Boolean(methodDesc) && methodDesc.toUpperCase() !== "NA"

                  return (
                    <article key={claim.claimId || claimIdx} className="review-claim-card">
                      <div className="review-claim-head">
                        <div className="review-claim-title" title={preview}>
                          <span className="review-claim-index">#{claimIdx + 1}</span>
                          <span className="review-claim-preview">{preview}</span>
                        </div>
                        <div className="review-claim-tools">
                          <label className="review-core-toggle">
                            <input
                              type="checkbox"
                              checked={claim.isMainContribution}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "isMainContribution", e.target.checked)}
                            />
                            <span>核心发现</span>
                          </label>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => removeClaim(runIdx, claimIdx)}
                            className="text-danger"
                            title="删除此条因果主张"
                            disabled={busy}
                          >
                            <Trash2 size={14} aria-hidden="true" />删除
                          </Button>
                        </div>
                      </div>

                      <div className="review-grid-2">
                        <label className="review-field">
                          <span>原因变量 <small className="mono muted">cause</small></span>
                          <input
                            className="field"
                            value={claim.cause}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "cause", e.target.value)}
                            placeholder="原因变量"
                          />
                        </label>
                        <label className="review-field">
                          <span>结果变量 <small className="mono muted">effect</small></span>
                          <input
                            className="field"
                            value={claim.effect}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "effect", e.target.value)}
                            placeholder="结果变量"
                          />
                        </label>
                      </div>

                      <div className="review-grid-5">
                        <label className="review-field">
                          <span>关系类型</span>
                          <select
                            className="field"
                            value={claim.typeOfRelationship || "other"}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "typeOfRelationship", e.target.value)}
                          >
                            {optionsWithCurrent(TYPE_OF_RELATIONSHIP_OPTIONS, claim.typeOfRelationship).map((opt) => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </select>
                        </label>
                        <label className="review-field">
                          <span>影响方向</span>
                          <select
                            className="field"
                            value={claim.signOfImpact || "ambiguous"}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "signOfImpact", e.target.value)}
                          >
                            {optionsWithCurrent(SIGN_OF_IMPACT_OPTIONS, claim.signOfImpact).map((opt) => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </select>
                        </label>
                        <label className="review-field">
                          <span>统计显著性</span>
                          <select
                            className="field"
                            value={claim.statisticalSignificance || "NA"}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "statisticalSignificance", e.target.value)}
                          >
                            {optionsWithCurrent(STATISTICAL_SIGNIFICANCE_OPTIONS, claim.statisticalSignificance).map((opt) => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </select>
                        </label>
                        <label className="review-field">
                          <span>因果推断方法</span>
                          <select
                            className="field"
                            value={claim.causalInferenceMethod || "Do not know"}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "causalInferenceMethod", e.target.value)}
                          >
                            {optionsWithCurrent(CAUSAL_INFERENCE_METHOD_OPTIONS, claim.causalInferenceMethod).map((opt) => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </select>
                        </label>
                        <label className="review-field">
                          <span>语言确定性</span>
                          <select
                            className="field"
                            value={claim.levelOfTentativeness || "certain"}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "levelOfTentativeness", e.target.value)}
                          >
                            {optionsWithCurrent(LEVEL_OF_TENTATIVENESS_OPTIONS, claim.levelOfTentativeness).map((opt) => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </select>
                        </label>
                      </div>

                      <div className="review-grid-2">
                        <label className="review-field">
                          <span>外生变异来源 <small className="mono muted">sources_of_exogenous_variation</small></span>
                          <textarea
                            className="field"
                            rows={2}
                            value={claim.sourcesOfExogenousVariation ?? ""}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "sourcesOfExogenousVariation", e.target.value)}
                            placeholder="如政策冲击、自然实验、工具变量来源（无则留空或 NA）"
                          />
                        </label>
                        <label className="review-field">
                          <span className="review-field-label-tip">
                            <span>证据方法描述 <small className="mono muted">evidence_method_other_description</small></span>
                            <HelpTip
                              placement="top"
                              label="证据方法描述说明"
                              tip={hasMethodDescTip
                                ? `当前描述：${methodDesc}`
                                : "当因果推断方法为 Other 或需补充识别策略细节时的原文说明；无则填 NA 或留空。"}
                            />
                          </span>
                          <textarea
                            className="field"
                            rows={2}
                            value={claim.evidenceMethodOtherDescription ?? ""}
                            onChange={(e) => updateClaim(runIdx, claimIdx, "evidenceMethodOtherDescription", e.target.value)}
                            placeholder="当推断方法为 Other 时的具体方法描述（无则 NA）"
                          />
                        </label>
                      </div>

                      <label className="review-field">
                        <span>原文佐证 <small className="mono muted">evidence</small></span>
                        <textarea
                          className="field"
                          rows={3}
                          value={claim.evidence}
                          onChange={(e) => updateClaim(runIdx, claimIdx, "evidence", e.target.value)}
                          placeholder="原文证据句段"
                        />
                      </label>
                    </article>
                  )
                })}
              </div>
            )}
          </section>
        ))}
      </div>

      <div className="review-modal-footer">
        <span className="muted text-sm tabular-nums">
          共 {data.length} 篇论文 · 确认 {totalClaims} 条因果主张
        </span>
        <div className="modal-actions">
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={busy}>
            <Check size={14} aria-hidden="true" />
            {busy ? "正在入库…" : "审核通过并确定入库"}
          </Button>
        </div>
      </div>
    </dialog>
  )
}
