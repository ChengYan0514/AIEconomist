import { BookOpen, ChevronDown, Database, FileText, LogOut, Menu, ShieldCheck, Upload, UserRound, X } from "lucide-react"
import { useState, type FormEvent, type ReactNode } from "react"
import { api, errorMessage } from "../api"
import { Modal, Notice } from "./shared"
import { Button } from "./ui/button"

export function Brand() {
  return <div className="brand"><BookOpen size={23} strokeWidth={1.8} aria-hidden="true" />
    <strong>AI Economist</strong>
  </div>
}

const navigation = [{
  label: "数据工作区",
  items: [
    { path: "/papers", label: "论文库", icon: FileText },
    { path: "/jobs", label: "导入与处理", icon: Upload },
    { path: "/openalex", label: "OpenAlex", icon: Database },
  ],
}]

export function Workspace({ page, username, children, logout }:
  { page: string; username: string; children: ReactNode; logout: () => void }) {
  const [password, setPassword] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const matchesPage = ({ path }: { path: string }) => page === path || page.startsWith(path + "/")
  const group = navigation.find(({ items }) => items.some(matchesPage))
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
          {navigation.map(({ label, items }) => <div className="nav-group" key={label}>
            <h2>{label}</h2>
            {items.map(({ path, label, icon: Icon }) =>
              <a key={path} href={path} aria-current={path === active?.path ? "page" : undefined} className="nav-link">
                <Icon size={19} strokeWidth={1.8} />{label}
              </a>
            )}
          </div>)}
        </nav>
        <details className="account-menu">
          <summary><UserRound size={19} /><span>{username}</span><ChevronDown size={16} /></summary>
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
    {password && <PasswordModal close={() => setPassword(false)} done={() => window.location.assign("/")} />}
  </div>
}

export function SectionHeader({ title, description, actions, back }:
  { title: string; description?: string; actions?: ReactNode; back?: ReactNode }) {
  return <section className="section-header">
    <div className="min-w-0">{back}<h1>{title}</h1>{description && <p>{description}</p>}</div>{actions && <div className="section-actions">{actions}</div>}
  </section>
}

function PasswordModal({ close, done }: { close: () => void; done: () => void }) {
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setError(""); setBusy(true)
    try {
      await api("/session/password", { method: "POST", body: JSON.stringify(Object.fromEntries(form)) })
      done()
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Modal title="修改密码" description="密码更新后请重新登录。" onClose={close}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <label>当前密码<input className="field" name="currentPassword" type="password" autoComplete="current-password" required /></label>
      <label>新密码<input className="field" name="newPassword" type="password" autoComplete="new-password" minLength={12} required /></label>
      <div className="modal-actions"><Button type="button" variant="outline" onClick={close}>取消</Button><Button disabled={busy}>保存密码</Button></div>
    </form>
  </Modal>
}
