import {
  ArrowLeftRight,
  BookCheck,
  ChevronDown,
  FlaskConical,
  LogOut,
  Menu,
  MessageSquareText,
  PanelLeftClose,
  PanelLeftOpen,
  PenTool,
  Radio,
  ShieldCheck,
  X,
} from "lucide-react"
import { useEffect, useState, type ReactNode } from "react"
import { PasswordForm } from "../components/password"
import { Modal } from "../components/shared"
import { Button } from "../components/ui/button"
import { Brand } from "../components/workspace"
import { usePermissions } from "../permissions"
import {
  getClientMode,
  listClientModules,
  setManualClientMock,
  subscribeClientMode,
  type ClientBackendMode,
} from "./api"
import type { ClientModule, ClientModuleId, ClientModuleStatus } from "./types"
import "./client.css"

const MODULE_META: Record<ClientModuleId, { path: string; fallbackName: string; icon: typeof BookCheck }> = {
  citations: { path: "/client/citations", fallbackName: "参考文献自动标注", icon: BookCheck },
  qa: { path: "/client/qa", fallbackName: "学科知识问答", icon: MessageSquareText },
  writing: { path: "/client/writing", fallbackName: "论文写作智能体", icon: PenTool },
}

const DEFAULT_MODULES: ClientModule[] = [
  { id: "citations", name: "参考文献自动标注", description: "", status: "AVAILABLE", provider: null, message: null },
  { id: "qa", name: "学科知识问答", description: "", status: "COMING_SOON", provider: null, message: null },
  { id: "writing", name: "论文写作智能体", description: "", status: "COMING_SOON", provider: null, message: null },
]

function statusPill(status: ClientModuleStatus): { label: string; tone: string } {
  if (status === "AVAILABLE") return { label: "已就绪", tone: "green" }
  if (status === "STUB") return { label: "演示", tone: "blue" }
  if (status === "UNAVAILABLE") return { label: "未连接", tone: "red" }
  return { label: "待接入", tone: "neutral" }
}

function readClientRail(): boolean {
  try { return window.localStorage.getItem("aie.client.rail") === "1" }
  catch { return false }
}

export function ClientShell({
  page,
  displayName,
  logout,
  children,
}: {
  page: string
  displayName: string
  logout: () => void
  children: (ctx: { modules: ClientModule[]; mode: ClientBackendMode }) => ReactNode
}) {
  const { hasAdminAccess } = usePermissions()
  const [rail, setRail] = useState(readClientRail)
  const [expanded, setExpanded] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [mode, setMode] = useState<ClientBackendMode>(getClientMode)
  const [modules, setModules] = useState<ClientModule[]>(DEFAULT_MODULES)

  useEffect(() => { setExpanded(false) }, [page])
  useEffect(() => subscribeClientMode(() => setMode(getClientMode())), [])

  useEffect(() => {
    let active = true
    listClientModules()
      .then((list) => { if (active && list.length) setModules(list) })
      .catch(() => {})
    return () => { active = false }
  }, [mode])

  function toggleRail() {
    setRail((prev) => {
      const next = !prev
      try { window.localStorage.setItem("aie.client.rail", next ? "1" : "0") } catch {}
      return next
    })
  }

  const initial = (displayName.trim()[0] ?? "R").toUpperCase()
  const activeModule = modules.find((m) => page === MODULE_META[m.id].path || page.startsWith(MODULE_META[m.id].path + "/"))
    ?? modules[0]

  return <div className="client-shell" data-rail={rail ? "true" : undefined}>
    <aside className="client-rail">
      <div className="client-rail-head">
        <a className="brand-link" href="/client/citations" aria-label="AI Economist 科研客户端首页">
          <Brand subtitle="科研工作台" compact={rail} />
        </a>
        <Button className="client-mobile-toggle" variant="ghost" size="icon" type="button"
          aria-label="客户端导航菜单" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}>
          {expanded ? <X size={19} /> : <Menu size={19} />}
        </Button>
      </div>

      <div className="client-rail-body" data-expanded={expanded}>
        <nav className="client-nav" aria-label="客户端模块导航">
          {!rail && <div className="client-nav-heading">科研模块</div>}
          {modules.map((mod) => {
            const meta = MODULE_META[mod.id]
            if (!meta) return null
            const Icon = meta.icon
            const active = page === meta.path || page.startsWith(meta.path + "/")
            const badge = statusPill(mod.status)
            return <a key={mod.id} href={meta.path} aria-current={active ? "page" : undefined}
              className="client-nav-item" title={rail ? (mod.name || meta.fallbackName) : undefined}>
              <Icon size={17} strokeWidth={1.75} aria-hidden="true" />
              {!rail && <>
                <span className="client-nav-label">{mod.name || meta.fallbackName}</span>
                <span className={"client-mod-badge is-" + badge.tone}>{badge.label}</span>
              </>}
            </a>
          })}
        </nav>

        <div className="client-rail-footer">
          <button type="button" className={"client-mode-toggle" + (mode !== "live" ? " is-mock" : "")}
            onClick={() => setManualClientMock(mode === "live")}
            title={mode === "live" ? "当前直连后端 /api/client，点击切换前端契约演示模式" : "当前处于契约演示模式，点击切回真实后端"}>
            {mode === "live" ? <Radio size={15} aria-hidden="true" /> : <FlaskConical size={15} aria-hidden="true" />}
            {!rail && <span>{mode === "live" ? "真实后端模式" : mode === "mock-fallback" ? "契约演示（待合入）" : "契约演示模式"}</span>}
          </button>

          {hasAdminAccess && <a href="/overview" className="client-switch-link" title={rail ? "切换至数据管理端" : undefined}>
            <ArrowLeftRight size={15} aria-hidden="true" />
            {!rail && <span>切换至数据管理端</span>}
          </a>}

          <button type="button" className="rail-toggle" onClick={toggleRail}
            aria-label={rail ? "展开模块栏" : "收起模块栏"} title={rail ? "展开模块栏" : "收起模块栏"}>
            {rail ? <PanelLeftOpen size={16} aria-hidden="true" /> : <><PanelLeftClose size={16} aria-hidden="true" /><span>收起导航</span></>}
          </button>

          <details className="account-menu">
            <summary title={rail ? displayName : undefined}>
              <span className="account-avatar" aria-hidden="true">{initial}</span>
              {!rail && <><span className="account-name">{displayName}</span><ChevronDown size={15} className="account-chevron" aria-hidden="true" /></>}
            </summary>
            <div className="account-popover">
              <button type="button" onClick={() => setPasswordOpen(true)}><ShieldCheck size={15} aria-hidden="true" />修改密码</button>
              <button type="button" onClick={logout}><LogOut size={15} aria-hidden="true" />退出登录</button>
            </div>
          </details>
        </div>
      </div>
    </aside>

    <div className="client-stage">
      <header className="client-topbar">
        <div className="client-topbar-left">
          <span className="client-topbar-kicker">AI Economist 客户端</span>
          <span className="breadcrumb-sep" aria-hidden="true">/</span>
          <strong>{activeModule?.name ?? "科研工作台"}</strong>
        </div>
        <div className="client-topbar-right">
          {mode === "mock-fallback" && (
            <span className="client-mode-pill is-mock" role="status">
              <FlaskConical size={13} aria-hidden="true" />
              <span>后端接口尚未合入，已自动启用契约演示数据</span>
            </span>
          )}
          {mode === "mock-manual" && (
            <span className="client-mode-pill is-mock" role="status">
              <FlaskConical size={13} aria-hidden="true" />
              <span>契约演示模式</span>
              <button type="button" className="client-mode-pill-action" onClick={() => setManualClientMock(false)}>切回真实后端</button>
            </span>
          )}
        </div>
      </header>
      <main className="client-stage-body">{children({ modules, mode })}</main>
    </div>

    {passwordOpen && <Modal title="修改密码" description="密码更新后请重新登录。" onClose={() => setPasswordOpen(false)}>
      <PasswordForm cancel={() => setPasswordOpen(false)} done={() => window.location.assign("/")} />
    </Modal>}
  </div>
}
