import { Check, Plus, Trash2, X } from "lucide-react"
import { useState } from "react"
import { Button } from "../components/ui/button"
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
  const [data, setData] = useState<RunReviewDraft[]>(() => JSON.parse(JSON.stringify(drafts)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")

  function updateClaim(runIndex: number, claimIndex: number, field: keyof EditableClaimItem, value: any) {
    setData((prev) => {
      const next = [...prev]
      const nextClaims = [...next[runIndex].claims]
      nextClaims[claimIndex] = { ...nextClaims[claimIndex], [field]: value }
      next[runIndex] = { ...next[runIndex], claims: nextClaims }
      return next
    })
  }

  function removeClaim(runIndex: number, claimIndex: number) {
    setData((prev) => {
      const next = [...prev]
      const nextClaims = next[runIndex].claims.filter((_, idx) => idx !== claimIndex)
      next[runIndex] = { ...next[runIndex], claims: nextClaims }
      return next
    })
  }

  function addClaim(runIndex: number) {
    const newItem: EditableClaimItem = {
      claimId: "custom-" + Date.now() + "-" + Math.random().toString(36).substring(2, 6),
      cause: "",
      effect: "",
      typeOfRelationship: "direct effect",
      signOfImpact: "positive",
      isMainContribution: true,
      statisticalSignificance: "p<0.01",
      causalInferenceMethod: "Other",
      sourcesOfExogenousVariation: "",
      levelOfTentativeness: "certain",
      evidenceMethodOtherDescription: "",
      evidence: "",
    }
    setData((prev) => {
      const next = [...prev]
      next[runIndex] = { ...next[runIndex], claims: [...next[runIndex].claims, newItem] }
      return next
    })
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
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal-card modal-xl" style={{ maxWidth: "1280px", width: "95vw", maxHeight: "90vh", display: "flex", flexDirection: "column" }}>
        <div className="modal-header">
          <div>
            <h2 className="modal-title">因果声明人工审核</h2>
            <p className="modal-description text-sm text-muted">
              {isBatch
                ? `共 ${data.length} 篇待审核论文，当前累计 ${totalClaims} 条因果声明记录。您可编辑、新增或删除，确认后将正式写入因果知识库。`
                : `论文 ${data[0]?.paperTitle || data[0]?.workId} · 当前共 ${totalClaims} 条因果声明记录。`}
            </p>
          </div>
          <button type="button" className="btn-close" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        {error && <div className="notice notice-error mb-3">{error}</div>}

        <div style={{ overflowY: "auto", flex: 1, padding: "1rem" }}>
          {data.map((draft, runIdx) => (
            <div key={draft.runId} className="panel mb-4 p-4 border rounded">
              <div className="flex justify-between items-center mb-3">
                <div>
                  <h3 className="font-semibold text-base">{draft.paperTitle || draft.paperId}</h3>
                  <span className="mono text-xs text-muted">作品 ID: {draft.workId} · 单篇运行 ID: {draft.runId}</span>
                </div>
                <Button type="button" size="sm" variant="outline" onClick={() => addClaim(runIdx)}>
                  <Plus size={14} className="mr-1" />添加因果声明
                </Button>
              </div>

              {draft.claims.length === 0 ? (
                <div className="text-center py-6 text-muted border border-dashed rounded">
                  未提取到因果声明记录（或已全部删除）。审核通过入库后，该篇论文状态将记录为「已抽取（无因果）」。
                </div>
              ) : (
                <div className="table-scroll">
                  <table className="data-table" style={{ fontSize: "0.825rem" }}>
                    <thead>
                      <tr>
                        <th style={{ width: "120px" }}>原因变量 (cause)</th>
                        <th style={{ width: "120px" }}>结果变量 (effect)</th>
                        <th style={{ width: "110px" }}>影响方向</th>
                        <th style={{ width: "110px" }}>关系类型</th>
                        <th style={{ width: "70px" }}>核心贡献</th>
                        <th style={{ width: "120px" }}>推断方法</th>
                        <th style={{ width: "90px" }}>确定性</th>
                        <th style={{ width: "100px" }}>显著性</th>
                        <th style={{ width: "160px" }}>原文佐证 (evidence)</th>
                        <th style={{ width: "50px" }}>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {draft.claims.map((claim, claimIdx) => (
                        <tr key={claim.claimId || claimIdx}>
                          <td>
                            <input
                              className="input input-sm"
                              value={claim.cause}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "cause", e.target.value)}
                              placeholder="原因变量"
                            />
                          </td>
                          <td>
                            <input
                              className="input input-sm"
                              value={claim.effect}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "effect", e.target.value)}
                              placeholder="结果变量"
                            />
                          </td>
                          <td>
                            <select
                              className="select select-sm"
                              value={claim.signOfImpact}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "signOfImpact", e.target.value)}
                            >
                              {SIGN_OF_IMPACT_OPTIONS.map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            <select
                              className="select select-sm"
                              value={claim.typeOfRelationship}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "typeOfRelationship", e.target.value)}
                            >
                              {TYPE_OF_RELATIONSHIP_OPTIONS.map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </select>
                          </td>
                          <td className="text-center">
                            <input
                              type="checkbox"
                              checked={claim.isMainContribution}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "isMainContribution", e.target.checked)}
                            />
                          </td>
                          <td>
                            <select
                              className="select select-sm"
                              value={claim.causalInferenceMethod}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "causalInferenceMethod", e.target.value)}
                            >
                              {CAUSAL_INFERENCE_METHOD_OPTIONS.map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            <select
                              className="select select-sm"
                              value={claim.levelOfTentativeness || "certain"}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "levelOfTentativeness", e.target.value)}
                            >
                              {LEVEL_OF_TENTATIVENESS_OPTIONS.map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            <select
                              className="select select-sm"
                              value={claim.statisticalSignificance || "p<0.01"}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "statisticalSignificance", e.target.value)}
                            >
                              {STATISTICAL_SIGNIFICANCE_OPTIONS.map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            <textarea
                              className="input input-sm"
                              rows={2}
                              value={claim.evidence}
                              onChange={(e) => updateClaim(runIdx, claimIdx, "evidence", e.target.value)}
                              placeholder="原文佐证句子"
                            />
                          </td>
                          <td className="text-center">
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              onClick={() => removeClaim(runIdx, claimIdx)}
                              className="text-danger p-1"
                              title="删除此行"
                            >
                              <Trash2 size={14} />
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ))}
        </div>

        <div className="modal-actions border-t p-3 flex justify-between items-center">
          <div className="text-sm text-muted">
            共计确认 {totalClaims} 条因果声明
          </div>
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
              取消
            </Button>
            <Button type="button" onClick={handleConfirm} disabled={busy}>
              <Check size={14} className="mr-1" />
              审核通过并确定入库
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
