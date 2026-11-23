import {
  Activity, ArrowRight, BookMarked, ClipboardList, CornerDownLeft, Database, FileText,
  LayoutDashboard, Network, Search, Shield, Upload, UsersRound,
} from "lucide-react"
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react"
import { paperPath, params, useQuery } from "../api"
import { usePermissions, type Permission } from "../permissions"
import { navigate } from "../router"
import type { JournalDirectory, Page, Paper } from "../types"

interface CommandItem {
  id: string
  group: string
  label: string
  hint?: string
  badge?: string
  icon: ReactNode
  href: string
}

const navTargets: { path: string; label: string; group: string; keywords: string; icon: typeof FileText; permission?: Permission; badge?: string }[] = [
  { path: "/overview", label: "总览", group: "工作区导航", keywords: "overview dashboard home 首页 概览", icon: LayoutDashboard },
  { path: "/journals", label: "期刊目录", group: "工作区导航", keywords: "journals sources 出版来源 期刊 覆盖", icon: BookMarked, permission: "PAPER_READ" },
  { path: "/papers", label: "论文库", group: "工作区导航", keywords: "papers library 文献 检索 导出", icon: FileText, permission: "PAPER_READ" },
  { path: "/jobs", label: "导入与处理", group: "工作区导航", keywords: "jobs tasks zip mineru parse 解析 任务", icon: Upload, permission: "JOB_READ" },
  { path: "/openalex", label: "OpenAlex", group: "工作区导航", keywords: "openalex metadata 书目 匹配 快照", icon: Database, permission: "OPENALEX_MANAGE" },
  { path: "/causal-graph", label: "因果图谱", group: "工作区导航", keywords: "causal graph claims 因果 主张 图谱", icon: Network, badge: "规划中" },
  { path: "/system-status", label: "系统状态", group: "系统管理", keywords: "system status health postgres mineru 健康", icon: Activity, permission: "SYSTEM_MANAGE" },
  { path: "/users", label: "用户管理", group: "系统管理", keywords: "users accounts 账号 密码", icon: UsersRound, permission: "SYSTEM_MANAGE" },
  { path: "/roles", label: "角色管理", group: "系统管理", keywords: "roles permissions 权限", icon: Shield, permission: "SYSTEM_MANAGE" },
  { path: "/audit", label: "审计日志", group: "系统管理", keywords: "audit logs 追溯 记录", icon: ClipboardList, permission: "SYSTEM_MANAGE" },
]

export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { can, canJob } = usePermissions()
  const [query, setQuery] = useState("")
  const [debounced, setDebounced] = useState("")
  const [activeIndex, setActiveIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listId = useId()

  useEffect(() => {
    if (!open) {
      setQuery("")
      setDebounced("")
      setActiveIndex(0)
      return
    }
    window.requestAnimationFrame(() => inputRef.current?.focus())
  }, [open])

  useEffect(() => {
    if (!open) return
    const timer = window.setTimeout(() => setDebounced(query.trim()), 180)
    return () => window.clearTimeout(timer)
  }, [query, open])

  const canReadPapers = can("PAPER_READ")
  const journalResults = useQuery<JournalDirectory>(
    open && canReadPapers && debounced.length >= 1 ? "/journals" + params({ q: debounced, size: 4 }) : null,
  )
  const paperResults = useQuery<Page<Paper>>(
    open && canReadPapers && debounced.length >= 2 ? "/papers" + params({ q: debounced, size: 5 }) : null,
  )

  const items = useMemo<CommandItem[]>(() => {
    const q = query.trim().toLowerCase()
    const list: CommandItem[] = []

    for (const target of navTargets) {
      const allowed = target.path === "/openalex" ? canJob("METADATA") : !target.permission || can(target.permission)
      if (!allowed) continue
      if (q && !target.label.toLowerCase().includes(q) && !target.keywords.toLowerCase().includes(q) && !target.path.includes(q)) {
        continue
      }
      const Icon = target.icon
      list.push({
        id: "nav:" + target.path,
        group: target.group,
        label: target.label,
        hint: target.path,
        badge: target.badge,
        icon: <Icon size={16} strokeWidth={1.7} aria-hidden="true" />,
        href: target.path,
      })
    }

    if (canReadPapers && debounced.length >= 1 && journalResults.data?.items.length) {
      for (const journal of journalResults.data.items) {
        const rate = journal.totalPapers > 0 ? Math.round((journal.publishedCount / journal.totalPapers) * 100) : 0
        list.push({
          id: "journal:" + journal.sourceId,
          group: "匹配期刊",
          label: journal.name,
          hint: `${journal.sourceId} · ${journal.totalPapers} 篇 · 已发布 ${rate}%`,
          icon: <BookMarked size={16} strokeWidth={1.7} aria-hidden="true" />,
          href: "/journals" + params({ journal: journal.sourceId }),
        })
      }
    }

    if (canReadPapers && debounced.length >= 2 && paperResults.data?.items.length) {
      for (const paper of paperResults.data.items) {
        list.push({
          id: "paper:" + paper.paperId,
          group: "匹配论文",
          label: paper.title || paper.paperId,
          hint: [paper.sourceName || paper.sourceId, paper.year].filter(Boolean).join(" · "),
          icon: <FileText size={16} strokeWidth={1.7} aria-hidden="true" />,
          href: paperPath(paper.paperId),
        })
      }
    }
    return list
  }, [query, debounced, can, canJob, canReadPapers, journalResults.data, paperResults.data])

  useEffect(() => { setActiveIndex(0) }, [items.length, query])

  if (!open) return null

  const safeIndex = Math.min(activeIndex, Math.max(items.length - 1, 0))
  const grouped = new Map<string, { item: CommandItem; index: number }[]>()
  items.forEach((item, index) => {
    const bucket = grouped.get(item.group) ?? []
    bucket.push({ item, index })
    grouped.set(item.group, bucket)
  })

  function select(item: CommandItem) {
    onClose()
    navigate(item.href)
  }

  return <div className="command-backdrop" onClick={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <div className="command-dialog" role="dialog" aria-modal="true" aria-label="快速命令与全局搜索">
      <div className="command-input-wrap">
        <Search size={17} aria-hidden="true" />
        <input
          ref={inputRef}
          className="command-input"
          type="search"
          role="combobox"
          aria-expanded="true"
          aria-controls={listId}
          aria-label="搜索页面、期刊或论文"
          placeholder="搜索页面、期刊名称、来源 ID 或论文标题…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") { event.preventDefault(); onClose() }
            else if (event.key === "ArrowDown") { event.preventDefault(); setActiveIndex((i) => items.length ? (i + 1) % items.length : 0) }
            else if (event.key === "ArrowUp") { event.preventDefault(); setActiveIndex((i) => items.length ? (i - 1 + items.length) % items.length : 0) }
            else if (event.key === "Enter" && items[safeIndex]) { event.preventDefault(); select(items[safeIndex]) }
          }}
        />
        <kbd className="kbd-pill">Esc</kbd>
      </div>
      <div id={listId} role="listbox" className="command-list">
        {items.length === 0 ? (
          <div className="command-empty">
            {journalResults.loading || paperResults.loading ? "正在检索期刊与论文…" : "没有匹配的页面、期刊或论文"}
          </div>
        ) : [...grouped.entries()].map(([groupName, entries]) => (
          <div className="command-group" key={groupName}>
            <div className="command-group-label">{groupName}</div>
            {entries.map(({ item, index }) => (
              <button
                key={item.id}
                type="button"
                role="option"
                aria-selected={index === safeIndex}
                className={"command-option" + (index === safeIndex ? " is-active" : "")}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => select(item)}
              >
                <span className="command-option-icon">{item.icon}</span>
                <span className="command-option-copy">
                  <span className="command-option-title">
                    {item.label}
                    {item.badge && <span className="nav-badge">{item.badge}</span>}
                  </span>
                  {item.hint && <span className="command-option-hint">{item.hint}</span>}
                </span>
                <ArrowRight size={14} className="command-option-arrow" aria-hidden="true" />
              </button>
            ))}
          </div>
        ))}
      </div>
      <div className="command-footer" aria-hidden="true">
        <span><CornerDownLeft size={12} /> 回车打开</span>
        <span>↑ ↓ 切换</span>
        <span>⌘K / Ctrl+K 随时唤出</span>
      </div>
    </div>
  </div>
}
