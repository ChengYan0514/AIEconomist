import { BookOpen, CircleCheck, FileText, Fingerprint, Layers3, LockKeyhole, PanelTop, ScanText, Server } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, errorMessage } from "./api"
import { Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { Brand } from "./components/workspace"

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
      window.location.assign(window.location.pathname === "/" ? "/papers" : window.location.pathname)
    } catch (reason) {
      setError(errorMessage(reason))
    } finally { setBusy(false) }
  }

  return <main className="login-page">
    <section className="login-showcase">
      <Brand />
      <div className="showcase-content">
        <div className="showcase-copy">
          <h1>论文数据管理</h1>
          <p>在一个工作空间管理原件、解析产物和可查阅的全文。</p>
        </div>
        <div className="showcase-diagram" aria-hidden="true">
          <div className="diagram-top"><span className="diagram-small-icon"><Layers3 size={20} /></span><strong>论文处理流程</strong></div>
          <div className="diagram-row"><span className="diagram-small-icon"><FileText size={18} /></span><div><strong>原始文件</strong><small>单篇上传或批量导入</small></div><CircleCheck size={17} className="diagram-check" /></div>
          <div className="diagram-row"><span className="diagram-small-icon"><ScanText size={18} /></span><div><strong>文件解析</strong><small>生成结构化内容</small></div><CircleCheck size={17} className="diagram-check" /></div>
          <div className="diagram-row"><span className="diagram-small-icon"><BookOpen size={18} /></span><div><strong>全文导入</strong><small>在线查看文字、图表与公式</small></div><CircleCheck size={17} className="diagram-check" /></div>
        </div>
      </div>
      <div className="showcase-footer"><Server size={16} />Paperflow 工作空间</div>
    </section>
    <section className="login-panel">
      <div className="mobile-brand"><Brand compact /></div>
      <div className="login-card">
        <span className="login-lock"><LockKeyhole size={22} /></span>
        <h2>登录 Paperflow</h2>
        <p>选择你要进入的工作空间。</p>
        <div className="login-tabs" role="tablist" aria-label="登录入口">
          <button type="button" role="tab" aria-controls="admin-login" aria-selected={selected === "admin"} onClick={() => setSelected("admin")}><Fingerprint size={17} />管理端</button>
          <button type="button" role="tab" aria-controls="client-login" aria-selected={selected === "client"} onClick={() => setSelected("client")}><PanelTop size={17} />客户端</button>
        </div>
        {selected === "admin" ? <form id="admin-login" role="tabpanel" className="form-stack" onSubmit={submit}>
          {(error || serviceError) && <Notice error>{error || serviceError}</Notice>}
          <label>用户名<input className="field" name="username" placeholder="输入管理员用户名" required autoComplete="username" /></label>
          <label>密码<input className="field" name="password" placeholder="输入密码" type="password" required autoComplete="current-password" /></label>
          <Button disabled={busy} type="submit">{busy ? "正在登录…" : "登录管理端"}</Button>
        </form> : <div id="client-login" role="tabpanel" className="client-entry">
          <PanelTop size={27} /><strong>客户端待开放</strong><p>论文数据管理员可以通过管理端登录。</p>
        </div>}
      </div>
    </section>
  </main>
}
