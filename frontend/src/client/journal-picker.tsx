import { BookMarked, Check, ChevronDown, Search, X } from "lucide-react"
import { useEffect, useMemo, useRef, useState } from "react"
import { listClientJournals } from "./api"
import type { ClientJournal } from "./types"

export function JournalPicker({
  selectedIds,
  onChange,
  disabled,
  compact,
  label = "限定检索期刊",
}: {
  selectedIds: string[]
  onChange: (nextIds: string[]) => void
  disabled?: boolean
  compact?: boolean
  label?: string
}) {
  const [journals, setJournals] = useState<ClientJournal[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    listClientJournals(undefined, 200)
      .then((res) => { if (active) { setJournals(res.items); setFailed(false) } })
      .catch(() => { if (active) setFailed(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!open) return
    function onOutside(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onOutside)
    return () => document.removeEventListener("mousedown", onOutside)
  }, [open])

  const byId = useMemo(() => new Map(journals.map((j) => [j.sourceId, j])), [journals])
  const selectedSet = useMemo(() => new Set(selectedIds), [selectedIds])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return journals
    return journals.filter((j) => j.name.toLowerCase().includes(q) || j.sourceId.toLowerCase().includes(q))
  }, [journals, query])

  function toggle(sourceId: string) {
    if (selectedSet.has(sourceId)) onChange(selectedIds.filter((id) => id !== sourceId))
    else onChange([...selectedIds, sourceId])
  }

  return <div className={"client-journal-picker" + (compact ? " is-compact" : "")} ref={wrapRef}>
    <div className="client-field-head">
      <span className="client-field-label"><BookMarked size={14} aria-hidden="true" />{label}</span>
      {selectedIds.length > 0 && !disabled && (
        <button type="button" className="client-text-btn" onClick={() => onChange([])}>清空已选（{selectedIds.length}）</button>
      )}
    </div>

    <button type="button" className="field client-picker-trigger" disabled={disabled || failed}
      aria-expanded={open} onClick={() => setOpen((v) => !v)}>
      <span className={selectedIds.length ? "" : "muted"}>
        {failed ? "期刊目录加载失败（提交时不限来源）" :
          loading ? "正在加载本地期刊目录…" :
          selectedIds.length === 0 ? `全部本地期刊（共 ${journals.length} 种，不限来源）` :
          `已限定 ${selectedIds.length} 种期刊`}
      </span>
      <ChevronDown size={15} className="muted" aria-hidden="true" />
    </button>

    {selectedIds.length > 0 && (
      <div className="client-chip-list" aria-label="已选期刊">
        {selectedIds.map((id) => {
          const item = byId.get(id)
          return <span key={id} className="client-selected-chip">
            <span className="client-selected-chip-text" title={item?.name ?? id}>{item?.name ?? id}</span>
            {!disabled && <button type="button" aria-label={"移除 " + (item?.name ?? id)} onClick={() => toggle(id)}>
              <X size={12} aria-hidden="true" />
            </button>}
          </span>
        })}
      </div>
    )}

    {open && !disabled && !failed && (
      <div className="client-picker-popover" role="listbox" aria-multiselectable="true" aria-label={label}>
        <div className="search-box client-picker-search">
          <Search size={14} aria-hidden="true" />
          <input className="field" type="search" value={query} autoFocus
            placeholder="搜索期刊名或 OpenAlex Source ID…"
            onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="client-picker-list">
          {filtered.length === 0 ? <p className="muted client-picker-empty">未匹配到期刊</p> :
            filtered.map((item) => {
              const checked = selectedSet.has(item.sourceId)
              return <button key={item.sourceId} type="button" role="option" aria-selected={checked}
                className={"client-picker-option" + (checked ? " is-checked" : "")}
                onClick={() => toggle(item.sourceId)}>
                <span className={"client-picker-box" + (checked ? " is-checked" : "")} aria-hidden="true">
                  {checked && <Check size={12} strokeWidth={2.5} />}
                </span>
                <span className="client-picker-meta">
                  <strong>{item.name}</strong>
                  <small className="muted">
                    <span className="mono">{item.sourceId}</span>
                    {" · "}全文 {item.publishedCount.toLocaleString()} / {item.paperCount.toLocaleString()} 篇
                    {item.yearMin && item.yearMax ? ` · ${item.yearMin}–${item.yearMax}` : ""}
                  </small>
                </span>
              </button>
            })}
        </div>
      </div>
    )}
  </div>
}
