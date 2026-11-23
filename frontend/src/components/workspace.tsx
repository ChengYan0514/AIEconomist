import { BookOpen, ChevronDown, ClipboardList, Database, FileText, LogOut, Menu, Shield, ShieldCheck, Upload, UserRound, UsersRound, X } from "lucide-react"
import { useState, type ReactNode } from "react"
import { usePermissions, type Permission } from "../permissions"
import { PasswordForm } from "./password"
import { Modal } from "./shared"
import { Button } from "./ui/button"

export function Brand() {
  return <div className="brand"><BookOpen size={23} strokeWidth={1.8} aria-hidden="true" />
    <strong>AI Economist</strong>
  </div>
}

const navigation: { label: string; items: { path: string; label: string; icon: typeof FileText; permission: Permission }[] }[] = [{
  label: "数据工作区",
  items: [
    { path: "/papers", label: "论文库", icon: FileText, permission: "PAPER_READ" },
    { path: "/jobs", label: "导入与处理", icon: Upload, permission: "JOB_READ" },
    { path: "/openalex", label: "OpenAlex", icon: Database, permission: "OPENALEX_MANAGE" },
  ],
}, {
  label: "系统管理",
  items: [
    { path: "/users", label: "用户管理", icon: UsersRound, permission: "SYSTEM_MANAGE" },
    { path: "/roles", label: "角色管理", icon: Shield, permission: "SYSTEM_MANAGE" },
    { path: "/audit", label: "审计日志", icon: ClipboardList, permission: "SYSTEM_MANAGE" },
  ],
}]

export function Workspace({ page, displayName, children, logout }:
  { page: string; displayName: string; children: ReactNode; logout: () => void }) {
  const { can, canJob } = usePermissions()
  const [password, setPassword] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const matchesPage = ({ path }: { path: string }) => page === path || page.startsWith(path + "/")
  const visibleNavigation = navigation.map(({ label, items }) => ({
    label, items: items.filter(({ path, permission }) => path === "/openalex" ? canJob("METADATA") : can(permission)),
  })).filter(({ items }) => items.length > 0)
  const group = visibleNavigation.find(({ items }) => items.some(matchesPage))
  const active = group?.items.find(matchesPage)
  return <div className="workspace">
    <aside className="sidebar">
      <div className="sidebar-heading">
        <a className="brand-link" href="/papers" aria-label="AI Economist 首页"><Brand /></a>
        <Button className="sidebar-toggle" variant="ghost" size="icon" type="button" aria-label="导航菜单"
          aria-expanded={expanded} aria-controls="sidebar-content" onClick={() => setExpanded(!expanded)}>
          {expanded ? <X size={21} /> : <Menu size={21} />}
        </Button>
      </div>
      <div id="sidebar-content" className="sidebar-content" data-expanded={expanded}>
        <nav className="sidebar-nav" aria-label="管理导航">
          {visibleNavigation.map(({ label, items }) => <div className="nav-group" key={label}>
            <h2>{label}</h2>
            {items.map(({ path, label, icon: Icon }) =>
              <a key={path} href={path} aria-current={path === active?.path ? "page" : undefined} className="nav-link">
                <Icon size={19} strokeWidth={1.8} />{label}
              </a>
            )}
          </div>)}
        </nav>
        <details className="account-menu">
          <summary><UserRound size={19} /><span>{displayName}</span><ChevronDown size={16} /></summary>
          <div className="account-popover">
            <button type="button" onClick={() => setPassword(true)}><ShieldCheck size={16} />修改密码</button>
            <button type="button" onClick={logout}><LogOut size={16} />退出登录</button>
          </div>
        </details>
      </div>
    </aside>
    <div className="workspace-main">
      <header className="topbar">
        {group && active && <div className="breadcrumb"><span>{group.label}</span><span aria-hidden="true">/</span><strong>{active.label}</strong></div>}
      </header>
      <main className="page-content">{children}</main>
    </div>
    {password && <Modal title="修改密码" description="密码更新后请重新登录。" onClose={() => setPassword(false)}>
      <PasswordForm cancel={() => setPassword(false)} done={() => window.location.assign("/")} />
    </Modal>}
  </div>
}

export function SectionHeader({ title, description, actions, back }:
  { title: string; description?: string; actions?: ReactNode; back?: ReactNode }) {
  return <section className="section-header">
    <div className="min-w-0">{back}<h1>{title}</h1>{description && <p>{description}</p>}</div>{actions && <div className="section-actions">{actions}</div>}
  </section>
}
