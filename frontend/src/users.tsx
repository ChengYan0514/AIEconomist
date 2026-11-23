import { Plus, Search } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, errorMessage, params, useQuery } from "./api"
import { Empty, Loading, Modal, Notice, Pagination } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import { roles, type Role } from "./permissions"
import type { Page } from "./types"

type Status = "ACTIVE" | "DISABLED"
type User = { username: string; displayName: string; role: Role; status: Status; createdAt: string }
export type IssuedPassword = { user: User; temporaryPassword: string }
type UserAction = { user: User; action: "status" | "reset" }
const size = 20
const initialFilters = { q: "", role: "", status: "" }

export function Users({ onChange, onPasswordReset }: { onChange: () => void; onPasswordReset: (issued: IssuedPassword) => void }) {
  const [filters, setFilters] = useState(() => ({ ...initialFilters, q: new URLSearchParams(window.location.search).get("q") ?? "" }))
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<User | "new" | null>(null)
  const [managing, setManaging] = useState<UserAction | null>(null)
  const result = useQuery<Page<User>>("/users" + params({ ...filters, page, size }))
  const rows = result.data?.items ?? []

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const values = new FormData(event.currentTarget)
    setFilters({ q: String(values.get("q")), role: String(values.get("role")), status: String(values.get("status")) })
    setPage(0)
  }

  function updated() {
    result.reload()
    onChange()
  }

  return <>
    <SectionHeader title="用户管理" actions={<Button onClick={() => setEditing("new")}><Plus size={17} />新建用户</Button>} />
    <form className="user-filters" onSubmit={search}>
      <label className="search-box"><Search size={17} />
        <input className="field" name="q" type="search" aria-label="搜索用户" maxLength={100} defaultValue={filters.q}
          placeholder="搜索用户名或显示名称" />
      </label>
      <select className="field" name="role" aria-label="筛选角色"><option value="">全部角色</option><RoleOptions /></select>
      <select className="field" name="status" aria-label="筛选状态">
        <option value="">全部状态</option><option value="ACTIVE">启用</option><option value="DISABLED">停用</option>
      </select>
      <Button type="submit" variant="outline">查询</Button>
    </form>
    <section className="panel">
      {result.loading ? <Loading /> : result.error ?
        <div className="p-5"><Notice error>{result.error.message}</Notice><Button className="mt-3" variant="outline" onClick={result.reload}>重新加载</Button></div> :
        rows.length === 0 ? <Empty title="没有找到用户" /> :
          <div className="table-scroll" role="region" aria-label="用户列表表格" tabIndex={0}>
            <table className="data-table user-table">
              <thead><tr><th scope="col">用户</th><th scope="col">角色</th><th scope="col">状态</th>
                <th scope="col">创建时间</th><th scope="col">操作</th></tr></thead>
              <tbody>{rows.map((user) => <tr key={user.username}>
                <td className="title-cell"><strong>{user.displayName}</strong><small>{user.username}</small></td>
                <td>{roles[user.role]}</td>
                <td><span className={"status-chip status-" + (user.status === "ACTIVE" ? "green" : "neutral")}>
                  <span className="status-dot" />{user.status === "ACTIVE" ? "启用" : "停用"}
                </span></td>
                <td className="table-date"><time dateTime={user.createdAt}>
                  {new Date(user.createdAt).toLocaleString("zh-CN", { hour12: false })}
                </time></td>
                <td><div className="flex flex-wrap gap-1">
                  <Button type="button" variant="ghost" size="sm" aria-label={"编辑 " + user.username}
                    onClick={() => setEditing(user)}>编辑</Button>
                  <Button type="button" variant="ghost" size="sm"
                    aria-label={(user.status === "ACTIVE" ? "停用 " : "恢复 ") + user.username}
                    onClick={() => setManaging({ user, action: "status" })}>{user.status === "ACTIVE" ? "停用" : "恢复"}</Button>
                  <Button type="button" variant="ghost" size="sm" aria-label={"重置密码 " + user.username}
                    onClick={() => setManaging({ user, action: "reset" })}>重置密码</Button>
                </div></td>
              </tr>)}</tbody>
            </table>
          </div>}
      {result.data && !result.error && <Pagination page={page} size={size} total={result.data.total} change={setPage} />}
    </section>
    {editing && <UserForm user={editing === "new" ? undefined : editing} close={() => setEditing(null)} updated={updated} />}
    {managing && <UserActionDialog {...managing} close={() => setManaging(null)} updated={updated} onPasswordReset={onPasswordReset} />}
  </>
}

function RoleOptions() {
  return <>{Object.entries(roles).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</>
}

function UserForm({ user, close, updated }: { user?: User; close: () => void; updated: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [created, setCreated] = useState<IssuedPassword>()
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = Object.fromEntries(new FormData(event.currentTarget))
    setBusy(true)
    setError("")
    try {
      if (user) {
        await api("/users/" + encodeURIComponent(user.username), { method: "PATCH", body: JSON.stringify(form) })
        updated()
        close()
      } else {
        setCreated(await api<IssuedPassword>("/users", { method: "POST", body: JSON.stringify(form) }))
        updated()
      }
    } catch (cause) { setError(errorMessage(cause)) }
    finally { setBusy(false) }
  }

  return <Modal title={created ? "用户已创建" : user ? "编辑用户" : "新建用户"}
    onClose={() => { if (!busy) close() }}>
    {created ? <PasswordNotice issued={created} close={close} /> : <form className="modal-body form-stack" onSubmit={save}>
      {error && <Notice error>{error}</Notice>}
      {user ? <label>用户名<input className="field" readOnly value={user.username} /></label> :
        <label>用户名<input className="field" name="username" required minLength={3} maxLength={50}
          pattern="[a-zA-Z0-9_.\\-]{3,50}" title="3–50 位字母、数字或 ._-" autoComplete="off" /></label>}
      <label>显示名称<input className="field" name="displayName" defaultValue={user?.displayName} required maxLength={100} /></label>
      <label>角色<select className="field" name="role" aria-label="角色" defaultValue={user?.role ?? "READ_ONLY"}><RoleOptions /></select></label>
      <div className="modal-actions">
        <Button type="button" variant="outline" disabled={busy} onClick={close}>取消</Button>
        <Button disabled={busy}>{user ? "保存修改" : "创建账号"}</Button>
      </div>
    </form>}
  </Modal>
}

function UserActionDialog({ user, action, close, updated, onPasswordReset }: UserAction & {
  close: () => void; updated: () => void; onPasswordReset: (issued: IssuedPassword) => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const disabling = user.status === "ACTIVE"
  const label = action === "reset" ? "重置" : disabling ? "停用" : "恢复"
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setError("")
    const path = "/users/" + encodeURIComponent(user.username)
    try {
      if (action === "reset") {
        const issued = await api<IssuedPassword>(path + "/reset-password", { method: "POST" })
        close()
        onPasswordReset(issued)
      } else {
        await api(path + "/status", { method: "PUT", body: JSON.stringify({ status: disabling ? "DISABLED" : "ACTIVE" }) })
        close()
        updated()
      }
    } catch (cause) { setError(errorMessage(cause)) }
    finally { setBusy(false) }
  }

  return <Modal title={action === "reset" ? "重置用户密码" : label + "用户"} onClose={() => { if (!busy) close() }}>
    <form className="modal-body form-stack" onSubmit={submit}>
      {error && <Notice error>{error}</Notice>}
      <p>{user.displayName} <span className="mono muted">{user.username}</span></p>
      <p className="muted">{action === "reset" ? "旧密码和会话将失效。账号启用时，用户可以用新临时密码登录并设置个人密码。" :
        disabling ? "该用户的会话将立即失效，已提交的任务会继续处理。" : "该用户可以重新登录。"}</p>
      <div className="modal-actions">
        <Button type="button" variant="outline" disabled={busy} onClick={close}>取消</Button>
        <Button disabled={busy}>{"确认" + label}</Button>
      </div>
    </form>
  </Modal>
}

export function ResetPasswordNotice({ issued, close }: { issued: IssuedPassword; close: () => void }) {
  return <Modal title="密码已重置" onClose={close}><PasswordNotice issued={issued} close={close} /></Modal>
}

function PasswordNotice({ issued, close }: { issued: IssuedPassword; close: () => void }) {
  return <div className="modal-body form-stack">
    <Notice>请现在保存临时密码并交给 {issued.user.displayName}。关闭后无法再次查看。</Notice>
    <label>临时密码<input className="field mono" readOnly value={issued.temporaryPassword}
      onFocus={(event) => event.currentTarget.select()} /></label>
    <div className="modal-actions"><Button onClick={close}>完成</Button></div>
  </div>
}
