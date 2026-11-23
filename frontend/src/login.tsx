import { ArrowRight, KeyRound, PanelTop, ShieldCheck } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, errorMessage } from "./api"
import { PasswordForm } from "./components/password"
import { Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { Brand } from "./components/workspace"

const pillars = [
  ["01", "原始文献典藏", "统一接续历史典藏与新增 PDF/XML/HTML 原件，保留稳定论文标识与完整修订谱系。"],
  ["02", "结构化全文流水线", "通过 MinerU 提取章节、图表与公式块，按事务发布可核验的结构化全文。"],
  ["03", "OpenAlex 书目关联", "按出版来源同步作品快照，优先 DOI 与书目精确匹配，多候选支持人工并排复核。"],
] as const

function EditorialBackdrop() {
  return <aside className="auth-editorial" aria-hidden="true">
    <div className="auth-editorial-inner">
      <div className="auth-kicker">
        <span className="auth-kicker-bar" />
        <span>ECONOMICS LITERATURE INFRASTRUCTURE</span>
      </div>
      <div className="auth-statement">
        <p className="auth-headline">经济学文献语料与<br />结构化全文工作台</p>
        <p className="auth-lead">面向研究数据管理员打造的专业文献治理空间，覆盖原件导入、解析发布、书目核验与团队权限审计。</p>
      </div>
      <ol className="auth-pillars">
        {pillars.map(([index, title, summary]) => <li key={index}>
          <span className="auth-pillar-index">{index}</span>
          <div><strong>{title}</strong><p>{summary}</p></div>
        </li>)}
      </ol>
      <div className="auth-colophon">
        <span>CORPUS CURATION &amp; ARCHIVAL GOVERNANCE</span>
        <span>V2 WORKSPACE</span>
      </div>
    </div>
  </aside>
}

export function Login({ serviceError }: { serviceError?: string }) {
  const [selected, setSelected] = useState<"admin" | "client">("admin")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setError("")
    const credentials = Object.fromEntries(new FormData(event.currentTarget))
    try {
      await api("/session", { method: "POST", body: JSON.stringify(credentials) })
      if (window.location.pathname === "/") window.location.assign("/overview")
      else window.location.reload()
    } catch (reason) {
      setError(errorMessage(reason))
    } finally { setBusy(false) }
  }

  return <main className="login-page">
    <EditorialBackdrop />
    <div className="auth-form-stage">
      <section className="login-card panel">
        <div className="login-card-brand"><Brand subtitle="文献数据工作台" /></div>
        <div className="login-card-intro">
          <h1>登录</h1>
          <p className="muted">选择工作入口并使用管理员分配的账号进入。</p>
        </div>
        <div className="login-tabs" role="tablist" aria-label="登录入口">
          <button type="button" role="tab" aria-controls="admin-login" aria-selected={selected === "admin"} onClick={() => setSelected("admin")}>
            <ShieldCheck size={16} aria-hidden="true" />管理端
          </button>
          <button type="button" role="tab" aria-controls="client-login" aria-selected={selected === "client"} onClick={() => setSelected("client")}>
            <PanelTop size={16} aria-hidden="true" />客户端
          </button>
        </div>
        {selected === "admin" ? <form id="admin-login" role="tabpanel" className="form-stack" onSubmit={submit}>
          {(error || serviceError) && <Notice error>{error || serviceError}</Notice>}
          <label>用户名<input className="field" name="username" placeholder="输入用户名" required autoComplete="username" /></label>
          <label>密码<input className="field" name="password" placeholder="输入密码" type="password" required autoComplete="current-password" /></label>
          <Button disabled={busy} type="submit" className="login-submit">
            {busy ? "正在登录…" : "登录管理端"}{!busy && <ArrowRight size={16} aria-hidden="true" />}
          </Button>
        </form> : <div id="client-login" role="tabpanel" className="client-entry">
          <span className="client-entry-icon" aria-hidden="true"><PanelTop size={22} strokeWidth={1.6} /></span>
          <strong>客户端待开放</strong>
          <p>论文数据管理员可以通过管理端登录。</p>
        </div>}
      </section>
    </div>
  </main>
}

export function FirstPassword({ logout }: { logout: () => void }) {
  return <main className="login-page">
    <EditorialBackdrop />
    <div className="auth-form-stage">
      <section className="login-card panel">
        <div className="login-card-brand"><Brand subtitle="文献数据工作台" /></div>
        <div className="login-card-intro">
          <span className="auth-step-badge" aria-hidden="true"><KeyRound size={14} />首次登录安全设置</span>
          <h1>设置个人密码</h1>
          <p className="muted">设置新密码后，请重新登录进入工作区。</p>
        </div>
        <PasswordForm temporary cancel={logout} done={() => window.location.reload()} />
      </section>
    </div>
  </main>
}
