import {
  Activity, BarChart3, BookMarked, ChevronDown, ChevronRight, ClipboardList, Database, FileText,
  LayoutDashboard, LogOut, Menu, Network, PanelLeftClose, PanelLeftOpen, Search,
  Shield, ShieldCheck, Sparkles, Upload, UsersRound, X,
} from "lucide-react"
import { useEffect, useState, type ReactNode } from "react"
import { usePermissions, type Permission } from "../permissions"
import { CommandPalette } from "./command-palette"
import { PasswordForm } from "./password"
import { Modal } from "./shared"
import { Button } from "./ui/button"

export function Brand({ subtitle, compact }: { subtitle?: string; compact?: boolean }) {
  return <div className="brand">
    <span className="brand-seal" aria-hidden="true">
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
        <rect x="2.25" y="9" width="2.5" height="4.75" rx="0.5" fill="currentColor" opacity="0.72" />
        <rect x="6.75" y="5.5" width="2.5" height="8.25" rx="0.5" fill="currentColor" opacity="0.88" />
        <rect x="11.25" y="2.25" width="2.5" height="11.5" rx="0.5" fill="currentColor" />
      </svg>
    </span>
    {!compact && <span className="brand-text">
      <strong>AI Economist</strong>
      {subtitle && <span className="brand-subtitle" aria-hidden="true">{subtitle}</span>}
    </span>}
  </div>
}

interface NavItem {
  path: string
  label: string
  icon: typeof FileText
  permission?: Permission
  badge?: string
}

interface NavGroup {
  id: string
  label: string
  items: NavItem[]
}

const primaryItem: NavItem = { path: "/overview", label: "总览", icon: LayoutDashboard }

const navigationGroups: NavGroup[] = [
  {
    id: "corpus",
    label: "文献管理",
    items: [
      { path: "/journals", label: "期刊目录", icon: BookMarked, permission: "PAPER_READ" },
      { path: "/papers", label: "论文库", icon: FileText, permission: "PAPER_READ" },
    ],
  },
  {
    id: "pipeline",
    label: "数据处理",
    items: [
      { path: "/jobs", label: "导入与处理", icon: Upload, permission: "JOB_READ" },
      { path: "/openalex", label: "OpenAlex", icon: Database, permission: "OPENALEX_MANAGE" },
    ],
  },
  {
    id: "knowledge",
    label: "知识图谱",
    items: [
      { path: "/causal-graph", label: "因果图谱", icon: Network, permission: "CAUSAL_READ" },
      { path: "/causal-fields", label: "领域分析", icon: BarChart3, permission: "CAUSAL_READ" },
      { path: "/causal-extraction", label: "因果抽取", icon: Sparkles, permission: "CAUSAL_READ" },
    ],
  },
  {
    id: "system",
    label: "系统管理",
    items: [
      { path: "/system-status", label: "系统状态", icon: Activity, permission: "SYSTEM_MANAGE" },
      { path: "/users", label: "用户管理", icon: UsersRound, permission: "SYSTEM_MANAGE" },
      { path: "/roles", label: "角色管理", icon: Shield, permission: "SYSTEM_MANAGE" },
      { path: "/audit", label: "审计日志", icon: ClipboardList, permission: "SYSTEM_MANAGE" },
    ],
  },
]

function readCollapsedGroups(): Record<string, boolean> {
  try {
    const raw = window.localStorage.getItem("aie.nav.collapsed")
    return raw ? JSON.parse(raw) : {}
  } catch { return {} }
}

function readRailMode(): boolean {
  try { return window.localStorage.getItem("aie.sidebar.rail") === "1" }
  catch { return false }
}

export function Workspace({ page, displayName, children, logout }:
  { page: string; displayName: string; children: ReactNode; logout: () => void }) {
  const { can, canJob } = usePermissions()
  const [password, setPassword] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [rail, setRail] = useState(readRailMode)
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(readCollapsedGroups)
  const [commandOpen, setCommandOpen] = useState(false)

  useEffect(() => { setExpanded(false) }, [page])

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault()
        setCommandOpen((value) => !value)
      }
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  }, [])

  const matchesPage = ({ path }: { path: string }) => page === path || page.startsWith(path + "/")
  const visibleGroups = navigationGroups.map((group) => ({
    ...group,
    items: group.items.filter(({ path, permission }) =>
      path === "/openalex" ? canJob("METADATA") : !permission || can(permission)),
  })).filter(({ items }) => items.length > 0)

  const overviewActive = matchesPage(primaryItem)
  const activeGroup = visibleGroups.find(({ items }) => items.some(matchesPage))
  const activeItem = overviewActive ? primaryItem : activeGroup?.items.find(matchesPage)
  const initial = (displayName.trim()[0] ?? "A").toUpperCase()

  function toggleGroup(id: string) {
    setCollapsed((prev) => {
      const next = { ...prev, [id]: !prev[id] }
      try { window.localStorage.setItem("aie.nav.collapsed", JSON.stringify(next)) } catch {}
      return next
    })
  }

  function toggleRail() {
    setRail((prev) => {
      const next = !prev
      try { window.localStorage.setItem("aie.sidebar.rail", next ? "1" : "0") } catch {}
      return next
    })
  }

  return <div className="workspace" data-rail={rail ? "true" : undefined}>
    <aside className="sidebar">
      <div className="sidebar-heading">
        <a className="brand-link" href="/overview" aria-label="AI Economist 首页">
          <Brand subtitle="文献数据工作台" compact={rail} />
        </a>
        <Button className="sidebar-toggle" variant="ghost" size="icon" type="button" aria-label="导航菜单"
          aria-expanded={expanded} aria-controls="sidebar-content" onClick={() => setExpanded(!expanded)}>
          {expanded ? <X size={19} /> : <Menu size={19} />}
        </Button>
      </div>
      <div id="sidebar-content" className="sidebar-content" data-expanded={expanded}>
        <nav className="sidebar-nav" aria-label="管理导航">
          <div className="nav-primary">
            <a href={primaryItem.path} aria-current={overviewActive ? "page" : undefined}
              className="nav-link" title={rail ? primaryItem.label : undefined}>
              <LayoutDashboard size={17} strokeWidth={1.7} aria-hidden="true" />
              <span className="nav-link-label">{primaryItem.label}</span>
            </a>
          </div>
          {visibleGroups.map((group) => {
            const isOpen = rail || !collapsed[group.id] || activeGroup?.id === group.id
            return <div className="nav-group" key={group.id} data-collapsed={!isOpen ? "true" : undefined}>
              {rail ? <div className="nav-rail-divider" aria-hidden="true" /> :
                <button type="button" className="nav-group-toggle" aria-expanded={isOpen}
                  aria-controls={"nav-group-" + group.id} onClick={() => toggleGroup(group.id)}>
                  <span>{group.label}</span>
                  {isOpen ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
                </button>}
              <div id={"nav-group-" + group.id} className="nav-group-items" hidden={!isOpen}>
                {group.items.map(({ path, label, icon: Icon, badge }) =>
                  <a key={path} href={path} aria-current={path === activeItem?.path ? "page" : undefined}
                    className="nav-link" title={rail ? label : undefined}>
                    <Icon size={17} strokeWidth={1.7} aria-hidden="true" />
                    <span className="nav-link-label">{label}</span>
                    {badge && !rail && <span className="nav-badge">{badge}</span>}
                  </a>
                )}
              </div>
            </div>
          })}
        </nav>
        <div className="sidebar-footer">
          <button type="button" className="rail-toggle" onClick={toggleRail}
            aria-label={rail ? "展开侧边栏" : "收起侧边栏"} title={rail ? "展开侧边栏" : "收起侧边栏"}>
            {rail ? <PanelLeftOpen size={16} aria-hidden="true" /> : <><PanelLeftClose size={16} aria-hidden="true" /><span>收起侧栏</span></>}
          </button>
          <details className="account-menu">
            <summary title={rail ? displayName : undefined}>
              <span className="account-avatar" aria-hidden="true">{initial}</span>
              {!rail && <><span className="account-name">{displayName}</span><ChevronDown size={15} className="account-chevron" aria-hidden="true" /></>}
            </summary>
            <div className="account-popover">
              <button type="button" onClick={() => setPassword(true)}><ShieldCheck size={15} aria-hidden="true" />修改密码</button>
              <button type="button" onClick={logout}><LogOut size={15} aria-hidden="true" />退出登录</button>
            </div>
          </details>
        </div>
      </div>
    </aside>
    <div className="workspace-main">
      <header className="topbar">
        <div className="topbar-inner">
          {activeItem ? <div className="breadcrumb">
            <span>{activeGroup ? activeGroup.label : "工作台"}</span>
            <span className="breadcrumb-sep" aria-hidden="true">/</span>
            <strong>{activeItem.label}</strong>
          </div> : <div />}
          <button type="button" className="command-trigger" onClick={() => setCommandOpen(true)}
            aria-label="快速搜索与跳转">
            <Search size={14} aria-hidden="true" />
            <span className="command-trigger-text">搜索期刊、论文或快速跳转…</span>
            <kbd className="kbd-pill" aria-hidden="true">⌘K</kbd>
          </button>
        </div>
      </header>
      <main className="page-content">{children}</main>
    </div>
    <CommandPalette open={commandOpen} onClose={() => setCommandOpen(false)} />
    {password && <Modal title="修改密码" description="密码更新后请重新登录。" onClose={() => setPassword(false)}>
      <PasswordForm cancel={() => setPassword(false)} done={() => window.location.assign("/")} />
    </Modal>}
  </div>
}

export function SectionHeader({ title, description, actions, back, editorial }:
  { title: string; description?: string; actions?: ReactNode; back?: ReactNode; editorial?: boolean }) {
  return <section className={"section-header" + (editorial ? " section-header-editorial" : "")}>
    <div className="section-header-copy">
      {back}
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </div>
    {actions && <div className="section-actions">{actions}</div>}
  </section>
}
