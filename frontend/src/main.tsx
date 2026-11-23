import { StrictMode, useEffect, useState } from "react"
import { createRoot } from "react-dom/client"
import { api, ApiError, useQuery } from "./api"
import { AuditLog } from "./audit"
import { CausalGraphPreview } from "./causal"
import { Empty, Loading } from "./components/shared"
import { Workspace } from "./components/workspace"
import { Journals } from "./journals"
import { FirstPassword, Login } from "./login"
import { Jobs, JobDetail } from "./jobs"
import { OpenAlex } from "./openalex"
import { Overview } from "./overview"
import { Library } from "./papers/library"
import { PaperDetail } from "./papers/detail"
import { Permissions, type Session } from "./permissions"
import { Roles } from "./roles"
import { useLinkInterceptor, useLocation } from "./router"
import { SystemStatus } from "./system-status"
import { ResetPasswordNotice, Users, type IssuedPassword } from "./users"
import "./styles.css"

function App() {
  useLinkInterceptor()
  const location = useLocation()
  const session = useQuery<Session>("/session")
  const [reset, setReset] = useState<IssuedPassword>()
  const refresh = session.reload
  useEffect(() => {
    window.addEventListener("focus", refresh)
    window.addEventListener("sessionchange", refresh)
    return () => {
      window.removeEventListener("focus", refresh)
      window.removeEventListener("sessionchange", refresh)
    }
  }, [refresh])
  const resetNotice = reset && <ResetPasswordNotice issued={reset} close={() => setReset(undefined)} />
  const signedOut = session.error instanceof ApiError && session.error.status === 401
  if (session.loading && !session.data) return <div className="boot-screen"><Loading label="正在打开 AI Economist…" /></div>
  if (!session.data || signedOut) {
    const showError = session.error && !signedOut
    return <><Login serviceError={showError ? session.error?.message : undefined} />{resetNotice}</>
  }
  const path = location.pathname.replace(/\/$/, "") || "/overview"
  const [section, id, action] = path.slice(1).split("/")
  async function logout() {
    try { await api("/session", { method: "DELETE" }); window.location.assign("/") }
    catch { window.location.reload() }
  }
  if (session.data.mustChangePassword) return <FirstPassword logout={logout} />

  const permissions = session.data.permissions
  const required = section === "papers" || section === "journals" ? "PAPER_READ" :
    section === "jobs" ? "JOB_READ" :
    section === "openalex" ? "OPENALEX_MANAGE" :
    ["system-status", "users", "roles", "audit"].includes(section) ? "SYSTEM_MANAGE" : undefined
  const blocked = (required && !permissions.includes(required)) ||
    (section === "openalex" && !permissions.includes("JOB_READ"))
  const page = blocked ? <section className="empty-state">
    <h1>无权访问此页面</h1><a href="/overview" className="inline-link">返回总览</a>
  </section> :
    section === "overview" && !id ? <Overview /> :
    section === "journals" && !id ? <Journals /> :
    section === "papers" ? id ? <PaperDetail key={id} id={decodeURIComponent(id)} /> : <Library /> :
    section === "jobs" ? id ? <JobDetail key={id} id={decodeURIComponent(id)} /> : <Jobs /> :
    section === "openalex" ? <OpenAlex /> :
    section === "causal-graph" && !id ? <CausalGraphPreview /> :
    section === "system-status" && !id ? <SystemStatus /> :
    section === "users" && !id ? <Users onChange={refresh} onPasswordReset={(issued) => { setReset(issued); refresh() }} /> :
    section === "roles" ? <Roles key={path} id={id && id !== "new" ? decodeURIComponent(id) : undefined} editing={id === "new" || action === "edit"} /> :
    section === "audit" && !id ? <AuditLog /> : <Empty title="找不到此页面"><a href="/overview" className="inline-link">返回总览</a></Empty>
  return <><Permissions.Provider value={permissions}>
    <Workspace page={path} displayName={session.data.displayName} logout={logout}>{page}</Workspace>
  </Permissions.Provider>{resetNotice}</>
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>)
