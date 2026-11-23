import { Archive, BookOpen, BookOpenCheck, ChevronDown, Database, Layers3, LogOut, ShieldCheck, Workflow } from "lucide-react"
import { useState, type FormEvent, type ReactNode } from "react"
import { api, errorMessage } from "../api"
import { Modal, Notice } from "./shared"
import { Button } from "./ui/button"

export function Brand({ compact = false }: { compact?: boolean }) {
  return <div className="brand"><span className="brand-icon"><BookOpen size={21} strokeWidth={2.3} /></span>
    <div><strong>Paperflow</strong>{!compact && <small>论文数据管理</small>}</div>
  </div>
}

const navigation = [
  { path: "/papers", label: "论文库", icon: BookOpenCheck },
  { path: "/jobs", label: "导入与处理", icon: Workflow },
  { path: "/openalex", label: "OpenAlex", icon: Database },
]

export function Workspace({ page, username, children, logout }:
  { page: string; username: string; children: ReactNode; logout: () => void }) {
  const [password, setPassword] = useState(false)
  const active = navigation.find((item) => page.startsWith(item.path)) ?? navigation[0]
  return <div className="workspace">
    <aside className="sidebar">
      <a className="brand-link" href="/papers" aria-label="Paperflow 首页"><Brand /></a>
      <nav className="sidebar-nav" aria-label="管理导航">
        {navigation.map(({ path, label, icon: Icon }) =>
          <a key={path} href={path} aria-current={path === active.path ? "page" : undefined}
            className={"nav-link " + (path === active.path ? "nav-active" : "")}><Icon size={18} /><span>{label}</span></a>
        )}
      </nav>
      <div className="sidebar-bottom"><ShieldCheck size={16} /><span>管理工作空间</span></div>
    </aside>
    <div className="workspace-main">
      <header className="topbar">
        <div className="breadcrumb"><Layers3 size={17} /><span>工作空间</span><span className="crumb-divider">/</span><strong>{active.label}</strong></div>
        <details className="account-menu">
          <summary><span className="avatar">{username.slice(0, 1).toUpperCase()}</span><span>{username}</span><ChevronDown size={15} /></summary>
          <div className="account-popover">
            <button type="button" onClick={() => setPassword(true)}><ShieldCheck size={16} />修改密码</button>
            <button type="button" onClick={logout}><LogOut size={16} />退出登录</button>
          </div>
        </details>
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

export function ArchiveIcon({ archived }: { archived: boolean }) {
  return archived ? <Archive size={17} /> : <BookOpenCheck size={17} />
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
