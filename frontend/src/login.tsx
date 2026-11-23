import { Fingerprint, PanelTop } from "lucide-react"
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
      if (window.location.pathname === "/") window.location.assign("/papers")
      else window.location.reload()
    } catch (reason) {
      setError(errorMessage(reason))
    } finally { setBusy(false) }
  }

  return <main className="login-page">
    <section className="login-card panel">
      <Brand />
      <h1>登录</h1>
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
    </section>
  </main>
}
