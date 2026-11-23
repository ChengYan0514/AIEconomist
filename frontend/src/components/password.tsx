import { useState, type FormEvent } from "react"
import { api, errorMessage } from "../api"
import { Notice } from "./shared"
import { Button } from "./ui/button"

export function PasswordForm({ temporary, cancel, done }: { temporary?: boolean; cancel: () => void; done: () => void }) {
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setError("")
    setBusy(true)
    try {
      await api("/session/password", { method: "POST", body: JSON.stringify(Object.fromEntries(form)) })
      done()
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <form className={(temporary ? "" : "modal-body ") + "form-stack"} onSubmit={submit}>
    {error && <Notice error>{error}</Notice>}
    <label>{temporary ? "临时密码" : "当前密码"}<input className="field" name="currentPassword" type="password" autoComplete="current-password" required /></label>
    <label>新密码<input className="field" name="newPassword" type="password" autoComplete="new-password" minLength={12} required /></label>
    <div className="modal-actions">
      <Button type="button" variant="outline" disabled={busy} onClick={cancel}>{temporary ? "退出登录" : "取消"}</Button>
      <Button disabled={busy}>保存密码</Button>
    </div>
  </form>
}
