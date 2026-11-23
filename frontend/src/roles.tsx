import { ArrowLeft, Plus } from "lucide-react"
import { useState, type FormEvent } from "react"
import { api, ApiError, errorMessage, rolePath, useQuery } from "./api"
import { Empty, Loading, Modal, Notice } from "./components/shared"
import { Button } from "./components/ui/button"
import { SectionHeader } from "./components/workspace"
import type { Permission } from "./permissions"

export type RoleSummary = { id: string; name: string; description: string; builtIn: boolean; userCount: number }
type RoleMember = { username: string; displayName: string; status: "ACTIVE" | "DISABLED" }
type RoleDetail = RoleSummary & { permissions: Permission[]; members: RoleMember[] }
type PermissionGroup = { label: string; permissions: { code: Permission; label: string; requires: Permission[] }[] }
export type RoleCatalog = { items: RoleSummary[]; permissionGroups: PermissionGroup[] }

export function Roles({ id, editing }: { id?: string; editing: boolean }) {
  const catalog = useQuery<RoleCatalog>("/roles")
  const role = useQuery<RoleDetail>(id ? rolePath(id) : null)
  const failure = catalog.error ?? role.error
  if (failure) return <>
    <SectionHeader title="角色管理" back={id && <RoleBack />} />
    <Notice error>{failure.message}</Notice>
    <Button className="mt-3" variant="outline" onClick={() => { catalog.reload(); role.reload() }}>重新加载</Button>
  </>
  if (!catalog.data || (id && !role.data)) return <Loading label="正在读取角色…" />
  const groups = catalog.data.permissionGroups
  if (editing && !role.data?.builtIn) {
    return <RoleEditor role={role.data} groups={groups} reload={role.reload} />
  }
  if (role.data) return <RoleDetails role={role.data} groups={groups} />
  return <>
    <SectionHeader title="角色管理" actions={<Button asChild><a href="/roles/new"><Plus size={17} />新建角色</a></Button>} />
    <section className="panel">
      <div className="table-scroll" tabIndex={0} role="region" aria-label="角色列表表格">
        <table className="data-table role-table">
          <thead><tr><th scope="col">角色</th><th scope="col">类型</th><th scope="col">说明</th><th scope="col">已分配用户</th></tr></thead>
          <tbody>{catalog.data.items.map((item) => <tr key={item.id}>
            <td className="title-cell"><a href={rolePath(item.id)}>{item.name}</a></td>
            <td>{item.builtIn ? "内置" : "自定义"}</td><td>{item.description || "—"}</td><td>{item.userCount}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </section>
  </>
}

function RoleBack({ role }: { role?: RoleSummary }) {
  return <a href={role ? rolePath(role.id) : "/roles"} className="back-link">
    <ArrowLeft size={15} />{role ? "返回角色详情" : "返回角色管理"}
  </a>
}

function RoleDetails({ role, groups }: { role: RoleDetail; groups: PermissionGroup[] }) {
  const [deleting, setDeleting] = useState(false)
  return <>
    <SectionHeader title={role.name} description={role.description} back={<RoleBack />}
      actions={!role.builtIn && <>
        <Button asChild variant="outline"><a href={rolePath(role.id) + "/edit"}>编辑角色</a></Button>
        <Button type="button" variant="outline" disabled={role.userCount > 0} onClick={() => setDeleting(true)}>删除角色</Button>
      </>} />
    <p className="muted mb-4">{role.builtIn ? "内置角色 · 权限固定" : "自定义角色"}</p>
    <section className="panel role-permissions" aria-labelledby="role-permissions-title">
      <h2 id="role-permissions-title" className="panel-bar font-semibold">角色权限</h2>
      {groups.map((group) => {
        const allowed = group.permissions.filter(({ code }) => role.permissions.includes(code))
        return allowed.length > 0 && <div className="role-permission-group" key={group.label}>
          <h3>{group.label}</h3>
          <ul>{allowed.map(({ code, label }) => <li key={code}>{label}</li>)}</ul>
        </div>
      })}
      {role.permissions.includes("SYSTEM_MANAGE") && <div className="role-permission-group">
        <h3>系统管理</h3><p>管理用户和角色、查看系统状态及审计日志</p>
      </div>}
      {role.permissions.length === 0 && <Empty title="没有授权的业务权限" />}
    </section>
    <section className="panel mt-5" aria-labelledby="role-members-title">
      <h2 className="panel-bar font-semibold" id="role-members-title">已分配用户（{role.userCount}）</h2>
      {role.members.length === 0 ? <Empty title="没有已分配用户" /> : <>
        {!role.builtIn && <p className="px-4 py-3 muted">请先为所有成员重新分配角色，包括停用的用户。</p>}
        <div className="table-scroll" tabIndex={0} role="region" aria-label="角色成员表格">
          <table className="data-table role-table">
            <thead><tr><th scope="col">用户</th><th scope="col">用户名</th><th scope="col">状态</th></tr></thead>
            <tbody>{role.members.map((member) => <tr key={member.username}>
              <td className="title-cell"><a href={"/users?q=" + encodeURIComponent(member.username)}>{member.displayName}</a></td>
              <td>{member.username}</td><td>{member.status === "ACTIVE" ? "启用" : "停用"}</td>
            </tr>)}</tbody>
          </table>
        </div>
      </>}
    </section>
    {deleting && <DeleteRole role={role} close={() => setDeleting(false)} />}
  </>
}

function RoleEditor({ role, groups, reload }: { role?: RoleDetail; groups: PermissionGroup[]; reload: () => void }) {
  const [permissions, setPermissions] = useState(new Set(role?.permissions ?? []))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const options = groups.flatMap((group) => group.permissions)
  const labels = new Map(options.map(({ code, label }) => [code, label]))
  const incomplete = options.some(({ code, requires }) => permissions.has(code) && requires.some((required) => !permissions.has(required)))

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = Object.fromEntries(new FormData(event.currentTarget))
    setBusy(true); setError("")
    try {
      const saved = await api<RoleDetail>(role ? rolePath(role.id) : "/roles", {
        method: role ? "PUT" : "POST",
        body: JSON.stringify({ ...fields, permissions: [...permissions], expectedUserCount: role?.userCount }),
      })
      window.location.assign(rolePath(saved.id))
    } catch (reason) {
      setError(errorMessage(reason))
      if (reason instanceof ApiError && reason.status === 409 && role) reload()
    } finally { setBusy(false) }
  }

  return <form onSubmit={save} className="role-editor">
    <SectionHeader title={role ? "编辑角色" : "新建角色"} back={<RoleBack role={role} />} actions={<>
      <Button asChild variant="outline"><a href={role ? rolePath(role.id) : "/roles"}>取消</a></Button>
      <Button disabled={busy || incomplete}>保存角色</Button>
    </>} />
    {error && <Notice error>{error}</Notice>}
    <div className="form-stack role-editor-fields">
      <label>角色名称<input className="field" name="name" defaultValue={role?.name} required maxLength={100} /></label>
      <p role="status" className="muted">保存将影响 {role?.userCount ?? 0} 位用户</p>
      <label>角色说明<textarea className="field" name="description" defaultValue={role?.description} rows={2} maxLength={500} /></label>
    </div>
    <section className="panel role-permissions" aria-labelledby="role-editor-permissions">
      <h2 id="role-editor-permissions" className="panel-bar font-semibold">业务权限</h2>
      {groups.map((group) => <div className="role-permission-group" key={group.label} role="group" aria-label={group.label}>
        <h3>{group.label}</h3>
        <div className="role-permission-options">{group.permissions.map(({ code, label, requires }) =>
          <div key={code}>
            <label><input type="checkbox" checked={permissions.has(code)} aria-describedby={requires.length ? "requires-" + code : undefined}
              onChange={(event) => {
                const next = new Set(permissions)
                if (event.target.checked) next.add(code)
                else next.delete(code)
                setPermissions(next)
              }} /> {label}</label>
            {requires.length > 0 && <small id={"requires-" + code}>依赖：{requires.map((required) => labels.get(required)).join("、")}</small>}
          </div>)}</div>
      </div>)}
    </section>
    {incomplete && <Notice error>请选中所需的依赖权限。</Notice>}
    <p className="muted mt-4">系统管理权限由内置系统管理员持有。</p>
  </form>
}

function DeleteRole({ role, close }: { role: RoleSummary; close: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true); setError("")
    try {
      await api(rolePath(role.id), { method: "DELETE" })
      window.location.assign("/roles")
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Modal title="删除角色" onClose={() => { if (!busy) close() }}>
    <form className="modal-body form-stack" onSubmit={confirm}>
      {error && <Notice error>{error}</Notice>}
      <p>确认删除「{role.name}」？</p>
      <div className="modal-actions">
        <Button type="button" variant="outline" onClick={close} disabled={busy}>取消</Button>
        <Button disabled={busy}>确认删除</Button>
      </div>
    </form>
  </Modal>
}
